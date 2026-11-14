package org.example.jython3;

import org.example.jython3.bridge.JavaBridge;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.security.AllowlistPolicy;
import org.example.jython3.security.DenyAllPolicy;
import org.example.jython3.security.JavaAccessPolicy;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class JavaBridgeOptimizationTest {

    public static class OverloadTarget {
        public String process(int x) { return "int:" + x; }
        public String process(String s) { return "str:" + s; }
        public String process(double d) { return "double:" + d; }
        public String process(int a, int b) { return "int2:" + (a + b); }
    }

    public static class TargetA {
        public String identify(int x) { return "A:" + x; }
    }

    public static class TargetB {
        public String identify(int x) { return "B:" + x; }
    }

    private static final JavaAccessPolicy TEST_POLICY = new JavaAccessPolicy() {
        @Override public boolean canAccessClass(String binaryName) { return true; }
        @Override public boolean canConstructClass(String binaryName, String descriptor) { return true; }
        @Override public boolean canCallMethod(String owner, String name, String descriptor) { return true; }
        @Override public boolean canReadField(String owner, String name) { return true; }
        @Override public boolean canWriteField(String owner, String name) { return true; }
    };

    @BeforeEach
    void setUp() {
        JavaBridge.clearResolutionCache();
    }

    @Test
    @DisplayName("Overload resolution selects correct method across diverse argument types and caches accurately")
    void testOverloadResolutionCaching() {
        var interp = new Jython3Interpreter(TEST_POLICY);
        interp.setGlobal("target", JavaBridge.toPyObject(new OverloadTarget(), TEST_POLICY));

        interp.eval("""
            r_int = target.process(42)
            r_str = target.process("hello")
            r_dbl = target.process(3.14)
            r_two = target.process(10, 20)

            # Repeat to hit resolution cache
            r_int_cached = target.process(100)
            r_str_cached = target.process("world")
            r_dbl_cached = target.process(2.718)
            r_two_cached = target.process(5, 7)
            """);

        assertEquals(new PyStr("int:42"), interp.getGlobal("r_int"));
        assertEquals(new PyStr("str:hello"), interp.getGlobal("r_str"));
        assertEquals(new PyStr("double:3.14"), interp.getGlobal("r_dbl"));
        assertEquals(new PyStr("int2:30"), interp.getGlobal("r_two"));

        assertEquals(new PyStr("int:100"), interp.getGlobal("r_int_cached"));
        assertEquals(new PyStr("str:world"), interp.getGlobal("r_str_cached"));
        assertEquals(new PyStr("double:2.718"), interp.getGlobal("r_dbl_cached"));
        assertEquals(new PyStr("int2:12"), interp.getGlobal("r_two_cached"));
    }

    @Test
    @DisplayName("Different classes with identical method signatures do not cross-pollinate cache")
    void testCrossClassResolutionIsolation() {
        var interp = new Jython3Interpreter(TEST_POLICY);
        interp.setGlobal("a", JavaBridge.toPyObject(new TargetA(), TEST_POLICY));
        interp.setGlobal("b", JavaBridge.toPyObject(new TargetB(), TEST_POLICY));

        interp.eval("""
            res_a1 = a.identify(1)
            res_b1 = b.identify(1)
            res_a2 = a.identify(2)
            res_b2 = b.identify(2)
            """);

        assertEquals(new PyStr("A:1"), interp.getGlobal("res_a1"));
        assertEquals(new PyStr("B:1"), interp.getGlobal("res_b1"));
        assertEquals(new PyStr("A:2"), interp.getGlobal("res_a2"));
        assertEquals(new PyStr("B:2"), interp.getGlobal("res_b2"));
    }

    @Test
    @DisplayName("Cached method resolution never bypasses security access policy checks")
    void testSecurityEnforcementWithCache() {
        // 1. Prime cache using a permissive policy
        var permissive = new Jython3Interpreter(TEST_POLICY);
        permissive.setGlobal("target", JavaBridge.toPyObject(new OverloadTarget(), TEST_POLICY));
        permissive.eval("res = target.process(42)");
        assertEquals(new PyStr("int:42"), permissive.getGlobal("res"));

        // 2. Denying policy must reject the call even though method is present in JavaBridge cache
        JavaAccessPolicy denyProcess = new JavaAccessPolicy() {
            @Override public boolean canAccessClass(String binaryName) { return true; }
            @Override public boolean canConstructClass(String binaryName, String descriptor) { return true; }
            @Override public boolean canCallMethod(String owner, String name, String descriptor) {
                return !name.equals("process");
            }
            @Override public boolean canReadField(String owner, String name) { return true; }
            @Override public boolean canWriteField(String owner, String name) { return true; }
        };

        var restricted = new Jython3Interpreter(denyProcess);
        restricted.setGlobal("target", JavaBridge.toPyObject(new OverloadTarget(), denyProcess));

        InterpreterException ex = assertThrows(InterpreterException.class, () -> {
            restricted.eval("target.process(42)");
        });
        assertTrue(ex.getMessage().contains("restricted"), ex.getMessage());
    }

    @Test
    @DisplayName("Results are identical before and after clearing resolution cache")
    void testResultsIdenticalAcrossCacheClearing() {
        var interp = new Jython3Interpreter(TEST_POLICY);
        interp.setGlobal("target", JavaBridge.toPyObject(new OverloadTarget(), TEST_POLICY));

        interp.eval("r1 = target.process('hello')");
        JavaBridge.clearResolutionCache();
        interp.eval("r2 = target.process('hello')");

        assertEquals(interp.getGlobal("r1"), interp.getGlobal("r2"));
    }
}
