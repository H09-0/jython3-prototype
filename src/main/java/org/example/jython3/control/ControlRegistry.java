package org.example.jython3.control;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-interpreter registry of simulated, host-bridged, or hardware {@link ControlDevice}s.
 *
 * <p><b>Host-Attested Trust:</b> Trust tiers are assigned at registration time by
 * the host ({@link AttestedTier}), never by querying {@link ControlDevice#trustTier()}.
 * Unattested custom implementations passed to {@link #register(ControlDevice)} are
 * recorded as {@link AttestedTier#UNTRUSTED} and blocked from Python access.
 *
 * <p><b>Trust Boundary:</b> Registering devices is a trusted internal host-only
 * operation and must never be exposed to untrusted script code.
 */
public final class ControlRegistry {

    public enum AttestedTier {
        SIMULATED,
        TRUSTED_HOST,
        HARDWARE,
        UNTRUSTED
    }

    public record Registration(ControlDevice device, AttestedTier tier, String origin, String attestor) {}

    private final Map<String, Registration> registrations = new ConcurrentHashMap<>();

    /**
     * Registers a built-in simulated/host adapter, or records any custom external
     * implementation as {@link AttestedTier#UNTRUSTED} (ignoring self-reported
     * {@link ControlDevice#trustTier()}).
     */
    public void register(ControlDevice device) {
        Objects.requireNonNull(device, "device");
        Registration reg;
        if (device instanceof SimulatedDevice) {
            reg = new Registration(device, AttestedTier.SIMULATED, "builtin:simulated", "jython3-core");
        } else if (device instanceof JvmTelemetryAdapter
                || device instanceof HostCommandChannelAdapter
                || device instanceof ScopedWorkspaceAdapter) {
            reg = new Registration(device, AttestedTier.TRUSTED_HOST, "builtin:host-adapter", "jython3-core");
        } else {
            reg = new Registration(device, AttestedTier.UNTRUSTED, "unattested", "none");
        }
        putUnique(device.id(), reg);
    }

    /**
     * Registers a host-reviewed or hardware {@link ControlDevice} with explicit
     * host-issued {@link HostAttestation}.
     */
    public void registerAttested(ControlDevice device, HostAttestation attestation) {
        Objects.requireNonNull(device, "device");
        Objects.requireNonNull(attestation, "attestation");
        putUnique(device.id(), new Registration(
                device, attestation.tier(), attestation.origin(), attestation.reviewerOrSigner()));
    }

    private void putUnique(String id, Registration reg) {
        Objects.requireNonNull(id, "device.id()");
        if (registrations.putIfAbsent(id, reg) != null) {
            throw new IllegalStateException("control device '" + id + "' is already registered");
        }
    }

    public ControlDevice get(String id) {
        Registration r = getRegistration(id);
        return r != null ? r.device() : null;
    }

    public Registration getRegistration(String id) {
        return id != null ? registrations.get(id) : null;
    }

    public Map<String, ControlDevice> devices() {
        Map<String, ControlDevice> out = new LinkedHashMap<>(registrations.size());
        for (var e : registrations.entrySet()) {
            out.put(e.getKey(), e.getValue().device());
        }
        return Collections.unmodifiableMap(out);
    }
}
