# Jython 3 Prototype — Field Test Report

**Version:** v0.10.2-alpha  
**Date:** 2026-10-31  
**Reference Implementation:** CPython 3.12.3  
**Methodology:** Identical Python scripts executed via the `jython3` CLI and
CPython, outputs compared with `diff`.

> This report covers genuine implementation gaps and correctness bugs in
> the Jython 3 interpreter.  Test-harness or operator-error issues are
> excluded.

---

## Executive Summary

| Metric | Count |
|:-------|------:|
| Features tested | 48 |
| Fully CPython-identical (PASS) | 33 |
| Partial support (PARTIAL) | 4 |
| Correctness bugs (FAIL) | 4 |
| Not yet implemented (MISSING) | 7 |

---

## 1. What Works — CPython-Identical Output

All of the following produced **byte-for-byte identical** output when run
against CPython 3.12.3:

| Area | Verified Capabilities |
|:-----|:----------------------|
| Integer arithmetic | `+`, `-`, `*`, `/`, `//`, `%`, `**` — all sign combinations, overflow to BigInt |
| BigInt arithmetic | Arbitrary-precision `+`, `-`, `*`, `//`, `%`, `**`, unary `-` |
| Float arithmetic | true division, floor division, modulo, power, `math.sqrt` |
| String operations | concatenation, repetition, indexing, slicing (step, reverse), `upper`, `lower`, `split`, `strip`, `startswith`, `endswith`, `replace`, `find`, `join`, `count`, `in` |
| String escapes | `\n`, `\t`, `\"`, `\\` |
| List operations | creation, indexing, negative indexing, slicing (step, reverse), `append`, `sort`, `reverse`, `pop`, `insert`, `remove`, `index`, `+`, `*`, `in`, iteration |
| Dict operations | creation, indexing, assignment, `del`, `in`, `.keys()`, `.values()`, `.get()`, `len`, `for k in d` |
| Control flow | `if`/`elif`/`else`, `for` loop, `while` loop, `break` (single-level), `continue`, `range(start, stop, step)` including negative step |
| Functions | `def`, `return`, recursion (tested `fib(20)` and `factorial(10)`), closures, nested functions, higher-order functions (pass function as argument) |
| Exception handling | `try`/`except`/`finally`, `raise`, nested `try`, `except (A, B)` tuple matching, `as e` binding, re-raise from handler |
| Classes & OOP | `class`, `__init__`, inheritance, `Animal.__init__(self, ...)` super-call, class variables, `isinstance` with user-defined classes, method-calls-method |
| Built-in functions | `print`, `len`, `range`, `type`, `int()`, `str()`, `float()`, `abs`, `min`, `max`, `sum`, `round`, `sorted(list)`, `map`, `filter`, `hasattr`, `getattr` |
| Standard library | `math` (sqrt, floor, ceil, pi, pow, log, abs), `time` (time, time\_ns), `json` (dumps, loads), `random` (seed, randint, choice), `os` (getcwd, listdir), `io` (open, read, write, close) |
| Edge cases | `None` handling, falsy values (`0`, `""`, `[]`, `{}`, `None`, `False`), chained comparisons (`1 < 2 < 3`), `and`/`or`/`not`, augmented assignment (`+=`, `-=`, `*=`), string iteration (`for ch in "abc"`), reference sharing, deep recursion, `list(range(1000))`, `sum(range(101))`, `dict.get()` with default |

---

## 2. Bugs — Incorrect Behavior

### 🔴 C1 — `break` in Nested `for` Loops Causes Infinite Loop

**Severity:** Critical  
**Symptom:** When `break` fires inside an inner `for` loop, the outer loop
becomes infinite — the outer loop variable freezes and the inner loop
restarts endlessly.

```python
for i in range(3):
    for j in range(3):
        if j == 1:
            break         # ← triggers infinite outer loop
        print(i, j)
```

**Root Cause (CompileVisitor.java `visitBreak_stmt`):**
When compiling `break`, the compiler emits `JUMP afterLoop` but does **not**
emit a `POP` instruction to remove the inner loop's `PyIterator` from the
VM stack. After the `break` jump, the stale inner iterator remains on the
stack. When the outer loop's `FOR_ITER` executes, it finds this leftover
iterator instead of its own, corrupting the outer loop's iteration state.

**Fix Needed:** Emit a stack `POP` before the `JUMP` in `visitBreak_stmt`
for `for`-loops so the inner iterator is cleaned up.

---

### 🟠 H1 — `isinstance()` Returns `False` for All Built-in Types

**Severity:** High  
**Symptom:** `isinstance(42, int)` → `False`. Same for `str`, `float`,
`bool`, `list`, `dict`.

```python
isinstance(42, int)       # → False  (should be True)
isinstance("hi", str)     # → False
isinstance(3.14, float)   # → False
```

**Root Cause (BuiltinFunctions.java, line 292–309):**
Built-in type names like `int`, `str`, `float` are registered as
`PyBuiltinFunction` objects (callable constructors), not as `PyClass`
instances. The `isinstance` implementation only checks `PyClass` and
`PyInstance`; when `typeArg` is a `PyBuiltinFunction` it falls through to a
string comparison where `typeName(42)` = `"int"` is compared against
`typeArg.toString()` = `"<built-in function int>"` — always a mismatch.

