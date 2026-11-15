# Development Roadmap

This document outlines **upcoming** milestones and future development steps for `jython3-prototype`.
For current verified capabilities, see [STATUS.md](STATUS.md) and [COMPATIBILITY.md](COMPATIBILITY.md).
For completed historical releases, see [CHANGELOG.md](../CHANGELOG.md).

---

## Active Track: Performance & Runtime Hardening

### Completed Foundation Steps
- [x] **Step 1**: Reproducible performance benchmark suite (`./gradlew benchmark`) and real-world field/leak profiler (`./gradlew fieldTest`).
- [x] **Step 2**: Array-backed `FastStack`, small-int caching (`PyInt.of`), `O(1)` generator check (`hasYield`), zero-overhead positional `bindArgs`.
- [x] **Step 3**: Version-guarded monomorphic inline caching (`LOAD_ATTR`), `PyClass.lookupMember` hierarchy cache with recursive invalidation, zero-allocation `PyDict` lookups, ASCII/BMP `PyStr` slicing fast-path.
- [x] **Step 4**: Policy-scoped `JavaBridge` overload/constructor caching, reflection method discovery caching, and pre-compiled `TypeConverter` plans.
- [x] **Step 5**: `MethodHandle` invocation in `JavaBridge`, pre-boxed `CompiledCode` literal pools, and pooled VM frame stacks/handler deques (preserving per-call security policy enforcement).

### Upcoming Performance Steps
- [ ] **Step 6**: Tier-1 JIT bytecode-to-JVM compilation (`invokedynamic` / `ClassFile` API) for hot Python functions.
- [ ] **Step 7**: Primitive unboxing / specialized numeric and homogeneous collection representations.

---

## Upcoming Language & Platform Phases

### Language Conformance Expansion
- [x] PEP 3110 exception-target deletion on `raise` exit paths inside `except ... as e:` without `finally`.
- [x] Dynamic 3-argument `type(name, bases, dict)`, C3 MRO, dynamic `__bases__` mutation, and `@property`/`@staticmethod`/`@classmethod`.
- [x] Numeric underscore literals (`1_000_000`), walrus `:=`, `bytes`/`bytearray`, lazy O(1) `range`, and full dunder operator overloading.
- [x] `async def` / `await` coroutine syntax (PEP 492), PEP 479 generator/coroutine `StopIteration`, Execution Model §4.2.2 `UnboundLocalError`, and cooperative `asyncio`.

### Virtual Threads & Concurrency
- [x] Native support for Java 21+ Virtual Threads (`HostCapability.THREADS` + `threading` module).
- [x] Standard library `queue` and `concurrent.futures` (`ThreadPoolExecutor` / `Future`) modules.
- [x] GIL-free Virtual-Thread execution sharing `ConcurrentHashMap` globals and session teardown.

### Ecosystem & Packaging
- [x] Capability-gated host UI (`HeadlessUiToolkit`, `SwingUiToolkit`, `JavaFxUiAdapter`), restricted `control` APIs, and `HostAppBridge` for Oculix/Cinnamon embedding.
- [ ] Direct `openjfx` widget factory bundle for Oculix IDE desktop UI panels.
- [ ] Standalone Maven Central artifact (`org.example.jython3:jython3-core`).
- [ ] Native executable packaging with GraalVM Native Image.
