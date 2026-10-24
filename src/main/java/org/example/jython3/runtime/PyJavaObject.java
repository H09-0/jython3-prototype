package org.example.jython3.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.example.jython3.bridge.JavaBridge;

/** Represents an instance of a Java object in the Python runtime. */
public record PyJavaObject(Object object) implements PyObject {

    public PyObject getMember(String name) {
        if (object == null) {
            throw new InterpreterException("AttributeError: 'None' Java object has no attribute '" + name + "'");
        }
        Class<?> clazz = object.getClass();
        JavaBridge.checkSecurity(clazz.getName());
        if (name.equals("getClass") || name.equals("forName") || name.equals("getClassLoader") ||
                name.equals("getMethod") || name.equals("getDeclaredMethod") || name.equals("getField") ||
                name.equals("getDeclaredField")) {
            throw new InterpreterException("SecurityError: access to '" + name + "' is restricted");
        }

        // Instance methods
        List<Method> methods = new ArrayList<>();
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name) && !Modifier.isStatic(m.getModifiers())) {
                methods.add(m);
            }
        }
        if (!methods.isEmpty()) {
            return new PyJavaMethod(object, clazz, name, methods);
        }

        // Instance fields
        try {
            Field f = clazz.getField(name);
            if (!Modifier.isStatic(f.getModifiers())) {
                return JavaBridge.toPyObject(f.get(object));
            }
        } catch (Exception ignored) {}

        // Java Bean getter: getX() or isX()
        String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        try {
            Method getter = clazz.getMethod("get" + cap);
            getter.setAccessible(true);
            return JavaBridge.toPyObject(getter.invoke(object));
        } catch (Exception ignored) {}
        try {
            Method isser = clazz.getMethod("is" + cap);
            isser.setAccessible(true);
            return JavaBridge.toPyObject(isser.invoke(object));
        } catch (Exception ignored) {}

        throw new InterpreterException(
                "AttributeError: Java object of type '" + clazz.getName() + "' has no attribute '" + name + "'");
    }

    public void setMember(String name, PyObject val) {
        if (object == null) {
            throw new InterpreterException("AttributeError: cannot set attribute on null Java object");
        }
        Class<?> clazz = object.getClass();

        // Public instance field
        try {
            Field f = clazz.getField(name);
            if (!Modifier.isStatic(f.getModifiers())) {
                Object javaVal = JavaBridge.toJavaObject(val, f.getType());
                f.set(object, javaVal);
                return;
            }
        } catch (NoSuchFieldException | IllegalAccessException ignored) {}

        // Java Bean setter: setX(val)
        String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        String setterName = "set" + cap;
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(setterName) && m.getParameterCount() == 1 && !Modifier.isStatic(m.getModifiers())) {
                try {
                    m.setAccessible(true);
                    Object javaArg = JavaBridge.toJavaObject(val, m.getParameterTypes()[0]);
                    m.invoke(object, javaArg);
                    return;
                } catch (Exception ignored) {}
            }
        }

        throw new InterpreterException(
                "AttributeError: cannot set attribute '" + name + "' on Java object '" + clazz.getName() + "'");
    }

    @Override public String toString() {
        return object != null ? object.toString() : "null";
    }
}
