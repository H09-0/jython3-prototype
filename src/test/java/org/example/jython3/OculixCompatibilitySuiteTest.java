package org.example.jython3;

import org.example.jython3.api.EmbeddedRuntime;
import org.example.jython3.api.RuntimeVersion;
import org.example.jython3.control.SimulatedDevice;
import org.example.jython3.host.CapabilitySet;
import org.example.jython3.host.HostCapability;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyBuiltinFunction;
import org.example.jython3.runtime.PyHostHandle;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.security.ExecutionLimits;
import org.example.jython3.ui.HeadlessUiToolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Compatibility suite validating the Oculix / Cinnamon embedded host contract
 * end-to-end against {@link EmbeddedRuntime}.
 */
class OculixCompatibilitySuiteTest {

    @Test
    @DisplayName("Contract: API version negotiation, feature probes, and default-deny isolation")
    void versionNegotiationAndDefaultDeny() {
        try (var rt = EmbeddedRuntime.builder().build()) {
            rt.requireCompatible(new RuntimeVersion(1, 0, 0, ""));
            assertTrue(rt.supportsFeature("capability-gating"));
            assertTrue(rt.supportsFeature("opaque-host-handles"));
            assertTrue(rt.supportsFeature("headless-ui"));
            assertTrue(rt.supportsFeature("swing-edt-ui"));
            assertTrue(rt.supportsFeature("javafx-dispatch-adapter"));
            assertTrue(rt.supportsFeature("callback-timeout-watchdog"));
            assertTrue(rt.supportsFeature("audit-log"));

            // Every privileged capability is denied by default
            for (String probe : List.of(
                    "import ui\nui.Window('No')",
                    "import control\ncontrol.Controller('dev')",
                    "import os\nos.getcwd()",
                    "import io\nio.open('/etc/passwd', 'r')"
            )) {
                InterpreterException ex = assertThrows(InterpreterException.class, () -> rt.eval(probe));
                assertEquals("SecurityError", ex.typeName());
            }
        }
    }

