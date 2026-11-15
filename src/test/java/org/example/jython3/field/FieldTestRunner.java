package org.example.jython3.field;

import org.example.jython3.Jython3Interpreter;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.security.AllowlistPolicy;
import org.example.jython3.security.ExecutionLimits;
import org.example.jython3.security.HostAccessPolicy;

import java.time.Duration;
import java.util.List;

/**
 * Modular Field Test & Resource/Leak Profiler for Jython 3.
 * Runs all real-world {@link FieldScenario}s and executes unwind, long-running,
 * and interpreter-churn leak probes via {@link ResourceProfiler}.
 */
public final class FieldTestRunner {

    private static final List<FieldScenario> SCENARIOS = List.of(
            new AnalyticsScenario(),
            new OopScenario(),
            new RegexScenario(),
            new JavaBridgeScenario()
    );

    public static void main(String[] args) {
        System.out.println("=========================================================================");
        System.out.println("  JYTHON 3 — FIELD TEST & MEMORY / CPU / LEAK PROFILER");
        System.out.println("=========================================================================\n");

        for (FieldScenario scenario : SCENARIOS) {
            runScenario(scenario);
        }

        System.out.println("\n--- Stress Test A: Exception & Timeout Unwinding Integrity ---");
        testExceptionUnwindingIntegrity();

        System.out.println("\n--- Stress Test B: 600-Cycle Long-Running Interpreter Leak Probe ---");
        testLongRunningInterpreterLeak();

        System.out.println("\n--- Stress Test C: 300 Short-Lived Interpreters Lifecycle Leak Probe ---");
        testShortLivedInterpretersChurn();

        System.out.println("\n=========================================================================");
        System.out.println("  FIELD TEST PROFILING COMPLETE");
        System.out.println("=========================================================================");
    }

    private static void runScenario(FieldScenario scenario) {
        System.out.println(">>> " + scenario.name());
        var interp = Jython3Interpreter.trusted();
        long cpu0 = ResourceProfiler.processCpuNanos();
        long wall0 = System.nanoTime();
        try {
            interp.eval(scenario.pythonCode(), "<" + scenario.name() + ">");
            long wall1 = System.nanoTime();
            long cpu1 = ResourceProfiler.processCpuNanos();
            scenario.verify(interp);
            double wallMs = (wall1 - wall0) / 1_000_000.0;
            double cpuMs = (cpu0 >= 0 && cpu1 >= 0) ? (cpu1 - cpu0) / 1_000_000.0 : -1;
            System.out.printf("    PASS | Wall: %.2f ms | CPU: %.2f ms | Heap: %.2f MB | Threads: %d%n",
                    wallMs, cpuMs, ResourceProfiler.usedHeapMB(), ResourceProfiler.threadCount());
        } catch (Throwable t) {
            System.out.printf("    FAIL | %s: %s%n", t.getClass().getSimpleName(), t.getMessage());
            if (t instanceof InterpreterException ie) {
                System.err.println(interp.formatTraceback(ie, "<" + scenario.name() + ">", scenario.pythonCode()));
            }
        }
    }

    private static void testExceptionUnwindingIntegrity() {
        var interp = Jython3Interpreter.trusted();
        for (int i = 0; i < 200; i++) {
            try {
                interp.eval("""
                    def boom(n):
                        return boom(n + 1)
                    try:
                        boom(0)
                    finally:
                        cleanup_done = True
                    """);
            } catch (InterpreterException expected) {
                if (!expected.getMessage().contains("RecursionError")) {
                    throw new AssertionError("Unexpected error: " + expected.getMessage());
                }
            }
        }
        interp.eval("ok = 10 + 20");
        FieldScenario.assertTrue("Interpreter healthy after 200 RecursionErrors",
                "30".equals(interp.getGlobal("ok").toString()));

        var sandboxed = new Jython3Interpreter(
                AllowlistPolicy.trusted(),
                HostAccessPolicy.DENY_ALL,
                new ExecutionLimits(50_000, Duration.ofSeconds(1), 10_000, 100_000, 100, 100_000)
        );
        boolean timedOut = false;
        try {
            sandboxed.eval("while True:\n    pass\n");
        } catch (InterpreterException e) {
            timedOut = e.getMessage().contains("TimeoutError");
        }
        FieldScenario.assertTrue("Sandboxed infinite loop stopped cleanly by ExecutionLimits", timedOut);
        System.out.printf("    PASS | 200 deep unwinds + sandboxed budget test | Heap: %.2f MB%n",
                ResourceProfiler.usedHeapMB());
    }

