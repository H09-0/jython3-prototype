# Jython 3 — Large-Scale & Complex Field Soak Test Results

> **Target Commit Tested**: `5810a89` (`feat(runtime): support yield from and generator send/throw/close`)
> **Test Duration**: **1,800 seconds (30 minutes uninterrupted)** (`2026-11-15T11:16:14Z` → `2026-11-15T11:46:14Z`)
> **JVM Configuration**: OpenJDK 21 (`-Xms64m -Xmx512m`)
> **Related Reports**: [FIELD_TEST_REPORT.md](FIELD_TEST_REPORT.md) | [STATUS.md](STATUS.md)

---

## 1. Executive Summary

| Metric | Measured Result |
| :--- | :--- |
| **Total Continuous Duration** | **1,800 s (30 minutes uninterrupted)** |
| **Completed Multi-Scenario Cycles** | **901,230 cycles** |
| **Total Python Script Executions** | **6,308,610 scripts** (~3,505 scripts/sec sustained) |
| **Failures / Uncaught Exceptions** | **0** |
| **Heap Memory (Start → Peak → Post-GC)** | `4.94 MB` → **`65.34 MB`** (flat from minute 1 to minute 30) → **`6.39 MB`** (`+1.46 MB` incl. warmed caches) |
| **Non-Heap / Metaspace (Start → Post-GC)** | `11.05 MB` → `20.69 MB` (flat after JIT warmup) |
| **Active Thread Count (Start → End)** | `1` → `1` (zero thread leaks) |
| **`BytecodeCache` Entries** | `10` (constant across all 30 minutes) |
| **`ThreadLocal` Leak Checks (`PyCaller`, `ExceptionTypeRegistry`)** | **0 leaks** (verified after every cycle) |

---

## 2. Exercised Scenarios & Functions (Every Cycle)

Each cycle reset a long-lived `Jython3Interpreter` (`resetExecutionState()`), ran 6 complex end-to-end Python scenarios, and every 5th cycle created a fresh `SandboxedPolicy` + `HostAccessPolicy.DENY_ALL` + `ExecutionLimits` interpreter with positive and negative security/budget checks:

1. **Scenario 1 — Analytics, JSON, Collections, Builtins & Bitwise Pipeline**:
   - `json.loads` / `json.dumps` with `BigInt` (`10**30`) and circular-reference `ValueError` detection.
   - Pure-Python `collections` (`Counter`, `defaultdict`, `deque`) and `functools` (`reduce`, `partial`, `lru_cache`).
   - Built-ins sweep: `len`, `str`, `int(base=16)`, `float`, `bool`, `abs`, `min`, `max`, `sum(start=)`, `sorted(key=)`, `reversed`, `enumerate(start=)`, `zip`, `map`, `filter`, `all`, `any`, `callable`, `repr`, `chr`, `ord`, `hex`, `oct`, `bin`, `round`, `id`, `dir`, `vars`.
   - Bitwise operators (`&`, `|`, `^`, `~`, `<<`, `>>`, `|=`, `>>=`), `0x`/`0b`/`0o` literals, chained comparisons (`1 < 5 < 10`, `0 == 0 == 0`), `not` precedence, `math` (`floor`, `ceil`, `sqrt`, `sin`, `cos`, `log`, `pow`), `random.randint`, `time.time`.
   - `str`, `list`, and `dict` methods (`upper`, `lower`, `strip`, `replace`, `split`, `join`, `find`, `count`, `isdigit`, `isalpha`, `isalnum`, `append`, `extend`, `insert`, `remove`, `sort`, `reverse`, `index`, `pop`, `copy`, `clear`, `update`, `get`, `items`), slicing (`[1:5:2]`), raw strings, and `del`.
2. **Scenario 2 — `dict`, `set`, `frozenset`, Hash Equivalence & True In-Place Operations**:
   - Numeric key equivalence (`1 == 1.0 == True`, `hash(1) == hash(1.0) == hash(True)`, nested tuple keys).
   - `frozenset` creation, immutability, equality (`frozenset == set`), dict keys & set elements.
   - Set operators (`|`, `&`, `-`, `^`), partial-order comparisons (`<`, `<=`, `>=`), set methods (`add`, `discard`, `update`, `remove`, `pop`, `clear`, `issubset`, `issuperset`, `isdisjoint`, `symmetric_difference`).
   - True in-place `list +=`, `list *=`, and `set |=`, `&=`, `^=`, `-=` preserving object identity across aliases (`s is s_alias`).
3. **Scenario 3 — Lazy Generators, Coroutines & PEP 380 `yield from`**:
   - Infinite generators, lazy `zip`/`all`/`any`, nested generator expressions.
   - Coroutine `.send(val)`, `.throw(exc)`, `.close()` (`GeneratorExit`), and `yield from` delegation capturing `return` values and propagating exceptions/`close()` through `with` and `try/finally`.
