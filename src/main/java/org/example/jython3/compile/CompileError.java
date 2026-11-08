package org.example.jython3.compile;

/**
 * Thrown by CompileVisitor when it detects a semantic error at compile-time
 * (e.g. non-default arg after default arg, positional arg after keyword arg).
 */
public final class CompileError extends RuntimeException {
    public CompileError(String message) {
        super(message);
    }
}