    @Test
    @DisplayName("Contract: plugin lifecycle with project handle, UI events, timer, cancel, restart, and stale-handle guard")
    void pluginLifecycleCancelRestartAndStaleHandles() throws Exception {
        var caps = CapabilitySet.builder()
                .allow(HostCapability.UI, HostCapability.EVENTS, HostCapability.INPUT,
                        HostCapability.TIMER, HostCapability.CONTROL)
                .allowDevice("cinnamon.cnc")
                .allowCommand("set_level", "ping")
                .maxWindows(2)
                .maxTimers(2)
                .maxListenersPerNode(4)
                .callbackTimeout(Duration.ofSeconds(1))
                .build();
        var toolkit = new HeadlessUiToolkit();
        AtomicReference<String> editorStatus = new AtomicReference<>("idle");
        AtomicInteger buildCount = new AtomicInteger(0);

        try (var rt = EmbeddedRuntime.builder()
                .capabilities(caps)
                .limits(ExecutionLimits.SANDBOXED)
                .uiToolkit(toolkit)
                .build()) {
            rt.registerDevice(new SimulatedDevice("cinnamon.cnc"));
            rt.bindModelData("project", Map.of(
                    "name", "OculixWorkspace",
                    "targets", List.of("arm64", "x64")
            ));
            rt.bindHandle("editor", PyHostHandle.builder("oculix.Editor")
                    .bindSession(rt.hostSession())
                    .prop("status", () -> new PyStr(editorStatus.get()), v -> editorStatus.set(v.toString()))
                    .method("trigger_build", new PyBuiltinFunction("trigger_build", args -> {
                        return PyInt.of(buildCount.incrementAndGet());
                    }))
                    .build());

            rt.eval("""
                import ui, control
                win = ui.Window(project["name"])
                lbl = ui.Label("ready")
                inp = ui.TextField("")
                btn = ui.Button("Build")
                ctrl = control.Controller("cinnamon.cnc")
                st = ctrl.state()

                def on_build(ev):
                    num = editor.trigger_build()
                    ctrl.send("set_level", {"value": num * 10})
                    lbl.text = inp.text + ":" + str(st.get("level"))
                    editor.status = lbl.text

                btn.on_click(on_build)
                win.add(lbl); win.add(inp); win.add(btn); win.show()
                inp.text = "x64"
                btn.click()
                assert lbl.text == "x64:10"
                """);

            assertEquals("x64:10", editorStatus.get());
            assertEquals(1, buildCount.get());
            assertEquals(1, toolkit.openWindowCount());

            // Capture a handle before restart and verify it goes stale after restart
            var oldWin = rt.getGlobal("win");
            rt.restart();
            assertEquals(0, toolkit.openWindowCount());
            assertEquals(0, rt.hostSession().managedResourceCount());

            InterpreterException staleErr = assertThrows(InterpreterException.class,
                    () -> rt.bindHandle("stale_win", oldWin instanceof PyHostHandle h ? h : null));
            assertEquals("RuntimeError", staleErr.typeName());

            // Verify mid-script cancellation from host thread
            CountDownLatch started = new CountDownLatch(1);
            AtomicBoolean cancelled = new AtomicBoolean(false);
            rt.bindHandle("sync_probe", PyHostHandle.builder("oculix.Probe")
                    .bindSession(rt.hostSession())
                    .method("signal", new PyBuiltinFunction("signal", args -> {
                        started.countDown();
                        return org.example.jython3.runtime.PyNull.INSTANCE;
                    }))
                    .build());

            try (var exec = Executors.newSingleThreadExecutor()) {
                Future<?> f = exec.submit(() -> {
                    InterpreterException ex = assertThrows(InterpreterException.class, () ->
                            rt.eval("sync_probe.signal()\nwhile True:\n    pass\n"));
                    cancelled.set("KeyboardInterrupt".equals(ex.typeName()));
                });
                assertTrue(started.await(5, TimeUnit.SECONDS));
                rt.cancel();
                f.get(5, TimeUnit.SECONDS);
                assertTrue(cancelled.get());
            }

            // Host runtime remains usable after cancellation + restart
            rt.restart();
            rt.eval("ok = (1 + 2 == 3)");
            assertEquals(PyBool.TRUE, rt.getGlobal("ok"));
            assertFalse(rt.isClosed());
        }
        assertEquals(0, toolkit.openWindowCount());
    }

    @Test
    @DisplayName("Field test: 200 mixed Oculix plugin reload/execute/teardown cycles with zero leaks")
    void compatibility200PluginReloadCyclesFieldTest() {
        var caps = CapabilitySet.builder()
                .allow(HostCapability.UI, HostCapability.EVENTS, HostCapability.INPUT, HostCapability.CONTROL)
                .allowDevice("cinnamon.cnc")
                .allowCommand("set_level", "ping")
                .build();
        var toolkit = new HeadlessUiToolkit();
        try (var rt = EmbeddedRuntime.builder()
                .capabilities(caps)
                .limits(ExecutionLimits.SANDBOXED)
                .uiToolkit(toolkit)
                .build()) {
            rt.registerDevice(new SimulatedDevice("cinnamon.cnc"));
            for (int i = 0; i < 200; i++) {
                rt.bindModelData("ctx", Map.of("id", i));
                rt.eval("""
                    import ui, control
                    w = ui.Window("Plugin-" + str(ctx["id"]))
                    p = ui.Panel()
                    b = ui.Button("Go")
                    c = control.Controller("cinnamon.cnc")
                    def handler(e):
                        c.send("set_level", {"value": ctx["id"]})
                    b.on_click(handler)
                    p.add(b); w.add(p); w.show()
                    b.click()
                    res = c.state().get("level")
                    """);
                assertEquals(PyInt.of(i), rt.getGlobal("res"));
                rt.restart();
            }
            assertEquals(0, toolkit.openWindowCount());
            assertEquals(0, rt.hostSession().managedResourceCount());
        }
    }
}
