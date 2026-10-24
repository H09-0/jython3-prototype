package org.example.jython3.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.example.jython3.bridge.JavaBridge;

/** Represents a Java class accessible from Python. */
public record PyJavaClass(Class<?> clazz) implements PyObject {

    public PyObject newInstance(PyObject[] args) {
        JavaBridge.checkSecurity(clazz.getName());
        if (clazz.getName().equals("java.lang.Class") || clazz == Class.class) {
            throw PyErrors.error("SecurityError", "access to '" + clazz.getName() + "' is restricted");
        }
        JavaBridge.ConstructorMatch match = JavaBridge.findBestConstructor(clazz, args);
        try {
            Object[] javaArgs = JavaBridge.convertArgs(args, match.paramTypes());
            Object instance = match.constructor().newInstance(javaArgs);
            return new PyJavaObject(instance);
        } catch (Exception e) {
            throw PyErrors.fromJava(e);
        }
    }

    public PyObject getMember(String name) {
        JavaBridge.checkSecurity(clazz.getName());
        if (name.equals("getClass") || name.equals("forName") || name.equals("getClassLoader") ||
                name.equals("getMethod") || name.equals("getDeclaredMethod") || name.equals("getField") ||
                name.equals("getDeclaredField")) {
            throw PyErrors.error("SecurityError", "access to '" + name + "' is restricted");
        }
        // Static methods
        List<Method> staticMethods = new ArrayList<>();
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name) && Modifier.isStatic(m.getModifiers())) {
                staticMethods.add(m);
            }
        }
        if (!staticMethods.isEmpty()) {
            return new PyJavaMethod(null, clazz, name, staticMethods);
        }

        // Static fields
        try {
            Field f = clazz.getField(name);
            if (Modifier.isStatic(f.getModifiers())) {
                return JavaBridge.toPyObject(f.get(null));
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // Not a static field; look for a nested class instead.
        }

        // Inner / Nested classes
        for (Class<?> inner : clazz.getClasses()) {
            if (inner.getSimpleName().equals(name)) {
                try {
                    JavaBridge.checkSecurity(inner.getName());
                } catch (Exception e) {
                    throw e;
                }
                return new PyJavaClass(inner);
            }
        }

        throw PyErrors.error("AttributeError",
                "Java class '" + clazz.getName() + "' has no attribute '" + name + "'");
    }

    @Override public String toString() {
        return "<java class '" + clazz.getName() + "'>";
    }
}
