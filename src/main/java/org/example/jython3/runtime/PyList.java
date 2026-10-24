package org.example.jython3.runtime;

import java.util.List;


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
