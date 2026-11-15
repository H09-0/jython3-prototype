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
- **Numeric & Boolean Semantics**: Signed 32-bit `int` (with `-128..256` cache and automatic `BigInt` promotion), hex/binary/octal literals (`0x`, `0b`, `0o`), IEEE-754 `float`, true division `/`, exact floor division `//` and `%`, bitwise & shift operations (`&`, `|`, `^`, `~`, `<<`, `>>`, `&=`, `|=`, `^=`, `<<=`, `>>=`), and `bool` subclassing `int` (`True + True == 2`, `True & False == False`).
- **Strings**: UTF-8 / codepoint-safe `PyStr` (with non-surrogate ASCII/BMP fast-path), triple-quoted strings, raw strings (`r"..."`, `r'...'`), f-strings (`f"..."`), `%` formatting, and full Unicode slicing (`s[a:b:c]`).
- **Collections**: Mutable `PyList`, immutable `PyTuple`, insertion-ordered `PyDict` (with `1 == 1.0 == True` key semantics and zero-allocation lookup key), `PySet` (keyed by `PyKey`, hashability enforcement, iteration, `add`/`remove`/`discard`/`pop`/`clear`/`copy`/`union`/`intersection`/`difference`/`symmetric_difference`/`issubset`/`issuperset`/`isdisjoint`/`update`, bitwise `|`/`&`/`-`/`^` and compound `|=`/`&=`/`-=`/`^=`, partial-order `<`/`<=`/`>`/`>=`), and immutable hashable `PyFrozenSet` (`frozenset()`, usable as dict keys / set elements).
- **Comprehensions & Generator Expressions**: List (`[x for x in seq if cond]`), dict (`{k: v for ...}`), set (`{x for ...}`), and lazy generator expressions (`(x * 2 for x in seq if cond)`, `sum(x for x in seq)`, nested `for`/`if`, tuple target unpacking, lambdas, closures).
- **Functions & Scope**: `def`, `lambda`, default parameters, `*args`, `**kwargs`, call-site `*args`/`**kwargs` unpacking (`CALL_FUNCTION_EX`), keyword calls, lexical closures, `global`, `nonlocal` via `PyCell`, decorators, and true lazy suspended generators (`PyGenerator` states `CREATED`/`RUNNING`/`SUSPENDED`/`COMPLETED`/`FAILED`, frame suspension/resume across `yield` & `yield` expressions, PEP 380 `yield from` delegation with return value forwarding, `.send()`, `.throw()`, `.close()` with `GeneratorExit` cleanup, `try/finally`/`with` cleanup, `StopIteration.value`, and `ExecutionLimits`/cancellation enforcement).
- **Assignments & Unpacking**: Unified `LValue` model (`NameTarget`, `AttributeTarget`, `SubscriptTarget`, `TupleTarget`) supporting chained assignment (`a = b = obj.x = expr`), tuple/sequence unpacking (`a, b = pair`, `for a, b in pairs:`), `del` (`del x`, `del obj.attr`, `del items[0]`), and single-evaluation augmented assignment (`x += 1`, `obj.value += 1`, `items[0] += 1`, `matrix[0][1] *= 2`) with true in-place mutation (`INPLACE_*`) for `list` (`+=`, `*=`) and `set` (`|=`, `&=`, `-=`, `^=`) preserving aliases.
- **Control Flow & Exceptions**: `if`/`elif`/`else`, `while`, `for` (with tuple target unpacking), `break`/`continue` (with compile-time `SyntaxError` if outside loop), `assert`, chained comparisons (`1 < x < 10`, `0 == 0 == 0`, `1 in [1, 2] == True` with single-evaluation and short-circuiting), `not` lower-than-comparison precedence, `with` context managers (single and multi-item `with a as x, b as y:`, `__enter__`/`__exit__` receiving real `exc_tb`, including `return`/`break`/`continue`/`raise` out of `with` inside functions), and full `try`/`except (A, B) as e`/`else`/`finally` unwinding across `return`, `break`, `continue`, and `raise` (preserving operand stack depth `ExceptHandler.stackDepth`, Python-readable `__traceback__` with `tb_next`/`tb_lineno`/`tb_frame.f_code`, `__context__`/`__cause__`/`__suppress_context__`/`args`/`__class__`, `raise X from Y` / `from None`, and chained traceback rendering).
- **Compiler temporaries & Debugging**: Synthetic slots (`$ret_val_N`, `$comp_list_N`, `$comp_dict_N`, `$comp_set_N`) use a `$` prefix that the lexer never accepts in identifiers, so they cannot collide with user variables, are deleted after use, and are excluded from closure capture. `BytecodeDisassembler` and `BytecodeValidator` verify opcode, pool, jump-target, and stack-depth invariants.
- **Re-entrant calls**: Python functions invoked from inside the VM (`__init__`, `__enter__`, `__exit__`, Java functional-interface callbacks) run in a nested loop bounded by `baseDepth`; they never return into or unwind through the caller's frames, and caller state is restored even when the callee raises. Every nested loop (re-entrant calls, class bodies, generator draining, isolated module execution) counts toward `MAX_CALL_DEPTH` (500) and raises `RecursionError`; any JVM `StackOverflowError` on small host stacks or deep Java↔Python callback chains is converted to `RecursionError` and never reaches the embedder. Class bodies run with their own handler stack.
- **OOP & Type Identity**: Classes, single & multiple inheritance, `super()` (0-arg and 2-arg), bound methods, `__name__` on classes/types/functions/builtins/methods, `__class__` on instances/exceptions/primitives/collections/functions, unified callable built-in type objects (`type(1) is int`, `type("x") is str`, `type(ValueError()) is ValueError`, `type(C()) is C`, `type(int) is type`, `isinstance(True, int)`, `issubclass(bool, int)`, `object`), `hasattr`/`getattr`/`setattr`/`delattr`/`vars`/`dir`/`callable`, and version-guarded monomorphic inline caching (`LOAD_ATTR`) with hierarchy invalidation.
- **Built-ins & Module Import**: Core built-ins (`len`, `str`, `print`, `int`, `float`, `bool`, `type`, `object`, `abs`, `min`, `max`, `sum`, `list`, `set`, `dict`, `tuple`, `range`, `sorted`, `reversed`, `iter`, `next`, `enumerate`, `zip`, `map`, `filter`, `all`, `any`, `callable`, `repr`, `hash`, `id`, `isinstance`, `issubclass`, `hasattr`, `getattr`, `setattr`, `delattr`, `vars`, `dir`, `chr`, `ord`, `hex`, `oct`, `bin`, `round`, `input`). Policy-aware, symlink-safe `ModuleLoader` supporting dotted packages (`__init__.py` package boundaries), aliases, `from ... import *` (`__all__`), `sys.path`/`sys.modules`, `__name__`/`__file__`/`__package__`, circular import handling, and `HostAccessPolicy` enforcement.

