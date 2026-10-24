package org.example.jython3.runtime;

import java.util.List;

/** Calls a Python callable. The interpreter installs itself on the current thread. */
@FunctionalInterface
public interface PyCaller {
    PyObject call(PyObject callable, PyObject[] args);

    /**
     * Executes a generator function to completion and returns every yielded value.
     * The interpreter overrides this; other callers may not support generators.
     */
    default List<PyObject> collectYields(PyFunction fn, PyObject[] args) {
        throw new UnsupportedOperationException("yield collection is not supported by this caller");
    }

    ThreadLocal<PyCaller> CURRENT = new ThreadLocal<>();

    static PyCaller current() {
        return CURRENT.get();
    }
}
