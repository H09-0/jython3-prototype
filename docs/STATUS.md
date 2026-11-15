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
| **`docs/EMBEDDED_INTEGRATION_CONTRACT.md`** | Stable host embedding, capability, UI/control, and lifecycle contract (Integration Beta). |
| **`CHANGELOG.md`** | Historical record of changes per release. |
| **`README.md`** | Project overview, quickstart commands, and links (no duplicated metrics). |
| **`docs/ARCHITECTURE.md`** | Pipeline design, layer boundaries, bytecode format, and VM invariants. |

---

## 2. Verified Current Capabilities

### Core Language & Syntax
- **Numeric & Boolean Semantics**: Signed 32-bit `int` (with `-128..256` cache and automatic `BigInt` promotion), underscore-separated literals (`1_000_000`, `0xFF_FF`, `0b1010_0101`), hex/binary/octal literals (`0x`, `0b`, `0o`), IEEE-754 `float` with scientific notation (`1.5e-3`), true division `/`, exact floor division `//` and `%`, bitwise & shift operations (`&`, `|`, `^`, `~`, `<<`, `>>`, `&=`, `|=`, `^=`, `<<=`, `>>=`), and `bool` subclassing `int` (`True + True == 2`, `True & False == False`).
- **Strings, F-Strings & Bytes**: UTF-8 / codepoint-safe `PyStr` (with non-surrogate ASCII/BMP fast-path), full escapes (`\xHH`, `\uHHHH`, `\UHHHHHHHH`, `\0..`, `\a\b\f\v`), triple-quoted strings, raw strings (`r"..."`), `u"..."` strings, f-strings (`f"..."` with `!r`/`!s` conversions and `:spec` format mini-language), `%` formatting, `.format()` / `format()`, and immutable `bytes` (`b"..."`) + mutable unhashable `bytearray` (with `.decode()`, `.encode()`, `.hex()`, `.fromhex()`, slicing, bounded repetition/concat).
- **Collections & Lazy Ranges**: Mutable `PyList` (`sort(key=..., reverse=...)`), immutable `PyTuple`, insertion-ordered `PyDict` (with `1 == 1.0 == True` key semantics, `|`/`|=`, `setdefault`, `popitem`, `fromkeys`), `PySet` and `PyFrozenSet` (keyed by `PyKey`), and O(1)-memory lazy `PyRange` (arbitrary-precision bounds, O(1) `in`/`len`/indexing/slicing/`reversed`/`hash`/`==`).
- **Comprehensions, Walrus & Generator Expressions**: List, dict, and set comprehensions, lazy generator expressions, and PEP 572 walrus assignment expressions (`:=`).
- **Functions, Scope & Generators**: `def`, `lambda`, default parameters, `*args`, `**kwargs`, call-site `*args`/`**kwargs` unpacking (`CALL_FUNCTION_EX`), keyword calls, lexical closures (including classes defined inside functions), `global`, `nonlocal` via `PyCell`, strict Execution Model §4.2.2 `UnboundLocalError` for read-before-assign / post-`del` locals, decorators (evaluated before binding for `@prop.setter`), and true lazy suspended generators (`PyGenerator`, PEP 380 `yield from`, PEP 479 `StopIteration` -> `RuntimeError` conversion, `.send()`, `.throw()`, `.close()`, `try/finally`/`with` cleanup, `StopIteration.value`, and `ExecutionLimits`).
- **Native Coroutines & Cooperative `asyncio` (PEP 492)**: `async def`, `await`, `async for` (`__aiter__`/`__anext__`/`StopAsyncIteration`), `async with` (`__aenter__`/`__aexit__`, including clean exit on `return`/`break`/`continue`), distinct `PyCoroutine` type (non-iterable, `GET_AWAITABLE`/`AWAIT_FROM`/`GET_AITER`), and cooperative single-threaded `asyncio` module (`run`, `sleep`, `gather`, `create_task`, `wait_for`, `Task`, `Queue`, `Event`, `Lock`, `CancelledError`, `InvalidStateError`, `ExecutionLimits` & `MAX_TASKS` enforcement).
- **Assignments & Unpacking**: Unified `LValue` model (`NameTarget`, `AttributeTarget`, `SubscriptTarget`, `TupleTarget`), tuple/sequence unpacking, `del`, and single-evaluation augmented assignment (`+=`..`>>=`) with true in-place `INPLACE_*` ops.
- **Control Flow & Exceptions**: `if`/`elif`/`else`, `while`, `for`, `break`/`continue`, `assert`, chained comparisons, `with` context managers (including `PyJavaObject` context managers and `io.open`), and full `try`/`except (A, B) as e`/`else`/`finally` unwinding with complete PEP 3110 deletion on all exits (including `raise` without `finally`), `__traceback__`, `__context__`, `__cause__`, `__suppress_context__`, and `raise X from Y`.
- **Compiler temporaries & Debugging**: Synthetic `$`-prefixed temporaries, `BytecodeDisassembler`, `BytecodeValidator`, and compile-time slot rewriting (`LOAD_SLOT`/`STORE_SLOT`) for pure local functions.
- **Re-entrant calls**: Nested `runNested()` calls (`__init__`, `__enter__`, `__exit__`, Java callbacks) bounded by `MAX_CALL_DEPTH` (500), converting JVM `StackOverflowError` to `RecursionError`.
- **OOP, C3 MRO, Descriptors & Dunder Overloading**: Classes, single & multiple inheritance with C3 Linearization (`__mro__`, `__bases__` including dynamic `__bases__` mutation with cycle/MRO validation), cooperative diamond `super()`, dynamic 3-arg `type(name, bases, dict)`, `@property` (getter/setter/deleter), `@staticmethod`, `@classmethod`, full dunder operator overloading (`__add__`/`__radd__`, `__sub__`/`__rsub__`, `__mul__`/`__rmul__`, `__truediv__`, `__floordiv__`, `__mod__`, `__pow__`, `__neg__`, `__eq__`, `__lt__`, `__hash__`, `__bool__`, `__len__`, `__getitem__`, `__setitem__`, `__delitem__`, `__contains__`, `__iter__`, `__next__`, `__call__`, `NotImplemented`), unified callable type objects, `hasattr`/`getattr` (`__getattr__` delegation)/`setattr`/`delattr`/`vars`/`globals`/`dir`/`callable`, and version-guarded monomorphic inline caching.
- **Built-ins & Module Import**: Complete core built-ins (`len`, `str`, `print`, `int`, `float`, `bool`, `bytes`, `bytearray`, `type`, `object`, `slice`, `property`, `staticmethod`, `classmethod`, `abs`, `pow`, `divmod`, `min`, `max`, `sum`, `list`, `set`, `frozenset`, `dict`, `tuple`, `range`, `sorted`, `reversed`, `iter`, `next`, `enumerate`, `zip`, `map`, `filter`, `all`, `any`, `callable`, `repr`, `format`, `hash`, `id`, `isinstance`, `issubclass`, `hasattr`, `getattr`, `setattr`, `delattr`, `vars`, `globals`, `dir`, `chr`, `ord`, `hex`, `oct`, `bin`, `round`, `input`). Policy-aware, symlink-safe `ModuleLoader` supporting dotted packages, `.sikuli` bundles, `importlib.reload`, `addImportListener`, parenthesized `from ... import (...)`, `sys.path`/`sys.modules`, and `HostAccessPolicy` enforcement.