### Known Language Gaps
- Async/await (`async def`, `await`).
- A suspended generator dropped without `.close()`, completion, or exception does not run its `finally` block on GC (no JVM finalizers).
- PEP 3110 (`except E as e:`) deletes `e` on normal/`return`/`break`/`continue` exits, not when the `except` body exits via `raise` without `finally`.
- `type()` 3-argument dynamic class creation `type(name, bases, dict)` is not implemented; `isinstance`/`issubclass` on non-type sentinel types like `JavaObject(...)` uses string type names.
- Built-ins not yet implemented: `slice()`, `property()`, `staticmethod()`, `classmethod()`, `eval()`, `exec()`, `compile()`, `open()` as a global builtin (use `io.open`).
- Re-entrant calls (`__init__`, `__enter__`, `__exit__`, Java callbacks) do not push a `Frame`, so they are invisible to `nonlocal` lookup and do not appear as separate frames in `traceback` (they are counted toward `MAX_CALL_DEPTH`).
- Dynamic `__bases__` mutation after class creation.
- **Security model boundary**: In-process `JavaAccessPolicy` and `HostAccessPolicy` provide defense-in-depth gating, not a complete sandbox against adversarial host-JVM / native / side-channel escapes. Run untrusted code inside OS process/container isolation.

### Standard Library Modules
- Java-backed built-in modules: `math`, `sys` (`argv`, `path`, `modules`, `version`, `platform`, `maxsize`, `exit`), `os` (policy-gated), `io` (policy-gated), `json` (strict RFC 8259 parser raising `JSONDecodeError`, cycle detection), `re`, `time`, `random`, `ui` (capability-gated), `control` (capability-gated).
- Pure-Python modules (`modules/`): `collections` (`Counter`, `defaultdict`, `deque`), `functools` (`reduce`, `partial`, `lru_cache`) — subject to `HostAccessPolicy.canImportFromDisk`.

