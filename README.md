# Jython 3 Prototype

> A modern, experimental Python 3 interpreter running on the Java Virtual Machine (Java 21+).  
> Inspired by and following the official [Jython 3 Roadmap](https://www.jython.org/jython-3-roadmap.html).

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-blue)](https://openjdk.org/projects/jdk/21/)
[![Build](https://img.shields.io/badge/Build-Gradle%208-green)](https://gradle.org/)
[![ANTLR](https://img.shields.io/badge/Parser-ANTLR4%204.13.1-orange)](https://www.antlr.org/)

---

## 📖 What is this?

This project is a prototype Python 3 interpreter built on the JVM. It is designed to explore and implement the concepts outlined in the [official Jython 3 roadmap](https://www.jython.org/jython-3-roadmap.html), using modern Java 21 features and a clean, extensible architecture.

| | Jython (legacy) | This Project |
|---|---|---|
| Python version | 2.7 | **3.x subset** |
| JVM target | Java 6 | **Java 21** |
| Parser | Hand-written | **ANTLR4 grammar** |
| Build system | Ant | **Gradle Kotlin DSL** |
| License | PSF 2.0 | **MIT** |

---

## ✅ Current Features (v0.4.0-alpha)

| Feature | Status |
|---------|--------|
| `print()` built-in | ✅ |
| Integer & string literals | ✅ |
| Variable assignment (`x = 10`) | ✅ |
| Arithmetic with full precedence (`+`, `-`, `*`, `/`) | ✅ |
| Parenthesized expressions (`(2 + 3) * 4`) | ✅ |
| `if / else` control flow | ✅ |
| `while` loops | ✅ |
| Module `import` (local `modules/` directory) | ✅ |
| Error traceback (NameError, ZeroDivisionError, SyntaxError, ImportError) | ✅ |
| Bytecode generation (`int[]` representation) | ✅ |
| ANTLR4 grammar parser (`Jython3.g4`) | ✅ |
| **26 JUnit 5 tests** (all passing) | ✅ |

---

## 🛠️ Prerequisites

| Tool | Version |
|------|---------|
| Java JDK | **21+** |
| Gradle Wrapper | **included** (no install needed) |
| Git | any recent |
| GitHub CLI (`gh`) | optional – for publishing |

---

## 🚀 Build & Run

```bash
# Clone the repository
git clone https://github.com/H09-0/jython3-prototype.git
cd jython3-prototype

# Build the project (compiles + generates ANTLR parser + runs tests)
./gradlew clean build

# Run the test suite
./gradlew test
# Expected: BUILD SUCCESSFUL – 26 tests, 0 failures
```

---

## 🧪 Supported Python 3 Syntax (current)

```python
# Variable assignment
x = 10
y = 42

# Arithmetic with operator precedence
print(2 + 3 * 4)       # → 14
print((2 + 3) * 4)     # → 20
print(x + y)           # → 52

# if / else
if x: print('truthy') else: print('falsy')

# while loop
while 0: print('never runs')

# Module import (from modules/ directory)
import mymodule

# String printing
print('Hello, World!')
print("Jython3 is here")
```

---

## 🏗️ Architecture

```
Source code (str)
      │
      ▼
ANTLR4 Lexer + Parser (Jython3.g4)
      │
      ▼
Parse Tree
      │
      ▼
CompileVisitor → OpCode[] + instrArgs[]
      │
      ▼
Stack Machine (run())
      │
      ▼
Output / Traceback
```

### Runtime Object Hierarchy

```java
sealed interface PyObject permits PyInt, PyStr, PyNull {}
record PyInt(int value)    implements PyObject {}
record PyStr(String value) implements PyObject {}
record PyNull()            implements PyObject {}
```

### Instruction Set

| OpCode | Description |
|--------|-------------|
| `LOAD_CONST` | Push a constant (int or string) onto the stack |
| `LOAD_FAST` | Push a variable value from locals |
| `STORE_FAST` | Pop and store into locals |
| `ADD / SUBTRACT / MULTIPLY / DIVIDE` | Binary arithmetic |
| `PRINT` | Pop and print top of stack |
| `JUMP_IF_FALSE` | Conditional jump (for `if/while`) |
| `JUMP` | Unconditional jump |
| `IMPORT_MODULE` | Load and exec a `.py` module file |
| `HALT` | End of program |

---

## 🗺️ Roadmap

| Phase | Version | Goal | Status |
|-------|---------|------|--------|
| 1 | `v0.1.0-alpha` | Basic interpreter loop (`print`, literals) | ✅ Done |
| 2 | `v0.2.0-beta` | Variables + arithmetic (`+`, `-`, `*`, `/`) | ✅ Done |
| 3 | `v0.3.0-rc1` | Control flow (`if/else`, `while`), import, traceback, bytecode | ✅ Done |
| 4 | `v0.4.0-alpha` | ANTLR4 grammar integration, jump desync fix, 26 tests | ✅ Done |
| 5 | `v0.5.0` | Function definitions (`def`), return values, call stack | 🔜 Next |
| 6 | `v0.6.0` | Real bytecode layer (`CALL`, `RETURN`, `LOAD_GLOBAL`) | 🔜 Planned |
| 7 | `v0.7.0` | Partial standard library (`math`, `sys`, `os`) | 🔜 Planned |
| 8 | `v0.8.0` | Exception handling (`try/except/finally`) | 🔜 Planned |
| 9 | `v1.0.0` | Stable release, Maven Central packaging, full docs | 🎯 Goal |

---

## 📁 Project Structure

```
jython3-prototype/
├── build.gradle.kts                          # Gradle build (Java 21, ANTLR4, JUnit 5)
├── settings.gradle.kts
├── LICENSE
├── README.md
├── CONTRIBUTING.md
├── modules/                                  # Importable Python modules
│   └── testmod.py
└── src/
    ├── main/
    │   ├── antlr/org/example/jython3/
    │   │   └── Jython3.g4                    # ANTLR4 grammar definition
    │   └── java/org/example/jython3/
    │       └── Jython3Interpreter.java       # Core interpreter
    └── test/
        └── java/org/example/jython3/
            └── Jython3InterpreterTest.java   # 26 JUnit 5 tests
```

---

## 🤝 Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on coding style, commit messages, and pull-request workflow.

---

## 📄 License

Released under the **MIT License** – see [`LICENSE`](LICENSE) for full text.

---

*This is a personal educational prototype inspired by the official [Jython project](https://www.jython.org/). It is **not** intended for production use.*