### Known Language Gaps
- A suspended generator or coroutine dropped without `.close()`, completion, or exception does not run its `finally` block on GC (no JVM finalizers).
- Global `eval()`, `exec()`, `compile()`, and global `open()` (use `io.open`).
- Re-entrant calls (`__init__`, `__enter__`, `__exit__`, Java callbacks) do not push a `Frame`, so they are invisible to `nonlocal` lookup and do not appear as separate frames in `traceback` (they are counted toward `MAX_CALL_DEPTH`).
- **Security model boundary**: In-process `JavaAccessPolicy`, `HostAccessPolicy`, and `CapabilitySet` provide defense-in-depth gating, not a complete OS sandbox against adversarial host-JVM / native / side-channel escapes. Run untrusted code and physical hardware inside OS process/container isolation.

### Standard Library & Concurrency Modules
- Java-backed built-in modules: `math` (`gcd`, `lcm`, `factorial`, `comb`, `perm`, `log2`, `log10`, `exp`, `atan2`, `hypot`, `degrees`, `radians`, `isnan`/`isinf`/`isfinite`, `tau`, `nan`), `sys` (`argv`, `path`, `modules`, `stdout`/`stderr` redirection, `version`, `platform`, `maxsize`, `exit`), `os` (policy-gated including `getcwd`/`getpid`/`listdir`/`environ`), `io` (policy-gated `open` with context manager + append-after-open), `json` (strict RFC 8259 parser, `indent`/`sort_keys`, depth limit 512, cycle detection), `re` (ReDoS-hardened `Match`/`Pattern`/`search`/`match`/`fullmatch`/`findall`/`sub`/`split`/`compile`/`escape` + flags), `time`, `random`, `itertools` (lazy limit-checked iterators + bounded combinatorics), `datetime` (`date`, `time`, `datetime`, `timedelta`), `queue` (`Queue`, `LifoQueue`, `PriorityQueue`, `Empty`, `Full`), `asyncio` (cooperative event loop: `run`, `sleep`, `gather`, `create_task`, `wait_for`, `Task`, `Queue`, `Event`, `Lock`, `CancelledError`), `threading` (capability-gated `HostCapability.THREADS` Java 21 Virtual Threads: `Thread`, `Lock`, `RLock`, `Event`, `Semaphore`, `current_thread`, `active_count`), `concurrent.futures` (capability-gated Virtual-Thread `ThreadPoolExecutor`, `Future`, `as_completed`), `ui` (capability-gated), `control` (capability-gated).
- Pure-Python modules (`modules/`): `collections` (`Counter`, `defaultdict`, `deque`, `OrderedDict`, `namedtuple`), `functools` (`reduce`, `partial`, `lru_cache`, `wraps`, `cmp_to_key`) — subject to `HostAccessPolicy.canImportFromDisk`.

