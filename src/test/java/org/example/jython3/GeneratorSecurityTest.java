package org.example.jython3;

import org.example.jython3.bridge.JavaBridge;
import org.example.jython3.runtime.ExceptionTypeRegistry;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyCaller;
import org.example.jython3.runtime.PyGenerator;
import org.example.jython3.security.AllowlistPolicy;
import org.example.jython3.security.ExecutionLimits;
import org.example.jython3.security.HostAccessPolicy;
import org.example.jython3.security.JavaAccessPolicy;
import org.example.jython3.security.SandboxedPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security, ExecutionLimits, cancellation, cleanup, and Java callback boundary
 * tests for lazy generators and generator expressions.
 */
class GeneratorSecurityTest {

    public interface GenBridge {
        int consume(Iterable<Object> it);
        Object roundtrip(java.util.function.IntUnaryOperator fn, int n);
    }

    public static class HostBridge {
        public int sumViaJava(Iterable<Object> it) {
            int s = 0;
            for (Object o : it) {
                s += ((Number) o).intValue();
            }
            return s;
        }

        public int invokeCallback(java.util.function.IntUnaryOperator fn, int n) {
            return fn.applyAsInt(n);
        }

        public int failMidway(java.util.function.IntUnaryOperator fn) {
            fn.applyAsInt(1);
            throw new IllegalStateException("java-midway-failure");
        }
    }

    private static JavaAccessPolicy policyWithBridge() {
        return new JavaAccessPolicy() {
            @Override public boolean canAccessClass(String binaryName) {
                if (binaryName.equals(HostBridge.class.getName())
                        || binaryName.equals(java.util.function.IntUnaryOperator.class.getName())
                        || binaryName.equals(Iterable.class.getName())) {
                    return true;
                }
                return AllowlistPolicy.trusted().canAccessClass(binaryName);
            }
            @Override public boolean canConstructClass(String binaryName, String descriptor) {
                return canAccessClass(binaryName);
            }
            @Override public boolean canCallMethod(String owner, String methodName, String descriptor) {
                if (owner.equals(HostBridge.class.getName())) return true;
                return AllowlistPolicy.trusted().canCallMethod(owner, methodName, descriptor);
            }
            @Override public boolean canReadField(String owner, String fieldName) { return canAccessClass(owner); }
            @Override public boolean canWriteField(String owner, String fieldName) {
                return canAccessClass(owner) && !"class".equals(fieldName);
            }
        };
    }

    @Test
    @DisplayName("Infinite generator obeys instruction budget, timeout, and host cancellation")
    void infiniteGeneratorObeysExecutionLimits() {
        // 1. Instruction budget
        var budgetLimits = ExecutionLimits.builder().maxInstructions(1_500).build();
        var budgetInterp = new Jython3Interpreter(SandboxedPolicy.get(), HostAccessPolicy.DENY_ALL, budgetLimits);
        var e1 = assertThrows(InterpreterException.class, () -> budgetInterp.eval("""
            def inf():
                i = 0
                while True:
                    yield i
                    i += 1
            for x in inf():
                pass
            """));
        assertEquals("TimeoutError", e1.typeName());
        assertNull(PyCaller.current());

        // 2. Generator expression cannot bypass instruction budget
        var e2 = assertThrows(InterpreterException.class, () -> budgetInterp.eval("""
            s = sum(x for x in range(10000))
            """));
        assertEquals("TimeoutError", e2.typeName());

        // 3. Wall-clock timeout
        var timeLimits = ExecutionLimits.builder().timeout(Duration.ofMillis(60)).build();
        var timeInterp = new Jython3Interpreter(SandboxedPolicy.get(), HostAccessPolicy.DENY_ALL, timeLimits);
        var e3 = assertThrows(InterpreterException.class, () -> timeInterp.eval("""
            def spin():
                while True:
                    yield 1
            for _ in spin():
                pass
            """));
        assertEquals("TimeoutError", e3.typeName());

        // 4. Host cancellation during generator iteration marks generator FAILED and releases frame
        var cancelInterp = Jython3Interpreter.trusted();
        cancelInterp.setGlobal("cancel_hook", new org.example.jython3.runtime.PyBuiltinFunction("cancel_hook", args -> {
            cancelInterp.cancel();
            return org.example.jython3.runtime.PyNull.INSTANCE;
        }));
        var e4 = assertThrows(InterpreterException.class, () -> cancelInterp.eval("""
            def inf_c():
                yield 1
                cancel_hook()
                yield 2
            g = inf_c()
            next(g)
            next(g)
            """));
        assertEquals("KeyboardInterrupt", e4.typeName());
        var gObj = (PyGenerator) cancelInterp.getGlobal("g");
        assertEquals(PyGenerator.State.FAILED, gObj.state());
        assertNull(gObj.operandStack());
        assertNull(gObj.locals());
        assertNull(gObj.creationContext());
        assertNull(PyCaller.current());
    }

