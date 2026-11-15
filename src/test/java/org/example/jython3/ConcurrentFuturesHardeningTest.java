package org.example.jython3;

import org.example.jython3.api.EmbeddedRuntime;
import org.example.jython3.host.CapabilitySet;
import org.example.jython3.host.HostCapability;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.security.ExecutionLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for true completion-ordered as_completed(), Future.cancel(), quotas,
 * bounded shutdown, and session teardown under blocked tasks.
 */
class ConcurrentFuturesHardeningTest {

    @Test
    @DisplayName("as_completed yields futures in true completion order rather than submission order")
    void asCompletedYieldsInCompletionOrder() {
        var caps = CapabilitySet.builder().allow(HostCapability.THREADS).maxThreads(4).build();
        try (var rt = EmbeddedRuntime.builder().capabilities(caps).limits(ExecutionLimits.TRUSTED).build()) {
            rt.eval("""
                import time, threading
                from concurrent.futures import ThreadPoolExecutor, as_completed

                gate = threading.Event()
                def slow():
                    gate.wait(2.0)
                    return "slow"
                def fast():
                    return "fast"

                order = []
                with ThreadPoolExecutor(max_workers=2) as ex:
                    f_slow = ex.submit(slow)
                    f_fast = ex.submit(fast)
                    for fut in as_completed([f_slow, f_fast], timeout=3.0):
                        val = fut.result()
                        order.append(val)
                        if val == "fast":
                            gate.set()
                """);
            assertEquals("['fast', 'slow']", rt.getGlobal("order").toString());
        }
    }

    @Test
    @DisplayName("Future.cancel() cancels queued pending futures and returns False for finished/running futures")
    void futureCancelWorksForPendingTasks() {
        var caps = CapabilitySet.builder().allow(HostCapability.THREADS).maxThreads(1).build();
        try (var rt = EmbeddedRuntime.builder().capabilities(caps).limits(ExecutionLimits.TRUSTED).build()) {
            rt.eval("""
                import threading
                from concurrent.futures import ThreadPoolExecutor

                gate = threading.Event()
                started = threading.Event()
                def blocker():
                    started.set()
                    gate.wait(2.0)
                    return "done"
                def queued():
                    return "should-not-run"

                ex = ThreadPoolExecutor(max_workers=1)
                f1 = ex.submit(blocker)
                assert started.wait(2.0)
                f2 = ex.submit(queued)
                cancelled_f2 = f2.cancel()
                cancelled_f1 = f1.cancel()
                is_cancelled_f2 = f2.cancelled()
                is_done_f2 = f2.done()
                gate.set()
                r1 = f1.result()
                ex.shutdown(wait=True)
                """);
            assertEquals("True", rt.getGlobal("cancelled_f2").toString());
            assertEquals("False", rt.getGlobal("cancelled_f1").toString());
            assertEquals("True", rt.getGlobal("is_cancelled_f2").toString());
            assertEquals("True", rt.getGlobal("is_done_f2").toString());
            assertEquals("done", rt.getGlobal("r1").toString());
            assertTrue(rt.hostSession().activeThreadCount() == 0);
        }
    }

    @Test
    @DisplayName("Pool and queue quotas reject excess ThreadPoolExecutors and queued tasks with audit entries")
    void poolAndQueueQuotasEnforced() {
        var caps = CapabilitySet.builder()
                .allow(HostCapability.THREADS)
                .maxThreads(1)
                .maxThreadPools(2)
                .maxQueuedTasks(2)
                .build();
        try (var rt = EmbeddedRuntime.builder().capabilities(caps).limits(ExecutionLimits.TRUSTED).build()) {
            InterpreterException poolErr = assertThrows(InterpreterException.class, () -> rt.eval("""
                from concurrent.futures import ThreadPoolExecutor
                p1 = ThreadPoolExecutor(1)
                p2 = ThreadPoolExecutor(1)
                p3 = ThreadPoolExecutor(1)
                """));
            assertEquals("RuntimeError", poolErr.typeName());
            assertTrue(rt.auditEntries().stream().anyMatch(e -> !e.allowed() && e.detail().contains("maxThreadPools=2")));
            rt.restart();
            assertEquals(0, rt.hostSession().activeThreadPoolCount());

            InterpreterException queueErr = assertThrows(InterpreterException.class, () -> rt.eval("""
                import threading
                from concurrent.futures import ThreadPoolExecutor
                gate = threading.Event()
                started = threading.Event()
                def block():
                    started.set()
                    gate.wait(2.0)
                ex = ThreadPoolExecutor(1)
                ex.submit(block)
                assert started.wait(2.0)
                ex.submit(lambda: 1)
                ex.submit(lambda: 2)
                ex.submit(lambda: 3)
                """));
            assertEquals("RuntimeError", queueErr.typeName());
            assertTrue(rt.auditEntries().stream().anyMatch(e -> !e.allowed() && e.detail().contains("maxQueuedTasks=2")));
        }
    }

