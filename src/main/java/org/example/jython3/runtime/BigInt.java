package org.example.jython3.runtime;



public record BigInt(java.math.BigInteger value) implements PyObject { @Override public String toString() { return value.toString(); } }
