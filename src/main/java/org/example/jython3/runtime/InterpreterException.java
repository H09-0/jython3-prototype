package org.example.jython3.runtime;

import java.util.ArrayList;
import java.util.List;

/**
 * A Python-level error. Carries the source location where it was raised and,
 * as the VM unwinds, one entry per frame that the error passed through.
 *
 * <p>The throwable is only the transport. The Python value is available from
 * {@link #value()}: it is a {@link PyException}, including for user-defined
 * exception classes.
 */
public class InterpreterException extends RuntimeException {
    /**
     * Python errors are raised often (for example {@code StopIteration} on
     * every exhausted loop). Filling a Java stack trace for each one costs
     * time and is never printed, so it is off unless debugging is requested
     * with {@code -Djython3.stacktraces=true}.
     */
    private static final boolean FILL_JAVA_STACK =
            Boolean.getBoolean("jython3.stacktraces");

    private final PyObject value;
    private String file;
    private int line;
    private String function;
    private final List<TraceEntry> traceback = new ArrayList<>();

    /** One frame in a traceback, ordered from outermost to innermost. */
    public record TraceEntry(String file, int line, String function) {}

    /**
     * Builds an error from the legacy {@code "Type: message"} text. The text
     * is kept verbatim for printing, while the type is parsed into a value so
     * matching can use it.
     */
    public InterpreterException(String message) {
        this(PyErrors.fromMessage(message), message);
    }

    /** Builds an error that carries a Python value. */
    public InterpreterException(PyObject value) {
        this(value, describe(value));
    }

    private InterpreterException(PyObject value, String message) {
        super(message, null, false, FILL_JAVA_STACK);
        this.value = value;
    }

    private static String describe(PyObject value) {
        return value == null ? "" : value.toString();
    }

    /** The Python value being raised. */
    public PyObject value() { return value; }

    /** The exception type name, or {@code null} when the value is not a PyException. */
    public String typeName() {
        return value instanceof PyException pe ? pe.typeName() : null;
    }

    /** Records where the error was raised, unless a location is already set. */
    public void setLocation(String file, int line, String function) {
        if (this.line > 0) return;
        this.file = file;
        this.line = line;
        this.function = function;
    }

    /** Adds the next frame as the error unwinds. */
    public void addFrame(String file, int line, String function) {
        traceback.add(new TraceEntry(file, line, function));
    }

    public String file() { return file; }
    public int line() { return line; }
    public String function() { return function; }
    public List<TraceEntry> traceback() { return traceback; }
}
