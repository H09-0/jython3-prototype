package org.example.jython3.runtime;

import java.lang.reflect.Method;
import java.util.List;

import org.example.jython3.bridge.JavaBridge;
import org.example.jython3.security.JavaAccessPolicy;
import org.example.jython3.security.JavaDescriptors;

/** Represents a callable Java method (static or instance) with overload resolution. */
public record PyJavaMethod(Object target, Class<?> clazz, String name, List<Method> methods,
                           JavaAccessPolicy policy) implements PyObject {

    public PyObject invoke(PyObject[] args) {
        if (!policy.canAccessClass(clazz.getName())) throw JavaBridge.denied(clazz.getName());
        JavaBridge.MethodMatch match = JavaBridge.findBestMethod(clazz, name, methods, args);
        String descriptor = JavaDescriptors.of(match.paramTypes());
        if (!policy.canCallMethod(clazz.getName(), name, descriptor)) {
            throw JavaBridge.denied("method '" + name + "'");
        }
        try {
            Object[] javaArgs = JavaBridge.convertArgs(args, match.paramTypes(), policy);
            Object result = match.method().invoke(target, javaArgs);
            return JavaBridge.toPyObject(result, policy);
        } catch (Exception e) {
            throw PyErrors.fromJava(e);
        }
    }

    @Override public String toString() {
        return "<java method '" + name + "' of '" + clazz.getName() + "'>";
    }
}
