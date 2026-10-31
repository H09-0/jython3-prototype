# Jython 3 Prototype — Field Test Report

**Version:** v0.10.2-alpha (commit `fdc0f7d`)
**Date:** 2026-10-31
**Methodology:** Each feature tested via CLI (`jython3`) with identical scripts
run against CPython 3.12.3. Results compared via `diff`.

---

## Summary

| Category | Status | Verdict |
|:---------|:------:|:--------|
| Core Arithmetic (int, float, BigInt) | ✅ PASS | Identical to CPython |
| Bool Arithmetic | ⚠️ PARTIAL | `True + True` returns `2.0` not `2` |
| Unary Operators | ⚠️ PARTIAL | `-x` works; `+x` missing from grammar |
| String Operations | ✅ PASS | concat, repeat, index, slice, methods, `in` all work |
| String Escapes | ⚠️ PARTIAL | `\n`, `\t`, `\"` work; `\uXXXX` NOT interpreted |
| String `%` Formatting | ⚠️ PARTIAL | Single arg works; tuple `%(x, y)` uses only first arg |
| List Operations | ✅ PASS | CRUD, slice, concat, repeat, `in`, methods, iteration |
| Dict Operations | ✅ PASS | CRUD, `del`, `in`, `.keys()`, `.values()`, `.get()`, iteration |
| Tuple | ⚠️ PARTIAL | Indexing/len work; `repr()` prints `[]` not `()` |
| Tuple Unpacking | ❌ MISSING | `x, y = (1, 2)` and `for a, b in ...` not supported |
| Control Flow | ✅ PASS | if/elif/else, for, while, break, continue all work (single-level) |
| Nested Loop + Break | 🔴 CRITICAL | `break` in inner loop causes infinite loop in outer loop |
| Functions | ✅ PASS | def, return, recursion, closures, nested, higher-order |
| Default Arguments | ❌ MISSING | `def f(x, y=2)` causes SyntaxError |
| Implicit Tuple Return | ❌ MISSING | `return a, b` causes SyntaxError |
| Exception Handling | ✅ PASS | try/except/finally, raise, nested, tuple-except, `as e` |
| Classes & OOP | ✅ PASS | class, `__init__`, inheritance, class vars, method chaining |
| `isinstance()` (user classes) | ✅ PASS | Works correctly with user-defined classes |
| `isinstance()` (builtin types) | ❌ FAIL | `isinstance(42, int)` returns `False` |
| `type()` | ✅ PASS | Returns correct `<class 'X'>` string |
| `type().__name__` | ❌ MISSING | `type()` returns str, not a type object |
| Builtins | ✅ PASS | print, len, range, int, str, float, abs, min, max, sum, round, sorted(list), map, filter, hasattr, getattr |
| `sorted(string)` | ❌ FAIL | String not iterable for `sorted()` |
| `sorted(dict)` | ❌ FAIL | Dict not iterable for `sorted()` (use `.keys()`) |
| `list(string)` | ❌ FAIL | String not iterable for `list()` |
| Import System | ✅ PASS | `import module` works for all stdlib modules |
| `math` Module | ✅ PASS | sqrt, floor, ceil, pi, pow, log, abs |
| `time` Module | ✅ PASS | time, time_ns |
| `json` Module | ✅ PASS | dumps, loads |
| `random` Module | ✅ PASS | seed, randint, choice |
| `re` Module | ⚠️ PARTIAL | `search` returns str not MatchObject; `match`/`split`/`findall`/`sub` missing |
| `os` Module | ✅ PASS | getcwd, listdir |
| `io` Module | ✅ PASS | open, read, write, close |
| None Handling | ✅ PASS | `is None`, falsy check, printing |
| Boolean Logic | ✅ PASS | and, or, not |
| Chained Comparisons | ✅ PASS | `1 < 2 < 3` works |
| Augmented Assignment | ✅ PASS | `+=`, `-=`, `*=` |
| String Iteration | ✅ PASS | `for ch in "abc"` works |
| Reference Sharing | ✅ PASS | `b = a; a.append(4); print(b)` correct |
| Deep Recursion | ✅ PASS | `fib(20)` runs correctly |
| Large Collections | ✅ PASS | `list(range(1000))`, `sum(range(101))` |
| `dict.get()` | ✅ PASS | With and without default |
| Lambda | ❌ MISSING | `lambda x: x` causes SyntaxError |
| List Comprehension | ❌ MISSING | `[x for x in ...]` causes SyntaxError |
| F-strings | ❌ MISSING | `f"..."` causes SyntaxError |
| `global` Keyword | ❌ MISSING | `global x` causes SyntaxError |
| Chained Assignment | ❌ MISSING | `a = b = c = 10` causes SyntaxError |

