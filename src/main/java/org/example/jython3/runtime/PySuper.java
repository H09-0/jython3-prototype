package org.example.jython3.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.example.jython3.compile.CompiledCode;

public final class PySuper implements PyObject {
    private final PyClass cls;
    private final PyInstance inst;
    public PySuper(PyClass cls, PyInstance inst) {
        this.cls = cls;
        this.inst = inst;
    }
    public PyInstance inst() { return inst; }
    public PyClass cls() { return cls; }
    public PyObject getMethod(String name) {
        if (cls != null) {
            PyClass base = cls.baseClass();
            if (base != null) {
                PyObject m = base.lookupMember(name);
                if (m instanceof PyFunction fn) return new PyBoundMethod(inst, fn);
                if (m != null) return m;
            }
            for (PyClass b : cls.getBases()) {
                if (b == cls) continue;
                PyObject m = b.lookupMember(name);
                if (m instanceof PyFunction fn) return new PyBoundMethod(inst, fn);
                if (m != null) return m;
            }
        }
        throw new InterpreterException("AttributeError: 'super' object has no attribute '" + name + "'");
    }
    @Override public String toString() { return "<super: ...>"; }
}
