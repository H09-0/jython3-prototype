package org.example.jython3;

import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyHostHandle;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyJavaClass;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.security.AllowlistPolicy;
import org.example.jython3.security.DenyAllPolicy;
import org.example.jython3.security.ExecutionLimits;
import org.example.jython3.security.HostAccessPolicy;
import org.example.jython3.security.JavaAccessPolicy;
import org.example.jython3.security.SandboxedPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Policy matrix verification for Java static field writes on PyJavaClass
 * across DenyAllPolicy, SandboxedPolicy, AllowlistPolicy, and custom host policies.
 */
class JavaStaticFieldSecurityTest {

    public static class TestTarget {
        public static int mutableInt = 1;
        public static final int FINAL_INT = 99;
        public static ClassLoader sensitiveLoader = null;
    }

    private static JavaAccessPolicy customPolicy(boolean allowWrite, boolean allowClassLoader) {
        return new JavaAccessPolicy() {
            @Override public boolean canAccessClass(String name) {
                return name.equals(TestTarget.class.getName())
                        || (allowClassLoader && "java.lang.ClassLoader".equals(name))
                        || AllowlistPolicy.trusted().canAccessClass(name);
            }
            @Override public boolean canConstructClass(String name, String desc) { return canAccessClass(name); }
            @Override public boolean canCallMethod(String owner, String method, String desc) { return canAccessClass(owner); }
            @Override public boolean canReadField(String owner, String field) { return canAccessClass(owner); }
            @Override public boolean canWriteField(String owner, String field) { return allowWrite && canAccessClass(owner); }
        };
    }

    @Test
    @DisplayName("DenyAllPolicy, SandboxedPolicy, and AllowlistPolicy forbid JDK/unauthorized static field writes")
    void policiesEnforceStaticFieldWriteGates() {
        assertFalse(DenyAllPolicy.INSTANCE.canWriteField(TestTarget.class.getName(), "mutableInt"));
        assertFalse(SandboxedPolicy.INSTANCE.canWriteField("java.lang.Integer", "MAX_VALUE"));
        assertFalse(SandboxedPolicy.INSTANCE.canWriteField(TestTarget.class.getName(), "mutableInt"));
        assertFalse(AllowlistPolicy.trusted().canWriteField("java.lang.System", "out"));
        assertFalse(AllowlistPolicy.trusted().canWriteField("java.lang.Integer", "MAX_VALUE"));

        // SandboxedPolicy blocks writing even allowed class fields
        var sandboxedType = new PyJavaClass(Integer.class, SandboxedPolicy.INSTANCE);
        assertEquals("SecurityError",
                assertThrows(InterpreterException.class, () -> sandboxedType.setMember("MAX_VALUE", PyInt.ONE)).typeName());

        // Custom read-only policy blocks write with SecurityError
        var roType = new PyJavaClass(TestTarget.class, customPolicy(false, false));
        assertEquals("SecurityError",
                assertThrows(InterpreterException.class, () -> roType.setMember("mutableInt", PyInt.of(7))).typeName());
    }

    @Test
    @DisplayName("Permitted static field writes block final fields, sensitive field types, and PyHostHandles")
    void permittedWriteEnforcesFinalTypeAndHandleGuards() {
        TestTarget.mutableInt = 1;
        var rwPolicy = customPolicy(true, false);
        var rwType = new PyJavaClass(TestTarget.class, rwPolicy);

        // Allowed mutable int field succeeds
        rwType.setMember("mutableInt", PyInt.of(42));
        assertEquals(42, TestTarget.mutableInt);

        // Final static field rejected with AttributeError
        assertEquals("AttributeError",
                assertThrows(InterpreterException.class, () -> rwType.setMember("FINAL_INT", PyInt.ONE)).typeName());

        // Sensitive field type (ClassLoader not permitted by policy) rejected with SecurityError
        assertEquals("SecurityError",
                assertThrows(InterpreterException.class,
                        () -> rwType.setMember("sensitiveLoader", org.example.jython3.runtime.PyNull.INSTANCE))
                        .typeName());

        // PyHostHandle cannot be written into a Java static field
        try (var interp = new Jython3Interpreter(rwPolicy, HostAccessPolicy.DENY_ALL, ExecutionLimits.SANDBOXED)) {
            PyHostHandle hh = PyHostHandle.builder("secret.H")
                    .bindSession(interp.hostSession())
                    .prop("v", () -> new PyStr("ok"))
                    .build();
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class, () -> rwType.setMember("mutableInt", hh)).typeName());
        }
    }
}