### Host Capabilities, Embedded UI & Control Layer
- **Versioned Public Embedding API (`org.example.jython3.api`)**: `EmbeddedRuntime` and `RuntimeVersion` (`1.0.0-beta`) providing a stable, version-negotiated facade for Oculix / Cinnamon with feature probes (`supportsFeature`), safe model/handle binding, attested device registration, cancellation, restart, and audit inspection.
- **Host Capabilities, Embedded UI & Control Layer (`org.example.jython3.host`, `.ui`, `.control`)**: `HostCapability` (`UI`, `EVENTS`, `INPUT`, `TIMER`, `FILESYSTEM`, `PROCESS`, `NETWORK`, `CONTROL`, `THREADS`), immutable `CapabilitySet` (per-device/per-command/per-origin allowlists, `allowHardwareAdapters`, centralized `maxWindows`, `maxTimers`, `maxListenersPerNode`, `maxCallbackDepth`, `maxThreads`, `maxThreadPools`, `maxQueuedTasks`, `executorShutdownTimeout`, `callbackTimeout` with watchdog enforcement), bounded `HostAuditLog`, and `HostSession` bound to `ExecutionContext` with session-epoch handle invalidation and deterministic teardown on `resetExecutionState()` / `close()`.
- **Reflection-free opaque handles (`PyHostHandle`)**: Always session-bound and epoch-checked; exposes only whitelisted properties and methods to Python; never exposes raw JVM objects, `Class.forName`, or reflection; operations on stale handles after reset/close raise `RuntimeError`.
- **UI Layouts, Basic Widgets & Event Dispatch (`org.example.jython3.ui`)**: `UiToolkit` and `UiDispatcher` abstractions (`Window`, `Button`, `Label`, `TextField`, `Panel` with `FLOW`/`VBOX`/`HBOX`/`GRID` layouts, `VBox`, `HBox`, `Grid`, `CheckBox`, `ProgressBar`, `ListView`, `Timer`, `Event`) with `HeadlessUiToolkit`, `SwingUiToolkit`, and `JavaFxUiAdapter`.
- **Control & Real Host Adapters (`org.example.jython3.control`)**: Host-attested `ControlRegistry` (`HostAttestation`, `seal()`), `ControlDevice`, `SimulatedDevice`, `JvmTelemetryAdapter`, `HostCommandChannelAdapter`, `ScopedWorkspaceAdapter` (dual-gated `CONTROL` + `FILESYSTEM`, POSIX `SecureDirectoryStream` + `NOFOLLOW_LINKS` TOCTOU defense + cross-platform fallback).
- **Host Embedding Bridge (`HostAppBridge`)**: Safe model data conversion + `PyHostHandle` binding, mid-event script cancellation, clean interpreter restarts, formal security/deployment requirements ([SECURITY_REVIEW_HOST_CAPABILITIES.md](SECURITY_REVIEW_HOST_CAPABILITIES.md); **Java 21+ mandatory runtime** for Virtual Threads & `Thread.ofVirtual()`; 570 tests passing green).

