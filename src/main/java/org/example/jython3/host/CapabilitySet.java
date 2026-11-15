package org.example.jython3.host;

import java.time.Duration;
import java.util.Arrays;
import java.util.Collections;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, deny-by-default capability policy for embedded host APIs
 * (UI, events, input, timers, filesystem, process, network, device control).
 */
public final class CapabilitySet {

    private static final CapabilitySet NONE = builder().build();

    private final EnumSet<HostCapability> granted;
    private final Set<String> allowedDevices;
    private final Set<String> allowedCommands;
    private final Set<String> allowedAdapterOrigins;
    private final boolean allowHardwareAdapters;
    private final int maxWindows;
    private final int maxTimers;
    private final int maxListenersPerNode;
    private final int maxCallbackDepth;
    private final int maxThreads;
    private final Duration callbackTimeout;

    private CapabilitySet(Builder b) {
        this.granted = b.granted.isEmpty() ? EnumSet.noneOf(HostCapability.class) : EnumSet.copyOf(b.granted);
        this.allowedDevices = Collections.unmodifiableSet(new HashSet<>(b.allowedDevices));
        this.allowedCommands = Collections.unmodifiableSet(new HashSet<>(b.allowedCommands));
        this.allowedAdapterOrigins = Collections.unmodifiableSet(new HashSet<>(b.allowedAdapterOrigins));
        this.allowHardwareAdapters = b.allowHardwareAdapters;
        this.maxWindows = b.maxWindows;
        this.maxTimers = b.maxTimers;
        this.maxListenersPerNode = b.maxListenersPerNode;
        this.maxCallbackDepth = b.maxCallbackDepth;
        this.maxThreads = b.maxThreads;
        this.callbackTimeout = b.callbackTimeout;
    }

    /** Returns an empty capability set (deny-by-default). */
    public static CapabilitySet none() { return NONE; }

    /** Grants only the specified capabilities with default resource limits. */
    public static CapabilitySet of(HostCapability... caps) {
        var b = builder();
        b.allow(caps);
        return b.build();
    }

    public static Builder builder() { return new Builder(); }

    public boolean has(HostCapability cap) { return cap != null && granted.contains(cap); }

    public Set<HostCapability> granted() { return Collections.unmodifiableSet(granted); }

    public Set<String> allowedDevices() { return allowedDevices; }

    public Set<String> allowedCommands() { return allowedCommands; }

    public boolean canControlDevice(String deviceId) {
        return has(HostCapability.CONTROL) && deviceId != null && allowedDevices.contains(deviceId);
    }

    public boolean canRunCommand(String command) {
        return has(HostCapability.CONTROL) && command != null && allowedCommands.contains(command);
    }

    public boolean allowHardwareAdapters() { return allowHardwareAdapters; }

    public Set<String> allowedAdapterOrigins() { return allowedAdapterOrigins; }

    public boolean isAdapterOriginAllowed(String origin) {
        return allowedAdapterOrigins.isEmpty() || (origin != null && allowedAdapterOrigins.contains(origin));
    }

    public int maxWindows() { return maxWindows; }
    public int maxTimers() { return maxTimers; }
    public int maxListenersPerNode() { return maxListenersPerNode; }
    public int maxCallbackDepth() { return maxCallbackDepth; }
    public int maxThreads() { return maxThreads; }
    public Duration callbackTimeout() { return callbackTimeout; }

    public static final class Builder {
        private final EnumSet<HostCapability> granted = EnumSet.noneOf(HostCapability.class);
        private final Set<String> allowedDevices = new HashSet<>();
        private final Set<String> allowedCommands = new HashSet<>();
        private final Set<String> allowedAdapterOrigins = new HashSet<>();
        private boolean allowHardwareAdapters = false;
        private int maxWindows = 8;
        private int maxTimers = 16;
        private int maxListenersPerNode = 32;
        private int maxCallbackDepth = 16;
        private int maxThreads = 16;
        private Duration callbackTimeout = Duration.ofMillis(500);

        public Builder allowHardwareAdapters(boolean allow) {
            this.allowHardwareAdapters = allow;
            return this;
        }

        public Builder allowAdapterOrigin(String... origins) {
            if (origins != null) {
                for (String origin : origins) {
                    if (origin != null && !origin.isBlank()) allowedAdapterOrigins.add(origin);
                }
            }
            return this;
        }

        public Builder allow(HostCapability... caps) {
            if (caps != null) granted.addAll(Arrays.asList(caps));
            return this;
        }

        public Builder allowDevice(String... deviceIds) {
            if (deviceIds != null) {
                for (String id : deviceIds) {
                    if (id != null && !id.isEmpty()) allowedDevices.add(id);
                }
            }
            return this;
        }

        public Builder allowCommand(String... commands) {
            if (commands != null) {
                for (String cmd : commands) {
                    if (cmd != null && !cmd.isEmpty()) allowedCommands.add(cmd);
                }
            }
            return this;
        }

        public Builder maxWindows(int maxWindows) {
            if (maxWindows < 0) throw new IllegalArgumentException("maxWindows < 0");
            this.maxWindows = maxWindows;
            return this;
        }

        public Builder maxTimers(int maxTimers) {
            if (maxTimers < 0) throw new IllegalArgumentException("maxTimers < 0");
            this.maxTimers = maxTimers;
            return this;
        }

        public Builder maxListenersPerNode(int maxListenersPerNode) {
            if (maxListenersPerNode < 0) throw new IllegalArgumentException("maxListenersPerNode < 0");
            this.maxListenersPerNode = maxListenersPerNode;
            return this;
        }

        public Builder maxCallbackDepth(int maxCallbackDepth) {
            if (maxCallbackDepth < 1) throw new IllegalArgumentException("maxCallbackDepth < 1");
            this.maxCallbackDepth = maxCallbackDepth;
            return this;
        }

        public Builder maxThreads(int maxThreads) {
            if (maxThreads < 0) throw new IllegalArgumentException("maxThreads < 0");
            this.maxThreads = maxThreads;
            return this;
        }

        public Builder callbackTimeout(Duration callbackTimeout) {
            this.callbackTimeout = Objects.requireNonNull(callbackTimeout, "callbackTimeout");
            return this;
        }

        public CapabilitySet build() { return new CapabilitySet(this); }
    }
}
