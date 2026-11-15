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
| **Lists** | **T2** | Dynamic mutable `PyList` with `maxCollectionSize` bounds. Indexing, slicing, slice assignment (`xs[1:4] = ...`), slice deletion (`del xs[::2]`), `.append()`, `.extend()`, `.insert()`, `.pop()`, `.remove()`, `.index()`, `.count()`, `.sort()`, `.reverse()`, `.copy()`, `.clear()`. |
| **Dictionaries** | **T2** | Insertion-ordered `PyDict` implementing Python 3 key semantics (`1 == 1.0 == True`, `(1, 2)` ≡ `(1.0, 2)`, `BigInt` ≡ integral `float`) for lookup, `in`, literals (later duplicate keys overwrite the value and keep the first key), and `==`/`!=`; unhashable lists/dicts/sets raise `TypeError`. Differential tested against CPython. |
| **Tuples & Unpacking** | **T2** | Immutable `PyTuple` distinct from `PyList` (`.count()`, `.index()`), sequence unpacking `a, b = seq`, PEP 3132 extended starred unpacking (`a, *mid, b = seq`, `for a, *rest in rows:`), PEP 448 literal/call unpacking (`[*a, *b]`, `(*a, *b)`, `{*a, *b}`, `{**d1, **d2}`, `f(*a, *b, **d1, **d2)`), `return a, b`, and `except (E1, E2)` tuple matching. |
| **Sets & `frozenset`** | **T2** | Mutable `PySet` and immutable hashable `PyFrozenSet` keyed by `PyKey` (same hash/eq contract as dict keys: `{1, 1.0, True}` has one element, `{1, 2} == frozenset([1.0, 2])`), set literals, set comprehensions, `set()`/`frozenset(iterable)`, `len`, `in`, iteration (`for x in s`), `==`/`!=`, partial-order `<`/`<=`/`>`/`>=`, binary `\|`, `&`, `-`, `^` (preserving left operand's type), true in-place `\|=`/`&=`/`-=`/`^=` on `set`, `isinstance(x, set/frozenset)`, and methods `copy`, `union`, `intersection`, `difference`, `symmetric_difference`, `issubset`, `issuperset`, `isdisjoint` (plus `add`/`remove`/`discard`/`pop`/`clear`/`update` on `set`); unhashable elements raise `TypeError`. Iteration order is insertion order (CPython uses hash order). |
| **Comprehensions & Generator Expressions** | **T2** | Multi-clause list (`[(x, y) for x in a for y in b if cond]`), dict (`{k: v for k, v in ...}`), set (`{x for ...}`), and lazy generator expressions (`(x * 2 for x in seq)`, `sum(x for x in seq)`, nested `for`/`if`, tuple & starred unpacking, closures, lambdas; outer iterable evaluated once at creation), differential tested against CPython. |
| **Slicing** | **T2** | List, tuple, and string slicing (`s[a:b:c]`, `s[::-1]`), list slice assignment/deletion, implementing CPython `slice.indices()` semantics and Unicode codepoint safety. |
| **Control Flow** | **T2** | `if` / `elif` / `else`, `while ... else`, `for ... in ... else`, `break`, `continue`, `assert`, chained assignment (`a = b = expr`), chained comparisons (`a < b < c`, `0 == 0 == 0`, `1 in [1, 2] == True`), and Python `not` precedence (`not a == b`), differential tested against CPython. |
| **Functions & Scope** | **T2** | `def`, `lambda`, default arguments, PEP 570 positional-only `/`, PEP 3102 keyword-only `*` / `*args, kw`, `*args`, `**kwargs`, call-site `*args`/`**kwargs` unpacking, keyword calls, lexical closures, `global`, `nonlocal` via `PyCell`, strict Execution Model §4.2.2 `UnboundLocalError` for read-before-assign / post-`del` locals, decorators. |
| **Context Managers (`with`)** | **T2** | Single and multi-item `with expr [as id], ...:` executing `__enter__` and `__exit__` (receiving real `exc_type, exc_val, exc_tb`) on normal, suppressed, and exceptional exits, including `return`/`break`/`continue`/`raise` out of `with` at module level and inside (nested) functions, differential tested against CPython. |
| **Object-Oriented Programming & Types** | **T2** | `class Name(Bases):`, `__init__`, dynamic instance fields, bound methods, single/multiple inheritance with C3 MRO (`__mro__`, dynamic `__bases__` mutation), `super()` (0-arg and 2-arg), `@property`/`@staticmethod`/`@classmethod`, full dunder operator overloading, dynamic 3-arg `type()`, version-guarded inline attribute caches, unified `type(obj)` / `__class__` / `__name__` / `isinstance` / `issubclass` (`issubclass(bool, int)`, `object`), `hasattr`/`getattr`/`setattr`/`delattr`/`vars`/`dir`/`callable`, differential tested against CPython. |
| **Exception Handling & `finally` Unwinding** | **T2** | `try / except / else / finally`. Supports class hierarchy matching, exception tuples `except (A, B) as e`, PEP 3110 deletion on normal/jump/raise exits, `return`/`break`/`continue`/`raise` unwinding through nested `finally` and `with` blocks, `finally` control-flow override semantics, operand stack restoration (`stackDepth`), `raise X from Y` / `from None`, Python-readable `__traceback__` (`tb_next`, `tb_lineno`, `tb_frame.f_code`), `__context__`, `__cause__`, `__suppress_context__`, `args`, `__class__`, and chained traceback rendering. Differential tested against CPython. |
| **Generators** | **T2** | True lazy frame suspension/resume across `yield` and `yield` expressions (`x = yield y`), PEP 380 `yield from` delegation (iterables, sub-generators, return value capture, `.send()`/`.throw()`/`.close()` forwarding), PEP 479 (`StopIteration` inside generator -> `RuntimeError` with `__cause__`), coroutine `.send()`, `.throw()`, and `.close()` (`GeneratorExit`), `PyGenerator` states (`CREATED`/`RUNNING`/`SUSPENDED`/`COMPLETED`/`FAILED`), infinite generators, `iter(g) is g`, `return` / `StopIteration.value`, `try/finally`/`with`/`except` preservation, full `__traceback__` & `__context__`/`__cause__`, `ExecutionLimits`/cancellation/recursion enforcement, frame cleanup, and lazy `zip`/`all`/`any`/`for`. Differential tested against CPython. |
| **Module Import System** | **T2** | Policy-aware, symlink-safe `ModuleLoader` supporting dotted packages (`__init__.py` boundaries), `.sikuli` bundles, `importlib.reload`, `import a.b.c [as x]`, `from a.b import c [as d]`, `from mod import *` (`__all__`), `sys.path`/`sys.modules`, `__name__`/`__file__`/`__package__`, circular import handling, and pure-Python `collections` & `functools` modules (subject to `HostAccessPolicy`). |
| **execfile()** | **T1** | Direct execution of external Python source files within current scope (gated by `HostAccessPolicy`). |
| **Async / Await (PEP 492)** | **T2** | Native `async def` coroutines (`PyCoroutine`), `await`, `async for` (`__aiter__`/`__anext__`/`StopAsyncIteration`), `async with` (`__aenter__`/`__aexit__` including `return`/`break`/`continue`), `__await__` protocol, static `SyntaxError` checks, and PEP 479 inside coroutines. Differential tested against CPython. |

---

## Standard Library Modules

| Module | Stage | Implementation & Verification Notes |
| :--- | :---: | :--- |
| **`math`** | **T2** | `sqrt`, `sin`, `cos`, `tan`, `log`, `log2`, `log10`, `exp`, `atan2`, `hypot`, `degrees`, `radians`, `floor`, `ceil`, `abs`, `pow`, `gcd`, `lcm`, `factorial`, `comb`, `perm`, `isnan`, `isinf`, `isfinite`, `pi`, `e`, `tau`, `inf`, `nan` |
| **`sys`** | **T1** | `argv`, `path`, `modules`, `stdout`, `stderr`, `version`, `platform`, `maxsize`, `exit(code)` |
| **`os`** | **T1** | `getcwd()`, `getpid()`, `sep`, `pathsep`, `name`, `environ`, `listdir(path)` (gated by `HostAccessPolicy`) |
| **`io`** | **T1** | `open(file, mode)` supporting `'r'`, `'w'`, `'a'`, context manager (`with`), `.read()`, `.write()`, `.close()` with symlink & size-cap enforcement (gated by `HostAccessPolicy`) |
| **`json`** | **T2** | `dumps(obj, indent=..., sort_keys=...)` with cycle/depth guards, strict RFC 8259 `loads(json_str)` supporting nested objects/arrays, escapes, `BigInt`s, and raising `json.JSONDecodeError` (subclass of `ValueError`) |
| **`re`** | **T2** | ReDoS-hardened `Match`, `Pattern`, `search`, `match`, `fullmatch`, `findall`, `sub`, `split`, `compile`, `escape`, regex flags |
| **`time`** | **T2** | `time()`, `time_ns()`, `sleep(secs)`, `monotonic()`, `perf_counter()` |
| **`random`** | **T2** | `random()`, `randint(a, b)`, `choice(seq)`, `seed(n)` (per-instance isolation) |
| **`itertools`** | **T2** | Lazy limit-checked `count`, `cycle`, `repeat`, `chain`, `islice`, `takewhile`, `dropwhile`, `compress`, `accumulate`, `zip_longest`, `product`, `permutations`, `combinations` |
| **`datetime`** | **T2** | `date`, `time`, `datetime`, `timedelta` |
| **`queue`** | **T2** | `Queue`, `LifoQueue`, `PriorityQueue`, `Empty`, `Full` |
| **`asyncio`** | **T2** | Cooperative event loop: `run`, `sleep`, `gather`, `create_task`, `wait_for`, `Task` (`done`, `cancelled`, `cancel`, `result`, `exception`, `get_name`, `add_done_callback`, `remove_done_callback`), `Queue`, `Event`, `Lock`, `current_task`, `all_tasks`, `iscoroutine`, `iscoroutinefunction`, `CancelledError`, `InvalidStateError` |
| **`threading`** | **T1** | Capability-gated (`HostCapability.THREADS`) Java 21 Virtual Threads: `Thread`, `Lock`, `RLock`, `Event`, `Semaphore`, `current_thread`, `active_count` |
| **`concurrent.futures`** | **T1** | Capability-gated (`HostCapability.THREADS`) Virtual-Thread `ThreadPoolExecutor`, `Future` (`result`, `exception`, `cancel`, `cancelled`, `running`, `done`), `as_completed` |
| **`ui`** | **T1** | Capability-gated `Window`, `Button`, `Label`, `TextField`, `Panel`, `VBox`, `HBox`, `Grid`, `CheckBox`, `ProgressBar`, `ListView`, `Timer`, `Event` via opaque `PyHostHandle`s over `HeadlessUiToolkit`, `SwingUiToolkit` (EDT), and `JavaFxUiAdapter` (FX Application Thread) |
| **`control`** | **T1** | Capability-gated `Controller`, `Command`, `State`, `Event`, `Timer` over host-attested `ControlRegistry` / `SimulatedDevice` / `JvmTelemetryAdapter` / `HostCommandChannelAdapter` / `ScopedWorkspaceAdapter` with per-device/command allowlists and `HostAuditLog` |
