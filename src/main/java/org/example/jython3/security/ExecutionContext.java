package org.example.jython3.security;

import org.example.jython3.runtime.PyErrors;

/**
 * Tracks runtime resource consumption during an active execution session.
 * Propagates across nested function calls, generator iterations, and imported modules.
 */
public final class ExecutionContext {

    private final ExecutionLimits limits;
    private long instructionCount = 0;
    private final long deadlineNanos;
    private volatile boolean cancelled = false;

    public ExecutionContext(ExecutionLimits limits) {
        this.limits = limits != null ? limits : ExecutionLimits.TRUSTED;
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

    public boolean isCancelled() {
        return cancelled;
    }

    /**
     * Checks instruction budget, deadline, and cancellation on each VM opcode cycle.
     */
    public void stepInstruction() {
        if (cancelled) {
            throw PyErrors.error("KeyboardInterrupt", "Execution cancelled by host");
        }
        instructionCount++;
        if (instructionCount > limits.maxInstructions()) {
            throw PyErrors.error("TimeoutError", "Instruction budget exceeded: limit=" + limits.maxInstructions());
        }
        if (deadlineNanos > 0 && (instructionCount & 0x3FF) == 0) { // Check deadline every 1024 instructions for high throughput
            if (System.nanoTime() >= deadlineNanos) {
                throw PyErrors.error("TimeoutError", "Execution timed out: limit=" + limits.timeout());
            }
        }
    }

    public long instructionCount() {
        return instructionCount;
    }
}
