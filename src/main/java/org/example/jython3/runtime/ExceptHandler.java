package org.example.jython3.runtime;

/**
 * A registered exception handler.
 *
 * <p>{@code exceptPc} is where the handler chain starts. {@code activeDepth} is
 * the size of the active-exception stack when the handler was registered; when
 * the handler catches, values pushed by handlers that were abandoned during the
 * unwind are removed down to that depth.
 */
public record ExceptHandler(int exceptPc, int activeDepth) {}
