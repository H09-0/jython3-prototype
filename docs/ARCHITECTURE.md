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
| Security | `org.example.jython3.security` | access policy, resource limits, execution modes | JDK only |

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
- A frame owns an operand stack, a local scope, a reference to globals, a
  handler stack, and an active-exception stack. `try` pushes a handler; normal
  exit pops it; an unmatched error runs `finally` and re-raises the same value.
- The active-exception stack starts empty in each call: entering a handler
  pushes the caught value, and `END_EXCEPT_HANDLER` restores the previous one,
  so a bare `raise` only sees handlers of the current frame.
- `break`, `continue`, and `return` that leave a `try` still execute `finally`.
- Generators are currently collected eagerly. Lazy frame suspension is a
  separate change and will not alter the public `PyGenerator` API.

---

## 4. Object and type model

`PyObject` is a sealed interface. Scalar values are immutable records
(`PyInt`, `PyFloat`, `PyStr`, `PyBool`, `PyNull`). `PyList` and `PyDict` are
records that wrap mutable Java containers (`ArrayList`, `LinkedHashMap`); the
record shape is a Java implementation detail, while Python treats both as
mutable objects with identity. Mutation through one reference is visible
through every other reference, as in CPython. Objects with identity or mutable
behaviour are final classes (`PyFunction`, `PyClass`, `PyInstance`, `PyModule`,
`PyGenerator`, `PyException`, and the bridge wrappers).

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

Exception values carry a real `PyClass` (see section 5). `ExceptionTypeRegistry`
builds `BaseException`, `Exception`, and the built-in hierarchy once per
interpreter, and `PyErrors.matches` walks each class's base list instead of a
separate parent table. `SecurityError`, `JavaException`, and `JavaError` are
runtime-specific subclasses of `Exception`.

`BaseException` is marked as the exception root, and the marker is inherited
through the base list. There is no second table of exception names: a class is
an exception type if a real base is. Built-in exception classes are registered
in interpreter globals, so `except ValueError` and `class E(ValueError)` both
resolve to the same objects the registry created. Calling a class with that
marker produces a `PyException` value rather than a `PyInstance`.

The bytecode interface matches this. `except` evaluates its clause expression
and `COMPARE_EXC_TYPE` requires a `PyClass` marked as an exception type;
anything else raises `TypeError`. `raise` accepts a class (instantiated with no
arguments), a call (which already produced a value), or an existing exception
value, and the VM decides which at run time.

---

## 5. Exception model

There are two separate concepts, and the separation is intentional:

- **Carrier**: `InterpreterException extends RuntimeException`. It unwinds the
  Java stack. It holds the Python value, the raise location, and the traceback
  entries collected while frames unwind.
- **Value**: `PyException implements PyObject`. It can be stored in a variable,
  passed to a function, compared, and re-raised. It holds a non-null `PyClass`
  `type`, `args`, Python `cause`, Python `context`, and `javaCause`. Its name
  and message are derived from the type and arguments, never stored as
  competing facts.

Rules:

1. `PyErrors` is the only place that constructs the carrier. `ErrorFactoryTest`
   fails the build if another file does so.
2. `PyErrors.error(type, message)` creates Python-level errors.
   `PyErrors.errorValue(value)` wraps an existing value when re-raising.
   `PyErrors.fromJava(throwable)` is the single entry point for JVM throwables;
   it unwraps reflection wrappers, chooses the Python type through the Java
   table, and keeps the original throwable in `javaCause`. That table never
   decides Python inheritance.
3. Each interpreter owns an `ExceptionTypeRegistry`; the active registry is
   installed while its code runs. String entry points resolve a name to that
   registry's class once, and `PyException` stores the class itself.
4. `cause` and `context` are Python-level links. `cause` comes from
   `raise ... from ...`, which the grammar does not parse yet, so it is null in
   current scripts. `context` is attached when a new exception is raised while
   another one is active, and the VM never stores a Java throwable there.
5. The VM pushes the exception value on the operand stack, not its message.
   `COMPARE_EXC_TYPE` pops the clause class, keeps the value for binding, and
   calls `PyErrors.matches`. `RERAISE` throws the same value again.
6. Matching uses class identity and the real base list. `BaseException` catches
   every exception; `Exception` does not catch `SystemExit`,
   `KeyboardInterrupt`, or `GeneratorExit`.
7. Each frame keeps its own active-exception stack. Entering a handler pushes
   the caught value (`BEGIN_EXCEPT_HANDLER`); leaving it restores the previous
   one (`END_EXCEPT_HANDLER`). A callee starts empty, so a bare `raise` never
   borrows the caller's handler.
