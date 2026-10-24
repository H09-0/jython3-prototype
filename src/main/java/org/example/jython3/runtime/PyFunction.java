package org.example.jython3.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.example.jython3.compile.CompiledCode;

public final class PyFunction implements PyObject {
    private final String name;
    private final List<String> params;
    private final CompiledCode code;
    private final Map<String, PyObject> closure;

    public PyFunction(String name, List<String> params, CompiledCode code) {
        this.name = name;
        this.params = params;
        this.code = code;
        this.closure = new HashMap<>();
    }

    public PyFunction(String name, List<String> params, CompiledCode code, Map<String, PyObject> closure) {
        this.name = name;
        this.params = params;
        this.code = code;
        this.closure = closure == null ? new HashMap<>() : new HashMap<>(closure);
    }

    public String name() { return name; }
    public List<String> params() { return params; }
    public CompiledCode code() { return code; }
    public Map<String, PyObject> closure() { return closure; }

    @Override public String toString() { return "<function " + name + ">"; }
}

/** A compiled code wrapper stored in constant pool for class bodies. */
