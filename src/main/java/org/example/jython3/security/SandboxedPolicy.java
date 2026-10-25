package org.example.jython3.security;

import java.util.Set;

/**
 * A strict, sandboxed policy designed for executing untrusted scripts.
 *
 * <p>Unlike {@link AllowlistPolicy#trusted()}, this policy:
 * <ul>
 *   <li>Only allows explicit, immutable data types and math utilities (String, Integer, Math, Collections, etc.).
 *   <li>Completely denies all file I/O (no java.io.*, no java.nio.*).
 *   <li>Completely denies all network access (no java.net.*).
 *   <li>Completely denies all UI/AWT subsystems (no java.awt.*).
 *   <li>Strictly forbids reflection, class loading, process execution, and threads.
 * </ul>
 */
public final class SandboxedPolicy implements JavaAccessPolicy {

    public static final SandboxedPolicy INSTANCE = new SandboxedPolicy();

    public static SandboxedPolicy get() { return INSTANCE; }

    /** Only pure value objects, math, and data collections. */
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
            "java.math.BigInteger",
            "java.math.BigDecimal",
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
            "java.util.Date"
    );

    private SandboxedPolicy() {}

    @Override
    public boolean canAccessClass(String binaryName) {
        if (binaryName == null || binaryName.isEmpty()) return false;
        return ALLOWED_CLASSES.contains(binaryName);
    }

    @Override
    public boolean canConstructClass(String binaryName, String descriptor) {
        return canAccessClass(binaryName) && !"java.lang.Class".equals(binaryName);
    }

    @Override
    public boolean canCallMethod(String owner, String methodName, String descriptor) {
        return canAccessClass(owner) && !AllowlistPolicy.RESTRICTED_MEMBERS.contains(methodName);
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
