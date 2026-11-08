package org.example.jython3.runtime;

import java.util.List;
import java.util.Map;

/** Calls a Python callable. The interpreter installs itself on the current thread. */
@FunctionalInterface
public interface PyCaller {
    PyObject call(PyObject callable, PyObject[] args);

    /**
     * Executes a generator function to completion (using raw positional args)
     * and returns every yielded value.
     */
    default List<PyObject> collectYields(PyFunction fn, PyObject[] args) {
        throw new UnsupportedOperationException("yield collection is not supported by this caller");
    }

    /**
     * Executes a generator function to completion (using a pre-bound locals map,
     * produced by {@code bindArgs()} when defaults/kwargs are involved)
     * and returns every yielded value.
     */
    default List<PyObject> collectYieldsWithLocals(PyFunction fn, Map<String, PyObject> locals) {
        throw new UnsupportedOperationException("yield collection is not supported by this caller");
    }

    ThreadLocal<PyCaller> CURRENT = new ThreadLocal<>();

    static PyCaller current() {
        return CURRENT.get();
    }
}
