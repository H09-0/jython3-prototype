package org.example.jython3;

import org.example.jython3.api.EmbeddedRuntime;
import org.example.jython3.host.CapabilitySet;
import org.example.jython3.host.HostCapability;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.security.ExecutionLimits;
import org.example.jython3.ui.HeadlessUiToolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for capability-gated Java 21 Virtual Threads concurrency (threading module:
 * Thread, Lock, RLock, Event, Semaphore, quotas, and deterministic teardown).
 */
class VirtualThreadsConcurrencyTest {

    @Test
    @DisplayName("threading.Thread is denied by default without HostCapability.THREADS")
    void threadingDeniedByDefault() {
        try (var rt = EmbeddedRuntime.builder().uiToolkit(new HeadlessUiToolkit()).build()) {
            assertTrue(rt.supportsFeature("virtual-threads"));
            InterpreterException ex = assertThrows(InterpreterException.class, () -> rt.eval("""
                import threading
                t = threading.Thread(target=lambda: None)
                t.start()
                """));
            assertEquals("SecurityError", ex.typeName());
        }
    }

    @Test
    @DisplayName("Virtual threads run in parallel, synchronize with Lock/RLock/Event/Semaphore, and enforce maxThreads")
    void virtualThreadsSyncAndQuotas() {
        var caps = CapabilitySet.builder()
                .allow(HostCapability.THREADS)
                .maxThreads(8)
                .build();

        try (var rt = EmbeddedRuntime.builder()
                .capabilities(caps)
                .limits(ExecutionLimits.TRUSTED)
                .uiToolkit(new HeadlessUiToolkit())
                .build()) {
            rt.eval("""
                import threading
                lock = threading.Lock()
                rlock = threading.RLock()
                ev = threading.Event()
                sem = threading.Semaphore(2)
                shared = {"total": 0}

                def worker(n, k=1):
                    ev.wait()
                    for i in range(n):
                        with lock:
                            with rlock:
                                with sem:
                                    shared["total"] += k

                threads = [threading.Thread(target=worker, args=(100,), kwargs={"k": 2}, name="w-" + str(i)) for i in range(6)]
                for t in threads:
                    t.start()
                assert threading.active_count() >= 2
                ev.set()
                for t in threads:
                    t.join()
                total = shared["total"]
                cur_name = threading.current_thread().name
                """);
            assertEquals(PyInt.of(1200), rt.getGlobal("total"));
            assertEquals(0, rt.hostSession().activeThreadCount());
        }

        // Enforce maxThreads=2 quota
        var tinyQuota = CapabilitySet.builder()
                .allow(HostCapability.THREADS)
                .maxThreads(2)
                .build();
        try (var rt = EmbeddedRuntime.builder().capabilities(tinyQuota).build()) {
            InterpreterException quotaErr = assertThrows(InterpreterException.class, () -> rt.eval("""
                import threading
                ev = threading.Event()
                ts = [threading.Thread(target=lambda: ev.wait()) for _ in range(3)]
                for t in ts:
                    t.start()
                """));
            assertEquals("RuntimeError", quotaErr.typeName());
            rt.restart();
            assertEquals(0, rt.hostSession().activeThreadCount());
        }
    }

    @Test
    @DisplayName("Field test: restart/close cleanly interrupts & joins runaway virtual threads across 30 cycles")
    void virtualThreadTeardownFieldTest() {
        var caps = CapabilitySet.builder().allow(HostCapability.THREADS).maxThreads(8).build();
        try (var rt = EmbeddedRuntime.builder()
                .capabilities(caps)
                .limits(ExecutionLimits.SANDBOXED)
                .uiToolkit(new HeadlessUiToolkit())
                .build()) {
            for (int c = 0; c < 30; c++) {
                rt.eval("""
                    import threading, time
                    ev = threading.Event()
                    t1 = threading.Thread(target=lambda: ev.wait())
                    t2 = threading.Thread(target=lambda: time.sleep(5))
                    t1.start()
                    t2.start()
                    alive = t1.is_alive() and t2.is_alive()
                    """);
                assertEquals(PyBool.TRUE, rt.getGlobal("alive"));
                rt.restart();
                assertEquals(0, rt.hostSession().activeThreadCount());
                assertEquals(0, rt.hostSession().managedResourceCount());
            }
        }
    }
}
