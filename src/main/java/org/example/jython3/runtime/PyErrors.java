package org.example.jython3.runtime;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single place that builds Python errors. New code should call
 * {@link #error(String, String)} instead of constructing
 * {@link InterpreterException} with a formatted string.
 */
public final class PyErrors {
    private PyErrors() {}

    /** Matches "TypeName: message", "TypeName:message", or a bare "TypeName". */
    private static final Pattern PREFIX =
            Pattern.compile("^([A-Za-z_][A-Za-z0-9_]*)(?::\\s?|\\s+-\\s+|$)(.*)$", Pattern.DOTALL);

    /** Creates a throwable carrying a Python exception value. */
    public static InterpreterException error(String typeName, String message) {
        return new InterpreterException(PyException.of(typeName, message));
    }

    /**
     * Builds a payload for text produced by legacy call sites. The printed
     * message is preserved verbatim by {@link InterpreterException}; this only
     * extracts the type so matching can use it.
     */
    public static PyObject fromMessage(String text) {
        if (text == null || text.isEmpty()) return PyException.of("Exception", "");
        Matcher m = PREFIX.matcher(text);
        if (m.matches()) {
            String name = m.group(1);
            String rest = m.group(2);
            if (looksLikeExceptionName(name)) {
                return PyException.of(name, rest);
            }
        }
        return PyException.of("Exception", text);
    }

    private static boolean looksLikeExceptionName(String name) {
        return name.endsWith("Error") || name.endsWith("Exception") || name.endsWith("Exit")
                || name.endsWith("Interrupt") || name.endsWith("Warning") || name.endsWith("Iteration");
    }
}
