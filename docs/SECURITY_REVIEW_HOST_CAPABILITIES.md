# Security Review: Real Hardware, Control, and Filesystem Capabilities

**Date:** 2026-11-15  
**Scope:** `org.example.jython3.api`, `org.example.jython3.host`, `org.example.jython3.control`, `org.example.jython3.ui`, `org.example.jython3.stdlib.modules.{OsModule, IoModule, UiModule, ControlModule}`

---

## 1. Threat Model & Trust Boundaries

`jython3` embeds user or plugin Python 3 code inside a host JVM process (Oculix IDE / Cinnamon). The security model assumes:
1. Embedded Python scripts may attempt privilege escalation via standard modules (`os`, `io`), Java reflection (`java.lang.Runtime`, `Class.forName`), host UI/control handles (`PyHostHandle`), symlink/path traversal, or resource exhaustion.
2. **In-process capability checks are defense-in-depth**, NOT a replacement for OS process/container isolation if fully untrusted adversarial code is run.

---

## 2. Verified Capability Matrix

| Capability | Default State | Required to Unlock | Audited | Notes |
| :--- | :--- | :--- | :--- | :--- |
| `UI`, `EVENTS`, `INPUT`, `TIMER` | **Denied** | Explicit `CapabilitySet.builder().allow(...)` | Yes | Strictly non-escalating: never grants `CONTROL`, `FILESYSTEM`, `PROCESS`, or `NETWORK`. |
| `CONTROL` | **Denied** | `HostCapability.CONTROL` + `allowDevice(id)` + `allowCommand(cmd)` | Yes | Real adapters (`JvmTelemetryAdapter`, `HostCommandChannelAdapter`) expose zero Java reflection or raw Java objects. |
| `FILESYSTEM` | **Denied** | `HostCapability.FILESYSTEM` + explicit `HostAccessPolicy` or `ScopedWorkspaceAdapter` | Yes | `ScopedWorkspaceAdapter` additionally requires `CONTROL` + device/command allowlist, enforces `Path.toRealPath()` root containment, rejects absolute paths, `..`, NUL bytes, symlinks, and enforces `maxBytes` + `readOnly`. |
| `PROCESS` | **Denied** | `HostCapability.PROCESS` (reserved) | Yes | No subprocess spawning API is exposed to Python. `OsModule.getpid()` is gated behind `HostAccessPolicy.canReadEnvironment()`. |
| `NETWORK` | **Denied** | `HostCapability.NETWORK` (reserved) | Yes | No socket/HTTP module is exposed by default. |

---

## 3. Hardening Findings Closed During Review

1. **`OsModule.getcwd()` and `OsModule.getpid()` leak (Fixed in `c248b20`):**
   - Previously returned `user.dir` and host PID even under `HostAccessPolicy.DENY_ALL`.
   - Now gated behind `hostPolicy.canListDirectory(Path.of("."))` and `hostPolicy.canReadEnvironment()`, throwing `SecurityError` when denied.
2. **Cross-interpreter `PyHostHandle` smuggling (Fixed in `e276070`):**
   - `PolicySanitizer.sanitize(..., HostSession)` rejects foreign-session `PyHostHandle` instances across primitives, containers, closures, modules, and generator frames.
3. **Stale handle & async timer teardown races (Fixed in `eddfcb4` / `e276070`):**
   - `HostSession.beginTeardown()` / `invalidateHandles()` increment the session epoch immediately outside locks so in-flight callbacks and stale widget/control/event handles fail deterministically with `RuntimeError`.

---

## 4. Preconditions Before Enabling Real Physical Hardware or Unscoped Filesystem

Before attaching real physical hardware (CNC / serial / GPIO / actuators) or host-wide filesystem access in Oculix/Cinnamon:
1. Keep `CapabilitySet.none()` as the default for all third-party plugins.
2. Require explicit per-device and per-command allowlists (`allowDevice`, `allowCommand`) plus hardware-level safety interlocks (e-stop, rate limits, physical range clamps) inside the Java `ControlDevice` implementation itself — never rely on Python script checks.
3. For file access, prefer `ScopedWorkspaceAdapter` with `readOnly = true` (or a dedicated scratch workspace root) rather than `HostAccessPolicy.PERMISSIVE`.
4. Run untrusted third-party scripts in a separate OS subprocess/sandboxed worker with OS-level filesystem/network/CPU limits.
