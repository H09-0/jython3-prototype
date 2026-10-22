# Jython 3 Prototype (myjython3)

**What is this?**

A lightweight, experimental Python 3 interpreter that runs on the Java Virtual Machine (Java 17+).  The project follows the official Jython 3 roadmap (<https://www.jython.org/jython-3-roadmap.html>) and aims to provide a modern, open‑source alternative to the legacy Jython (Python 2.7) implementation.

---

## 📦 Features (alpha)

* Minimal interpreter loop capable of executing `print('…')` statements.
* Very small instruction set (`LOAD_CONST`, `PRINT`, `HALT`).
* JUnit‑5 test suite verifying basic functionality.
* Gradle Kotlin DSL build (Java 17 toolchain, JUnit 5).

## 🛠️ Prerequisites

| Tool | Minimum version |
|------|-----------------|
| Java | **17** (JDK) |
| Gradle | **8.0** (wrapper is included) |
| Git | any recent version |
| GitHub CLI (`gh`) | optional – used for publishing |

## 🚀 Building & Testing

```bash
# From the project root directory:
./gradlew clean build          # compiles the code
./gradlew test                # runs the JUnit test suite
```

You should see a green build with **2 passing tests**.

## ▶️ Running the Interpreter

```bash
./gradlew run --args="print('Hello from Jython3!')"
```

The command will print `Hello from Jython3!` to the console.

## 📚 Contributing

Please read the [CONTRIBUTING.md](CONTRIBUTING.md) for guidelines on how to fork, develop, and submit pull requests.

## 📄 License

This project is released under the **MIT License** – see the `LICENSE` file for the full text.

---

*This repository is a personal educational prototype and is **not** intended for production use.*
