package org.example.jython3.runtime;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * A Python exception value.
 *
 * <p>This is a value, not a Java throwable: it can be stored in a variable
 * ({@code except ValueError as e}), passed to a function, and compared. The
 * Java object used to unwind the stack is {@link InterpreterException}, which
 * carries one of these as its payload.
 *
 * <p>The exception type is a real {@link PyClass}. The name is derived from
 * that class, never stored separately, so matching always follows the actual
 * base list. {@code context} links to the exception that was being handled when
 * this one was raised; {@code cause} is the explicit {@code raise ... from ...}
 * link. {@code javaCause} keeps the original JVM throwable when the error came
 * through the bridge. They are kept apart on purpose: a Java throwable is not a
 * Python exception.
 */
public final class PyException implements PyObject {
    private final PyClass type;
    private final List<PyObject> args;
    private final PyException cause;
    private PyException context;
    private final Throwable javaCause;
    /**
     * Frames captured when the error was caught. The carrier builds the
     * traceback while unwinding; the value keeps a copy so {@code __traceback__}
     * survives after the carrier is gone.
     */
    private List<InterpreterException.TraceEntry> traceback = List.of();

    /** Creates a value of {@code type} with a single string argument. */
    public PyException(PyClass type, String message) {
        this(type, defaultArgs(message), null, null, null);
    }

    /** Creates a value that carries {@code args} as its arguments. */
    public PyException(PyClass type, List<PyObject> args) {
        this(type, args, null, null, null);
    }

    public PyException(PyClass type, List<PyObject> args, PyException cause,
                       PyException context, Throwable javaCause) {
        this.type = Objects.requireNonNull(type, "type");
        this.args = args == null ? List.of() : List.copyOf(args);
        this.cause = cause;
        this.context = context;
        this.javaCause = javaCause;
    }

    /**
     * Records {@code candidate} as the exception being handled when this one
     * was raised. Python sets {@code __context__} once, at raise time; a value
     * that already has a context keeps it, and an exception is never its own
     * context.
     */
    public void attachContextIfAbsent(PyException candidate) {
        if (candidate != null && candidate != this && this.context == null) {
            this.context = candidate;
        }
    }

    private static List<PyObject> defaultArgs(String message) {
        if (message == null || message.isEmpty()) return List.of();
        return List.of(new PyStr(message));
    }

    /**
     * Creates a value by resolving {@code typeName} in the current runtime's
     * registry. New code should pass a {@link PyClass} instead.
     */
    public static PyException of(String typeName, String message) {
        return new PyException(ExceptionTypeRegistry.current().type(typeName), message);
    }

    /** Creates a value from a concrete exception class. */
    public static PyException of(PyClass type, String message) {
        return new PyException(type, message);
    }

    /**
     * Creates a value that remembers the JVM throwable behind it. The Python
     * type is chosen independently; Java hierarchy does not decide Python
     * inheritance.
     */
    public static PyException withJavaCause(PyClass type, String message, Throwable javaCause) {
        return new PyException(type, defaultArgs(message), null, null, javaCause);
    }

    /** Compatibility entry point for callers that still name the type. */
    public static PyException withJavaCause(String typeName, String message, Throwable javaCause) {
        return withJavaCause(ExceptionTypeRegistry.current().type(typeName), message, javaCause);
    }

    /** The concrete exception class. */
    public PyClass type() { return type; }

    /** The type name, derived from the class. */
    public String typeName() { return type.name(); }

    /** The exception arguments; the message is rendered from them. */
    public List<PyObject> args() { return args; }

    /** The message as Python prints it, derived from {@link #args()}. */
    public String message() {
        if (args.isEmpty()) return "";
        if (args.size() == 1) return text(args.get(0));
        var sb = new StringBuilder("(");
        for (int i = 0; i < args.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(text(args.get(i)));
        }
        return sb.append(')').toString();
    }

    private static String text(PyObject value) {
        return value instanceof PyStr s ? s.value() : String.valueOf(value);
    }

    public PyException cause() { return cause; }

    public PyException context() { return context; }

    public Throwable javaCause() { return javaCause; }

    /** Frames captured when the handler received this value, oldest first. */
    public List<InterpreterException.TraceEntry> traceback() { return traceback; }

    /**
     * Copies carrier frames into the value. Called by the VM when a handler is
     * entered, so the traceback outlives the throwable that produced it.
     */
    public void attachTraceback(List<InterpreterException.TraceEntry> frames) {
        if (frames == null || frames.isEmpty()) return;
        var merged = new ArrayList<>(traceback);
        merged.addAll(frames);
        traceback = List.copyOf(merged);
    }

    @Override public String toString() {
        String message = message();
        return message.isEmpty() ? typeName() : typeName() + ": " + message;
    }
}
