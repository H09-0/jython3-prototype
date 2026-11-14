package org.example.jython3.benchmark;

import org.example.jython3.Jython3Interpreter;
import org.example.jython3.runtime.PyObject;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reproducible performance benchmark suite for Jython 3.
 *
 * <p>Measures execution performance across core interpreter workloads:
 * <ol>
 *   <li>Arithmetic tight loop (LOAD_FAST, STORE_FAST, ADD, FOR_ITER)
 *   <li>Function call overhead (recursive calls, frame allocation, argument binding)
 *   <li>Object creation and attribute access (classes, instances, method dispatch)
 *   <li>Collection operations (list append, dict put/get, iteration)
 *   <li>String processing (concatenation, slicing, search)
 *   <li>Java Bridge interop (reflection method invocation, object wrapping)
 * </ol>
 */
public class BenchmarkSuite {

    public record BenchmarkCase(
            String name,
            String description,
            String pythonCode,
            int defaultWarmupIterations,
            int defaultMeasureIterations
    ) {}

    public record BenchmarkResult(
            String name,
            String description,
            int iterations,
            double compileMs,
            double meanMs,
            double minMs,
            double maxMs,
            double stdDevMs,
            long memoryUsedBytes
    ) {
        public String formatRow() {
            return String.format("%-22s | %7.2f ms | %7.2f ms | %7.2f ms | %7.2f ms | %7.2f ms | %6.1f MB",
                    name, compileMs, meanMs, minMs, maxMs, stdDevMs, memoryUsedBytes / (1024.0 * 1024.0));
        }
    }

    private static final List<BenchmarkCase> BENCHMARKS = new ArrayList<>();

    static {
        // 1. Arithmetic tight loop: 200,000 additions
        BENCHMARKS.add(new BenchmarkCase(
                "arithmetic_loop",
                "200,000 iterations integer summation",
                """
                total = 0
                for i in range(200000):
                    total = total + i
                """,
                3, 5
        ));

        // 2. Function calls: recursive Fibonacci fib(24)
        BENCHMARKS.add(new BenchmarkCase(
                "function_fibonacci",
                "Recursive Fibonacci fib(24) function calls",
                """
                def fib(n):
                    if n <= 1:
                        return n
                    return fib(n - 1) + fib(n - 2)
                res = fib(24)
                """,
                2, 4
        ));

        // 3. Function call overhead: 50,000 calls with args
        BENCHMARKS.add(new BenchmarkCase(
                "function_call_loop",
                "50,000 function invocations with 2 arguments",
                """
                def add(a, b):
                    return a + b
                total = 0
                for i in range(50000):
                    total = add(total, 1)
                """,
                3, 5
        ));

        // 4. Object creation & method dispatch: 20,000 class instances
        BENCHMARKS.add(new BenchmarkCase(
                "oop_instantiation",
                "20,000 class instances with method calls",
                """
                class Point:
                    def __init__(self, x, y):
                        self.x = x
                        self.y = y
                    def dist_sq(self):
                        return self.x * self.x + self.y * self.y

                total = 0
                for i in range(20000):
                    p = Point(i, i + 1)
                    total = total + p.dist_sq()
                """,
                3, 5
        ));

        // 5. List operations: 50,000 appends and indexing
        BENCHMARKS.add(new BenchmarkCase(
                "list_operations",
                "50,000 list appends and lookups",
                """
                lst = []
                for i in range(50000):
                    lst.append(i)
                total = 0
                for i in range(50000):
                    total = total + lst[i]
                """,
                3, 5
        ));

        // 6. Dict operations: 20,000 insertions and lookups
        BENCHMARKS.add(new BenchmarkCase(
                "dict_operations",
                "20,000 dict insertions and lookups",
                """
                d = {}
                for i in range(20000):
                    d[str(i)] = i
                total = 0
                for i in range(20000):
                    total = total + d[str(i)]
                """,
                3, 5
        ));

        // 7. String operations: 20,000 concatenations and slicing
        BENCHMARKS.add(new BenchmarkCase(
                "string_manipulation",
                "20,000 string slicing and method calls",
                """
                s = "abcdefghijklmnopqrstuvwxyz"
                total = 0
                for i in range(20000):
                    sub = s[5:15]
                    up = sub.upper()
                    total = total + len(up)
                """,
                3, 5
        ));

        // 8. Java Bridge interop: 20,000 static and instance Java calls
        BENCHMARKS.add(new BenchmarkCase(
                "java_bridge_calls",
                "20,000 Java Math.sqrt and ArrayList invocations",
                """
                from java.lang import Math
                from java.util import ArrayList
                list = ArrayList()
                total = 0.0
                for i in range(20000):
                    r = Math.sqrt(float(i))
                    list.add(r)
                    total = total + r
                """,
                3, 5
        ));
    }

