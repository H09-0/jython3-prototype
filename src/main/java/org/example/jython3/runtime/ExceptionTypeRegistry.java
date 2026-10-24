package org.example.jython3.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Owns the built-in exception types for one runtime.
 *
 * <p>Each interpreter creates its own registry, so the {@link PyClass}
 * instances used by its exception values are the same instances used by its
 * handlers. The registry only creates and stores types; the inheritance
 * relationships live in {@link PyClass} itself.
 *
 * <p>There is no shared parent table. A name is resolved to a class once, and
 * matching walks the real base list from that point on.
 */
public final class ExceptionTypeRegistry {

    private static final ThreadLocal<ExceptionTypeRegistry> CURRENT = new ThreadLocal<>();
    private static final ExceptionTypeRegistry FALLBACK = standard();

    private final Map<String, PyClass> types = new ConcurrentHashMap<>();
    private final PyClass baseException;
    private final PyClass exception;

    private ExceptionTypeRegistry() {
        baseException = define("BaseException", null);
        baseException.markExceptionRoot();
        exception = define("Exception", baseException);
        define("SystemExit", baseException);
        define("KeyboardInterrupt", baseException);
        define("GeneratorExit", baseException);

        var arithmetic = define("ArithmeticError", exception);
        var lookup = define("LookupError", exception);
        var os = define("OSError", exception);
        var importError = define("ImportError", exception);
        var nameError = define("NameError", exception);
        var runtimeError = define("RuntimeError", exception);
        var syntaxError = define("SyntaxError", exception);
        var valueError = define("ValueError", exception);
        var warning = define("Warning", exception);
        define("AssertionError", exception);
        define("AttributeError", exception);
        define("BufferError", exception);
        define("EOFError", exception);
        define("MemoryError", exception);
        define("ReferenceError", exception);
        define("StopAsyncIteration", exception);
        define("StopIteration", exception);
        define("SystemError", exception);
        define("TypeError", exception);

        define("ZeroDivisionError", arithmetic);
        define("OverflowError", arithmetic);
        define("FloatingPointError", arithmetic);
        define("IndexError", lookup);
        define("KeyError", lookup);
        define("ModuleNotFoundError", importError);
        var indentationError = define("IndentationError", syntaxError);
        define("TabError", indentationError);
        define("UnboundLocalError", nameError);
        define("RecursionError", runtimeError);
        define("NotImplementedError", runtimeError);
        define("FileNotFoundError", os);
        define("FileExistsError", os);
        define("PermissionError", os);
        define("IsADirectoryError", os);
        define("NotADirectoryError", os);
        define("TimeoutError", os);
        define("ConnectionError", os);
        var unicodeError = define("UnicodeError", valueError);
        define("UnicodeDecodeError", unicodeError);
        define("UnicodeEncodeError", unicodeError);
        define("DeprecationWarning", warning);
        define("UserWarning", warning);
        define("RuntimeWarning", warning);
        define("SyntaxWarning", warning);
        define("ResourceWarning", warning);

        // Runtime-specific types. They are ordinary exception classes and
        // inherit from Exception like any other Python error.
        define("SecurityError", exception);
        define("JavaException", exception);
        define("JavaError", exception);
    }

    /** Creates a registry with the full built-in exception hierarchy. */
    public static ExceptionTypeRegistry standard() {
        return new ExceptionTypeRegistry();
    }

    /**
     * The registry of the runtime currently running on this thread, or the
     * fallback used by code that calls the bridge without an interpreter.
     */
    public static ExceptionTypeRegistry current() {
        ExceptionTypeRegistry registry = CURRENT.get();
        return registry != null ? registry : FALLBACK;
    }

    /**
     * Makes {@code registry} current on this thread and returns the previous
     * one. Callers must pass the result to {@link #restore(ExceptionTypeRegistry)}.
     */
    public static ExceptionTypeRegistry install(ExceptionTypeRegistry registry) {
        ExceptionTypeRegistry previous = CURRENT.get();
        if (registry == null) CURRENT.remove();
        else CURRENT.set(registry);
        return previous;
    }

    /** Restores the registry returned by {@link #install(ExceptionTypeRegistry)}. */
    public static void restore(ExceptionTypeRegistry previous) {
        if (previous == null) CURRENT.remove();
        else CURRENT.set(previous);
    }

    private PyClass define(String name, PyClass parent) {
        PyClass type = new PyClass(name, parent, new LinkedHashMap<>());
        types.put(name, type);
        return type;
    }

    /**
     * Resolves an exception type name. Unknown names become ordinary
     * user-defined exception classes inheriting from {@code Exception}; the
     * created class is stable for the lifetime of this registry.
     */
    public PyClass type(String name) {
        if (name == null || name.isEmpty()) return exception;
        return types.computeIfAbsent(name,
                n -> new PyClass(n, exception, new LinkedHashMap<>()));
    }

    public PyClass baseException() { return baseException; }

    public PyClass exception() { return exception; }

    /** Whether {@code type} is {@code BaseException} or derives from it. */
    public boolean isExceptionClass(PyClass type) {
        return type != null && type.isExceptionType();
    }

    /** An unmodifiable view of the registered types, keyed by name. */
    public Map<String, PyClass> types() {
        return Collections.unmodifiableMap(types);
    }
}
