package org.example.jython3;

import org.example.jython3.benchmark.BenchmarkSuite;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

public class BenchmarkSmokeTest {

    @Test
    @DisplayName("Benchmark suite runs all workloads cleanly")
    void testBenchmarkSuiteRuns() {
        for (BenchmarkSuite.BenchmarkCase bench : BenchmarkSuite.getBenchmarks()) {
            // Run a 1-iteration smoke run to ensure all Python code in benchmarks is valid
            BenchmarkSuite.BenchmarkResult res = BenchmarkSuite.runBenchmark(bench, 1, 1);
            assertNotNull(res, "Result should not be null for " + bench.name());
            assertTrue(res.meanMs() > 0, "Duration should be positive for " + bench.name());
            assertFalse(res.formatRow().isEmpty());
        }
    }
}
