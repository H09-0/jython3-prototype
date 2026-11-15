# Compatibility Matrix

This document provides a feature-by-feature evaluation of language support in `jython3-prototype` against the **CPython 3.14** baseline.
For a high-level summary of the current live state, see [STATUS.md](STATUS.md).

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
| **Integer Arithmetic & Bitwise** | **T2** | Signed 32-bit `int` (with `-128..256` cache) and automatic transparent promotion to `BigInt`. Decimal, hex (`0x`), binary (`0b`), and octal (`0o`) literals. Overflow-free `+`, `-`, `*`, `//`, `%`, `**`, and bitwise `&`, `|`, `^`, `~`, `<<`, `>>` (plus compound `&=`, `|=`, `^=`, `<<=`, `>>=`), differential tested against CPython. |
| **Floating Point Arithmetic** | **T2** | IEEE 754 64-bit double precision. `/` always produces `float`. Division by zero raises `ZeroDivisionError`. |
| **Exact BigInt & Float Comparisons** | **T2** | Exact equality and relational comparisons via `BigDecimal` without 64-bit float precision loss. |
| **String Manipulation** | **T2** | UTF-8 `PyStr` with non-surrogate ASCII/BMP fast-path. Triple-quoted strings, raw strings (`r"..."`), escapes, `%` formatting, f-strings (`f"..."`), and string methods. |
| **F-Strings (`f"..."`)** | **T1** | Interpolated string literals `f"..."` and `f'...'` with embedded expressions. |
| **Boolean & Identity Operations** | **T2** | `True`, `False`, `None` (`PyNull`), short-circuit `and` / `or`, unary `not`, identity `is` / `is not`, membership `in` / `not in`. |
| **Lists** | **T2** | Dynamic mutable `PyList`. Indexing, slicing, `.append()`, `.extend()`, `.insert()`, `.pop()`, `.remove()`, `.index()`, `.count()`, `.sort()`, `.reverse()`, `.copy()`, `.clear()`. |
| **Dictionaries** | **T2** | Insertion-ordered `PyDict` implementing Python 3 key semantics (`1 == 1.0 == True`, `(1, 2)` ≡ `(1.0, 2)`, `BigInt` ≡ integral `float`) for lookup, `in`, literals (later duplicate keys overwrite the value and keep the first key), and `==`/`!=`; unhashable lists/dicts/sets raise `TypeError`. Differential tested against CPython. |
| **Tuples & Unpacking** | **T2** | Immutable `PyTuple` distinct from `PyList`, sequence unpacking `a, b = seq`, `for a, b in seq:`, `return a, b`, and `except (E1, E2)` tuple matching. |
| **Sets** | **T2** | Mutable `PySet` keyed by `PyKey` (same hash/eq contract as dict keys: `{1, 1.0, True}` has one element), set literals, set comprehensions, `set()`/`set(iterable)`, `len`, `in`, `==`/`!=`, `isinstance(x, set)`; unhashable elements raise `TypeError`. Methods other than `add` are not yet implemented. Iteration order is insertion order (CPython uses hash order). |
| **Comprehensions** | **T2** | List comprehensions (`[x for x in seq if cond]`), dict comprehensions (`{k: v for ...}`), and set comprehensions (`{x for ...}`), differential tested against CPython. |
| **Slicing** | **T2** | List, tuple, and string slicing (`s[a:b:c]`, `s[::-1]`) implementing CPython `slice.indices()` semantics and Unicode codepoint safety. |
| **Control Flow** | **T1** | `if` / `elif` / `else`, `while`, `for ... in ...`, `break`, `continue`, `assert`, chained assignment (`a = b = expr`). |
| **Functions & Scope** | **T2** | `def`, `lambda`, default arguments, `*args`, `**kwargs`, keyword calls, lexical closures, `global`, `nonlocal` via `PyCell`, decorators. |
| **Context Managers (`with`)** | **T2** | `with expr [as id]:` executing `__enter__` and `__exit__` on normal and exceptional exits, including `return`/`break`/`continue`/`raise` out of `with` at module level and inside (nested) functions, differential tested against CPython. |
| **Object-Oriented Programming** | **T1** | `class Name(Bases):`, `__init__`, dynamic instance fields, bound methods, single/multiple inheritance, `super()` (0-arg and 2-arg), version-guarded inline attribute caches. |
| **Exception Handling & `finally` Unwinding** | **T2** | `try / except / else / finally`. Supports class hierarchy matching, exception tuples `except (A, B) as e`, PEP 3110 deletion on normal/jump exits, `return`/`break`/`continue`/`raise` unwinding through nested `finally` and `with` blocks, `finally` control-flow override semantics, operand stack restoration (`stackDepth`), `raise X from Y` / `from None`, and Python-readable `__context__`, `__cause__`, `__suppress_context__`, `args`, `__class__`. Differential tested against CPython. |
| **Generators** | **T1** | Functions with `yield` produce `PyGenerator` supporting `for` loops and `list()`/`sum()`/`sorted()`/`reversed()`. Coroutine `.send()` / `.throw()` are not yet implemented. |
| **execfile()** | **T1** | Direct execution of external Python source files within current scope (gated by `HostAccessPolicy`). |
| **Async / Await** | **T0** | Reserved for future concurrency phase (Java Virtual Threads). |

---

## Standard Library Modules

| Module | Stage | Implementation & Verification Notes |
| :--- | :---: | :--- |
| **`math`** | **T1** | `sqrt`, `sin`, `cos`, `tan`, `log`, `floor`, `ceil`, `abs`, `pow`, `pi`, `e`, `inf` |
| **`sys`** | **T1** | `argv`, `version`, `platform`, `maxsize`, `exit(code)` |
| **`os`** | **T1** | `getcwd()`, `getpid()`, `sep`, `pathsep`, `name`, `environ`, `listdir(path)` (gated by `HostAccessPolicy`) |
| **`io`** | **T1** | `open(file, mode)` supporting `'r'`, `'w'`, `'a'`, `.read()`, `.write()`, `.close()` (gated by `HostAccessPolicy`) |
| **`json`** | **T2** | `dumps(obj)`, strict RFC 8259 `loads(json_str)` supporting nested objects/arrays, escapes, `BigInt`s, and raising `json.JSONDecodeError` (subclass of `ValueError`) |
| **`re`** | **T1** | `search(pattern, str)`, `findall(pattern, str)` backed by `java.util.regex` |
| **`time`** | **T2** | `time()`, `time_ns()`, `sleep(secs)`, `monotonic()`, `perf_counter()` |
| **`random`** | **T2** | `random()`, `randint(a, b)`, `choice(seq)`, `seed(n)` (per-instance isolation) |
