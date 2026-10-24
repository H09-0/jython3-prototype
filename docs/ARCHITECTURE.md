# Jython 3 Architecture

Status: living document. Last reviewed: 2026-10-24.

This document defines the boundaries that the implementation is expected to
respect. It is deliberately short: it records the decisions that are expensive
to change later, not every class in the tree.

Jython 3 is an independent re-implementation of Python 3 on the JVM. It follows
the goals of the public Jython 3 roadmap but is not an official Jython release
and is not endorsed by the upstream project.

---

## 1. Compatibility target

| Item | Decision |
|---|---|
| Language and semantics baseline | **CPython 3.14.x** |
| Grammar baseline | Python 3.14, minus features listed as unsupported |
| Standard library | Only the modules listed in the README compatibility table |
| Newer syntax | 3.15 features are out of scope until the 3.14 baseline is green |
| Reference oracle | CPython 3.14 for output, error type, and error text |

3.14 is chosen because it is the previous stable minor: its semantics are
settled, its documentation is stable, and it is the version most libraries
still target. 3.15 is three days old at the time of writing, so adopting it as
the baseline now would add surface without adding conformance value. The
baseline moves only when the 3.14 conformance suite passes; moving it is a
documented change to this table, not an incidental side effect.

"Supports Python 3" is never used as a claim on its own. A feature is supported
only when it appears in the compatibility table with a passing test.

---

## 2. Layers and dependency rules

| Layer | Package | Responsibility | May depend on |
|---|---|---|---|
| Entry points | `org.example.jython3`, `.cli` | `exec`/`eval`, REPL, script runner, embedding API | compiler, VM, runtime |
| Parser | `org.example.jython3.parse`, generated ANTLR | tokens, indentation, parse tree | ANTLR runtime |
| Compiler | `org.example.jython3.compile` | parse tree to bytecode and constant pool | runtime values, parser |
| Virtual machine | `Jython3Interpreter` | frames, stack, calls, control flow, exceptions | runtime, compiler, stdlib |
| Runtime | `org.example.jython3.runtime` | `PyObject`, value types, errors, protocols | JDK only |
| Standard library | `org.example.jython3.stdlib`, `modules/` | built-in functions and modules | runtime |
| Java bridge | `org.example.jython3.bridge`, `PyJava*` | reflection, conversion, overloads, access policy | runtime |
| Security | `org.example.jython3.security` (planned) | access policy, resource limits, execution modes | JDK only |

Rules:

- The runtime never calls the bridge. `PyObject` describes Python values; the
  bridge adapts Java values into that model.
- The compiler does not resolve names, import modules, or touch reflection. It
  emits instructions and constant-pool entries.
- The VM orchestrates; it does not contain module implementations. Built-ins
  are registered from `stdlib`.
- Security policy is passed in by the host, not read from static global state.
  A process-wide allowlist is a default for the trusted mode, not the model.

Known layering debt: `PyJavaClass`, `PyJavaObject`, `PyJavaMethod`, and
`PyJavaPackage` currently live in `runtime` because they are listed in the
`PyObject` permit clause. They belong in `bridge`; the permit list and package
move are planned together so the sealed hierarchy stays closed.

---

## 3. Execution pipeline

```
Python source
    |
    v
Jython3Lexer  ->  PythonIndentStream  ->  Jython3Parser
    (tokens)        (INDENT/DEDENT)        (parse tree)
    |
    v
CompileVisitor + CodeBuilder
    |
    v
CompiledCode { int[] code, Object[] constants }
    |
    v
Virtual machine: frames, operand stack, call stack, handler stack
```

- One instruction is one `int`: high 16 bits are an explicit `OpCode` number,
  low 16 bits are a constant-pool index or a raw operand. Opcode numbers never
  change when a new opcode is added.
- A frame owns an operand stack, a local scope, a reference to globals, and a
  handler stack. `try` pushes a handler; normal exit pops it; an unmatched
  error runs `finally` and re-raises the same value.
