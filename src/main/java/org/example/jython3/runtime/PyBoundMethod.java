package org.example.jython3.runtime;



public record PyBoundMethod(PyInstance instance, PyFunction fn) implements PyObject {
    @Override public String toString() {
        return "<bound method " + fn.name() + " of " + instance + ">";
    }
}
