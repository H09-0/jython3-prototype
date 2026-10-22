# Jython 3 Prototype

> A modern, experimental Python 3 interpreter running on the Java Virtual Machine (Java 21+).  
> Inspired by and following the official [Jython 3 Roadmap](https://www.jython.org/jython-3-roadmap.html).

[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](LICENSE)
[![Java](https://img.shields.io/badge/Java-21%2B-blue)](https://openjdk.org/projects/jdk/21/)
[![Build](https://img.shields.io/badge/Build-Gradle%208-green)](https://gradle.org/)
[![ANTLR](https://img.shields.io/badge/Parser-ANTLR4%204.13.1-orange)](https://www.antlr.org/)
[![Tests](https://img.shields.io/badge/Tests-64%20passing-brightgreen)]()

---

## 📖 What is this?

This project is a prototype Python 3 interpreter built on the JVM. It is designed to explore and implement the concepts outlined in the [official Jython 3 roadmap](https://www.jython.org/jython-3-roadmap.html), using modern Java 21 features and a clean, extensible architecture.

| | Jython (legacy) | This Project |
|---|---|---|
| Python version | 2.7 | **3.x subset** |
| JVM target | Java 6 | **Java 21** |
| Parser | Hand-written | **ANTLR4 grammar** |
| Build system | Ant | **Gradle Kotlin DSL** |
| Bytecode | CPython `.pyc` | **Custom `CompiledCode` (`int[]` + constants pool)** |
| Standard Library | Full Python 2.7 stdlib | **Built-in `math`, `sys`, `os` via Java wrappers** |
| License | PSF 2.0 | **MIT** |

---

## ✅ Current Features (v0.7.0-alpha)

| Feature | Status |
|---------|--------|
| `print()` built-in | ✅ |
| Integer & string literals | ✅ |
| List literals (`[1, 2, 3]`) | ✅ |
| Variable assignment (`x = 10`) | ✅ |
| Arithmetic with full precedence (`+`, `-`, `*`, `/`) | ✅ |
| Parenthesized expressions (`(2 + 3) * 4`) | ✅ |
| Subscript access (`list[index]`) | ✅ |
| `if / else` control flow | ✅ |
| `while` loops | ✅ |
| `for ... in ...` loops | ✅ |
| Function definitions (`def name(a, b): body`) | ✅ |
| Function calls (standalone & in expressions) | ✅ |
| `return` values from functions | ✅ |
| Local scope isolation | ✅ |
| Nested function calls | ✅ |
| Multi-statement function bodies | ✅ |
| Module `import` (local `modules/` directory) | ✅ |
| **`import math`** – `sqrt`, `sin`, `cos`, `floor`, `ceil`, `abs`, `pow`, `pi`, `e` | ✅ **NEW** |
| **`import sys`** – `argv`, `version`, `platform`, `maxsize` | ✅ **NEW** |
| **`import os`** – `getcwd()`, `getpid()`, `sep`, `name` | ✅ **NEW** |
| **Attribute access** (`math.sqrt`, `sys.argv`, `os.sep`) | ✅ **NEW** |
| **`PyFloat`** runtime type for math results | ✅ **NEW** |
| Error traceback (NameError, ZeroDivisionError, SyntaxError, ImportError, IndexError, TypeError, AttributeError) | ✅ |
| Real bytecode layer (`CompiledCode` = `int[]` + `Object[]` constants pool) | ✅ |
| ANTLR4 grammar parser (`Jython3.g4`) | ✅ |
| **64 JUnit 5 tests** (all passing) | ✅ |

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
# Expected: BUILD SUCCESSFUL – 64 tests, 0 failures
```

---

## 🧪 Supported Python 3 Syntax (current)

```python
# Variable assignment
x = 10

# Arithmetic
print(2 + 3 * 4)       # → 14

# List literals & subscript
xs = [1, 2, 3]
print(xs[0])            # → 1

# for loop
for n in [10, 20, 30]: print(n)

# if / else
if x: print('yes') else: print('no')

# Functions
def add(a, b): return a + b
print(add(3, 4))        # → 7

# Built-in modules (NEW in v0.7.0)
import math
print(math.sqrt(16))    # → 4.0
print(math.pi)          # → 3.141592653589793

import sys
print(sys.argv)         # → []
print(sys.version)      # → 3.0.0 (Jython3-prototype)

import os
print(os.getcwd())      # → /path/to/working/directory
print(os.sep)           # → /
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
CompileVisitor → CodeBuilder
      │
      ▼
CompiledCode {
    int[]    bytecode     ← (opcode << 16) | constPoolIndex
    Object[] constPool    ← strings, ints, PyFunction objects
}
      │
      ▼
Stack Machine (run())
      │
      ▼
Output / Traceback
```

### Bytecode Encoding

Each instruction is a single **32-bit int**:

```
┌──────────────────┬──────────────────┐
│  OpCode (16 bit) │ Operand (16 bit) │
│   ordinal value  │ constPool index  │
│                  │ or raw int arg   │
└──────────────────┴──────────────────┘
```

### Runtime Object Hierarchy

```java
sealed interface PyObject
    permits PyInt, PyStr, PyNull, PyFloat,
            PyFunction, PyBuiltinFunction,
            PyList, PyIterator, PyModule {}

record PyInt(int value)                                    implements PyObject {}
record PyStr(String value)                                 implements PyObject {}
record PyNull()                                            implements PyObject {}
record PyFloat(double value)                               implements PyObject {}  // NEW
record PyFunction(String name, List<String> params,
                  CompiledCode code)                       implements PyObject {}
record PyBuiltinFunction(String name,
                  Function<PyObject[], PyObject> fn)       implements PyObject {}  // NEW
record PyList(List<PyObject> items)                        implements PyObject {}
final  class PyIterator                                    implements PyObject {}
record PyModule(String name, Map<String, PyObject> attrs)  implements PyObject {}  // NEW
```

### Standard Library Architecture

```
import math  ──►  BuiltinModules.math()  ──►  PyModule { sqrt, sin, cos, pi, e, ... }
import sys   ──►  BuiltinModules.sys()   ──►  PyModule { argv, version, platform, ... }
import os    ──►  BuiltinModules.os()    ──►  PyModule { getcwd(), sep, name, ... }
import foo   ──►  modules/foo.py         ──►  file-based fallback
```

### Instruction Set (22 opcodes)

| OpCode | Operand | Description |
|--------|---------|-------------|
| `LOAD_CONST` | constPool idx | Push a constant onto the stack |
| `LOAD_FAST` | constPool idx | Push a local/global variable value |
| `LOAD_GLOBAL` | constPool idx | Push a global variable value |
| `LOAD_ATTR` | constPool idx | Pop object, push `object.attr` ← NEW |
| `STORE_FAST` | constPool idx | Pop and store into current scope |
| `ADD / SUBTRACT / MULTIPLY / DIVIDE` | — | Binary arithmetic |
| `PRINT` | — | Pop and print top of stack |
| `JUMP_IF_FALSE` | target pc | Conditional jump |
| `JUMP` | target pc | Unconditional jump |
| `IMPORT_MODULE` | constPool idx | Load built-in or file-based module |
| `MAKE_FUNCTION` | constPool idx | Pop PyFunction, store in scope |
| `CALL_FUNCTION` | argc | Call PyFunction or PyBuiltinFunction |
| `RETURN_VALUE` | — | Return from function |
| `POP_TOP` | — | Discard top of stack |
| `BUILD_LIST` | count | Build a list from N stack items |
| `BINARY_SUBSCR` | — | Index into a list (`list[i]`) |
| `GET_ITER` | — | Convert iterable to iterator |
| `FOR_ITER` | target pc | Advance iterator or jump to exit |
| `HALT` | — | End of program |

---

## 🗺️ Roadmap

| Phase | Version | Goal | Status |
|-------|---------|------|--------|
| 1 | `v0.1.0-alpha` | Basic interpreter loop (`print`, literals) | ✅ Done |
| 2 | `v0.2.0-beta` | Variables + arithmetic (`+`, `-`, `*`, `/`) | ✅ Done |
| 3 | `v0.3.0-rc1` | Control flow (`if/else`, `while`), import, traceback, bytecode | ✅ Done |
| 4 | `v0.4.0-alpha` | ANTLR4 grammar integration, jump desync fix, 26 tests | ✅ Done |
| 5 | `v0.5.0-alpha` | Function definitions, return values, call stack, 37 tests | ✅ Done |
| 6 | `v0.6.0-alpha` | Real bytecode layer, lists, subscript, for loops, 49 tests | ✅ Done |
| 7 | `v0.7.0-alpha` | Partial standard library (`math`, `sys`, `os`), 64 tests | ✅ Done |
| 8 | `v0.8.0` | Exception handling (`try/except/finally`) | 🔜 Next |
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
├── modules/                                  # File-based importable Python modules
│   └── testmod.py
└── src/
    ├── main/
    │   ├── antlr/org/example/jython3/
    │   │   └── Jython3.g4                    # ANTLR4 grammar (23 rules)
    │   └── java/org/example/jython3/
    │       ├── Jython3Interpreter.java       # Core interpreter + bytecode VM
    │       └── BuiltinModules.java           # math / sys / os Java wrappers (NEW)
    └── test/
        └── java/org/example/jython3/
            └── Jython3InterpreterTest.java   # 64 JUnit 5 tests
```

---

## 🤝 Contributing

See [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on coding style, commit messages, and pull-request workflow.

---

## 📄 License

Released under the **MIT License** – see [`LICENSE`](LICENSE) for full text.

---

*This is a personal educational prototype inspired by the official [Jython project](https://www.jython.org/). It is **not** intended for production use.*
