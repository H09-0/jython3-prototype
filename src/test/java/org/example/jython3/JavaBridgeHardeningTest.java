package org.example.jython3;

import org.example.jython3.bridge.JavaBridge;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.security.AllowlistPolicy;
import org.example.jython3.security.JavaAccessPolicy;
import org.example.jython3.security.SandboxedPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.function.IntUnaryOperator;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Negative and boundary hardening tests for JavaBridge: policy-aware caching,
 * reflection/class/method/constructor denial, array conversions, varargs,
 * re-entrant callbacks, callback exception tracebacks, and callback recursion limits.
 */
class JavaBridgeHardeningTest {

    public interface IntStep {
        int apply(int n);
    }

    public static class CallbackHost {
        public int invoke(IntStep step, int n) {
            return step.apply(n);
        }

        public String joinStrings(String sep, String... items) {
            return String.join(sep, items);
        }

        public int sumIntArray(int[] arr) {
            int s = 0;
            for (int v : arr) s += v;
            return s;
        }
    }

    private static JavaAccessPolicy policyAllowingHost() {
        return new JavaAccessPolicy() {
            @Override public boolean canAccessClass(String binaryName) {
                if (binaryName.equals(CallbackHost.class.getName())
                        || binaryName.equals(IntStep.class.getName())
                        || binaryName.equals(IntUnaryOperator.class.getName())) {
                    return true;
                }
                return AllowlistPolicy.trusted().canAccessClass(binaryName);
            }
            @Override public boolean canConstructClass(String binaryName, String descriptor) {
                if (binaryName.equals(CallbackHost.class.getName())) return true;
                return AllowlistPolicy.trusted().canConstructClass(binaryName, descriptor);
            }
            @Override public boolean canCallMethod(String owner, String methodName, String descriptor) {
                if (owner.equals(CallbackHost.class.getName())) return true;
                return AllowlistPolicy.trusted().canCallMethod(owner, methodName, descriptor);
            }
            @Override public boolean canReadField(String owner, String fieldName) {
                return canAccessClass(owner);
            }
            @Override public boolean canWriteField(String owner, String fieldName) {
                return canAccessClass(owner) && !"class".equals(fieldName);
            }
        };
    }

    @Test
    @DisplayName("Negative security checks: disallowed classes, reflection, methods, constructors and cache isolation")
    void negativePolicyAndReflectionChecks() {
        var trusted = Jython3Interpreter.trusted();
        for (String src : new String[]{
                "from java.lang import Runtime",
                "from java.lang import ProcessBuilder",
                "from java.lang import Thread",
                "from java.lang import Class",
                "from java.lang.reflect import Method",
                "from java.util import ArrayList\na = ArrayList()\na.getClass()",
                "from java.util import ArrayList\na = ArrayList()\nx = getattr(a, 'class')",
        }) {
            var e = assertThrows(InterpreterException.class, () -> trusted.eval(src), src);
            assertEquals("SecurityError", e.typeName(), src);
        }

        // Empty attribute on Java object yields AttributeError, not StringIndexOutOfBoundsException
        var emptyAttr = assertThrows(InterpreterException.class, () -> trusted.eval("""
            from java.util import ArrayList
            getattr(ArrayList(), "")
            """));
        assertEquals("AttributeError", emptyAttr.typeName());

        // SandboxedPolicy blocks java.io.File even after trusted warmed the bridge caches
        trusted.eval("from java.io import File\nf = File('.')");
        var sandboxed = new Jython3Interpreter(SandboxedPolicy.get());
        var se = assertThrows(InterpreterException.class, () -> sandboxed.eval("from java.io import File"));
        assertEquals("SecurityError", se.typeName());

        // Method-level and constructor-level denial cannot be bypassed via overload cache
        JavaAccessPolicy denySort = new JavaAccessPolicy() {
            @Override public boolean canAccessClass(String name) { return AllowlistPolicy.trusted().canAccessClass(name); }
            @Override public boolean canConstructClass(String name, String desc) {
                return !"java.util.ArrayList".equals(name) || "()V".equals(desc);
            }
            @Override public boolean canCallMethod(String owner, String methodName, String desc) {
                if ("java.util.Collections".equals(owner) && "sort".equals(methodName)) return false;
                return AllowlistPolicy.trusted().canCallMethod(owner, methodName, desc);
            }
            @Override public boolean canReadField(String owner, String field) { return true; }
            @Override public boolean canWriteField(String owner, String field) { return !"class".equals(field); }
        };
        var restricted = new Jython3Interpreter(denySort);
        trusted.eval("from java.util import ArrayList, Collections\na = ArrayList([1, 2])\nCollections.sort(a)");
        var ctorDenied = assertThrows(InterpreterException.class,
                () -> restricted.eval("from java.util import ArrayList\na = ArrayList([1, 2])"));
        assertEquals("SecurityError", ctorDenied.typeName());
        var methDenied = assertThrows(InterpreterException.class,
                () -> restricted.eval("from java.util import ArrayList, Collections\na = ArrayList()\nCollections.sort(a)"));
        assertEquals("SecurityError", methDenied.typeName());
    }

