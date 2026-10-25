package org.example.jython3.security;

import java.time.Duration;

/**
 * Configurable resource and execution limits enforced by the Jython3 virtual machine.
 *
 * <p>Protects the host JVM against infinite loops, CPU starvation, runaway recursion,
 * and malicious memory amplification attacks.
 */
public record ExecutionLimits(
        long maxInstructions,
        Duration timeout,
        int maxSourceLength,
        int maxCollectionSize,
        int maxStringLength,
        int maxExponent
) {

    /** Default limits for trusted in-process execution (broad boundaries). */
    public static final ExecutionLimits TRUSTED = new ExecutionLimits(
            Long.MAX_VALUE,
            Duration.ZERO, // 0 = no timeout
            10_000_000,    // 10 MB source text
            100_000_000,   // 100M items in list/dict
            100_000_000,   // 100M chars
            1_000_000      // 1M exponent limit
    );

    /** Strict limits for sandboxed or untrusted execution. */
    public static final ExecutionLimits SANDBOXED = new ExecutionLimits(
            1_000_000L,          // max 1 million VM instructions
            Duration.ofSeconds(5), // max 5 seconds execution
            500_000,             // 500 KB source text
            100_000,             // max 100K collection items
            1_000_000,           // max 1M characters
            10_000               // max 10,000 exponent
    );

    /** Builder helper for custom limits. */
    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private long maxInstructions = Long.MAX_VALUE;
        private Duration timeout = Duration.ZERO;
        private int maxSourceLength = 10_000_000;
        private int maxCollectionSize = 100_000_000;
        private int maxStringLength = 100_000_000;
        private int maxExponent = 1_000_000;

        public Builder maxInstructions(long count) { this.maxInstructions = count; return this; }
        public Builder timeout(Duration duration) { this.timeout = duration; return this; }
        public Builder maxSourceLength(int length) { this.maxSourceLength = length; return this; }
        public Builder maxCollectionSize(int size) { this.maxCollectionSize = size; return this; }
        public Builder maxStringLength(int length) { this.maxStringLength = length; return this; }
        public Builder maxExponent(int exp) { this.maxExponent = exp; return this; }

        public ExecutionLimits build() {
            return new ExecutionLimits(maxInstructions, timeout, maxSourceLength,
                                   maxCollectionSize, maxStringLength, maxExponent);
        }
    }
}
