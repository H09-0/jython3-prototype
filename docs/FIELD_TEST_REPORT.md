# Jython 3 Prototype — Field Test & Resource Profiling Report

> **Live Status Reference**: For the single source of truth on current capabilities, see **[STATUS.md](STATUS.md)** and **[COMPATIBILITY.md](COMPATIBILITY.md)**.
> For the 30-minute uninterrupted large-scale soak test (901,230 cycles / 6.3M scripts), see **[LARGE_SCALE_TEST_RESULTS.md](LARGE_SCALE_TEST_RESULTS.md)**.
> Automated field scenarios and leak probes are executed via `./gradlew fieldTest` (`org.example.jython3.field.FieldTestRunner`) and verified in CI via `FieldIntegrationTest`.

---

## 1. Real-World Field Scenarios (`./gradlew fieldTest`)

| Scenario (`org.example.jython3.field.*`) | Exercised Subsystems | Status |
| :--- | :--- | :---: |
| **`AnalyticsScenario`** | `json.loads` / `json.dumps` (nested objects/arrays), list comprehensions, `lambda`, `sorted(..., key=..., reverse=True)`, dict aggregation | ✅ **PASS** |
| **`OopScenario`** | `class` inheritance, zero-arg `super()`, `with` context managers (`__enter__`/`__exit__`), `nonlocal` closures (`PyCell`), generators (`yield`, `list(gen)`) | ✅ **PASS** |
| **`RegexScenario`** | `re.findall`, raw string literals (`r"\d+"`), Unicode/Emoji codepoint indexing and slicing (`[::-1]`, `[3:10]`), `random` Monte Carlo simulation | ✅ **PASS** |
| **`JavaBridgeScenario`** | `ArrayList`, `HashMap` subscripting, `StringBuilder` fluent chaining (`.append().append()`), `Math.sqrt`, policy-scoped resolution caching | ✅ **PASS** |

---

## 2. Stress & Memory / Resource Leak Probes

| Probe | Workload | Verified Outcome |
| :--- | :--- | :--- |
| **Stress Test A — Unwinding & Sandbox Budget** | 200 consecutive deep `RecursionError` unwinds through `try/finally` + infinite loop halted by `ExecutionLimits` | ✅ **PASS** — VM stack and frames remain clean; subsequent scripts execute normally. |
| **Stress Test B — 600-Cycle Long-Running Interpreter** | 600 full cycles across all 4 field scenarios (2,400 script runs) on a single warm interpreter | ✅ **PASS** — Post-GC Heap delta `< +0.10 MB`, NonHeap/Metaspace stable, thread count constant (`6 -> 6`), `BytecodeCache` bounded. |
| **Stress Test C — 300 Short-Lived Interpreters** | Creating, evaluating OOP/dict/tuple scripts, and discarding 300 independent `Jython3Interpreter` instances | ✅ **PASS** — Post-GC Heap delta `< +0.05 MB`; `PyCaller.CURRENT` and `PyDict.QUERY_KEY` `ThreadLocal` references cleaned up in `finally` blocks. |

---

## 3. Historical Defects Resolved (Archive)

All defects and syntax gaps identified in the initial `v0.10.2-alpha` baseline field test have been resolved and covered by permanent regression tests:

| Historical ID | Description | Resolution Status |
| :---: | :--- | :---: |
| **C1** | `break` in nested `for` loops caused infinite loop | ✅ Fixed (`e4a38b3`) |
| **H1** | `isinstance(42, int)` with built-in types | ✅ Fixed (`b2a8869`) |
| **H2** | `True + True` boolean integer arithmetic | ✅ Fixed (`1fbea33`) |
| **H3** | Sequential multiple-argument `%` string formatting | ✅ Fixed (`351f9c7`) |
| **M1–M7** | Default args, tuple unpacking, `lambda`, comprehensions, f-strings, `global`/`nonlocal`, chained assignment | ✅ Implemented (`0b37ad0`, `89a6e79`) |
| **P1–P4** | Unary `+`, immutable `PyTuple`, `re.findall` / `re.error`, iterable protocol in `list`/`sum`/`sorted`/`reversed` | ✅ Implemented (`0ac55e6`, `50d397a`, `6252a0b`) |
| **F1–F5** | Strict RFC 8259 `json.loads` (`JSONDecodeError`) & circular `json.dumps` check, zero-arg `super()`, raw strings (`r"..."`), `StringBuilder` chaining, `ThreadLocal` cleanup | ✅ Fixed (`50d397a`, `9c9bb5b`, `5233140`) |
