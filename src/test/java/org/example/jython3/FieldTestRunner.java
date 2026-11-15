package org.example.jython3;

import org.example.jython3.bridge.JavaBridge;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.security.AllowlistPolicy;
import org.example.jython3.security.ExecutionLimits;
import org.example.jython3.security.HostAccessPolicy;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.ThreadMXBean;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

/**
 * Comprehensive Field Test & Resource Profiler for Jython 3.
 * Executes realistic multi-feature Python projects, monitors RAM, CPU, GC, Threads,
 * and probes for software bugs and memory/resource leaks.
 */
public class FieldTestRunner {

    private static final MemoryMXBean MEMORY_BEAN = ManagementFactory.getMemoryMXBean();
    private static final ThreadMXBean THREAD_BEAN = ManagementFactory.getThreadMXBean();
    private static final List<GarbageCollectorMXBean> GC_BEANS = ManagementFactory.getGarbageCollectorMXBeans();

    private static long getProcessCpuNanos() {
        var osBean = ManagementFactory.getOperatingSystemMXBean();
        if (osBean instanceof com.sun.management.OperatingSystemMXBean sunOs) {
            return sunOs.getProcessCpuTime();
        }
        return -1;
    }

    private static long getGcCount() {
        long count = 0;
        for (var gc : GC_BEANS) {
            long c = gc.getCollectionCount();
            if (c > 0) count += c;
        }
        return count;
    }

    private static long getGcTimeMs() {
        long time = 0;
        for (var gc : GC_BEANS) {
            long t = gc.getCollectionTime();
            if (t > 0) time += t;
        }
        return time;
    }

    private static double usedHeapMB() {
        return MEMORY_BEAN.getHeapMemoryUsage().getUsed() / (1024.0 * 1024.0);
    }

    private static double usedNonHeapMB() {
        return MEMORY_BEAN.getNonHeapMemoryUsage().getUsed() / (1024.0 * 1024.0);
    }

    // ─── Project 1: E-Commerce / Analytics Data Pipeline ─────────────────
    private static final String PROJECT_ANALYTICS = """
        import json
        import math

        # Simulated order stream in JSON
        raw_orders = [
            '{"id": 101, "user": "Alice", "items": [{"price": 25.5, "qty": 2}, {"price": 10.0, "qty": 1}], "region": "EU"}',
            '{"id": 102, "user": "Bob", "items": [{"price": 120.0, "qty": 1}], "region": "US"}',
            '{"id": 103, "user": "Alice", "items": [{"price": 15.0, "qty": 3}, {"price": 5.5, "qty": 2}], "region": "EU"}',
            '{"id": 104, "user": "Charlie", "items": [{"price": 200.0, "qty": 2}], "region": "APAC"}',
            '{"id": 105, "user": "Bob", "items": [{"price": 45.0, "qty": 2}], "region": "US"}'
        ]

        def compute_order_total(order):
            return sum([item["price"] * item["qty"] for item in order["items"]])

        orders = [json.loads(s) for s in raw_orders]
        totals = [(o["id"], o["user"], o["region"], compute_order_total(o)) for o in orders]

        # Aggregate by user using closures and dicts
        user_spend = {}
        for entry in totals:
            oid, user, region, amt = entry
            user_spend[user] = user_spend.get(user, 0.0) + amt

        ranked = sorted(list(user_spend.items()), key=lambda pair: pair[1], reverse=True)
        top_user, top_spend = ranked[0]
        total_revenue = sum([t[3] for t in totals])
        avg_order = total_revenue / len(totals)
        report_json = json.dumps({"top_user": top_user, "top_spend": top_spend, "avg": avg_order})
        """;

    // ─── Project 2: OOP Plugin & State Machine with Generators & Context Managers ───
    private static final String PROJECT_OOP_ENGINE = """
        class ResourceLock:
            def __init__(self, name, log):
                self.name = name
                self.log = log
            def __enter__(self):
                self.log.append("enter:" + self.name)
                return self
            def __exit__(self, exc_type, exc_val, exc_tb):
                self.log.append("exit:" + self.name)
                return False

        class Task:
            def __init__(self, name):
                self.name = name
            def run(self):
                return "base:" + self.name

        class ComputeTask(Task):
            def __init__(self, name, factor):
                super().__init__(name)
                self.factor = factor
            def run(self):
                return super().run() + ":x" + str(self.factor)

        def make_pipeline(start):
            state = start
            def step(delta):
                nonlocal state
                state = state + delta
                return state
            return step

        def fibonacci_gen(limit):
            a, b = 0, 1
            for _ in range(limit):
                yield a
                a, b = b, a + b

        events = []
        with ResourceLock("db", events):
            t1 = Task("init")
            t2 = ComputeTask("train", 4)
            results = [t.run() for t in [t1, t2]]

        pipe = make_pipeline(10)
        p_vals = [pipe(5), pipe(3), pipe(-2)]
        fibs = list(fibonacci_gen(12))
        """;