- `break`, `continue`, and `return` that leave a `try` still execute `finally`.
- Generators are currently collected eagerly. Lazy frame suspension is a
  separate change and will not alter the public `PyGenerator` API.

---

## 4. Object and type model

`PyObject` is a sealed interface. Immutable values are records (`PyInt`,
`PyFloat`, `PyStr`, `PyBool`, `PyNull`, `PyList`, `PyDict`); objects with
identity or mutable behaviour are final classes (`PyFunction`, `PyClass`,
`PyInstance`, `PyModule`, `PyGenerator`, `PyException`, and the bridge
wrappers).

- `PyClass` holds a name, an ordered base list, and members. `PyInstance` holds
  a class and an attribute map. Attribute lookup walks the base list.
- Protocols are currently implemented with `instanceof` switches in the VM and
  `PyOps` (`toInt`, iteration, comparison). The target is special-method
  dispatch (`__add__`, `__iter__`, `__eq__`, ...) so user classes participate
  without changing the VM.
- `PyOps.typeName` is the single place that renders a Python type name for
  errors.
- Equality and hashing follow Python semantics for built-in values; identity
  is used where Python uses identity (`is`).

### Exceptions as types

Exception *values* already exist (see section 5), but exception *types* are
currently names in a parent table in `PyErrors`. The next slice registers
`BaseException`, `Exception`, and the built-in hierarchy as `PyClass` objects
inheriting from `Exception`, with `SecurityError` and `JavaException` as
runtime-specific subclasses. When that lands, `PyErrors.matches` resolves
through `PyClass` bases instead of the name table, and `except` binds a real
`PyInstance`. No call site outside `PyErrors` should need to change.

---

## 5. Exception model

There are two separate concepts, and the separation is intentional:

- **Carrier**: `InterpreterException extends RuntimeException`. It unwinds the
  Java stack. It holds the Python value, the raise location, and the traceback
  entries collected while frames unwind.
- **Value**: `PyException implements PyObject`. It can be stored in a variable,
  passed to a function, compared, and re-raised. It holds `typeName`, `message`,
  `args`, Python `cause`, Python `context`, and `javaCause`.

Rules:

1. `PyErrors` is the only place that constructs the carrier. `ErrorFactoryTest`
   fails the build if another file does so.
2. `PyErrors.error(type, message)` creates Python-level errors.
   `PyErrors.fromJava(throwable)` is the single entry point for JVM throwables;
   it unwraps reflection wrappers, maps the concrete class upwards, and keeps
   the original throwable in `javaCause`.
3. `cause` and `context` are Python-level links created by `raise ... from ...`.
   The Java throwable is never stored there.
4. The VM pushes the exception value on the operand stack, not its message.
   `COMPARE_EXC_TYPE` calls `PyErrors.matches`; `RERAISE` throws the same value.
5. Matching uses real inheritance. `BaseException` catches every exception;
   `Exception` does not catch `SystemExit`, `KeyboardInterrupt`, or
   `GeneratorExit`.
6. Java stack traces are disabled by default because Python raises often
   (`StopIteration` in every exhausted loop). `-Djython3.stacktraces=true`
   enables them for debugging.
7. The traceback lives on the carrier. When `e.__traceback__` is exposed, the
   attribute reads from the carrier; the value does not own it.

Java mapping (specific class first, then superclasses):

| Java throwable | Python type |
|---|---|
| `ArrayIndexOutOfBoundsException`, `IndexOutOfBoundsException` | `IndexError` |
| `NoSuchElementException` | `KeyError` |
| `NumberFormatException`, `IllegalArgumentException` | `ValueError` |
| `ClassCastException`, `UnsupportedOperationException`, `NullPointerException` | `TypeError` |
| `ArithmeticException` | `ZeroDivisionError` |
| `FileNotFoundException` | `FileNotFoundError` |
| `SecurityException` | `PermissionError` |
| anything else | `JavaException` |

