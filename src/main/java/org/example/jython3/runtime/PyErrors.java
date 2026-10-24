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
        return new InterpreterException(PyException.of(typeName, stripPrefix(typeName, message)));
    }

    /** Removes a duplicated "Type: " prefix so printing never repeats the type. */
    private static String stripPrefix(String typeName, String message) {
        if (message == null) return "";
        String prefix = typeName + ":";
        if (message.startsWith(prefix)) {
            String rest = message.substring(prefix.length());
            return rest.startsWith(" ") ? rest.substring(1) : rest;
        }
        return message;
    }

    /**
     * Python-style exception matching. This is the single place that decides
     * whether a raised value is caught by {@code except <typeName>}. When the
     * class hierarchy lands, only this method needs to change.
     */
    public static boolean matches(PyObject value, String typeName) {
        if (typeName == null || typeName.isEmpty()) return true;
        if (typeName.equals("Exception") || typeName.equals("BaseException")) return true;

        if (value instanceof PyException pe) {
            if (pe.typeName().equals(typeName)) return true;
            if (pe.javaCause() != null) return typeName.equals(javaTypeName(pe.javaCause()));
            if (pe.typeName().equals("JavaException")) return typeName.equals(javaTypeName(pe.message()));
            return false;
        }

        String text = value == null ? "" : value.toString();
        return text.equals(typeName) || text.startsWith(typeName + ":") || text.startsWith(typeName + "(");
    }

    /**
     * Maps a Java throwable to the Python type it should be caught as. The
     * table is consulted from the concrete class upwards, so a specific entry
     * wins over a superclass entry.
     */
    private static final java.util.Map<String, String> JAVA_TYPES = java.util.Map.of(
            "ArrayIndexOutOfBoundsException", "IndexError",
            "IndexOutOfBoundsException", "IndexError",
            "NoSuchElementException", "KeyError",
            "NumberFormatException", "ValueError",
            "IllegalArgumentException", "ValueError",
            "ClassCastException", "TypeError",
            "ArithmeticException", "ZeroDivisionError",
            "UnsupportedOperationException", "TypeError");

    public static String javaTypeName(Throwable throwable) {
        for (Class<?> c = throwable.getClass(); c != null; c = c.getSuperclass()) {
            String mapped = JAVA_TYPES.get(c.getSimpleName());
            if (mapped != null) return mapped;
        }
        return "JavaException";
    }

    /** Maps legacy "fqcn: message" text produced by older bridge call sites. */
    private static String javaTypeName(String message) {
        int colon = message.indexOf(':');
        String fqcn = colon < 0 ? message : message.substring(0, colon);
        String simple = fqcn.substring(fqcn.lastIndexOf('.') + 1);
        return JAVA_TYPES.getOrDefault(simple, "JavaException");
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
