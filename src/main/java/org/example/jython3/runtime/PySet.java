package org.example.jython3.runtime;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Python set — mutable, unordered (insertion-ordered here), no duplicates.
 *
 * <p>Elements are keyed by {@link PyKey}, so membership, de-duplication and equality
 * follow Python's hash/eq contract exactly like {@link PyDict} keys:
 * {@code 1 == 1.0 == True} are one element, {@code (1, 2)} and {@code (1.0, 2)} are one
 * element, and unhashable values ({@code list}, {@code dict}, {@code set}) raise
 * {@code TypeError: unhashable type} on insertion and on lookup.
 */
public final class PySet implements PyObject {

    /** Key → first inserted element (Python keeps the first-inserted representative). */
    private final Map<PyKey, PyObject> entries = new LinkedHashMap<>();

    public PySet() {}

    public PySet(Collection<? extends PyObject> items) {
        if (items != null) {
            for (PyObject item : items) add(item);
        }
    }

    /** Read-only live view of the elements in insertion order. */
    public Collection<PyObject> items() {
        return Collections.unmodifiableCollection(entries.values());
    }

    /** Adds {@code v}; raises {@code TypeError} if it is unhashable. Existing equal element is kept. */
    public void add(PyObject v) {
        entries.putIfAbsent(new PyKey(v), v);
    }

    /** Python {@code v in set}; raises {@code TypeError} if {@code v} is unhashable. */
    public boolean contains(PyObject v) {
        return entries.containsKey(new PyKey(v));
    }

    public int size() { return entries.size(); }

    public boolean isEmpty() { return entries.isEmpty(); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PySet other)) return false;
        if (size() != other.size()) return false;
        for (PyKey k : entries.keySet()) {
            if (!other.entries.containsKey(k)) return false;
        }
        return true;
    }

    @Override
    public int hashCode() { throw new UnsupportedOperationException("set is unhashable"); }

    @Override
    public String toString() {
        if (entries.isEmpty()) return "set()";
        var sb = new StringBuilder("{");
        boolean first = true;
        for (var item : entries.values()) {
            if (!first) sb.append(", ");
            if (item instanceof PyStr s) sb.append('\'').append(s.value()).append('\'');
            else sb.append(item);
            first = false;
        }
        return sb.append("}").toString();
    }
}