**Fix Needed:** Add a lookup table mapping built-in type function names
(`"int"`, `"str"`, `"float"`, `"bool"`, `"list"`, `"dict"`, `"tuple"`) to
their corresponding `typeName` strings so `isinstance` can resolve them.

---

### 🟠 H2 — Bool Arithmetic Returns `float` Instead of `int`

**Severity:** High  
**Symptom:** `True + True` → `2.0` instead of `2`.

```python
True + True    # → 2.0  (should be 2)
True * 5       # → 5.0  (should be 5)
False + 1      # → 1.0  (should be 1)
```

**Root Cause (Jython3Interpreter.java `binaryAdd`):**
`PyBool` is a separate record type and is not a subtype of `PyInt`. The
`binaryAdd` method checks `PyInt` first, which does not match `PyBool`,
then falls through to `isNumeric()` → `toDouble()`, producing a `PyFloat`
result. In CPython, `bool` is a subclass of `int`, so `True + True`
yields `int(2)`.

**Fix Needed:** In all arithmetic dispatchers (`binaryAdd`,
`binarySubtract`, `binaryMultiply`, etc.), unwrap `PyBool` to `PyInt`
(`True` → `PyInt(1)`, `False` → `PyInt(0)`) before dispatching.

---

### 🟠 H3 — `%` String Formatting Ignores Tuple Arguments Beyond the First

**Severity:** High  
**Symptom:** `"x=%d y=%s" % (10, "hi")` → `"x=10 y=10"`.

```python
"x=%d y=%s" % (10, "hi")  # → "x=10 y=10"  (should be "x=10 y=hi")
```

**Root Cause (Jython3Interpreter.java `pyStringFormat`):**
The `%` operator dispatches to `pyStringFormat` which receives the
right-hand operand. When a tuple is passed, the formatter uses only the
first element for every placeholder instead of iterating through the tuple
elements sequentially.

**Fix Needed:** When the right operand is a `PyList` (used for tuples
internally), iterate through its elements to fill successive `%d`, `%s`,
`%f` placeholders.

---

## 3. Implementation Gaps — Not Yet Supported

These features are part of the Python 3 specification but not yet
implemented in the Jython 3 grammar or runtime. They all produce
`SyntaxError`.

| ID | Feature | Example | Notes |
|:--:|:--------|:--------|:------|
| M1 | Default arguments | `def f(x, y=2)` | Grammar does not support `=` in parameter list |
| M2 | Tuple unpacking | `x, y = (1, 2)` | Grammar does not support comma-separated assignment targets |
| M3 | Lambda expressions | `lambda x: x * 2` | `lambda` keyword not in grammar |
| M4 | List comprehensions | `[x*x for x in range(5)]` | `for` not recognized inside `[...]` expression |
| M5 | F-strings | `f"hello {name}"` | `f"..."` prefix not in lexer |
| M6 | `global` / `nonlocal` | `global x` | Keywords not in grammar |
| M7 | Chained assignment | `a = b = c = 10` | Grammar supports only single `=` target |

---

## 4. Partial Support — Works with Limitations

| ID | Feature | What works | Limitation | Root Cause |
|:--:|:--------|:-----------|:-----------|:-----------|
| P1 | Unary `+` | Unary `-` works | `+x` → SyntaxError | Grammar has `SUB expr` alternative but no `ADD expr` |
| P2 | Tuple representation | Indexing, `len`, slicing | `print((1,2))` → `[1, 2]` | Tuples stored as `PyList` internally with no distinct `repr()` |
| P3 | `re` module | `re.search(pattern, text)` returns matched text | Returns `str` not `MatchObject`; `re.match`, `re.split`, `re.findall`, `re.sub` missing | Simplified wrapper returns raw string |
| P4 | Iterable protocol | Lists, dicts (`for k in d`), ranges iterable | `sorted(dict)`, `sorted("abc")`, `list("abc")` fail | `sorted()` and `list()` only accept `PyList` argument, not arbitrary iterables |

---

## 5. Recommended Fix Priority

| Priority | ID | Issue | Rationale |
|:--------:|:--:|:------|:----------|
| 1 | C1 | Nested loop `break` infinite loop | Blocks any real-world program with nested loops |
| 2 | H1 | `isinstance()` with built-in types | Fundamental type-checking broken |
| 3 | H2 | Bool arithmetic returns float | Violates Python numeric tower |
| 4 | M1 | Default arguments | Extremely common in Python code |
| 5 | M2 | Tuple unpacking | Required by `for i, v in enumerate(...)`, multiple return values |
| 6 | H3 | `%` formatting with multiple args | Common string formatting pattern |
| 7 | M3 | Lambda | Common in functional patterns |
| 8 | M4 | List comprehensions | Idiomatic Python |
| 9 | P3 | `re` module completeness | Missing `match`, `split`, `findall`, `sub` |
| 10 | M5–M7 | F-strings, `global`, chained assignment, unary `+` | Less frequently blocking |
