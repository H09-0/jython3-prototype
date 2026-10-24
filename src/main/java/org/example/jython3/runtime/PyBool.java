package org.example.jython3.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.example.jython3.compile.CompiledCode;

public record PyBool(boolean value) implements PyObject {
    public static final PyBool TRUE = new PyBool(true);
    public static final PyBool FALSE = new PyBool(false);
    public static PyBool of(boolean v) { return v ? TRUE : FALSE; }
    @Override public String toString() { return value ? "True" : "False"; }
}
