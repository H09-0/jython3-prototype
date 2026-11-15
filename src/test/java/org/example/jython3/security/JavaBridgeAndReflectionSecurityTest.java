package org.example.jython3.security;

import org.example.jython3.Jython3Interpreter;
import org.example.jython3.bridge.JavaBridge;
import org.example.jython3.host.CapabilitySet;
import org.example.jython3.runtime.ExceptionTypeRegistry;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyHostHandle;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyList;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security verification for JavaBridge & reflection policies.
 * Standards: OWASP Top 10:2025 A05 (Injection), MITRE CWE-470 (Unsafe Reflection),
 * CWE-284 (Improper Access Control), SEI CERT Java SEC06-J.
 */
@Tag("security")
class JavaBridgeAndReflectionSecurityTest {

    public interface IntStep {
        int apply(int n);
    }

    public static class SampleTarget {
        public static int staticInt = 10;
        public static final int FINAL_INT = 99;
        public static Thread forbiddenStaticField = null;
        public int instanceInt = 5;
        public final int finalInstance = 7;
        public Thread forbiddenInstanceField = null;

        public int invoke(IntStep step, int n) {
            return step.apply(n);
        }

        public void asyncInvoke(Runnable r) {
            Thread t = new Thread(r);
            t.start();
            try { t.join(500); } catch (InterruptedException ignored) {}
        }
    }

    public static class ClinitProbe {
        public static final AtomicBoolean INITIALIZED = new AtomicBoolean(false);
        static { INITIALIZED.set(true); }
    }

    private static JavaAccessPolicy policyWithSampleTarget() {
        return new JavaAccessPolicy() {
            @Override public boolean canAccessClass(String name) {
                if (SampleTarget.class.getName().equals(name) || IntStep.class.getName().equals(name)) return true;
                return AllowlistPolicy.trusted().canAccessClass(name);
            }
            @Override public boolean canConstructClass(String name, String desc) {
                return SampleTarget.class.getName().equals(name) || AllowlistPolicy.trusted().canConstructClass(name, desc);
            }
            @Override public boolean canCallMethod(String owner, String methodName, String desc) {
                if (SampleTarget.class.getName().equals(owner)) return true;
                return AllowlistPolicy.trusted().canCallMethod(owner, methodName, desc);
            }
            @Override public boolean canReadField(String owner, String field) {
                return canAccessClass(owner);
            }
            @Override public boolean canWriteField(String owner, String field) {
                return AllowlistPolicy.trusted().canWriteField(owner, field)
                        || (SampleTarget.class.getName().equals(owner) && !"class".equals(field));
            }
        };
    }

    @Test
    @DisplayName("CWE-470 / CERT SEC06-J: AllowlistPolicy and SandboxedPolicy block reflection, threads, timers, SPI, net, script")
    void blocksDangerousClassesAndMembers() {
        var trusted = Jython3Interpreter.trusted();
        for (String code : new String[]{
                "from java.lang import Runtime",
                "from java.lang import ProcessBuilder",
                "from java.lang import Thread",
                "from java.lang import Class",
                "from java.lang import System",
                "from java.lang import StackWalker",
                "from java.lang import SecurityManager",
                "from java.lang.reflect import Method",
                "from java.lang.invoke import MethodHandles",
                "from java.util import Timer",
                "from java.util import TimerTask",
                "from java.util import ServiceLoader",
                "from java.util import ResourceBundle",
                "from java.net import Socket",
                "from javax.script import ScriptEngineManager",
                "from sun.misc import Unsafe",
                "from jdk.internal.misc import Unsafe",
                "from java.util import ArrayList\na = ArrayList()\na.getClass()",
                "from java.util import ArrayList\na = ArrayList()\ngetattr(a, 'class')",
                "from java.util import Properties\np = Properties()\np.loadFromXML(None)",
        }) {
            InterpreterException ex = assertThrows(InterpreterException.class, () -> trusted.eval(code), code);
            assertEquals("SecurityError", ex.typeName(), "Expected SecurityError for: " + code);
        }

        // SandboxedPolicy blocks java.io.File and DenyAllPolicy blocks everything
        var sandboxed = new Jython3Interpreter(SandboxedPolicy.get());
        assertEquals("SecurityError",
                assertThrows(InterpreterException.class, () -> sandboxed.eval("from java.io import File")).typeName());
        var denyAll = new Jython3Interpreter(DenyAllPolicy.INSTANCE);
        assertEquals("SecurityError",
                assertThrows(InterpreterException.class, () -> denyAll.eval("from java.lang import Math")).typeName());
    }

