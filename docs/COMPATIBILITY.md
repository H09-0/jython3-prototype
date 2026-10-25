# Compatibility Matrix

This document provides a detailed breakdown of language features supported by `jython3-prototype` against the standard behavior of **CPython 3.14**.

---

## Compatibility Tiers

- **Tier 0 (Complete)**: Syntactically and semantically compliant with CPython 3.14. Covered by regression tests.
- **Tier 1 (Substantial)**: Fully functional for core use cases, with minor edge cases or omitted optional arguments.
- **Tier 2 (Partial / Work in Progress)**: Functional prototype available; actively undergoing enhancement.
- **Tier 3 (Planned / Not Yet Implemented)**: Reserved on the roadmap; syntax or module not yet available.

---

## Core Language Syntax & Semantics

| Feature | Tier | Status / Details |
| :--- | :---: | :--- |
| **Integer Arithmetic** | **T0** | Signed 32-bit `int` with automatic transparent promotion to arbitrary-precision `BigInt`. Overflow-free `+`, `-`, `*`, `//`, `%`, `**`. |
| **Floating Point Arithmetic** | **T0** | IEEE 754 64-bit double precision. Division `/` always produces float. Division by zero raises `ZeroDivisionError`. |
| **Numeric Comparisons** | **T0** | Exact equality and relational comparisons across `int`, `BigInt`, and `float` without 64-bit double precision loss. |
| **String Manipulation** | **T0** | UTF-8 encoded `PyStr`. Triple-quoted strings, escape sequences, concatenation, repetition `*`, `.upper()`, `.lower()`, `.strip()`, `.split()`, `.join()`, `.replace()`, `.startswith()`, `.endswith()`, `.find()`, `.count()`, `.isdigit()`, `.isalpha()`, `.isalnum()`. |
| **Boolean Logic** | **T0** | `True`, `False`, `None`, short-circuit `and` / `or`, unary `not`, identity `is` / `is not`, membership `in` / `not in`. |
| **Lists** | **T0** | Dynamic mutable `PyList`. Indexing (positive & negative), slicing, `.append()`, `.extend()`, `.insert()`, `.pop()`, `.remove()`, `.index()`, `.count()`, `.sort()`, `.reverse()`, `.copy()`, `.clear()`. |
| **Dictionaries** | **T0** | Insertion-ordered `PyDict`. Key-value access, `.keys()`, `.values()`, `.items()`, `.get(key, default)`, `.pop()`, `.update()`, `.copy()`, `.clear()`. |
| **Tuples** | **T1** | Syntactically parsed as `(a, b)` and supported in `except (E1, E2)` clauses. Tuple immutability enforcement is in progress. |
| **Control Flow** | **T0** | `if` / `elif` / `else`, `while`, `for ... in ...`. Loop control: `break` and `continue` with proper jump back-patching across nested contexts. |
| **Functions & Scope** | **T0** | `def name(args):`, default return `None`, explicit `return val`, isolated local frames, closure variable capture, nested function definitions. |
| **Decorators** | **T0** | Function and class decorators `@dec` with bottom-to-top chaining. |
| **Object-Oriented Programming** | **T0** | `class Name(Bases):`, `__init__` constructor, instance fields, bound method invocation, single & multiple inheritance, method lookup resolution, `super()` proxy. |
| **Exception Handling** | **T0** | `try / except / else / finally`. Supports class-based hierarchy matching (`isinstance`), multiple except clauses, exception tuples `except (A, B)`, `as e` variable binding with automatic deletion at block exit (PEP 3110), nested unwinding, and bare `raise` of active exceptions. |
| **Generators** | **T1** | Functions containing `yield` produce a `PyGenerator` object supporting iteration in `for` loops. Coroutine `.send()` and `.throw()` are planned. |
| **execfile()** | **T0** | Direct execution of external Python source files within current scope (essential for Jython 2.7 migration & Oculix automation). |

---

## Standard Library Modules

| Module | Tier | Available Functions & Constants |
| :--- | :---: | :--- |
| **`math`** | **T0** | `sqrt`, `sin`, `cos`, `tan`, `log`, `floor`, `ceil`, `abs`, `pow`, `pi`, `e`, `inf` |
| **`sys`** | **T0** | `argv` (CLI arguments pass-through), `version`, `platform`, `maxsize`, `exit(code)` |
| **`os`** | **T0** | `getcwd()`, `getpid()`, `sep`, `pathsep`, `name`, `environ`, `listdir(path)` |
| **`io`** | **T0** | `open(file, mode)` supporting `'r'`, `'w'`, `'a'` modes, `.read()`, `.write()`, `.close()` |
| **`json`** | **T0** | `dumps(obj)`, `loads(json_str)` supporting nested dicts, lists, numbers, booleans, and null |
| **`re`** | **T0** | `search(pattern, str)`, `findall(pattern, str)` backed by Java Regex engine |
| **`time`** | **T0** | `time()`, `time_ns()` (Unix Epoch nanos), `sleep(secs)` (validated against NaN/Inf/negative), `monotonic()`, `perf_counter()` |
| **`random`** | **T0** | `random()`, `randint(a, b)` (unbiased arbitrary-precision rejection sampling), `choice(seq)` (Unicode/Emoji codepoint-safe), `seed(n)` |

---

## Known Gaps & Differences from CPython 3.14

1. **Async / Await (`async def`, `await`)**:
   - Currently Tier 3. Planned for implementation on top of Java virtual threads (Project Loom).
2. **Context Managers (`with` statement)**:
   - Planned for Phase 11.
3. **Comprehensions**:
   - List and dictionary comprehensions are planned for Phase 11 grammar expansion.
4. **F-Strings (`f"..."`)**:
   - String formatting currently uses `%` operator and `.format()`. Full interpolated f-string AST expansion is scheduled.
5. **C-Extension Modules (CPython C-API)**:
   - Not supported and not planned. Integration is performed natively via the JVM **Java Bridge**.
