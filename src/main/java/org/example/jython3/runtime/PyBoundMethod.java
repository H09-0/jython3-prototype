package org.example.jython3.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.example.jython3.compile.CompiledCode;

public record PyBoundMethod(PyInstance instance, PyFunction fn) implements PyObject {
    @Override public String toString() {
        return "<bound method " + fn.name() + " of " + instance + ">";
    }
}