    private static void testLongRunningInterpreterLeak() {
        var interp = Jython3Interpreter.trusted();
        for (int i = 0; i < 50; i++) {
            interp.resetExecutionState();
            interp.eval(SCENARIOS.get(0).pythonCode());
            interp.eval(SCENARIOS.get(1).pythonCode());
        }
        System.gc();
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        double heapStart = ResourceProfiler.usedHeapMB();
        double nonHeapStart = ResourceProfiler.usedNonHeapMB();
        long gcCountStart = ResourceProfiler.gcCount();
        long gcTimeStart = ResourceProfiler.gcTimeMs();
        long cpuStart = ResourceProfiler.processCpuNanos();
        long wallStart = System.nanoTime();
        int threadsStart = ResourceProfiler.threadCount();

        double peakHeap = heapStart;
        for (int i = 0; i < 600; i++) {
            interp.resetExecutionState();
            for (FieldScenario sc : SCENARIOS) {
                interp.eval(sc.pythonCode());
            }
            if (i % 100 == 0) {
                double h = ResourceProfiler.usedHeapMB();
                if (h > peakHeap) peakHeap = h;
                System.out.printf("    Cycle %3d/600 | Heap: %.2f MB | NonHeap: %.2f MB | CacheSize: %d%n",
                        i, h, ResourceProfiler.usedNonHeapMB(), interp.getCacheSize());
            }
        }

        long wallEnd = System.nanoTime();
        long cpuEnd = ResourceProfiler.processCpuNanos();

        System.gc();
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        double heapAfterGc = ResourceProfiler.usedHeapMB();
        double nonHeapAfterGc = ResourceProfiler.usedNonHeapMB();
        long gcCountDelta = ResourceProfiler.gcCount() - gcCountStart;
        long gcTimeDelta = ResourceProfiler.gcTimeMs() - gcTimeStart;
        int threadsEnd = ResourceProfiler.threadCount();

        System.out.printf("    RESULTS (600 full multi-project cycles = 2,400 script runs):%n");
        System.out.printf("      Wall Time   : %.2f ms (%.2f ms/cycle)%n", (wallEnd - wallStart) / 1e6, (wallEnd - wallStart) / 600e6);
        System.out.printf("      CPU Time    : %.2f ms%n", (cpuEnd - cpuStart) / 1e6);
        System.out.printf("      Heap Start  : %.2f MB -> Peak: %.2f MB -> After GC: %.2f MB (Delta: %+.2f MB)%n",
                heapStart, peakHeap, heapAfterGc, heapAfterGc - heapStart);
        System.out.printf("      NonHeap     : %.2f MB -> %.2f MB (Delta: %+.2f MB)%n",
                nonHeapStart, nonHeapAfterGc, nonHeapAfterGc - nonHeapStart);
        System.out.printf("      GC Activity : %d collections, %d ms total pause%n", gcCountDelta, gcTimeDelta);
        System.out.printf("      Threads     : %d -> %d%n", threadsStart, threadsEnd);
        System.out.printf("      BytecodeCache: %d entries%n", interp.getCacheSize());
    }

    private static void testShortLivedInterpretersChurn() {
        System.gc();
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        double heapStart = ResourceProfiler.usedHeapMB();
        long wallStart = System.nanoTime();

        for (int i = 0; i < 300; i++) {
            var tempInterp = Jython3Interpreter.trusted();
            tempInterp.eval("""
                class Box:
                    def __init__(self, v):
                        self.v = v
                    def get(self):
                        return self.v
                b = Box(42)
                d = {"key": b.get(), (1, 2): "tuple_key"}
                res = d["key"] + len(d)
                """);
            if (!"44".equals(tempInterp.getGlobal("res").toString())) {
                throw new AssertionError("Unexpected result in churn test");
            }
        }

        System.gc();
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}
        double heapEnd = ResourceProfiler.usedHeapMB();
        long wallEnd = System.nanoTime();

        System.out.printf("    RESULTS (300 short-lived interpreters created & discarded):%n");
        System.out.printf("      Wall Time   : %.2f ms%n", (wallEnd - wallStart) / 1e6);
        System.out.printf("      Heap Start  : %.2f MB -> After GC: %.2f MB (Delta: %+.2f MB)%n",
                heapStart, heapEnd, heapEnd - heapStart);
    }
}
