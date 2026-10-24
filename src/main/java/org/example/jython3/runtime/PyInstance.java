package org.example.jython3.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.example.jython3.compile.CompiledCode;

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
        throw new InterpreterException("AttributeError: '" + pyClass.name() + "' object has no attribute '" + name + "'");
    }

    public void setField(String name, PyObject val) {
        fields.put(name, val);
    }

    public boolean hasField(String name) {
        return fields.containsKey(name) || pyClass.hasMember(name);
    }

    @Override
    public String toString() {
        try {
            PyCaller interp = PyCaller.current();
            if (interp != null) {
                PyObject strMethod = pyClass.lookupMember("__str__");
                if (strMethod != null) {
                    try {
                        if (strMethod instanceof PyFunction fn) {
                            PyObject res = interp.call(fn, new PyObject[]{this});
                            return res.toString();
                        } else if (strMethod instanceof PyBoundMethod bm) {
                            PyObject res = interp.call(bm.fn(), new PyObject[]{this});
                            return res.toString();
                        }
                    } catch (Exception ignored) {}
                }
                PyObject reprMethod = pyClass.lookupMember("__repr__");
                if (reprMethod != null) {
                    try {
                        if (reprMethod instanceof PyFunction fn) {
                            PyObject res = interp.call(fn, new PyObject[]{this});
                            return res.toString();
                        } else if (reprMethod instanceof PyBoundMethod bm) {
                            PyObject res = interp.call(bm.fn(), new PyObject[]{this});
                            return res.toString();
                        }
                    } catch (Exception ignored) {}
                }
            }
        } catch (Exception ignored) {}
        return "<" + pyClass.name() + " instance>";
    }
}

