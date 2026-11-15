package org.example.jython3.host;

import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.security.ExecutionContext;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Per-interpreter host session binding {@link CapabilitySet}, {@link HostAuditLog},
 * {@link ExecutionContext}, callback invocation, and deterministic resource cleanup.
 */
public final class HostSession implements AutoCloseable {

    @FunctionalInterface
    public interface CallbackInvoker {
        PyObject invoke(PyObject callable, PyObject... args);
    }

    private final CapabilitySet capabilities;
    private final HostAuditLog auditLog = new HostAuditLog();
    private final Set<AutoCloseable> managedResources = new LinkedHashSet<>();
    private final CallbackInvoker callbackInvoker;
    private volatile ExecutionContext activeContext;
    private volatile boolean closed = false;
    private int activeCallbackDepth = 0;

    public HostSession(CapabilitySet capabilities, CallbackInvoker callbackInvoker) {
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.callbackInvoker = Objects.requireNonNull(callbackInvoker, "callbackInvoker");
    }

    public CapabilitySet capabilities() { return capabilities; }

    public HostAuditLog auditLog() { return auditLog; }

    public boolean isClosed() { return closed; }

    public void bindContext(ExecutionContext context) { this.activeContext = context; }

    public ExecutionContext activeContext() { return activeContext; }

    /**
     * Checks {@code cap} against the active {@link CapabilitySet} and logs the outcome.
     * Throws {@code SecurityError} if the session is closed or the capability is absent.
     */
    public void require(HostCapability cap, String action, String target) {
        if (closed) {
            auditLog.recordDenied(cap, action, target, "host session closed");
            throw PyErrors.error("SecurityError", "host session is closed");
        }
        if (!capabilities.has(cap)) {
            auditLog.recordDenied(cap, action, target, "capability not granted");
            throw PyErrors.error("SecurityError",
                    "capability '" + cap.name().toLowerCase() + "' denied for " + action
                            + (target != null && !target.isEmpty() ? " on '" + target + "'" : ""));
        }
        auditLog.recordAllowed(cap, action, target, "granted");
    }

    /** Enforces {@link HostCapability#CONTROL} plus device and command allowlists. */
    public void requireControlCommand(String deviceId, String command) {
        if (closed) {
            auditLog.recordDenied(HostCapability.CONTROL, command, deviceId, "host session closed");
            throw PyErrors.error("SecurityError", "host session is closed");
        }
        if (!capabilities.has(HostCapability.CONTROL)) {
            auditLog.recordDenied(HostCapability.CONTROL, command, deviceId, "capability not granted");
            throw PyErrors.error("SecurityError", "capability 'control' denied for '" + command + "'");
        }
        if (!capabilities.canControlDevice(deviceId)) {
            auditLog.recordDenied(HostCapability.CONTROL, command, deviceId, "device not allowlisted");
            throw PyErrors.error("SecurityError", "control access to device '" + deviceId + "' is restricted");
        }
        if (!capabilities.canRunCommand(command)) {
            auditLog.recordDenied(HostCapability.CONTROL, command, deviceId, "command not allowlisted");
            throw PyErrors.error("SecurityError", "control command '" + command + "' is restricted");
        }
        auditLog.recordAllowed(HostCapability.CONTROL, command, deviceId, "granted");
    }

    public synchronized void registerResource(AutoCloseable resource) {
        if (closed) {
            safeClose(resource);
            throw PyErrors.error("SecurityError", "host session is closed");
        }
        if (resource != null) {
            managedResources.add(resource);
        }
    }

    public synchronized void unregisterResource(AutoCloseable resource) {
        managedResources.remove(resource);
    }

    public synchronized int managedResourceCount() {
        return managedResources.size();
    }

    /** Invokes a Python callback from a host adapter while enforcing depth, cancellation, and session state. */
    public PyObject invokeCallback(PyObject callable, PyObject... args) {
        if (closed) {
            throw PyErrors.error("RuntimeError", "cannot run callback on closed host session");
        }
        ExecutionContext ctx = activeContext;
        if (ctx != null && ctx.isCancelled()) {
            throw PyErrors.error("KeyboardInterrupt", "Execution cancelled by host");
        }
        synchronized (this) {
            if (activeCallbackDepth >= capabilities.maxCallbackDepth()) {
                throw PyErrors.error("RecursionError",
                        "maximum host callback depth (" + capabilities.maxCallbackDepth() + ") exceeded");
            }
            activeCallbackDepth++;
        }
        try {
            return callbackInvoker.invoke(callable, args);
        } finally {
            synchronized (this) {
                activeCallbackDepth--;
            }
        }
    }

    /** Closes all registered windows, timers, listeners, and controllers without throwing. */
    public void reset() {
        List<AutoCloseable> toClose;
        synchronized (this) {
            toClose = new ArrayList<>(managedResources);
            managedResources.clear();
        }
        for (int i = toClose.size() - 1; i >= 0; i--) {
            safeClose(toClose.get(i));
        }
    }

    @Override
    public void close() {
        closed = true;
        reset();
    }

    private static void safeClose(AutoCloseable resource) {
        if (resource == null) return;
        try {
            resource.close();
        } catch (Exception ignored) {
            // Cleanup must never abort teardown of remaining resources.
        }
    }
}
