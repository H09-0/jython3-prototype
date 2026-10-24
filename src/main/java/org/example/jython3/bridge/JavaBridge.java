package org.example.jython3.bridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyDict;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyFloat;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyJavaClass;
import org.example.jython3.runtime.PyJavaObject;
import org.example.jython3.runtime.PyList;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyOps;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.security.JavaAccessPolicy;


/**
 * Java Bridge for Jython 3.
 *
 * Provides bidirectional interoperability between Python 3 and the Java Virtual Machine:
 *   • Direct instantiation of accessible Java classes: {@code f = File("/path")}
 *   • Calling instance and static methods with automatic overload resolution.
 *   • Reading and writing instance and static fields and Java Bean properties (get/set/is).
 *   • Transparent type conversion (int, float, str, bool, list, dict <-> Java primitives, arrays, collections).
 *   • Cached reflection for repeat dispatch.
 *
 * Access is decided by the {@link JavaAccessPolicy} supplied with each
 * interpreter. The policy gates classes, constructors, methods, and fields;
 * it is defense in depth, not a sandbox.
 */
public class JavaBridge {

    /** Method resolution cache: (TargetClass#MethodName#ArgTypes) -> MethodMatch */
    private static final Map<String, MethodMatch> METHOD_CACHE = new ConcurrentHashMap<>();

    /** Constructor resolution cache: (TargetClass#ArgTypes) -> ConstructorMatch */
    private static final Map<String, ConstructorMatch> CONSTRUCTOR_CACHE = new ConcurrentHashMap<>();

    // ─── Class & Package Loading & Resolution ─────────────────────────────

    /** The error raised when a policy rejects an access. */
    public static InterpreterException denied(String target) {
        return PyErrors.error("SecurityError", "access to '" + target + "' is restricted");
    }

    public static PyObject resolveClassOrPackage(String fullName, JavaAccessPolicy policy) {
        if (!policy.canAccessClass(fullName)) throw denied(fullName);

        try {
            Class<?> clazz = Class.forName(fullName);
            return new PyJavaClass(clazz, policy);
        } catch (ClassNotFoundException e) {
            throw PyErrors.error("ImportError", "cannot import '" + fullName + "'");
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
    public static PyObject toPyObject(Object obj, JavaAccessPolicy policy) {
        if (obj == null) return new PyNull();
        if (obj instanceof PyObject pyObj) return pyObj;

        if (obj instanceof Boolean b) return PyBool.of(b);
        if (obj instanceof Byte || obj instanceof Short || obj instanceof Integer)
            return new PyInt(((Number) obj).intValue());
        if (obj instanceof Long l) {
            return new PyInt(l.intValue());
        }
        if (obj instanceof Float || obj instanceof Double)
            return new PyFloat(((Number) obj).doubleValue());
        if (obj instanceof CharSequence || obj instanceof Character)
            return new PyStr(obj.toString());

        // Wrap arbitrary Java object
        return new PyJavaObject(obj, policy);
    }

    /** Convert a Python PyObject into a target Java type. */
    public static Object toJavaObject(PyObject pyObj, Class<?> targetType) {
        if (pyObj instanceof PyNull) return null;

        if (pyObj instanceof PyJavaObject jo) {
            return jo.object();
        }
        if (pyObj instanceof PyJavaClass jc) {
            return jc.clazz();
        }

        if (targetType == String.class || targetType == CharSequence.class) {
            if (pyObj instanceof PyStr s) return s.value();
            return pyObj.toString();
        }

        if (targetType == int.class || targetType == Integer.class) {
            if (pyObj instanceof PyInt i) return i.value();
            if (pyObj instanceof PyFloat f) return (int) f.value();
            if (pyObj instanceof PyBool b) return b.value() ? 1 : 0;
            return Integer.parseInt(pyObj.toString());
        }
        if (targetType == long.class || targetType == Long.class) {
            if (pyObj instanceof PyInt i) return (long) i.value();
            if (pyObj instanceof PyFloat f) return (long) f.value();
            return Long.parseLong(pyObj.toString());
        }
        if (targetType == double.class || targetType == Double.class) {
            return PyOps.toDouble(pyObj);
        }
        if (targetType == float.class || targetType == Float.class) {
            return (float) PyOps.toDouble(pyObj);
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return PyOps.isTruthy(pyObj);
        }
        if (targetType == byte.class || targetType == Byte.class) {
            if (pyObj instanceof PyInt i) return (byte) i.value();
            return (byte) Integer.parseInt(pyObj.toString());
        }
        if (targetType == short.class || targetType == Short.class) {
            if (pyObj instanceof PyInt i) return (short) i.value();
            return (short) Integer.parseInt(pyObj.toString());
        }
        if (targetType == char.class || targetType == Character.class) {
            String s = pyObj.toString();
            return s.isEmpty() ? '\0' : s.charAt(0);
        }

        if (targetType.isInstance(pyObj)) {
            return pyObj;
        }

        // Try to be more lenient for Object/Collection/Map/List
        if (targetType == Object.class) {
            if (pyObj instanceof PyInt i) return i.value();
            if (pyObj instanceof PyFloat f) return f.value();
            if (pyObj instanceof PyStr s) return s.value();
            if (pyObj instanceof PyBool b) return b.value();
            if (pyObj instanceof PyList list) {
                var javaList = new ArrayList<Object>();
                for (var it : list.items()) javaList.add(toJavaObject(it, Object.class));
                return javaList;
            }
            if (pyObj instanceof PyDict dict) {
                var javaMap = new LinkedHashMap<Object, Object>();
                for (var entry : dict.map().entrySet()) {
                    javaMap.put(toJavaObject(entry.getKey(), Object.class), toJavaObject(entry.getValue(), Object.class));
                }
                return javaMap;
            }
            return pyObj;
        }

        // List conversion
        if (pyObj instanceof PyList list && (List.class.isAssignableFrom(targetType) || targetType == Object.class || targetType == Collection.class)) {
            var javaList = new ArrayList<Object>();
            for (var it : list.items()) javaList.add(toJavaObject(it, Object.class));
            return javaList;
        }

        // Map conversion
        if (pyObj instanceof PyDict dict && (Map.class.isAssignableFrom(targetType) || targetType == Object.class)) {
            var javaMap = new LinkedHashMap<Object, Object>();
            for (var entry : dict.map().entrySet()) {
                javaMap.put(toJavaObject(entry.getKey(), Object.class), toJavaObject(entry.getValue(), Object.class));
            }
            return javaMap;
        }

        // Array conversion
        if (targetType.isArray() && pyObj instanceof PyList list) {
            Class<?> componentType = targetType.getComponentType();
            Object arr = java.lang.reflect.Array.newInstance(componentType, list.items().size());
            for (int i = 0; i < list.items().size(); i++) {
                java.lang.reflect.Array.set(arr, i, toJavaObject(list.items().get(i), componentType));
            }
            return arr;
        }

        if (targetType == Object.class) {
            if (pyObj instanceof PyInt i) return i.value();
            if (pyObj instanceof PyFloat f) return f.value();
            if (pyObj instanceof PyStr s) return s.value();
            if (pyObj instanceof PyBool b) return b.value();
            return pyObj;
        }

        throw PyErrors.error("TypeError",
                "cannot convert " + PyOps.typeName(pyObj) + " to Java " + targetType.getName());
    }

    // ─── Fast Method / Constructor Resolution Engine ─────────────────────

    public record MethodMatch(Method method, Class<?>[] paramTypes) {}
    public record ConstructorMatch(Constructor<?> constructor, Class<?>[] paramTypes) {}

    public static MethodMatch findBestMethod(Class<?> clazz, String name, List<Method> methods, PyObject[] args) {
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
            throw PyErrors.error("TypeError",
                    "no matching Java method '" + name + "' for argument types (" + signature(args) + ")");
        }

        best.setAccessible(true);
        var match = new MethodMatch(best, best.getParameterTypes());
        METHOD_CACHE.put(cacheKey, match);
        return match;
    }

    public static ConstructorMatch findBestConstructor(Class<?> clazz, PyObject[] args) {
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
            throw PyErrors.error("TypeError",
                    "no matching Java constructor for class '" + clazz.getName() + "' with (" + signature(args) + ")");
        }

        best.setAccessible(true);
        var match = new ConstructorMatch(best, best.getParameterTypes());
        CONSTRUCTOR_CACHE.put(cacheKey, match);
        return match;
    }

