package org.example.jython3;

import org.example.jython3.api.EmbeddedRuntime;
import org.example.jython3.control.HostCommandChannelAdapter;
import org.example.jython3.control.JvmTelemetryAdapter;
import org.example.jython3.control.ScopedWorkspaceAdapter;
import org.example.jython3.host.CapabilitySet;
import org.example.jython3.host.HostCapability;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.security.ExecutionLimits;
import org.example.jython3.ui.HeadlessUiToolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security boundary review tests verifying that privileged host capabilities
 * (hardware/control devices, filesystem, process, network) remain strictly
 * locked down by default and cannot be escalated from UI/event capabilities.
 */
class HostSecurityBoundaryReviewTest {

    @Test
    @DisplayName("UI/Events/Input/Timer capabilities never escalate to Control, Filesystem, Process, or Network")
    void uiCapabilitiesDoNotEscalate() {
        var uiOnly = CapabilitySet.builder()
                .allow(HostCapability.UI, HostCapability.EVENTS, HostCapability.INPUT, HostCapability.TIMER)
                .build();
        assertFalse(uiOnly.has(HostCapability.CONTROL));
        assertFalse(uiOnly.has(HostCapability.FILESYSTEM));
        assertFalse(uiOnly.has(HostCapability.PROCESS));
        assertFalse(uiOnly.has(HostCapability.NETWORK));

        try (var rt = EmbeddedRuntime.builder()
                .capabilities(uiOnly)
                .limits(ExecutionLimits.SANDBOXED)
                .uiToolkit(new HeadlessUiToolkit())
                .build()) {
            rt.registerDevice(new JvmTelemetryAdapter("jvm.telemetry"));
            for (String script : List.of(
                    "import control\ncontrol.Controller('jvm.telemetry')",
                    "import os\nos.getcwd()",
                    "import os\nos.getpid()",
                    "import os\nos.listdir('.')",
                    "import io\nio.open('/etc/passwd', 'r')",
                    "import java.lang.Runtime"
            )) {
                InterpreterException ex = assertThrows(InterpreterException.class, () -> rt.eval(script));
                assertEquals("SecurityError", ex.typeName(), "Expected SecurityError for: " + script);
            }
            // os.environ must be empty under DENY_ALL
            rt.eval("import os\nenv_len = len(os.environ)");
            assertEquals("0", rt.getGlobal("env_len").toString());
        }
    }

    @Test
    @DisplayName("Real control adapters enforce device + command allowlists and reject unlisted actions")
    void realAdaptersEnforceDualAllowlists() {
        var caps = CapabilitySet.builder()
                .allow(HostCapability.CONTROL)
                .allowDevice("jvm.telemetry")
                .allowCommand("ping")
                .build();
        try (var rt = EmbeddedRuntime.builder().capabilities(caps).build()) {
            rt.registerDevice(new JvmTelemetryAdapter("jvm.telemetry"));
            rt.registerDevice(HostCommandChannelAdapter.builder("hardware.cnc")
                    .onCommand("home_axis", (s, a) -> new PyStr("homed"))
                    .build());

            // Unlisted device denied
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class,
                            () -> rt.eval("import control\ncontrol.Controller('hardware.cnc')")).typeName());
            // Allowlisted device + unlisted command denied
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class,
                            () -> rt.eval("import control\ncontrol.Controller('jvm.telemetry').send('sample')"))
                            .typeName());
            // Allowlisted device + allowlisted command succeeds
            rt.eval("import control\nres = control.Controller('jvm.telemetry').send('ping')");
            assertEquals("pong:jvm.telemetry", rt.getGlobal("res").toString());
        }
    }

    @Test
    @DisplayName("ScopedWorkspaceAdapter enforces readOnly, maxBytes, null-byte, and symlink containment")
    void scopedWorkspaceStrictEnforcement(@TempDir Path tmp) throws Exception {
        Path ws = Files.createDirectory(tmp.resolve("ws"));
        Files.writeString(ws.resolve("a.txt"), "hello");
        Files.writeString(ws.resolve("big.txt"), "0123456789ABCDEF");
        Path outside = Files.writeString(tmp.resolve("outside.txt"), "secret");
        Path link = ws.resolve("link.txt");
        boolean hasSymlink = false;
        try {
            Files.createSymbolicLink(link, outside);
            hasSymlink = true;
        } catch (UnsupportedOperationException ignored) {}

        var caps = CapabilitySet.builder()
                .allow(HostCapability.CONTROL, HostCapability.FILESYSTEM)
                .allowDevice("ws_ro")
                .allowCommand("read_text", "write_text", "exists")
                .build();

        try (var rt = EmbeddedRuntime.builder().capabilities(caps).build()) {
            rt.registerDevice(new ScopedWorkspaceAdapter("ws_ro", ws, rt.hostSession(), true, 8));

            // Read within 8-byte cap works
            rt.eval("import control\nv = control.Controller('ws_ro').send('read_text', {'path': 'a.txt'})");
            assertEquals("hello", rt.getGlobal("v").toString());

            // Exceeding maxBytes fails cleanly with ValueError
            assertEquals("ValueError",
                    assertThrows(InterpreterException.class,
                            () -> rt.eval("control.Controller('ws_ro').send('read_text', {'path': 'big.txt'})"))
                            .typeName());

            // Read-only write fails with SecurityError
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class,
                            () -> rt.eval("control.Controller('ws_ro').send('write_text', {'path': 'n.txt', 'content': 'x'})"))
                            .typeName());

            // Null byte fails with ValueError
            assertEquals("ValueError",
                    assertThrows(InterpreterException.class,
                            () -> rt.eval("control.Controller('ws_ro').send('exists', {'path': 'a.txt' + chr(0) + '.png'})"))
                            .typeName());

            // Traversal and symlink escape fail with SecurityError
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class,
                            () -> rt.eval("control.Controller('ws_ro').send('read_text', {'path': '../outside.txt'})"))
                            .typeName());
            if (hasSymlink) {
                assertEquals("SecurityError",
                        assertThrows(InterpreterException.class,
                                () -> rt.eval("control.Controller('ws_ro').send('read_text', {'path': 'link.txt'})"))
                                .typeName());
            }
            assertTrue(rt.auditEntries().stream().anyMatch(e -> !e.allowed()));
        }
    }
}
