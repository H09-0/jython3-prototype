package org.example.jython3;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * High-performance, secure Java Bridge for Jython 3.
 *
 * Provides bidirectional interoperability between Python 3 and the Java Virtual Machine:
 *   • Direct instantiation of any accessible Java class: {@code f = File("/path")}
 *   • Calling instance and static methods with automatic overload resolution.
 *   • Reading and writing instance and static fields and Java Bean properties (get/set/is).
 *   • Direct package navigation: {@code java.lang.Math.sqrt(100.0)}
 *   • Transparent type conversion (int, float, str, bool, list, dict <-> Java primitives, arrays, collections).
 *   • Blazing-fast cached reflection to achieve near-native dispatch speeds.
 *   • Strict security boundaries preventing access to JVM internals (Unsafe, internal lookups).
 */
public class JavaBridge {

    /** Restricted classes and package prefixes for sandboxing and security. */
    private static final Set<String> RESTRICTED_CLASSES = Set.of(
            "sun.misc.Unsafe",
            "jdk.internal.misc.Unsafe",
            "java.lang.invoke.MethodHandles$Lookup",
            "java.lang.invoke.MethodHandles"
    );

    private static final List<String> RESTRICTED_PREFIXES = List.of(
            "jdk.internal.",
            "sun.reflect.",
            "java.lang.invoke.MethodHandles"
    );

    /** Method resolution cache: (TargetClass#MethodName#ArgTypes) -> MethodMatch */
    private static final Map<String, MethodMatch> METHOD_CACHE = new ConcurrentHashMap<>();

    /** Constructor resolution cache: (TargetClass#ArgTypes) -> ConstructorMatch */
    private static final Map<String, ConstructorMatch> CONSTRUCTOR_CACHE = new ConcurrentHashMap<>();

    // ─── Class & Package Loading & Resolution ─────────────────────────────

    public static void checkSecurity(String fullName) {
        if (RESTRICTED_CLASSES.contains(fullName)) {
            throw new Jython3Interpreter.InterpreterException(
                    "SecurityError: access to class '" + fullName + "' is restricted");
        }
        for (String pfx : RESTRICTED_PREFIXES) {
            if (fullName.startsWith(pfx)) {
                throw new Jython3Interpreter.InterpreterException(
                        "SecurityError: access to package '" + fullName + "' is restricted");
            }
        }
    }

    public static Jython3Interpreter.PyObject resolveClassOrPackage(String fullName) {
        checkSecurity(fullName);

        try {
            Class<?> clazz = Class.forName(fullName);
            return new PyJavaClass(clazz);
        } catch (ClassNotFoundException e) {
            // Try common packages if unqualified
            if (!fullName.contains(".")) {
                for (String pkg : List.of("java.lang.", "java.util.", "java.io.", "java.math.")) {
                    try {
                        Class<?> clazz = Class.forName(pkg + fullName);
                        return new PyJavaClass(clazz);
                    } catch (ClassNotFoundException ignored) {}
                }
            }

            // If it looks like a package prefix, return package wrapper
            if (isLikelyPackage(fullName)) {
                return new PyJavaPackage(fullName);
            }
            return null;
        }
    }

    private static boolean isLikelyPackage(String name) {
        return name.equals("java") || name.equals("javax") || name.equals("org") ||
               name.equals("com") || name.equals("net") || name.startsWith("java.") ||
               name.startsWith("javax.") || name.startsWith("org.") ||
               name.startsWith("com.") || name.startsWith("net.");
    }

    // ─── Type Conversions ────────────────────────────────────────────────

    /** Convert an arbitrary Java object into a Jython PyObject. */
    public static Jython3Interpreter.PyObject toPyObject(Object obj) {
        if (obj == null) return new Jython3Interpreter.PyNull();
        if (obj instanceof Jython3Interpreter.PyObject pyObj) return pyObj;

        if (obj instanceof Boolean b) return Jython3Interpreter.PyBool.of(b);
        if (obj instanceof Byte || obj instanceof Short || obj instanceof Integer)
            return new Jython3Interpreter.PyInt(((Number) obj).intValue());
        if (obj instanceof Long l) {
            return new Jython3Interpreter.PyInt(l.intValue());
        }
        if (obj instanceof Float || obj instanceof Double)
            return new Jython3Interpreter.PyFloat(((Number) obj).doubleValue());
        if (obj instanceof CharSequence || obj instanceof Character)
            return new Jython3Interpreter.PyStr(obj.toString());

        // Wrap arbitrary Java object
        return new PyJavaObject(obj);
    }

