package org.example.jython3.security;

import java.util.List;
import java.util.Set;

/**
 * The default policy for trusted scripts. It mirrors the historical Java
 * bridge allowlist: known packages and classes are reachable, JVM internals
 * and process control are not, and reflection-style members stay closed.
 *
 * <p>This policy is a gate, not a sandbox. Treat it as defense in depth.
 */
public final class AllowlistPolicy implements JavaAccessPolicy {

    private static final AllowlistPolicy TRUSTED = new AllowlistPolicy();

    public static AllowlistPolicy trusted() { return TRUSTED; }

    public static final Set<String> ALLOWED_PACKAGES = Set.of(
            "java.lang",
            "java.util",
            "java.io",
            "java.math",
            "java.text",
            "java.time",
            "java.nio",
            "java.net",
            "java.awt");

    public static final Set<String> ALLOWED_CLASSES = Set.of(
            "java.lang.String",
            "java.lang.Integer",
            "java.lang.Double",
            "java.lang.Float",
            "java.lang.Boolean",
            "java.lang.Long",
            "java.lang.Byte",
            "java.lang.Short",
            "java.lang.Character",
            "java.lang.Math",
            "java.lang.StringBuilder",
            "java.lang.StringBuffer",
            "java.lang.Object",
            "java.util.ArrayList",
            "java.util.LinkedList",
            "java.util.HashMap",
            "java.util.LinkedHashMap",
            "java.util.TreeMap",
            "java.util.HashSet",
            "java.util.LinkedHashSet",
            "java.util.TreeSet",
            "java.util.Arrays",
            "java.util.Collections",
            "java.util.Date",
            "java.util.Scanner",
            "java.io.File",
            "java.io.InputStream",
            "java.io.OutputStream",
            "java.io.PrintStream",
            "java.math.BigInteger",
            "java.math.BigDecimal");

    public static final Set<String> FORBIDDEN_CLASSES = Set.of(
            // Process and system control
            "java.lang.Runtime",
            "java.lang.ProcessBuilder",
            "java.lang.Process",
            "java.lang.ProcessHandle",
            "java.lang.ProcessHandle$Info",
            "java.lang.System",
            // Thread and concurrency manipulation
            "java.lang.Thread",
            "java.lang.ThreadGroup",
            "java.lang.ThreadLocal",
            "java.lang.InheritableThreadLocal",
            // Class loading and reflection
            "java.lang.ClassLoader",
            "java.security.SecureClassLoader",
            "java.net.URLClassLoader",
            "java.lang.invoke.MethodHandles",
            "java.lang.invoke.MethodHandles$Lookup",
            "java.lang.invoke.MethodHandle",
            "java.lang.invoke.VarHandle",
            // Java Object Deserialization hazards
            "java.io.ObjectInputStream",
            "java.io.ObjectOutputStream",
            "java.io.ObjectStreamClass",
            // Desktop and external application launching / UI automation
            "java.awt.Desktop",
            "java.awt.Robot",
            // Unsafe internals
            "sun.misc.Unsafe",
            "jdk.internal.misc.Unsafe");

    public static final List<String> FORBIDDEN_PREFIXES = List.of(
            "sun.",
            "jdk.internal.",
            "com.sun.",
            "java.lang.reflect.",
            "java.lang.instrument.",
            "java.lang.management.");

    /**
     * Members that expose reflection, class loading, process control, thread manipulation,
     * or object deserialization. They are denied even when the owning class is allowed.
     */
    public static final Set<String> RESTRICTED_MEMBERS = Set.of(
            // Reflection and type inspection
            "getClass",
            "forName",
            "getClassLoader",
            "getMethod",
            "getMethods",
            "getDeclaredMethod",
            "getDeclaredMethods",
            "getField",
            "getFields",
            "getDeclaredField",
            "getDeclaredFields",
            "getConstructor",
            "getConstructors",
            "getDeclaredConstructor",
            "getDeclaredConstructors",
            "newInstance",
            // Class loading
            "loadClass",
            "findClass",
            "defineClass",
            // Execution and library loading
            "exec",
            "getRuntime",
            "load",
            "loadLibrary",
            // Thread group and thread manipulation
            "getThreadGroup",
            "interrupt",
            "stop",
            "suspend",
            "resume",
            // Deserialization entry points
            "readObject",
            "readObjectUnshared",
            "readUnshared",
            // Desktop / external launcher methods
            "getDesktop",
            "browse",
            "open",
            "edit",
            "print",
            "mail");

    private AllowlistPolicy() {}

    @Override
    public boolean canAccessClass(String binaryName) {
        if (binaryName == null || binaryName.isEmpty()) return false;

        // Check if the class itself or any of its enclosing outer classes is forbidden.
        // For example, "java.lang.Thread$State" or "java.lang.ProcessBuilder$Redirect"
        // will be rejected because the outer class "java.lang.Thread" / "java.lang.ProcessBuilder" is forbidden.
        String checkName = binaryName;
        while (checkName != null && !checkName.isEmpty()) {
            if (FORBIDDEN_CLASSES.contains(checkName)) return false;
            int lastDollar = checkName.lastIndexOf('$');
            checkName = lastDollar > 0 ? checkName.substring(0, lastDollar) : null;
        }

        for (String prefix : FORBIDDEN_PREFIXES) {
            if (binaryName.startsWith(prefix)) return false;
        }
        if (ALLOWED_CLASSES.contains(binaryName)) return true;
        String pkg = binaryName.contains(".")
                ? binaryName.substring(0, binaryName.lastIndexOf('.'))
                : binaryName;
        return ALLOWED_PACKAGES.contains(pkg);
    }

    @Override
    public boolean canConstructClass(String binaryName, String descriptor) {
        return canAccessClass(binaryName) && !"java.lang.Class".equals(binaryName);
    }

    @Override
    public boolean canCallMethod(String owner, String methodName, String descriptor) {
        return canAccessClass(owner) && !RESTRICTED_MEMBERS.contains(methodName);
    }

    @Override
    public boolean canReadField(String owner, String fieldName) {
        return canAccessClass(owner);
    }

    @Override
    public boolean canWriteField(String owner, String fieldName) {
        return canAccessClass(owner) && !"class".equals(fieldName);
    }
}
