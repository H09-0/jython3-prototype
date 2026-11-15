package org.example.jython3;

import org.example.jython3.benchmark.HostDispatchBenchmark;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test ensuring host UI/timer/control/FX dispatch benchmarks execute cleanly
 * with healthy throughput and zero errors.
 */
class HostDispatchPerformanceTest {

    @Test
    @DisplayName("Host UI callback, control command, timer churn, and FX dispatch benchmarks run cleanly")
    void hostDispatchBenchmarksCompleteCleanly() {
        var r1 = HostDispatchBenchmark.benchUiEventDispatch(1_000);
        var r2 = HostDispatchBenchmark.benchControlCommands(1_000);
        var r3 = HostDispatchBenchmark.benchTimerChurn(50);
        var r4 = HostDispatchBenchmark.benchFxDispatch(200);

        for (var r : new HostDispatchBenchmark.HostBenchResult[]{r1, r2, r3, r4}) {
            assertTrue(r.meanMs() >= 0.0 && r.opsPerSec() > 0.0, r.toString());
        }
        assertEquals(1_000, r1.ops());
        assertEquals(1_000, r2.ops());
        assertEquals(50, r3.ops());
        assertEquals(200, r4.ops());
    }
}