### Host Capabilities, Embedded UI & Control Layer
- **Versioned Public Embedding API (`org.example.jython3.api`)**: `EmbeddedRuntime` and `RuntimeVersion` (`1.0.0-beta`) providing a stable, version-negotiated facade for Oculix / Cinnamon with feature probes (`supportsFeature`), safe model/handle binding, cancellation, restart, and audit inspection.
- **Host Capabilities, Embedded UI & Control Layer (`org.example.jython3.host`, `.ui`, `.control`)**: `HostCapability` (`UI`, `EVENTS`, `INPUT`, `TIMER`, `FILESYSTEM`, `PROCESS`, `NETWORK`, `CONTROL`), immutable `CapabilitySet` (per-device/per-command allowlists, centralized `maxWindows`, `maxTimers`, `maxListenersPerNode`, `maxCallbackDepth`, `callbackTimeout` with watchdog enforcement), bounded `HostAuditLog`, and `HostSession` bound to `ExecutionContext` with session-epoch handle invalidation and deterministic teardown on `resetExecutionState()` / `close()`.
- **Reflection-free opaque handles (`PyHostHandle`)**: Exposes only whitelisted properties and methods to Python; never exposes raw JVM objects, `Class.forName`, or reflection; operations on stale handles after reset/close raise `RuntimeError`.
- **UI Layouts, Basic Widgets & Event Dispatch (`org.example.jython3.ui`)**: `UiToolkit` and `UiDispatcher` abstractions (`Window`, `Button`, `Label`, `TextField`, `Panel` with `FLOW`/`VBOX`/`HBOX`/`GRID` layouts, `VBox`, `HBox`, `Grid`, `CheckBox`, `ProgressBar`, `ListView`, `Timer`, `Event`) with `HeadlessUiToolkit` (immutable thread ownership, CI-safe), `SwingUiToolkit` (EDT-safe), and `JavaFxUiAdapter` (`FxDispatcher` for JavaFX Application Thread dispatch with bounded sync timeout and off-thread mutation rejection). Async/timer callback failures stop the timer, record into `HostAuditLog`, and notify `HostSession` error handlers.
- **Control & Real Host Adapters (`org.example.jython3.control`)**: `ControlRegistry`, `ControlDevice`, `SimulatedDevice`, `JvmTelemetryAdapter`, `HostCommandChannelAdapter`, `ScopedWorkspaceAdapter` (dual-gated `CONTROL` + `FILESYSTEM` with `toRealPath()` containment and audit logging), and `control` module (`Controller`, `Command`, live `State` view + `.snapshot()`, `Event`, `Timer`).
- **Host Embedding Bridge (`HostAppBridge`)**: Safe model data conversion + `PyHostHandle` binding (strictly session-bound + epoch-checked), mid-event script cancellation, clean interpreter restarts, `TrustTier.HARDWARE` explicit opt-in, `SecureDirectoryStream` workspace TOCTOU protection, and formal security review ([SECURITY_REVIEW_HOST_CAPABILITIES.md](SECURITY_REVIEW_HOST_CAPABILITIES.md); 508 tests passing green).

### Performance & Java Bridge
- Array-backed `FastStack` + pooled frame stacks/handler deques, pre-boxed immutable `CompiledCode.pyConstPool`, small-int caching, zero-overhead positional `bindArgs` fast path, precomputed `hasYield` flag.
- Version-guarded monomorphic inline caching for `LOAD_ATTR` and `PyInstance.boundMethodCache`.
- `MethodHandle` (`publicLookup().unreflect` + `asFixedArity().asSpreader(...)`) dispatch in `JavaBridge` with policy-scoped, capacity-bounded overload/constructor resolution caching, varargs support, public interface resolution for non-public JDK classes (e.g. `Arrays$ArrayList`), functional-interface callback proxies, and pre-compiled `TypeConverter` plans.
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
