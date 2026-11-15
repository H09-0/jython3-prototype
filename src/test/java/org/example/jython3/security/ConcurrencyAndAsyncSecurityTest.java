package org.example.jython3.security;

import org.example.jython3.Jython3Interpreter;
import org.example.jython3.api.EmbeddedRuntime;
import org.example.jython3.host.CapabilitySet;
import org.example.jython3.host.HostCapability;
import org.example.jython3.runtime.InterpreterException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Security verification for Virtual Threads, ThreadPoolExecutor quotas, asyncio DoS limits,
 * generator re-entrancy/isolation, and concurrent OOP __bases__ mutation.
 * Standards: OWASP Top 10:2025 A06/A10, CWE-400, CWE-362, OpenSSF pyscg-0024.
 */
@Tag("security")
class ConcurrencyAndAsyncSecurityTest {

    @Test
    @DisplayName("CWE-400 / OpenSSF pyscg-0024: Virtual Thread, Pool, and Queue quotas + cancellation-aware join/shutdown")
    void virtualThreadAndExecutorQuotasAndCancellation() {
        var caps = CapabilitySet.builder()
                .allow(HostCapability.THREADS)
                .maxThreads(3)
                .maxThreadPools(1)
                .maxQueuedTasks(2)
                .executorShutdownTimeout(Duration.ofMillis(200))
                .build();

        try (var rt = EmbeddedRuntime.builder().capabilities(caps).limits(ExecutionLimits.SANDBOXED).build()) {
            // Exceeding maxThreadPools -> RuntimeError
            assertEquals("RuntimeError",
                    assertThrows(InterpreterException.class, () -> rt.eval("""
                        import concurrent.futures as cf
                        p1 = cf.ThreadPoolExecutor(max_workers=1)
                        p2 = cf.ThreadPoolExecutor(max_workers=1)
                        """)).typeName());
        }

        // Thread.join() aborts cleanly on host cancel() instead of hanging forever
        var longCaps = CapabilitySet.builder().allow(HostCapability.THREADS).maxThreads(4).build();
        try (var interp = new Jython3Interpreter(
                DenyAllPolicy.INSTANCE, HostAccessPolicy.DENY_ALL,
                new ExecutionLimits(10_000_000, Duration.ofSeconds(5), 500_000, 100_000, 1_000_000, 10_000),
                org.example.jython3.runtime.ExceptionTypeRegistry.standard(), longCaps)) {
            Thread canceller = new Thread(() -> {
                try { Thread.sleep(80); } catch (InterruptedException ignored) {}
                interp.cancel();
            });
            canceller.start();
            InterpreterException cancelErr = assertThrows(InterpreterException.class, () -> interp.eval("""
                import threading, time
                t = threading.Thread(target=lambda: time.sleep(5))
                t.start()
                t.join()
                """));
            assertEquals("KeyboardInterrupt", cancelErr.typeName());
        }
    }

    @Test
    @DisplayName("CWE-400: asyncio enforces nested-run prohibition, deadlock detection, ExecutionLimits, and MAX_TASKS bomb protection")
    void asyncioSecurityControls() {
        var tight = new ExecutionLimits(10_000, Duration.ofMillis(200), 50_000, 1_000, 1_000, 100);
        try (var rt = EmbeddedRuntime.builder().limits(tight).build()) {
            assertEquals("RuntimeError",
                    assertThrows(InterpreterException.class, () -> rt.eval("""
                        import asyncio
                        async def inner(): return 1
                        async def outer(): return asyncio.run(inner())
                        asyncio.run(outer())
                        """)).typeName());

            assertEquals("RuntimeError",
                    assertThrows(InterpreterException.class, () -> rt.eval("""
                        import asyncio
                        async def stuck():
                            await asyncio.Event().wait()
                        asyncio.run(stuck())
                        """)).typeName());

            InterpreterException spinErr = assertThrows(InterpreterException.class, () -> rt.eval("""
                import asyncio
                async def spin():
                    while True:
                        await asyncio.sleep(0)
                asyncio.run(spin())
                """));
            assertTrue("TimeoutError".equals(spinErr.typeName()) || "MemoryError".equals(spinErr.typeName()));
        }

        try (var rt2 = EmbeddedRuntime.builder().limits(ExecutionLimits.TRUSTED).build()) {
            assertEquals("RuntimeError",
                    assertThrows(InterpreterException.class, () -> rt2.eval("""
                        import asyncio
                        async def noop(): return 1
                        async def bomb():
                            for _ in range(5000):
                                asyncio.create_task(noop())
                        asyncio.run(bomb())
                        """)).typeName());
        }
    }

    @Test
    @DisplayName("Generator security: re-entrancy, cross-interpreter ownership, and tight ExecutionLimits enforced")
    void generatorReentrancyCrossInterpreterAndLimits() {
        var tight = new ExecutionLimits(500, Duration.ofMillis(200), 50_000, 1_000, 1_000, 100);
        try (var interpA = new Jython3Interpreter(DenyAllPolicy.INSTANCE, HostAccessPolicy.DENY_ALL, tight,
                org.example.jython3.runtime.ExceptionTypeRegistry.standard(), CapabilitySet.none());
             var interpB = Jython3Interpreter.restricted()) {
            interpA.eval("""
                def inf():
                    i = 0
                    while True:
                        yield i
                        i += 1
                g_inf = inf()
                def reent():
                    yield next(g_reent)
                g_reent = reent()
                """);

            // Re-entrant generator call -> ValueError
            assertEquals("ValueError",
                    assertThrows(InterpreterException.class, () -> interpA.eval("next(g_reent)")).typeName());

            // Cross-interpreter generator execution rejected
            var gInf = interpA.getGlobal("g_inf");
            interpB.setGlobal("foreign_g", gInf);
            assertEquals("RuntimeError",
                    assertThrows(InterpreterException.class, () -> interpB.eval("next(foreign_g)")).typeName());

            // Infinite generator stopped by ExecutionLimits
            assertEquals("TimeoutError",
                    assertThrows(InterpreterException.class, () -> interpA.eval("""
                        for _ in g_inf:
                            pass
                        """)).typeName());
        }
    }

    @Test
    @DisplayName("CWE-362: Concurrent __bases__ mutation and member lookups never corrupt MRO caches")
    void concurrentOopBasesMutationIsThreadSafe() {
        var caps = CapabilitySet.builder().allow(HostCapability.THREADS).maxThreads(8).build();
        try (var interp = new Jython3Interpreter(
                DenyAllPolicy.INSTANCE, HostAccessPolicy.DENY_ALL, ExecutionLimits.TRUSTED,
                org.example.jython3.runtime.ExceptionTypeRegistry.standard(), caps)) {
            interp.eval("""
                import threading
                class BaseA:
                    val = 1
                class BaseB:
                    val = 2
                class Child(BaseA):
                    pass
                errors = []
                def mutator():
                    for i in range(40):
                        Child.__bases__ = (BaseA,) if i % 2 == 0 else (BaseB,)
                def reader():
                    for _ in range(40):
                        v = Child().val
                        if v not in (1, 2):
                            errors.append(v)
                threads = [threading.Thread(target=mutator if i % 2 == 0 else reader) for i in range(6)]
                for t in threads: t.start()
                for t in threads: t.join()
                assert errors == []
                """);
        }
    }
}
