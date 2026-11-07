package org.example.jython3.runtime;

import java.util.Collections;
import java.util.List;
import java.util.Objects;

/**
 * Immutable Python tuple sequence.
 * Distinct from {@link PyList}, hashable if its elements are hashable,
 * and cannot be mutated after creation.
 */
public final class PyTuple implements PyObject {

    public static final PyTuple EMPTY = new PyTuple(Collections.emptyList());

    private final List<PyObject> items;

    public PyTuple(List<PyObject> items) {
        Objects.requireNonNull(items, "items");
        this.items = List.copyOf(items);
    }

    public List<PyObject> items() {
        return items;
    }

    public int size() {
        return items.size();
    }

    public PyObject get(int index) {
        return items.get(index);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PyTuple other)) return false;
        if (items.size() != other.items.size()) return false;
        for (int i = 0; i < items.size(); i++) {
            if (!PyOps.pyEquals(items.get(i), other.items.get(i))) return false;
        }
        return true;
    }

    @Override
    public int hashCode() {
        int hash = 1;
        for (PyObject item : items) {
            hash = 31 * hash + item.hashCode();
        }
        return hash;
    }

    @Override
    public String toString() {
        if (items.isEmpty()) return "()";
        if (items.size() == 1) {
            return "(" + items.get(0).toString() + ",)";
        }
        var sb = new StringBuilder("(");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(items.get(i).toString());
        }
        return sb.append(")").toString();
    }
}
