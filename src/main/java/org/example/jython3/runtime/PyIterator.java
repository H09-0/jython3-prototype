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

    public PyIterator(List<PyObject> items, int cursor) {
        Objects.requireNonNull(items, "items");
        this.iterator = (cursor >= items.size())
                ? Collections.emptyIterator()
                : items.subList(cursor, items.size()).iterator();
    }

    public PyIterator(Iterator<PyObject> iterator) {
        this.iterator = Objects.requireNonNull(iterator, "iterator");
    }

    public boolean hasNext() {
        return iterator.hasNext();
    }

    public PyObject next() {
        return iterator.next();
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
