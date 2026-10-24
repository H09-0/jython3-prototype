# Architecture

Python source is compiled to a stack-machine bytecode image and executed by
`Jython3Interpreter`.

```
source
  → Jython3Lexer
  → PythonIndentStream          parse/
  → Jython3Parser
  → CompileVisitor             compile/
  → CompiledCode
  → Jython3Interpreter.run()
```

Each instruction is one `int`: the high 16 bits are `OpCode.code()`, the low 16
bits are a constant-pool index or a raw operand. Opcode numbers are explicit.
They are not `Enum.ordinal()`, so inserting a new operation does not renumber
the ones that already exist.

## Packages

| Package | Role |
|---|---|
| `org.example.jython3` | Virtual machine, import, `exec`, and `eval` |
| `org.example.jython3.runtime` | `PyObject` and the Python and Java value types |
| `org.example.jython3.compile` | `OpCode`, `CodeBuilder`, `CompileVisitor` |
| `org.example.jython3.bridge` | Reflection, overload selection, allow-list |
| `org.example.jython3.stdlib` | Built-in functions and `math`, `sys`, `os`, `io`, `json`, `re` |
| `org.example.jython3.parse` | Indentation token stream |
| `org.example.jython3.cli` | REPL and script runner |

`PyObject` is a sealed interface in the unnamed module, so every permitted
type stays in `runtime`. The Java wrappers (`PyJavaClass`, `PyJavaObject`,
`PyJavaMethod`, `PyJavaPackage`) live there and call `JavaBridge` for
resolution. `PyCaller` is the only link from a Python instance back to the
running interpreter, used when `__str__` or `__repr__` runs.

`exec` prints a traceback and swallows `InterpreterException`. `eval` runs the
same pipeline and lets that exception propagate. `SystemExit` is rethrown by both.

## Control flow, exceptions, and generators

- `break` and `continue` are compiled as `JUMP` instructions. `CompileVisitor`
  keeps a loop-context stack so the jump targets are patched when the loop ends.
- A `try` statement installs one handler. The handler chain evaluates
  `COMPARE_EXC_TYPE` for each clause; if nothing matches, the interpreter runs
  the `finally` body and executes `RERAISE`.
- Generator functions are detected by scanning their bytecode for `YIELD_VALUE`.
  Calling one returns a `PyGenerator`. On first iteration the interpreter runs
  the body with a yield collector, so all values are produced eagerly.

## Entry points

| Gradle task | Class |
|---|---|
| `run` | `org.example.jython3.cli.Repl` |
| `repl` | `org.example.jython3.cli.Repl` |
| `runScript` | `org.example.jython3.cli.RunScript` |
