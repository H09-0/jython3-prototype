package org.example.jython3.runtime;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;

/**
 * Immutable, hashable Python {@code frozenset}.
 *
 * <p>Elements are keyed by {@link PyKey}, sharing Python's hash/equality semantics
 * with {@link PySet} and {@link PyDict}. Because it is immutable, {@code frozenset}
 * instances can be stored as dict keys and set elements and compute an
 * order-independent hash over their elements.
 */
public final class PyFrozenSet implements PyObject {

    public static final PyFrozenSet EMPTY = new PyFrozenSet();

    private final Map<PyKey, PyObject> entries = new LinkedHashMap<>();
    private final int cachedHash;

    public PyFrozenSet() {
        this.cachedHash = computeSetHash(entries.keySet());
    }

    public PyFrozenSet(Collection<? extends PyObject> items) {
        if (items != null) {
            for (PyObject item : items) {
                entries.putIfAbsent(new PyKey(item), item);
            }
        }
        this.cachedHash = computeSetHash(entries.keySet());
    }

    private PyFrozenSet(Map<PyKey, PyObject> prebuilt) {
        this.entries.putAll(prebuilt);
        this.cachedHash = computeSetHash(entries.keySet());
    }

    /** Internal factory from an already-validated key map. */
    public static PyFrozenSet fromValidatedMap(Map<PyKey, PyObject> map) {
        return map.isEmpty() ? EMPTY : new PyFrozenSet(map);
    }

    private static int computeSetHash(Set<PyKey> keys) {
        // Commutative mixing so equal frozensets have identical hash regardless of insertion order
        int h = 0x517cc1b7;
        for (PyKey k : keys) {
            int kh = k.hashCode();
            kh ^= (kh >>> 16);
            kh *= 0x45d9f3b;
            kh ^= (kh >>> 16);
            h += kh;
        }
        return h;
    }

    public Collection<PyObject> items() {
        return Collections.unmodifiableCollection(entries.values());
    }

    public Set<PyKey> keySet() {
        return Collections.unmodifiableSet(entries.keySet());
    }

    public boolean contains(PyObject v) {
        return entries.containsKey(new PyKey(v));
    }

    public boolean containsKey(PyKey k) {
        return entries.containsKey(k);
    }

    public int size() { return entries.size(); }

    public boolean isEmpty() { return entries.isEmpty(); }

    @Override
    public int hashCode() {
        return cachedHash;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o instanceof PyFrozenSet other) {
            if (size() != other.size()) return false;
            return entries.keySet().containsAll(other.entries.keySet());
        }
        if (o instanceof PySet other) {
            if (size() != other.size()) return false;
            return entries.keySet().containsAll(other.keySet());
        }
        return false;
    }

    @Override
    public String toString() {
        if (entries.isEmpty()) return "frozenset()";
        var sb = new StringBuilder("frozenset({");
        boolean first = true;
        for (var item : entries.values()) {
            if (!first) sb.append(", ");
            if (item instanceof PyStr s) sb.append('\'').append(s.value()).append('\'');
            else sb.append(item);
            first = false;
        }
        return sb.append("})").toString();
    }
}
