package org.example.jython3.runtime;

import java.util.List;

/**
 * A Python exception value.
 *
 * <p>This is a value, not a Java throwable: it can be stored in a variable
 * ({@code except ValueError as e}), passed to a function, and compared. The
 * Java object used to unwind the stack is {@link InterpreterException}, which
 * carries one of these as its payload.
 *
 * <p>{@code cause} and {@code context} are the Python-level links created by
 * {@code raise ... from ...}; {@code javaCause} keeps the original JVM
 * throwable when the error came through the bridge. They are kept apart on
 * purpose: a Java throwable is not a Python exception.
 */
public final class PyException implements PyObject {
    private final String typeName;
    private final String message;
    private final List<PyObject> args;
    private final PyObject cause;
    private final PyObject context;
    private final Throwable javaCause;

    public PyException(String typeName, String message) {
        this(typeName, message, defaultArgs(message), null, null, null);
    }

    public PyException(String typeName, String message, List<PyObject> args,
                       PyObject cause, PyObject context, Throwable javaCause) {
        this.typeName = typeName;
        this.message = message == null ? "" : message;
        this.args = args == null ? List.of() : List.copyOf(args);
        this.cause = cause;
        this.context = context;
        this.javaCause = javaCause;
    }

    private static List<PyObject> defaultArgs(String message) {
        if (message == null || message.isEmpty()) return List.of();
        return List.of(new PyStr(message));
    }

    /** Creates an exception value from a type name and a message. */
    public static PyException of(String typeName, String message) {
        return new PyException(typeName, message);
    }

    public String typeName() { return typeName; }
    public String message() { return message; }
    public List<PyObject> args() { return args; }
    public PyObject cause() { return cause; }
    public PyObject context() { return context; }
    public Throwable javaCause() { return javaCause; }

    @Override public String toString() {
        return message.isEmpty() ? typeName : typeName + ": " + message;
    }
}
