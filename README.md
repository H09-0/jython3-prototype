# Jython 3 Prototype

[![Java CI](https://github.com/H09-0/jython3-prototype/actions/workflows/test.yml/badge.svg)](https://github.com/H09-0/jython3-prototype/actions)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Status: Pre-Production](https://img.shields.io/badge/Status-Pre--Production%20Alpha-yellow.svg)](docs/ROADMAP.md)

An experimental Python runtime for the JVM, written in Java.

Jython 3 is an independent re-implementation inspired by the original Jython project's goals. It targets a documented subset of Python 3.14 and provides a custom compiler, stack-based virtual machine, Python object model, and Java bridge.

The implementation is currently pre-production. Python 3.14 compatibility is tracked feature-by-feature in the [Compatibility Matrix](docs/COMPATIBILITY.md).

> **Important**: This is an independent research prototype. It is not an official release of the original Jython project and is not endorsed by the upstream project.

---

## Key Highlights

- **Python 3 Compatibility**: Targets a documented subset of Python 3.14, including indentation blocks, OOP, exception models, and standard built-ins.
- **Direct JVM Execution**: Pure Java 21+ stack-based bytecode virtual machine with zero C-dependencies.
- **Java Bridge**: Bidirectional Java reflection bridge allowing scripts to directly instantiate and interact with JVM classes.
- **Defense-in-Depth Security**: Configurable access policies (`AllowlistPolicy`, `SandboxedPolicy`, `DenyAllPolicy`) with recursive inner-class containment and circular reference safety.
- **Automated Verification**: Comprehensive regression test suite verified in continuous integration.

> **Security Note**: Access policies restrict Java interop but do not provide an in-process security sandbox. To safely execute untrusted code, run the interpreter in a separate, isolated OS process or container with CPU, memory, and timeout limits. See [SECURITY.md](SECURITY.md) for full details.

---

## Quick Example

```python
import math
import time
from java.util import ArrayList, HashMap

# 1. Native Python 3 OOP & Built-ins
class Greeter:
    def __init__(self, greeting):
        self.greeting = greeting
    def say_hello(self, name):
        print(self.greeting + ", " + name + "!")

g = Greeter("Hello from Jython 3")
g.say_hello("World")

# 2. JVM Interoperability
users = ArrayList()
users.add("Alice")
users.add("Bob")
print("Total JVM list items:", users.size())

# 3. High-Precision Time
print("Current Unix Epoch Nanos:", time.time_ns())
```

---

## Quick Start & Installation

### Prerequisites
- **Java Development Kit (JDK) 21** or higher.
- Linux, macOS, or Windows.

### Building & Running Tests
```bash
git clone https://github.com/H09-0/jython3-prototype.git
cd jython3-prototype

# Run automated unit tests
./gradlew test

# Start the interactive REPL
./gradlew repl

# Run a Python script file
./gradlew runScript --args="demo.py"
```

### Installing as a System CLI Command
To install the standalone launcher (`jython3`) locally into your environment:
```bash
./gradlew installDist
mkdir -p ~/.local/share/jython3
cp -r build/install/jython3/* ~/.local/share/jython3/
ln -sf ~/.local/share/jython3/bin/jython3 ~/.local/bin/jython3
```

You can now use `jython3` anywhere from your terminal:
```bash
# Start interactive REPL
jython3

# Execute inline Python 3 code
jython3 -c "import math; print('Sqrt of 144 is:', math.sqrt(144))"

# Execute a Python file
jython3 my_script.py arg1 arg2
```

---

## Field Testing Results (v0.10.2-alpha)

A comprehensive field test was conducted by running identical Python scripts
through the `jython3` CLI and CPython 3.12.3, comparing outputs with `diff`.

### ✅ Fully CPython-Identical (33 / 48 features)

| Area | Status |
|:-----|:------:|
| Integer & BigInt arithmetic (all operators, all sign combinations) | ✅ |
| Float arithmetic & true division | ✅ |
| String operations (concat, slice, methods, `in`) | ✅ |
| List operations (CRUD, slice, methods, iteration) | ✅ |
| Dict operations (CRUD, `del`, `.get()`, `.keys()`, iteration) | ✅ |
| Control flow (`if`/`elif`/`else`, `for`, `while`, `break`, `continue`) | ✅ |
| Functions (def, return, recursion, closures, nested, higher-order) | ✅ |
| Exception handling (`try`/`except`/`finally`, `raise`, tuple-except) | ✅ |
| Classes & OOP (inheritance, class vars, method chaining) | ✅ |
| 20+ built-in functions (print, len, sorted, map, filter, …) | ✅ |
| stdlib: math, time, json, random, os, io | ✅ |
| Edge cases: None, falsy values, chained comparisons, deep recursion | ✅ |

### 🔴 Known Bugs (4 issues)

| ID | Bug | Root Cause |
|:--:|:----|:-----------|
| C1 | `break` in nested `for` loops → infinite loop | Compiler does not pop inner iterator from VM stack before break-jump |
| H1 | `isinstance(42, int)` → `False` | Built-in types are `PyBuiltinFunction`, not `PyClass`; isinstance only checks `PyClass` |
| H2 | `True + True` → `2.0` (float) | `PyBool` not unwrapped to `PyInt` before arithmetic dispatch |
| H3 | `"%d %s" % (10, "hi")` → `"10 10"` | Formatter uses only the first tuple element for all placeholders |

### 🟡 Not Yet Implemented (7 features)

`lambda` · list comprehensions · f-strings · default arguments · tuple unpacking · `global`/`nonlocal` · chained assignment

> 📄 **[Full Report with Root-Cause Analysis →](docs/FIELD_TEST_REPORT.md)**

---

## Architecture at a Glance

The interpreter compiles Python source text into a compact 32-bit bytecode format executed by an optimized stack machine:

```
Source Code
    │
    ▼  (ANTLR4 + PythonIndentStream)
AST Parse Tree
    │
    ▼  (CompileVisitor)
CompiledCode (32-bit Bytecode + Constant Pool)
    │
    ▼
Stack Machine Loop (Jython3Interpreter.run())
    ├── Runtime Object Model (PyInt, BigInt, PyFloat, PyStr, PyList, PyDict, PyClass...)
    ├── Java Bridge Dispatch (JavaBridge, PyJavaClass, PyJavaObject)
    └── Standard Modules (math, sys, os, io, json, re, time, random)
```

---

## Detailed Documentation

To explore in-depth technical documentation, please consult the dedicated documentation guides:

- 📖 **[Project Structure](docs/PROJECT_STRUCTURE.md)**: Full codebase tree and package responsibilities.
- 🧪 **[Field Test Report](docs/FIELD_TEST_REPORT.md)**: Comprehensive testing results with root-cause analysis for all known issues.
- 🎯 **[Compatibility Matrix](docs/COMPATIBILITY.md)**: Feature-by-feature comparison against CPython 3.14 (T0–T3 tiers).
- 🗺️ **[Development Roadmap](docs/ROADMAP.md)**: Current milestones and upcoming architectural phases.
- 🏗️ **[Architecture & VM Specification](docs/ARCHITECTURE.md)**: Bytecode encoding, call frame lifecycle, and dispatch.
- 📦 **[Standard Library Status](docs/STDLIB.md)**: Documentation of all built-in modules (`math`, `sys`, `os`, `io`, `json`, `re`, `time`, `random`).
- ☕ **[Java Bridge Guide](JAVA_BRIDGE.md)**: Complete guide to calling Java from Python and embedding Jython in Java.
- 🛡️ **[Security Policy](SECURITY.md)**: Threat model, trust boundaries, sandboxing, and vulnerability reporting.
- 📜 **[Changelog](CHANGELOG.md)**: Version release history and changes.
- 🤝 **[Contributing Guidelines](CONTRIBUTING.md)**: How to set up development, write tests, and submit PRs.

---

## License

This project is licensed under the [MIT License](LICENSE).
