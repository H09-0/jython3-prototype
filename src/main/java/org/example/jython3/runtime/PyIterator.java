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
     * Creates a lazy PyIterator wrapping a PyGenerator without intermediate list collection.
     */
    public static PyIterator fromGenerator(PyGenerator gen) {
        Objects.requireNonNull(gen, "gen");
        return new PyIterator(new Iterator<PyObject>() {
            @Override
            public boolean hasNext() {
                return !gen.isDone();
            }

            @Override
            public PyObject next() {
                try {
                    return gen.next();
                } catch (InterpreterException ie) {
                    if ("StopIteration".equals(ie.typeName())) {
                        throw new NoSuchElementException();
                    }
                    throw ie;
                }
            }
        });
    }

    @Override
    public String toString() {
        return "<iterator>";
    }
}
