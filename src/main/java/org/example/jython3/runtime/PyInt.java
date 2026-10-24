package org.example.jython3.runtime;



public record PyInt(int value) implements PyObject { @Override public String toString() { return Integer.toString(value); } }
