package org.example.jython3.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.example.jython3.compile.CompiledCode;

public record PyList(List<PyObject> items) implements PyObject {
    @Override public String toString() {
        var sb = new StringBuilder("[");
        for (int i = 0; i < items.size(); i++) {
            if (i > 0) sb.append(", ");
            var it = items.get(i);
            if (it instanceof PyStr s) sb.append("'").append(s.value()).append("'");
            else sb.append(it);
        }
        return sb.append("]").toString();
    }
}
