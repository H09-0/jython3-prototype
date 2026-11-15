package org.example.jython3;

import org.example.jython3.api.EmbeddedRuntime;
import org.example.jython3.control.ControlDevice;
import org.example.jython3.control.ControlRegistry;
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
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security tests verifying that forged or unattested ControlDevice adapters
 * cannot spoof SIMULATED or TRUSTED_HOST tiers and cannot shadow existing devices.
 */
class ForgedAdapterSecurityTest {

    private static final class SpoofingAdapter implements ControlDevice {
        private final String id;
        private final TrustTier claimedTier;
        private final AtomicBoolean executed;

        SpoofingAdapter(String id, TrustTier claimedTier, AtomicBoolean executed) {
            this.id = id;
            this.claimedTier = claimedTier;
            this.executed = executed;
        }

        @Override public String id() { return id; }
        @Override public TrustTier trustTier() { return claimedTier; }

        @Override
        public PyObject execute(String command, Map<String, PyObject> args) {
            executed.set(true);
            return new PyStr("forged-exec");
        }

        @Override
        public Map<String, PyObject> snapshotState() {
            return Map.of("spoof", new PyStr("yes"));
        }
    }

    @Test
    @DisplayName("Custom adapters spoofing SIMULATED or TRUSTED_HOST via registerDevice() are rejected as UNTRUSTED")
    void forgedSelfReportedTrustTierIsRejected() {
        var caps = CapabilitySet.builder()
                .allow(HostCapability.CONTROL)
                .allowDevice("fake.sim", "fake.host", "fake.hw")
                .allowCommand("ping", "trigger")
                .allowHardwareAdapters(true) // even with hardware opt-in, unattested custom adapters stay UNTRUSTED!
                .build();

        AtomicBoolean executed = new AtomicBoolean(false);
        try (var rt = EmbeddedRuntime.builder().capabilities(caps).uiToolkit(new HeadlessUiToolkit()).build()) {
            rt.registerDevice(new SpoofingAdapter("fake.sim", ControlDevice.TrustTier.SIMULATED, executed));
            rt.registerDevice(new SpoofingAdapter("fake.host", ControlDevice.TrustTier.TRUSTED_HOST, executed));
            rt.registerDevice(new SpoofingAdapter("fake.hw", ControlDevice.TrustTier.HARDWARE, executed));

            for (String dev : new String[]{"fake.sim", "fake.host", "fake.hw"}) {
                InterpreterException ex = assertThrows(InterpreterException.class,
                        () -> rt.eval("import control\ncontrol.Controller('" + dev + "')"));
                assertEquals("SecurityError", ex.typeName());
            }
            assertFalse(executed.get(), "Unattested forged adapter must never execute");
            assertEquals(3, rt.auditEntries().stream()
                    .filter(e -> !e.allowed() && e.detail().contains("unattested"))
                    .count());
        }
    }

    @Test
    @DisplayName("Host-attested HARDWARE tier overrides adapter claiming SIMULATED and blocks without allowHardwareAdapters")
    void hostAttestedHardwareOverridesSelfClaim() {
        var caps = CapabilitySet.builder()
                .allow(HostCapability.CONTROL)
                .allowDevice("hw.masquerade")
                .allowCommand("ping")
                .build();
        AtomicBoolean executed = new AtomicBoolean(false);
        try (var rt = EmbeddedRuntime.builder().capabilities(caps).uiToolkit(new HeadlessUiToolkit()).build()) {
            // Adapter claims SIMULATED, but host attests it as HARDWARE
            rt.registerAttestedDevice(
                    new SpoofingAdapter("hw.masquerade", ControlDevice.TrustTier.SIMULATED, executed),
                    HostAttestation.hardware("pci-gpio", "host-verifier"));

            InterpreterException ex = assertThrows(InterpreterException.class,
                    () -> rt.eval("import control\ncontrol.Controller('hw.masquerade')"));
            assertEquals("SecurityError", ex.typeName());
            assertFalse(executed.get());
        }
    }

    @Test
    @DisplayName("ControlRegistry prevents overwriting existing device IDs and validates HostAttestation")
    void duplicateShadowingAndInvalidAttestationRejected() {
        assertThrows(IllegalArgumentException.class,
                () -> new HostAttestation(ControlRegistry.AttestedTier.UNTRUSTED, "o", "s"));
        assertThrows(IllegalArgumentException.class,
                () -> HostAttestation.hardware("  ", "signer"));
        assertThrows(IllegalArgumentException.class,
                () -> HostAttestation.trustedHost("origin", ""));

        try (var rt = EmbeddedRuntime.builder().uiToolkit(new HeadlessUiToolkit()).build()) {
            rt.registerDevice(new SimulatedDevice("axis-1"));
            assertThrows(IllegalStateException.class,
                    () -> rt.registerDevice(new SpoofingAdapter("axis-1", ControlDevice.TrustTier.SIMULATED, new AtomicBoolean())));
            assertThrows(IllegalStateException.class,
                    () -> rt.registerAttestedDevice(
                            new SpoofingAdapter("axis-1", ControlDevice.TrustTier.TRUSTED_HOST, new AtomicBoolean()),
                            HostAttestation.trustedHost("shadow", "attacker")));
        }
    }
}
