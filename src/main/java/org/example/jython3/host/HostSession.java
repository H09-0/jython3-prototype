package org.example.jython3.host;

import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.security.DenyAllPolicy;
import org.example.jython3.security.ExecutionContext;
import org.example.jython3.security.JavaAccessPolicy;
import org.example.jython3.security.PolicySanitizer;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.BiConsumer;

/**
 * Per-interpreter host session binding {@link CapabilitySet}, {@link HostAuditLog},
 * {@link ExecutionContext}, callback invocation, and deterministic resource cleanup.
 */
public final class HostSession implements AutoCloseable {

    @FunctionalInterface
    public interface CallbackInvoker {
        PyObject invoke(PyObject callable, PyObject... args);
    }

    public record CallbackFailure(Instant timestamp, String sourceId, Throwable error) {}

    private static final int MAX_FAILURE_RECORDS = 256;

    private final CapabilitySet capabilities;
    private final JavaAccessPolicy javaAccessPolicy;
    private final HostAuditLog auditLog = new HostAuditLog();
    private final Set<AutoCloseable> managedResources = new LinkedHashSet<>();
    private final Deque<CallbackFailure> callbackFailures = new ArrayDeque<>(16);
    private final CallbackInvoker callbackInvoker;
    private final AtomicLong epoch = new AtomicLong(1);
    private volatile BiConsumer<String, Throwable> callbackErrorHandler;
    private volatile ExecutionContext activeContext;
    private volatile boolean closed = false;
    private int activeCallbackDepth = 0;
    private int windowCount = 0;
    private int timerCount = 0;
    private final Set<AutoCloseable> activeWindows = new LinkedHashSet<>();
    private final Set<AutoCloseable> activeTimers = new LinkedHashSet<>();

    public HostSession(CapabilitySet capabilities, CallbackInvoker callbackInvoker) {
        this(capabilities, callbackInvoker, DenyAllPolicy.INSTANCE);
    }

    public HostSession(CapabilitySet capabilities, CallbackInvoker callbackInvoker, JavaAccessPolicy javaAccessPolicy) {
        this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
        this.callbackInvoker = Objects.requireNonNull(callbackInvoker, "callbackInvoker");
        this.javaAccessPolicy = Objects.requireNonNull(javaAccessPolicy, "javaAccessPolicy");
    }

    public JavaAccessPolicy javaAccessPolicy() { return javaAccessPolicy; }

    public CapabilitySet capabilities() { return capabilities; }

    public HostAuditLog auditLog() { return auditLog; }

    public boolean isClosed() { return closed; }

    /** Marks the session closing/invalidated immediately (non-blocking) to abort in-flight callbacks. */
    public void beginTeardown() {
        closed = true;
        epoch.incrementAndGet();
    }

    /** Advances epoch immediately (non-blocking) to invalidate handles prior to full reset. */
    public void invalidateHandles() {
        epoch.incrementAndGet();
    }

    public long epoch() { return epoch.get(); }

    public void requireValidEpoch(long handleEpoch, String handleType) {
        if (closed || epoch.get() != handleEpoch) {
            throw PyErrors.error("RuntimeError",
                    "stale handle '" + handleType + "' from a reset or closed host session");
        }
    }

    public void setCallbackErrorHandler(BiConsumer<String, Throwable> handler) {
        this.callbackErrorHandler = handler;
    }

    public void recordCallbackFailure(String sourceId, Throwable error) {
        String msg = error != null ? error.toString() : "unknown";
        auditLog.recordDenied(HostCapability.EVENTS, "async_callback_error", sourceId, msg);
        synchronized (this) {
            if (callbackFailures.size() >= MAX_FAILURE_RECORDS) {
                callbackFailures.removeFirst();
            }
            callbackFailures.addLast(new CallbackFailure(Instant.now(), sourceId, error));
        }
        BiConsumer<String, Throwable> handler = this.callbackErrorHandler;
        if (handler != null) {
            handler.accept(sourceId, error);
        }
    }

    public synchronized List<CallbackFailure> callbackFailures() {
        return new ArrayList<>(callbackFailures);
    }

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

    public synchronized int openWindowCount() {
        return windowCount;
    }

    public synchronized int liveTimerCount() {
        return timerCount;
    }

    public synchronized void acquireWindowSlot(AutoCloseable window, String title) {
        if (closed) throw PyErrors.error("SecurityError", "host session is closed");
        int maxW = capabilities.maxWindows();
        if (windowCount >= maxW) {
            auditLog.recordDenied(HostCapability.UI, "create_window", title, "maxWindows=" + maxW);
            throw PyErrors.error("RuntimeError", "maximum open UI windows exceeded: " + maxW);
        }
        windowCount++;
        if (window != null) {
            activeWindows.add(window);
            managedResources.add(window);
        }
    }

    public synchronized void releaseWindowSlot(AutoCloseable window) {
        if (window != null && activeWindows.remove(window)) {
            windowCount = Math.max(0, windowCount - 1);
            managedResources.remove(window);
        }
    }

    public synchronized void acquireTimerSlot(AutoCloseable timer, String target) {
        if (closed) throw PyErrors.error("SecurityError", "host session is closed");
        int maxT = capabilities.maxTimers();
        if (timerCount >= maxT) {
            auditLog.recordDenied(HostCapability.TIMER, "create_timer", target, "maxTimers=" + maxT);
            throw PyErrors.error("RuntimeError", "maximum timers exceeded: " + maxT);
        }
        timerCount++;
        if (timer != null) {
            activeTimers.add(timer);
            managedResources.add(timer);
        }
    }

    public synchronized void releaseTimerSlot(AutoCloseable timer) {
        if (timer != null && activeTimers.remove(timer)) {
            timerCount = Math.max(0, timerCount - 1);
            managedResources.remove(timer);
        }
    }

    public void checkListenerQuota(int currentCount, String nodeId) {
        int maxL = capabilities.maxListenersPerNode();
        if (currentCount >= maxL) {
            auditLog.recordDenied(HostCapability.EVENTS, "attach_listener", nodeId, "maxListenersPerNode=" + maxL);
            throw PyErrors.error("RuntimeError", "maximum listeners per node exceeded: " + maxL);
        }
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
        PyObject safeCallable = PolicySanitizer.sanitize(callable, javaAccessPolicy, this);
        PyObject[] safeArgs = new PyObject[args != null ? args.length : 0];
        for (int i = 0; i < safeArgs.length; i++) {
            safeArgs[i] = PolicySanitizer.sanitize(args[i], javaAccessPolicy, this);
        }
        synchronized (this) {
            if (activeCallbackDepth >= capabilities.maxCallbackDepth()) {
                throw PyErrors.error("RecursionError",
                        "maximum host callback depth (" + capabilities.maxCallbackDepth() + ") exceeded");
            }
            activeCallbackDepth++;
        }
        try {
            return callbackInvoker.invoke(safeCallable, safeArgs);
        } finally {
            synchronized (this) {
                activeCallbackDepth--;
            }
        }
    }

    /** Closes all registered windows, timers, listeners, and controllers without throwing. */
    public void reset() {
        epoch.incrementAndGet();
        List<AutoCloseable> toClose;
        synchronized (this) {
            toClose = new ArrayList<>(managedResources);
            managedResources.clear();
            activeWindows.clear();
            activeTimers.clear();
            callbackFailures.clear();
            windowCount = 0;
            timerCount = 0;
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
