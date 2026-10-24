package org.example.jython3.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.example.jython3.bridge.JavaBridge;

/** Represents a callable Java method (static or instance) with overload resolution. */
public record PyJavaMethod(Object target, Class<?> clazz, String name, List<Method> methods)
        implements PyObject {

    public PyObject invoke(PyObject[] args) {
        JavaBridge.checkSecurity(clazz.getName());
        if (name.equals("getClass") || name.equals("forName") || name.equals("getClassLoader") ||
                name.equals("getMethod") || name.equals("getDeclaredMethod") || name.equals("exec")) {
            throw new InterpreterException("SecurityError: access to '" + name + "' is restricted");
        }
        JavaBridge.MethodMatch match = JavaBridge.findBestMethod(clazz, name, methods, args);
        try {
            Object[] javaArgs = JavaBridge.convertArgs(args, match.paramTypes());
            Object result = match.method().invoke(target, javaArgs);
            return JavaBridge.toPyObject(result);
        } catch (InvocationTargetException e) {
            Throwable cause = e.getCause() != null ? e.getCause() : e;
            throw new InterpreterException(
                    "JavaException: " + cause.getClass().getName() + ": " + cause.getMessage());
        } catch (Exception e) {
            throw new InterpreterException("JavaError: " + e.getMessage());
        }
    }

    @Override public String toString() {
        return "<java method '" + name + "' of '" + clazz.getName() + "'>";
    }
}