8. The compiler emits `END_EXCEPT_HANDLER` at the natural end of a handler and
   on every `return`, `break`, or `continue` that leaves one. The handler
   records the active-stack depth at registration; when the VM catches, it
   first truncates back to that depth, which drops state from handlers that the
   unwind abandoned. A new exception does not clear the active one by itself.
9. Bare `raise` re-raises the active exception, or raises `RuntimeError` when
   none is active. `raise E` instantiates `E`, `raise E(...)` uses the value the
   call produced, and `raise e` re-raises `e`; a non-exception value is a
   `TypeError`.
10. Java stack traces are disabled by default because Python raises often
   (`StopIteration` in every exhausted loop). `-Djython3.stacktraces=true`
   enables them for debugging.
11. The carrier builds the traceback while unwinding. When a handler is
   entered, the VM copies the frames into the value, so `__traceback__` remains
   available after the carrier is gone and after the `except` block exits. The
   entries are immutable records; a later re-raise appends new frames.
   `Jython3InterpreterTest.testExceptionValueKeepsTracebackAfterHandlerExits`
   covers this lifetime.

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
types that inherit from `Exception` as ordinary registered classes.

---

## 6. Java bridge and security boundary

The bridge provides class lookup, construction, method and field access,
overload resolution, and value conversion. It is useful, and it is also the
largest attack surface in the runtime.

### The access policy

`JavaBridge` no longer holds allowlists. Every interpreter owns a
`JavaAccessPolicy`, and every bridge value (`PyJavaClass`, `PyJavaObject`,
`PyJavaMethod`, `PyJavaPackage`) carries the policy that created it. The
policy gates four separate decisions:

```java
public interface JavaAccessPolicy {
    boolean canAccessClass(String binaryName);
    boolean canConstructClass(String binaryName, String constructorDescriptor);
    boolean canCallMethod(String owner, String name, String descriptor);
    boolean canReadField(String owner, String name);
    boolean canWriteField(String owner, String name);
}
```

- Access to a class does **not** imply access to its constructors, methods, or
  fields. A host can expose a read-only type, or grant a single constructor.
- Method and constructor descriptors cover parameter types only
  (`(Ljava/lang/String;I)V`), because Java does not allow two methods with the
  same name and parameter types. This lets a policy grant one overload while
  denying another.
- The policy is supplied per interpreter, never stored in a static field.
  `new Jython3Interpreter()` uses `AllowlistPolicy.trusted()`;
  `new Jython3Interpreter(policy)` uses the host's policy, including
  `DenyAllPolicy.INSTANCE`.
- Lookup filters overloads through the policy, and invocation checks again.
  A wrapper obtained through another path, such as reflection in a test, still
  cannot invoke a denied member.
- Denials raise `SecurityError` through `PyErrors`; the policy itself has no
  dependency on the error factory.

The default `AllowlistPolicy` is **defense in depth at the language level**. It
is not a sandbox:

- name-based checks can be bypassed by any path that reaches reflection;
- a permitted class can expose powerful objects through its own API;
- the JVM security manager is permanently disabled from Java 24 onward, so
  there is no in-process mechanism to fall back on.

The bridge is tested without the VM: class, constructor, method, and field
grants, overload selection, conversion, and per-interpreter isolation are
covered by `JavaAccessPolicyTest`.

### Execution modes

| Mode | Use case | Mechanism |
|---|---|---|
| **Trusted** | Host application runs its own scripts | Host supplies a `JavaAccessPolicy`; the default mirrors the documented allowlist |
| **Restricted** | Untrusted code | Separate OS process or container, not an in-process sandbox: read-only filesystem, no network by default, CPU/memory/time limits, bridge disabled unless granted, results over IPC |

Restricted mode is a deployment property. The interpreter may support it by
exposing a minimal runner, but it must never claim that class-name filtering
makes arbitrary code safe inside the same JVM.

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
| 2026-10-24 | Class access does not imply constructor access; each constructor is granted by descriptor |
| 2026-10-24 | The exception value owns a traceback snapshot so it outlives the carrier |
| 2026-10-24 | Exception values carry a non-optional `PyClass`; names are derived, and each interpreter owns its type registry |
| 2026-10-24 | `except` evaluates its clause to a class; a non-exception type is a `TypeError` |
| 2026-10-24 | Custom classes inherit exception status from their bases, and calling one produces a value, not an instance |
| 2026-10-24 | The active exception is a per-frame stack; handler entry pushes, `END_EXCEPT_HANDLER` and handler exit restore |
| 2026-10-24 | `InterpreterException` is the carrier and `PyObject` is the value |
| 2026-10-24 | Exception matching uses the type hierarchy, not catch-all names |
| 2026-10-24 | Java throwables enter through `PyErrors.fromJava` only |

Changes to this document require the same review as code: update the decision
log, update the affected tests, and keep the README compatibility table in
sync.
