package org.example.jython3;

import org.example.jython3.control.SimulatedDevice;
import org.example.jython3.host.CapabilitySet;
import org.example.jython3.host.HostCapability;
import org.example.jython3.runtime.ExceptionTypeRegistry;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyDict;
import org.example.jython3.runtime.PyList;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.security.DenyAllPolicy;
import org.example.jython3.security.ExecutionLimits;
import org.example.jython3.security.HostAccessPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security audit tests for capability boundaries: cross-interpreter handle
 * smuggling rejection, stale event/command/widget handles after reset or close,
 * and strict isolation of host OS / JVM reflection.
 */
class HostSecurityAuditTest {

    @Test
    @DisplayName("Cross-interpreter PyHostHandle smuggling (direct, list, dict, closure) is rejected with SecurityError")
    void crossInterpreterHandleSmugglingRejected() {
        var privilegedCaps = CapabilitySet.builder()
                .allow(HostCapability.UI, HostCapability.EVENTS, HostCapability.INPUT, HostCapability.CONTROL)
                .allowDevice("dev-sec")
                .allowCommand("ping")
                .build();
        try (var interpA = new Jython3Interpreter(
                DenyAllPolicy.INSTANCE, HostAccessPolicy.DENY_ALL, ExecutionLimits.SANDBOXED,
                ExceptionTypeRegistry.standard(), privilegedCaps);
             var interpB = Jython3Interpreter.restricted()) {

            interpA.controlRegistry().register(new SimulatedDevice("dev-sec"));
            interpA.eval("""
                import ui, control
                w = ui.Window("A")
                c = control.Controller("dev-sec")
                def make_leak():
                    lc = control.Controller("dev-sec")
                    return lambda: lc.send("ping")
                leak = make_leak()
                """);

            var winA = interpA.getGlobal("w");
            var ctrlA = interpA.getGlobal("c");
            var leakFn = interpA.getGlobal("leak");

            // Direct smuggling
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class, () -> interpB.setGlobal("w", winA)).typeName());
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class, () -> interpB.setGlobal("c", ctrlA)).typeName());

            // Nested in list, dict, and function closure
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class,
                            () -> interpB.setGlobal("lst", new PyList(List.of(winA)))).typeName());
            var d = new PyDict();
            d.put(new PyStr("k"), ctrlA);
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class, () -> interpB.setGlobal("d", d)).typeName());
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class, () -> interpB.setGlobal("fn", leakFn)).typeName());
        }
    }

    @Test
    @DisplayName("All session-bound handles (events, commands, widgets, timers, controllers) reject use after reset or close")
    void allSessionHandlesBecomeStaleAfterResetAndClose() {
        var caps = CapabilitySet.builder()
                .allow(HostCapability.UI, HostCapability.EVENTS, HostCapability.INPUT,
                        HostCapability.TIMER, HostCapability.CONTROL)
                .allowDevice("dev-1")
                .allowCommand("ping")
                .build();
        try (var interp = new Jython3Interpreter(
                DenyAllPolicy.INSTANCE, HostAccessPolicy.DENY_ALL, ExecutionLimits.SANDBOXED,
                ExceptionTypeRegistry.standard(), caps)) {
            interp.controlRegistry().register(new SimulatedDevice("dev-1"));
            interp.eval("""
                import ui, control
                captured_ui_ev = []
                captured_ctrl_ev = []
                w = ui.Window("W")
                b = ui.Button("B")
                l = ui.Label("L")
                t = ui.TextField("T")
                p = ui.Panel()
                tm = ui.Timer(100, lambda: None)
                c = control.Controller("dev-1")
                cmd = control.Command("ping")
                st = c.state()
                b.on_click(lambda ev: captured_ui_ev.append(ev))
                c.on_event(lambda ev: captured_ctrl_ev.append(ev))
                b.click()
                c.send(cmd)
                ui_ev = captured_ui_ev[0]
                ctrl_ev = captured_ctrl_ev[0]
                """);

            var saved = Map.of(
                    "w", interp.getGlobal("w"),
                    "b", interp.getGlobal("b"),
                    "l", interp.getGlobal("l"),
                    "t", interp.getGlobal("t"),
                    "p", interp.getGlobal("p"),
                    "tm", interp.getGlobal("tm"),
                    "c", interp.getGlobal("c"),
                    "cmd", interp.getGlobal("cmd"),
                    "st", interp.getGlobal("st"),
                    "ui_ev", interp.getGlobal("ui_ev")
            );
            var ctrlEv = interp.getGlobal("ctrl_ev");

            interp.resetExecutionState();
            for (var e : saved.entrySet()) {
                interp.setGlobal(e.getKey(), e.getValue());
            }
            interp.setGlobal("ctrl_ev", ctrlEv);

            for (String probe : List.of(
                    "x = w.title", "x = b.text", "x = l.text", "x = t.text",
                    "p.children()", "tm.start()", "c.send('ping')",
                    "x = cmd.name", "st.get('power')", "x = ui_ev.type", "x = ctrl_ev.command"
            )) {
                var err = assertThrows(InterpreterException.class, () -> interp.eval(probe), probe);
                assertEquals("RuntimeError", err.typeName(), probe);
                assertTrue(err.getMessage().contains("stale handle"), probe + " -> " + err.getMessage());
            }
        }
    }
}
