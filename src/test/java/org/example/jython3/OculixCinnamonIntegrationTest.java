package org.example.jython3;

import org.example.jython3.control.SimulatedDevice;
import org.example.jython3.host.CapabilitySet;
import org.example.jython3.host.HostAppBridge;
import org.example.jython3.host.HostCapability;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBuiltinFunction;
import org.example.jython3.runtime.PyHostHandle;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.security.ExecutionLimits;
import org.example.jython3.ui.HeadlessUiToolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end host integration tests simulating Oculix/Cinnamon embedding:
 * host model binding, Python UI construction, event callback updating host state
 * and device controllers, mid-event cancellation, and clean interpreter restart.
 */
class OculixCinnamonIntegrationTest {

    @Test
    @DisplayName("Oculix/Cinnamon host workflow: model data, UI events, device commands, cancel, and clean restart")
    void fullHostIntegrationLifecycle() {
        var caps = CapabilitySet.builder()
                .allow(HostCapability.UI, HostCapability.EVENTS, HostCapability.INPUT, HostCapability.CONTROL)
                .allowDevice("stage-x")
                .allowCommand("set_level", "ping")
                .build();
        var toolkit = new HeadlessUiToolkit();
        var device = new SimulatedDevice("stage-x");
        AtomicReference<String> hostStatus = new AtomicReference<>("boot");
        AtomicInteger hostCounter = new AtomicInteger(0);

        try (var bridge = new HostAppBridge(caps, toolkit, ExecutionLimits.SANDBOXED)) {
            bridge.registerDevice(device);
            bridge.bindModelData("project", Map.of(
                    "name", "Oculix-Demo",
                    "targets", List.of(10, 25, 50)
            ));
            bridge.bindHandle("app_state", PyHostHandle.builder("host.AppState")
                    .prop("status", () -> new PyStr(hostStatus.get()), v -> hostStatus.set(v.toString()))
                    .method("increment", new PyBuiltinFunction("increment", a -> PyInt.of(hostCounter.incrementAndGet())))
                    .build());

            // 1. Python script builds UI, handles event, updates UI, model handle, and device
            bridge.runScript("""
                import ui, control

                win = ui.Window(project["name"], width=800, height=600)
                status_lbl = ui.Label("Ready")
                apply_btn = ui.Button("Apply")
                ctrl = control.Controller("stage-x")

                def on_apply(ev):
                    step = app_state.increment()
                    target = project["targets"][step - 1]
                    ctrl.send("set_level", {"value": target})
                    status_lbl.text = "Level=" + str(target)
                    app_state.status = status_lbl.text

                apply_btn.on_click(on_apply)
                win.add(status_lbl)
                win.add(apply_btn)
                win.show()

                apply_btn.click()
                assert status_lbl.text == "Level=10"
                assert app_state.status == "Level=10"
                assert ctrl.state().get("level") == 10

                apply_btn.click()
                assert status_lbl.text == "Level=25"
                assert app_state.status == "Level=25"
                """, "<oculix-plugin>");

            assertEquals(1, toolkit.openWindowCount());
            assertEquals(2, hostCounter.get());
            assertEquals("Level=25", hostStatus.get());
            assertEquals(List.of("set_level", "set_level"), device.commandHistory());

            // 2. Cancelling a script mid-event interrupts cleanly without corrupting bridge
            bridge.bindHandle("cancel_gate", PyHostHandle.builder("host.CancelGate")
                    .method("trip", new PyBuiltinFunction("trip", a -> {
                        bridge.cancelRunningScript();
                        return PyNull.INSTANCE;
                    }))
                    .build());
            bridge.runScript("""
                abort_btn = ui.Button("Abort")
                def on_abort(ev):
                    cancel_gate.trip()
                    app_state.status = "should-not-run"
                abort_btn.on_click(on_abort)
                """, "<oculix-cancel>");

            var cancelErr = assertThrows(InterpreterException.class,
                    () -> bridge.runScript("abort_btn.click()", "<trigger>"));
            assertEquals("KeyboardInterrupt", cancelErr.typeName());
            assertEquals("Level=25", hostStatus.get(), "cancelled callback must not complete mutation");

            // 3. Restarting interpreter closes windows/controllers and clears stale Python globals
            bridge.restart();
            assertEquals(0, toolkit.openWindowCount());
            assertEquals(0, bridge.interpreter().hostSession().managedResourceCount());
            assertNull(bridge.getGlobal("win"));
            assertNull(bridge.getGlobal("project"));

            // Fresh script runs cleanly after restart
            bridge.bindModelData("project", Map.of("name", "Fresh-Session"));
            bridge.runScript("""
                import ui
                w2 = ui.Window(project["name"])
                w2.show()
                assert w2.title == "Fresh-Session"
                """, "<oculix-restarted>");
            assertEquals(1, toolkit.openWindowCount());
        }
        // Bridge close closes everything deterministically
        assertEquals(0, toolkit.openWindowCount());
        assertTrue(bridgeClosedCheck(toolkit));
    }

    @Test
    @DisplayName("HostAppBridge refuses raw arbitrary Java DTOs that are not pure data or PyHostHandle")
    void refusesArbitraryJavaObjects() {
        var bridge = new HostAppBridge(CapabilitySet.none(), new HeadlessUiToolkit(), ExecutionLimits.SANDBOXED);
        try (bridge) {
            var e = assertThrows(InterpreterException.class,
                    () -> bridge.bindModelData("bad", new java.io.File(".")));
            assertEquals("TypeError", e.typeName());
        }
    }

    private static boolean bridgeClosedCheck(HeadlessUiToolkit toolkit) {
        return toolkit.openWindowCount() == 0 && toolkit.activeTimerCount() == 0;
    }
}
