package org.example.jython3.runtime;

/** Calls a Python callable. The interpreter installs itself on the current thread. */
@FunctionalInterface
public interface PyCaller {
    PyObject call(PyObject callable, PyObject[] args);

    ThreadLocal<PyCaller> CURRENT = new ThreadLocal<>();

    static PyCaller current() {
        return CURRENT.get();
    }
}