    @Test
    @DisplayName("Generator cannot bypass SandboxedPolicy, HostAccessPolicy, or PolicySanitizer")
    void generatorsEnforceSecurityPolicies() {
        var sandboxed = new Jython3Interpreter(SandboxedPolicy.get());

        // Disallowed Java class inside generator function & generator expression
        var e1 = assertThrows(InterpreterException.class, () -> sandboxed.eval("""
            def bad():
                from java.io import File
                yield File(".")
            next(bad())
            """));
        assertEquals("SecurityError", e1.typeName());

        var e2 = assertThrows(InterpreterException.class, () -> sandboxed.eval("""
            from java.util import ArrayList
            g = (a.getClass() for a in [ArrayList()])
            next(g)
            """));
        assertEquals("SecurityError", e2.typeName());

        // HostAccessPolicy.DENY_ALL enforced inside generator expression
        var e3 = assertThrows(InterpreterException.class, () -> sandboxed.eval("""
            import os
            g = (os.listdir("/") for _ in [1])
            next(g)
            """));
        assertEquals("SecurityError", e3.typeName());

        // PolicySanitizer rejects injecting a trusted generator holding a restricted Java object
        var trusted = Jython3Interpreter.trusted();
        trusted.eval("""
            from java.io import File
            def hold(f):
                yield f
            g_secret = hold(File("."))
            """);
        var e4 = assertThrows(InterpreterException.class,
                () -> sandboxed.setGlobal("g_bad", trusted.getGlobal("g_secret")));
        assertEquals("SecurityError", e4.typeName());

        // Generator created in one interpreter cannot be resumed in another
        trusted.eval("def ok_gen():\n    yield 1\ng_ok = ok_gen()");
        sandboxed.setGlobal("g_ok", trusted.getGlobal("g_ok"));
        var e5 = assertThrows(InterpreterException.class, () -> sandboxed.eval("next(g_ok)"));
        assertEquals("RuntimeError", e5.typeName());
    }

    @Test
    @DisplayName("Java callbacks consuming/returning generators preserve caller state, tracebacks, and RecursionError")
    void javaCallbacksAndRecursionWithGenerators() {
        var policy = policyWithBridge();
        var interp = new Jython3Interpreter(policy);
        interp.setGlobal("bridge", JavaBridge.toPyObject(new HostBridge(), policy));

        interp.eval("""
            # 1. Java iterable (ArrayList) + lazy generator + Java callback during iteration
            from java.util import ArrayList
            jl = ArrayList()
            jl.add(10); jl.add(20); jl.add(30)

            def double_gen(seq):
                for item in seq:
                    yield bridge.invokeCallback(lambda n: n * 2, item)

            assert list(double_gen(jl)) == [20, 40, 60]

            # 2. Callback returns a generator and caller consumes it while preserving caller locals
            def caller_locals():
                local_x = 100
                def step(n):
                    gen = (i for i in range(n))
                    return sum(gen)
                res = bridge.invokeCallback(step, 5)
                return local_x + res
            assert caller_locals() == 110

            # 3. Java exception during generator iteration cleans generator frame and keeps traceback
            fin_log = []
            def failing_cb_gen():
                try:
                    yield 1
                    bridge.failMidway(lambda n: n)
                finally:
                    fin_log.append("gen-finally")

            fg = failing_cb_gen()
            assert next(fg) == 1
            try:
                next(fg)
                failed = False
            except JavaException:
                failed = True
            assert failed and fin_log == ["gen-finally"]

            # 4. zip() stops on finite sequence even when paired with an infinite generator
            def inf():
                i = 0
                while True:
                    yield i
                    i += 1
            assert zip(["a", "b"], inf()) == [("a", 0), ("b", 1)]

            ok = True
            """);
        assertEquals(PyBool.TRUE, interp.getGlobal("ok"));

        var fg = (PyGenerator) interp.getGlobal("fg");
        assertEquals(PyGenerator.State.FAILED, fg.state());
        assertNull(fg.operandStack());
        assertNull(fg.locals());

        // 5. Recursive generator + Java callback hits RecursionError, not StackOverflowError
        var rec = assertThrows(InterpreterException.class, () -> interp.eval("""
            def rec_cb(n):
                if n <= 0:
                    yield 0
                    return
                for v in rec_cb(bridge.invokeCallback(lambda k: k - 1, n)):
                    yield v + 1
            next(rec_cb(1000))
            """));
        assertEquals("RecursionError", rec.typeName());
        assertNull(PyCaller.current());

        // Interpreter stays healthy afterwards
        interp.eval("after = sum(x for x in [1, 2, 3])");
        assertEquals("6", interp.getGlobal("after").toString());
    }

    @Test
    @DisplayName("Generator send/throw/close raise explicit NotImplementedError and leave ThreadLocals clean")
    void sendThrowCloseRejectedAndThreadLocalsClean() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("g = (x for x in [1, 2])");
        for (String expr : List.of("g.send(1)", "g.throw(ValueError)", "g.close()")) {
            var e = assertThrows(InterpreterException.class, () -> interp.eval(expr), expr);
            assertEquals("NotImplementedError", e.typeName(), expr);
        }
        assertNull(PyCaller.current());
        assertTrue(ExceptionTypeRegistry.current() != interp.exceptionTypes());
    }
}
