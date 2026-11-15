package org.example.jython3;

import org.example.jython3.api.EmbeddedRuntime;
import org.example.jython3.host.CapabilitySet;
import org.example.jython3.host.HostCapability;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyByteArray;
import org.example.jython3.runtime.PyBytes;
import org.example.jython3.runtime.PyHostHandle;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyJavaObject;
import org.example.jython3.runtime.PyList;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.security.DenyAllPolicy;
import org.example.jython3.security.ExecutionLimits;
import org.example.jython3.security.PolicySanitizer;
import org.example.jython3.ui.HeadlessUiToolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for concurrent.futures (Virtual-Thread ThreadPoolExecutor) plus
 * adversarial security tests across dunder recursion, __bases__ cycles,
 * thread quotas, container smuggling, and DoS limits.
 */
class ConcurrentFuturesAndSecurityTest {

    @Test
    @DisplayName("concurrent.futures.ThreadPoolExecutor executes parallel tasks, map(), and context teardown")
    void threadPoolExecutorWorksWithVirtualThreads() {
        var caps = CapabilitySet.builder().allow(HostCapability.THREADS).maxThreads(6).build();
        try (var rt = EmbeddedRuntime.builder().capabilities(caps).limits(ExecutionLimits.TRUSTED).build()) {
            rt.eval("""
                from concurrent.futures import ThreadPoolExecutor, as_completed

                def square(x):
                    return x * x

                with ThreadPoolExecutor(max_workers=4) as ex:
                    f1 = ex.submit(square, 5)
                    f2 = ex.submit(square, 9)
                    mapped = list(ex.map(square, [1, 2, 3, 4]))

                # Also test direct import concurrent.futures + exception propagation
                import concurrent.futures
                pool = concurrent.futures.ThreadPoolExecutor(2)
                def boom():
                    raise ValueError("task-fail")
                bad = pool.submit(boom)
                exc_obj = bad.exception()
                pool.shutdown(wait=True)

                total = f1.result() + f2.result() + sum(mapped)
                """);
            assertEquals(PyInt.of(25 + 81 + 30), rt.getGlobal("total"));
            assertTrue(rt.getGlobal("exc_obj").toString().contains("task-fail"));
            assertEquals(0, rt.hostSession().activeThreadCount());
        }
    }

    @Test
    @DisplayName("ThreadPoolExecutor is denied by default without HostCapability.THREADS")
    void threadPoolDeniedByDefault() {
        try (var rt = EmbeddedRuntime.builder().build()) {
            InterpreterException ex = assertThrows(InterpreterException.class, () -> rt.eval("""
                from concurrent.futures import ThreadPoolExecutor
                ThreadPoolExecutor(2)
                """));
            assertEquals("SecurityError", ex.typeName());
        }
    }

    @Test
    @DisplayName("Adversarial security: dunder infinite recursion, __bases__ cycles, str width DoS, and cross-session smuggling")
    void adversarialSecuritySuite() {
        var tight = new ExecutionLimits(50_000, Duration.ofSeconds(2), 50_000, 1_000, 1_000, 100);
        try (var hostA = EmbeddedRuntime.builder().uiToolkit(new HeadlessUiToolkit()).build();
             var rt = EmbeddedRuntime.builder().limits(tight).uiToolkit(new HeadlessUiToolkit()).build()) {

            // 1. Dunder infinite recursion triggers RecursionError cleanly
            for (String loopScript : List.of(
                    "class A:\n    def __add__(self, o): return self + o\nA() + 1",
                    "class B:\n    def __eq__(self, o): return self == o\nB() == 1",
                    "class C:\n    def __bool__(self): return bool(self)\nbool(C())",
                    "class D:\n    def __call__(self): return self()\nD()()",
                    "class E:\n    def __getitem__(self, k): return self[k]\nE()[0]"
            )) {
                InterpreterException rec = assertThrows(InterpreterException.class, () -> rt.eval(loopScript));
                assertEquals("RecursionError", rec.typeName(), "Expected RecursionError for:\n" + loopScript);
            }

            // 2. Dynamic __bases__ cycle & non-class rejection
            assertEquals("TypeError",
                    assertThrows(InterpreterException.class, () -> rt.eval("""
                        class X: pass
                        class Y(X): pass
                        X.__bases__ = (Y,)
                        """)).typeName());
            assertEquals("TypeError",
                    assertThrows(InterpreterException.class, () -> rt.eval("""
                        class X: pass
                        X.__bases__ = (123,)
                        """)).typeName());

            // 3. str.center/zfill/format DoS blocked before OOM
            assertEquals("MemoryError",
                    assertThrows(InterpreterException.class, () -> rt.eval("'x'.center(999999999)")).typeName());
            assertEquals("MemoryError",
                    assertThrows(InterpreterException.class, () -> rt.eval("'x'.zfill(999999999)")).typeName());
            assertEquals("MemoryError",
                    assertThrows(InterpreterException.class, () -> rt.eval("'{:999999999}'.format('x')")).typeName());

            // 4. bytes / bytearray immutable & sanitized, and foreign handle inside PyList/PyHostHandle blocked
            PyHostHandle hA = PyHostHandle.builder("vault.A")
                    .bindSession(hostA.hostSession())
                    .prop("k", () -> new PyStr("secret"))
                    .build();
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class,
                            () -> PolicySanitizer.sanitize(new PyList(List.of(hA)), DenyAllPolicy.INSTANCE, rt.hostSession()))
                            .typeName());
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class,
                            () -> PolicySanitizer.sanitize(new PyJavaObject(Runtime.getRuntime(), DenyAllPolicy.INSTANCE),
                                    DenyAllPolicy.INSTANCE, rt.hostSession()))
                            .typeName());

            // Clean bytes/bytearray pass sanitizer
            var pb = new PyBytes("ok".getBytes(StandardCharsets.UTF_8));
            var pba = new PyByteArray("ok".getBytes(StandardCharsets.UTF_8));
            assertEquals(pb, PolicySanitizer.sanitize(pb, DenyAllPolicy.INSTANCE, rt.hostSession()));
            assertEquals(pba, PolicySanitizer.sanitize(pba, DenyAllPolicy.INSTANCE, rt.hostSession()));
        }
    }
}
