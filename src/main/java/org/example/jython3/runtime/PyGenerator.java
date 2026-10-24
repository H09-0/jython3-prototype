package org.example.jython3.runtime;

import java.util.ArrayList;

/**
 * Python generator object produced by calling a function that contains {@code yield}.
 *
 * <p>Implementation strategy: we eagerly evaluate the entire generator function once
 * at construction time, collecting all yielded values into an {@code ArrayList}.
 * This is equivalent to CPython's lazy approach for the subset of generators supported
 * by our interpreter (no {@code send()}, no {@code throw()}), and avoids the need to
 * pause / resume the bytecode VM across calls.
 *
 * <p>Iteration is then O(1) per {@code next()} call after the initial eager evaluation.
 */
public final class PyGenerator implements PyObject {

    private final PyFunction fn;
    private final PyObject[] args;
    private java.util.List<PyObject> yielded;
    private int index = 0;
    private boolean initialized = false;

    public PyGenerator(PyFunction fn, PyObject[] args) {
        this.fn = fn;
        this.args = args;
    }

    /**
     * Lazily initializes the generator on the first {@code next()} call.
     * Uses {@link PyCaller#CURRENT} to reuse the running interpreter instance.
     */
    private void ensureInitialized() {
        if (initialized) return;
        initialized = true;

        PyCaller caller = PyCaller.CURRENT.get();
        if (caller == null) {
            yielded = new ArrayList<>();
            return;
        }
        yielded = caller.collectYields(fn, args);
    }

    /**
     * Returns the next yielded value, or throws {@code StopIteration} when exhausted.
     */
    public PyObject next() {
        ensureInitialized();
        if (index >= yielded.size()) {
            throw new InterpreterException("StopIteration");
        }
        return yielded.get(index++);
    }

    public boolean isDone() {
        ensureInitialized();
        return index >= yielded.size();
    }

    @Override
    public String toString() {
        return "<generator object " + fn.name() + ">";
    }
}