### Performance & Java Bridge
- Array-backed `FastStack` + pooled frame stacks/handler deques/local-slot arrays (`LOAD_SLOT`/`STORE_SLOT`), pre-boxed immutable `CompiledCode.pyConstPool`, small-int caching, zero-allocation `CALL_FUNCTION` fast paths, inline `PyClass.__init__`, inline `PyInt` arithmetic/comparison fast paths, `PyIterator.fastNext()` direct `PyRange` loop fast path, precomputed `hasYield` flag.
- Version-guarded monomorphic inline caching for `LOAD_ATTR` and `PyInstance.boundMethodCache`.
- `MethodHandle` dispatch in `JavaBridge` with policy-scoped caching, varargs, functional-interface proxies, `PyJavaObject` context managers, and `PyJavaClass` static field writes.
- Reproducible benchmark suite (`./gradlew benchmark`) and real-world field/leak profiler (`./gradlew fieldTest`).

---

## 3. Core Runtime Concepts Glossary

Understanding how internal Java runtime types map to Python semantics:

| Concept | Java Type | Relationship & Purpose |
| :--- | :--- | :--- |
| **Python `None`** | `PyNull` | Represents Python's `None` object inside the VM stack, locals, and collections. **Distinct from Java `null`** (Java `null` inside `currentLocals` or maps indicates an *unbound* variable, never `None`). |
| **Java `null`** | `null` (reference) | Used internally in Java structures for absent map entries or uninitialized slots. `JavaBridge.toPyObject(null, policy)` converts Java `null` to `PyNull`. `JavaBridge.toJavaObject(PyNull, refType)` converts `PyNull` back to Java `null` for reference types (and rejects `PyNull` with `TypeError` for primitive types). |
| **32-bit Integer** | `PyInt` | Fast 32-bit signed integer (`int value`) with pre-allocated cache for `-128..256` (`PyInt.of`). Automatically promotes to `BigInt` on arithmetic overflow (`Math.addExact`, `subtractExact`, `multiplyExact`, `**`). |
| **Arbitrary-Precision Integer** | `BigInt` | Wraps `java.math.BigInteger` for integers outside 32-bit `int` bounds (`< -2^31` or `> 2^31 - 1`). Shares Python's unified `int` type (`typeName == "int"`, `isinstance(big, int) == True`) and participates seamlessly with `PyInt` and `PyFloat` in arithmetic, hashing (`PyKey`), and exact comparisons. |
| **Callable Bridge** | `PyCaller` | Thread-scoped interface installed on `PyCaller.CURRENT` during `eval()`/`evalCode()` (and removed in `finally` to prevent `ThreadLocal` leaks) allowing built-ins (`sorted(key=...)`, `map`, `filter`, `super()`) and `PyInstance.toString()` to invoke Python callables on the active interpreter. |
| **Closure / Nonlocal Cell** | `PyCell` | Mutable reference box holding a `PyObject` value (or `null` if deleted/unbound). Enables inner functions with `nonlocal` to read and mutate bindings shared across enclosing call frames and closures. |
| **Nested Code Block** | `PyCompiledCodeWrapper` | `PyObject` wrapper carrying a compiled `CompiledCode` for class bodies on the VM operand stack before `BUILD_CLASS` executes them. |
| **Resource & Sandbox Tracker** | `ExecutionContext` | Per-evaluation tracker enforcing `ExecutionLimits` (instruction budget, wall-clock timeout, host cancellation, collection/string pre-allocation caps) across nested calls and imported modules. |
