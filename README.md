# Jython 3

**A modern Python runtime for the JVM.**

[![Build](https://img.shields.io/badge/Build-Passing-brightgreen?logo=github-actions)](https://github.com/H09-0/jython3-prototype)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-red?logo=openjdk)](https://openjdk.org/projects/jdk/21/)
[![Tests](https://img.shields.io/badge/Tests-186%20Passing-brightgreen)](https://github.com/H09-0/jython3-prototype)

Jython 3 is an independent re-implementation of Python 3 on the Java Virtual
Machine, built from the ground up with a layered architecture, an explicit
Java bridge, and security treated as a design input rather than an afterthought.
It follows the goals of the [official Jython 3 Roadmap](https://www.jython.org/jython-3-roadmap.html)
but is not an official Jython release and is not endorsed by the upstream project.

The project targets **CPython 3.14** as its compatibility baseline, implemented
with Java 21, an ANTLR4 grammar, and a compact stack-machine bytecode runtime.
See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the layer boundaries, the
exception model, and the trusted vs. restricted execution modes.

> **This is pre-production software.** It is intended for research, education, and iterative
> development of a Python 3 runtime for the JVM. It is not a drop-in replacement for CPython
> or Jython 2.7, and it is not a sandbox for untrusted code.

For background on the long-term vision, see:
- [Jython Official Website](https://www.jython.org/)
- [Jython 3 Roadmap](https://www.jython.org/jython-3-roadmap.html)
- [Jython GitHub Repository](https://github.com/jython/jython)

---

## Compatibility

The compatibility target is CPython 3.14. The current interpreter implements a
growing subset of that baseline; the table below is the source of truth for what
actually works. A feature is listed as supported only when it has a passing
test, and newer 3.15-only syntax is out of scope until the 3.14 baseline is
green.

Stage definitions: **T0** parsed, **T1** executes under the VM, **T2** matches
CPython 3.14 output and errors, **T3** library-ready. Every row below that says
Supported has passed T1; `—` marks work that has not started.

| Feature | Stage | Status |
|---------|-------|--------|
| Integer and string literals | T1 | ✅ Supported |
| List literals and subscript access (`xs[i]`) | T1 | ✅ Supported |
| Variable assignment and arithmetic (`+`, `-`, `*`, `/`) | T1 | ✅ Supported |
| Operator precedence and parenthesised expressions | T1 | ✅ Supported |
| `print()` built-in | T1 | ✅ Supported |
| `if` / `else` conditional statements | T1 | ✅ Supported |
| `while` loops | T1 | ✅ Supported |
| `for ... in ...` loops with list iteration | T1 | ✅ Supported |
| `break` and `continue` (including nested loops) | T1 | ✅ Supported |
| `yield` and generator iteration | T1 | ✅ Supported (eager, no `send`/`throw`) |
| `execfile(path)` | T1 | ✅ Supported |
| Function definitions (`def`), parameters, `return` | T1 | ✅ Supported |
| Nested and recursive function calls | T1 | ✅ Supported |
| Local scope isolation per function frame | T1 | ✅ Supported |
| `import` (file-based modules from `modules/`) | T1 | ✅ Supported |
| `import math` — `sqrt`, `sin`, `cos`, `floor`, `ceil`, `pi`, `e` | T1 | ✅ Supported |
| `import sys` — `argv`, `version`, `platform` | T1 | ✅ Supported |
| `import os` — `getcwd()`, `sep`, `name` | T1 | ✅ Supported |
| Module attribute access (`math.sqrt`, `sys.argv`) | T1 | ✅ Supported |
| `PyFloat` runtime type for floating-point results | T1 | ✅ Supported |
| Tracebacks: `NameError`, `ZeroDivisionError`, `TypeError`, `AttributeError`, `IndexError` | T1 | ✅ Supported |
| Real bytecode layer (`CompiledCode`: `int[]` + `Object[]` constants pool) | T1 | ✅ Supported |
| Exception handling (`try` / `except` / `else` / `finally` / `raise`) | T1 | ✅ Supported |
| Multiple `except` clauses and `except Exception` catch-all | T1 | ✅ Supported |
| Classes, inheritance, bound methods, decorators | T1 | ✅ Supported |
| Java bridge (`from java.lang import Math`) | T1 | ✅ Supported |
| Per-interpreter Java access policy | T1 | ✅ Supported |
| `re`, `json`, and `io` modules | T1 | ✅ Supported |
| `collections`, comprehensions, true division `/` | — | 🔜 Planned |
| Maven Central packaging | — | 🔜 Planned |

### Known Limitations

- Generators are evaluated eagerly: the function runs to completion on the first
  iteration. `send()`, `throw()`, and `close()` are not implemented.
- Exception values carry a type, message, arguments, Python cause/context, and
  the original Java throwable, but exception *types* are still resolved through
  a name table. User-defined `class E(Exception)` types are not yet first-class;
  that is the next runtime slice.
- No `with`, `async`/`await`, comprehensions, `lambda`, f-strings, or `match`
  statements yet.
- Java access is gated per interpreter by `JavaAccessPolicy`. The default
  `AllowlistPolicy` rejects known dangerous classes by name; it is defense in
  depth, not a sandbox, and untrusted code must run in a separate process or
  container.

---

## Architecture

The runtime is split into layers with explicit dependency rules — entry points,
parser, compiler, VM, runtime, standard library, Java bridge, and security.
The full contract lives in [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md); the
diagram below shows the execution pipeline.

```
Python 3 Source (string)
        │
        ▼
┌───────────────────────┐
│   ANTLR4 Lexer        │  Tokenises source using Jython3.g4 grammar rules
│   + Parser            │  Produces a concrete parse tree
└───────────┬───────────┘
            │
            ▼
┌───────────────────────┐
│   CompileVisitor      │  Walks the parse tree (Visitor pattern)
│   + CodeBuilder       │  Emits bytecode into int[] + Object[] constPool
└───────────┬───────────┘
            │
            ▼
┌───────────────────────┐
│   CompiledCode        │  Immutable record: { int[] bytecode, Object[] constPool }
│                       │  Each instruction: (opcode << 16) | constPoolIndex
└───────────┬───────────┘
            │
            ▼
┌───────────────────────┐
│   Stack Machine       │  Decodes and executes bytecode in a while-loop dispatch
│   run()               │  Operand stack (Deque<PyObject>), call stack (Deque<Frame>)
└───────────────────────┘
```

### Bytecode Encoding

Each instruction occupies a single 32-bit integer:

```
 31          16 15           0
 ┌─────────────┬─────────────┐
 │ OpCode (16) │ Operand(16) │
 │   ordinal   │  constPool  │
 │             │  index or   │
 │             │  raw int    │
 └─────────────┴─────────────┘
```

### Runtime Object Model

The Python object hierarchy is encoded as a Java 21 **sealed interface**. Simple values
are records; objects with mutable state are final classes. The permit list lives in
`runtime/PyObject.java` and includes the core types plus the Java bridge wrappers:

```java
public sealed interface PyObject
        permits PyInt, BigInt, PyStr, PyNull, PyBool, PyFloat,
                PyFunction, PyBuiltinFunction, PyList, PyDict, PyIterator,
                PyModule, PyClass, PyInstance, PyBoundMethod, PySuper,
                PyException, PyGenerator, PyCompiledCodeWrapper,
                PyJavaClass, PyJavaObject, PyJavaMethod, PyJavaPackage {}

public record PyInt(int value)                       implements PyObject {}
public record PyStr(String value)                    implements PyObject {}
public record PyNull()                               implements PyObject {}
public record PyBool(boolean value)                  implements PyObject {}
public record PyFloat(double value)                  implements PyObject {}
public record PyList(List<PyObject> items)           implements PyObject {}
public record PyDict(Map<PyObject, PyObject> map)    implements PyObject {}

public final class PyFunction implements PyObject { /* name, params, code, closure */ }
public final class PyClass    implements PyObject { /* name, bases, members */ }
public final class PyGenerator implements PyObject { /* eagerly collected yields */ }
```

### Security model

The Java bridge is useful and is also the largest attack surface in the
runtime. Access is deny-by-default behind a policy that the host application
supplies per interpreter. Two modes are supported by design:

- **Trusted execution** for scripts owned by the host, with an explicit grant
  of Java classes and members.
- **Restricted execution** for untrusted code, which must run in a separate
  process or container with OS-level filesystem, network, memory, and time
  limits. The JVM security manager is permanently disabled from Java 24
  onward, so class-name filtering inside one JVM is not a sandbox.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md#6-java-bridge-and-security-boundary)
for the policy interface and the bridge test plan.

### Standard Library

Built-in modules are resolved at import time via `BuiltinModules.java` before falling back
to file-based modules in the `modules/` directory:

```
import math  ──▶  BuiltinModules.math()  ──▶  PyModule { sqrt, sin, cos, pi, e, … }
import sys   ──▶  BuiltinModules.sys()   ──▶  PyModule { argv, version, platform, … }
import os    ──▶  BuiltinModules.os()    ──▶  PyModule { getcwd(), sep, name, … }
import foo   ──▶  modules/foo.py         ──▶  file-based fallback
```

### Instruction Set

| OpCode | Operand | Description |
|--------|---------|-------------|
| `LOAD_CONST` | constPool idx | Push integer, string, or PyFunction constant |
| `LOAD_FAST` | constPool idx | Push variable from local or global scope |
| `LOAD_GLOBAL` | constPool idx | Push variable from global scope only |
| `LOAD_ATTR` | constPool idx | Pop object; push named attribute |
| `STORE_FAST` | constPool idx | Pop value; write to current scope |
| `ADD` / `SUBTRACT` / `MULTIPLY` / `DIVIDE` | — | Integer binary arithmetic |
| `PRINT` | — | Pop and print top of stack |
| `JUMP_IF_FALSE` | target pc | Conditional branch |
| `JUMP` | target pc | Unconditional branch |
| `IMPORT_MODULE` | constPool idx | Load built-in or file-based module into globals |
| `MAKE_FUNCTION` | constPool idx | Pop PyFunction; register in current scope |
| `CALL_FUNCTION` | argc | Dispatch call to PyFunction or PyBuiltinFunction |
| `RETURN_VALUE` | — | Pop return value; restore caller frame |
| `POP_TOP` | — | Discard top of stack |
| `BUILD_LIST` | count | Collect N stack items into a PyList |
| `BINARY_SUBSCR` | — | Pop index and container; push `container[index]` |
| `GET_ITER` | — | Convert PyList on stack to PyIterator |
| `FOR_ITER` | exit pc | Advance iterator; jump when exhausted |
| `PUSH_EXCEPT_HANDLER` | except pc | Register an exception handler for a `try` block |
| `POP_EXCEPT_HANDLER` | — | Deregister handler on normal exit from `try` |
| `COMPARE_EXC_TYPE` | — | Pop type name; push whether the current exception matches |
| `RAISE` | constPool idx | Raise an exception (type name from pool) |
| `RERAISE` | — | Propagate an unmatched exception |
| `YIELD_VALUE` | — | Emit a value from a generator function |
| `EXECFILE` | — | Execute a script file in the current scope |
| `HALT` | — | Terminate execution |

---

## Getting Started

### Prerequisites

| Tool | Minimum Version | Notes |
|------|----------------|-------|
| Java JDK | **21** | OpenJDK or Oracle JDK |
| Gradle | **9.x** | Wrapper included — no separate installation required |
| Git | any | For cloning the repository |

### Cloning the Repository

```bash
git clone https://github.com/H09-0/jython3-prototype.git
cd jython3-prototype
```

---

## Building the Project

Jython 3 Prototype uses Gradle with the Kotlin DSL. The Gradle wrapper (`gradlew`) is included
in the repository — no separate Gradle installation is required.

```bash
# Generate ANTLR4 parser sources, compile all Java, and run the full test suite
./gradlew clean build
```

The build will automatically:
1. Run the ANTLR4 tool against `src/main/antlr/.../Jython3.g4` to generate lexer, parser, and visitor classes.
2. Compile all Java source files under `src/main/java/`.
3. Execute the JUnit 5 test suite under `src/test/java/`.

---

## Running the Tests

```bash
./gradlew test
```

Expected output:

```
BUILD SUCCESSFUL in Xs
5 actionable tasks: 5 executed
```

All 186 tests pass with zero failures and zero skipped. Test reports are written to:

```
build/reports/tests/test/index.html
```

---

## Project Structure

```
jython3-prototype/
├── build.gradle.kts                             # Gradle build script (Java 21, ANTLR4, JUnit 5)
├── settings.gradle.kts                          # Root project name
├── gradlew / gradlew.bat                        # Gradle wrapper scripts
├── LICENSE                                      # MIT License
├── README.md
├── CONTRIBUTING.md
│
├── modules/                                     # File-based importable Python modules
│   └── testmod.py
│
├── docs/
│   └── ARCHITECTURE.md                         # Layer contract, exceptions, security
│
└── src/
    ├── main/
    │   ├── antlr/org/example/jython3/
    │   │   └── Jython3.g4                       # ANTLR4 grammar — source of truth for syntax
    │   └── java/org/example/jython3/
    │       ├── Jython3Interpreter.java          # VM, imports, and public exec/eval API
    │       ├── runtime/                         # PyObject hierarchy and value operations
    │       ├── compile/                         # OpCode, CodeBuilder, CompileVisitor
    │       ├── bridge/                          # JavaBridge reflection and conversion
    │       ├── security/                        # JavaAccessPolicy and shipped policies
    │       ├── stdlib/                          # Builtins plus math, sys, os, io, json, re
    │       ├── parse/                           # PythonIndentStream (INDENT / DEDENT)
    │       └── cli/                             # Repl and RunScript
    └── test/
        └── java/org/example/jython3/
            ├── Jython3InterpreterTest.java      # 162 JUnit 5 regression tests
            ├── JavaAccessPolicyTest.java        # 9 bridge and access-policy tests
            └── ErrorFactoryTest.java            # 15 error-model guard tests
```

The object types (`PyInt`, `PyFunction`, `PyClass`, and the Java wrappers) live in
`org.example.jython3.runtime`. `PyObject` does not name `JavaBridge`; the bridge
types sit beside it and call into `org.example.jython3.bridge`.

See [docs/ARCHITECTURE.md](docs/ARCHITECTURE.md) for the layer contract,
exception model, and security boundary.

---

## Development Roadmap

| Phase | Release | Scope | Status |
|-------|---------|-------|--------|
| 1 | `v0.1.0-alpha` | Interpreter scaffold, `print()`, integer literals, `HALT` | ✅ Released |
| 2 | `v0.2.0-beta` | Variable assignment, arithmetic, `PyInt`, `PyStr` | ✅ Released |
| 3 | `v0.3.0-rc1` | `if/else`, `while`, `import`, traceback, raw bytecode | ✅ Released |
| 4 | `v0.4.0-alpha` | ANTLR4 grammar, `CompileVisitor`, 26 tests | ✅ Released |
| 5 | `v0.5.0-alpha` | `def`, `return`, call stack, `Frame` record, 37 tests | ✅ Released |
| 6 | `v0.6.0-alpha` | `CompiledCode`, constants pool, lists, `for` loops, 49 tests | ✅ Released |
| 7 | `v0.7.0-alpha` | `math`/`sys`/`os` stdlib, `PyFloat`, `PyModule`, `LOAD_ATTR`, 64 tests | ✅ Released |
| 8 | `v0.8.0-alpha` | Exception handling (`try`/`except`/`raise`), cross-frame propagation, 74 tests | ✅ Released |
| 9–11 | `v0.9.0-alpha` | Indentation, classes, decorators, Java bridge, package split, 133 tests | ✅ Released |
| 12 | `v0.10.0-alpha` | Loop control, exception clauses, generators, REPL blocks, `execfile`, 144 tests | ✅ Released |
| 13 | `v1.0.0` | Stable API, Maven Central, true division, comprehensions | 🎯 Target |

---

## How to Build a JAR

```bash
./gradlew jar
# Output: build/libs/myjython3.jar
```

---

## Contributing

Contributions are welcome. Please follow these steps:

1. Fork the repository and create a branch from `master`.
2. Ensure all existing tests pass (`./gradlew test`).
3. Add tests for any new behaviour.
4. Open a pull request with a clear description of the change.

See [CONTRIBUTING.md](CONTRIBUTING.md) for the full contribution guide, coding standards,
and commit message format.

---

## Acknowledgements

This project is inspired by the work of the Jython team and the broader Python-on-JVM community.
See the [official Jython project](https://www.jython.org/) and its contributors for the
foundational ideas that inform this prototype.

---

## License

Jython 3 Prototype is distributed under the **MIT License**.
See the [`LICENSE`](LICENSE) file for the full text.

---

*This is a personal research prototype. It is not affiliated with, endorsed by, or a
replacement for the official Jython project or the Python Software Foundation.*
