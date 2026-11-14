# Changelog

All notable changes to the `jython3-prototype` project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [v0.11.0-alpha] - 2026-11-14
### Added
- **Monomorphic Inline Caching (`LOAD_ATTR`)**: Per-instruction call-site caching in `CompiledCode.attrCache` eliminating repeated inheritance tree and map lookups for class methods.
- **Inlined Bound Method Dispatch**: Inlined `PyBoundMethod` invocation directly into the interpreter loop (`continue outer`) in `CALL_FUNCTION` and `CALL_FUNCTION_KW`, eliminating nested interpreter frames.
- **Class Member Resolution Caching (`PyClass.lookupMember`)**: Added `memberCache` to `PyClass` to resolve inherited methods in $O(1)$ time after initial lookup.
- **Instance Bound Method Caching (`PyInstance`)**: Added `boundMethodCache` in `PyInstance` to reuse `PyBoundMethod` wrappers across calls.
- **Zero-Allocation Dictionary Lookups (`PyDict`)**: Added reusable `ThreadLocal<QueryKey>` for dictionary queries (`get`, `containsKey`, `remove`), eliminating temporary `PyKey` allocations on lookups.
- **Zero-Allocation Set Hashability Check (`PySet.add`)**: Added static `PyKey.checkHashable()` eliminating temporary key allocations on set insertion.
- **Optimized ASCII/BMP String Slicing and Indexing (`PyStr`)**: Fast-path detection for non-surrogate Unicode strings enabling direct JVM `String.substring` and character lookups without intermediate `int[]` code point arrays.
- **Small Integer Caching (`PyInt.of`)**: Pre-allocated cache for integers between `-128` and `256` plus static constants (`PyInt.ZERO`, `PyInt.ONE`, `PyInt.MINUS_ONE`), avoiding millions of temporary heap allocations during arithmetic loops and range iteration.
- **Fast Array-Backed Operand Stack (`FastStack`)**: Replaced `ArrayDeque` with `FastStack` in VM frames and call stack, reducing stack push/pop overhead to single-instruction array operations.
- **Fast Function Argument Binding**: Added zero-overhead fast path in `bindArgs` for pure positional calls matching parameter count without kwargs or varargs, reducing call setup overhead.
- **Precomputed Generator Flag (`hasYield`)**: Stored `hasYield` in `CompiledCode` to turn O(N) bytecode yield scanning into O(1) checks on function calls.
- **Instruction Counting Bypass in Trusted Mode**: Bypassed per-instruction budget checks when running unconstrained trusted execution.
- **Performance Benchmark Suite (Step 1, 2, 3)**: Reproducible micro-benchmark suite (`BenchmarkSuite`) measuring 8 core execution workloads with isolated compilation and execution timing.
- **Full Scoping with `nonlocal` and `PyCell`**: Integrated `PyCell` mutable cell bindings into the compiler and interpreter, enabling lexical closure mutations across arbitrary nested enclosing scopes.
- **Gradle Benchmark Task**: Added `./gradlew benchmark` task for automated performance baseline profiling with separate Compile and Execution metrics.
- **Benchmark Smoke Tests & Conformance Suite**: Added `BenchmarkSmokeTest` and `NonlocalAndBuiltinFixesTest` (353 automated tests passing green).

### Fixed
- **Builtin `print` Keywords**: Implemented `print(file=..., flush=...)` routing with support for custom file objects (`.write()`) and flush handling.
- **Builtin `int` Argument Validation**: Fixed `int(obj, base=10)` to reject duplicate `base` argument when passed both positionally and as keyword (`TypeError: int() got multiple values for argument 'base'`).
- **Project Structure Documentation**: Fixed tree alignment in `docs/PROJECT_STRUCTURE.md` and updated bytecode opcode count to 77.

---

## [v0.10.0-alpha] - 2026-10-26
### Added
- **Core Numeric Model (P1.0 & P1.1)**: True division `/` always producing `float`, integer-power semantics using BigInteger exponentiation, and `PyOps` exact numeric operations.
- **Python Dictionary Key Semantics (P1.2)**: `PyKey` wrapper implementing Python 3 key semantics where `1`, `1.0`, and `True` share equivalent identity and hash codes, while unhashable collections (`list`, `dict`) throw `TypeError`.
- **Extended Slicing (P1.3)**: Comprehensive list and string slicing (`s[a:b:c]`, `s[::-1]`, `lst[:]`) with exact CPython `slice.indices()` semantics, Unicode/Emoji surrogate boundaries, and `step != 0` validation.
- **Differential Test Harness**: `DifferentialHarness` and `CoreDifferentialTest` comparing Jython 3 directly against host CPython 3 across division, powers, comparisons, dictionary keys, and slicing.
- **Security & Resource Enforcement (P0.1, P0.2, P0.3)**: `HostAccessPolicy` gating filesystem, environment, and disk imports; clean module namespaces avoiding caller globals leakage; `ExecutionLimits` and `ExecutionContext` with instruction counting, timeouts, and pre-allocation checks.
- Test suite expanded to **276 automated tests** (all passing green).

---

