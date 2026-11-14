package org.example.jython3.runtime;

import java.util.Objects;

/**
 * Shared mutable cell for closures and nonlocal variables.
 * Allows nested scopes to read and write the same underlying binding.
 */
public final class PyCell implements PyObject {
    private PyObject value;

    public PyCell(PyObject initial) {
        this.value = initial;
    }

    public PyObject get() {
        return value;
    }

    public void set(PyObject value) {
        this.value = value;
    }

    public boolean isBound() {
        return value != null;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof PyCell other)) return false;
        return Objects.equals(value, other.value);
    }

    @Override
    public int hashCode() {
        return value != null ? value.hashCode() : 0;
    }

    @Override
    public String toString() {
        return value != null ? value.toString() : "<empty cell>";
    }
}