    public static List<BenchmarkCase> getBenchmarks() {
        return Collections.unmodifiableList(BENCHMARKS);
    }

    public static BenchmarkResult runBenchmark(BenchmarkCase bench, int warmup, int measure) {
        Jython3Interpreter interpreter = Jython3Interpreter.trusted();

        // 1. Measure pure compilation time separately
        long compStart = System.nanoTime();
        var code = interpreter.compileToCode(bench.pythonCode());
        double compileMs = (System.nanoTime() - compStart) / 1_000_000.0;

        // 2. Warmup cycles on the same interpreter using precompiled bytecode
        for (int w = 0; w < warmup; w++) {
            interpreter.resetExecutionState();
            interpreter.evalCode(code, "<benchmark-warmup>");
        }

        // Force GC before measurements
        System.gc();
        try { Thread.sleep(50); } catch (InterruptedException ignored) {}

        long memoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        List<Double> durationsMs = new ArrayList<>();

        // 3. Measurement cycles: pure VM execution of precompiled bytecode
        for (int m = 0; m < measure; m++) {
            interpreter.resetExecutionState();
            long start = System.nanoTime();
            interpreter.evalCode(code, "<benchmark>");
            long elapsed = System.nanoTime() - start;
            durationsMs.add(elapsed / 1_000_000.0);
        }

        long memoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
        long memUsed = Math.max(0, memoryAfter - memoryBefore);

        double sum = 0;
        double min = Double.MAX_VALUE;
        double max = 0;
        for (double d : durationsMs) {
            sum += d;
            if (d < min) min = d;
            if (d > max) max = d;
        }
        double mean = sum / durationsMs.size();

        double varSum = 0;
        for (double d : durationsMs) {
            varSum += (d - mean) * (d - mean);
        }
        double stdDev = Math.sqrt(varSum / durationsMs.size());

        return new BenchmarkResult(bench.name(), bench.description(), measure, compileMs, mean, min, max, stdDev, memUsed);
    }

    public static Map<String, BenchmarkResult> runAll(int warmupOverride, int measureOverride) {
        Map<String, BenchmarkResult> results = new LinkedHashMap<>();
        for (BenchmarkCase bench : BENCHMARKS) {
            int warmup = warmupOverride > 0 ? warmupOverride : bench.defaultWarmupIterations();
            int measure = measureOverride > 0 ? measureOverride : bench.defaultMeasureIterations();
            BenchmarkResult result = runBenchmark(bench, warmup, measure);
            results.put(bench.name(), result);
        }
        return results;
    }

    public static void printReport(Map<String, BenchmarkResult> results) {
        String line = "=".repeat(92);
        String subline = "-".repeat(92);
        System.out.println(line);
        System.out.println(" JYTHON 3 PERFORMANCE BENCHMARK REPORT");
        System.out.println(line);
        System.out.printf("%-22s | %10s | %10s | %10s | %10s | %10s | %9s%n",
                "Benchmark", "Compile", "Mean Exec", "Min Exec", "Max Exec", "Std Dev", "Memory");
        System.out.println(subline);
        for (BenchmarkResult res : results.values()) {
            System.out.println(res.formatRow());
        }
        System.out.println(line);
    }

    public static void main(String[] args) {
        int warmup = 0;
        int measure = 0;
        String filter = null;

        for (int i = 0; i < args.length; i++) {
            if ("--warmup".equals(args[i]) && i + 1 < args.length) {
                warmup = Integer.parseInt(args[++i]);
            } else if ("--measure".equals(args[i]) && i + 1 < args.length) {
                measure = Integer.parseInt(args[++i]);
            } else if ("--filter".equals(args[i]) && i + 1 < args.length) {
                filter = args[++i];
            }
        }

        System.out.println("Starting Jython 3 Benchmark Suite...");
        Map<String, BenchmarkResult> results = new LinkedHashMap<>();
        for (BenchmarkCase bench : BENCHMARKS) {
            if (filter != null && !bench.name().contains(filter)) {
                continue;
            }
            System.out.print("Running " + bench.name() + " (" + bench.description() + ")... ");
            System.out.flush();
            int w = warmup > 0 ? warmup : bench.defaultWarmupIterations();
            int m = measure > 0 ? measure : bench.defaultMeasureIterations();
            BenchmarkResult res = runBenchmark(bench, w, m);
            results.put(bench.name(), res);
            System.out.printf("Done (%.2f ms)%n", res.meanMs());
        }
        System.out.println();
        printReport(results);
    }
}
