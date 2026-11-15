package org.example.jython3.runtime;

import java.util.List;
import java.util.Map;

/** Calls a Python callable. The interpreter installs itself on the current thread. */
@FunctionalInterface
public interface PyCaller {
    PyObject call(PyObject callable, PyObject[] args);

    /**
     * Advances a lazy {@link PyGenerator} by one step until its next {@code yield},
     * completion ({@code StopIteration}), or exception.
     */
    default PyObject stepGenerator(PyGenerator gen) {
        throw new UnsupportedOperationException("generator stepping is not supported by this caller");
    }

    /**
     * Resolves Python 3 zero-argument {@code super()} using the active frame's
     * function and first parameter instance.
     */
    default PyObject resolveZeroArgSuper() {
        throw PyErrors.error("RuntimeError", "super(): no arguments");
    }

    /** Returns the active frame's local scope (or globals at module level) if available. */
    default Map<String, PyObject> currentScope() {
        return null;
    }

    ThreadLocal<PyCaller> CURRENT = new ThreadLocal<>();

    static PyCaller current() {
        return CURRENT.get();
    }
}
