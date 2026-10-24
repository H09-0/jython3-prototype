package org.example.jython3.runtime;



public record PyNull() implements PyObject { @Override public String toString() { return "None"; } }
