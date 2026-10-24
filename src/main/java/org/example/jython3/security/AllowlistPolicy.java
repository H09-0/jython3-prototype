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
            "java.lang.Runtime",
            "java.lang.ProcessBuilder",
            "java.lang.System",
            "java.lang.ClassLoader",
            "java.lang.invoke.MethodHandles",
            "java.lang.invoke.MethodHandles$Lookup",
            "sun.misc.Unsafe",
            "jdk.internal.misc.Unsafe");

    public static final List<String> FORBIDDEN_PREFIXES = List.of(
            "sun.",
            "jdk.internal.",
            "com.sun.",
            "java.lang.reflect.");

    /**
     * Members that expose reflection, class loading, or process control. They
     * are denied even when the owning class is allowed.
     */
    public static final Set<String> RESTRICTED_MEMBERS = Set.of(
            "getClass",
            "forName",
            "getClassLoader",
            "getMethod",
            "getDeclaredMethod",
            "getFields",
            "getField",
            "getDeclaredField",
            "getDeclaredFields",
            "exec",
            "getRuntime",
            "load",
            "loadLibrary");

    private AllowlistPolicy() {}

    @Override
    public boolean canAccessClass(String binaryName) {
        if (binaryName == null || binaryName.isEmpty()) return false;
        if (FORBIDDEN_CLASSES.contains(binaryName)) return false;
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
