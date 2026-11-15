# Security Review: Real Hardware, Control, and Filesystem Capabilities

**Date:** 2026-11-15  
**Scope:** `org.example.jython3.api`, `org.example.jython3.host`, `org.example.jython3.control`, `org.example.jython3.ui`, `org.example.jython3.security`, `org.example.jython3.stdlib.modules.{OsModule, IoModule, UiModule, ControlModule}`

---

## 1. Threat Model & Trust Boundaries

`jython3` embeds user or plugin Python 3 code inside a host JVM process (Oculix IDE / Cinnamon). The security model assumes:
1. Embedded Python scripts may attempt privilege escalation via standard modules (`os`, `io`), Java reflection (`java.lang.Runtime`, `Class.forName`), host UI/control handles (`PyHostHandle`), symlink/path traversal or TOCTOU races, adapter return-value leakage, or resource exhaustion.
2. **WARNING — Defense-in-Depth Only:** In-process `CapabilitySet`, `JavaAccessPolicy`, and `HostAccessPolicy` checks are **NOT** a standalone OS sandbox. Untrusted third-party scripts and real physical hardware control MUST run inside an isolated OS process/container with OS-level filesystem, network, and CPU limits.

---

## 2. Verified Capability Matrix

| Capability | Default State | Required to Unlock | Audited | Notes |
| :--- | :--- | :--- | :--- | :--- |
| `UI`, `EVENTS`, `INPUT`, `TIMER` | **Denied** | Explicit `CapabilitySet.builder().allow(...)` | Yes | Strictly non-escalating: never grants `CONTROL`, `FILESYSTEM`, `PROCESS`, or `NETWORK`. |
| `CONTROL` (`SIMULATED` / `TRUSTED_HOST`) | **Denied** | `HostCapability.CONTROL` + `allowDevice(id)` + `allowCommand(cmd)` | Yes | `ControlDevice` / handler registration is a **trusted internal host-only API**. `ControlModule` and `HostCommandChannelAdapter` sanitize all arguments, handler return values, and state snapshots via `PolicySanitizer`. |
| `CONTROL` (`HARDWARE`) | **Denied** | Above + `CapabilitySet.Builder.allowHardwareAdapters(true)` | Yes | Adapters with `ControlDevice.TrustTier.HARDWARE` are blocked unless explicitly opted in; physical hardware interlocks must live in signed/trusted host Java adapters inside an isolated OS process/container. |
| `FILESYSTEM` | **Denied** | `HostCapability.FILESYSTEM` + explicit `HostAccessPolicy` or `ScopedWorkspaceAdapter` | Yes | `ScopedWorkspaceAdapter` additionally requires `CONTROL` + device/command allowlist, uses POSIX `SecureDirectoryStream` + `LinkOption.NOFOLLOW_LINKS` (`O_NOFOLLOW`) to defeat TOCTOU symlink/directory-swap races, and enforces `maxBytes` + `readOnly`. |
| `PROCESS` | **Denied** | `HostCapability.PROCESS` (reserved) | Yes | No subprocess spawning API is exposed to Python. `OsModule.getpid()` is gated behind `HostAccessPolicy.canReadEnvironment()`. |
| `NETWORK` | **Denied** | `HostCapability.NETWORK` (reserved) | Yes | No socket/HTTP module is exposed by default. |

---

## 3. Hardening Findings Closed During Review

1. **Unbound & cross-session `PyHostHandle` smuggling (Fixed in `6e21315`, `93dd80e`):**
   - `PyHostHandle.Builder.build()` now requires `.bindSession(HostSession)` — unbound handles cannot be created.
   - `PolicySanitizer` rejects `targetSession == null || hh.session() == null || hh.session() != targetSession` across direct values, `list`, `dict`, `tuple`, `set`, `frozenset`, `module`, `closure`/`PyCell`, `PyBoundMethod`, `generator`, and `HostSession.invokeCallback(...)` arguments.
   - `HostAppBridge.bindHandle(...)` / `EmbeddedRuntime.bindHandle(...)` verify both matching `HostSession` and valid `sessionEpoch`.
2. **Control adapter argument/result/state leakage (Fixed in `c73a81f`):**
   - `ControlModule` and `HostCommandChannelAdapter` sanitize every command argument, handler return value, and state entry via `PolicySanitizer`, rejecting `PyJavaObject`, `PyJavaClass`, and foreign/unbound `PyHostHandle`s with `SecurityError` + `HostAuditLog` entry.
3. **Workspace filesystem TOCTOU symlink-race hardening (Fixed in `d68a4c2`):**
   - `ScopedWorkspaceAdapter` traverses segments and opens files using `SecureDirectoryStream` + `LinkOption.NOFOLLOW_LINKS` (`openat(..., O_NOFOLLOW)` on Linux) and is verified under concurrent symlink-swap stress (`ScopedWorkspaceRaceTest`).
4. **`OsModule.getcwd()` and `OsModule.getpid()` leak (Fixed in `c248b20`):**
   - Gated behind `hostPolicy.canListDirectory(Path.of("."))` and `hostPolicy.canReadEnvironment()`, throwing `SecurityError` when denied.
5. **Stale handle & async timer teardown races (Fixed in `eddfcb4` / `e276070`):**
   - `HostSession.beginTeardown()` / `invalidateHandles()` increment the session epoch immediately outside locks so in-flight callbacks and stale widget/control/event handles fail deterministically with `RuntimeError`.

---

## 4. Preconditions Before Enabling Real Physical Hardware or Unscoped Filesystem

1. Keep `CapabilitySet.none()` (`allowHardwareAdapters = false`) as the default for all third-party plugins.
2. Only register signed/reviewed `ControlDevice` implementations from trusted host code — never allow plugins to register `ControlDevice` or `HostCommandChannelAdapter` handlers.
3. Require explicit `allowHardwareAdapters(true)`, per-device/per-command allowlists, and hardware-level safety interlocks (e-stop, rate limits, physical range clamps) inside the Java `ControlDevice` itself.
4. For file access, prefer `ScopedWorkspaceAdapter` with `readOnly = true` (or a dedicated scratch workspace root) rather than `HostAccessPolicy.PERMISSIVE`.
5. Run untrusted third-party scripts and physical hardware control inside a separate OS subprocess/container with OS-level filesystem/network/CPU isolation.
