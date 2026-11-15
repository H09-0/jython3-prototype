package org.example.jython3.runtime;

import java.util.Objects;

/**
 * Native Python 3 coroutine object returned by {@code async def} functions (PEP 492).
 *
 * <p>Distinct from {@link PyGenerator}: does not implement {@code __iter__} or
 * {@code __next__} directly (cannot be iterated with {@code for}/{@code iter()}),
 * implements {@code send()}, {@code throw()}, {@code close()}, and {@code __await__()},
 * and enforces PEP 479 ({@code StopIteration} inside coroutine becomes {@code RuntimeError}).
 */
public final class PyCoroutine implements PyObject {

    private final PyGenerator runner;

    public PyCoroutine(PyGenerator runner) {
        this.runner = Objects.requireNonNull(runner, "runner");
    }

    public PyGenerator runner() {
        return runner;
    }

    public PyFunction fn() {
        return runner.fn();
    }

    public PyGenerator.State state() {
        return runner.state();
    }

    public PyObject send(PyObject value) {
        return runner.send(value);
    }

    public PyObject throwException(PyObject[] args) {
        return runner.throwException(args);
    }

    public PyObject close() {
        return runner.close();
    }

    @Override
    public String toString() {
        return "<coroutine object " + runner.fn().name() + ">";
    }
}