    private static int scoreParam(Class<?> p, PyObject a) {
        if (a instanceof PyNull) {
            return p.isPrimitive() ? -1 : 5;
        }
        if (a instanceof PyJavaObject jo) {
            if (p.isInstance(jo.object())) return 10;
        }
        if (a instanceof PyJavaClass jc) {
            if (p.isInstance(jc.clazz()) || p == Class.class) return 10;
        }
        if (p == String.class || p == CharSequence.class) {
            if (a instanceof PyStr) return 10;
            return 2;
        }
        if (p == int.class || p == Integer.class) {
            if (a instanceof PyInt) return 10;
            if (a instanceof PyFloat || a instanceof PyBool) return 5;
            return -1;
        }
        if (p == long.class || p == Long.class) {
            if (a instanceof PyInt) return 10;
            if (a instanceof PyFloat) return 5;
            return -1;
        }
        if (p == double.class || p == Double.class) {
            if (a instanceof PyFloat) return 10;
            if (a instanceof PyInt) return 8;
            return -1;
        }
        if (p == float.class || p == Float.class) {
            if (a instanceof PyFloat) return 10;
            if (a instanceof PyInt) return 8;
            return -1;
        }
        if (p == boolean.class || p == Boolean.class) {
            if (a instanceof PyBool) return 10;
            return -1;
        }
        if (p == byte.class || p == Byte.class || p == short.class || p == Short.class) {
            if (a instanceof PyInt) return 8;
            return -1;
        }
        if (p == char.class || p == Character.class) {
            if (a instanceof PyStr s && s.value().length() == 1) return 10;
            return -1;
        }
        if (List.class.isAssignableFrom(p) || Collection.class.isAssignableFrom(p)) {
            if (a instanceof PyList) return 10;
            if (a instanceof PyJavaObject) return 5;
            return -1;
        }
        if (Map.class.isAssignableFrom(p)) {
            if (a instanceof PyDict) return 10;
            if (a instanceof PyJavaObject) return 5;
            return -1;
        }
        if (p == Object.class) {
            if (a instanceof PyList) return 10;
            if (a instanceof PyDict) return 8;
            return 1;
        }
        if (p.isInstance(a)) {
            return 10;
        }
        return -1;
    }

    private static int scoreCompatibility(Class<?>[] params, PyObject[] args) {
        int total = 0;
        for (int i = 0; i < params.length; i++) {
            int s = scoreParam(params[i], args[i]);
            if (s < 0) return -1;
            total += s;
        }
        return total;
    }

    public static Object[] convertArgs(PyObject[] args, Class<?>[] paramTypes) {
        Object[] res = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            res[i] = toJavaObject(args[i], paramTypes[i]);
        }
        return res;
    }

    private static String signature(PyObject[] args) {
        var sb = new StringBuilder();
        for (int i = 0; i < args.length; i++) {
            if (i > 0) sb.append(", ");
            sb.append(PyOps.typeName(args[i]));
        }
        return sb.toString();
    }
}
