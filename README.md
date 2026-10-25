# Jython 3 Prototype

[![Java CI](https://github.com/H09-0/jython3-prototype/actions/workflows/test.yml/badge.svg)](https://github.com/H09-0/jython3-prototype/actions)
[![License: MIT](https://img.shields.io/badge/License-MIT-blue.svg)](LICENSE)
[![Java 21+](https://img.shields.io/badge/Java-21%2B-orange.svg)](https://openjdk.org/projects/jdk/21/)
[![Status: Pre-Production](https://img.shields.io/badge/Status-Pre--Production%20Alpha-yellow.svg)](docs/ROADMAP.md)

An exploratory, high-performance **Python 3 execution engine running directly on the Java Virtual Machine (JVM)**.

Inspired by the design and vision of the original [Jython Project](https://www.jython.org/), this prototype provides a modern JVM-native implementation designed to bring clean Python 3.14 syntax, object models, and bidirectional Java interop to JVM applications and automation frameworks.

---

## Key Highlights

- **Python 3 Compatibility**: Conforms to Python 3.14 syntax and semantics, including indentation blocks, OOP, exception models, and standard built-ins.
- **Direct JVM Execution**: Pure Java 21+ stack-based bytecode virtual machine with zero C-dependencies.
- **Fast Java Bridge**: Blazing-fast bidirectional reflection bridge allowing scripts to directly instantiate and call JVM classes.
- **Defense-in-Depth Security**: Configurable access policies (`AllowlistPolicy`, `SandboxedPolicy`, `DenyAllPolicy`) with recursive inner-class containment and circular reference safety.
- **Tested & Verified**: Comprehensive test suite with **246 automated tests** passing green across all subsystems.

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

# Run all 246 unit tests
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
