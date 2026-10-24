package org.example.jython3.runtime;



public record PyStr(String value) implements PyObject { @Override public String toString() { return value; } }
