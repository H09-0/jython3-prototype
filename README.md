# Jython 3 Prototype

**Python 3 for the Java Platform — a modern, iterative re-implementation of the Jython
interpreter targeting Python 3.x on the JVM.**

[![Build](https://img.shields.io/badge/Build-Passing-brightgreen?logo=github-actions)](https://github.com/H09-0/jython3-prototype)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Version](https://img.shields.io/badge/Version-v0.8.0--alpha-orange)](https://github.com/H09-0/jython3-prototype/releases)
[![Java](https://img.shields.io/badge/Java-21%2B-red?logo=openjdk)](https://openjdk.org/projects/jdk/21/)
[![ANTLR](https://img.shields.io/badge/Parser-ANTLR%204.13.1-purple)](https://www.antlr.org/)
[![Tests](https://img.shields.io/badge/Tests-133%20Passing-brightgreen)](https://github.com/H09-0/jython3-prototype)

---

## Overview

Jython 3 Prototype is an open-source, experimental implementation of a Python 3 interpreter
running on the Java Virtual Machine (JVM). The project is inspired by and follows the
architectural direction of the [official Jython 3 Roadmap](https://www.jython.org/jython-3-roadmap.html),
with the goal of delivering full Python 3 compatibility on a modern Java runtime.

Unlike the production Jython 2.7 release, which targets Python 2.7, this project exclusively
targets **Python 3.x syntax and semantics**. It is built from the ground up using Java 21
language features, an ANTLR4-driven grammar, and a compact stack-machine bytecode runtime —
a foundation designed to scale toward a full, production-grade Python 3 runtime on the JVM.

> **This is pre-production software.** It is intended for research, education, and iterative
> development of the Jython 3 architecture. It is not a drop-in replacement for CPython or
> Jython 2.7.

For background on the long-term vision, see:
- [Jython Official Website](https://www.jython.org/)
- [Jython 3 Roadmap](https://www.jython.org/jython-3-roadmap.html)
- [Jython GitHub Repository](https://github.com/jython/jython)

---

## Compatibility

Jython 3 Prototype provides support for a growing subset of the Python 3 language.
The table below summarises the current state of language and runtime support.

| Feature | Status |
|---------|--------|
| Integer and string literals | ✅ Supported |
| List literals and subscript access (`xs[i]`) | ✅ Supported |
| Variable assignment and arithmetic (`+`, `-`, `*`, `/`) | ✅ Supported |
| Operator precedence and parenthesised expressions | ✅ Supported |
| `print()` built-in | ✅ Supported |
| `if` / `else` conditional statements | ✅ Supported |
| `while` loops | ✅ Supported |
| `for ... in ...` loops with list iteration | ✅ Supported |
| Function definitions (`def`), parameters, `return` | ✅ Supported |
| Nested and recursive function calls | ✅ Supported |
| Local scope isolation per function frame | ✅ Supported |
| `import` (file-based modules from `modules/`) | ✅ Supported |
| `import math` — `sqrt`, `sin`, `cos`, `floor`, `ceil`, `pi`, `e` | ✅ Supported |
| `import sys` — `argv`, `version`, `platform` | ✅ Supported |
| `import os` — `getcwd()`, `sep`, `name` | ✅ Supported |
| Module attribute access (`math.sqrt`, `sys.argv`) | ✅ Supported |
| `PyFloat` runtime type for floating-point results | ✅ Supported |
| Tracebacks: `NameError`, `ZeroDivisionError`, `TypeError`, `AttributeError`, `IndexError` | ✅ Supported |
| Real bytecode layer (`CompiledCode`: `int[]` + `Object[]` constants pool) | ✅ Supported |
| Exception handling (`try` / `except` / `raise`) | ✅ Supported |
| Classes, inheritance, bound methods, decorators | ✅ Supported |
| Java bridge (`from java.lang import Math`) | ✅ Supported |
| `re`, `json`, and `io` modules | ✅ Supported |
| `collections`, comprehensions, true division `/` | 🔜 Planned |
| Maven Central packaging | 🔜 Planned |

---

## Architecture

The interpreter pipeline is composed of four distinct layers:

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

The Python object hierarchy is encoded as a Java 21 **sealed interface** with **record**
implementations, enabling exhaustive pattern-matching switches throughout the runtime:

```java
public sealed interface PyObject
        permits PyInt, PyStr, PyNull, PyFloat,
                PyFunction, PyBuiltinFunction,
                PyList, PyIterator, PyModule {}

public record PyInt(int value)                                      implements PyObject {}
public record PyStr(String value)                                   implements PyObject {}
public record PyNull()                                              implements PyObject {}
public record PyFloat(double value)                                 implements PyObject {}
public record PyFunction(String name, List<String> params,
                         CompiledCode code)                         implements PyObject {}
public record PyBuiltinFunction(String name,
                         Function<PyObject[], PyObject> fn)         implements PyObject {}
public record PyList(List<PyObject> items)                          implements PyObject {}
public record PyModule(String name, Map<String, PyObject> attrs)    implements PyObject {}
public final  class PyIterator                                      implements PyObject {}
```

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
| `RAISE` | constPool idx | Raise an exception (type name from pool) |
| `HALT` | — | Terminate execution |

---

## Getting Started

### Prerequisites

| Tool | Minimum Version | Notes |
|------|----------------|-------|
| Java JDK | **21** | OpenJDK or Oracle JDK |
| Gradle | **8.x** | Wrapper included — no separate installation required |
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

All 133 tests pass with zero failures and zero skipped. Test reports are written to:

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
│   └── architecture.md                          # Package map and pipeline
│
└── src/
    ├── main/
    │   ├── antlr/org/example/jython3/
    │   │   └── Jython3.g4                       # ANTLR4 grammar — source of truth for syntax
    │   └── java/org/example/jython3/
    │       ├── Jython3Interpreter.java          # VM, imports, and public exec/eval API
    │       ├── runtime/                         # PyObject hierarchy and value operations
    │       ├── compile/                         # OpCode, CodeBuilder, CompileVisitor
    │       ├── bridge/                          # JavaBridge reflection and security checks
    │       ├── stdlib/                          # Builtins plus math, sys, os, io, json, re
    │       ├── parse/                           # PythonIndentStream (INDENT / DEDENT)
    │       └── cli/                             # Repl and RunScript
    └── test/
        └── java/org/example/jython3/
            └── Jython3InterpreterTest.java      # 133 JUnit 5 regression tests
```

The object types (`PyInt`, `PyFunction`, `PyClass`, and the Java wrappers) live in
`org.example.jython3.runtime`. `PyObject` does not name `JavaBridge`; the bridge
types sit beside it and call into `org.example.jython3.bridge`.

See [docs/architecture.md](docs/architecture.md) for the pipeline and package roles.

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
| 9–11 | working tree | Indentation, classes, decorators, Java bridge, package split, 133 tests | ✅ In tree |
| 12 | `v1.0.0` | Stable API, Maven Central, true division, comprehensions | 🎯 Target |

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
