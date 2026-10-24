package org.example.jython3.runtime;

import java.util.HashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Single place that builds and matches Python errors. New code should call
 * {@link #error(String, String)} instead of constructing
 * {@link InterpreterException} with a formatted string, and Java throwables
 * should enter through {@link #fromJava(Throwable)}.
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
     * Wraps a JVM throwable as a Python exception value. Returns the original
     * throwable when it already is an {@link InterpreterException}, so Python
     * errors raised inside reflective calls do not turn into
     * {@code JavaException}.
     */
    public static InterpreterException fromJava(Throwable throwable) {
        if (throwable instanceof InterpreterException ie) return ie;
        Throwable cause = unwrap(throwable);
        String typeName = javaTypeName(cause);
        String message = javaMessage(cause, typeName);
        return new InterpreterException(PyException.withJavaCause(typeName, message, cause));
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
     * Python-style exception matching. This is the single place that decides
     * whether a raised value is caught by {@code except <typeName>}. Matching
     * follows the real type hierarchy rather than the type name alone, so
     * {@code except Exception} does not catch {@code SystemExit},
     * {@code KeyboardInterrupt}, or {@code GeneratorExit}.
     */
    public static boolean matches(PyObject value, String typeName) {
        if (typeName == null || typeName.isEmpty()) return true;

        if (value instanceof PyException pe) {
            if (isInstanceOf(pe.typeName(), typeName)) return true;
            if (pe.javaCause() != null
                    && isInstanceOf(javaTypeName(pe.javaCause()), typeName)) {
                return true;
            }
            // Values created before the bridge migration carry the Java class
            // name in the message instead of a javaCause.
            if (pe.typeName().equals("JavaException") && !pe.message().isEmpty()) {
                return isInstanceOf(javaTypeName(pe.message()), typeName);
            }
            return false;
        }

        // Legacy non-exception values. Raised strings and numbers are not
        // Python exceptions, so only an exact textual match applies.
        String text = value == null ? "" : value.toString();
        return text.equals(typeName) || text.startsWith(typeName + ":") || text.startsWith(typeName + "(");
    }

    /** Whether {@code typeName} is {@code ancestor} or derives from it. */
    public static boolean isInstanceOf(String typeName, String ancestor) {
        if (typeName == null || ancestor == null) return false;
        if (typeName.equals(ancestor)) return true;
        String current = typeName;
        for (int depth = 0; depth < 64; depth++) {
            String parent = parentOf(current);
            if (parent == null || parent.isEmpty()) return false;
            if (parent.equals(ancestor)) return true;
            current = parent;
        }
        return false;
    }

    /**
     * The parent of a Python exception type. Unknown names are treated as
     * user-defined exception classes, which inherit from {@code Exception}.
     */
    private static String parentOf(String typeName) {
        if (typeName == null || typeName.isEmpty()) return "";
        String known = PARENTS.get(typeName);
        if (known != null) return known;
        return "Exception";
    }

    private static final Map<String, String> PARENTS = buildParents();

    private static Map<String, String> buildParents() {
        Map<String, String> m = new HashMap<>();
        // Direct subclasses of BaseException.
        m.put("BaseException", "");
        m.put("Exception", "BaseException");
        m.put("SystemExit", "BaseException");
        m.put("KeyboardInterrupt", "BaseException");
        m.put("GeneratorExit", "BaseException");
        // Direct subclasses of Exception.
        m.put("ArithmeticError", "Exception");
        m.put("AssertionError", "Exception");
        m.put("AttributeError", "Exception");
        m.put("BufferError", "Exception");
        m.put("EOFError", "Exception");
        m.put("ImportError", "Exception");
        m.put("LookupError", "Exception");
        m.put("MemoryError", "Exception");
        m.put("NameError", "Exception");
        m.put("OSError", "Exception");
        m.put("ReferenceError", "Exception");
        m.put("RuntimeError", "Exception");
        m.put("StopAsyncIteration", "Exception");
        m.put("StopIteration", "Exception");
        m.put("SyntaxError", "Exception");
        m.put("SystemError", "Exception");
        m.put("TypeError", "Exception");
        m.put("ValueError", "Exception");
        m.put("Warning", "Exception");
        // Deeper levels.
        m.put("ZeroDivisionError", "ArithmeticError");
        m.put("OverflowError", "ArithmeticError");
        m.put("FloatingPointError", "ArithmeticError");
        m.put("IndexError", "LookupError");
        m.put("KeyError", "LookupError");
        m.put("ModuleNotFoundError", "ImportError");
        m.put("IndentationError", "SyntaxError");
        m.put("TabError", "IndentationError");
        m.put("UnboundLocalError", "NameError");
        m.put("RecursionError", "RuntimeError");
        m.put("NotImplementedError", "RuntimeError");
        m.put("FileNotFoundError", "OSError");
        m.put("FileExistsError", "OSError");
        m.put("PermissionError", "OSError");
        m.put("IsADirectoryError", "OSError");
        m.put("NotADirectoryError", "OSError");
        m.put("TimeoutError", "OSError");
        m.put("ConnectionError", "OSError");
        m.put("UnicodeError", "ValueError");
        m.put("UnicodeDecodeError", "UnicodeError");
        m.put("UnicodeEncodeError", "UnicodeError");
        m.put("DeprecationWarning", "Warning");
        m.put("UserWarning", "Warning");
        m.put("RuntimeWarning", "Warning");
        m.put("SyntaxWarning", "Warning");
        m.put("ResourceWarning", "Warning");
        // Runtime-specific types. They are not CPython built-ins but behave as
        // ordinary exception classes and inherit from Exception.
        m.put("SecurityError", "Exception");
        m.put("JavaException", "Exception");
        m.put("JavaError", "Exception");
        return Map.copyOf(m);
    }

    // ─── Java throwable mapping ──────────────────────────────────────────

    /**
     * Maps a Java throwable to the Python type it should be caught as. The
     * table is consulted from the concrete class upwards, so a specific entry
     * wins over a superclass entry.
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
