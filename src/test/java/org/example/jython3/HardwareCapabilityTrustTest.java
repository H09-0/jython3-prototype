package org.example.jython3;

import org.example.jython3.api.EmbeddedRuntime;
import org.example.jython3.control.ControlDevice;
import org.example.jython3.control.HostAttestation;
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
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Verifies that physical/hardware-tier ControlDevice adapters remain locked down
 * by default unless explicitly opted in via allowHardwareAdapters(true), and that
 * FILESYSTEM, PROCESS, NETWORK, and hardware capabilities stay deny-by-default.
 */
class HardwareCapabilityTrustTest {

    private static final class MockHardwareDevice implements ControlDevice {
        @Override public String id() { return "cnc.spindle"; }
        @Override public TrustTier trustTier() { return TrustTier.HARDWARE; }
        @Override public PyObject execute(String command, Map<String, PyObject> args) {
            return new PyStr("hw:" + command);
        }
        @Override public Map<String, PyObject> snapshotState() {
            return Map.of("rpm", new PyStr("0"));
        }
    }

    @Test
    @DisplayName("Default capabilities deny FILESYSTEM, PROCESS, NETWORK, CONTROL, and hardware adapters")
    void denyByDefaultIncludesHardware() {
        CapabilitySet none = CapabilitySet.none();
        assertFalse(none.has(HostCapability.FILESYSTEM));
        assertFalse(none.has(HostCapability.PROCESS));
        assertFalse(none.has(HostCapability.NETWORK));
        assertFalse(none.has(HostCapability.CONTROL));
        assertFalse(none.allowHardwareAdapters());
    }

    @Test
    @DisplayName("Hardware-tier device requires allowHardwareAdapters(true) even when CONTROL + allowlists are granted")
    void hardwareAdapterRequiresExplicitTrustOptIn() {
        var controlWithoutHardware = CapabilitySet.builder()
                .allow(HostCapability.CONTROL)
                .allowDevice("cnc.spindle")
                .allowCommand("ping", "spin")
                .build();

        try (var rt = EmbeddedRuntime.builder()
                .capabilities(controlWithoutHardware)
                .uiToolkit(new HeadlessUiToolkit())
                .build()) {
            rt.registerAttestedDevice(new MockHardwareDevice(), HostAttestation.hardware("cnc-driver", "oculix-signer"));
            InterpreterException denied = assertThrows(InterpreterException.class,
                    () -> rt.eval("import control\ncontrol.Controller('cnc.spindle').send('ping')"));
            assertEquals("SecurityError", denied.typeName());
            assertTrue(rt.auditEntries().stream()
                    .anyMatch(e -> !e.allowed() && e.detail().contains("allowHardwareAdapters")));
        }

        var controlWithHardware = CapabilitySet.builder()
                .allow(HostCapability.CONTROL)
                .allowDevice("cnc.spindle")
                .allowCommand("ping")
                .allowHardwareAdapters(true)
                .build();

        try (var rt = EmbeddedRuntime.builder()
                .capabilities(controlWithHardware)
                .uiToolkit(new HeadlessUiToolkit())
                .build()) {
            rt.registerAttestedDevice(new MockHardwareDevice(), HostAttestation.hardware("cnc-driver", "oculix-signer"));
            rt.eval("import control\nout = control.Controller('cnc.spindle').send('ping')");
            assertEquals("hw:ping", rt.getGlobal("out").toString());
        }
    }
}
