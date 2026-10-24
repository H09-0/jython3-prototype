package org.example.jython3.runtime;



public record PyException(String excType, String message) implements PyObject {
    @Override public String toString() { return excType + ": " + message; }
}
