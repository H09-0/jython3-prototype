package org.example.jython3.logic;

import org.example.jython3.Jython3Interpreter;
import org.example.jython3.differential.DifferentialHarness;
import org.example.jython3.host.CapabilitySet;
import org.example.jython3.host.HostCapability;
import org.example.jython3.runtime.ExceptionTypeRegistry;
import org.example.jython3.security.AllowlistPolicy;
import org.example.jython3.security.ExecutionLimits;
import org.example.jython3.security.HostAccessPolicy;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Logic & conformance tests exercising every built-in and disk standard-library module:
 * math, sys, os, io, json, re, time, random, itertools, datetime, queue,
 * threading, concurrent.futures, collections, functools, importlib, and .sikuli bundles.
 */
@Tag("logic")
class StdlibModulesLogicTest {

    @Test
    @DisplayName("math, json, re, itertools, datetime, queue, collections, and functools match CPython")
    void pureAndDeterministicStdlibMatchesCPython() {
        String code = """
            import math, json, re, itertools, datetime, queue, collections, functools

            # math (all functions + constants)
            print(round(math.sqrt(16), 2), round(math.sin(math.pi / 2), 4), round(math.cos(0), 4),
                  round(math.tan(0), 4), round(math.atan2(1, 1), 4), round(math.hypot(3, 4), 2))
            print(round(math.degrees(math.pi), 2), round(math.radians(180), 4), round(math.exp(1), 4),
                  round(math.log(math.e), 4), round(math.log(8, 2), 2), math.log2(16), math.log10(1000))
            print(math.floor(3.9), math.ceil(3.1), math.abs(-5), math.pow(2, 5))
            print(math.isnan(math.nan), math.isinf(math.inf), math.isfinite(1.0), round(math.tau, 4))
            print(math.gcd(12, 18), math.lcm(4, 6), math.factorial(6), math.comb(5, 2), math.perm(5, 2))

            # json
            payload = {"b": [1, 2, {"c": True, "n": None}], "a": "hi\\n"}
            dumped = json.dumps(payload, sort_keys=True, indent=2)
            loaded = json.loads(dumped)
            print("json:", loaded["a"], loaded["b"][2]["c"])

            # re (compile, search, match, fullmatch, findall, sub, split, escape, Match methods)
            pat = re.compile(r"(\\w+)-(\\d+)", re.IGNORECASE)
            m = pat.search("id: ABC-42!")
            print("re_m:", m.group(0), m.group(1), m.group(2), m.groups(), m.start(1), m.end(1), m.span(2))
            print("re_ops:", re.match(r"\\d+", "12a").group(), re.fullmatch(r"\\d+", "123").group(),
                  re.findall(r"\\d+", "a1b22c3"), re.sub(r"\\d+", "#", "a1b22", 1), re.split(r"[,;]", "x,y;z"))
            print("re_esc:", re.search(re.escape("a+b"), "x a+b y").group())

            # itertools
            print("it1:", list(itertools.islice(itertools.count(10, 2), 4)))
            print("it2:", list(itertools.islice(itertools.cycle(["a", "b"]), 5)))
            print("it3:", list(itertools.repeat("x", 3)), list(itertools.chain([1, 2], [3])))
            print("it4:", list(itertools.takewhile(lambda x: x < 4, [1, 3, 5, 2])),
                  list(itertools.dropwhile(lambda x: x < 4, [1, 3, 5, 2])))
            print("it5:", list(itertools.compress("ABCD", [1, 0, 1, 0])),
                  list(itertools.accumulate([1, 2, 3, 4])))
            print("it6:", list(itertools.zip_longest([1, 2], ["a"], fillvalue=0)))
            print("it7:", list(itertools.product([1, 2], ["x"])),
                  list(itertools.permutations([1, 2], 2)), list(itertools.combinations([1, 2, 3], 2)))

            # datetime
            d = datetime.date(2026, 11, 15)
            tm = datetime.time(14, 30, 5)
            dt = datetime.datetime(2026, 11, 15, 14, 30, 5)
            td = datetime.timedelta(days=2, hours=3)
            dt2 = dt + td
            print("dt:", d.isoformat(), tm.isoformat(), dt2.year, dt2.day, dt2.hour, (dt2 - dt).days)

            # queue
            q = queue.Queue(maxsize=2); q.put(10); q.put(20)
            lq = queue.LifoQueue(); lq.put(1); lq.put(2)
            pq = queue.PriorityQueue(); pq.put((2, "b")); pq.put((1, "a"))
            print("q:", q.qsize(), q.full(), q.get(), q.get(), q.empty(), lq.get(), pq.get())

            # collections & functools
            od = collections.OrderedDict([("a", 1), ("b", 2)])
            od.move_to_end("a")
            Pt = collections.namedtuple("Pt", ["x", "y"])
            pt = Pt(3, 4)
            cnt = collections.Counter("abracadabra")
            dd = collections.defaultdict(list)
            dd["k"].append(9)
            dq = collections.deque([1, 2]); dq.appendleft(0); p_r = dq.pop()
            print("col:", list(od.keys()), pt.x + pt.y, cnt["a"], dd["k"], p_r)

            @functools.lru_cache(maxsize=16)
            def fib(n):
                return n if n < 2 else fib(n - 1) + fib(n - 2)
            def add(a, b): return a + b
            add5 = functools.partial(add, 5)
            print("fn:", fib(10), add5(7), functools.reduce(add, [1, 2, 3, 4]))
            print("cmp:", sorted(["pear", "fig", "apple"], key=functools.cmp_to_key(lambda a, b: len(a) - len(b))))
            """;
        DifferentialHarness.assertMatchesCPython(code);
    }

