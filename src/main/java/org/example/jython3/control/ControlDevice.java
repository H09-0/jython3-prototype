package org.example.jython3.control;

import org.example.jython3.runtime.PyObject;

import java.util.Map;

/**
 * Abstract host-controlled device (simulated, host-bridged, or physical hardware).
 *
 * <p><b>Trust Boundary:</b> Registering {@link ControlDevice} implementations is a
 * trusted internal host-only API. Untrusted plugins must never register devices
 * or handlers directly. Never exposes raw Java objects or reflection to Python scripts.
 */
public interface ControlDevice {

    /** Provenance/risk tier of a {@link ControlDevice} adapter. */
    enum TrustTier {
        /** Deterministic in-memory simulation; no host or physical side effects. */
        SIMULATED,
        /** Trusted, code-reviewed host subsystem adapter (e.g. telemetry, workspace). */
        TRUSTED_HOST,
        /** Physical hardware or external actuator adapter; requires explicit {@code allowHardwareAdapters(true)}. */
        HARDWARE
    }

    String id();

    default TrustTier trustTier() {
        return TrustTier.SIMULATED;
    }

    PyObject execute(String command, Map<String, PyObject> args);

    Map<String, PyObject> snapshotState();
}
