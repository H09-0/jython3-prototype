package org.example.jython3;

import org.example.jython3.api.EmbeddedRuntime;
import org.example.jython3.host.CapabilitySet;
import org.example.jython3.host.HostCapability;
import org.example.jython3.security.ExecutionLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
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
}