    /** Convert a Python PyObject into a target Java type. */
    public static Object toJavaObject(Jython3Interpreter.PyObject pyObj, Class<?> targetType) {
        if (pyObj instanceof Jython3Interpreter.PyNull) return null;

        if (pyObj instanceof PyJavaObject jo) {
            return jo.object();
        }
        if (pyObj instanceof PyJavaClass jc) {
            return jc.clazz();
        }

        if (targetType == String.class || targetType == CharSequence.class) {
            if (pyObj instanceof Jython3Interpreter.PyStr s) return s.value();
            return pyObj.toString();
        }

        if (targetType == int.class || targetType == Integer.class) {
            if (pyObj instanceof Jython3Interpreter.PyInt i) return i.value();
            if (pyObj instanceof Jython3Interpreter.PyFloat f) return (int) f.value();
            if (pyObj instanceof Jython3Interpreter.PyBool b) return b.value() ? 1 : 0;
            return Integer.parseInt(pyObj.toString());
        }
        if (targetType == long.class || targetType == Long.class) {
            if (pyObj instanceof Jython3Interpreter.PyInt i) return (long) i.value();
            if (pyObj instanceof Jython3Interpreter.PyFloat f) return (long) f.value();
            return Long.parseLong(pyObj.toString());
        }
        if (targetType == double.class || targetType == Double.class) {
            return Jython3Interpreter.toDouble(pyObj);
        }
        if (targetType == float.class || targetType == Float.class) {
            return (float) Jython3Interpreter.toDouble(pyObj);
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return Jython3Interpreter.isTruthy(pyObj);
        }
        if (targetType == byte.class || targetType == Byte.class) {
            if (pyObj instanceof Jython3Interpreter.PyInt i) return (byte) i.value();
            return (byte) Integer.parseInt(pyObj.toString());
        }
        if (targetType == short.class || targetType == Short.class) {
            if (pyObj instanceof Jython3Interpreter.PyInt i) return (short) i.value();
            return (short) Integer.parseInt(pyObj.toString());
        }
        if (targetType == char.class || targetType == Character.class) {
            String s = pyObj.toString();
            return s.isEmpty() ? '\0' : s.charAt(0);
        }

        if (targetType.isInstance(pyObj)) {
            return pyObj;
        }

        // List conversion
        if (pyObj instanceof Jython3Interpreter.PyList list && List.class.isAssignableFrom(targetType)) {
            var javaList = new ArrayList<Object>();
            for (var it : list.items()) javaList.add(toJavaObject(it, Object.class));
            return javaList;
        }

        // Map conversion
        if (pyObj instanceof Jython3Interpreter.PyDict dict && Map.class.isAssignableFrom(targetType)) {
            var javaMap = new LinkedHashMap<Object, Object>();
            for (var entry : dict.map().entrySet()) {
                javaMap.put(toJavaObject(entry.getKey(), Object.class), toJavaObject(entry.getValue(), Object.class));
            }
            return javaMap;
        }

        // Array conversion
        if (targetType.isArray() && pyObj instanceof Jython3Interpreter.PyList list) {
            Class<?> componentType = targetType.getComponentType();
            Object arr = java.lang.reflect.Array.newInstance(componentType, list.items().size());
            for (int i = 0; i < list.items().size(); i++) {
                java.lang.reflect.Array.set(arr, i, toJavaObject(list.items().get(i), componentType));
            }
            return arr;
        }

        if (targetType == Object.class) {
            if (pyObj instanceof Jython3Interpreter.PyInt i) return i.value();
            if (pyObj instanceof Jython3Interpreter.PyFloat f) return f.value();
            if (pyObj instanceof Jython3Interpreter.PyStr s) return s.value();
            if (pyObj instanceof Jython3Interpreter.PyBool b) return b.value();
            return pyObj;
        }

        throw new Jython3Interpreter.InterpreterException(
                "TypeError: cannot convert " + Jython3Interpreter.typeName(pyObj) + " to Java " + targetType.getName());
    }

    // ─── Runtime Types for Java Entities ─────────────────────────────────

    /** Represents a Java Package navigation point (e.g. java.lang or java.util). */
    public record PyJavaPackage(String packageName) implements Jython3Interpreter.PyObject {
        public Jython3Interpreter.PyObject getMember(String name) {
            String fullName = packageName.isEmpty() ? name : packageName + "." + name;
            checkSecurity(fullName);
            Jython3Interpreter.PyObject resolved = resolveClassOrPackage(fullName);
            if (resolved != null) return resolved;
            return new PyJavaPackage(fullName);
        }

        @Override public String toString() {
            return "<java package '" + packageName + "'>";
        }
    }

    /** Represents a Java class accessible from Python. */
    public record PyJavaClass(Class<?> clazz) implements Jython3Interpreter.PyObject {