    // ─── Project 3: Text Processing, Regex, Unicode & Simulation ─────────
    private static final String PROJECT_TEXT_REGEX = """
        import re
        import random
        import math

        log_lines = [
            "[2026-11-15] ERROR user=42 latency=120ms msg=timeout 🚀",
            "[2026-11-15] INFO  user=17 latency=15ms  msg=ok ✅",
            "[2026-11-15] WARN  user=99 latency=450ms msg=slow ⚠️",
            "[2026-11-15] ERROR user=88 latency=999ms msg=crash 💥"
        ]

        errors = []
        latencies = []
        for line in log_lines:
            nums = re.findall(r"\\d+", line)
            latencies.append(int(nums[-1]))
            if "ERROR" in line:
                errors.append(line)

        # Unicode / Emoji slicing check
        emoji_str = "🔥🚀✨Jython3🎯"
        emoji_len = len(emoji_str)
        emoji_rev = emoji_str[::-1]
        emoji_mid = emoji_str[3:10]

        # Monte Carlo Pi approximation (deterministic seed)
        random.seed(42)
        inside = 0
        samples = 5000
        for _ in range(samples):
            x = random.random()
            y = random.random()
            if x * x + y * y <= 1.0:
                inside += 1
        pi_est = 4.0 * inside / samples
        """;

    // ─── Project 4: Java Bridge Automation App ───────────────────────────
    private static final String PROJECT_JAVA_BRIDGE = """
        from java.util import ArrayList, HashMap
        from java.lang import StringBuilder, Math

        cache = HashMap()
        items = ArrayList()
        sb = StringBuilder()

        for i in range(500):
            key = "k_" + str(i)
            val = Math.sqrt(float(i * i + 1))
            cache.put(key, val)
            items.add(key)
            if i < 5:
                sb.append(key).append(";")

        count = 0
        for item in items:
            if cache[item] >= 0.0:
                count += 1
        summary = sb.toString()
        """;

    public static void main(String[] args) {
        System.out.println("=========================================================================");
        System.out.println("  JYTHON 3 — FIELD TEST & MEMORY / CPU / LEAK PROFILER");
        System.out.println("=========================================================================\n");

        // 1. Run Real-World Projects and verify outputs
        runFieldProject("Project 1: Analytics & JSON Pipeline", PROJECT_ANALYTICS, interp -> {
            assertCond("top_user == 'Charlie'", "Charlie".equals(interp.getGlobal("top_user").toString()));
            assertCond("top_spend == 400.0", Math.abs(Double.parseDouble(interp.getGlobal("top_spend").toString()) - 400.0) < 1e-6);
            assertCond("avg_order == 145.4", Math.abs(Double.parseDouble(interp.getGlobal("avg_order").toString()) - 145.4) < 1e-6);
        });

        runFieldProject("Project 2: OOP, Context Managers, Generators, Nonlocal", PROJECT_OOP_ENGINE, interp -> {
            assertCond("events == ['enter:db', 'exit:db']", "['enter:db', 'exit:db']".equals(interp.getGlobal("events").toString()));
            assertCond("results == ['base:init', 'base:train:x4']", "['base:init', 'base:train:x4']".equals(interp.getGlobal("results").toString()));
            assertCond("p_vals == [15, 18, 16]", "[15, 18, 16]".equals(interp.getGlobal("p_vals").toString()));
            assertCond("fibs == [0, 1, 1, 2, 3, 5, 8, 13, 21, 34, 55, 89]",
                    "[0, 1, 1, 2, 3, 5, 8, 13, 21, 34, 55, 89]".equals(interp.getGlobal("fibs").toString()));
        });

        runFieldProject("Project 3: Regex, Unicode/Emoji, Monte Carlo", PROJECT_TEXT_REGEX, interp -> {
            assertCond("len(errors) == 2", "2".equals(String.valueOf(((org.example.jython3.runtime.PyList) interp.getGlobal("errors")).items().size())));
            assertCond("emoji_len == 11", "11".equals(interp.getGlobal("emoji_len").toString()));
            assertCond("emoji_mid == 'Jython3'", "Jython3".equals(interp.getGlobal("emoji_mid").toString()));
            double pi = Double.parseDouble(interp.getGlobal("pi_est").toString());
            assertCond("pi_est ~ 3.14 (got " + pi + ")", pi > 3.0 && pi < 3.3);
        });

        runFieldProject("Project 4: Java Bridge Automation App", PROJECT_JAVA_BRIDGE, interp -> {
            assertCond("count == 500", "500".equals(interp.getGlobal("count").toString()));
            assertCond("summary prefix", interp.getGlobal("summary").toString().startsWith("k_0;k_1;"));
        });

        // 2. Test Exception & Limit Unwinding Stress
        System.out.println("\n--- Stress Test A: Exception & Timeout Unwinding Integrity ---");
        testExceptionUnwindingIntegrity();

        // 3. Long-running Single Interpreter Memory Leak & CPU Probe
        System.out.println("\n--- Stress Test B: 600-Cycle Long-Running Interpreter Leak Probe ---");
        testLongRunningInterpreterLeak();

        // 4. Short-lived Multi-Interpreter Churn Leak Probe
        System.out.println("\n--- Stress Test C: 300 Short-Lived Interpreters Lifecycle Leak Probe ---");
        testShortLivedInterpretersChurn();

        System.out.println("\n=========================================================================");
        System.out.println("  FIELD TEST PROFILING COMPLETE");
        System.out.println("=========================================================================");
    }

