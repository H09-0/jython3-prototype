package org.example.jython3.runtime;

import java.util.Arrays;
import java.util.NoSuchElementException;

/**
 * High-performance array-backed operand stack for the Jython 3 virtual machine.
 * Replaces ArrayDeque to eliminate bitmask head/tail arithmetic and virtual call overhead.
 */
public final class FastStack {

    private PyObject[] elements;
    private int sp;

    public FastStack() {
        this(64);
    }

    public FastStack(int initialCapacity) {
        this.elements = new PyObject[Math.max(16, initialCapacity)];
        this.sp = 0;
    }

    public void push(PyObject val) {
        if (sp == elements.length) {
            elements = Arrays.copyOf(elements, elements.length << 1);
        }
        elements[sp++] = val;
    }

    public PyObject pop() {
        if (sp == 0) {
            throw new NoSuchElementException("Stack underflow");
        }
        PyObject val = elements[--sp];
        elements[sp] = null;
        return val;
    }

    public PyObject peek() {
        if (sp == 0) {
            return null;
        }
        return elements[sp - 1];
    }

    public PyObject get(int index) {
        if (index < 0 || index >= sp) {
            throw new IndexOutOfBoundsException("Index " + index + ", size " + sp);
        }
        return elements[index];
    }

    public int size() {
        return sp;
    }

    public boolean isEmpty() {
        return sp == 0;
    }

    public void clear() {
        Arrays.fill(elements, 0, sp, null);
        sp = 0;
    }
}