        public Jython3Interpreter.PyObject newInstance(Jython3Interpreter.PyObject[] args) {
            ConstructorMatch match = findBestConstructor(clazz, args);
            try {
                Object[] javaArgs = convertArgs(args, match.paramTypes);
                Object instance = match.constructor.newInstance(javaArgs);
                return new PyJavaObject(instance);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new Jython3Interpreter.InterpreterException("JavaException: " + cause.getClass().getName() + ": " + cause.getMessage());
            } catch (Exception e) {
                throw new Jython3Interpreter.InterpreterException("JavaError: " + e.getMessage());
            }
        }

        public Jython3Interpreter.PyObject getMember(String name) {
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
                    return toPyObject(f.get(null));
                }
            } catch (NoSuchFieldException | IllegalAccessException ignored) {}

            // Inner / Nested classes
            for (Class<?> inner : clazz.getClasses()) {
                if (inner.getSimpleName().equals(name)) {
                    return new PyJavaClass(inner);
                }
            }

            throw new Jython3Interpreter.InterpreterException(
                    "AttributeError: Java class '" + clazz.getName() + "' has no attribute '" + name + "'");
        }

        @Override public String toString() {
            return "<java class '" + clazz.getName() + "'>";
        }
    }

    /** Represents an instance of a Java object in the Python runtime. */
    public record PyJavaObject(Object object) implements Jython3Interpreter.PyObject {

        public Jython3Interpreter.PyObject getMember(String name) {
            if (object == null) {
                throw new Jython3Interpreter.InterpreterException("AttributeError: 'None' Java object has no attribute '" + name + "'");
            }
            Class<?> clazz = object.getClass();

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
                    return toPyObject(f.get(object));
                }
            } catch (NoSuchFieldException | IllegalAccessException ignored) {}

            // Java Bean getter: getX() or isX()
            String cap = Character.toUpperCase(name.charAt(0)) + name.substring(1);
            try {
                Method getter = clazz.getMethod("get" + cap);
                getter.setAccessible(true);
                return toPyObject(getter.invoke(object));
            } catch (Exception ignored) {}
            try {
                Method isser = clazz.getMethod("is" + cap);
                isser.setAccessible(true);
                return toPyObject(isser.invoke(object));
            } catch (Exception ignored) {}

            throw new Jython3Interpreter.InterpreterException(
                    "AttributeError: Java object of type '" + clazz.getName() + "' has no attribute '" + name + "'");
        }

        public void setMember(String name, Jython3Interpreter.PyObject val) {
            if (object == null) {
                throw new Jython3Interpreter.InterpreterException("AttributeError: cannot set attribute on null Java object");
            }
            Class<?> clazz = object.getClass();

            // Public instance field
            try {
                Field f = clazz.getField(name);
                if (!Modifier.isStatic(f.getModifiers())) {
                    Object javaVal = toJavaObject(val, f.getType());
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
                        Object javaArg = toJavaObject(val, m.getParameterTypes()[0]);
                        m.invoke(object, javaArg);
                        return;
                    } catch (Exception ignored) {}
                }
            }

            throw new Jython3Interpreter.InterpreterException(
                    "AttributeError: cannot set attribute '" + name + "' on Java object '" + clazz.getName() + "'");
        }

        @Override public String toString() {
            return object != null ? object.toString() : "null";
        }
    }

    /** Represents a callable Java method (static or instance) with overload resolution. */
    public record PyJavaMethod(Object target, Class<?> clazz, String name, List<Method> methods)
            implements Jython3Interpreter.PyObject {

        public Jython3Interpreter.PyObject invoke(Jython3Interpreter.PyObject[] args) {
            MethodMatch match = findBestMethod(clazz, name, methods, args);
            try {
                Object[] javaArgs = convertArgs(args, match.paramTypes);
                Object result = match.method.invoke(target, javaArgs);
                return toPyObject(result);
            } catch (InvocationTargetException e) {
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                throw new Jython3Interpreter.InterpreterException(
                        "JavaException: " + cause.getClass().getName() + ": " + cause.getMessage());
            } catch (Exception e) {
                throw new Jython3Interpreter.InterpreterException("JavaError: " + e.getMessage());
            }
        }

        @Override public String toString() {
            return "<java method '" + name + "' of '" + clazz.getName() + "'>";
        }
    }

    // ─── Fast Method / Constructor Resolution Engine ─────────────────────

    private record MethodMatch(Method method, Class<?>[] paramTypes) {}
    private record ConstructorMatch(Constructor<?> constructor, Class<?>[] paramTypes) {}

    private static MethodMatch findBestMethod(Class<?> clazz, String name, List<Method> methods, Jython3Interpreter.PyObject[] args) {
        String cacheKey = clazz.getName() + "#" + name + "#" + signature(args);
        MethodMatch cached = METHOD_CACHE.get(cacheKey);
        if (cached != null) return cached;

        Method best = null;
        int bestScore = -1;

        for (Method m : methods) {
            Class<?>[] params = m.getParameterTypes();
            if (params.length != args.length) continue;

            int score = scoreCompatibility(params, args);
            if (score > bestScore) {
                bestScore = score;
                best = m;
            }
        }

        if (best == null || bestScore < 0) {
            throw new Jython3Interpreter.InterpreterException(
                    "TypeError: no matching Java method '" + name + "' for argument types (" + signature(args) + ")");
        }

        best.setAccessible(true);
        var match = new MethodMatch(best, best.getParameterTypes());
        METHOD_CACHE.put(cacheKey, match);
        return match;
    }

    private static ConstructorMatch findBestConstructor(Class<?> clazz, Jython3Interpreter.PyObject[] args) {
        String cacheKey = clazz.getName() + "#<init>#" + signature(args);
        ConstructorMatch cached = CONSTRUCTOR_CACHE.get(cacheKey);
        if (cached != null) return cached;

        Constructor<?> best = null;
        int bestScore = -1;

        for (Constructor<?> c : clazz.getConstructors()) {
            Class<?>[] params = c.getParameterTypes();
            if (params.length != args.length) continue;

            int score = scoreCompatibility(params, args);
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }

        if (best == null || bestScore < 0) {
            throw new Jython3Interpreter.InterpreterException(
                    "TypeError: no matching Java constructor for class '" + clazz.getName() + "' with (" + signature(args) + ")");
        }

        best.setAccessible(true);
        var match = new ConstructorMatch(best, best.getParameterTypes());
        CONSTRUCTOR_CACHE.put(cacheKey, match);
        return match;
    }

    private static int scoreParam(Class<?> p, Jython3Interpreter.PyObject a) {
        if (a instanceof Jython3Interpreter.PyNull) {
            return p.isPrimitive() ? -1 : 5;
        }
        if (a instanceof PyJavaObject jo) {
            if (p.isInstance(jo.object())) return 10;
        }
        if (a instanceof PyJavaClass jc) {
            if (p.isInstance(jc.clazz()) || p == Class.class) return 10;
        }
        if (p == String.class || p == CharSequence.class) {
            if (a instanceof Jython3Interpreter.PyStr) return 10;
            return 2;
        }
        if (p == int.class || p == Integer.class) {
            if (a instanceof Jython3Interpreter.PyInt) return 10;
            if (a instanceof Jython3Interpreter.PyFloat || a instanceof Jython3Interpreter.PyBool) return 5;
            return -1;
        }
        if (p == long.class || p == Long.class) {
            if (a instanceof Jython3Interpreter.PyInt) return 10;
            if (a instanceof Jython3Interpreter.PyFloat) return 5;
            return -1;
        }
        if (p == double.class || p == Double.class) {
            if (a instanceof Jython3Interpreter.PyFloat) return 10;
            if (a instanceof Jython3Interpreter.PyInt) return 8;
            return -1;
        }
        if (p == float.class || p == Float.class) {
            if (a instanceof Jython3Interpreter.PyFloat) return 10;
            if (a instanceof Jython3Interpreter.PyInt) return 8;
            return -1;
        }
        if (p == boolean.class || p == Boolean.class) {
            if (a instanceof Jython3Interpreter.PyBool) return 10;
            return -1;
        }
        if (p == byte.class || p == Byte.class || p == short.class || p == Short.class) {
            if (a instanceof Jython3Interpreter.PyInt) return 8;
            return -1;
        }
        if (p == char.class || p == Character.class) {
            if (a instanceof Jython3Interpreter.PyStr s && s.value().length() == 1) return 10;
            return -1;
        }
        if (List.class.isAssignableFrom(p)) {
            if (a instanceof Jython3Interpreter.PyList) return 10;
            return -1;
        }
        if (Map.class.isAssignableFrom(p)) {
            if (a instanceof Jython3Interpreter.PyDict) return 10;
            return -1;
        }
        if (p == Object.class) {
            return 1;
        }
        if (p.isInstance(a)) {
            return 10;
        }
        return -1;
    }

    private static int scoreCompatibility(Class<?>[] params, Jython3Interpreter.PyObject[] args) {
        int total = 0;
        for (int i = 0; i < params.length; i++) {
            int s = scoreParam(params[i], args[i]);
            if (s < 0) return -1;
            total += s;
        }
        return total;
    }

    private static Object[] convertArgs(Jython3Interpreter.PyObject[] args, Class<?>[] paramTypes) {
        Object[] res = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            res[i] = toJavaObject(args[i], paramTypes[i]);
        }
        return res;
    }

    private static String signature(Jython3Interpreter.PyObject[] args) {
        var sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(Jython3Interpreter.typeName(args[i]));
        }
        return sb.toString();
    }
}
