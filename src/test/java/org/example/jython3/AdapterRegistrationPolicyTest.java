package org.example.jython3;

import org.example.jython3.api.EmbeddedRuntime;
import org.example.jython3.control.ControlDevice;
import org.example.jython3.control.HostAttestation;
import org.example.jython3.control.SimulatedDevice;
import org.example.jython3.host.CapabilitySet;
import org.example.jython3.host.HostCapability;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.ui.HeadlessUiToolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for ControlRegistry sealing and hardware adapter origin allowlisting.
 */
class AdapterRegistrationPolicyTest {

    private static final class DummyHw implements ControlDevice {
        private final String id;
        DummyHw(String id) { this.id = id; }
        @Override public String id() { return id; }
        @Override public PyObject execute(String command, Map<String, PyObject> args) {
            return new PyStr("ok:" + id + ":" + command);
        }
        @Override public Map<String, PyObject> snapshotState() { return Map.of(); }
    }

    @Test
    @DisplayName("Sealed ControlRegistry blocks post-boot device registration and allows only pre-registered devices")
    void sealedRegistryPreventsLateInjection() {
        var caps = CapabilitySet.builder()
                .allow(HostCapability.CONTROL)
                .allowDevice("sim-1", "late-1")
                .allowCommand("ping")
                .build();
        try (var rt = EmbeddedRuntime.builder().capabilities(caps).uiToolkit(new HeadlessUiToolkit()).build()) {
            rt.registerDevice(new SimulatedDevice("sim-1"));
            rt.lockDeviceRegistrations();

            assertThrows(IllegalStateException.class, () -> rt.registerDevice(new SimulatedDevice("late-1")));
            assertThrows(IllegalStateException.class, () -> rt.registerAttestedDevice(
                    new DummyHw("late-1"), HostAttestation.hardware("vendor:a", "sig")));

            rt.eval("import control\nassert control.Controller('sim-1').send('ping') == 'pong:sim-1'");
        }
    }

    @Test
    @DisplayName("allowAdapterOrigin restricts hardware adapters to matching attested origins and audits decisions")
    void hardwareOriginAllowlistEnforced() {
        var caps = CapabilitySet.builder()
                .allow(HostCapability.CONTROL)
                .allowDevice("hw-good", "hw-bad")
                .allowCommand("ping")
                .allowHardwareAdapters(true)
                .allowAdapterOrigin("oculix.hardware.cnc.v1")
                .build();
        try (var rt = EmbeddedRuntime.builder().capabilities(caps).uiToolkit(new HeadlessUiToolkit()).build()) {
            rt.registerAttestedDevice(new DummyHw("hw-good"),
                    HostAttestation.hardware("oculix.hardware.cnc.v1", "release-key-1"));
            rt.registerAttestedDevice(new DummyHw("hw-bad"),
                    HostAttestation.hardware("unreviewed.driver.v0", "self"));
            rt.lockDeviceRegistrations();

            rt.eval("import control\nout = control.Controller('hw-good').send('ping')");
            assertEquals("ok:hw-good:ping", rt.getGlobal("out").toString());

            InterpreterException denied = assertThrows(InterpreterException.class,
                    () -> rt.eval("control.Controller('hw-bad').send('ping')"));
            assertEquals("SecurityError", denied.typeName());

            assertTrue(rt.auditEntries().stream()
                    .anyMatch(e -> e.allowed() && e.action().startsWith("attested_hardware_")
                            && e.detail().contains("oculix.hardware.cnc.v1")));
            assertTrue(rt.auditEntries().stream()
                    .anyMatch(e -> !e.allowed() && e.detail().contains("unreviewed.driver.v0")));
        }
    }

    @Test
    @DisplayName("Field test: 100 cycles with sealed registry and attested hardware origin")
    void sealedHardwarePolicyFieldTest() {
        var caps = CapabilitySet.builder()
                .allow(HostCapability.CONTROL)
                .allowDevice("hw-good")
                .allowCommand("ping")
                .allowHardwareAdapters(true)
                .allowAdapterOrigin("oculix.hardware.cnc.v1")
                .build();
        try (var rt = EmbeddedRuntime.builder().capabilities(caps).uiToolkit(new HeadlessUiToolkit()).build()) {
            rt.registerAttestedDevice(new DummyHw("hw-good"),
                    HostAttestation.hardware("oculix.hardware.cnc.v1", "release-key-1"));
            rt.lockDeviceRegistrations();
            for (int i = 0; i < 100; i++) {
                rt.eval("import control\nassert control.Controller('hw-good').send('ping') == 'ok:hw-good:ping'");
                rt.restart();
            }
            assertEquals(0, rt.hostSession().managedResourceCount());
        }
    }
}
