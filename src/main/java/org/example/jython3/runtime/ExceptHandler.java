package org.example.jython3.runtime;

/**
 * A registered cleanup or exception handler.
 *
 * <p>{@code exceptPc} is where the handler code starts. The two depths are
 * recorded when the handler is registered and restored when it catches:
 * {@code activeDepth} drops active exceptions pushed by handlers that the
 * unwind abandoned, and {@code stackDepth} discards intermediate operands from
 * the expression that failed. The handler then starts from the same stack it
 * would have seen on the normal path, plus the exception value.
 */
public record ExceptHandler(int exceptPc, int activeDepth, int stackDepth) {}
