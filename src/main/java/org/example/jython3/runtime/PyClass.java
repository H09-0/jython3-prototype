package org.example.jython3.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.example.jython3.compile.CompiledCode;

public final class PyClass implements PyObject {
    private final String name;
    private final PyClass baseClass;
    private final Map<String, PyObject> members;
    private final java.util.List<PyClass> bases = new java.util.ArrayList<>();

    public PyClass(String name, PyClass baseClass, Map<String, PyObject> members) {
        this.name = name;
        this.baseClass = baseClass;
        this.members = members == null ? new HashMap<>() : members;
        if (baseClass != null) this.bases.add(baseClass);
    }

    public PyClass(String name, PyClass baseClass, Map<String, PyObject> members, java.util.List<PyClass> basesList) {
        this.name = name;
        this.baseClass = baseClass;
        this.members = members == null ? new HashMap<>() : members;
        if (basesList != null) {
            this.bases.addAll(basesList);
        } else if (baseClass != null) {
            this.bases.add(baseClass);
        }
    }

    public String name() { return name; }
    public PyClass baseClass() { return baseClass; }
    public Map<String, PyObject> members() { return members; }
    public java.util.List<PyClass> getBases() { return bases; }

    public PyObject lookupMember(String attr) {
        PyObject val = members.get(attr);
        if (val != null) return val;
        // try bases in order
        for (PyClass b : bases) {
            PyObject v = b.lookupMember(attr);
            if (v != null) return v;
        }
        return null;
    }

    public boolean hasMember(String attr) {
        return lookupMember(attr) != null;
    }

    @Override public String toString() {
        return "<class '" + name + "'>";
    }
}

/** Python Class Instance. */
