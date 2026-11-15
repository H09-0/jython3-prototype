# Changelog

All notable changes to the `jython3-prototype` project are documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.0.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

---

## [v0.12.0-beta] - 2026-11-15
### Added
- **Versioned Public Embedding API (`org.example.jython3.api`)**:
  - Added `EmbeddedRuntime` and `RuntimeVersion` (`1.0.0-beta`) with version compatibility checking, feature flags (`supportsFeature`), and Oculix host compatibility suite (`VersionedEmbeddingApiTest`, `OculixCompatibilitySuiteTest`).
- **Real Capability-Gated Control Adapters (`org.example.jython3.control`)**:
  - Added `JvmTelemetryAdapter` (live JVM memory/thread/uptime telemetry), `HostCommandChannelAdapter` (typed host command routing), and `ScopedWorkspaceAdapter` (dual-gated `CONTROL` + `FILESYSTEM` with `toRealPath()` containment, symlink/traversal rejection, `readOnly` mode, `maxBytes` cap, and audit logging; `RealControlAdaptersTest`).
- **UI Layouts & Basic Widget Abstractions (`org.example.jython3.ui`, `UiModule`)**:
  - Added `UiLayout` (`FLOW`, `VBOX`, `HBOX`, `GRID` + `ui.VBox`, `ui.HBox`, `ui.Grid`) and `UiCheckBox`, `UiProgressBar`, `UiListView` across `HeadlessUiToolkit`, `SwingUiToolkit`, and `JavaFxUiAdapter` (`UiLayoutsAndWidgetsTest`).
- **Security & Field Verification**:
  - Gated `OsModule.getcwd()` and `OsModule.getpid()` behind `HostAccessPolicy` to close host path/PID leaks under `DENY_ALL`.
  - Added `HostSecurityBoundaryReviewTest`, `docs/SECURITY_REVIEW_HOST_CAPABILITIES.md`, and Stress Test D (`200` `EmbeddedRuntime` + layouts + real control adapters cycles) in `FieldTestRunner` (500 tests passing green).

## [v0.11.0-alpha] - 2026-11-14
### Added
- **Capability-Based Host API, Embedded UI & Control Layer (`org.example.jython3.host`, `.ui`, `.control`)**:
  - Added deny-by-default `HostCapability` (`UI`, `EVENTS`, `INPUT`, `TIMER`, `FILESYSTEM`, `PROCESS`, `NETWORK`, `CONTROL`), `CapabilitySet`, `HostAuditLog`, `HostSession`, opaque reflection-free `PyHostHandle`, and `HostAppBridge` for Oculix/Cinnamon embedding (`HostCapabilityTest`, `OculixCinnamonIntegrationTest`).
  - Added `UiToolkit` / `UiDispatcher` abstractions, `HeadlessUiToolkit`, `SwingUiToolkit` (EDT), `JavaFxUiAdapter` (`FxDispatcher`), and `ui` module (`Window`, `Button`, `Label`, `TextField`, `Panel`, `Timer`, `Event`) with thread-affinity checks, callback timeouts, and automatic teardown on `resetExecutionState()` / `close()` (`HeadlessUiTest`).
  - Added `ControlDevice`, `SimulatedDevice`, `ControlRegistry`, and `control` module (`Controller`, `Command`, `State`, `Event`, `Timer`) with per-device/per-command allowlists and audit logging (`HostCapabilitiesSecurityTest`; also fixed `isJavaRootPackage` to cover `sun.*`/`jdk.*` and `HeadlessUiToolkit` `maxTimers` accounting; 475 total tests passing green).
- **PEP 380 `yield from` & Generator `.send()` / `.throw()` / `.close()` (`OpCode.YIELD_FROM`, `Jython3.g4`, `PyGenerator`, `Jython3Interpreter`)**:
  - Added `yield` expressions (`x = yield y`, `(yield y) + 1`) and `yield from expr` delegation across iterables and sub-generators (`YIELD_FROM`), forwarding `.send()`, `.throw()`, `.close()`, and capturing sub-generator `return` values.
  - Implemented `generator.send(val)`, `generator.throw(exc_type[, val])`, and `generator.close()` (`GeneratorExit` unwinding + `RuntimeError: generator ignored GeneratorExit`), with full `BytecodeValidator` and CPython differential verification (`GeneratorControlAndYieldFromTest`).
