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
    private final boolean isType;

    public PyBuiltinFunction(String name, Function<PyObject[], PyObject> fn) {
        this(name, fn, null, false);
    }

    public PyBuiltinFunction(String name, KwTarget kwFn) {
        this(name, args -> kwFn.call(args, Collections.emptyMap()), kwFn, false);
    }

    private PyBuiltinFunction(String name, Function<PyObject[], PyObject> posFn, KwTarget kwFn, boolean isType) {
        this.name = name;
        this.posFn = posFn;
        this.kwFn = kwFn;
        this.isType = isType;
    }

    /** Creates a built-in Python type object (renders as {@code <class 'name'>}). */
    public static PyBuiltinFunction builtinType(String name, Function<PyObject[], PyObject> fn) {
        return new PyBuiltinFunction(name, fn, null, true);
    }

    /** Creates a built-in Python type object accepting keyword arguments. */
    public static PyBuiltinFunction builtinType(String name, KwTarget kwFn) {
        return new PyBuiltinFunction(name, args -> kwFn.call(args, Collections.emptyMap()), kwFn, true);
    }

    public String name() { return name; }
    public boolean isType() { return isType; }
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
        return isType ? "<class '" + name + "'>" : "<built-in function " + name + ">";
    }
}
