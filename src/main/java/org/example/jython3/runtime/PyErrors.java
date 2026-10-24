package org.example.jython3.runtime;

import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single place that builds and matches Python errors. New code should call
 * {@link #error(String, String)} instead of constructing
 * {@link InterpreterException} with a formatted string, and Java throwables
 * should enter through {@link #fromJava(Throwable)}.
 *
 * <p>Every exception value carries a {@link PyClass}. A type name is only a
 * lookup key at the entry points that still receive text; once the value
 * exists, matching walks the class's real base list.
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

    /**
     * Wraps an existing Python exception value, such as a user-defined
     * exception instance, so it can be thrown and later re-raised.
     */
    public static InterpreterException errorValue(PyObject value) {
        return new InterpreterException(value);
    }

    /**
     * Wraps a JVM throwable as a Python exception value. Returns the original
     * throwable when it already is an {@link InterpreterException}, so Python
     * errors raised inside reflective calls do not turn into
     * {@code JavaException}.
     *
     * <p>The Java table chooses the Python type; it does not create Python
     * inheritance. The chosen type is resolved in the active runtime's
     * {@link ExceptionTypeRegistry}, and the original throwable is preserved in
     * {@code javaCause}.
     */
    public static InterpreterException fromJava(Throwable throwable) {
        if (throwable instanceof InterpreterException ie) return ie;
        Throwable cause = unwrap(throwable);
        String typeName = javaTypeName(cause);
        String message = javaMessage(cause, typeName);
        PyClass type = ExceptionTypeRegistry.current().type(typeName);
        return new InterpreterException(PyException.withJavaCause(type, message, cause));
    }

    private static Throwable unwrap(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null
                && (current instanceof java.lang.reflect.InvocationTargetException
                    || current instanceof java.lang.reflect.UndeclaredThrowableException
                    || current instanceof java.lang.ExceptionInInitializerError)) {
            current = current.getCause();
        }
        return current;
    }

    private static String javaMessage(Throwable throwable, String typeName) {
        String message = throwable.getMessage();
        boolean blank = message == null || message.isBlank();
        if ("JavaException".equals(typeName)) {
            return blank ? throwable.getClass().getName()
                    : throwable.getClass().getName() + ": " + message;
        }
        return blank ? throwable.getClass().getName() : message;
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

    // ─── Matching ────────────────────────────────────────────────────────

    /**
     * Python-style exception matching against a type name resolved in the
     * active runtime. Prefer {@link #matches(PyObject, PyClass)} when the
     * handler already has a class object.
     */
    public static boolean matches(PyObject value, String typeName) {
        if (typeName == null || typeName.isEmpty()) return true;
        return matches(value, ExceptionTypeRegistry.current().type(typeName));
    }

    /**
     * Python-style exception matching. This is the single place that decides
     * whether a raised value is caught by a handler type, using the class's
     * real base list rather than a name table. {@code Exception} therefore does
     * not catch {@code SystemExit}, {@code KeyboardInterrupt}, or
     * {@code GeneratorExit}.
     */
    public static boolean matches(PyObject value, PyClass type) {
        if (type == null) return true;

        if (value instanceof PyException pe) {
            if (pe.type().isSubclassOf(type)) return true;
            if (pe.javaCause() != null) {
                PyClass mapped = ExceptionTypeRegistry.current().type(javaTypeName(pe.javaCause()));
                if (mapped.isSubclassOf(type)) return true;
            }
            // Values created before the bridge migration carry the Java class
            // name in the message instead of a javaCause.
            if (pe.typeName().equals("JavaException") && !pe.message().isEmpty()) {
                PyClass mapped = ExceptionTypeRegistry.current().type(javaTypeName(pe.message()));
                if (mapped.isSubclassOf(type)) return true;
            }
            return false;
        }

        // Legacy non-exception values. Raised strings and numbers are not
        // Python exceptions, so only an exact textual match applies.
        String text = value == null ? "" : value.toString();
        String name = type.name();
        return text.equals(name) || text.startsWith(name + ":") || text.startsWith(name + "(");
    }

    // ─── Java throwable mapping ──────────────────────────────────────────

    /**
     * Maps a Java throwable to the Python type it should be caught as. The
     * table is consulted from the concrete class upwards, so a specific entry
     * wins over a superclass entry. This mapping is separate from Python
     * inheritance.
     */
    private static final Map<String, String> JAVA_TYPES = Map.ofEntries(
            Map.entry("ArrayIndexOutOfBoundsException", "IndexError"),
            Map.entry("StringIndexOutOfBoundsException", "IndexError"),
            Map.entry("IndexOutOfBoundsException", "IndexError"),
            Map.entry("NoSuchElementException", "KeyError"),
            Map.entry("NumberFormatException", "ValueError"),
            Map.entry("IllegalArgumentException", "ValueError"),
            Map.entry("ClassCastException", "TypeError"),
            Map.entry("ArithmeticException", "ZeroDivisionError"),
            Map.entry("UnsupportedOperationException", "TypeError"),
            Map.entry("FileNotFoundException", "FileNotFoundError"),
            Map.entry("SecurityException", "PermissionError"),
            Map.entry("NullPointerException", "TypeError"));

    private static final String UNKNOWN_JAVA = "JavaException";

    /** The Python type name for a Java throwable, or {@code JavaException}. */
    public static String javaTypeName(Throwable throwable) {
        if (throwable == null) return UNKNOWN_JAVA;
        for (Class<?> c = throwable.getClass(); c != null; c = c.getSuperclass()) {
            String mapped = JAVA_TYPES.get(c.getSimpleName());
            if (mapped != null) return mapped;
        }
        return UNKNOWN_JAVA;
    }

    /** Maps legacy "fqcn: message" text produced by older bridge call sites. */
    private static String javaTypeName(String message) {
        if (message == null || message.isEmpty()) return UNKNOWN_JAVA;
        int colon = message.indexOf(':');
        String fqcn = colon < 0 ? message : message.substring(0, colon);
        String simple = fqcn.substring(fqcn.lastIndexOf('.') + 1);
        return JAVA_TYPES.getOrDefault(simple, UNKNOWN_JAVA);
    }

    // ─── Legacy text parsing ─────────────────────────────────────────────

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
                || name.endsWith("Interrupt") || name.endsWith("Warning") || name.endsWith("Iteration")
                || name.equals("StopIteration");
    }
}