---

## Findings by Severity

### 🔴 CRITICAL (must fix before next release)

| # | Bug | Impact |
|:-:|:----|:-------|
| C1 | **`break` in nested `for` loops causes infinite loop** | Inner `break` corrupts outer loop iterator — outer loop variable freezes and inner loop restarts endlessly. Hits execution limits but generates massive output. |

### 🟠 HIGH (correctness bugs)

| # | Bug | Impact |
|:-:|:----|:-------|
| H1 | `isinstance(42, int)` returns `False` | All builtin type checks broken: `int`, `str`, `float`, `bool`, `list`, `dict` |
| H2 | Bool arithmetic returns float (`True + True` → `2.0`) | Should return `int` per CPython semantics |
| H3 | `sorted(dict)` / `sorted(string)` fails | Should iterate keys / characters |
| H4 | `re.search()` returns raw string, not MatchObject | No `.group()`, `.start()`, `.end()` |
| H5 | `%` string formatting ignores tuple args beyond first | `"x=%d y=%s" % (10, "hi")` → `"x=10 y=10"` |

### 🟡 MEDIUM (missing language features)

| # | Feature | Notes |
|:-:|:--------|:------|
| M1 | Default arguments (`def f(x, y=2)`) | SyntaxError |
| M2 | Tuple unpacking (`x, y = (1, 2)`) | SyntaxError |
| M3 | Implicit tuple return (`return a, b`) | SyntaxError |
| M4 | Lambda expressions (`lambda x: x * 2`) | SyntaxError |
| M5 | List comprehensions (`[x for x in ...]`) | SyntaxError |
| M6 | F-strings (`f"hello {name}"`) | SyntaxError |
| M7 | `global` keyword | SyntaxError |
| M8 | Chained assignment (`a = b = c = 10`) | SyntaxError |
| M9 | Unary `+` operator | SyntaxError |

### 🟢 LOW (cosmetic / edge-case)

| # | Issue | Notes |
|:-:|:------|:------|
| L1 | Tuple `repr()` prints `[1, 2]` not `(1, 2)` | Cosmetic but confusing |
| L2 | `\uXXXX` escape not interpreted in strings | Unicode escape sequences stored literally |
| L3 | `type()` returns string, not type object | `type(x).__name__` fails |
| L4 | `re.match`, `re.split`, `re.findall`, `re.sub` missing | Only `re.search` exists |
| L5 | `list("abc")` fails — string not iterable for `list()` | |

---

## What Works Perfectly (CPython-identical output)

1. **Integer arithmetic** — all operators, all sign combos, BigInt
2. **Float arithmetic** — true division, floor division, modulo, power
3. **String operations** — concat, repeat, index, slice, methods (upper/lower/split/strip/startswith/endswith/replace/find/join/count), `in`
4. **List operations** — all CRUD, slicing (including step/reverse), concat, repeat, `in`, sort/reverse/pop/insert/remove/index
5. **Dict operations** — CRUD, `del`, `in`, keys/values, iteration, `.get()`
6. **Control flow** — if/elif/else, for, while, break (single level), continue, range with step/negative
7. **Functions** — def, return, recursion, closures, nested functions, higher-order
8. **Exception handling** — try/except/finally, raise, nested try, tuple except, `as e`
9. **Classes** — class, `__init__`, inheritance, class variables, isinstance (user classes), method calling method
10. **Builtins** — print, len, range, type, int, str, float, abs, min, max, sum, round, sorted(list), map, filter, hasattr, getattr, isinstance (user classes)
11. **Edge cases** — None handling, falsy values, chained comparisons, boolean logic, string escapes (\n \t \"), augmented assignment, string iteration, reference sharing, deep recursion, large collections

---

## Recommended Fix Priority

1. **C1** — Nested loop break (blocks any real-world Python program with nested loops)
2. **H1** — `isinstance()` with builtin types (fundamental type-checking)
3. **H2** — Bool arithmetic returning float
4. **M1** — Default arguments (very common in Python)
5. **M2/M3** — Tuple unpacking and return (very common)
6. **H5** — `%` formatting with multiple args
7. **M4** — Lambda (common in functional patterns)
8. **M5** — List comprehensions (idiomatic Python)
9. **H3/H4** — sorted/re improvements
10. **M6–M9** — f-strings, global, chained assignment, unary +
