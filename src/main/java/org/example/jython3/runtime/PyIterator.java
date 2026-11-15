package org.example.jython3.runtime;

import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * Universal iterator over Python objects.
 * Supports lazy iteration over Java Iterables and PyGenerators without
 * eager collection allocation, enforcing execution limits per step.
 */
public final class PyIterator implements PyObject {

    private final Iterator<PyObject> iterator;
    private final boolean fastIntRange;
    private long rangeVal;
    private final long rangeStep;
    private long rangeRemaining;

    private PyIterator(long start, long step, long count) {
        this.iterator = null;
        this.fastIntRange = true;
        this.rangeVal = start;
        this.rangeStep = step;
        this.rangeRemaining = count;
    }

    public PyIterator(List<PyObject> items, int cursor) {
        Objects.requireNonNull(items, "items");
        this.iterator = (cursor >= items.size())
                ? Collections.emptyIterator()
                : items.subList(cursor, items.size()).iterator();
        this.fastIntRange = false;
        this.rangeStep = 0L;
    }

    public PyIterator(Iterator<PyObject> iterator) {
        this.iterator = Objects.requireNonNull(iterator, "iterator");
        this.fastIntRange = false;
        this.rangeStep = 0L;
    }

    public static PyIterator ofIntRange(long start, long step, long count) {
        return new PyIterator(start, step, count);
    }

    public boolean hasNext() {
        return fastIntRange ? rangeRemaining > 0 : iterator.hasNext();
    }

    public PyObject next() {
        if (fastIntRange) {
            if (rangeRemaining <= 0) throw new NoSuchElementException();
            int out = (int) rangeVal;
            rangeVal += rangeStep;
            rangeRemaining--;
            return PyInt.of(out);
        }
        return iterator.next();
    }

    /** Returns the next item or {@code null} when exhausted, avoiding double dispatch in {@code FOR_ITER}. */
    public PyObject fastNext() {
        if (fastIntRange) {
            if (rangeRemaining <= 0) return null;
            int out = (int) rangeVal;
            rangeVal += rangeStep;
            rangeRemaining--;
            return PyInt.of(out);
        }
        return iterator.hasNext() ? iterator.next() : null;
    }

    /**
     * Creates a lazy PyIterator wrapping a Java Iterable without eager copying.
     * Elements are converted to PyObject on demand, and budget/timeout limits are checked.
     */
    public static PyIterator fromJavaIterable(Iterable<?> iterable,
                                              org.example.jython3.security.JavaAccessPolicy policy,
                                              org.example.jython3.security.ExecutionContext ctx) {
        Objects.requireNonNull(iterable, "iterable");
        var javaIter = iterable.iterator();
        return new PyIterator(new Iterator<PyObject>() {
            @Override
            public boolean hasNext() {
                if (ctx != null) ctx.stepInstruction();
                return javaIter.hasNext();
            }

            @Override
            public PyObject next() {
                if (ctx != null) ctx.stepInstruction();
                Object raw = javaIter.next();
                return org.example.jython3.bridge.JavaBridge.toPyObject(raw, policy);
            }
        });
    }

    /**
     * Creates a lazy PyIterator wrapping a PyGenerator without eager list collection.
     * Pulls at most one item ahead on {@link Iterator#hasNext()}.
     */
    public static PyIterator fromGenerator(PyGenerator gen) {
        Objects.requireNonNull(gen, "gen");
        return new PyIterator(new Iterator<PyObject>() {
            private PyObject buffered = null;
            private boolean hasBuffered = false;
            private boolean exhausted = false;

            @Override
            public boolean hasNext() {
                if (hasBuffered) return true;
                if (exhausted) return false;
                try {
                    buffered = gen.next();
                    hasBuffered = true;
                    return true;
                } catch (InterpreterException ie) {
                    if ("StopIteration".equals(ie.typeName())) {
                        exhausted = true;
                        return false;
                    }
                    exhausted = true;
                    throw ie;
                }
            }

            @Override
            public PyObject next() {
                if (!hasNext()) {
                    throw new NoSuchElementException();
                }
                PyObject val = buffered;
                buffered = null;
                hasBuffered = false;
                return val;
            }
        });
    }

    @Override
    public String toString() {
        return "<iterator>";
    }
}
