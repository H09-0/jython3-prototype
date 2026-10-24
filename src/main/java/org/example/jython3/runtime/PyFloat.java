package org.example.jython3.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.example.jython3.compile.CompiledCode;

public record PyFloat(double value) implements PyObject {
    @Override public String toString() {
        if (Double.isInfinite(value)) return value > 0 ? "inf" : "-inf";
        if (Double.isNaN(value)) return "nan";
        return (value == Math.floor(value) && !Double.isInfinite(value)) ? (long) value + ".0" : String.valueOf(value);
    }
}
