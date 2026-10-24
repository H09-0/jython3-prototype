package org.example.jython3.runtime;

import java.util.Map;


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