    @Test
    @DisplayName("sys, time, random, os, io, importlib.reload, and .sikuli bundles work end-to-end")
    void sysTimeRandomOsIoAndImportSystem(@TempDir Path tmp) throws Exception {
        Path modDir = tmp.resolve("mods");
        Path pkgDir = modDir.resolve("mypkg");
        Path sikuli = modDir.resolve("bundle.sikuli");
        Files.createDirectories(pkgDir);
        Files.createDirectories(sikuli);
        Files.writeString(pkgDir.resolve("__init__.py"), "PKG_VER = 1\n");
        Files.writeString(pkgDir.resolve("helper.py"), "def greet(): return 'v1'\n");
        Files.writeString(sikuli.resolve("bundle.py"), "SIKULI_OK = 'yes'\n");

        var outBuf = new ByteArrayOutputStream();
        var errBuf = new ByteArrayOutputStream();
        var caps = CapabilitySet.builder().allow(HostCapability.THREADS).maxThreads(8).build();
        var interp = new Jython3Interpreter(AllowlistPolicy.trusted(), HostAccessPolicy.PERMISSIVE,
                ExecutionLimits.TRUSTED, ExceptionTypeRegistry.standard(), caps);
        interp.setOut(new PrintStream(outBuf, true, StandardCharsets.UTF_8));
        interp.setErr(new PrintStream(errBuf, true, StandardCharsets.UTF_8));
        interp.addModulePath(modDir);

        Path dataFile = tmp.resolve("data.txt");
        interp.setGlobal("tmp_dir", new org.example.jython3.runtime.PyStr(tmp.toAbsolutePath().toString()));
        interp.setGlobal("data_file", new org.example.jython3.runtime.PyStr(dataFile.toAbsolutePath().toString()));
        interp.eval("""
            import sys, time, random, os, io, importlib
            import mypkg.helper as h
            import bundle

            # sys & stdout/stderr redirection
            print("hello-stdout")
            sys.stderr.write("hello-stderr\\n")
            assert len(sys.version) > 0 and sys.maxsize > 0

            # time & random
            t0 = time.monotonic()
            t_ns = time.time_ns()
            p0 = time.perf_counter()
            time.sleep(0.005)
            assert time.monotonic() >= t0 and time.perf_counter() >= p0 and t_ns > 0 and time.time() > 0
            random.seed(12345)
            r1 = random.random()
            ri = random.randint(1, 10**25)
            ch = random.choice("😀😃😄")
            assert 0.0 <= r1 < 1.0 and 1 <= ri <= 10**25 and ch in "😀😃😄"

            # os & io
            cwd = os.getcwd()
            pid = os.getpid()
            entries = os.listdir(tmp_dir)
            assert len(cwd) > 0 and pid > 0 and "mods" in entries
            with io.open(data_file, "w") as f:
                f.write("line1\\n")
            with io.open(data_file, "a") as f:
                f.write("line2")
            with io.open(data_file, "r") as f:
                read_back = f.read()
            assert read_back == "line1\\nline2"

            # .sikuli & importlib.reload
            assert bundle.SIKULI_OK == "yes" and h.greet() == "v1"
            """);

        Files.writeString(pkgDir.resolve("helper.py"), "def greet(): return 'v2'\n");
        interp.eval("importlib.reload(h)\nreloaded = h.greet()");
        assertEquals("v2", interp.getGlobal("reloaded").toString());
        assertTrue(outBuf.toString(StandardCharsets.UTF_8).contains("hello-stdout"));
        assertTrue(errBuf.toString(StandardCharsets.UTF_8).contains("hello-stderr"));
    }

    @Test
    @DisplayName("threading (Thread, Lock, RLock, Event, Semaphore) and concurrent.futures work with Virtual Threads")
    void threadingAndConcurrentFuturesLogic() {
        var caps = CapabilitySet.builder().allow(HostCapability.THREADS).maxThreads(8).maxThreadPools(2).build();
        try (var interp = new Jython3Interpreter(
                AllowlistPolicy.trusted(), HostAccessPolicy.DENY_ALL, ExecutionLimits.TRUSTED,
                ExceptionTypeRegistry.standard(), caps)) {
            interp.eval("""
                import threading, concurrent.futures as cf, time

                lock = threading.Lock()
                rlock = threading.RLock()
                ev = threading.Event()
                sem = threading.Semaphore(2)
                out = []

                def work(n):
                    with rlock:
                        with rlock:
                            with sem:
                                with lock:
                                    out.append(n)
                    ev.set()

                t = threading.Thread(target=work, args=(42,), name="W1")
                assert threading.current_thread().is_alive()
                t.start()
                t.join()
                assert ev.wait(1.0) and out == [42] and threading.active_count() >= 1

                with cf.ThreadPoolExecutor(max_workers=3) as ex:
                    def slow_sq(delay, x):
                        time.sleep(delay)
                        return x * x
                    f1 = ex.submit(slow_sq, 0.04, 2)
                    f2 = ex.submit(slow_sq, 0.005, 3)
                    completed = [f.result() for f in cf.as_completed([f1, f2])]
                    mapped = list(ex.map(lambda x: x + 1, [10, 20, 30]))
                """);
            assertEquals("[9, 4]", interp.getGlobal("completed").toString());
            assertEquals("[11, 21, 31]", interp.getGlobal("mapped").toString());
        }
    }

    private static String repr(Path p) {
        return "\"" + p.toAbsolutePath().toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }
}