    @Test
    @DisplayName("Bounded shutdown and session close unblock infinite-loop tasks and Semaphore/Lock waits cleanly")
    void blockedTaskShutdownAndSessionCloseStress() throws Exception {
        var caps = CapabilitySet.builder()
                .allow(HostCapability.THREADS)
                .maxThreads(4)
                .maxThreadPools(4)
                .executorShutdownTimeout(Duration.ofMillis(150))
                .build();

        // 1. Semaphore timeout + bounded shutdown of infinite task
        try (var rt = EmbeddedRuntime.builder().capabilities(caps).limits(ExecutionLimits.SANDBOXED).build()) {
            long t0 = System.nanoTime();
            rt.eval("""
                import threading
                from concurrent.futures import ThreadPoolExecutor
                sem = threading.Semaphore(0)
                acquired = sem.acquire(timeout=0.03)
                assert acquired == False

                ex = ThreadPoolExecutor(1)
                def spin():
                    while True:
                        pass
                ex.submit(spin)
                ex.shutdown(wait=True, cancel_futures=True)
                """);
            long elapsedMs = (System.nanoTime() - t0) / 1_000_000L;
            assertTrue(elapsedMs < 3000, "shutdown must stay bounded, took " + elapsedMs + " ms");
            rt.restart();
            assertEquals(0, rt.hostSession().activeThreadPoolCount());
        }

        // 2. Closing session while worker thread is blocked on Lock/Semaphore interrupts cleanly
        try (var rt = EmbeddedRuntime.builder().capabilities(caps).limits(ExecutionLimits.TRUSTED).build();
             var exec = Executors.newSingleThreadExecutor()) {
            CountDownLatch blocked = new CountDownLatch(1);
            rt.bindHandle("probe", org.example.jython3.runtime.PyHostHandle.builder("test.Probe")
                    .bindSession(rt.hostSession())
                    .method("ready", new org.example.jython3.runtime.PyBuiltinFunction("ready", a -> {
                        blocked.countDown();
                        return org.example.jython3.runtime.PyNull.INSTANCE;
                    }))
                    .build());
            Future<?> fut = exec.submit(() -> assertThrows(InterpreterException.class, () -> rt.eval("""
                import threading
                sem = threading.Semaphore(0)
                probe.ready()
                sem.acquire()
                """)));
            assertTrue(blocked.await(5, TimeUnit.SECONDS));
            rt.close();
            fut.get(5, TimeUnit.SECONDS);
        }

        // 3. Field test: 40 cycles of executor + as_completed + shutdown with zero leaked pools/threads
        try (var rt = EmbeddedRuntime.builder().capabilities(caps).limits(ExecutionLimits.TRUSTED).build()) {
            for (int i = 0; i < 40; i++) {
                rt.eval("""
                    from concurrent.futures import ThreadPoolExecutor, as_completed
                    with ThreadPoolExecutor(2) as ex:
                        fs = [ex.submit(lambda x: x * x, k) for k in range(6)]
                        vals = sorted([f.result() for f in as_completed(fs)])
                        assert vals == [0, 1, 4, 9, 16, 25]
                    """);
                rt.restart();
            }
            assertEquals(0, rt.hostSession().activeThreadPoolCount());
            assertEquals(0, rt.hostSession().activeThreadCount());
        }
    }
}
