package org.example.jython3.runtime;

import java.util.HashMap;
import java.util.Map;


public final class PyInstance implements PyObject {
    private final PyClass pyClass;
    private final Map<String, PyObject> fields = new HashMap<>();
    private Map<String, PyBoundMethod> boundMethodCache;
    private long lastClassVersion = -1;

    public PyInstance(PyClass pyClass) {
        this.pyClass = pyClass;
    }

    public PyClass pyClass() {
        return pyClass;
    }

    public boolean isInstanceOf(PyClass target) {
        PyClass c = pyClass;
        while (c != null) {
            if (c == target || c.name().equals(target.name())) return true;
            c = c.baseClass();
        }
        return false;
    }

    public PyObject getField(String name) {
        PyObject val = fields.get(name);
        if (val != null) {
            return val;
        }
        PyObject member = pyClass.lookupMember(name);
        if (member instanceof PyFunction fn) {
            long currentVer = pyClass.version();
            if (boundMethodCache == null) {
                boundMethodCache = new HashMap<>(4);
                lastClassVersion = currentVer;
            } else if (lastClassVersion != currentVer) {
                boundMethodCache.clear();
                lastClassVersion = currentVer;
            }
            PyBoundMethod cached = boundMethodCache.get(name);
            if (cached != null) return cached;
            PyBoundMethod bm = new PyBoundMethod(this, fn);
            boundMethodCache.put(name, bm);
            return bm;
        }
        if (member != null) {
            return member;
        }
        if (fields.containsKey(name)) {
            return fields.get(name);
        }
        throw PyErrors.error("AttributeError", "'" + pyClass.name() + "' object has no attribute '" + name + "'");
    }

    public void setField(String name, PyObject val) {
        fields.put(name, val);
        if (boundMethodCache != null) {
            boundMethodCache.remove(name);
        }
    }

    public Map<String, PyObject> fields() {
        return fields;
    }

    public boolean hasField(String name) {
        return fields.containsKey(name) || pyClass.hasMember(name);
    }

    @Override
    public String toString() {
        PyCaller interp = PyCaller.current();
        if (interp != null) {
            PyObject strMethod = pyClass.lookupMember("__str__");
            if (strMethod instanceof PyFunction fn) {
                return interp.call(fn, new PyObject[]{this}).toString();
            }
            if (strMethod instanceof PyBoundMethod bm) {
                return interp.call(bm.fn(), new PyObject[]{this}).toString();
            }

            PyObject reprMethod = pyClass.lookupMember("__repr__");
            if (reprMethod instanceof PyFunction fn) {
                return interp.call(fn, new PyObject[]{this}).toString();
            }
            if (reprMethod instanceof PyBoundMethod bm) {
                return interp.call(bm.fn(), new PyObject[]{this}).toString();
            }
        }
        return "<" + pyClass.name() + " instance>";
    }
}

