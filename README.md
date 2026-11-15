# Jython 3 Prototype

[![Java CI](https://github.com/H09-0/jython3-prototype/actions/workflows/test.yml/badge.svg)](https://github.com/H09-0/jython3-prototype/actions)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Status: Pre-Production](https://img.shields.io/badge/Status-Pre--Production%20Alpha-yellow.svg)](docs/STATUS.md)

An experimental Python 3 runtime for the JVM, written in pure Java 21+.

Jython 3 is an independent re-implementation inspired by the original Jython project's goals. It targets a documented subset of Python 3.14 and provides an ANTLR4-based compiler, stack-based bytecode virtual machine, Python object model, and bidirectional Java reflection bridge.

> **Current Status**: See **[docs/STATUS.md](docs/STATUS.md)** (single source of truth) and **[docs/COMPATIBILITY.md](docs/COMPATIBILITY.md)** for live feature coverage.
> **Important**: This is an independent research prototype. It is not an official release of the original Jython project and is not endorsed by the upstream project.

---

## Key Highlights

- **Python 3 Execution Engine**: Supports core Python 3 syntax (classes & inheritance, `super()`, closures, `global`/`nonlocal`, comprehensions, f-strings, raw strings, `lambda`, `with` context managers, generators, and exception handling).
- **Direct JVM Execution**: Pure Java 21+ stack-based bytecode VM (`FastStack`, version-guarded inline attribute caches, small-int caching) with zero native C dependencies.
- **Java Bridge**: Bidirectional Java reflection bridge with policy-scoped overload resolution caching and specialized array/collection conversion plans.
- **Defense-in-Depth Security**: Configurable access policies (`AllowlistPolicy`, `SandboxedPolicy`, `DenyAllPolicy`), `HostAccessPolicy`, and `ExecutionLimits` (instruction budget, timeouts, allocation caps).
- **Automated Verification**: Unit, integration, CPython differential conformance, performance benchmark, and field/leak profiling suites verified in CI.

> **Security Note**: Access policies restrict Java interop but do not provide an in-process security sandbox. To safely execute untrusted code, run the interpreter in a separate, isolated OS process or container with CPU, memory, and timeout limits. See [SECURITY.md](SECURITY.md) for full details.

---

## Quick Example

```python
import math
import time
from java.util import ArrayList

# 1. Native Python 3 OOP & Built-ins
class Greeter:
    def __init__(self, greeting):
        self.greeting = greeting
    def say_hello(self, name):
        print(f"{self.greeting}, {name}!")

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

## Quick Start & Commands

### Prerequisites
- **Java Development Kit (JDK) 21** or higher.
- Linux, macOS, or Windows.

### Building, Testing & Profiling
```bash
git clone https://github.com/H09-0/jython3-prototype.git
cd jython3-prototype

# Run full unit, integration & CPython differential test suite
./gradlew test

# Run real-world field tests & RAM/CPU/leak profiler
./gradlew fieldTest

# Run performance benchmark suite
./gradlew benchmark

# Start the interactive REPL
./gradlew repl

# Run a Python script file
./gradlew runScript --args="demo.py"
```

### Installing as a System CLI Command
```bash
./gradlew installDist
mkdir -p ~/.local/share/jython3
cp -r build/install/jython3/* ~/.local/share/jython3/
ln -sf ~/.local/share/jython3/bin/jython3 ~/.local/bin/jython3
```

---

## Architecture at a Glance

```text
Python Source
    │
    ▼  (ANTLR4 Jython3Lexer -> PythonIndentStream -> Jython3Parser)
Parse Tree
    │
    ▼  (CompileVisitor -> CodeBuilder)
CompiledCode (32-bit Bytecode + Constant Pool + Inline Attribute Cache)
    │
    ▼  (Jython3Interpreter VM Loop + FastStack)
    ├── Runtime Object Model (PyInt, BigInt, PyFloat, PyStr, PyList, PyDict, PyClass, PyNull...)
    ├── Java Bridge Dispatch (JavaBridge, Method/Converter Caches, JavaAccessPolicy)
    └── Standard Library (math, sys, os, io, json, re, time, random)
```

---

## Documentation Index

- 📌 **[Current Status & Concept Glossary](docs/STATUS.md)** — Live project status and runtime glossary (`PyNull` vs `null` vs `None`, `PyCaller`, `PyCell`, `ExecutionContext`).
- 🎯 **[Compatibility Matrix](docs/COMPATIBILITY.md)** — Feature-by-feature comparison against CPython 3.14 (`T0`–`T3`).
- 🗺️ **[Development Roadmap](docs/ROADMAP.md)** — Upcoming optimization steps and future phases.
- 🏗️ **[Architecture & VM Specification](docs/ARCHITECTURE.md)** — Execution pipeline, layer rules, bytecode encoding, and frame lifecycle.
- 📖 **[Project Structure](docs/PROJECT_STRUCTURE.md)** — Full codebase tree and package responsibilities.
- 📦 **[Standard Library Reference](docs/STDLIB.md)** — Built-in modules (`math`, `sys`, `os`, `io`, `json`, `re`, `time`, `random`).
- ☕ **[Java Bridge Guide](JAVA_BRIDGE.md)** — Calling Java from Python and embedding Jython in Java.
- 🛡️ **[Security Policy](SECURITY.md)** — Threat model, trust boundaries, sandboxing, and vulnerability reporting.
- 📜 **[Changelog](CHANGELOG.md)** — Version release history.
- 🤝 **[Contributing Guidelines](CONTRIBUTING.md)** — Development setup and PR standards.

---

## License

This project is licensed under the [MIT License](LICENSE).
