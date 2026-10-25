package org.example.jython3.runtime;

import java.util.HashMap;
import java.util.Map;


public final class PyInstance implements PyObject {
    private final PyClass pyClass;
    private final Map<String, PyObject> fields = new HashMap<>();

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
        if (fields.containsKey(name)) {
            return fields.get(name);
        }
        PyObject member = pyClass.lookupMember(name);
        if (member instanceof PyFunction fn) {
            return new PyBoundMethod(this, fn);
        }
        if (member != null) {
            return member;
        }
        throw PyErrors.error("AttributeError", "'" + pyClass.name() + "' object has no attribute '" + name + "'");
    }

    public void setField(String name, PyObject val) {
        fields.put(name, val);
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

