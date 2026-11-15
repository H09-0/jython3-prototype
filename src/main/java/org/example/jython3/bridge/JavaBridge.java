package org.example.jython3.bridge;

import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
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
import org.example.jython3.runtime.PyBoundMethod;
import org.example.jython3.runtime.PyBuiltinFunction;
import org.example.jython3.runtime.PyCaller;
import org.example.jython3.runtime.PyDict;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyFloat;
import org.example.jython3.runtime.PyFunction;
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
                    Method accessible = findAccessibleMethod(m);
                    if (accessible != null) {
                        map.computeIfAbsent(m.getName(), k -> new ArrayList<>()).add(accessible);
                    }
                }
            }
            return map;
        });
        return byName.getOrDefault(name, java.util.Collections.emptyList());
    }

    /**
     * Resolves a public method declared on a non-public JDK/implementation class
     * (such as {@code java.util.Arrays$ArrayList}) to its public interface/superclass
     * declaration so {@code Method.invoke} succeeds without module-access errors.
     */
    public static Method findAccessibleMethod(Method m) {
        if (Modifier.isPublic(m.getDeclaringClass().getModifiers())) {
            return m;
        }
        String name = m.getName();
        Class<?>[] params = m.getParameterTypes();
        var queue = new java.util.ArrayDeque<Class<?>>();
        var seen = new java.util.HashSet<Class<?>>();
        queue.add(m.getDeclaringClass());
        while (!queue.isEmpty()) {
            Class<?> cur = queue.removeFirst();
            if (!seen.add(cur)) continue;
            if (Modifier.isPublic(cur.getModifiers())) {
                try {
                    Method candidate = cur.getMethod(name, params);
                    if (Modifier.isPublic(candidate.getModifiers())) return candidate;
                } catch (NoSuchMethodException ignored) {}
            }
            if (cur.getSuperclass() != null) queue.add(cur.getSuperclass());
            for (Class<?> iface : cur.getInterfaces()) queue.add(iface);
        }
        return null;
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

        // Mutable StringBuilder/StringBuffer should remain PyJavaObject so fluent chaining (.append().append()) works.
        if (obj instanceof StringBuilder || obj instanceof StringBuffer) {
            if (!policy.canAccessClass(obj.getClass().getName())) {
                throw denied(obj.getClass().getName());
            }
            return new PyJavaObject(obj, policy);
        }

        // For non-String CharSequence (e.g. custom user class implementing CharSequence),
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
            return toJavaInt(pyObj);
        }
        if (targetType == long.class || targetType == Long.class) {
            return toJavaLong(pyObj);
        }
        if (targetType == java.math.BigInteger.class) {
            if (pyObj instanceof org.example.jython3.runtime.BigInt bi) return bi.value();
            if (pyObj instanceof PyInt i) return java.math.BigInteger.valueOf(i.value());
            if (pyObj instanceof PyFloat f) return java.math.BigInteger.valueOf((long) f.value());
            return new java.math.BigInteger(pyObj.toString());
        }
        if (targetType == double.class || targetType == Double.class) {
            return toJavaDouble(pyObj);
        }
        if (targetType == float.class || targetType == Float.class) {
            return (float) toJavaDouble(pyObj);
        }
        if (targetType == boolean.class || targetType == Boolean.class) {
            return toJavaBoolean(pyObj);
        }
        if (targetType == byte.class || targetType == Byte.class) {
            return (byte) toJavaInt(pyObj);
        }
        if (targetType == short.class || targetType == Short.class) {
            return (short) toJavaInt(pyObj);
        }
        if (targetType == char.class || targetType == Character.class) {
            return toJavaChar(pyObj);
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

        // Functional-interface callback proxy for Python callables
        if (targetType.isInterface()
                && (pyObj instanceof PyFunction || pyObj instanceof PyBuiltinFunction || pyObj instanceof PyBoundMethod)) {
            Method sam = findSingleAbstractMethod(targetType);
            if (sam != null) {
                if (policy != null && !policy.canAccessClass(targetType.getName())) {
                    throw denied(targetType.getName());
                }
                PyCaller capturedCaller = PyCaller.current();
                JavaAccessPolicy boundPolicy = policy;
                return Proxy.newProxyInstance(
                        targetType.getClassLoader(),
                        new Class<?>[]{targetType},
                        (proxy, method, args) -> {
                            if (method.getDeclaringClass() == Object.class) {
                                return switch (method.getName()) {
                                    case "toString" -> pyObj.toString();
                                    case "hashCode" -> System.identityHashCode(proxy);
                                    case "equals" -> proxy == (args != null && args.length > 0 ? args[0] : null);
                                    default -> null;
                                };
                            }
                            PyCaller active = PyCaller.current();
                            if (active == null) active = capturedCaller;
                            if (active == null) {
                                throw PyErrors.error("RuntimeError", "Python callback invoked outside interpreter");
                            }
                            int n = args == null ? 0 : args.length;
                            PyObject[] pyArgs = new PyObject[n];
                            for (int i = 0; i < n; i++) {
                                pyArgs[i] = toPyObject(args[i], boundPolicy);
                            }
                            PyObject result = active.call(pyObj, pyArgs);
                            Class<?> retType = method.getReturnType();
                            if (retType == void.class || retType == Void.class) {
                                return null;
                            }
                            return toJavaObject(result, retType, boundPolicy);
                        });
            }
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
                    arr[i] = toJavaInt(list.items().get(i));
                }
                return arr;
            }
            if (componentType == long.class) {
                long[] arr = new long[len];
                for (int i = 0; i < len; i++) {
                    arr[i] = toJavaLong(list.items().get(i));
                }
                return arr;
            }
            if (componentType == double.class) {
                double[] arr = new double[len];
                for (int i = 0; i < len; i++) {
                    arr[i] = toJavaDouble(list.items().get(i));
                }
                return arr;
            }
            if (componentType == float.class) {
                float[] arr = new float[len];
                for (int i = 0; i < len; i++) {
                    arr[i] = (float) toJavaDouble(list.items().get(i));
                }
                return arr;
            }
            if (componentType == boolean.class) {
                boolean[] arr = new boolean[len];
                for (int i = 0; i < len; i++) {
                    arr[i] = toJavaBoolean(list.items().get(i));
                }
                return arr;
            }
            if (componentType == byte.class) {
                byte[] arr = new byte[len];
                for (int i = 0; i < len; i++) {
                    arr[i] = (byte) toJavaInt(list.items().get(i));
                }
                return arr;
            }
            if (componentType == short.class) {
                short[] arr = new short[len];
                for (int i = 0; i < len; i++) {
                    arr[i] = (short) toJavaInt(list.items().get(i));
                }
                return arr;
            }
            if (componentType == char.class) {
                char[] arr = new char[len];
                for (int i = 0; i < len; i++) {
                    arr[i] = toJavaChar(list.items().get(i));
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

    public static int toJavaInt(PyObject pyObj) {
        if (pyObj == null || pyObj instanceof PyNull) {
            throw PyErrors.error("TypeError", "cannot convert NoneType to Java primitive int");
        }
        if (pyObj instanceof PyInt i) return i.value();
        if (pyObj instanceof PyFloat f) return (int) f.value();
        if (pyObj instanceof PyBool b) return b.value() ? 1 : 0;
        if (pyObj instanceof org.example.jython3.runtime.BigInt bi) return bi.value().intValue();
        try {
            return Integer.parseInt(pyObj.toString());
        } catch (NumberFormatException e) {
            throw PyErrors.error("ValueError", "cannot convert '" + pyObj + "' to Java int");
        }
    }

    public static long toJavaLong(PyObject pyObj) {
        if (pyObj == null || pyObj instanceof PyNull) {
            throw PyErrors.error("TypeError", "cannot convert NoneType to Java primitive long");
        }
        if (pyObj instanceof PyInt i) return (long) i.value();
        if (pyObj instanceof org.example.jython3.runtime.BigInt bi) return bi.value().longValue();
        if (pyObj instanceof PyFloat f) return (long) f.value();
        if (pyObj instanceof PyBool b) return b.value() ? 1L : 0L;
        try {
            return Long.parseLong(pyObj.toString());
        } catch (NumberFormatException e) {
            throw PyErrors.error("ValueError", "cannot convert '" + pyObj + "' to Java long");
        }
    }

    public static double toJavaDouble(PyObject pyObj) {
        return PyOps.toDouble(pyObj);
    }

    public static boolean toJavaBoolean(PyObject pyObj) {
        if (pyObj == null || pyObj instanceof PyNull) {
            throw PyErrors.error("TypeError", "cannot convert NoneType to Java primitive boolean");
        }
        return PyOps.isTruthy(pyObj);
    }

    public static char toJavaChar(PyObject pyObj) {
        if (pyObj == null || pyObj instanceof PyNull) {
            throw PyErrors.error("TypeError", "cannot convert NoneType to Java primitive char");
        }
        if (pyObj instanceof PyStr s) {
            String val = s.value();
            return val.isEmpty() ? '\0' : val.charAt(0);
        }
        if (pyObj instanceof PyInt i) {
            return (char) i.value();
        }
        String s = pyObj.toString();
        return s.isEmpty() ? '\0' : s.charAt(0);
    }

    private static final int MAX_CONVERTER_ENTRIES = 512;

    public static TypeConverter getConverter(Class<?> targetType) {
        TypeConverter cached = CONVERTER_CACHE.get(targetType);
        if (cached != null) return cached;
        if (CONVERTER_CACHE.size() >= MAX_CONVERTER_ENTRIES) {
            CONVERTER_CACHE.clear();
        }
        TypeConverter conv = buildConverter(targetType);
        CONVERTER_CACHE.put(targetType, conv);
        return conv;
    }

    private static TypeConverter buildConverter(Class<?> targetType) {
        if (targetType == int.class) {
            return (pyObj, policy) -> toJavaInt(pyObj);
        }
        if (targetType == Integer.class) {
            return (pyObj, policy) -> (pyObj == null || pyObj instanceof PyNull) ? null : toJavaInt(pyObj);
        }
        if (targetType == double.class) {
            return (pyObj, policy) -> toJavaDouble(pyObj);
        }
        if (targetType == Double.class) {
            return (pyObj, policy) -> (pyObj == null || pyObj instanceof PyNull) ? null : toJavaDouble(pyObj);
        }
        if (targetType == float.class) {
            return (pyObj, policy) -> (float) toJavaDouble(pyObj);
        }
        if (targetType == Float.class) {
            return (pyObj, policy) -> (pyObj == null || pyObj instanceof PyNull) ? null : (float) toJavaDouble(pyObj);
        }
        if (targetType == long.class) {
            return (pyObj, policy) -> toJavaLong(pyObj);
        }
        if (targetType == Long.class) {
            return (pyObj, policy) -> (pyObj == null || pyObj instanceof PyNull) ? null : toJavaLong(pyObj);
        }
        if (targetType == boolean.class) {
            return (pyObj, policy) -> toJavaBoolean(pyObj);
        }
        if (targetType == Boolean.class) {
            return (pyObj, policy) -> (pyObj == null || pyObj instanceof PyNull) ? null : toJavaBoolean(pyObj);
        }
        if (targetType == byte.class) {
            return (pyObj, policy) -> (byte) toJavaInt(pyObj);
        }
        if (targetType == Byte.class) {
            return (pyObj, policy) -> (pyObj == null || pyObj instanceof PyNull) ? null : (byte) toJavaInt(pyObj);
        }
        if (targetType == short.class) {
            return (pyObj, policy) -> (short) toJavaInt(pyObj);
        }
        if (targetType == Short.class) {
            return (pyObj, policy) -> (pyObj == null || pyObj instanceof PyNull) ? null : (short) toJavaInt(pyObj);
        }
        if (targetType == char.class) {
            return (pyObj, policy) -> toJavaChar(pyObj);
        }
        if (targetType == Character.class) {
            return (pyObj, policy) -> (pyObj == null || pyObj instanceof PyNull) ? null : toJavaChar(pyObj);
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

    private static final MethodHandles.Lookup PUBLIC_LOOKUP = MethodHandles.publicLookup();

    private static MethodHandle compileMethodInvoker(Method m) {
        try {
            MethodHandle mh = PUBLIC_LOOKUP.unreflect(m).asFixedArity();
            mh = mh.asSpreader(Object[].class, m.getParameterCount());
            if (Modifier.isStatic(m.getModifiers())) {
                return mh.asType(MethodType.methodType(Object.class, Object[].class));
            }
            return mh.asType(MethodType.methodType(Object.class, Object.class, Object[].class));
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    private static MethodHandle compileConstructorInvoker(Constructor<?> c) {
        try {
            MethodHandle ch = PUBLIC_LOOKUP.unreflectConstructor(c).asFixedArity();
            ch = ch.asSpreader(Object[].class, c.getParameterCount());
            return ch.asType(MethodType.methodType(Object.class, Object[].class));
        } catch (IllegalAccessException e) {
            return null;
        }
    }

    public record MethodMatch(Method method, Class<?>[] paramTypes, TypeConverter[] converters,
                              boolean varArgs, MethodHandle invoker, boolean isStatic) {
        public MethodMatch(Method method, Class<?>[] paramTypes) {
            this(method, paramTypes, buildConverters(paramTypes), false,
                    compileMethodInvoker(method), Modifier.isStatic(method.getModifiers()));
        }
        public MethodMatch(Method method, Class<?>[] paramTypes, TypeConverter[] converters) {
            this(method, paramTypes, converters, false,
                    compileMethodInvoker(method), Modifier.isStatic(method.getModifiers()));
        }
        public MethodMatch(Method method, Class<?>[] paramTypes, TypeConverter[] converters, boolean varArgs) {
            this(method, paramTypes, converters, varArgs,
                    compileMethodInvoker(method), Modifier.isStatic(method.getModifiers()));
        }
    }

    public record ConstructorMatch(Constructor<?> constructor, Class<?>[] paramTypes, TypeConverter[] converters,
                                   boolean varArgs, MethodHandle invoker) {
        public ConstructorMatch(Constructor<?> constructor, Class<?>[] paramTypes) {
            this(constructor, paramTypes, buildConverters(paramTypes), false, compileConstructorInvoker(constructor));
        }
        public ConstructorMatch(Constructor<?> constructor, Class<?>[] paramTypes, TypeConverter[] converters) {
            this(constructor, paramTypes, converters, false, compileConstructorInvoker(constructor));
        }
        public ConstructorMatch(Constructor<?> constructor, Class<?>[] paramTypes, TypeConverter[] converters, boolean varArgs) {
            this(constructor, paramTypes, converters, varArgs, compileConstructorInvoker(constructor));
        }
    }

    public static Object[] convertArgs(PyObject[] args, MethodMatch match, JavaAccessPolicy policy) {
        return convertWithVarargs(args, match.paramTypes(), match.converters(), match.varArgs(), policy);
    }

    public static Object[] convertArgs(PyObject[] args, ConstructorMatch match, JavaAccessPolicy policy) {
        return convertWithVarargs(args, match.paramTypes(), match.converters(), match.varArgs(), policy);
    }

    private static Object[] convertWithVarargs(PyObject[] args, Class<?>[] paramTypes,
                                               TypeConverter[] convs, boolean varArgs, JavaAccessPolicy policy) {
        if (!varArgs) {
            Object[] res = new Object[args.length];
            for (int i = 0; i < args.length; i++) {
                res[i] = convs[i].convert(args[i], policy);
            }
            return res;
        }
        int fixed = paramTypes.length - 1;
        Object[] res = new Object[paramTypes.length];
        for (int i = 0; i < fixed; i++) {
            res[i] = convs[i].convert(args[i], policy);
        }
        Class<?> compType = paramTypes[fixed].getComponentType();
        TypeConverter elemConv = getConverter(compType);
        int extra = args.length - fixed;
        Object arr = Array.newInstance(compType, extra);
        for (int i = 0; i < extra; i++) {
            Array.set(arr, i, elemConv.convert(args[fixed + i], policy));
        }
        res[fixed] = arr;
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
        boolean bestVarArgs = false;
        boolean matchingMethodFound = false;
        boolean anyMethodPermitted = false;

        // Pass 1: exact arity; Pass 2: varargs fallback
        for (int pass = 0; pass < 2 && best == null; pass++) {
            boolean varArgsPass = (pass == 1);
            for (Method m : methods) {
                if (!Modifier.isPublic(m.getModifiers()) || m.isSynthetic()) continue;
                Class<?>[] params = m.getParameterTypes();
                int score;
                if (!varArgsPass) {
                    if (params.length != args.length) continue;
                    score = scoreCompatibility(params, args);
                } else {
                    if (!m.isVarArgs() || args.length < params.length - 1) continue;
                    score = scoreVarArgsCompatibility(params, args);
                }
                if (score < 0) continue;

                matchingMethodFound = true;
                if (policy != null && !policy.canCallMethod(clazz.getName(), name,
                        org.example.jython3.security.JavaDescriptors.of(params))) {
                    continue;
                }
                anyMethodPermitted = true;
                if (score > bestScore) {
                    bestScore = score;
                    best = m;
                    bestVarArgs = varArgsPass;
                }
            }
        }

        if (best == null) {
            if (matchingMethodFound && !anyMethodPermitted) {
                throw denied("method '" + name + "'");
            }
            throw PyErrors.error("TypeError",
                    "no matching Java method '" + name + "' for argument types (" + signature(args) + ")");
        }

        var params = best.getParameterTypes();
        var match = new MethodMatch(best, params, buildConverters(params), bestVarArgs);
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
        boolean bestVarArgs = false;
        boolean matchingConstructorFound = false;
        boolean anyConstructorPermitted = false;

        for (int pass = 0; pass < 2 && best == null; pass++) {
            boolean varArgsPass = (pass == 1);
            for (Constructor<?> c : clazz.getConstructors()) {
                if (!Modifier.isPublic(c.getModifiers()) || c.isSynthetic()) continue;
                Class<?>[] params = c.getParameterTypes();
                int score;
                if (!varArgsPass) {
                    if (params.length != args.length) continue;
                    score = scoreCompatibility(params, args);
                } else {
                    if (!c.isVarArgs() || args.length < params.length - 1) continue;
                    score = scoreVarArgsCompatibility(params, args);
                }
                if (score < 0) continue;

                matchingConstructorFound = true;
                if (policy != null && !policy.canConstructClass(clazz.getName(), org.example.jython3.security.JavaDescriptors.of(params))) {
                    continue; // Denied by policy, try next compatible overload
                }

                anyConstructorPermitted = true;
                if (score > bestScore) {
                    bestScore = score;
                    best = c;
                    bestVarArgs = varArgsPass;
                }
            }
        }

        if (best == null) {
            if (matchingConstructorFound && !anyConstructorPermitted) {
                throw denied("constructor for '" + clazz.getName() + "'");
            }
            throw PyErrors.error("TypeError",
                    "no matching Java constructor for class '" + clazz.getName() + "' with (" + signature(args) + ")");
        }

        var params = best.getParameterTypes();
        var match = new ConstructorMatch(best, params, buildConverters(params), bestVarArgs);
        putConstructorCache(key, match);
        return match;
    }

    private static Method findSingleAbstractMethod(Class<?> iface) {
        if (!iface.isInterface()) return null;
        Method sam = null;
        for (Method m : iface.getMethods()) {
            if (!Modifier.isAbstract(m.getModifiers())) continue;
            if (isObjectMethod(m)) continue;
            if (sam != null) return null;
            sam = m;
        }
        return sam;
    }

    private static boolean isObjectMethod(Method m) {
        String n = m.getName();
        Class<?>[] p = m.getParameterTypes();
        return ("toString".equals(n) && p.length == 0)
                || ("hashCode".equals(n) && p.length == 0)
                || ("equals".equals(n) && p.length == 1 && p[0] == Object.class);
    }

    private static int scoreVarArgsCompatibility(Class<?>[] params, PyObject[] args) {
        int fixed = params.length - 1;
        int total = 0;
        for (int i = 0; i < fixed; i++) {
            int s = scoreParam(params[i], args[i]);
            if (s < 0) return -1;
            total += s;
        }
        Class<?> comp = params[fixed].getComponentType();
        for (int i = fixed; i < args.length; i++) {
            int s = scoreParam(comp, args[i]);
            if (s < 0) return -1;
            total += s;
        }
        return total;
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
            if (a instanceof PyInt || a instanceof org.example.jython3.runtime.BigInt) return 10;
            if (a instanceof PyFloat) return 5;
            return -1;
        }
        if (p == java.math.BigInteger.class) {
            if (a instanceof org.example.jython3.runtime.BigInt) return 10;
            if (a instanceof PyInt) return 8;
            return -1;
        }
        if (p.isArray()) {
            if (a instanceof PyList) return 10;
            if (a instanceof PyJavaObject jo && p.isInstance(jo.object())) return 10;
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
        if ((a instanceof PyFunction || a instanceof PyBuiltinFunction || a instanceof PyBoundMethod)
                && findSingleAbstractMethod(p) != null) {
            return 8;
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
