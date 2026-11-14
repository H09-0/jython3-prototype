package org.example.jython3.runtime;

import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Set;

/**
 * Python set — mutable, unordered, no duplicates.
 * Hashability of elements is enforced at insertion time via PyKey.
 */
public final class PySet implements PyObject {

    private final Set<PyObject> items;

    public PySet(Set<PyObject> items) {
        Objects.requireNonNull(items, "items");
        this.items = items;
    }

    public Set<PyObject> items() { return items; }

    public void add(PyObject v) {
        // Validate hashability before inserting (raises TypeError for lists etc.)
        PyKey.checkHashable(v);
        items.add(v);
    }

    public int size() { return items.size(); }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PySet other)) return false;
        return items.equals(other.items);
    }

    @Override
    public int hashCode() { throw new UnsupportedOperationException("set is unhashable"); }

    @Override
    public String toString() {
        if (items.isEmpty()) return "set()";
        var sb = new StringBuilder("{");
        boolean first = true;
        for (var item : items) {
            if (!first) sb.append(", ");
            sb.append(item);
            first = false;
        }
        return sb.append("}").toString();
    }
}