## [v0.9.8-alpha] - 2026-10-26
### Documentation
- Modularized documentation architecture following the single source of truth principle:
  - Streamlined `README.md` into an accurate, clean landing page with quickstart guide and documentation links.
  - Created `docs/PROJECT_STRUCTURE.md` mapping full codebase layout and package responsibilities.
  - Created `docs/COMPATIBILITY.md` documenting T0–T3 compatibility stages against CPython 3.14.
  - Created `docs/ROADMAP.md` documenting milestone progression from foundation to v1.0.0.
  - Created `docs/STDLIB.md` detailing built-in modules (`math`, `sys`, `os`, `io`, `json`, `re`, `time`, `random`).
  - Created `SECURITY.md` detailing threat models, access policies, and vulnerability reporting.
  - Created `CHANGELOG.md` tracking all historical releases.

---

## [v0.9.7-alpha] - 2026-10-26
### Added
- Graph-aware, circular-reference safe sanitization in `sanitizeForPolicy()` using `IdentityHashMap` to prevent `StackOverflowError` on self-referential lists, dicts, and modules.
- Recursive inheritance chain sanitization for `PyClass` (`baseClass` and `getBases()`) and isolated instance instantiation for `PyInstance`.
- Pure `BigInteger` rejection sampling in `random.randint()` for unbiased uniform distributions across arbitrary-precision ranges.
- Exact `BigInt` vs `PyFloat` equality and relational comparisons via `BigDecimal` without 64-bit float precision loss.
- Unit test suite expanded to 246 passing tests.

---

## [v0.9.6-alpha] - 2026-10-26
### Added
- Deep sanitization for `PyModule`, `PyFunction` (closures), and `PyClass` when injected across interpreter security boundaries.
- Standard `time.time_ns()` corrected to Unix Epoch nanoseconds using `Instant.now()`.
- Hardened `time.sleep()` input validation against `NaN`, `Infinity`, negative durations, and overflow.
- Per-module PRNG instance isolation for `random` module.
- Unicode and Emoji codepoint-safe character selection in `random.choice(str)`.
- BigInt literal parsing support in `CompileVisitor`.

---

## [v0.9.5-alpha] - 2026-10-25
### Added
- Built-in standard `time` module (`time`, `time_ns`, `sleep`, `monotonic`, `perf_counter`).
- Built-in standard `random` module (`random`, `randint`, `choice`, `seed`).

---

## [v0.9.4-alpha] - 2026-10-25
### Security
- Prevented wrapper privilege escalation when transferring Java wrappers across different policy contexts.
- Sanitized `PyJavaObject.toString()` so restricted classes return safe representation without invoking target Java code.
- Blocked `java.awt.Robot` in `AllowlistPolicy` to prevent desktop automated inputs.

---

## [v0.9.3-alpha] - 2026-10-25
### Security
- Explicitly blocked `java.awt.Desktop` and external launcher methods (`browse`, `open`, etc.).
- Added recursive checks for inner classes (`$`) of forbidden types (e.g. `Thread$State`, `ProcessBuilder$Redirect`).
- Introduced `SandboxedPolicy` profile strictly prohibiting file I/O, network access, UI, and reflection.

---

## [v0.9.2-alpha] - 2026-10-25
### Security
- Hardened `AllowlistPolicy` against thread interruption and process control (`Thread`, `ThreadGroup`, `ProcessHandle`).
- Blocked unsafe Java deserialization entry points (`ObjectInputStream`, `ObjectOutputStream`, `readObject`).
- Blocked dynamic class loading via `URLClassLoader` and reflection methods (`newInstance`, `loadClass`).

---

## [v0.9.1-alpha] - 2026-10-25
### Added
- Support for exception tuples: `except (ValueError, TypeError) as e:`.
- Automatic PEP 3110 deletion of `as e` exception variables upon leaving `except` blocks.
- Unwinding guarantee: `finally` suites always execute even when a replacement exception is raised inside an `except` handler.

---

## [v0.9.0-alpha] - 2026-10-24
### Added
- Implemented `execfile()` opcode for direct execution of legacy automation scripts.
- Refactored interpreter into clean decoupled packages (`compile`, `runtime`, `bridge`, `security`, `stdlib`, `cli`, `parse`).
- Introduced interactive CLI REPL and standalone script launcher (`jython3`).
- Added `-c` inline code execution flag to `RunScript`.

---

## [v0.8.0-alpha] - 2026-10-22
### Added
- Structured exception handling: `try / except / else / finally` with handler stack.
- Class-based exception hierarchy (`BaseException`, `Exception`, `ValueError`, `TypeError`, etc.).
- Active exception management and cross-frame traceback capturing.

---

## [v0.7.0-alpha] - 2026-10-21
### Added
- Initial Java-backed standard library modules (`math`, `sys`, `os`, `io`).
- Attribute lookup (`LOAD_ATTR`) and dynamic method invocation.

---

## [v0.6.0-alpha] - 2026-10-20
### Added
- Real 32-bit bytecode layer with `CompiledCode` and `constPool`.
- Support for mutable lists, subscription indexing, and `for ... in` iteration.

---

## [v0.1.0-alpha] - 2026-10-18
### Added
- Initial project scaffold targeting Java 21+ with ANTLR4 grammar parser.
- Core virtual machine loop, arithmetic operations, and basic variable storage.
