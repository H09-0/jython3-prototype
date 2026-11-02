# Jython3 — Project Direction & Technical Identity

> **Core Principle:**  
> *CPython defines the language. The JVM provides the execution platform.  
> Jython3 must not redefine Python to make implementation easier.*

---

## 1. Technical Identity

| Attribute | Specification |
|:----------|:--------------|
| **Project Name** | Jython3 |
| **Primary Objective** | Execute Python 3 programs with behavior matching the corresponding CPython version, using the JVM as the underlying runtime |
| **Implementation Language** | Java (modern Java 21+) |
| **Language Specification** | Python 3 |
| **Compatibility Reference** | CPython 3.14.x |
| **Execution Platform** | Java Virtual Machine (JVM) |
| **Primary Distribution** | Standalone interpreter CLI (`jython3`) and embeddable Java library |
| **Secondary Capability** | Controlled Java interoperability |

Jython3 is an **independent clean-room implementation** inspired by the
historical goals of the Jython project. It is not an official release of
the upstream Jython project.

---

## 2. The Five Guiding Principles

### Principle 1 — Semantic compatibility over implementation convenience
If CPython returns an integer, Jython3 must not return a float simply because
Java's numeric conversion made that easier. If CPython raises `TypeError`,
Jython3 must not silently accept the operation. If CPython considers tuples
immutable, Jython3 must have actual immutable tuples.

Python semantics are the specification we implement, not suggestions we
approximate.

### Principle 2 — Python's object model must remain Python's object model
Java types must not leak into Python semantics.

| Python Concept | Required Jython3 Behavior |
|:---------------|:--------------------------|
| `int` | Python integer semantics (arbitrary-precision) |
| `bool` | Subclass of `int` (`True + True == 2`, `isinstance(True, int) is True`) |
| `list` | Mutable Python sequence |
| `tuple` | Immutable Python sequence |
| `dict` | Python mapping semantics (Python hash/equals via `PyKey`) |
| `str` | Python Unicode string (code point semantics) |
| `type` | Python metaclass / type object |
| `None` | Python singleton |

The JVM implements these internally, but JVM semantics must never dictate
observable Python behavior.

### Principle 3 — CPython compatibility must be measurable
Never declare a feature compatible because a simple example works. Every
feature is tracked under the four-tier classification:

- **T0 — Parsed:** Accepted by the ANTLR4 grammar without syntax error.
- **T1 — Executes:** Bytecode emitted and runs without VM crash.
- **T2 — Differentially Matches CPython:** Verified byte-for-byte or
  semantic equivalence against CPython 3.14 via the differential test
  harness (`CoreDifferentialTest`).
- **T3 — Library Ready:** Verified against real-world Python packages and
  CPython regression suite modules.

A feature must **never** reach T2 without automated differential evidence.

### Principle 4 — Java interoperability is secondary
Java interop is a valuable capability, but it must never compromise Python
semantics. `my_list.pop()` must follow Python list semantics, regardless of
whether Java provides a similarly named method. Java classes are exposed
through a controlled bridge layer (`org.example.jython3.bridge`), never mixed
into the Python core object model.

### Principle 5 — Security is a separate boundary
Compatibility and security must not become competing excuses. In-process
Java access policies (`AllowlistPolicy`, `SandboxedPolicy`) restrict JVM
reflection and capability access, but **do not constitute an OS-level
sandbox**. Untrusted code execution requires process- or container-level
isolation.

---

## 3. Long-Term Development Roadmap (Phases I–VI)

```
Phase I    Runtime Correctness & Conformance Foundation  ◄── CURRENT MILESTONE
           ├── Repair known semantic defects (nested loop break, isinstance, bool arith, % format)
           ├── Stabilize VM stack invariants
           ├── Correct Python numeric tower
           └── Establish reproducible CPython 3.14 test baseline

Phase II   Python Object Model
           ├── First-class built-in type objects (type, int, str, float, bool, list, tuple, dict)
           ├── Immutable PyTuple distinct from PyList
           ├── Python special-method dispatch (__add__, __radd__, __getitem__, __iter__, etc.)
           └── Descriptors, attribute lookup, and MRO

Phase III  Language Compatibility
           ├── Complete Python 3.14 grammar coverage
           ├── Default & keyword arguments (*args, **kwargs)
           ├── List / dict / set comprehensions & generator expressions
           ├── Context managers (with statement)
           ├── F-strings
           └── Scope & closure semantics (global, nonlocal)

Phase IV   Standard Library Compatibility
           ├── Expansion of pure-Python and JVM-backed stdlib modules
           ├── Full re module (PCRE-compatible or documented delta)
           ├── Module import system & sys.path / modules directory
           └── CPython regression test suite integration

Phase V    Ecosystem Compatibility
           ├── Running real-world Python packages (pip compatibility subset)
           ├── Test framework support (pytest subset)
           └── Documented boundary for C-extension dependencies

Phase VI   Production Maturity
           ├── JVM bytecode generation / invokedynamic optimization
           ├── Concurrency model & thread safety
           ├── Stable embeddable Java API
           └── Cross-platform distribution & packaging
```

---

## 4. Milestone 1 Acceptance Criteria (Definition of Done)

All 9 criteria are required before closing Milestone 1:

1. [ ] **Baseline:** CPython 3.14 reference version and exact Jython3 commit recorded.
2. [ ] **Reproducibility:** Field-test suite can be rerun cleanly via Gradle / CLI.
3. [ ] **Nested control flow:** Nested `break`, `continue`, `return`, and exception unwinding pass.
4. [ ] **Numeric correctness:** Boolean, integer, float, and BigInt semantics verified against CPython.
5. [ ] **Built-in types:** `isinstance()` and `issubclass()` verified for all supported built-in types.
6. [ ] **Formatting:** Multiple-argument `%` formatting matches CPython.
7. [ ] **Regression protection:** Every confirmed defect has a permanent automated regression test.
8. [ ] **Architecture:** No new VM/runtime layering violations introduced.
9. [ ] **CI:** All existing and new regression tests pass cleanly.

---

## 5. Release Philosophy

- **Alpha (`v0.x.x-alpha`):** Active development of core semantics, object
  model, and language syntax. Breaking changes to internal APIs expected.
- **Beta (`v0.x.x-beta`):** Broad Python 3.14 syntax coverage achieved;
  focus shifts to stdlib completeness, bug fixing, and real-package testing.
- **Release Candidate (`v1.0.0-rcX`):** Feature-complete for targeted
  subset; all Phase I–IV acceptance gates satisfied.
- **Stable (`v1.0.0`):** Production-ready embedding library and CLI;
  documented compatibility; long-term API stability guarantees.

*A release version number alone must never be used to imply compatibility.*
