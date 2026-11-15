package org.example.jython3.runtime;

import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Set;


public record PyList(List<PyObject> items) implements PyObject {
    private static final ThreadLocal<Set<Object>> REPR_SEEN =
            ThreadLocal.withInitial(() -> Collections.newSetFromMap(new IdentityHashMap<>()));

    @Override public String toString() {
        Set<Object> seen = REPR_SEEN.get();
        if (!seen.add(this)) return "[...]";
        try {
            var sb = new StringBuilder("[");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(", ");
                var it = items.get(i);
                if (it instanceof PyStr s) sb.append("'").append(s.value()).append("'");
                else sb.append(it);
            }
            return sb.append("]").toString();
        } finally {
            seen.remove(this);
        }
    }
}
