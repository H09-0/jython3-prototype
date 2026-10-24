package org.example.jython3.runtime;



public record PyBuiltinFunction(String name, java.util.function.Function<PyObject[], PyObject> fn) implements PyObject {
    @Override public String toString() { return "<built-in function " + name + ">"; }
}
