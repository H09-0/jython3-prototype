package org.example.jython3.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.example.jython3.compile.CompiledCode;

public final class PyGenerator implements PyObject {
    private final PyFunction fn;
    private final PyObject[] args;
    private boolean done = false;
    private PyObject lastValue = new PyNull();
    private final Deque<Frame> genStack = new ArrayDeque<>();
    private CompiledCode currentCode;
    private int[] currentBc;
    private Object[] currentPool;
    private int currentPc = 0;
    private Map<String, PyObject> currentLocals;
    private Deque<PyObject> currentStack = new ArrayDeque<>();
    private Deque<ExceptHandler> currentExcept = new ArrayDeque<>();

    public PyGenerator(PyFunction fn, PyObject[] args) {
        this.fn = fn;
        this.args = args;
        // initialize
        var fl = new HashMap<String, PyObject>();
        if (fn.closure() != null) fl.putAll(fn.closure());
        for (int i = 0; i < fn.params().size() && i < args.length; i++) {
            fl.put(fn.params().get(i), args[i]);
        }
        this.currentLocals = fl;
        this.currentCode = fn.code();
        this.currentBc = currentCode.bytecode();
        this.currentPool = currentCode.constPool();
        this.currentPc = 0;
    }

    public PyObject next() {
        if (done) throw new InterpreterException("StopIteration");
        // simplified - will be enhanced
        throw new InterpreterException("StopIteration");
    }

    public boolean isDone() { return done; }

    @Override public String toString() { return "<generator object " + fn.name() + ">"; }
}
