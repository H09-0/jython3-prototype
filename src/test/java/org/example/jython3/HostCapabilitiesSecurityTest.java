package org.example.jython3;

import org.example.jython3.control.SimulatedDevice;
import org.example.jython3.host.CapabilitySet;
import org.example.jython3.host.HostCapability;
import org.example.jython3.runtime.ExceptionTypeRegistry;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.security.DenyAllPolicy;
import org.example.jython3.security.ExecutionLimits;
import org.example.jython3.security.HostAccessPolicy;
import org.example.jython3.security.SandboxedPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security tests ensuring deny-by-default host capabilities, per-device/per-command
 * allowlists, audit logging, resource caps, and JVM reflection isolation.
 */
class HostCapabilitiesSecurityTest {

    @Test
    @DisplayName("Authorized simulated control device executes allowlisted commands and logs every attempt")
    void authorizedControlDeviceAndAudit() {
        var caps = CapabilitySet.builder()
                .allow(HostCapability.CONTROL, HostCapability.EVENTS)
                .allowDevice("pump-1")
                .allowCommand("power_on", "set_level", "ping")
                .build();
        try (var interp = new Jython3Interpreter(
                DenyAllPolicy.INSTANCE, HostAccessPolicy.DENY_ALL, ExecutionLimits.SANDBOXED,
                ExceptionTypeRegistry.standard(), caps)) {
            var dev1 = new SimulatedDevice("pump-1");
            var dev2 = new SimulatedDevice("pump-2");
            interp.controlRegistry().register(dev1);
            interp.controlRegistry().register(dev2);

            interp.eval("""
                import control
                events = []
                ctrl = control.Controller("pump-1")
                ctrl.on_event(lambda ev: events.append((ev.type, ev.device_id, ev.command, ev.result)))
                assert ctrl.send("ping") == "pong:pump-1"
                assert ctrl.send("power_on") == True
                cmd = control.Command("set_level", {"value": 75})
                assert ctrl.send(cmd) == 75
                st = ctrl.state()
                assert st.get("power") == True and st.get("level") == 75 and st.to_dict()["mode"] == "idle"
                ok = True
                """);
            assertEquals(PyBool.TRUE, interp.getGlobal("ok"));
            assertEquals(List.of("ping", "power_on", "set_level"), dev1.commandHistory());
            assertTrue(dev2.commandHistory().isEmpty());

            // Unauthorized device and unauthorized command rejected & logged
            var dErr = assertThrows(InterpreterException.class, () -> interp.eval("control.Controller('pump-2')"));
            assertEquals("SecurityError", dErr.typeName());
            var cErr = assertThrows(InterpreterException.class, () -> interp.eval("ctrl.send('reset')"));
            assertEquals("SecurityError", cErr.typeName());

            assertTrue(interp.hostSession().auditLog().allowedCount() >= 5);
            assertTrue(interp.hostSession().auditLog().deniedCount() >= 2);

            // PyHostHandle blocks reflection and read-only mutation
            assertEquals("AttributeError",
                    assertThrows(InterpreterException.class, () -> interp.eval("ctrl.getClass()")).typeName());
            assertEquals("AttributeError",
                    assertThrows(InterpreterException.class, () -> interp.eval("ctrl.device_id = 'hack'")).typeName());
        }
    }

    @Test
    @DisplayName("Granting UI/CONTROL capabilities never unlocks reflection, Runtime, ProcessBuilder, java.io, or java.net")
    void uiAndControlNeverUnlockJvmReflectionOrIo() {
        var caps = CapabilitySet.builder()
                .allow(HostCapability.UI, HostCapability.EVENTS, HostCapability.INPUT, HostCapability.CONTROL)
                .allowDevice("dev-1")
                .allowCommand("ping")
                .build();
        try (var interp = new Jython3Interpreter(
                SandboxedPolicy.get(), HostAccessPolicy.DENY_ALL, ExecutionLimits.SANDBOXED,
                ExceptionTypeRegistry.standard(), caps)) {
            interp.controlRegistry().register(new SimulatedDevice("dev-1"));

            for (String blocked : List.of(
                    "from java.lang import Runtime",
                    "from java.lang import ProcessBuilder",
                    "from java.lang import ClassLoader",
                    "from java.lang.invoke import MethodHandles",
                    "from java.io import File",
                    "from java.net import Socket",
                    "from sun.misc import Unsafe",
                    "import os\nos.listdir('/')",
                    "import io\nio.open('/etc/passwd', 'r')"
            )) {
                var e = assertThrows(InterpreterException.class, () -> interp.eval(blocked), blocked);
                assertEquals("SecurityError", e.typeName(), blocked);
            }
        }
    }

    @Test
    @DisplayName("Resource caps (maxWindows, maxTimers, maxListenersPerNode) are strictly enforced")
    void resourceCapsEnforced() {
        var caps = CapabilitySet.builder()
                .allow(HostCapability.UI, HostCapability.EVENTS, HostCapability.INPUT, HostCapability.TIMER)
                .maxWindows(1)
                .maxTimers(1)
                .maxListenersPerNode(2)
                .build();
        try (var interp = new Jython3Interpreter(
                DenyAllPolicy.INSTANCE, HostAccessPolicy.DENY_ALL, ExecutionLimits.SANDBOXED,
                ExceptionTypeRegistry.standard(), caps)) {
            interp.eval("""
                import ui
                w1 = ui.Window("W1")
                t1 = ui.Timer(500, lambda: None)
                b = ui.Button("B")
                b.on_click(lambda e: None)
                b.on_click(lambda e: None)
                """);
            assertEquals("RuntimeError",
                    assertThrows(InterpreterException.class, () -> interp.eval("w2 = ui.Window('W2')")).typeName());
            assertEquals("RuntimeError",
                    assertThrows(InterpreterException.class, () -> interp.eval("t2 = ui.Timer(500, lambda: None)")).typeName());
            assertEquals("RuntimeError",
                    assertThrows(InterpreterException.class, () -> interp.eval("b.on_click(lambda e: None)")).typeName());
        }
    }
}
