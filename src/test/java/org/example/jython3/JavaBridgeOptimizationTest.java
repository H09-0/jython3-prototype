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
        public String tag;

        public OverloadTarget() {
            this.tag = "default";
        }

        public OverloadTarget(String s) {
            this.tag = "ctor:str:" + s;
        }

        public OverloadTarget(Object o) {
            this.tag = "ctor:obj:" + o;
        }

        public String getTag() { return tag; }

        public String process(int x) { return "int:" + x; }
        public String process(String s) { return "str:" + s; }
        public String process(double d) { return "double:" + d; }
        public String process(int a, int b) { return "int2:" + (a + b); }

        public String dispatch(String s) { return "str:" + s; }
        public String dispatch(Object o) { return "obj:" + o; }
    }

    public static class TargetA {
        public String identify(int x) { return "A:" + x; }
    }

    public static class TargetB {
        public String identify(int x) { return "B:" + x; }
    }

    public static class ArrayTarget {
        public int sumInts(int[] arr) {
            int s = 0;
            for (int v : arr) s += v;
            return s;
        }

        public double sumDoubles(double[] arr) {
            double s = 0.0;
            for (double v : arr) s += v;
            return s;
        }

        public String concatStrings(String[] arr) {
            return String.join("-", arr);
        }

        public int countTrues(boolean[] arr) {
            int count = 0;
            for (boolean b : arr) if (b) count++;
            return count;
        }

        public String describeBigInt(java.math.BigInteger bi) {
            return "big:" + bi.toString();
        }

        public long sumLongs(long[] arr) {
            long s = 0;
            for (long v : arr) s += v;
            return s;
        }

        public int sumBytes(byte[] arr) {
            int s = 0;
            for (byte v : arr) s += v;
            return s;
        }

        public int sumShorts(short[] arr) {
            int s = 0;
            for (short v : arr) s += v;
            return s;
        }

        public String joinChars(char[] arr) {
            return new String(arr);
        }

        public String joinStringsWithNull(String[] arr) {
            var sb = new StringBuilder();
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(arr[i] == null ? "<null>" : arr[i]);
            }
            return sb.toString();
        }

        public String describeBoxedInts(Integer[] arr) {
            var sb = new StringBuilder();
            for (int i = 0; i < arr.length; i++) {
                if (i > 0) sb.append(",");
                sb.append(arr[i] == null ? "<null>" : arr[i]);
            }
            return sb.toString();
        }

        public int inspectListSize(java.util.List<?> list) {
            return list.size();
        }

        public int inspectMapSize(java.util.Map<?, ?> map) {
            return map.size();
        }
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

    @Test
    @DisplayName("Policy-aware resolution selects permitted overload when a more specific overload is denied")
    void testPolicyAwareOverloadSelection() {
        // 1. Policy A allows both dispatch(String) and dispatch(Object)
        var interpA = new Jython3Interpreter(TEST_POLICY);
        interpA.setGlobal("target", JavaBridge.toPyObject(new OverloadTarget(), TEST_POLICY));
        interpA.eval("res_a = target.dispatch('test')");
        // String overload selected
        assertEquals(new PyStr("str:test"), interpA.getGlobal("res_a"));

        // 2. Policy B restricts dispatch(String) descriptor "(Ljava/lang/String;)Ljava/lang/String;"
        // but permits dispatch(Object) descriptor "(Ljava/lang/Object;)Ljava/lang/String;"
        JavaAccessPolicy policyB = new JavaAccessPolicy() {
            @Override public boolean canAccessClass(String binaryName) { return true; }
            @Override public boolean canConstructClass(String binaryName, String descriptor) { return true; }
            @Override public boolean canCallMethod(String owner, String name, String descriptor) {
                if (name.equals("dispatch") && descriptor.contains("Ljava/lang/String;")) {
                    return false; // deny String overload
                }
                return true; // permit Object overload and all others
            }
            @Override public boolean canReadField(String owner, String name) { return true; }
            @Override public boolean canWriteField(String owner, String name) { return true; }
        };

        var interpB = new Jython3Interpreter(policyB);
        interpB.setGlobal("target", JavaBridge.toPyObject(new OverloadTarget(), policyB));
        interpB.eval("res_b = target.dispatch('test')");
        // Must fallback cleanly to dispatch(Object), NOT fail due to cache pollution from Policy A!
        assertEquals(new PyStr("obj:test"), interpB.getGlobal("res_b"));
    }

    @Test
    @DisplayName("Constructor resolution filters candidate constructors by policy before selecting best match")
    void testPolicyAwareConstructorSelection() {
        // 1. Policy A allows both OverloadTarget(String) and OverloadTarget(Object)
        var interpA = new Jython3Interpreter(TEST_POLICY);
        interpA.setGlobal("TargetClass", new org.example.jython3.runtime.PyJavaClass(OverloadTarget.class, TEST_POLICY));
        interpA.eval("inst_a = TargetClass('alpha')\ntag_a = inst_a.getTag()");
        assertEquals(new PyStr("ctor:str:alpha"), interpA.getGlobal("tag_a"));

        // 2. Policy B denies (String) constructor "(Ljava/lang/String;)V"
        // but permits (Object) constructor "(Ljava/lang/Object;)V"
        JavaAccessPolicy policyB = new JavaAccessPolicy() {
            @Override public boolean canAccessClass(String binaryName) { return true; }
            @Override public boolean canConstructClass(String binaryName, String descriptor) {
                // Deny String constructor, allow Object constructor
                return !descriptor.contains("Ljava/lang/String;");
            }
            @Override public boolean canCallMethod(String owner, String name, String descriptor) { return true; }
            @Override public boolean canReadField(String owner, String name) { return true; }
            @Override public boolean canWriteField(String owner, String name) { return true; }
        };

        var interpB = new Jython3Interpreter(policyB);
        interpB.setGlobal("TargetClass", new org.example.jython3.runtime.PyJavaClass(OverloadTarget.class, policyB));
        interpB.eval("inst_b = TargetClass('alpha')\ntag_b = inst_b.getTag()");
        // Must fall back to (Object) constructor without throwing SecurityError
        assertEquals(new PyStr("ctor:obj:alpha"), interpB.getGlobal("tag_b"));
    }

    @Test
    @DisplayName("Fast array conversion handles int[], double[], String[], boolean[], long[] correctly")
    void testArrayConversions() {
        var interp = new Jython3Interpreter(TEST_POLICY);
        interp.setGlobal("target", JavaBridge.toPyObject(new ArrayTarget(), TEST_POLICY));

        interp.eval("""
            # int[] with mixed types: [3.14, True, 42] -> 3 + 1 + 42 = 46
            r_int = target.sumInts([3.14, True, 42])

            # double[] with mixed types: [10, 2.5, False] -> 10.0 + 2.5 + 0.0 = 12.5
            r_dbl = target.sumDoubles([10, 2.5, False])

            # String[]: ["alpha", "beta", "gamma"] -> "alpha-beta-gamma"
            r_str = target.concatStrings(["alpha", "beta", "gamma"])

            # boolean[]: [True, False, 1, 0, "", "hello"] -> 3 trues (True, 1, "hello")
            r_bool = target.countTrues([True, False, 1, 0, "", "hello"])
            """);

        assertEquals(new PyInt(46), interp.getGlobal("r_int"));
        assertEquals(new org.example.jython3.runtime.PyFloat(12.5), interp.getGlobal("r_dbl"));
        assertEquals(new PyStr("alpha-beta-gamma"), interp.getGlobal("r_str"));
        assertEquals(new PyInt(3), interp.getGlobal("r_bool"));
    }

    @Test
    @DisplayName("Large values and BigInt conversion")
    void testBigIntAndLargeValuesConversion() {
        var interp = new Jython3Interpreter(TEST_POLICY);
        interp.setGlobal("target", JavaBridge.toPyObject(new ArrayTarget(), TEST_POLICY));

        interp.eval("""
            # Large BigInt passed to BigInteger parameter
            large_val = 10 ** 25
            r_big = target.describeBigInt(large_val)

            # Large long array
            r_longs = target.sumLongs([10000000000, 20000000000])
            """);

        assertEquals(new PyStr("big:10000000000000000000000000"), interp.getGlobal("r_big"));
        assertEquals(new org.example.jython3.runtime.BigInt(java.math.BigInteger.valueOf(30000000000L)), interp.getGlobal("r_longs"));
    }

    @Test
    @DisplayName("Null (None) inside String[] and Integer[] converts to null, while None inside primitive arrays throws TypeError")
    void testNullInArrays() {
        var interp = new Jython3Interpreter(TEST_POLICY);
        interp.setGlobal("target", JavaBridge.toPyObject(new ArrayTarget(), TEST_POLICY));

        interp.eval("""
            r_str_null = target.joinStringsWithNull(["a", None, "b"])
            r_box_null = target.describeBoxedInts([1, None, 3])
            """);

        assertEquals(new PyStr("a,<null>,b"), interp.getGlobal("r_str_null"));
        assertEquals(new PyStr("1,<null>,3"), interp.getGlobal("r_box_null"));

        InterpreterException exInt = assertThrows(InterpreterException.class, () -> {
            interp.eval("target.sumInts([1, None, 2])");
        });
        assertTrue(exInt.getMessage().contains("TypeError"), exInt.getMessage());

        InterpreterException exDbl = assertThrows(InterpreterException.class, () -> {
            interp.eval("target.sumDoubles([1.5, None])");
        });
        assertTrue(exDbl.getMessage().contains("TypeError"), exDbl.getMessage());

        InterpreterException exBool = assertThrows(InterpreterException.class, () -> {
            interp.eval("target.countTrues([True, None])");
        });
        assertTrue(exBool.getMessage().contains("TypeError"), exBool.getMessage());
    }

    @Test
    @DisplayName("byte[], short[], and char[] array conversions work accurately")
    void testByteShortCharArrayConversions() {
        var interp = new Jython3Interpreter(TEST_POLICY);
        interp.setGlobal("target", JavaBridge.toPyObject(new ArrayTarget(), TEST_POLICY));

        interp.eval("""
            r_byte  = target.sumBytes([10, 20, True, 3.9])
            r_short = target.sumShorts([100, 200, True, 4.7])
            r_char  = target.joinChars(["J", "3", "!"])
            """);

        assertEquals(new PyInt(34), interp.getGlobal("r_byte"));
        assertEquals(new PyInt(305), interp.getGlobal("r_short"));
        assertEquals(new PyStr("J3!"), interp.getGlobal("r_char"));
    }

    @Test
    @DisplayName("List and Map conversions enforce access policy on nested PyJavaObject elements")
    void testListAndMapPolicyEnforcement() {
        // 1. Create a restricted Java object (TargetA) wrapped under permissive policy
        var secretObj = new TargetA();
        var wrappedSecret = new org.example.jython3.runtime.PyJavaObject(secretObj, TEST_POLICY);

        // 2. Policy that allows ArrayTarget, List, Map, etc., but DENIES TargetA
        JavaAccessPolicy denyTargetA = new JavaAccessPolicy() {
            @Override public boolean canAccessClass(String binaryName) {
                return !binaryName.equals(TargetA.class.getName());
            }
            @Override public boolean canConstructClass(String binaryName, String descriptor) { return true; }
            @Override public boolean canCallMethod(String owner, String name, String descriptor) { return true; }
            @Override public boolean canReadField(String owner, String name) { return true; }
            @Override public boolean canWriteField(String owner, String name) { return true; }
        };

        var interp = new Jython3Interpreter(denyTargetA);
        interp.setGlobal("target", JavaBridge.toPyObject(new ArrayTarget(), denyTargetA));

        // Permitted elements in List and Map succeed
        interp.eval("""
            l_sz = target.inspectListSize([1, "two", 3.0])
            m_sz = target.inspectMapSize({"k1": 10, "k2": 20})
            """);
        assertEquals(new PyInt(3), interp.getGlobal("l_sz"));
        assertEquals(new PyInt(2), interp.getGlobal("m_sz"));

        // Injecting restricted PyJavaObject inside a list or dict must be rejected during conversion!
        var listWithSecret = new org.example.jython3.runtime.PyList(java.util.List.of(wrappedSecret));
        assertThrows(InterpreterException.class, () -> {
            JavaBridge.toJavaObject(listWithSecret, java.util.List.class, denyTargetA);
        });

        var mapWithSecret = new org.example.jython3.runtime.PyDict(java.util.Map.of(new PyStr("secret"), wrappedSecret));
        assertThrows(InterpreterException.class, () -> {
            JavaBridge.toJavaObject(mapWithSecret, java.util.Map.class, denyTargetA);
        });
    }
}
