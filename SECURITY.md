# Security Policy & Architecture

## Security Philosophy & Defense-in-Depth

The `jython3-prototype` execution engine provides a multi-layered defense-in-depth security model to govern interactions between Python scripts and the host Java Virtual Machine.

> **CRITICAL ARCHITECTURAL NOTE**:
> No embedded language runtime can serve as an impenetrable security sandbox on its own. For evaluating untrusted user code in multi-tenant environments, in-process security policies **must be combined with operating-system boundaries** (such as separate OS processes, Linux containers, or cgroups with hard CPU, memory, and timeout limits).

---

## Security Policies

The engine provides three primary policy profiles via `JavaAccessPolicy`:

### 1. `AllowlistPolicy.trusted()` (Default)
Intended for trusted scripts authored by application developers or internal automation workflows (e.g. Oculix automation scripts).
- **Permitted Packages**: Standard utility, math, collection, and safe IO libraries (`java.lang`, `java.util`, `java.io`, `java.math`, `java.text`, `java.time`, `java.nio`, `java.net`).
- **Forbidden Internals**:
  - Process Control: `java.lang.Runtime`, `java.lang.ProcessBuilder`, `java.lang.Process`, `java.lang.ProcessHandle`.
  - Concurrency & Threads: `java.lang.Thread`, `java.lang.ThreadGroup`, `java.lang.ThreadLocal`.
  - Java Deserialization: `java.io.ObjectInputStream`, `java.io.ObjectOutputStream`, `java.io.ObjectStreamClass`.
  - ClassLoaders & Reflection: `java.lang.ClassLoader`, `java.net.URLClassLoader`, `sun.misc.Unsafe`, `jdk.internal.*`, `java.lang.reflect.*`.
  - Desktop Automation: `java.awt.Desktop`, `java.awt.Robot`.
- **Recursive Inner Class Containment**: All nested/inner classes of forbidden classes (e.g. `Thread$State`, `ProcessBuilder$Redirect`) are automatically and recursively rejected.

### 2. `SandboxedPolicy.get()`
Intended for untrusted or semi-trusted script execution within the host application.
- **Strictly Limited**: Only allows pure, in-memory data structures, math utilities, and strings (`java.lang.String`, `Integer`, `Double`, `Math`, `BigInteger`, `ArrayList`, `HashMap`, etc.).
- **Complete Denial**:
  - File I/O completely blocked (`java.io.*`, `java.nio.*` denied).
  - Network sockets completely blocked (`java.net.*` denied).
  - UI & graphics completely blocked (`java.awt.*` denied).
  - System commands, threads, and class loading completely blocked.

### 3. `DenyAllPolicy.INSTANCE`
Completely disables the Java Bridge. Scripts cannot import or interact with any host Java class under any circumstances.

---

## Trust Boundaries & Privilege Containment

1. **Wrapper Privilege Containment**:
   - `PyJavaClass` and `PyJavaObject` instances cannot escalate privileges when passed across interpreters with different policies.
   - Any attempt to access or convert a host Java wrapper under a restricted policy automatically enforces the target policy.
2. **Safe Object Representation**:
   - Restricted Java objects never execute arbitrary host code via `toString()`, returning sanitized indicators instead.
3. **Circular Reference Safety**:
   - Deep sanitization across policy boundaries uses graph-aware identity tracking (`IdentityHashMap`) to prevent denial-of-service via circular object references.

---

## Reporting a Security Vulnerability

If you discover a security vulnerability within `jython3-prototype`, please report it responsibly:

- **Do NOT** open a public issue on GitHub.
- Submit a detailed report including steps to reproduce directly to the repository maintainer via email or GitHub Security Advisories.
- Security reports receive priority evaluation and resolution.
