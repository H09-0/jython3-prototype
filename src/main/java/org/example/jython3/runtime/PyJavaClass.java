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
        JavaBridge.ConstructorMatch match = JavaBridge.findBestConstructor(clazz, args, policy);
        String descriptor = JavaDescriptors.of(match.paramTypes());
        if (!policy.canConstructClass(clazz.getName(), descriptor)) {
            throw JavaBridge.denied(clazz.getName() + descriptor);
        }
        try {
            Object[] javaArgs = JavaBridge.convertArgs(args, match, policy);
            var invoker = match.invoker();
            Object instance = invoker != null
                    ? (Object) invoker.invokeExact(javaArgs)
                    : match.constructor().newInstance(javaArgs);
            return new PyJavaObject(instance, policy);
        } catch (Throwable t) {
            throw PyErrors.fromJava(t);
        }
    }

    public PyObject getMember(String name) {
        if (!policy.canAccessClass(clazz.getName())) throw JavaBridge.denied(clazz.getName());

        // Static methods
        List<Method> allStaticMethods = JavaBridge.getStaticMethodsNamed(clazz, name);
        List<Method> staticMethods = permit(allStaticMethods, name);
        if (!staticMethods.isEmpty()) {
            return new PyJavaMethod(null, clazz, name, staticMethods, policy);
        }
        if (!allStaticMethods.isEmpty()) throw JavaBridge.denied("method '" + name + "'");

        // Static fields
        try {
            Field f = clazz.getField(name);
            if (Modifier.isStatic(f.getModifiers()) && Modifier.isPublic(f.getModifiers()) && !f.isSynthetic()) {
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
            if (inner.getSimpleName().equals(name) && Modifier.isPublic(inner.getModifiers())) {
                if (!policy.canAccessClass(inner.getName())) throw JavaBridge.denied(inner.getName());
                return new PyJavaClass(inner, policy);
            }
        }

        throw PyErrors.error("AttributeError",
                "Java class '" + clazz.getName() + "' has no attribute '" + name + "'");
    }

    public void setMember(String name, PyObject val) {
        if (!policy.canAccessClass(clazz.getName())) throw JavaBridge.denied(clazz.getName());
        if (name == null || name.isEmpty()) {
            throw PyErrors.error("AttributeError", "cannot set empty attribute on Java class");
        }
        try {
            Field f = clazz.getField(name);
            if (Modifier.isStatic(f.getModifiers()) && Modifier.isPublic(f.getModifiers()) && !f.isSynthetic()) {
                if (!policy.canWriteField(clazz.getName(), name)) {
                    throw JavaBridge.denied("field '" + name + "'");
                }
                if (Modifier.isFinal(f.getModifiers())) {
                    throw PyErrors.error("AttributeError",
                            "cannot set final static field '" + name + "' on Java class '" + clazz.getName() + "'");
                }
                Object javaVal = JavaBridge.toJavaObject(val, f.getType(), policy);
                f.set(null, javaVal);
                return;
            }
        } catch (NoSuchFieldException | IllegalAccessException ignored) {
        }
        throw PyErrors.error("AttributeError",
                "Java class '" + clazz.getName() + "' has no writable static attribute '" + name + "'");
    }

    public java.util.Map<String, PyObject> accessibleStaticMembers() {
        if (!policy.canAccessClass(clazz.getName())) throw JavaBridge.denied(clazz.getName());
        var result = new java.util.LinkedHashMap<String, PyObject>();
        for (Field f : clazz.getFields()) {
            if (Modifier.isStatic(f.getModifiers()) && Modifier.isPublic(f.getModifiers()) && !f.isSynthetic()) {
                if (policy.canReadField(clazz.getName(), f.getName())) {
                    try {
                        result.put(f.getName(), JavaBridge.toPyObject(f.get(null), policy));
                    } catch (IllegalAccessException | InterpreterException ignored) {
                    }
                }
            }
        }
        for (Method m : clazz.getMethods()) {
            if (Modifier.isStatic(m.getModifiers()) && Modifier.isPublic(m.getModifiers()) && !m.isSynthetic()) {
                String mName = m.getName();
                if (!result.containsKey(mName) && policy.canCallMethod(
                        clazz.getName(), mName, JavaDescriptors.of(m.getParameterTypes()))) {
                    result.put(mName, new PyJavaMethod(null, clazz, mName,
                            JavaBridge.getStaticMethodsNamed(clazz, mName), policy));
                }
            }
        }
        for (Class<?> inner : clazz.getClasses()) {
            if (Modifier.isPublic(inner.getModifiers()) && policy.canAccessClass(inner.getName())) {
                result.put(inner.getSimpleName(), new PyJavaClass(inner, policy));
            }
        }
        return result;
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
