package org.example.jython3.control;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Per-interpreter registry of simulated or host-bridged {@link ControlDevice}s.
 *
 * <p><b>Trust Boundary:</b> Registering devices is a trusted internal host-only
 * operation and must never be exposed to untrusted script code.
 */
public final class ControlRegistry {

    private final Map<String, ControlDevice> devices = new ConcurrentHashMap<>();

    public void register(ControlDevice device) {
        Objects.requireNonNull(device, "device");
        devices.put(device.id(), device);
    }

    public ControlDevice get(String id) {
        return id != null ? devices.get(id) : null;
    }

    public Map<String, ControlDevice> devices() {
        return Collections.unmodifiableMap(devices);
    }
}
