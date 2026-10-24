package org.example.jython3.runtime;

import java.util.List;


public final class PyIterator implements PyObject {
    private final List<PyObject> items; private int cursor;
    public PyIterator(List<PyObject> items, int cursor) { this.items = items; this.cursor = cursor; }
    public boolean hasNext() { return cursor < items.size(); }
    public PyObject next() { return items.get(cursor++); }
    @Override public String toString() { return "<iterator>"; }
}
