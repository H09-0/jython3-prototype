package org.example.jython3.bridge;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
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

    public record MethodResolutionKey(JavaAccessPolicy policy, Class<?> targetClass, String methodName, Class<?>[] argTypes, int argCount) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof MethodResolutionKey other)) return false;
            return argCount == other.argCount &&
                   java.util.Objects.equals(policy, other.policy) &&
                   targetClass == other.targetClass &&
                   methodName.equals(other.methodName) &&
                   Arrays.equals(argTypes, other.argTypes);
        }

        @Override
        public int hashCode() {
            int result = java.util.Objects.hashCode(policy);
            result = 31 * result + targetClass.hashCode();
            result = 31 * result + methodName.hashCode();
            result = 31 * result + argCount;
            result = 31 * result + Arrays.hashCode(argTypes);
            return result;
        }
    }

    public record ConstructorResolutionKey(JavaAccessPolicy policy, Class<?> targetClass, Class<?>[] argTypes, int argCount) {
        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (!(o instanceof ConstructorResolutionKey other)) return false;
            return argCount == other.argCount &&
                   java.util.Objects.equals(policy, other.policy) &&
                   targetClass == other.targetClass &&
                   Arrays.equals(argTypes, other.argTypes);
        }

        @Override
        public int hashCode() {
            int result = java.util.Objects.hashCode(policy);
            result = 31 * result + targetClass.hashCode();
            result = 31 * result + argCount;
            result = 31 * result + Arrays.hashCode(argTypes);
            return result;
        }
    }

    private static final int MAX_CACHE_ENTRIES = 2048;
    private static final int MAX_CLASS_ENTRIES = 1024;
    private static final Map<MethodResolutionKey, MethodMatch> METHOD_CACHE = new ConcurrentHashMap<>();
    private static final Map<ConstructorResolutionKey, ConstructorMatch> CONSTRUCTOR_CACHE = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Map<String, List<Method>>> CLASS_INSTANCE_METHODS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Map<String, List<Method>>> CLASS_STATIC_METHODS = new ConcurrentHashMap<>();
    private static final Class<?>[] EMPTY_CLASSES = new Class<?>[0];

    private static void putMethodCache(MethodResolutionKey key, MethodMatch match) {
        if (METHOD_CACHE.size() >= MAX_CACHE_ENTRIES) {
            METHOD_CACHE.clear();
        }
        METHOD_CACHE.put(key, match);
    }

    private static void putConstructorCache(ConstructorResolutionKey key, ConstructorMatch match) {
        if (CONSTRUCTOR_CACHE.size() >= MAX_CACHE_ENTRIES) {
            CONSTRUCTOR_CACHE.clear();
        }
        CONSTRUCTOR_CACHE.put(key, match);
    }

    public static void clearResolutionCache() {
        METHOD_CACHE.clear();
        CONSTRUCTOR_CACHE.clear();
        CLASS_INSTANCE_METHODS.clear();
        CLASS_STATIC_METHODS.clear();
        CONVERTER_CACHE.clear();
    }

    public static Class<?>[] extractArgTypes(PyObject[] args) {
        if (args == null || args.length == 0) return EMPTY_CLASSES;
        Class<?>[] types = new Class<?>[args.length];
        for (int i = 0; i < args.length; i++) {
            PyObject a = args[i];
            if (a instanceof PyJavaObject jo) {
                types[i] = jo.object() != null ? jo.object().getClass() : Void.class;
            } else if (a == null || a instanceof PyNull) {
                types[i] = Void.class;
            } else {
                types[i] = a.getClass();
            }
        }
        return types;
    }

    public static List<Method> getInstanceMethodsNamed(Class<?> clazz, String name) {
        if (CLASS_INSTANCE_METHODS.size() >= MAX_CLASS_ENTRIES) {
            CLASS_INSTANCE_METHODS.clear();
        }
        Map<String, List<Method>> byName = CLASS_INSTANCE_METHODS.computeIfAbsent(clazz, c -> {
            Map<String, List<Method>> map = new java.util.HashMap<>();
            for (Method m : c.getMethods()) {
                if (!Modifier.isStatic(m.getModifiers()) && Modifier.isPublic(m.getModifiers()) && !m.isSynthetic()) {
                    map.computeIfAbsent(m.getName(), k -> new ArrayList<>()).add(m);
                }
            }
            return map;
        });
        return byName.getOrDefault(name, java.util.Collections.emptyList());
    }

    public static List<Method> getStaticMethodsNamed(Class<?> clazz, String name) {
        if (CLASS_STATIC_METHODS.size() >= MAX_CLASS_ENTRIES) {
            CLASS_STATIC_METHODS.clear();
        }
        Map<String, List<Method>> byName = CLASS_STATIC_METHODS.computeIfAbsent(clazz, c -> {
            Map<String, List<Method>> map = new java.util.HashMap<>();
            for (Method m : c.getMethods()) {
                if (Modifier.isStatic(m.getModifiers()) && Modifier.isPublic(m.getModifiers()) && !m.isSynthetic()) {
                    map.computeIfAbsent(m.getName(), k -> new ArrayList<>()).add(m);
                }
            }
            return map;
        });
        return byName.getOrDefault(name, java.util.Collections.emptyList());
    }

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
        if (obj instanceof PyObject pyObj) {
            // If already a PyJavaObject or PyJavaClass, retarget with the current policy to prevent privilege escalation
            if (pyObj instanceof PyJavaObject jo) {
                if (!policy.canAccessClass(jo.object().getClass().getName())) {
                    throw denied(jo.object().getClass().getName());
                }
                return new PyJavaObject(jo.object(), policy);
            }
            if (pyObj instanceof PyJavaClass jc) {
                if (!policy.canAccessClass(jc.clazz().getName())) {
                    throw denied(jc.clazz().getName());
                }
                return new PyJavaClass(jc.clazz(), policy);
            }
            return pyObj;
        }

        if (obj instanceof Boolean b) return PyBool.of(b);
        if (obj instanceof Byte || obj instanceof Short || obj instanceof Integer)
            return PyInt.of(((Number) obj).intValue());
        if (obj instanceof Long l) {
            long val = l;
            if (val <= 256 && val >= -128) {
                return PyInt.of((int) val);
            }
            if (val <= Integer.MAX_VALUE && val >= Integer.MIN_VALUE) {
                return new PyInt((int) val);
            }
            return new org.example.jython3.runtime.BigInt(java.math.BigInteger.valueOf(val));
        }
        if (obj instanceof java.math.BigInteger bi) {
            if (bi.compareTo(java.math.BigInteger.valueOf(Integer.MAX_VALUE)) <= 0 &&
                bi.compareTo(java.math.BigInteger.valueOf(Integer.MIN_VALUE)) >= 0) {
                return PyInt.of(bi.intValue());
            }
            return new org.example.jython3.runtime.BigInt(bi);
        }
        if (obj instanceof Float || obj instanceof Double)
            return new PyFloat(((Number) obj).doubleValue());
        if (obj instanceof String s)
            return new PyStr(s);
        if (obj instanceof Character c)
            return new PyStr(String.valueOf(c));

        // For non-String CharSequence (e.g. custom user class implementing CharSequence, or StringBuilder),
        // we must check the policy BEFORE invoking toString(), to prevent arbitrary host code execution.
        if (obj instanceof CharSequence) {
            if (!policy.canAccessClass(obj.getClass().getName())) {
                throw denied(obj.getClass().getName());
            }
            return new PyStr(obj.toString());
        }

        // Check if the object class is accessible under the given policy before wrapping
        if (!policy.canAccessClass(obj.getClass().getName())) {
            throw denied(obj.getClass().getName());
        }

        // Reject dangerous reflection and classloading types completely from entering Python
        if (obj instanceof Class<?> || obj instanceof ClassLoader
                || obj instanceof java.lang.reflect.Member
                || obj instanceof java.lang.reflect.AccessibleObject
                || obj instanceof java.lang.Module
                || obj instanceof java.lang.ModuleLayer) {
            throw denied(obj.getClass().getName());
        }

        // Wrap arbitrary Java object
        return new PyJavaObject(obj, policy);
    }

    public static Object toJavaObject(PyObject pyObj, Class<?> targetType) {
        return toJavaObject(pyObj, targetType, null);
    }

    /** Convert a Python PyObject into a target Java type, enforcing access policy. */
    public static Object toJavaObject(PyObject pyObj, Class<?> targetType, JavaAccessPolicy policy) {
        if (pyObj instanceof PyNull) return null;

        if (pyObj instanceof PyJavaObject jo) {
            if (policy != null && !policy.canAccessClass(jo.object().getClass().getName())) {
                throw denied(jo.object().getClass().getName());
            }
            return jo.object();
        }
        if (pyObj instanceof PyJavaClass jc) {
            if (policy != null && !policy.canAccessClass(jc.clazz().getName())) {
                throw denied(jc.clazz().getName());
            }
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
            if (pyObj instanceof org.example.jython3.runtime.BigInt bi) return bi.value().longValue();
            if (pyObj instanceof PyFloat f) return (long) f.value();
            return Long.parseLong(pyObj.toString());
        }
        if (targetType == java.math.BigInteger.class) {
            if (pyObj instanceof org.example.jython3.runtime.BigInt bi) return bi.value();
            if (pyObj instanceof PyInt i) return java.math.BigInteger.valueOf(i.value());
            if (pyObj instanceof PyFloat f) return java.math.BigInteger.valueOf((long) f.value());
            return new java.math.BigInteger(pyObj.toString());
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

        if (targetType != Object.class && targetType.isInstance(pyObj)) {
            return pyObj;
        }

        // Try to be more lenient for Object/Collection/Map/List
        if (targetType == Object.class) {
            if (pyObj instanceof PyInt i) return i.value();
            if (pyObj instanceof PyFloat f) return f.value();
            if (pyObj instanceof PyStr s) return s.value();
            if (pyObj instanceof PyBool b) return b.value();
            if (pyObj instanceof PyList list) {
                var javaList = new ArrayList<Object>(list.items().size());
                for (var it : list.items()) javaList.add(toJavaObject(it, Object.class, policy));
                return javaList;
            }
            if (pyObj instanceof PyDict dict) {
                var javaMap = new LinkedHashMap<Object, Object>(dict.size() * 2);
                for (var entry : dict.map().entrySet()) {
                    javaMap.put(toJavaObject(entry.getKey(), Object.class, policy), toJavaObject(entry.getValue(), Object.class, policy));
                }
                return javaMap;
            }
            return pyObj;
        }

        // List conversion
        if (pyObj instanceof PyList list && (List.class.isAssignableFrom(targetType) || targetType == Object.class || targetType == Collection.class)) {
            var javaList = new ArrayList<Object>(list.items().size());
            for (var it : list.items()) javaList.add(toJavaObject(it, Object.class, policy));
            return javaList;
        }

        // Map conversion
        if (pyObj instanceof PyDict dict && (Map.class.isAssignableFrom(targetType) || targetType == Object.class)) {
            var javaMap = new LinkedHashMap<Object, Object>(dict.size() * 2);
            for (var entry : dict.map().entrySet()) {
                javaMap.put(toJavaObject(entry.getKey(), Object.class, policy), toJavaObject(entry.getValue(), Object.class, policy));
            }
            return javaMap;
        }

        // Array conversion
        if (targetType.isArray() && pyObj instanceof PyList list) {
            Class<?> componentType = targetType.getComponentType();
            int len = list.items().size();
            if (componentType == int.class) {
                int[] arr = new int[len];
                for (int i = 0; i < len; i++) {
                    PyObject it = list.items().get(i);
                    arr[i] = it instanceof PyInt pi ? pi.value() : Integer.parseInt(it.toString());
                }
                return arr;
            }
            if (componentType == double.class) {
                double[] arr = new double[len];
                for (int i = 0; i < len; i++) {
                    arr[i] = PyOps.toDouble(list.items().get(i));
                }
                return arr;
            }
            if (componentType == String.class) {
                String[] arr = new String[len];
                for (int i = 0; i < len; i++) {
                    PyObject it = list.items().get(i);
                    arr[i] = it instanceof PyStr s ? s.value() : (it instanceof PyNull ? null : it.toString());
                }
                return arr;
            }
            if (!componentType.isPrimitive()) {
                Object[] arr = (Object[]) java.lang.reflect.Array.newInstance(componentType, len);
                for (int i = 0; i < len; i++) {
                    arr[i] = toJavaObject(list.items().get(i), componentType, policy);
                }
                return arr;
            }
            Object arr = java.lang.reflect.Array.newInstance(componentType, len);
            for (int i = 0; i < len; i++) {
                java.lang.reflect.Array.set(arr, i, toJavaObject(list.items().get(i), componentType, policy));
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

    @FunctionalInterface
    public interface TypeConverter {
        Object convert(PyObject pyObj, JavaAccessPolicy policy);
    }

    private static final Map<Class<?>, TypeConverter> CONVERTER_CACHE = new ConcurrentHashMap<>();

    public static TypeConverter getConverter(Class<?> targetType) {
        TypeConverter cached = CONVERTER_CACHE.get(targetType);
        if (cached != null) return cached;
        TypeConverter conv = buildConverter(targetType);
        CONVERTER_CACHE.put(targetType, conv);
        return conv;
    }

    private static TypeConverter buildConverter(Class<?> targetType) {
        if (targetType == int.class || targetType == Integer.class) {
            return (pyObj, policy) -> {
                if (pyObj instanceof PyInt i) return i.value();
                if (pyObj instanceof PyFloat f) return (int) f.value();
                if (pyObj instanceof PyBool b) return b.value() ? 1 : 0;
                if (pyObj instanceof PyNull) return 0;
                return Integer.parseInt(pyObj.toString());
            };
        }
        if (targetType == double.class || targetType == Double.class) {
            return (pyObj, policy) -> PyOps.toDouble(pyObj);
        }
        if (targetType == float.class || targetType == Float.class) {
            return (pyObj, policy) -> (float) PyOps.toDouble(pyObj);
        }
        if (targetType == long.class || targetType == Long.class) {
            return (pyObj, policy) -> {
                if (pyObj instanceof PyInt i) return (long) i.value();
                if (pyObj instanceof org.example.jython3.runtime.BigInt bi) return bi.value().longValue();
                if (pyObj instanceof PyFloat f) return (long) f.value();
                if (pyObj instanceof PyNull) return 0L;
                return Long.parseLong(pyObj.toString());
            };
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return (pyObj, policy) -> PyOps.isTruthy(pyObj);
        }
        if (targetType == String.class || targetType == CharSequence.class) {
            return (pyObj, policy) -> {
                if (pyObj instanceof PyStr s) return s.value();
                if (pyObj instanceof PyNull) return null;
                return pyObj.toString();
            };
        }
        if (targetType == Object.class) {
            return (pyObj, policy) -> {
                if (pyObj instanceof PyNull) return null;
                if (pyObj instanceof PyInt i) return i.value();
                if (pyObj instanceof PyFloat f) return f.value();
                if (pyObj instanceof PyStr s) return s.value();
                if (pyObj instanceof PyBool b) return b.value();
                if (pyObj instanceof PyJavaObject jo) {
                    if (policy != null && !policy.canAccessClass(jo.object().getClass().getName())) {
                        throw denied(jo.object().getClass().getName());
                    }
                    return jo.object();
                }
                return toJavaObject(pyObj, Object.class, policy);
            };
        }
        return (pyObj, policy) -> toJavaObject(pyObj, targetType, policy);
    }

    private static TypeConverter[] buildConverters(Class<?>[] params) {
        TypeConverter[] convs = new TypeConverter[params.length];
        for (int i = 0; i < params.length; i++) {
            convs[i] = getConverter(params[i]);
        }
        return convs;
    }

    public record MethodMatch(Method method, Class<?>[] paramTypes, TypeConverter[] converters) {
        public MethodMatch(Method method, Class<?>[] paramTypes) {
            this(method, paramTypes, buildConverters(paramTypes));
        }
    }

    public record ConstructorMatch(Constructor<?> constructor, Class<?>[] paramTypes, TypeConverter[] converters) {
        public ConstructorMatch(Constructor<?> constructor, Class<?>[] paramTypes) {
            this(constructor, paramTypes, buildConverters(paramTypes));
        }
    }

    public static Object[] convertArgs(PyObject[] args, MethodMatch match, JavaAccessPolicy policy) {
        TypeConverter[] convs = match.converters();
        Object[] res = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            res[i] = convs[i].convert(args[i], policy);
        }
        return res;
    }

    public static Object[] convertArgs(PyObject[] args, ConstructorMatch match, JavaAccessPolicy policy) {
        TypeConverter[] convs = match.converters();
        Object[] res = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            res[i] = convs[i].convert(args[i], policy);
        }
        return res;
    }

    public static MethodMatch findBestMethod(Class<?> clazz, String name, List<Method> methods, PyObject[] args) {
        return findBestMethod(clazz, name, methods, args, null);
    }

    public static MethodMatch findBestMethod(Class<?> clazz, String name, List<Method> methods, PyObject[] args, JavaAccessPolicy policy) {
        Class<?>[] argTypes = extractArgTypes(args);
        var key = new MethodResolutionKey(policy, clazz, name, argTypes, args.length);
        MethodMatch cached = METHOD_CACHE.get(key);
        if (cached != null) return cached;

        Method best = null;
        int bestScore = -1;

        for (Method m : methods) {
            if (!Modifier.isPublic(m.getModifiers()) || m.isSynthetic()) continue;
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

        var match = new MethodMatch(best, best.getParameterTypes());
        putMethodCache(key, match);
        return match;
    }

    public static ConstructorMatch findBestConstructor(Class<?> clazz, PyObject[] args) {
        return findBestConstructor(clazz, args, null);
    }

    public static ConstructorMatch findBestConstructor(Class<?> clazz, PyObject[] args, JavaAccessPolicy policy) {
        Class<?>[] argTypes = extractArgTypes(args);
        var key = new ConstructorResolutionKey(policy, clazz, argTypes, args.length);
        ConstructorMatch cached = CONSTRUCTOR_CACHE.get(key);
        if (cached != null) return cached;

        Constructor<?> best = null;
        int bestScore = -1;
        boolean matchingConstructorFound = false;
        boolean anyConstructorPermitted = false;

        for (Constructor<?> c : clazz.getConstructors()) {
            if (!Modifier.isPublic(c.getModifiers()) || c.isSynthetic()) continue;
            Class<?>[] params = c.getParameterTypes();
            if (params.length != args.length) continue;

            int score = scoreCompatibility(params, args);
            if (score < 0) continue;

            matchingConstructorFound = true;
            if (policy != null && !policy.canConstructClass(clazz.getName(), org.example.jython3.security.JavaDescriptors.of(params))) {
                continue; // Denied by policy, try next compatible overload
            }

            anyConstructorPermitted = true;
            if (score > bestScore) {
                bestScore = score;
                best = c;
            }
        }

        if (best == null) {
            if (matchingConstructorFound && !anyConstructorPermitted) {
                throw denied("constructor for '" + clazz.getName() + "'");
            }
            throw PyErrors.error("TypeError",
                    "no matching Java constructor for class '" + clazz.getName() + "' with (" + signature(args) + ")");
        }

        var match = new ConstructorMatch(best, best.getParameterTypes());
        putConstructorCache(key, match);
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
        return convertArgs(args, paramTypes, null);
    }

    public static Object[] convertArgs(PyObject[] args, Class<?>[] paramTypes, JavaAccessPolicy policy) {
        Object[] res = new Object[args.length];
        for (int i = 0; i < args.length; i++) {
            res[i] = toJavaObject(args[i], paramTypes[i], policy);
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
