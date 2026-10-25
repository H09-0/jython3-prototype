# Project Structure

This document provides a comprehensive map of the `jython3-prototype` repository layout, explaining the responsibilities of each package and directory.

```
jython3-prototype/
├── build.gradle.kts                # Gradle build script (Java 21 toolchain, ANTLR4 plugin, JUnit 5)
├── gradlew, gradlew.bat            # Gradle wrapper scripts
├── settings.gradle.kts             # Project root settings
├── LICENSE                         # MIT License
├── README.md                       # Project entry point & quick start guide
├── SECURITY.md                     # Security policies, threat model, trust boundaries
├── CHANGELOG.md                    # Release and development history across versions
├── JAVA_BRIDGE.md                  # Comprehensive Java Bridge interop guide
├── CONTRIBUTING.md                 # Contribution guidelines
├── demo.py                         # General language feature demo script
├── demo_java_bridge.py             # Java Bridge interop demo script
├── docs/                           # Focused technical documentation
│   ├── ARCHITECTURE.md             # Execution engine, bytecode specification, VM design
│   ├── COMPATIBILITY.md            # Tier-by-tier CPython 3.14 compatibility matrix
│   ├── ROADMAP.md                  # Development phases & milestones to v1.0.0
│   ├── STDLIB.md                   # Built-in standard library modules reference
│   └── PROJECT_STRUCTURE.md        # This layout guide
├── modules/                        # File-based external Python modules path
└── src/
    ├── main/
    │   ├── antlr/org/example/jython3/
    │   │   └── Jython3.g4          # ANTLR4 grammar for Python 3 syntax (expressions, statements, OOP)
    │   └── java/org/example/jython3/
    │       ├── Jython3Interpreter.java # Main interpreter entry point, cache manager & stack VM loop
    │       ├── bridge/             # Bidirectional Java <-> Python interoperability
    │       │   └── JavaBridge.java # Reflection dispatcher, argument converter, overload resolver
    │       ├── cli/                # Command-line interfaces
    │       │   ├── Repl.java       # Interactive Python REPL with block & delimiter awareness
    │       │   └── RunScript.java  # CLI launcher executing script files or inline code (-c)
    │       ├── compile/            # AST compilation into stack bytecode
    │       │   ├── CodeBuilder.java    # Bytecode instruction emitter and branch patcher
    │       │   ├── CompiledCode.java   # Immutable CompiledCode record (int[] bytecode, Object[] constPool)
    │       │   ├── CompileVisitor.java # ANTLR Visitor generating 32-bit encoded instructions
    │       │   ├── OpCode.java         # 58 stable bytecode operations
    │       │   └── StringEscapes.java  # Python string escape sequence unescaper
    │       ├── parse/              # Lexical stream preprocessors
    │       │   └── PythonIndentStream.java # Converts indentation/dedentation into synthetic INDENT/DEDENT tokens
    │       ├── runtime/            # Python object model and execution frame
    │       │   ├── BigInt.java         # Arbitrary-precision integer wrapping BigInteger
    │       │   ├── ExceptHandler.java  # Active exception handler entry on exceptStack
    │       │   ├── ExceptionTypeRegistry.java # Standard Python exception type hierarchy
    │       │   ├── Frame.java          # Call stack frame record (code, returnPc, locals, stack)
    │       │   ├── InterpreterException.java # Java exception carrying runtime Python errors
    │       │   ├── PyBool.java         # Python boolean literal (True, False)
    │       │   ├── PyBoundMethod.java   # Bound instance method wrapper
    │       │   ├── PyBuiltinFunction.java # Built-in native function wrapper
    │       │   ├── PyCaller.java       # Callable dispatch abstraction
    │       │   ├── PyClass.java        # Python class definition (methods, bases)
    │       │   ├── PyCompiledCodeWrapper.java # Wrapper for nested code blocks
    │       │   ├── PyDict.java         # Python dictionary backed by LinkedHashMap
    │       │   ├── PyErrors.java       # Centralized Python error factory and exception matcher
    │       │   ├── PyException.java    # Concrete instantiated Python exception object
    │       │   ├── PyFloat.java        # Python floating point wrapper (double)
    │       │   ├── PyFunction.java     # User-defined Python function (bytecode, params, closure)
    │       │   ├── PyGenerator.java    # Generator object produced by functions containing yield
    │       │   ├── PyInstance.java     # Python class instance storing dynamic fields
    │       │   ├── PyInt.java          # 32-bit signed integer
    │       │   ├── PyIterator.java     # Sequential iterator for lists, strings, and iterables
    │       │   ├── PyJavaClass.java    # Python reflection wrapper for a java.lang.Class
    │       │   ├── PyJavaMethod.java   # Overload-resolving wrapper for Java methods
    │       │   ├── PyJavaObject.java   # Python wrapper for an arbitrary Java instance
    │       │   ├── PyJavaPackage.java  # Dynamic Java package hierarchy navigator
    │       │   ├── PyList.java         # Python list backed by ArrayList
    │       │   ├── PyModule.java       # Python module holding exported attributes
    │       │   ├── PyNull.java         # Python None singleton
    │       │   ├── PyObject.java       # Sealed base interface for all Python runtime types
    │       │   ├── PyOps.java          # Mathematical, boolean, comparison & equality operations
    │       │   ├── PyStr.java          # Python UTF-8 string wrapper
    │       │   ├── PySuper.java        # super() proxy for inherited method dispatch
    │       │   └── SystemExit.java     # Control exception raised by sys.exit()
    │       ├── security/           # Java Bridge security policies and sandboxing
    │       │   ├── AllowlistPolicy.java # Default trusted policy (safe standard libraries, blocks unsafe internals)
    │       │   ├── DenyAllPolicy.java   # Complete denial of all Java access
    │       │   ├── JavaAccessPolicy.java # Interface defining access control hooks
    │       │   ├── JavaDescriptors.java # JVM bytecode descriptor generator
    │       │   └── SandboxedPolicy.java # Strict policy for untrusted code (denies IO, net, UI, threads)
    │       └── stdlib/             # Built-in Python standard library implementations
    │           ├── BuiltinFunctions.java # Global built-in functions (len, range, type, isinstance, etc.)
    │           └── BuiltinModules.java   # Java-backed modules (math, sys, os, io, json, re, time, random)
    └── test/
        └── java/org/example/jython3/
            ├── ErrorFactoryTest.java           # Tests error construction and exception matching
            ├── ExceptionTypeRegistryTest.java  # Tests standard exception class hierarchies
            ├── JavaAccessPolicyTest.java       # Tests policy boundaries, privilege isolation, and sandboxing
            └── Jython3InterpreterTest.java     # 207 end-to-end integration tests for language features
```

---

## Where to Add New Code

1. **New Python Syntax**:
   - Add lexer/parser rules in `src/main/antlr/org/example/jython3/Jython3.g4`.
   - Update `CompileVisitor.java` to emit corresponding opcodes.

2. **New Bytecode Instructions**:
   - Register a stable enum code in `OpCode.java`.
   - Implement execution logic in `Jython3Interpreter.java` (`run()` switch).

3. **New Python Built-in Types**:
   - Implement `PyObject` in `src/main/java/org/example/jython3/runtime/`.
   - Add permitted type in `PyObject.java` sealed hierarchy.

4. **New Standard Library Modules**:
   - Add module factory in `BuiltinModules.java`.
   - Register module resolution in `Jython3Interpreter.java` (`resolveBuiltinModule()`).
   - Document supported functions in `docs/STDLIB.md`.

5. **Security Rules**:
   - Add forbidden classes, prefixes, or members in `AllowlistPolicy.java` and `SandboxedPolicy.java`.
   - Add regression tests in `JavaAccessPolicyTest.java`.
