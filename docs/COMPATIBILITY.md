# Compatibility Matrix

This document provides a feature-by-feature evaluation of language support in `jython3-prototype` against the **CPython 3.14** baseline.

---

## Compatibility Stages (T0 – T3)

The project adheres to the uniform four-stage definitions established in `docs/ARCHITECTURE.md`:

- **T0 (Parsed)**: Grammar rule exists in `Jython3.g4` and source compiles to an AST, but execution may not be complete or verified.
- **T1 (Executes)**: Instruction sequence executes on the stack-machine VM and produces expected results in baseline automated unit tests.
- **T2 (CPython Matches)**: Output values, error types, exception messages, and edge cases match CPython 3.14 verified by differential tests.
- **T3 (Library Ready)**: Complete, production-grade behavior capable of hosting external standard libraries and frameworks without modification.

---

## Core Language Syntax & Semantics

| Feature | Stage | Implementation & Verification Notes |
| :--- | :---: | :--- |
| **Integer Arithmetic** | **T2** | Signed 32-bit `int` with automatic transparent promotion to arbitrary-precision `BigInt`. Overflow-free `+`, `-`, `*`, `//`, `%`, `**`. Tested against large bounds. |
| **Floating Point Arithmetic** | **T2** | IEEE 754 64-bit double precision. `/` always produces `float`. Division by zero raises `ZeroDivisionError`. |
| **Exact BigInt & Float Comparisons** | **T2** | Exact equality and relational comparisons via `BigDecimal` without 64-bit float precision loss. Validated against `9007199254740993 > 9007199254740992.0`. |
| **String Manipulation** | **T1** | UTF-8 `PyStr`. Triple-quoted strings, escapes, `.upper()`, `.lower()`, `.strip()`, `.split()`, `.join()`, `.replace()`, `.startswith()`, `.endswith()`, `.find()`, `.count()`, `.isdigit()`, `.isalpha()`, `.isalnum()`. |
| **Boolean & Identity Operations** | **T2** | `True`, `False`, `None`, short-circuit `and` / `or`, unary `not`, identity `is` / `is not`, membership `in` / `not in`. |
| **Lists** | **T1** | Dynamic mutable `PyList`. Indexing, slicing, `.append()`, `.extend()`, `.insert()`, `.pop()`, `.remove()`, `.index()`, `.count()`, `.sort()`, `.reverse()`, `.copy()`, `.clear()`. |
| **Dictionaries** | **T1** | Insertion-ordered `PyDict`. Key lookup, `.keys()`, `.values()`, `.items()`, `.get(key, default)`, `.pop()`, `.update()`, `.copy()`, `.clear()`. |
| **Tuples** | **T1** | Parsed as `(a, b)` and supported in `except (E1, E2)` clauses. Full tuple immutability is in progress. |
| **Control Flow** | **T1** | `if` / `elif` / `else`, `while`, `for ... in ...`. Loop control: `break` and `continue` with loop context back-patching across nested loops. |
| **Functions & Scope** | **T1** | `def name(args):`, `return val`, isolated local call frames, lexical closure capture, nested function definitions. |
| **Decorators** | **T1** | Function and class decorators `@dec` with bottom-to-top chaining. |
| **Object-Oriented Programming** | **T1** | `class Name(Bases):`, `__init__`, dynamic instance fields, bound methods, single/multiple inheritance, `super()` proxy. |
| **Exception Handling** | **T2** | `try / except / else / finally`. Supports class hierarchy matching, multiple except clauses, exception tuples `except (A, B)`, PEP 3110 `as e` deletion at block exit, nested unwinding, handler `finally` execution, and bare `raise`. |
| **Generators** | **T1** | Functions with `yield` produce `PyGenerator` supporting iteration in `for` loops. Coroutine `.send()` / `.throw()` are in progress. |
| **execfile()** | **T1** | Direct execution of external Python source files within current scope (Jython 2.7 migration & Oculix automation). |
| **Context Managers (`with`)** | **T0** | Planned for Phase 11. |
| **Comprehensions** | **T0** | List, dict, and set comprehensions planned for Phase 11. |
| **F-Strings (`f"..."`)** | **T0** | String interpolation currently uses `%` and `.format()`. Full f-string parser expansion planned for Phase 11. |
| **Async / Await** | **T0** | Reserved for Phase 12 (Project Loom virtual threads). |

---

## Standard Library Modules

| Module | Stage | Implementation & Verification Notes |
| :--- | :---: | :--- |
| **`math`** | **T1** | `sqrt`, `sin`, `cos`, `tan`, `log`, `floor`, `ceil`, `abs`, `pow`, `pi`, `e`, `inf` |
| **`sys`** | **T1** | `argv`, `version`, `platform`, `maxsize` (JVM Integer/Long boundary), `exit(code)` |
| **`os`** | **T1** | `getcwd()`, `getpid()`, `sep`, `pathsep`, `name`, `environ` (dict-like), `listdir(path)` |
| **`io`** | **T1** | `open(file, mode)` supporting `'r'`, `'w'`, `'a'`, `.read()`, `.write()`, `.close()` |
| **`json`** | **T1** | `dumps(obj)`, `loads(json_str)` supporting nested dicts, lists, numbers, booleans, and null |
| **`re`** | **T1** | `search(pattern, str)`, `findall(pattern, str)` backed by Java Regex engine (minor pattern dialect differences from CPython PCRE) |
| **`time`** | **T2** | `time()`, `time_ns()` (Unix Epoch nanos via `Instant.now()`), `sleep(secs)` (validated against NaN/Inf/negative), `monotonic()`, `perf_counter()` |
| **`random`** | **T2** | `random()`, `randint(a, b)` (unbiased arbitrary-precision rejection sampling), `choice(seq)` (Unicode/Emoji codepoint-safe), `seed(n)` (per-instance isolation) |
