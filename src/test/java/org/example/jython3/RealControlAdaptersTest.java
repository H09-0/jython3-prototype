package org.example.jython3;

import org.example.jython3.api.EmbeddedRuntime;
import org.example.jython3.control.HostCommandChannelAdapter;
import org.example.jython3.control.JvmTelemetryAdapter;
import org.example.jython3.control.ScopedWorkspaceAdapter;
import org.example.jython3.host.CapabilitySet;
import org.example.jython3.host.HostCapability;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.security.ExecutionLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit + field tests for real host-backed ControlDevice adapters behind
 * capability, allowlist, and workspace containment boundaries.
 */
class RealControlAdaptersTest {

    @Test
    @DisplayName("JvmTelemetryAdapter and HostCommandChannelAdapter work behind allowlists and audit log")
    void telemetryAndHostChannelAdapters() {
        var caps = CapabilitySet.builder()
                .allow(HostCapability.CONTROL)
                .allowDevice("jvm.telemetry", "oculix.build")
                .allowCommand("sample", "set_tag", "compile", "ping")
                .build();
        AtomicInteger builds = new AtomicInteger();

        try (var rt = EmbeddedRuntime.builder().capabilities(caps).limits(ExecutionLimits.SANDBOXED).build()) {
            rt.registerDevice(new JvmTelemetryAdapter("jvm.telemetry"));
            var buildChannel = HostCommandChannelAdapter.builder("oculix.build")
                    .initialState("status", new PyStr("idle"))
                    .onCommand("compile", (state, args) -> {
                        String target = args.get("target") instanceof PyStr s ? s.value() : "default";
                        int n = builds.incrementAndGet();
                        state.put("status", new PyStr("built:" + target));
                        state.put("count", PyInt.of(n));
                        return PyInt.of(n);
                    })
                    .build();
            rt.registerDevice(buildChannel);

            rt.eval("""
                import control
                t = control.Controller("jvm.telemetry")
                t.send("set_tag", {"tag": "field-check"})
                snap = t.send("sample")
                st_t = t.state()
                assert snap["tag"] == "field-check"
                assert snap["heap_used_bytes"] > 0
                assert st_t.get("processors") >= 1

                b = control.Controller("oculix.build")
                n = b.send("compile", {"target": "x64"})
                assert n == 1
                assert b.state().get("status") == "built:x64"
                ok = True
                """);
            assertEquals(PyBool.TRUE, rt.getGlobal("ok"));
            assertEquals(1, builds.get());

            // Unlisted command on real adapter is still blocked by CapabilitySet
            InterpreterException blocked = assertThrows(InterpreterException.class,
                    () -> rt.eval("control.Controller('oculix.build').send('shell_exec')"));
            assertEquals("SecurityError", blocked.typeName());
            assertTrue(rt.auditEntries().stream().anyMatch(e -> !e.allowed() && e.action().equals("shell_exec")));
        }
    }

