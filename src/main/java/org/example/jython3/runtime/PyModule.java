package org.example.jython3.runtime;

import java.util.Map;


public record PyModule(String name, Map<String, PyObject> attrs) implements PyObject {
    @Override public String toString() { return "<module '" + name + "'>"; }
}
