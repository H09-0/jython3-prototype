package org.example.jython3.runtime;

import java.util.ArrayList;
import java.util.Map;

/**
 * Python generator object produced by calling a function that contains {@code yield}.
 *
 * <p>The generator may be created either with raw positional args (which the
 * interpreter will bind when collecting yields) or with a pre-bound locals map
 * (produced by {@code bindArgs()} when the function uses defaults/kwargs).
 *
 * <p>Implementation strategy: we eagerly evaluate the entire generator function
 * the first time {@code next()} is called, collecting all yielded values into an
 * {@code ArrayList}. This avoids the need to pause/resume the bytecode VM.
 */
public final class PyGenerator implements PyObject {

    private final PyFunction fn;
    // Exactly one of these two is non-null:
    private final PyObject[] rawArgs;        // raw positional args (simple case)
    private final Map<String, PyObject> boundLocals;  // pre-bound locals (default/kw args case)

    private java.util.List<PyObject> yielded;
    private int index = 0;
    private boolean initialized = false;

    /** Simple constructor — positional args only. */
    public PyGenerator(PyFunction fn, PyObject[] args) {
        this.fn = fn;
        this.rawArgs = args;
        this.boundLocals = null;
    }

    /** Constructor with pre-bound locals (used when defaults/kwargs are present). */
    public PyGenerator(PyFunction fn, Map<String, PyObject> boundLocals) {
        this.fn = fn;
        this.rawArgs = null;
        this.boundLocals = boundLocals;
    }

    private void ensureInitialized() {
        if (initialized) return;
        initialized = true;

        PyCaller caller = PyCaller.CURRENT.get();
        if (caller == null) {
            yielded = new ArrayList<>();
            return;
        }
        if (boundLocals != null) {
            yielded = caller.collectYieldsWithLocals(fn, boundLocals);
        } else {
            yielded = caller.collectYields(fn, rawArgs);
        }
    }

    public PyObject next() {
        ensureInitialized();
        if (index >= yielded.size()) {
            throw PyErrors.error("StopIteration", "");
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
