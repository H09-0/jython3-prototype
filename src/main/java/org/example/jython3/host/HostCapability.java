package org.example.jython3.host;

/**
 * Fine-grained capabilities governing what host subsystems a Python script
 * may access. Everything is denied by default unless explicitly granted in a
 * {@link CapabilitySet}.
 */
public enum HostCapability {
    UI,
    EVENTS,
    INPUT,
    TIMER,
    FILESYSTEM,
    PROCESS,
    NETWORK,
    CONTROL
}
