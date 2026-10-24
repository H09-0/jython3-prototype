package org.example.jython3.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.example.jython3.compile.CompiledCode;

public final class PyIterator implements PyObject {
    private final List<PyObject> items; private int cursor;
    public PyIterator(List<PyObject> items, int cursor) { this.items = items; this.cursor = cursor; }
    public boolean hasNext() { return cursor < items.size(); }
    public PyObject next() { return items.get(cursor++); }
    @Override public String toString() { return "<iterator>"; }
}
