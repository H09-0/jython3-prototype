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
| **Sets & `frozenset`** | **T2** | Mutable `PySet` and immutable hashable `PyFrozenSet` keyed by `PyKey` (same hash/eq contract as dict keys: `{1, 1.0, True}` has one element, `{1, 2} == frozenset([1.0, 2])`), set literals, set comprehensions, `set()`/`frozenset(iterable)`, `len`, `in`, iteration (`for x in s`), `==`/`!=`, partial-order `<`/`<=`/`>`/`>=`, binary `\|`, `&`, `-`, `^` (preserving left operand's type), true in-place `\|=`/`&=`/`-=`/`^=` on `set`, `isinstance(x, set/frozenset)`, and methods `copy`, `union`, `intersection`, `difference`, `symmetric_difference`, `issubset`, `issuperset`, `isdisjoint` (plus `add`/`remove`/`discard`/`pop`/`clear`/`update` on `set`); unhashable elements raise `TypeError`. Iteration order is insertion order (CPython uses hash order). |
| **Comprehensions & Generator Expressions** | **T2** | List (`[x for x in seq if cond]`), dict (`{k: v for ...}`), set (`{x for ...}`), and lazy generator expressions (`(x * 2 for x in seq)`, `sum(x for x in seq)`, nested `for`/`if`, tuple unpacking, closures, lambdas; outer iterable evaluated once at creation), differential tested against CPython. |
| **Slicing** | **T2** | List, tuple, and string slicing (`s[a:b:c]`, `s[::-1]`) implementing CPython `slice.indices()` semantics and Unicode codepoint safety. |
| **Control Flow** | **T2** | `if` / `elif` / `else`, `while`, `for ... in ...`, `break`, `continue`, `assert`, chained assignment (`a = b = expr`), chained comparisons (`a < b < c`, `0 == 0 == 0`, `1 in [1, 2] == True`), and Python `not` precedence (`not a == b`), differential tested against CPython. |
| **Functions & Scope** | **T2** | `def`, `lambda`, default arguments, `*args`, `**kwargs`, call-site `*args`/`**kwargs` unpacking, keyword calls, lexical closures, `global`, `nonlocal` via `PyCell`, decorators. |
| **Context Managers (`with`)** | **T2** | Single and multi-item `with expr [as id], ...:` executing `__enter__` and `__exit__` (receiving real `exc_type, exc_val, exc_tb`) on normal, suppressed, and exceptional exits, including `return`/`break`/`continue`/`raise` out of `with` at module level and inside (nested) functions, differential tested against CPython. |
| **Object-Oriented Programming & Types** | **T2** | `class Name(Bases):`, `__init__`, dynamic instance fields, bound methods, single/multiple inheritance, `super()` (0-arg and 2-arg), version-guarded inline attribute caches, unified `type(obj)` / `__class__` / `__name__` / `isinstance` / `issubclass` (`issubclass(bool, int)`, `object`), `hasattr`/`getattr`/`setattr`/`delattr`/`vars`/`dir`/`callable`, differential tested against CPython. |
| **Exception Handling & `finally` Unwinding** | **T2** | `try / except / else / finally`. Supports class hierarchy matching, exception tuples `except (A, B) as e`, PEP 3110 deletion on normal/jump exits, `return`/`break`/`continue`/`raise` unwinding through nested `finally` and `with` blocks, `finally` control-flow override semantics, operand stack restoration (`stackDepth`), `raise X from Y` / `from None`, Python-readable `__traceback__` (`tb_next`, `tb_lineno`, `tb_frame.f_code`), `__context__`, `__cause__`, `__suppress_context__`, `args`, `__class__`, and chained traceback rendering. Differential tested against CPython. |
| **Generators** | **T2** | True lazy frame suspension/resume across `yield` (`PyGenerator` states `CREATED`/`RUNNING`/`SUSPENDED`/`COMPLETED`/`FAILED`), infinite generators, `iter(g) is g`, `return` / `StopIteration.value`, `try/finally`/`with`/`except` preservation across suspension, full `__traceback__` & `__context__`/`__cause__`, `ExecutionLimits`/cancellation/recursion enforcement, frame cleanup on completion/failure, and lazy `zip`/`all`/`any`/`for`. Differential tested against CPython. `.send()`, `.throw()`, `.close()`, and `yield from` are not implemented (`NotImplementedError` for `.send`/`.throw`/`.close`). |
| **Module Import System** | **T2** | Policy-aware, symlink-safe `ModuleLoader` supporting dotted packages (`__init__.py` boundaries), `import a.b.c [as x]`, `from a.b import c [as d]`, `from mod import *` (`__all__`), `sys.path`/`sys.modules`, `__name__`/`__file__`/`__package__`, circular import handling, and pure-Python `collections` & `functools` modules (subject to `HostAccessPolicy`). |
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
