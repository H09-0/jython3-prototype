package org.example.jython3.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.example.jython3.compile.CompiledCode;

public record PyDict(Map<PyObject, PyObject> map) implements PyObject {
    @Override public String toString() {
        var sb = new StringBuilder("{");
        int i = 0;
        for (var entry : map.entrySet()) {
            if (i++ > 0) sb.append(", ");
            var k = entry.getKey();
            var v = entry.getValue();
            if (k instanceof PyStr s) sb.append("'").append(s.value()).append("'");
            else sb.append(k);
            sb.append(": ");
            if (v instanceof PyStr s) sb.append("'").append(s.value()).append("'");
            else sb.append(v);
        }
        return sb.append("}").toString();
    }
}