    @FunctionalInterface
    interface Verifier {
        void verify(Jython3Interpreter interp);
    }

    private static void runFieldProject(String title, String code, Verifier verifier) {
        System.out.println(">>> " + title);
        var interp = Jython3Interpreter.trusted();
        long cpu0 = getProcessCpuNanos();
        long wall0 = System.nanoTime();
        try {
            interp.eval(code, "<" + title + ">");
            long wall1 = System.nanoTime();
            long cpu1 = getProcessCpuNanos();
            verifier.verify(interp);
            double wallMs = (wall1 - wall0) / 1_000_000.0;
            double cpuMs = (cpu0 >= 0 && cpu1 >= 0) ? (cpu1 - cpu0) / 1_000_000.0 : -1;
            System.out.printf("    PASS | Wall: %.2f ms | CPU: %.2f ms | Heap: %.2f MB | Threads: %d%n",
                    wallMs, cpuMs, usedHeapMB(), THREAD_BEAN.getThreadCount());
        } catch (Throwable t) {
            System.out.printf("    FAIL | %s: %s%n", t.getClass().getSimpleName(), t.getMessage());
            if (t instanceof InterpreterException ie) {
                System.err.println(interp.formatTraceback(ie, "<" + title + ">", code));
            }
        }
    }

    private static void testExceptionUnwindingIntegrity() {
        var interp = Jython3Interpreter.trusted();
        // Run 200 scripts that trigger deep recursion errors and nested try/finally exceptions
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
        // Verify interpreter is still completely healthy after 200 unwinds
        interp.eval("ok = 10 + 20");
        assertCond("Interpreter healthy after 200 RecursionErrors", "30".equals(interp.getGlobal("ok").toString()));

        // Test sandboxed timeout & instruction budget enforcement
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
        assertCond("Sandboxed infinite loop stopped cleanly by ExecutionLimits", timedOut);
        System.out.printf("    PASS | 200 deep unwinds + sandboxed budget test | Heap: %.2f MB%n", usedHeapMB());
    }

    private static void testLongRunningInterpreterLeak() {
        var interp = Jython3Interpreter.trusted();
        // Warmup
        for (int i = 0; i < 50; i++) {
            interp.resetExecutionState();
            interp.eval(PROJECT_ANALYTICS);
            interp.eval(PROJECT_OOP_ENGINE);
        }
        System.gc();
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        double heapStart = usedHeapMB();
        double nonHeapStart = usedNonHeapMB();
        long gcCountStart = getGcCount();
        long gcTimeStart = getGcTimeMs();
        long cpuStart = getProcessCpuNanos();
        long wallStart = System.nanoTime();
        int threadsStart = THREAD_BEAN.getThreadCount();

        double peakHeap = heapStart;
        for (int i = 0; i < 600; i++) {
            interp.resetExecutionState();
            interp.eval(PROJECT_ANALYTICS);
            interp.eval(PROJECT_OOP_ENGINE);
            interp.eval(PROJECT_TEXT_REGEX);
            interp.eval(PROJECT_JAVA_BRIDGE);
            if (i % 100 == 0) {
                double h = usedHeapMB();
                if (h > peakHeap) peakHeap = h;
                System.out.printf("    Cycle %3d/600 | Heap: %.2f MB | NonHeap: %.2f MB | CacheSize: %d%n",
                        i, h, usedNonHeapMB(), interp.getCacheSize());
            }
        }

        long wallEnd = System.nanoTime();
        long cpuEnd = getProcessCpuNanos();

        System.gc();
        try { Thread.sleep(100); } catch (InterruptedException ignored) {}

        double heapAfterGc = usedHeapMB();
        double nonHeapAfterGc = usedNonHeapMB();
        long gcCountDelta = getGcCount() - gcCountStart;
        long gcTimeDelta = getGcTimeMs() - gcTimeStart;
        int threadsEnd = THREAD_BEAN.getThreadCount();

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
        double heapStart = usedHeapMB();
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
        double heapEnd = usedHeapMB();
        long wallEnd = System.nanoTime();

        System.out.printf("    RESULTS (300 short-lived interpreters created & discarded):%n");
        System.out.printf("      Wall Time   : %.2f ms%n", (wallEnd - wallStart) / 1e6);
        System.out.printf("      Heap Start  : %.2f MB -> After GC: %.2f MB (Delta: %+.2f MB)%n",
                heapStart, heapEnd, heapEnd - heapStart);
    }

    private static void assertCond(String msg, boolean cond) {
        if (!cond) {
            throw new AssertionError("Field test assertion failed: " + msg);
        }
    }
}
