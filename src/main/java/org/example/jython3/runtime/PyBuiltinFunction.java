package org.example.jython3.runtime;

import java.util.Collections;
import java.util.Map;
import java.util.function.Function;

public final class PyBuiltinFunction implements PyObject {

    @FunctionalInterface
    public interface KwTarget {
        PyObject call(PyObject[] args, Map<String, PyObject> kwargs);
    }

    private final String name;
    private final Function<PyObject[], PyObject> posFn;
    private final KwTarget kwFn;

    public PyBuiltinFunction(String name, Function<PyObject[], PyObject> fn) {
        this.name = name;
        this.posFn = fn;
        this.kwFn = null;
    }

    public PyBuiltinFunction(String name, KwTarget kwFn) {
        this.name = name;
        this.posFn = args -> kwFn.call(args, Collections.emptyMap());
        this.kwFn = kwFn;
    }

    public String name() { return name; }
    public Function<PyObject[], PyObject> fn() { return posFn; }
    public KwTarget kwFn() { return kwFn; }

    public PyObject callWithKw(PyObject[] args, Map<String, PyObject> kwargs) {
        if (kwFn != null) {
            return kwFn.call(args, kwargs != null ? kwargs : Collections.emptyMap());
        }
        if (kwargs != null && !kwargs.isEmpty()) {
            throw PyErrors.error("TypeError", name + "() takes no keyword arguments");
        }
        return posFn.apply(args);
    }

    @Override
    public String toString() {
        return "<built-in function " + name + ">";
    }
}
