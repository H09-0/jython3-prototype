package org.example.jython3.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Opaque, reflection-free Python handle for a host resource (Window, Button,
 * Timer, Controller, Event, etc.). Only explicitly whitelisted methods and
 * properties are exposed — never raw Java fields, methods, or {@code getClass()}.
 */
public final class PyHostHandle implements PyObject {

    private final String typeName;
    private final Map<String, PyBuiltinFunction> methods;
    private final Map<String, Supplier<PyObject>> getters;
    private final Map<String, Consumer<PyObject>> setters;

    private PyHostHandle(String typeName,
                         Map<String, PyBuiltinFunction> methods,
                         Map<String, Supplier<PyObject>> getters,
                         Map<String, Consumer<PyObject>> setters) {
        this.typeName = Objects.requireNonNull(typeName, "typeName");
        this.methods = Collections.unmodifiableMap(new LinkedHashMap<>(methods));
        this.getters = Collections.unmodifiableMap(new LinkedHashMap<>(getters));
        this.setters = Collections.unmodifiableMap(new LinkedHashMap<>(setters));
    }

    public static Builder builder(String typeName) { return new Builder(typeName); }

    public String handleTypeName() { return typeName; }

    public boolean hasAttr(String name) {
        return methods.containsKey(name) || getters.containsKey(name);
    }

    public PyObject getAttr(String name) {
        PyBuiltinFunction m = methods.get(name);
        if (m != null) return m;
        Supplier<PyObject> g = getters.get(name);
        if (g != null) return g.get();
        return null;
    }

    public void setAttr(String name, PyObject value) {
        Consumer<PyObject> s = setters.get(name);
        if (s == null) {
            throw PyErrors.error("AttributeError", "'" + typeName + "' attribute '" + name + "' is read-only or unknown");
        }
        s.accept(value);
    }

    public Set<String> attrNames() {
        var names = new TreeSet<String>(methods.keySet());
        names.addAll(getters.keySet());
        return Collections.unmodifiableSet(names);
    }

    @Override
    public String toString() {
        return "<" + typeName + ">";
    }

    public static final class Builder {
        private final String typeName;
        private final Map<String, PyBuiltinFunction> methods = new LinkedHashMap<>();
        private final Map<String, Supplier<PyObject>> getters = new LinkedHashMap<>();
        private final Map<String, Consumer<PyObject>> setters = new LinkedHashMap<>();

        private Builder(String typeName) { this.typeName = typeName; }

        public Builder method(String name, PyBuiltinFunction fn) {
            methods.put(name, fn);
            return this;
        }

        public Builder prop(String name, Supplier<PyObject> getter) {
            getters.put(name, getter);
            return this;
        }

        public Builder prop(String name, Supplier<PyObject> getter, Consumer<PyObject> setter) {
            getters.put(name, getter);
            setters.put(name, setter);
            return this;
        }

        public PyHostHandle build() {
            return new PyHostHandle(typeName, methods, getters, setters);
        }
    }
}
