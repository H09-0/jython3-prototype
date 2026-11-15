package org.example.jython3.security;

import org.example.jython3.runtime.PyCaller;
import org.example.jython3.runtime.PyErrors;

/**
 * Tracks runtime resource consumption during an active execution session.
 * Propagates across nested function calls, generator iterations, and imported modules.
 */
public final class ExecutionContext {

    public static ExecutionContext current() {
        PyCaller c = PyCaller.current();
        return c != null ? c.executionContext() : null;
    }

    private final ExecutionLimits limits;
    private final ExecutionContext parent;
    private long instructionCount = 0;
    private final long deadlineNanos;
    private volatile boolean cancelled = false;
    private volatile boolean timedOut = false;

    public ExecutionContext(ExecutionLimits limits) {
        this(limits, null);
    }

    public ExecutionContext(ExecutionLimits limits, ExecutionContext parent) {
        this.limits = limits != null ? limits : ExecutionLimits.TRUSTED;
        this.parent = parent;
        if (this.limits.timeout() != null && !this.limits.timeout().isZero() && !this.limits.timeout().isNegative()) {
            this.deadlineNanos = System.nanoTime() + this.limits.timeout().toNanos();
        } else {
            this.deadlineNanos = 0;
        }
    }

    public ExecutionLimits limits() {
        return limits;
    }

    public void cancel() {
        this.cancelled = true;
    }

    /** Marks this context expired due to a watchdog timeout (raises {@code TimeoutError}). */
    public void cancelByTimeout() {
        this.timedOut = true;
    }

    public boolean isTimedOut() { return timedOut; }

    public boolean hasActiveLimits() {
        return cancelled || timedOut || deadlineNanos > 0 || limits.maxInstructions() < Long.MAX_VALUE
                || (parent != null && parent.hasActiveLimits());
    }

    public boolean isCancelled() {
        return cancelled || (parent != null && parent.isCancelled());
    }

    /**
     * Checks instruction budget, deadline, and cancellation on each VM opcode cycle.
     */
    public void stepInstruction() {
        if (cancelled) {
            throw PyErrors.error("KeyboardInterrupt", "Execution cancelled by host");
        }
        if (timedOut) {
            throw PyErrors.error("TimeoutError", "Execution timed out: limit=" + limits.timeout());
        }
        if (parent != null && parent.hasActiveLimits()) {
            parent.stepInstruction();
        }
        instructionCount++;
        if (instructionCount > limits.maxInstructions()) {
            throw PyErrors.error("TimeoutError", "Instruction budget exceeded: limit=" + limits.maxInstructions());
        }
        if (deadlineNanos > 0 && (instructionCount & 0x3F) == 0) {
            if (System.nanoTime() >= deadlineNanos) {
                timedOut = true;
                throw PyErrors.error("TimeoutError", "Execution timed out: limit=" + limits.timeout());
            }
        }
    }

    public long instructionCount() {
        return instructionCount;
    }
}
