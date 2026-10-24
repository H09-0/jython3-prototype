package org.example.jython3.runtime;



public record PyBool(boolean value) implements PyObject {
    public static final PyBool TRUE = new PyBool(true);
    public static final PyBool FALSE = new PyBool(false);
    public static PyBool of(boolean v) { return v ? TRUE : FALSE; }
    @Override public String toString() { return value ? "True" : "False"; }
}