`SecurityError` and `JavaException` are not CPython built-ins. They are runtime
types that inherit from `Exception`, and they will be registered as `PyClass`
objects in the same slice that makes the built-in hierarchy first-class.

---

## 6. Java bridge and security boundary

The bridge provides class lookup, construction, method and field access,
overload resolution, and value conversion. It is useful, and it is also the
largest attack surface in the runtime.

### What the current allowlist is

`JavaBridge` holds static sets of allowed packages and classes and rejects
reflection, `Unsafe`, process control, and JVM internals by name. This is
**defense in depth at the language level**. It is not a sandbox:

- name-based checks can be bypassed by any path that reaches reflection;
- a permitted class can expose powerful objects through its own API;
- the JVM security manager is permanently disabled from Java 24 onward, so
  there is no in-process mechanism to fall back on.

### Two execution modes

| Mode | Use case | Mechanism |
|---|---|---|
| **Trusted** | Host application runs its own scripts | Host supplies a `JavaAccessPolicy`; the default policy mirrors today's allowlist; reflection stays denied unless explicitly granted |
| **Restricted** | Untrusted code | Separate OS process or container, not an in-process sandbox: read-only filesystem, no network by default, CPU/memory/time limits, bridge disabled unless granted, results over IPC |

Restricted mode is a deployment property. The interpreter may support it by
exposing a minimal runner, but it must never claim that class-name filtering
makes arbitrary code safe inside the same JVM.

### Planned policy seam

```java
public interface JavaAccessPolicy {
    boolean canAccessClass(String binaryName);
    boolean canCallMethod(String owner, String name, String descriptor);
    boolean canReadField(String owner, String name);
    boolean canWriteField(String owner, String name);
}
```

- The policy is supplied per interpreter, not held in a static field.
- The default is deny-by-default plus the documented allowlist.
- Denials raise `SecurityError` (or `PermissionError` where the cause is an OS
  permission) through `PyErrors`.
- The bridge is testable without the VM: conversion, overload selection, and
  policy denials are unit tests.

Resource limits are separate from access policy. An instruction budget and
cooperative interruption can stop runaway Python code; heap limits cannot be
enforced reliably in-process. Untrusted workloads therefore require the
process or container boundary.

---

## 7. Compatibility and acceptance

Each feature moves through four stages:

| Stage | Evidence |
|---|---|
| T0 Parsed | Grammar rule plus a parser test |
| T1 Executes | Compiler and VM test with the expected output |
| T2 Matches CPython | Differential test against CPython 3.14 for output, error type, and error text |
| T3 Library-ready | Standard-library module present, documented, and covered by integration tests |

A feature is "supported" only at T1 or above, and the README table says which
stage it reached. Negative tests are required: wrong argument counts, wrong
types, uncaught errors, and traceback shape.

The exception hierarchy and the bridge policy each need their own integration
tests before the next layer is built on top of them.

---

## 8. Non-goals

- Bytecode compatibility with CPython. The instruction set is internal.
- CPython C extensions and the C API.
- An in-process sandbox for untrusted code.
- Full standard-library parity in the prototype phase.
- Free-threaded execution. The VM is single-threaded per interpreter for now.

---

## 9. Decision log

| Date | Decision |
|---|---|
| 2026-10-24 | Baseline is CPython 3.14; 3.15 features are deferred |
| 2026-10-24 | Trusted and restricted execution are separate; name filtering is not a sandbox |
| 2026-10-24 | Access policy is injected per interpreter; no global mutable allowlist |
| 2026-10-24 | `InterpreterException` is the carrier and `PyObject` is the value |
| 2026-10-24 | Exception matching uses the type hierarchy, not catch-all names |
| 2026-10-24 | Java throwables enter through `PyErrors.fromJava` only |

Changes to this document require the same review as code: update the decision
log, update the affected tests, and keep the README compatibility table in
sync.
