# Security Review: Real Hardware, Control, and Filesystem Capabilities

**Date:** 2026-11-15  
**Scope:** `org.example.jython3.api`, `org.example.jython3.host`, `org.example.jython3.control`, `org.example.jython3.ui`, `org.example.jython3.security`, `org.example.jython3.stdlib.modules.{OsModule, IoModule, UiModule, ControlModule}`

---

## 1. Threat Model & Trust Boundaries

`jython3` embeds user or plugin Python 3 code inside a host JVM process (Oculix IDE / Cinnamon). The security model assumes:
1. Embedded Python scripts may attempt privilege escalation via standard modules (`os`, `io`), Java reflection (`java.lang.Runtime`, `Class.forName`), host UI/control handles (`PyHostHandle`), symlink/path traversal or TOCTOU races, forged adapter tiers, adapter return-value leakage, or resource exhaustion.
2. **WARNING — Defense-in-Depth Only:** In-process `CapabilitySet`, `JavaAccessPolicy`, and `HostAccessPolicy` checks are **NOT** a standalone OS sandbox. Untrusted third-party scripts and real physical hardware control MUST run inside an isolated OS process/container with OS-level filesystem, network, and CPU limits.

---

## 2. Verified Capability & Trust Matrix

| Capability / Tier | Default State | Required to Unlock | Audited | Notes |
| :--- | :--- | :--- | :--- | :--- |
| `UI`, `EVENTS`, `INPUT`, `TIMER` | **Denied** | Explicit `CapabilitySet.builder().allow(...)` | Yes | Strictly non-escalating: never grants `CONTROL`, `FILESYSTEM`, `PROCESS`, or `NETWORK`. |
| `CONTROL` (`AttestedTier.SIMULATED` / built-in `TRUSTED_HOST`) | **Denied** | `HostCapability.CONTROL` + `allowDevice(id)` + `allowCommand(cmd)` | Yes | `ControlModule` and `HostCommandChannelAdapter` sanitize all arguments, handler return values, and state snapshots via `PolicySanitizer`. |
| `CONTROL` (Custom / External `TRUSTED_HOST`) | **Denied** | Registered via `registerAttestedDevice(dev, HostAttestation.trustedHost(...))` + `allowAdapterOrigin(...)` (if configured) | Yes | Self-reported `ControlDevice.trustTier()` is **ignored**. Unattested custom adapters passed to `registerDevice(...)` are marked `AttestedTier.UNTRUSTED` and rejected. |
| `CONTROL` (`AttestedTier.HARDWARE`) | **Denied** | `registerAttestedDevice(dev, HostAttestation.hardware(...))` + `allowHardwareAdapters(true)` + `allowAdapterOrigin(...)` | Yes | Duplicate device IDs cannot overwrite existing registrations; `lockDeviceRegistrations()` seals `ControlRegistry` before script execution. |
| `FILESYSTEM` | **Denied** | `HostCapability.FILESYSTEM` + explicit `HostAccessPolicy` or `ScopedWorkspaceAdapter` | Yes | `ScopedWorkspaceAdapter` requires `CONTROL` + device/command allowlist, uses POSIX `SecureDirectoryStream` + `LinkOption.NOFOLLOW_LINKS` (`O_NOFOLLOW`) with canonical fallback on non-POSIX filesystems, normalizes backslashes, and blocks drive/UNC paths, symlinks, `maxBytes`, and `readOnly` writes. |
| `PROCESS` | **Denied** | `HostCapability.PROCESS` (reserved) | Yes | No subprocess spawning API is exposed to Python. `OsModule.getpid()` is gated behind `HostAccessPolicy.canReadEnvironment()`. |
| `NETWORK` | **Denied** | `HostCapability.NETWORK` (reserved) | Yes | No socket/HTTP module is exposed by default. |
| `THREADS` | **Denied** | `HostCapability.THREADS` + `maxThreads`, `maxThreadPools`, `maxQueuedTasks`, `executorShutdownTimeout` | Yes | Requires **Java 21+** (`Thread.ofVirtual()`). Enforces cancellation-aware bounded shutdown/locking/futures (`ConcurrentFuturesHardeningTest`). |

---

## 3. Hardening Findings Closed During Review

1. **Host-Attested Adapter Trust & Anti-Spoofing (`1de4775`, `a9c6382`, `43ba335`):**
   - `ControlRegistry` assigns `AttestedTier` at registration time (`SIMULATED`, `TRUSTED_HOST`, `HARDWARE`, `UNTRUSTED`), ignoring self-reported `ControlDevice.trustTier()`.
   - Custom adapters without `HostAttestation` are forced to `UNTRUSTED` and blocked (`ForgedAdapterSecurityTest`).
   - `ControlRegistry` prevents device-ID shadowing/overwrites, supports `seal()` / `lockDeviceRegistrations()`, and enforces `CapabilitySet.allowedAdapterOrigins()` with full audit logging (`AdapterRegistrationPolicyTest`).
2. **Unbound & cross-session `PyHostHandle` smuggling (`6e21315`, `93dd80e`):**
   - `PyHostHandle.Builder.build()` requires `.bindSession(HostSession)`. `PolicySanitizer`, `HostSession.invokeCallback(...)`, and `HostAppBridge.bindHandle(...)` reject unbound/foreign/stale-epoch handles across all containers, closures, and generators (`HandleSmugglingSecurityTest`).
3. **Control adapter argument/result/state leakage (`c73a81f`):**
   - `ControlModule` and `HostCommandChannelAdapter` sanitize every argument, return value, and state entry via `PolicySanitizer`, blocking `PyJavaObject` and foreign handles (`ControlAdapterSanitizationTest`).
4. **Cross-platform workspace TOCTOU & path hardening (`d68a4c2`, `f577f7b`):**
   - `ScopedWorkspaceAdapter` uses `SecureDirectoryStream` + `LinkOption.NOFOLLOW_LINKS` on POSIX, canonical `NOFOLLOW_LINKS` fallback on non-POSIX/virtual filesystems (`CrossPlatformWorkspaceTest`), and defeats symlink-swap races (`ScopedWorkspaceRaceTest`).
5. **`OsModule.getcwd()` and `OsModule.getpid()` leak (`c248b20`):**
   - Gated behind `HostAccessPolicy.canListDirectory` and `canReadEnvironment`.

---

## 4. Trusted Adapter Deployment Requirements (Oculix / Cinnamon)

Before deploying `jython3` with real hardware or host-integrated adapters in production:
1. **Trusted Host-Only Registration:** Only signed, code-reviewed host bootstrap code may call `EmbeddedRuntime.registerAttestedDevice(device, HostAttestation)` — followed immediately by `rt.lockDeviceRegistrations()` before evaluating any plugin script.
2. **Origin Allowlisting:** Always configure `CapabilitySet.Builder.allowAdapterOrigin(...)` alongside `allowHardwareAdapters(true)` when granting hardware access.
3. **Hardware Safety Interlocks:** Physical safety limits (e-stop, motion/rate bounds, watchdog timeouts) MUST be implemented inside the host Java `ControlDevice` / firmware layer, never in Python scripts.
4. **Workspace Containment:** Prefer `ScopedWorkspaceAdapter` with `readOnly = true` (or a dedicated scratch workspace directory) over `HostAccessPolicy.PERMISSIVE`.
5. **Process / Container Isolation:** Run untrusted third-party plugins and physical hardware control inside an isolated OS worker process or container with OS-level filesystem, network, and CPU quotas.