    @Test
    @DisplayName("ScopedWorkspaceAdapter requires BOTH CONTROL and FILESYSTEM and blocks traversal/symlinks")
    void scopedWorkspaceContainmentAndCapabilities(@TempDir Path tmp) throws Exception {
        Path workspace = Files.createDirectory(tmp.resolve("ws"));
        Path outside = Files.writeString(tmp.resolve("secret.txt"), "TOP_SECRET");
        Files.writeString(workspace.resolve("readme.txt"), "hello-oculix");
        Path symlinkEscape = workspace.resolve("escape.txt");
        boolean symlinkCreated = false;
        try {
            Files.createSymbolicLink(symlinkEscape, outside);
            symlinkCreated = true;
        } catch (UnsupportedOperationException ignored) {}

        // 1) CONTROL only (no FILESYSTEM) -> SecurityError
        var ctrlOnly = CapabilitySet.builder()
                .allow(HostCapability.CONTROL)
                .allowDevice("ws")
                .allowCommand("read_text", "write_text", "list_dir", "exists")
                .build();
        try (var rt = EmbeddedRuntime.builder().capabilities(ctrlOnly).build()) {
            rt.registerDevice(new ScopedWorkspaceAdapter("ws", workspace, rt.hostSession(), false));
            InterpreterException err = assertThrows(InterpreterException.class,
                    () -> rt.eval("import control\ncontrol.Controller('ws').send('read_text', {'path': 'readme.txt'})"));
            assertEquals("SecurityError", err.typeName());
        }

        // 2) CONTROL + FILESYSTEM -> scoped operations succeed, escapes blocked
        var both = CapabilitySet.builder()
                .allow(HostCapability.CONTROL, HostCapability.FILESYSTEM)
                .allowDevice("ws")
                .allowCommand("read_text", "write_text", "list_dir", "exists")
                .build();
        try (var rt = EmbeddedRuntime.builder().capabilities(both).build()) {
            rt.registerDevice(new ScopedWorkspaceAdapter("ws", workspace, rt.hostSession(), false));
            rt.eval("""
                import control
                ws = control.Controller("ws")
                assert ws.send("read_text", {"path": "readme.txt"}) == "hello-oculix"
                n = ws.send("write_text", {"path": "sub/out.txt", "content": "ok-123"})
                assert n == 6
                assert ws.send("read_text", {"path": "sub/out.txt"}) == "ok-123"
                assert "readme.txt" in ws.send("list_dir", {"path": "."})
                """);
            for (String attack : new String[]{
                    "control.Controller('ws').send('read_text', {'path': '../secret.txt'})",
                    "control.Controller('ws').send('read_text', {'path': '/etc/passwd'})",
                    "control.Controller('ws').send('write_text', {'path': '../pwn.txt', 'content': 'x'})"
            }) {
                assertEquals("SecurityError",
                        assertThrows(InterpreterException.class, () -> rt.eval(attack)).typeName());
            }
            if (symlinkCreated) {
                assertEquals("SecurityError",
                        assertThrows(InterpreterException.class,
                                () -> rt.eval("control.Controller('ws').send('read_text', {'path': 'escape.txt'})"))
                                .typeName());
            }
        }
    }

    @Test
    @DisplayName("Field test: 150 cycles of real telemetry + host channel + scoped workspace")
    void realAdaptersFieldTest(@TempDir Path tmp) throws Exception {
        Path workspace = Files.createDirectory(tmp.resolve("field_ws"));
        var caps = CapabilitySet.builder()
                .allow(HostCapability.CONTROL, HostCapability.FILESYSTEM)
                .allowDevice("jvm.telemetry", "oculix.channel", "ws")
                .allowCommand("sample", "emit", "write_text", "read_text")
                .build();

        try (var rt = EmbeddedRuntime.builder().capabilities(caps).limits(ExecutionLimits.SANDBOXED).build()) {
            rt.registerDevice(new JvmTelemetryAdapter("jvm.telemetry"));
            rt.registerDevice(HostCommandChannelAdapter.builder("oculix.channel")
                    .initialState("seq", PyInt.ZERO)
                    .onCommand("emit", (st, args) -> {
                        PyObject val = args.get("v");
                        st.put("seq", val);
                        return val;
                    })
                    .build());
            rt.registerDevice(new ScopedWorkspaceAdapter("ws", workspace, rt.hostSession(), false));

            for (int i = 0; i < 150; i++) {
                rt.eval("""
                    import control
                    tel = control.Controller("jvm.telemetry").send("sample")
                    assert tel["heap_used_bytes"] > 0
                    ch = control.Controller("oculix.channel")
                    ch.send("emit", {"v": %d})
                    ws = control.Controller("ws")
                    ws.send("write_text", {"path": "cycle.txt", "content": "%d"})
                    assert ws.send("read_text", {"path": "cycle.txt"}) == "%d"
                    """.formatted(i, i, i));
                rt.restart();
            }
            assertEquals(0, rt.hostSession().managedResourceCount());
        }
    }
}