    @Test
    @DisplayName("Array conversion errors, varargs, and Java exceptions inside finally")
    void arrayConversionsVarargsAndJavaExceptionInFinally() {
        var interp = new Jython3Interpreter(policyAllowingHost());
        interp.setGlobal("host", JavaBridge.toPyObject(new CallbackHost(), policyAllowingHost()));

        interp.eval("""
            from java.util import Arrays
            xs = Arrays.asList(1, 2, 3)
            joined = host.joinStrings("-", "a", "b", "c")
            empty_join = host.joinStrings("-")
            arr_sum = host.sumIntArray([10, 20, 30])
            assert xs.size() == 3 and joined == "a-b-c" and empty_join == "" and arr_sum == 60
            """);

        // Invalid array conversions raise clean Python TypeError / ValueError
        for (String bad : new String[]{
                "host.sumIntArray(['not-an-int'])",
                "host.sumIntArray([1, None, 3])",
                "host.sumIntArray(123)",
        }) {
            var e = assertThrows(InterpreterException.class, () -> interp.eval(bad), bad);
            assertTrue("TypeError".equals(e.typeName()) || "ValueError".equals(e.typeName()),
                    bad + " -> " + e.typeName());
        }

        // Java exception inside try/finally executes finally and keeps traceback
        var je = assertThrows(InterpreterException.class, () -> interp.eval("""
            from java.util import ArrayList
            fin = []
            def trigger():
                try:
                    ArrayList().get(99)
                finally:
                    fin.append("cleaned")
            trigger()
            """));
        assertEquals("IndexError", je.typeName());
        assertEquals("['cleaned']", interp.getGlobal("fin").toString());
        String tb = interp.formatTraceback(je, "<jb>", "");
        assertTrue(tb.contains("in trigger"), tb);
    }

    @Test
    @DisplayName("Re-entrant Python callbacks via Java preserve caller state, propagate tracebacks, and enforce RecursionError")
    void callbackReentrancyTracebacksAndRecursionLimit() {
        var policy = policyAllowingHost();
        var interp = new Jython3Interpreter(policy);
        interp.setGlobal("host", JavaBridge.toPyObject(new CallbackHost(), policy));

        // 1. Callback calling Python + Collections.sort with Python Comparator lambda
        interp.eval("""
            from java.util import ArrayList, Collections
            items = ArrayList()
            items.add("pear")
            items.add("fig")
            items.add("apple")
            Collections.sort(items, lambda a, b: len(a) - len(b))
            assert [items.get(0), items.get(1), items.get(2)] == ["fig", "pear", "apple"]

            def caller_with_locals():
                x = 100
                res = host.invoke(lambda n: n * 2, 21)
                return x + res
            assert caller_with_locals() == 142
            """);

        // 2. Python exception thrown inside a Java callback keeps full traceback & context
        interp.eval("""
            def py_cb(n):
                if n == 0:
                    raise KeyError("bad-cb")
                return n
            def outer_cb():
                return host.invoke(py_cb, 0)
            try:
                outer_cb()
            except KeyError as e:
                frames = []
                cur = e.__traceback__
                while cur is not None:
                    frames.append(cur.tb_frame.f_code.co_name)
                    cur = cur.tb_next
            """);
        assertEquals("['<module>', 'outer_cb', 'py_cb']", interp.getGlobal("frames").toString());

        // 3. Mutual recursion Python -> Java -> Python hits RecursionError, not StackOverflowError
        var rec = assertThrows(InterpreterException.class, () -> interp.eval("""
            def ping(n):
                if n <= 0:
                    return 0
                return host.invoke(ping, n - 1) + 1
            ping(100000)
            """));
        assertEquals("RecursionError", rec.typeName());

        // Interpreter stays healthy afterwards
        interp.eval("after = host.invoke(lambda n: n + 1, 9)");
        assertEquals("10", interp.getGlobal("after").toString());
    }
}
