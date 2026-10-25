# Development Roadmap

This document outlines the milestones, completed phases, and future development steps toward **Jython 3 v1.0.0-alpha** and production readiness.

---

## Completed Milestones

### Phase 1–5: Interpreter Foundation & Scaffolding (v0.1.0 – v0.5.0)
- ✅ Modern JVM architecture targeting Java 21+.
- ✅ ANTLR4 syntax parser with clean grammar rules.
- ✅ Fundamental arithmetic expressions with operator precedence.
- ✅ Variable bindings, scope management, and isolated call frames.
- ✅ Function definitions, return values, and nested recursion.

### Phase 6: Bytecode Compilation Engine (v0.6.0)
- ✅ 32-bit instruction encoding (`OpCode` + `constPool`).
- ✅ Stack-based virtual execution machine (`run()` loop).
- ✅ Dynamic list literals, subscript indexing, and `for ... in` iterations.

### Phase 7: Built-in Standard Library (v0.7.0)
- ✅ Java-backed standard modules (`math`, `sys`, `os`, `io`).
- ✅ Attribute lookup (`LOAD_ATTR`) and method dispatch.
- ✅ Floating point (`PyFloat`) and module object model (`PyModule`).

### Phase 8: Exception Handling Model (v0.8.0)
- ✅ Class-based exception hierarchy (`BaseException`, `Exception`, `ValueError`, `TypeError`, etc.).
- ✅ `try / except / else / finally` blocks with handler stack.
- ✅ Active exception restoration and cross-frame traceback capturing.

### Phase 9: Java Bridge & Oculix Compatibility (v0.9.0)
- ✅ High-performance bidirectional Java reflection bridge (`JavaBridge`).
- ✅ Automatic argument conversion and constructor/method overload resolution.
- ✅ `execfile()` opcode for direct execution of legacy automation scripts.
- ✅ Interactive CLI REPL and standalone script runner (`jython3`).

### Phase 10: Security Hardening & Conformance (v0.9.1 – v0.9.8)
- ✅ Python 3 `except (A, B)` tuple matching and PEP 3110 `as e` deletion at block exit.
- ✅ Complete unwinding guarantee for `finally` suites when exceptions are raised inside handlers.
- ✅ ThreadGroup, Process, ProcessHandle, Deserialization, ClassLoader, and Reflection lockdown.
- ✅ Recursive containment of inner classes (`$`) and desktop automation blocking (`Desktop`, `Robot`).
- ✅ Introduction of `SandboxedPolicy` profile for untrusted code execution.
- ✅ Safe graph-aware sanitization with `IdentityHashMap` to handle circular object references.
- ✅ Standard `time` (with Epoch `time_ns()`) and `random` (with unbiased arbitrary-precision `randint()`).
- ✅ Exact `BigInt` vs `PyFloat` comparisons without precision loss.
- ✅ Modularized documentation architecture (v0.9.8-alpha).

---

## Current Status

- **Current Release**: `v0.9.8-alpha` (Pre-production research prototype).
- **Next Milestone**: `v1.0.0-alpha` (Feature stabilization & differential CPython 3.14 test harness).
- Automated test suite: **246 tests passing green in continuous integration**.

---

## Upcoming Phases

### Phase 11: Modern Syntax Expansion (v1.1.0)
- [ ] Context managers (`with` statement, `__enter__` and `__exit__`).
- [ ] List, dictionary, and set comprehensions (`[x for x in seq]`, `{k: v for ...}`).
- [ ] Interpolated string literals (`f"..."`).
- [ ] `raise ... from ...` with full `__cause__` and `__context__` chain formatting.

### Phase 12: Virtual Threads & Concurrency (v1.2.0)
- [ ] Native support for Java 21+ Virtual Threads (Project Loom).
- [ ] Standard library `threading` and `concurrent.futures` modules.
- [ ] GIL-free execution architecture (leveraging Java's native memory model).

### Phase 13: Oculix Engine Integration & Packaging (v2.0.0)
- [ ] Replacement of embedded Jython 2.7 in Oculix IDE with `Jython3Interpreter`.
- [ ] Packaging as an standalone Maven Central dependency (`org.example.jython3:jython3-core`).
- [ ] Native executable packaging with GraalVM Native Image.
