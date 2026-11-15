# Jython 3 — Current Project Status

> **Single Source of Truth**: This file tracks the live, verified state of `jython3-prototype`.
> For feature-by-feature details, see [COMPATIBILITY.md](COMPATIBILITY.md).
> For future plans, see [ROADMAP.md](ROADMAP.md).
> For release history, see [CHANGELOG.md](../CHANGELOG.md).

---

## 1. Documentation Roles

| Document | Responsibility |
| :--- | :--- |
| **`docs/STATUS.md`** | **Current verified runtime state, active capabilities, and core concept glossary.** |
| **`docs/COMPATIBILITY.md`** | Feature-by-feature compatibility matrix against CPython 3.14 (`T0`–`T3`). |
| **`docs/ROADMAP.md`** | Future planned work and upcoming optimization/language phases only. |
| **`CHANGELOG.md`** | Historical record of changes per release. |
| **`README.md`** | Project overview, quickstart commands, and links (no duplicated metrics). |
| **`docs/ARCHITECTURE.md`** | Pipeline design, layer boundaries, bytecode format, and VM invariants. |

---

## 2. Verified Current Capabilities

### Core Language & Syntax
- **Numeric & Boolean Semantics**: Signed 32-bit `int` (with `-128..256` cache and automatic `BigInt` promotion), IEEE-754 `float`, true division `/`, exact floor division `//` and `%`, `bool` subclassing `int` (`True + True == 2`).
- **Strings**: UTF-8 / codepoint-safe `PyStr` (with non-surrogate ASCII/BMP fast-path), triple-quoted strings, raw strings (`r"..."`, `r'...'`), f-strings (`f"..."`), `%` formatting, and full Unicode slicing (`s[a:b:c]`).
- **Collections**: Mutable `PyList`, immutable `PyTuple`, insertion-ordered `PyDict` (with `1 == 1.0 == True` key semantics and zero-allocation lookup key), `PySet` (with hashability enforcement).
- **Comprehensions**: List (`[x for x in seq if cond]`), dict (`{k: v for ...}`), and set (`{x for ...}`) comprehensions.
- **Functions & Scope**: `def`, `lambda`, default parameters, `*args`, `**kwargs`, keyword calls, lexical closures, `global`, `nonlocal` via `PyCell`, decorators, and generators (`yield` with `PyGenerator`).
- **Assignments & Unpacking**: Chained assignment (`a = b = expr`), tuple/sequence unpacking (`a, b = pair`), augmented assignment.
- **Control Flow & Exceptions**: `if`/`elif`/`else`, `while`, `for`, `break`/`continue`, `assert`, `with` context managers (`__enter__`/`__exit__`), `try`/`except (A, B) as e`/`else`/`finally`, `raise`.
- **OOP**: Classes, single & multiple inheritance, `super()` (0-arg and 2-arg), bound methods, version-guarded monomorphic inline caching (`LOAD_ATTR`) and hierarchy invalidation.

### Known Language Gaps
- Tuple unpacking directly inside `for` headers (`for a, b in seq:`).
- Async/await (`async def`, `await`).
- Coroutine `.send()` / `.throw()` on suspended generator frames.
- `raise ... from ...` explicit exception chaining (`__cause__`).
- Dynamic `__bases__` mutation after class creation.

### Standard Library Modules
- Built-in modules: `math`, `sys`, `os`, `io`, `json` (strict RFC 8259 parser raising `JSONDecodeError`), `re`, `time`, `random`.

### Performance & Java Bridge
- Array-backed `FastStack`, small-int caching, zero-overhead positional `bindArgs` fast path, precomputed `hasYield` flag.
- Version-guarded monomorphic inline caching for `LOAD_ATTR` and `PyInstance.boundMethodCache`.
- Policy-scoped, capacity-bounded overload/constructor resolution caching and pre-compiled `TypeConverter` plans in `JavaBridge`.
- Reproducible benchmark suite (`./gradlew benchmark`) and real-world field/leak profiler (`./gradlew fieldTest`).

---

## 3. Core Runtime Concepts Glossary

Understanding how internal Java runtime types map to Python semantics:

| Concept | Java Type | Relationship & Purpose |
| :--- | :--- | :--- |
| **Python `None`** | `PyNull` | Represents Python's `None` object inside the VM stack, locals, and collections. **Distinct from Java `null`** (Java `null` inside `currentLocals` or maps indicates an *unbound* variable, never `None`). |
| **Java `null`** | `null` (reference) | Used internally in Java structures for absent map entries or uninitialized slots. `JavaBridge.toPyObject(null, policy)` converts Java `null` to `PyNull`. `JavaBridge.toJavaObject(PyNull, refType)` converts `PyNull` back to Java `null` for reference types (and rejects `PyNull` with `TypeError` for primitive types). |
| **Callable Bridge** | `PyCaller` | Thread-scoped interface installed on `PyCaller.CURRENT` during `eval()`/`evalCode()` (and removed in `finally` to prevent `ThreadLocal` leaks) allowing built-ins (`sorted(key=...)`, `map`, `filter`, `super()`) and `PyInstance.toString()` to invoke Python callables on the active interpreter. |
| **Closure / Nonlocal Cell** | `PyCell` | Mutable reference box holding a `PyObject` value (or `null` if deleted/unbound). Enables inner functions with `nonlocal` to read and mutate bindings shared across enclosing call frames and closures. |
| **Nested Code Block** | `PyCompiledCodeWrapper` | `PyObject` wrapper carrying a compiled `CompiledCode` for class bodies on the VM operand stack before `BUILD_CLASS` executes them. |
| **Resource & Sandbox Tracker** | `ExecutionContext` | Per-evaluation tracker enforcing `ExecutionLimits` (instruction budget, wall-clock timeout, host cancellation, collection/string pre-allocation caps) across nested calls and imported modules. |
