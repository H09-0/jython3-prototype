package org.example.jython3.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.example.jython3.bridge.JavaBridge;
import org.example.jython3.security.JavaAccessPolicy;
import org.example.jython3.security.JavaDescriptors;

/** Represents a Java class accessible from Python. */
public record PyJavaClass(Class<?> clazz, JavaAccessPolicy policy) implements PyObject {

    public PyObject newInstance(PyObject[] args) {
        if (!policy.canAccessClass(clazz.getName())) throw JavaBridge.denied(clazz.getName());
        JavaBridge.ConstructorMatch match = JavaBridge.findBestConstructor(clazz, args);
        String descriptor = JavaDescriptors.of(match.paramTypes());
        if (!policy.canConstructClass(clazz.getName(), descriptor)) {
            throw JavaBridge.denied(clazz.getName() + descriptor);
        }
        try {
            Object[] javaArgs = JavaBridge.convertArgs(args, match.paramTypes());
            Object instance = match.constructor().newInstance(javaArgs);
            return new PyJavaObject(instance, policy);
        } catch (Exception e) {
            throw PyErrors.fromJava(e);
        }
    }

    public PyObject getMember(String name) {
        if (!policy.canAccessClass(clazz.getName())) throw JavaBridge.denied(clazz.getName());

        // Static methods
        List<Method> allStaticMethods = new ArrayList<>();
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name) && Modifier.isStatic(m.getModifiers())) {
                allStaticMethods.add(m);
            }
        }
        List<Method> staticMethods = permit(allStaticMethods, name);
        if (!staticMethods.isEmpty()) {
            return new PyJavaMethod(null, clazz, name, staticMethods, policy);
        }
        if (!allStaticMethods.isEmpty()) throw JavaBridge.denied("method '" + name + "'");

        // Static fields
        try {
            Field f = clazz.getField(name);
            if (Modifier.isStatic(f.getModifiers())) {
                if (!policy.canReadField(clazz.getName(), name)) {
                    throw JavaBridge.denied("field '" + name + "'");
                }
                return JavaBridge.toPyObject(f.get(null), policy);
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // Not a static field; look for a nested class instead.
        }

        // Inner / Nested classes
        for (Class<?> inner : clazz.getClasses()) {
            if (inner.getSimpleName().equals(name)) {
                if (!policy.canAccessClass(inner.getName())) throw JavaBridge.denied(inner.getName());
                return new PyJavaClass(inner, policy);
            }
        }

        throw PyErrors.error("AttributeError",
                "Java class '" + clazz.getName() + "' has no attribute '" + name + "'");
    }

    private List<Method> permit(List<Method> methods, String name) {
        return methods.stream()
                .filter(m -> policy.canCallMethod(
                        clazz.getName(), name, JavaDescriptors.of(m.getParameterTypes())))
                .toList();
    }

    @Override public String toString() {
        return "<java class '" + clazz.getName() + "'>";
    }
}
