package org.example.jython3.runtime;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Python dictionary implementation with Python 3 hash and equality semantics.
 */
public final class PyDict implements PyObject {

    static final class QueryKey {
        private PyObject object;
        private int hashCode;

        QueryKey set(PyObject obj) {
            this.object = obj;
            this.hashCode = PyKey.computeHash(obj);
            return this;
        }

        void clear() {
            this.object = null;
        }

        public PyObject object() {
            return object;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o instanceof PyKey pk) {
                return PyOps.pyEquals(this.object, pk.object());
            }
            if (o instanceof QueryKey qk) {
                return PyOps.pyEquals(this.object, qk.object);
            }
            return false;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }
    }

    private static final ThreadLocal<QueryKey> QUERY_KEY = ThreadLocal.withInitial(QueryKey::new);
    private static final ThreadLocal<java.util.Set<Object>> REPR_SEEN =
            ThreadLocal.withInitial(() -> java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()));

    private final Map<PyKey, PyObject> entries = new LinkedHashMap<>();

    public PyDict() {}

    public PyDict(Map<PyObject, PyObject> initialMap) {
        if (initialMap != null) {
            for (var entry : initialMap.entrySet()) {
                put(entry.getKey(), entry.getValue());
            }
        }
    }

    public PyObject get(PyObject key) {
        QueryKey qk = QUERY_KEY.get();
        try {
            return entries.get(qk.set(key));
        } finally {
            qk.clear();
        }
    }

    public void put(PyObject key, PyObject value) {
        entries.put(new PyKey(key), value);
    }

    public PyObject remove(PyObject key) {
        QueryKey qk = QUERY_KEY.get();
        try {
            return entries.remove(qk.set(key));
        } finally {
            qk.clear();
        }
    }

    public boolean containsKey(PyObject key) {
        QueryKey qk = QUERY_KEY.get();
        try {
            return entries.containsKey(qk.set(key));
        } finally {
            qk.clear();
        }
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
    }

    public List<PyObject> keys() {
        var list = new ArrayList<PyObject>();
        for (PyKey k : entries.keySet()) list.add(k.object());
        return list;
    }

    public List<PyObject> values() {
        return new ArrayList<>(entries.values());
    }

    public List<List<PyObject>> itemsList() {
        var list = new ArrayList<List<PyObject>>();
        for (var e : entries.entrySet()) {
            list.add(List.of(e.getKey().object(), e.getValue()));
        }
        return list;
    }

    public Map<PyObject, PyObject> map() {
        var m = new LinkedHashMap<PyObject, PyObject>();
        for (var e : entries.entrySet()) m.put(e.getKey().object(), e.getValue());
        return m;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (o instanceof PyDict other) {
            if (this.size() != other.size()) return false;
            for (var e : entries.entrySet()) {
                PyObject otherVal = other.get(e.getKey().object());
                if (otherVal == null || !PyOps.pyEquals(e.getValue(), otherVal)) {
                    return false;
                }
            }
            return true;
        }
        return false;
    }

    @Override
    public int hashCode() {
        return entries.hashCode();
    }

    @Override
    public String toString() {
        var seen = REPR_SEEN.get();
        if (!seen.add(this)) return "{...}";
        try {
            var sb = new StringBuilder("{");
            int i = 0;
            for (var entry : entries.entrySet()) {
                if (i++ > 0) sb.append(", ");
                var k = entry.getKey().object();
                var v = entry.getValue();
                if (k instanceof PyStr s) sb.append("'").append(s.value()).append("'");
                else sb.append(k);
                sb.append(": ");
                if (v instanceof PyStr s) sb.append("'").append(s.value()).append("'");
                else sb.append(v);
            }
            return sb.append("}").toString();
        } finally {
            seen.remove(this);
        }
    }
}