    @Test
    @DisplayName("CWE-470: Class.forName(..., false) prevents static initializer execution on denied classes")
    void deniedClassDoesNotRunStaticInitializer() {
        ClinitProbe.INITIALIZED.set(false);
        var restricted = Jython3Interpreter.restricted();
        assertEquals("SecurityError",
                assertThrows(InterpreterException.class,
                        () -> restricted.eval("from org.example.jython3.security.JavaBridgeAndReflectionSecurityTest import ClinitProbe")).typeName());
        assertThrows(InterpreterException.class,
                () -> JavaBridge.resolveClassOrPackage(ClinitProbe.class.getName(), DenyAllPolicy.INSTANCE));
        assertFalse(ClinitProbe.INITIALIZED.get(), "Static initializer must not run for policy-denied class");
    }

    @Test
    @DisplayName("CWE-284: JavaBridge caches cannot be poisoned across policies")
    void policyScopedOverloadCacheIsolation() {
        var trusted = Jython3Interpreter.trusted();
        trusted.eval("from java.util import ArrayList, Collections\na = ArrayList([3, 1, 2])\nCollections.sort(a)");

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
            @Override public boolean canWriteField(String owner, String field) { return false; }
        };
        var restricted = new Jython3Interpreter(denySort);
        assertEquals("SecurityError",
                assertThrows(InterpreterException.class,
                        () -> restricted.eval("from java.util import ArrayList\na = ArrayList([1, 2])")).typeName());
        assertEquals("SecurityError",
                assertThrows(InterpreterException.class,
                        () -> restricted.eval("from java.util import ArrayList, Collections\na = ArrayList()\nCollections.sort(a)")).typeName());
    }

    @Test
    @DisplayName("CWE-284: Static and instance Java field writes enforce policy, final, field-type, and PyHostHandle checks")
    void javaFieldWriteSecurityEnforcement() {
        var sandboxed = new Jython3Interpreter(SandboxedPolicy.get());
        assertEquals("SecurityError",
                assertThrows(InterpreterException.class,
                        () -> sandboxed.eval("from java.lang import Integer\nInteger.MAX_VALUE = 0")).typeName());

        var trusted = Jython3Interpreter.trusted();
        assertEquals("SecurityError",
                assertThrows(InterpreterException.class,
                        () -> trusted.eval("from java.lang import Integer\nInteger.MAX_VALUE = 0")).typeName());

        var policy = policyWithSampleTarget();
        var interp = new Jython3Interpreter(policy, HostAccessPolicy.DENY_ALL, ExecutionLimits.SANDBOXED,
                ExceptionTypeRegistry.standard(), CapabilitySet.none());
        interp.setGlobal("SampleTarget", JavaBridge.resolveClassOrPackage(SampleTarget.class.getName(), policy));
        interp.setGlobal("target", JavaBridge.toPyObject(new SampleTarget(), policy));

        // Final static & final instance -> AttributeError
        assertEquals("AttributeError",
                assertThrows(InterpreterException.class, () -> interp.eval("SampleTarget.FINAL_INT = 1")).typeName());
        assertEquals("AttributeError",
                assertThrows(InterpreterException.class, () -> interp.eval("target.finalInstance = 1")).typeName());

        // Forbidden field types (java.lang.Thread) -> SecurityError
        assertEquals("SecurityError",
                assertThrows(InterpreterException.class, () -> interp.eval("SampleTarget.forbiddenStaticField = None")).typeName());
        assertEquals("SecurityError",
                assertThrows(InterpreterException.class, () -> interp.eval("target.forbiddenInstanceField = None")).typeName());

        // PyHostHandle assigned to static/instance field or unwrapped to Object -> SecurityError
        PyHostHandle h = PyHostHandle.builder("ui.Fake").bindSession(interp.hostSession()).build();
        interp.setGlobal("h", h);
        assertEquals("SecurityError",
                assertThrows(InterpreterException.class, () -> interp.eval("SampleTarget.staticInt = h")).typeName());
        assertEquals("SecurityError",
                assertThrows(InterpreterException.class, () -> interp.eval("target.instanceInt = h")).typeName());
        assertEquals("SecurityError",
                assertThrows(InterpreterException.class,
                        () -> JavaBridge.toJavaObject(h, Object.class, policy)).typeName());
    }

    @Test
    @DisplayName("CWE-470 / CWE-674: SAM proxies reject cross-thread execution and toJavaObject rejects cyclic containers")
    void samProxyThreadAffinityAndCyclicConversionSafety() {
        var policy = policyWithSampleTarget();
        var interp = new Jython3Interpreter(policy);
        interp.setGlobal("target", JavaBridge.toPyObject(new SampleTarget(), policy));

        // Cross-thread Runnable callback is rejected instead of racing Jython3Interpreter
        var thrownInAsync = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        interp.eval("fn = lambda: 1 + 1");
        Runnable r = (Runnable) JavaBridge.toJavaObject(interp.getGlobal("fn"), Runnable.class, policy);
        Thread bg = new Thread(() -> {
            try { r.run(); } catch (Throwable t) { thrownInAsync.set(t); }
        });
        bg.start();
        try { bg.join(1000); } catch (InterruptedException ignored) {}
        assertTrue(thrownInAsync.get() instanceof InterpreterException ie && "SecurityError".equals(ie.typeName()),
                "Expected SecurityError on cross-thread proxy call, got: " + thrownInAsync.get());

        // Cyclic list conversion to Java raises ValueError instead of StackOverflowError
        interp.eval("cyc = []\ncyc.append(cyc)");
        PyList cyc = (PyList) interp.getGlobal("cyc");
        InterpreterException cycErr = assertThrows(InterpreterException.class,
                () -> JavaBridge.toJavaObject(cyc, List.class, policy));
        assertEquals("ValueError", cycErr.typeName());

        // Giant integer string conversion in JavaBridge rejected (> 10,000 digits)
        InterpreterException bigErr = assertThrows(InterpreterException.class,
                () -> JavaBridge.toJavaObject(new org.example.jython3.runtime.PyStr("9".repeat(15_000)),
                        java.math.BigInteger.class, policy));
        assertEquals("ValueError", bigErr.typeName());
    }

    @Test
    @DisplayName("CWE-284: JavaBridge.toPyObject blocks reflection, MethodHandle, Process, and Thread objects")
    void toPyObjectRejectsSensitiveJvmObjects() throws Exception {
        JavaAccessPolicy permissive = new JavaAccessPolicy() {
            @Override public boolean canAccessClass(String name) { return true; }
            @Override public boolean canConstructClass(String name, String desc) { return true; }
            @Override public boolean canCallMethod(String owner, String m, String d) { return true; }
            @Override public boolean canReadField(String owner, String f) { return true; }
            @Override public boolean canWriteField(String owner, String f) { return true; }
        };
        assertEquals("SecurityError", assertThrows(InterpreterException.class,
                () -> JavaBridge.toPyObject(String.class, permissive)).typeName());
        assertEquals("SecurityError", assertThrows(InterpreterException.class,
                () -> JavaBridge.toPyObject(String.class.getMethod("length"), permissive)).typeName());
        assertEquals("SecurityError", assertThrows(InterpreterException.class,
                () -> JavaBridge.toPyObject(Thread.currentThread(), permissive)).typeName());
        assertEquals("SecurityError", assertThrows(InterpreterException.class,
                () -> JavaBridge.toPyObject(ProcessHandle.current(), permissive)).typeName());
        assertEquals("SecurityError", assertThrows(InterpreterException.class,
                () -> JavaBridge.toPyObject(java.lang.invoke.MethodHandles.publicLookup(), permissive)).typeName());
    }
}
