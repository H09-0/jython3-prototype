package org.example.jython3;

import org.example.jython3.api.EmbeddedRuntime;
import org.example.jython3.differential.DifferentialHarness;
import org.example.jython3.host.CapabilitySet;
import org.example.jython3.host.HostCapability;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.security.ExecutionLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for Track 3A: itertools, queue, datetime, math enhancements,
 * str methods + str.format(), list.sort(key/reverse), and dict | / |= / setdefault / popitem / fromkeys.
 */
class StdlibExpansionTest {

    @Test
    @DisplayName("itertools, math, str, list, and dict enhancements match CPython")
    void itertoolsMathStrListDictMatchCPython() {
        String code = """
            import itertools, math

            print(list(itertools.islice(itertools.count(10, 3), 5)))
            print(list(itertools.islice(itertools.cycle(["a", "b"]), 5)))
            print(list(itertools.repeat(7, 4)))
            print(list(itertools.chain([1, 2], (3, 4))))
            print(list(itertools.takewhile(lambda x: x < 5, [1, 3, 5, 2])))
            print(list(itertools.dropwhile(lambda x: x < 5, [1, 3, 5, 2])))
            print(list(itertools.accumulate([1, 2, 3, 4])))
            print(list(itertools.compress("ABCD", [1, 0, 1, 0])))
            print(list(itertools.zip_longest("AB", "1234", fillvalue="-")))
            print(list(itertools.product([1, 2], ["x", "y"])))
            print(list(itertools.permutations([1, 2, 3], 2)))
            print(list(itertools.combinations([1, 2, 3, 4], 2)))

            print(math.gcd(48, 18), math.lcm(12, 18), math.factorial(6), math.comb(6, 2), math.perm(5, 2))
            print(math.isnan(math.nan), math.isinf(math.inf), math.isfinite(3.5), math.log2(1024), math.log10(1000))

            s = "  hello world  "
            print(s.lstrip(), "|", s.rstrip(), "|", "unhappy".removeprefix("un"), "test.py".removesuffix(".py"))
            print("a-b-c".partition("-"), "a-b-c".rpartition("-"))
            print("hello world".title(), "hi".center(6, "*"), "42".zfill(5))
            print("abc.py".endswith((".txt", ".py")), "hello".startswith(("he", "lo")))
            print("a={}, b={name}, x={:.1f}".format(3.1415, 2.71, name="oculix"))
            print("a={0}, x={0:.1f}".format(3.1415))

            xs = ["bbb", "a", "cc"]
            xs.sort(key=len, reverse=True)
            print(xs)

            d1 = {"a": 1, "b": 2}
            d2 = {"b": 20, "c": 3}
            print(d1 | d2)
            d1 |= {"d": 4}
            print(d1.setdefault("a", 99), d1.setdefault("e", 55), d1.popitem())
            print(dict.fromkeys(["x", "y"], 0))
            """;
        DifferentialHarness.assertMatchesCPython(code);
    }

    @Test
    @DisplayName("datetime module supports date, time, datetime, timedelta arithmetic and strftime")
    void datetimeModuleMatchesCPython() {
        String code = """
            import datetime
            d = datetime.date(2026, 11, 15)
            td = datetime.timedelta(days=5, hours=3)
            d2 = d + td
            print(d.isoformat(), d2.isoformat(), (d2 - d).days, d < d2)
            dt = datetime.datetime(2026, 11, 15, 10, 30, 0)
            dt2 = dt + datetime.timedelta(minutes=45)
            print(dt2.strftime("%Y-%m-%d %H:%M:%S"))
            print(datetime.datetime.fromisoformat("2026-01-02T03:04:05").year)
            """;
        DifferentialHarness.assertMatchesCPython(code);
    }

    @Test
    @DisplayName("queue.Queue / LifoQueue / PriorityQueue coordinate Virtual Thread producers and consumers")
    void queueWithVirtualThreads() {
        var caps = CapabilitySet.builder().allow(HostCapability.THREADS).maxThreads(8).build();
        try (var rt = EmbeddedRuntime.builder().capabilities(caps).limits(ExecutionLimits.TRUSTED).build()) {
            rt.eval("""
                import queue, threading

                q = queue.Queue(maxsize=4)
                results = []

                def producer():
                    for i in range(10):
                        q.put(i)
                    q.put(None)

                def consumer():
                    while True:
                        item = q.get()
                        q.task_done()
                        if item is None:
                            break
                        results.append(item)

                t1 = threading.Thread(target=producer)
                t2 = threading.Thread(target=consumer)
                t1.start(); t2.start()
                t1.join(); t2.join()
                q.join()

                lq = queue.LifoQueue()
                lq.put(1); lq.put(2); lq.put(3)
                lifo_out = [lq.get_nowait(), lq.get_nowait(), lq.get_nowait()]

                pq = queue.PriorityQueue()
                pq.put(30); pq.put(10); pq.put(20)
                pq_out = [pq.get_nowait(), pq.get_nowait(), pq.get_nowait()]
                total = sum(results)
                """);
            assertEquals(PyInt.of(45), rt.getGlobal("total"));
            assertEquals("[3, 2, 1]", rt.getGlobal("lifo_out").toString());
            assertEquals("[10, 20, 30]", rt.getGlobal("pq_out").toString());
        }
    }

    @Test
    @DisplayName("itertools infinite streams & math.factorial enforce ExecutionLimits against DoS")
    void stdlibSecurityLimits() {
        var tight = new ExecutionLimits(20_000, Duration.ofMillis(200), 50_000, 500, 1_000, 100);
        try (var rt = EmbeddedRuntime.builder().limits(tight).build()) {
            InterpreterException ex = assertThrows(InterpreterException.class,
                    () -> rt.eval("import itertools\nlist(itertools.count())"));
            assertTrue("TimeoutError".equals(ex.typeName()) || "MemoryError".equals(ex.typeName()));

            assertEquals("MemoryError",
                    assertThrows(InterpreterException.class,
                            () -> rt.eval("import itertools\nlist(itertools.product(range(100), range(100)))")).typeName());

            assertEquals("ValueError",
                    assertThrows(InterpreterException.class,
                            () -> rt.eval("import math\nmath.factorial(50000)")).typeName());
        }
    }
}