4. **Scenario 4 — OOP, Control Flow, `with`/`try/finally`, Type Identity & Tracebacks**:
   - Multiple-level inheritance, `super()`, `type()` / `__class__` / `__name__`, `isinstance(True, int)`, `issubclass(bool, int)`, `hasattr`/`getattr`/`setattr`/`delattr`.
   - Multi-item `with A, B:` + nested `try/finally` + `continue`/`return` + `nonlocal` closures.
   - `raise ... from ...`, `__traceback__` (`tb_frame.f_code.co_name`), `__cause__`, `__context__`, `__suppress_context__`.
5. **Scenario 5 — Stdlib (`re`, `os`, `io`, `sys`), `ModuleLoader`, Decorators & Unpacking**:
   - Dotted package imports (`import soakpkg.sub as submod`, `from soakpkg.sub import *`, `sys.modules`), `re.findall`/`search`, Unicode/Emoji indexing & slicing, f-strings, `%` formatting, `io.open`, `os.listdir`/`getcwd`/`getpid`, `sys.version`/`platform`.
   - Decorators, `*args`/`**kwargs`, call-site `*args`/`**kwargs` unpacking, chained assignment, `del attr`.
6. **Scenario 6 — `JavaBridge`, Callbacks, Exceptions & Sandbox Negative Checks**:
   - `StringBuilder` fluent chaining, `Arrays.asList` (`Arrays$ArrayList` public interface resolution), `Collections.sort` with Python lambda `Comparator`, functional-interface callbacks (`IntUnaryOperator`), varargs, primitive array coercion.
   - Python exception unwinding through Java callbacks and Java exception unwinding through `try/finally`.
   - Every 5 cycles: fresh `SandboxedPolicy` + `HostAccessPolicy.DENY_ALL` + `ExecutionLimits` verifying `SecurityError` (`java.io.File`, disk module imports), `TimeoutError` (`while True: pass`), and `RecursionError`.

---

## 3. Minute-by-Minute Telemetry Log (Verbatim)

