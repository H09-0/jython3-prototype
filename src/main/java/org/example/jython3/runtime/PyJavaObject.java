package org.example.jython3.runtime;

import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

import org.example.jython3.bridge.JavaBridge;
import org.example.jython3.security.JavaAccessPolicy;
import org.example.jython3.security.JavaDescriptors;

/** Represents an instance of a Java object in the Python runtime. */
public record PyJavaObject(Object object, JavaAccessPolicy policy) implements PyObject {

    public PyObject getMember(String name) {
        if (object == null) {
            throw PyErrors.error("AttributeError", "'None' Java object has no attribute '" + name + "'");
        }
        Class<?> clazz = object.getClass();
        if (!policy.canAccessClass(clazz.getName())) throw JavaBridge.denied(clazz.getName());

        // Instance methods
        List<Method> allMethods = new ArrayList<>();
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(name) && !Modifier.isStatic(m.getModifiers())) {
                allMethods.add(m);
            }
        }
        List<Method> methods = allMethods.stream()
                .filter(m -> policy.canCallMethod(
                        clazz.getName(), name, JavaDescriptors.of(m.getParameterTypes())))
                .toList();
        if (!methods.isEmpty()) {
            return new PyJavaMethod(object, clazz, name, methods, policy);
        }
        if (!allMethods.isEmpty()) throw JavaBridge.denied("method '" + name + "'");

        // Instance fields
        try {
            Field f = clazz.getField(name);
            if (!Modifier.isStatic(f.getModifiers())) {
                if (!policy.canReadField(clazz.getName(), name)) {
                    throw JavaBridge.denied("field '" + name + "'");
                }
                return JavaBridge.toPyObject(f.get(object), policy);
            }
        } catch (NoSuchFieldException | IllegalAccessException e) {
            // No public field with this name; fall back to bean accessors.
        }

        // Java Bean getter: getX() or isX()
        String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        try {
            Method getter = clazz.getMethod("get" + cap);
            requireMethodAccess(clazz, getter);
            getter.setAccessible(true);
            return JavaBridge.toPyObject(getter.invoke(object), policy);
        } catch (NoSuchMethodException e) {
            // Try the boolean-style accessor below.
        } catch (ReflectiveOperationException e) {
            throw PyErrors.fromJava(e);
        }
        try {
            Method isser = clazz.getMethod("is" + cap);
            requireMethodAccess(clazz, isser);
            isser.setAccessible(true);
            return JavaBridge.toPyObject(isser.invoke(object), policy);
        } catch (NoSuchMethodException e) {
            // Neither accessor exists; report a Python AttributeError below.
        } catch (ReflectiveOperationException e) {
            throw PyErrors.fromJava(e);
        }

        throw PyErrors.error("AttributeError",
                "Java object of type '" + clazz.getName() + "' has no attribute '" + name + "'");
    }

    public void setMember(String name, PyObject val) {
        if (object == null) {
            throw PyErrors.error("AttributeError", "cannot set attribute on null Java object");
        }
        Class<?> clazz = object.getClass();
        if (!policy.canAccessClass(clazz.getName())) throw JavaBridge.denied(clazz.getName());

        // Public instance field
        try {
            Field f = clazz.getField(name);
            if (!Modifier.isStatic(f.getModifiers())) {
                if (!policy.canWriteField(clazz.getName(), name)) {
                    throw JavaBridge.denied("field '" + name + "'");
                }
                Object javaVal = JavaBridge.toJavaObject(val, f.getType(), policy);
                f.set(object, javaVal);
                return;
            }
        } catch (NoSuchFieldException | IllegalAccessException ignored) {}

        // Java Bean setter: setX(val)
        String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        String setterName = "set" + cap;
        boolean settersExist = false;
        boolean permittedSetterSeen = false;
        for (Method m : clazz.getMethods()) {
            if (m.getName().equals(setterName) && m.getParameterCount() == 1 && !Modifier.isStatic(m.getModifiers())) {
                settersExist = true;
                if (!policy.canCallMethod(clazz.getName(), setterName,
                        JavaDescriptors.of(m.getParameterTypes()))) {
                    continue;
                }
                permittedSetterSeen = true;
                try {
                    m.setAccessible(true);
                    Object javaArg = JavaBridge.toJavaObject(val, m.getParameterTypes()[0], policy);
                    m.invoke(object, javaArg);
                    return;
                } catch (InvocationTargetException e) {
                    throw PyErrors.fromJava(e);
                } catch (IllegalAccessException | IllegalArgumentException e) {
                    // Try the next overload with a compatible parameter type.
                }
            }
        }
        if (settersExist && !permittedSetterSeen) throw JavaBridge.denied("method '" + setterName + "'");

        throw PyErrors.error("AttributeError",
                "cannot set attribute '" + name + "' on Java object '" + clazz.getName() + "'");
    }

    private void requireMethodAccess(Class<?> clazz, Method method) {
        if (!policy.canCallMethod(clazz.getName(), method.getName(),
                JavaDescriptors.of(method.getParameterTypes()))) {
            throw JavaBridge.denied("method '" + method.getName() + "'");
        }
    }

    @Override public String toString() {
        if (object == null) return "null";
        if (!policy.canAccessClass(object.getClass().getName())) {
            return "<unprintable restricted Java object of type " + object.getClass().getName() + ">";
        }
        return object.toString();
    }
}