- **Set Bitwise Operators, Partial Ordering & `frozenset` (`PyFrozenSet`, `ArithmeticOps`, `PyOps`, `BuiltinMethodDispatcher`)**:
  - Added immutable, order-independently hashable `PyFrozenSet` and the `frozenset([iterable])` built-in type (usable as `dict` keys and `set` elements, `isinstance`/`type`/`len`/`iter`/`dir`).
  - Added binary `|`, `&`, `-`, `^` (preserving left operand's `set`/`frozenset` type), compound `|=`, `&=`, `-=`, `^=`, partial-order subset/superset comparisons (`<`, `<=`, `>`, `>=`), cross-type `{1, 2} == frozenset([1.0, 2])` equality, and `symmetric_difference`, `issubset`, `issuperset`, `isdisjoint` on both `set` and `frozenset` (`SetOperatorsAndFrozenSetTest`, CPython differential tests).
- **Lazy Generator Expressions & True Frame Suspension (`PyGenerator`, `Jython3.g4`, `CompileVisitor`, `Jython3Interpreter`)**:
  - Added grammar and compiler support for generator expressions `(expr for target in iterable [if cond]...)`, nested `for`/`if`, tuple unpacking, closures, lambdas, and sole-argument call-site form `sum(x for x in seq)` (outer iterable evaluated once at creation via `GET_ITER`; body 100% lazy).
  - Replaced eager yield collection with real VM frame suspension/resume on `YIELD_VALUE`: `PyGenerator` tracks `CREATED`/`RUNNING`/`SUSPENDED`/`COMPLETED`/`FAILED`, preserves `pc`, `FastStack`, `locals`, `exceptHandlers`, `activeExceptions`, `globalCells`, `moduleGlobals`, and `ExecutionContext` across `next()`, enforces single-interpreter ownership and re-entrancy (`ValueError: generator already executing`), preserves `try/finally`, `with`, `__traceback__`, `__context__`, `__cause__`, and `StopIteration.value`, releases all frame references on completion/failure, and enforces `ExecutionLimits`, cancellation (`cancel()`), and `RecursionError` (`GeneratorExpressionGrammarTest`, `GeneratorExpressionTest`, `GeneratorSuspensionTest`, `GeneratorFinallyTest`, `GeneratorSecurityTest`, and CPython differential tests; 456 total tests passing green).
  - Made `zip()` and `iter(gen)` lazy (`iter(g) is g`, `zip(["a", "b"], infinite_gen())` stops cleanly); fixed `PythonIndentStream.createToken` so syntax-error recovery never triggers `NullPointerException`; updated `BytecodeValidator` to reject `YIELD_VALUE` outside generator functions and check `YIELD_VALUE`/`ASSERT` stack effects.
- **Full Python `__traceback__` & Chained Exception Rendering (`PyTraceback`, `PyFrameInfo`, `PyCodeInfo`)**:
  - Exposed `exc.__traceback__` (`tb_next`, `tb_lineno`, `tb_lasti`, `tb_frame.f_code.co_name`/`co_filename`, `tb_frame.f_lineno`), passed live `exc_tb` to `with.__exit__(exc_type, exc_val, exc_tb)`, preserved inner frames across `RERAISE`/`RAISE_ACTIVE` (`try/finally`, `with`, `except` mismatch, bare `raise`), propagated implicit `__context__` to runtime errors (`ZeroDivisionError`, `IndexError`, `JavaException`) raised inside `except`/`finally`/callees, unwrapped `InterpreterException` inside `PyErrors.fromJava` (`InvocationTargetException`), and rendered chained `cause`/`context` tracebacks.
- **Unified Python Type Identity (`PyBuiltinFunction.builtinType`)**:
  - Updated `type(obj)` and `obj.__class__` to return canonical type objects (`int`, `str`, `float`, `bool`, `list`, `dict`, `tuple`, `set`, `type`, `object`, `NoneType`, `function`, `builtin_function_or_method`, `method`, `generator`, `iterator`, `module`, `slice`, `traceback`, `frame`, `code`, and `PyClass` for instances/exceptions); unified `isinstance`, `issubclass`, `hasattr`, `getattr`, and `__name__` (`TypeIdentityTest`).
- **Set Methods, Iteration & Numeric Key Hardening**:
  - Added `GET_ITER` support for `PySet`, implemented `set.remove`, `set.discard`, `set.pop`, `set.clear`, `set.copy`, `set.union`, `set.intersection`, `set.difference`, `set.update`, rejected `PySlice` as unhashable in `PyKey`, fixed `BigInt` vs `PyBool` equality without `OverflowError` (`10**400 == True`), and fixed `PyBoundMethod` equality.
- **Core Built-ins & Call-Site Unpacking (`CALL_FUNCTION_EX`)**:
  - Added `all`, `any`, `callable`, `delattr`, `vars`, `dir`; upgraded `repr` (string quoting/escapes and `PyException`), `enumerate` (iterables + `start` + tuples), `zip` (iterables + tuples), `map`, `filter`, and `sum` (iterators + `start` + `BigInt`); wired `CALL_FUNCTION_EX`, `LIST_EXTEND`, and `DICT_MERGE` for `*args` and `**kwargs` unpacking at call sites (`BuiltinsTest`).
- **Grammar & Compiler Edge Cases (`ParserEdgeCasesTest`)**:
  - Replaced `call_stmt` with `expr_stmt` (allowing expression statements starting with `[` or `{`), implemented single-evaluation short-circuiting chained comparisons (`1 < x < 10`, `0 == 0 == 0`, `1 in [1, 2] == True`), fixed `not` precedence below comparisons, added multi-item `with a as x, b as y:`, and `yield rhs_tuple`.
- **Bytecode Disassembler & Static Stack Validator (`BytecodeDisassembler`, `BytecodeValidator`)**:
  - Added `Jython3Interpreter.disassemble(src)` and static jump/pool/stack-depth verification (`BytecodeInspectionTest`); caught and fixed stack leaks on `with` suppression (`POP_TOP`) and `except <expr>` evaluation failure inside `try/finally`.
- **Policy-Aware Dotted Module Loader & Pure-Python Stdlib (`ModuleLoader`, `modules/collections.py`, `modules/functools.py`)**:
  - Extracted `ModuleLoader` supporting dotted packages (`__init__.py` boundaries), `sys.path`/`sys.modules`, `__name__`/`__file__`/`__package__`, `from mod import *` (`__all__`), symlink-escape and path-traversal rejection (`SecurityError`), circular `from ... import ...` detection, and pure-Python `collections` (`Counter`, `defaultdict`, `deque`) and `functools` (`reduce`, `partial`, `lru_cache`).
- **Python-visible exception metadata & `raise ... from ...` (`PyException`, `RAISE_FROM`)**:
  - Exposed `__context__`, `__cause__`, `__suppress_context__`, `args`, and `__class__` on `PyException` via `LOAD_ATTR`, `getattr`, and `hasattr`.
  - Added grammar and `RAISE_FROM` opcode for `raise exc from cause` and `raise exc from None` (instantiating exception classes, validating `BaseException` inheritance with `TypeError`, setting `__cause__` and `__suppress_context__ = True`, and preserving implicit `__context__`).
- **`__name__`, `__class__`, and `type()` class identity**:
  - Exposed `__name__` on classes, functions, builtins, and bound methods; `__class__` on instances and exceptions; updated `type(inst)`/`type(exc)` to return the `PyClass`; added identity equality in `PyOps.pyEquals` for classes, exceptions, functions, instances, and modules; fixed `getattr(inst, missing, default)` so missing fields fall back to `default` instead of throwing (417 total tests passing green).
- **Integer Bitwise Operations & Base Literals**: Added `&`, `|`, `^`, `~`, `<<`, `>>` and compound assignments `&=`, `|=`, `^=`, `<<=`, `>>=` (plus `0x`/`0b`/`0o` integer literals) with exact Python operator precedence, `PyInt` and `BigInt` two's-complement support, `PyBool` bitwise semantics, `float` `TypeError` rejection, and CPython differential conformance tests.
- **Bytecode-Level Closure Variable Analysis**: Refined `CompiledCode.referencedNames` to inspect `LOAD_FAST`/`STORE_FAST`/`DELETE_FAST` and `@nonlocal:` bytecode instructions rather than raw `constPool` strings, preventing string literals from retaining identically named outer local variables (395 total tests passing green).
- **Unified `LValue` Assignment & Compound Assignment (`LValue.java`)**: Unified `NameTarget`, `AttributeTarget`, `SubscriptTarget`, and `TupleTarget` across regular assignment, chained assignment, sequence unpacking, `for` loop unpacking, `del` (`DELETE_ATTR`), and single-evaluation augmented assignment (`x += 1`, `obj.value += 1`, `items[0] += 1`, `matrix[0][1] *= 2`) using `DUP_TOP_TWO`, `ROT_TWO`, and `ROT_THREE`.
- **Selective Closure Variable Capture (`CompiledCode.referencedNames`)**: Precomputed referenced symbol names per `CompiledCode` so `def` and `lambda` closures only capture referenced outer variables and never retain unreferenced locals after function exit (reducing Stress Test B peak heap from 115 MB to 44 MB; 393 total tests passing green).
- **First-Class Built-in `print` Function**: Removed the special `print_stmt` / `PRINT` grammar keyword so `print` is a regular built-in function supporting variable assignment (`fn = print`), list/closure invocation, `sep`/`end`/`file`/`flush` keywords, and strict `TypeError` validation on non-string `sep`/`end` (391 total tests passing green).
- **Unified Callable Expression Trailers (`CallExpr`)**: Refactored `Jython3.g4` and `CompileVisitor` so any expression yielding a callable can be invoked directly (`factory()(10)`, `items[0](10)`, `obj.method()(10)`, `get_callback()("value")`) with CPython-compatible `'...' object is not callable` error reporting.
- **`PolicySanitizer` & `BigInt` Conformance Suite (`PolicySanitizerTest`)**: Added tests for circular `PyList`/`PyDict` sanitization, nested Java object retargeting, `PyFunction` closure sanitization, `PyClass` inheritance sanitization, `json.dumps(10 ** 30)` / tuple serialization, and `math.sqrt(True)` / `math.sqrt(10 ** 30)` (390 total tests passing green).
- **Documentation Consolidation (`docs/STATUS.md`)**: Established `docs/STATUS.md` as the single source of truth for current live capabilities and runtime concept glossary (`PyNull` vs Java `null` vs Python `None`, `PyCaller`, `PyCell`, `PyCompiledCodeWrapper`, `ExecutionContext`); aligned `README.md`, `COMPATIBILITY.md`, `ROADMAP.md`, `ARCHITECTURE.md`, and `PROJECT_STRUCTURE.md`.
- **Modular Stdlib & VM Package Extraction**: Split `BuiltinModules` into per-module classes under `stdlib.modules.*`, extracted `BuiltinMethodDispatcher`, `ArithmeticOps`, and `PolicySanitizer` from `Jython3Interpreter`, and split `FieldTestRunner` into modular `org.example.jython3.field.*` scenarios and `ResourceProfiler`.
- **Strict `re.error` Validation**: Replaced silent error swallowing in `ReModule` (`re.search`, `re.findall`) with `PatternError` (`re.error`) on malformed patterns and `TypeError` on invalid arguments (379 total tests passing green).
- **Field Test & Resource/Leak Profiler (`./gradlew fieldTest`)**: Added `FieldTestRunner` and `FieldIntegrationTest` running 4 real-world Python projects (JSON analytics, OOP/context managers/generators, regex/Unicode/Monte Carlo, Java Bridge automation), 200 deep exception-unwind stress tests, and 600-cycle / 300-interpreter memory, CPU, GC, and thread leak probes.
- **Raw String Literals (`r"..."`, `r'...'`, `r"""..."""`)**: Added `RSTRING` lexer/parser rule and `visitRStr` compiler support preserving backslashes verbatim.
- **Zero-Argument `super()`**: Implemented Python 3 `super()` resolution via `PyCaller.resolveZeroArgSuper()` and `findDefiningClass()`, resolving parent methods across multi-level inheritance chains.
- **Java Bridge Type Converter Plans (`TypeConverter`)**: Pre-compiled argument converter plans on `MethodMatch` and `ConstructorMatch` replacing cascading runtime `if (targetType == ...)` checks with direct functional dispatch.
- **Fast Array & Collection Conversions**: Added specialized loops for all primitive and reference arrays (`int[]`, `long[]`, `double[]`, `float[]`, `boolean[]`, `byte[]`, `short[]`, `char[]`, `String[]`, `Object[]`) replacing slow reflection `Array.set()`, with pre-sized lists and maps during interop.
- **Primitive vs Boxed Nullability & Edge-Case Verification**: Separated boxed (`Integer`, `Double`, `Boolean`, etc. accepting `None -> null`) from primitive (`int`, `double`, `boolean`, `byte`, `short`, `char` rejecting `None` with `TypeError`) in `JavaBridge`, verified `String[]` / `Integer[]` with `null` elements, `byte[]` / `short[]` / `char[]` coercion, and nested `List` / `Map` access policy enforcement (372 total tests passing).
- **Java Bridge Overload Resolution Caching (Step 4)**: Introduced policy-scoped structured immutable `MethodResolutionKey` and `ConstructorResolutionKey` (access policy, target class, method name, argument types, argument count) with `MAX_CACHE_ENTRIES` and `MAX_CLASS_ENTRIES` size caps, policy-filtered constructor overload selection, reducing Java interop dispatch latency by 56.3% (from 124.69 ms to 54.54 ms) with zero cross-policy resolution pollution.
- **Class Reflection Method Discovery Caching**: Added grouped method caches in `JavaBridge` (`CLASS_INSTANCE_METHODS`, `CLASS_STATIC_METHODS`) eliminating repeated array allocations from `Class.getMethods()`.
- **Java Bridge Caching Regression Suite**: Added `JavaBridgeOptimizationTest` verifying overload selection, cross-class cache isolation, per-call security policy enforcement, and cache invalidation (365 total tests passing).
- **Class Hierarchy Versioning & Generation Stamps**: Introduced `PyClass.version()` counter and recursive `invalidateHierarchy()` propagation across subclasses in `PyClass`.
- **Version-Guarded Monomorphic Inline Caching**: Added class-version verification to `AttrCacheEntry` in `LOAD_ATTR` to guarantee stale method/attribute references are never returned after class mutations.
- **Version-Guarded Bound Method Caching**: Added class version checks to `PyInstance.boundMethodCache` ensuring replaced methods immediately reflect on existing instances.
- **Inline Cache Correctness Regression Suite**: Added `InlineCacheCorrectnessTest` covering method replacement, base class mutation propagation, instance field shadowing, and polymorphic call sites.
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
- **Performance Benchmark Suite (Step 1, 2, 3, 4)**: Reproducible micro-benchmark suite (`BenchmarkSuite`) measuring 8 core execution workloads with isolated compilation and execution timing.
- **Full Scoping with `nonlocal` and `PyCell`**: Integrated `PyCell` mutable cell bindings into the compiler and interpreter, enabling lexical closure mutations across arbitrary nested enclosing scopes.
- **Gradle Benchmark Task**: Added `./gradlew benchmark` task for automated performance baseline profiling with separate Compile and Execution metrics.
- **Benchmark Smoke Tests & Conformance Suite**: Added `BenchmarkSmokeTest` and `NonlocalAndBuiltinFixesTest` (365 automated tests passing green).

### Fixed
- **Embedded Runtime Integration Contract & Production Hardening**:
  - Published [docs/EMBEDDED_INTEGRATION_CONTRACT.md](docs/EMBEDDED_INTEGRATION_CONTRACT.md) locking the Integration Beta API contract for Oculix/Cinnamon (`HostAppBridge`, `CapabilitySet`, `HostSession`, `PyHostHandle`, `UiToolkit`, `ControlDevice`).
  - Blocked cross-interpreter `PyHostHandle` smuggling in `PolicySanitizer`, synchronized interpreter `eval`/`resetExecutionState`/`close` teardown without deadlocks, coalesced `HeadlessTimer` ticks, added `HostDispatchBenchmark` (`182k` UI events/sec, `386k` control cmds/sec), and expanded tests to `486` passing tests (`HostConcurrencyAndTeardownTest`, `SwingAndJavaFxDispatchTest`, `HostSecurityAuditTest`, `ControlDeviceConcurrencyTest`, `HostDispatchPerformanceTest`).
- **Host UI/Control Hardening (`HeadlessUiToolkit`, `HostSession`, `ExecutionContext`, `ControlModule`, `PyHostHandle`)**:
  - Made `HeadlessDispatcher` thread ownership immutable (explicit `HeadlessDispatcher.dedicatedThread()` + `pumpEvents()` queue).
  - Enforced `callbackTimeout` across nested `eval()` calls and blocking calls (via child `ExecutionContext` + `CALLBACK_WATCHDOG` with clean interrupt recovery).
  - Centralized `maxWindows`, `maxTimers`, and `maxListenersPerNode` quotas in `HostSession` across `HeadlessUiToolkit`, `SwingUiToolkit`, `JavaFxUiAdapter`, and `ControlModule`.
  - Audited and propagated async timer callback failures (`HostSession.recordCallbackFailure`, `HostAuditLog`, `setCallbackErrorHandler`).
  - Made `control.State` a live view over `ControlDevice.snapshotState()` with explicit `.snapshot()`, and added session-epoch tracking so stale `PyHostHandle`s raise `RuntimeError` after `resetExecutionState()` or `close()`.
- **True In-Place Augmented Assignment for `list` and `set` (`INPLACE_*`)**: Added `INPLACE_ADD`, `INPLACE_SUBTRACT`, `INPLACE_MULTIPLY`, `INPLACE_AND`, `INPLACE_OR`, `INPLACE_XOR` so `list += iterable`, `list *= n`, and `set |= / &= / -= / ^=` mutate the target object in-place (preserving aliases and self-operation safety) while enforcing `ExecutionLimits.maxCollectionSize()` and keeping `frozenset` immutable.
- **JavaBridge Hardening & `MethodHandle` Dispatch (`JavaBridge`, `PyJavaMethod`, `PyJavaClass`, `PyJavaObject`, `PyErrors`)**:
  - Enforced `JavaAccessPolicy` inside `findBestMethod`, added varargs resolution/packing, functional-interface callback proxies (preserving policy, caller state, tracebacks, and recursion limits), public-interface method resolution for non-public JDK classes (`Arrays$ArrayList`), empty-attribute `AttributeError` guard, `StackOverflowError` -> `RecursionError` mapping in `PyErrors.fromJava`, and `MethodHandle` (`asFixedArity().asSpreader(...)`) invocation (`JavaBridgeHardeningTest`).
- **Generator Frame Isolation & VM Micro-Optimizations**:
  - Saved/restored `globalCells`, `currentGlobals`, `currentFile`, and `outerActiveContexts` in `runGeneratorWithLocals`, prevented `NullPointerException` on `next(g)` after generator exception, explicitly rejected `send`/`throw`/`close` with `NotImplementedError`, pre-boxed `CompiledCode.pyConstPool`, and pooled frame stacks/handler deques (reducing `fib(24)` heap delta by ~55%; 441 total tests passing green).
- **Python hash/eq semantics for dict and set (`PyOps`, `PySet`, `PyKey`, `BUILD_DICT`/`BUILD_SET`)**:
  - `dict == dict` used Java map equality, so `{1: 0} == {1.0: 0}`, `{True: 1} == {1: 1}`, `{"a": 1} == {"a": 1.0}` and `{10**20: 1} == {1e20: 1}` were `False`; now delegates to `PyDict.equals` (PyKey key lookup + `pyEquals` values).
  - `x in dict` used a Java `containsKey`, so `1.0 in {1: 0}` was `False`; now uses `PyDict.containsKey` (PyKey).
  - `PySet` now stores elements keyed by `PyKey` (O(1) Python membership; `{1, 1.0, True}` has one element); `set` literals/comprehensions validate hashability.
  - Unhashable keys/elements (`{[1]: 1}`, `{{1}: 1}`, `{{}}`, `[1] in {1}`) raise `TypeError: unhashable type` instead of a raw Java `UnsupportedOperationException`; `PySet` is unhashable in `PyKey`.
  - Added `len(set)`, `set()`/`set(iterable)` and `isinstance(x, set)`; set `repr` quotes string elements (413 total tests passing green).
- **Recursion through re-entrant paths crashed the host with `StackOverflowError`**: Recursion via `__init__`, `__enter__`/`__exit__`, class bodies, generators, or isolated module execution used nested `run()` loops that were not counted toward `MAX_CALL_DEPTH`, so `R(100000)` with a recursive `__init__` killed the host thread with an uncatchable `java.lang.StackOverflowError` (a sandbox denial-of-service vector). Added `nestedDepth` accounting via `runNested()` (including caller frames hidden by isolated runs), so these paths raise a catchable `RecursionError`; a residual JVM stack overflow on small host stacks is converted to `RecursionError` at the nesting boundary.
- **Exceptions in class bodies jumped to the wrong handler**: `BUILD_CLASS` ran the class body on the caller's handler stack, so `try: class C: x = 1/0 except ZeroDivisionError:` swallowed the exception and resumed at a bogus `pc`. Class bodies now get their own handler stack, and caller state is restored in `finally` (408 total tests passing green).
- **Re-entrant Python calls corrupted the caller's frame (`Jython3Interpreter.run` / `callPyCallableKw`)**: Python functions invoked from inside the VM (`__init__`, `__enter__`, `__exit__`) ran a nested `run()` that shared `callStack`; their `RETURN_VALUE` popped the *caller's* frame, so any `with` block inside a function silently ended that function and returned `None`. `run()` now records `baseDepth` and never returns into or unwinds below it, and `callPyCallableKw` restores caller state (`stack`, locals, handlers, `globalCells`, globals, file/line) in a `finally` block even when the callee raises.
- **Compiler temporaries could collide with user variables**: `__ret_val_N` / `__comp_list_N` / `__comp_dict_N` / `__comp_set_N` renamed to `$`-prefixed names (illegal in Python identifiers), deleted after use, and excluded from `CompiledCode.referencedNames` so closures never capture them.
- **Set equality**: `{0} == {0}` returned `False` because `PyOps.pyEquals` had no `PySet` branch; added size + membership comparison with Python-equality fallback (`{1} == {1.0}`).
- **Edge-case and differential coverage**: `return`/`break`/`continue`/`raise` out of `with` inside functions, exception raised in `else` with `finally`, `return` in `finally` with active `except ... as e`, `as`-target cleanup, `__context__` from `finally`, Java exceptions in hot `try/finally` loops, re-entrant `__init__` raising, and set equality (401 total tests passing green).
- **Guaranteed `try/finally` Control-Flow Unwinding (`CompileVisitor` & `Jython3Interpreter`)**:
  - Restored `ExceptHandler.stackDepth` (`truncateStack`) on exception catch across intra-frame and cross-frame unwinds so intermediate expression operands and inner `for`-loop iterators never leak onto the operand stack.
  - Moved pending unhandled exceptions onto `activeExceptions` (`BEGIN_EXCEPT_HANDLER` + `POP_TOP` / `RERAISE(1)`) during `<exceptional-finally>` execution and saved pending return values in frame-local slots during `emitScopeExits`, so `return`, `break`, and `continue` inside `finally` (or inside `except`/`else`/`with`) execute with a clean operand stack and CPython-identical override semantics.
  - Fixed `scope.handlerActive` tracking inside `except` and `else` suites when `finally` is present, and enforced `SyntaxError` on `break`/`continue` outside loops.
  - Fixed `PyTuple.toString()` to quote `PyStr` elements matching Python `repr` and `PyList.toString()` (397 total tests passing green).
- **Circular Reference Detection in `json.dumps()`**: Added active-ancestor cycle tracking (`IdentityHashMap` set) in `JsonModule.simpleJsonDumps` raising `ValueError: Circular reference detected` on self-referential lists, tuples, or dicts while allowing shared non-circular DAG siblings (`[x, x]`).
- **Exact `math.floor()` / `math.ceil()` on `BigInt` & Overflow Checks**: Updated `MathModule` so `floor` and `ceil` preserve arbitrary-precision `BigInt` values without clamping to 32-bit `int`, and verified `OverflowError` across `sqrt`, `sin`, `cos`, `tan`, `log`, `pow` for `10 ** 400` (389 total tests passing green).
- **`json.dumps(BigInt)` & `StdlibSupport.toDouble(bool/BigInt)`**: Fixed `JsonModule` to serialize `BigInt` as unquoted JSON numbers and `PyTuple` as JSON arrays; extended `StdlibSupport.toDouble` and `MathModule.abs` to accept `PyBool` and `BigInt` (raising `OverflowError` when `BigInt` overflows 64-bit `double`).
- **`PolicySanitizer` `PyDict` & `PyFunction` Mutation**: Fixed `PolicySanitizer.sanitize()` to populate `PyDict` via `res.put(...)` and `PyFunction` closure via `res.closure().put(...)` (preserving `defaults`, `argsParam`, and `kwargsParam`) after defensive constructor copying.
- **Strict RFC 8259 `json.loads()` & `JSONDecodeError`**: Replaced the permissive JSON fallback parser with a strict recursive-descent `JsonParser` enforcing RFC 8259 syntax and raising `json.JSONDecodeError` (registered as a `ValueError` subclass) on malformed inputs like `"{bad json}"`, `"[1,]"`, `"unknown"`, leading-zero numbers, trailing commas, and unquoted keys (378 total tests passing).
- **Recursive `json.loads()` Parsing**: Fixed `jsonParseValue()` in `BuiltinModules` to recursively parse nested JSON objects `{...}`, nested arrays `[...]`, escape sequences, colons inside quoted keys/values, and `BigInt` numbers.
- **Builtins Iterable Consumption (`list`, `sum`, `sorted`, `reversed`)**: Unified iterable collection via `collectIterable()` so generators, iterators, sets, and Java `Iterable`s work across all core built-in functions.
- **Fluent Chaining on `StringBuilder` / `StringBuffer`**: Preserved `PyJavaObject` wrapping for mutable `StringBuilder`/`StringBuffer` return values in `JavaBridge.toPyObject()` so chained calls like `sb.append(a).append(b)` succeed while maintaining `CharSequence` policy gating.
- **ThreadLocal Memory Leak Prevention**: Cleared `PyDict.QUERY_KEY` object references after lookups (`qk.clear()`) and installed/restored/removed `PyCaller.CURRENT` in `eval()`/`evalCode()` `finally` blocks, preventing retention of discarded interpreters and queried keys across thread pools.
- **Array Conversion Semantic Fidelity**: Fixed specialized primitive array conversions (`int[]`, `long[]`, `double[]`, `boolean[]`) to support Python coercion semantics (e.g. `[3.14, True] -> int[] {3, 1}`) via `toJavaInt/Long/Double/Boolean` and added array/BigInt resolution support in `scoreParam`.
- **Bounded Converter Cache**: Capped `CONVERTER_CACHE` with `MAX_CONVERTER_ENTRIES = 512` and auto-eviction.
- **CI GitHub Actions Workflow**: Fixed multi-line `python-version` YAML parsing error in `.github/workflows/test.yml`.
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