```text
[2026-11-15T11:16:14.459905759Z] SOAK START | target=1800s | heapStart=4.94 MB | nonHeapStart=11.05 MB | threads=1
[2026-11-15T11:17:14.454387710Z] t=  60s | cycles= 28151 | scripts= 197056 | heap=15.26 MB (peak=65.27) | nonHeap=26.91 MB | gc=526 | threads=1 | cache=10
[2026-11-15T11:18:14.455462178Z] t= 120s | cycles= 57265 | scripts= 400855 | heap=21.25 MB (peak=65.29) | nonHeap=27.17 MB | gc=1058 | threads=1 | cache=10
[2026-11-15T11:19:14.455923044Z] t= 180s | cycles= 87091 | scripts= 609636 | heap=25.27 MB (peak=65.29) | nonHeap=27.43 MB | gc=1603 | threads=1 | cache=10
[2026-11-15T11:20:14.455893572Z] t= 240s | cycles=117824 | scripts= 824764 | heap=62.26 MB (peak=65.29) | nonHeap=27.50 MB | gc=2164 | threads=1 | cache=10
[2026-11-15T11:21:14.460671777Z] t= 300s | cycles=147505 | scripts=1032535 | heap=31.27 MB (peak=65.29) | nonHeap=27.50 MB | gc=2707 | threads=1 | cache=10
[2026-11-15T11:22:14.460875933Z] t= 360s | cycles=177516 | scripts=1242611 | heap=57.29 MB (peak=65.30) | nonHeap=27.51 MB | gc=3255 | threads=1 | cache=10
[2026-11-15T11:23:14.462971215Z] t= 420s | cycles=207465 | scripts=1452255 | heap=18.28 MB (peak=65.30) | nonHeap=27.51 MB | gc=3803 | threads=1 | cache=10
[2026-11-15T11:24:14.465513241Z] t= 480s | cycles=236970 | scripts=1658790 | heap=30.27 MB (peak=65.30) | nonHeap=27.52 MB | gc=4342 | threads=1 | cache=10
[2026-11-15T11:25:14.466215910Z] t= 540s | cycles=266749 | scripts=1867239 | heap=40.40 MB (peak=65.30) | nonHeap=27.52 MB | gc=4886 | threads=1 | cache=10
[2026-11-15T11:26:14.469104073Z] t= 600s | cycles=296930 | scripts=2078510 | heap=17.38 MB (peak=65.30) | nonHeap=27.53 MB | gc=5438 | threads=1 | cache=10
[2026-11-15T11:27:14.471789354Z] t= 660s | cycles=326950 | scripts=2288650 | heap=54.18 MB (peak=65.30) | nonHeap=27.54 MB | gc=5986 | threads=1 | cache=10
[2026-11-15T11:28:14.472633931Z] t= 720s | cycles=356819 | scripts=2497729 | heap=42.29 MB (peak=65.30) | nonHeap=27.56 MB | gc=6532 | threads=1 | cache=10
[2026-11-15T11:29:14.476771766Z] t= 780s | cycles=386925 | scripts=2708475 | heap=57.28 MB (peak=65.31) | nonHeap=27.56 MB | gc=7082 | threads=1 | cache=10
[2026-11-15T11:30:14.479813317Z] t= 840s | cycles=416940 | scripts=2918580 | heap=29.28 MB (peak=65.31) | nonHeap=27.57 MB | gc=7631 | threads=1 | cache=10
[2026-11-15T11:31:14.480540793Z] t= 900s | cycles=446043 | scripts=3122298 | heap=18.28 MB (peak=65.31) | nonHeap=27.57 MB | gc=8163 | threads=1 | cache=10
[2026-11-15T11:32:14.482952195Z] t= 960s | cycles=475520 | scripts=3328640 | heap=62.29 MB (peak=65.32) | nonHeap=27.58 MB | gc=8701 | threads=1 | cache=10
[2026-11-15T11:33:14.483810935Z] t=1020s | cycles=505877 | scripts=3541137 | heap=47.30 MB (peak=65.32) | nonHeap=27.58 MB | gc=9256 | threads=1 | cache=10
[2026-11-15T11:34:14.483854726Z] t=1080s | cycles=535913 | scripts=3751388 | heap=41.31 MB (peak=65.32) | nonHeap=27.66 MB | gc=9805 | threads=1 | cache=10
[2026-11-15T11:35:14.486628373Z] t=1140s | cycles=566300 | scripts=3964100 | heap=63.30 MB (peak=65.32) | nonHeap=27.67 MB | gc=10360 | threads=1 | cache=10
[2026-11-15T11:36:14.487111968Z] t=1200s | cycles=597269 | scripts=4180879 | heap=58.30 MB (peak=65.32) | nonHeap=27.69 MB | gc=10926 | threads=1 | cache=10
[2026-11-15T11:37:14.490416296Z] t=1260s | cycles=627505 | scripts=4392535 | heap=35.30 MB (peak=65.33) | nonHeap=27.70 MB | gc=11479 | threads=1 | cache=10
[2026-11-15T11:38:14.490988227Z] t=1320s | cycles=657917 | scripts=4605417 | heap=21.31 MB (peak=65.33) | nonHeap=27.71 MB | gc=12035 | threads=1 | cache=10
[2026-11-15T11:39:14.492019461Z] t=1380s | cycles=688258 | scripts=4817803 | heap=49.31 MB (peak=65.33) | nonHeap=27.72 MB | gc=12589 | threads=1 | cache=10
[2026-11-15T11:40:14.492795623Z] t=1440s | cycles=718547 | scripts=5029827 | heap=22.21 MB (peak=65.33) | nonHeap=27.76 MB | gc=13143 | threads=1 | cache=10
[2026-11-15T11:41:14.492977150Z] t=1500s | cycles=747681 | scripts=5233766 | heap=48.31 MB (peak=65.33) | nonHeap=27.78 MB | gc=13675 | threads=1 | cache=10
[2026-11-15T11:42:14.493651288Z] t=1560s | cycles=777144 | scripts=5440004 | heap=13.18 MB (peak=65.33) | nonHeap=27.78 MB | gc=14214 | threads=1 | cache=10
[2026-11-15T11:43:14.494481605Z] t=1620s | cycles=806760 | scripts=5647320 | heap=30.30 MB (peak=65.33) | nonHeap=27.80 MB | gc=14755 | threads=1 | cache=10
[2026-11-15T11:44:14.495430668Z] t=1680s | cycles=837866 | scripts=5865061 | heap=57.31 MB (peak=65.34) | nonHeap=27.80 MB | gc=15323 | threads=1 | cache=10
[2026-11-15T11:45:14.498567423Z] t=1740s | cycles=868240 | scripts=6077680 | heap=63.31 MB (peak=65.34) | nonHeap=27.83 MB | gc=15878 | threads=1 | cache=10
[2026-11-15T11:46:14.678993419Z] SOAK COMPLETE | elapsed=1800s | cycles=901230 | scripts=6308610 | heapStart=4.94 MB -> peak=65.34 MB -> afterGC=6.39 MB (delta=1.46 MB) | nonHeap=11.05 -> 20.69 MB | threads=1
```

---

## 4. Key Takeaways

- **Zero Heap Growth Over 30 Minutes**: Peak heap remained bounded at `65.27–65.34 MB` across 6.3 million full-stack script executions, returning to `6.39 MB` after final GC.
- **Zero Thread / `ThreadLocal` Leaks**: Thread count stayed `1 -> 1`, and `PyCaller` / `ExceptionTypeRegistry` `ThreadLocal` assertions passed after every cycle.
- **Stable Throughput**: ~30,000 full 6-scenario cycles (~210,000 scripts) per minute continuously from minute 1 through minute 30 with no degradation.
