package org.example.jython3.runtime;



public record PyNull() implements PyObject {
    public static final PyNull INSTANCE = new PyNull();
    @Override public String toString() { return "None"; }
}
