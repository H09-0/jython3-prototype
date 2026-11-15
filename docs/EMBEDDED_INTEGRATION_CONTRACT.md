# Jython 3 — Embedded Runtime Integration Contract (Integration Beta)

> **Audience**: Embedders integrating `jython3-prototype` into host applications (such as **Oculix IDE** and **Cinnamon**).
> **Live Status Reference**: [STATUS.md](STATUS.md) | [COMPATIBILITY.md](COMPATIBILITY.md) | [SECURITY.md](../SECURITY.md)

---

## 1. Stable Java Embedder Surface

The following packages and entry points constitute the supported embedding contract for host UI, event dispatch, and device control:

| Layer | Stable Types (`org.example.jython3.*`) | Responsibility |
| :--- | :--- | :--- |
| **Public API** | `api.EmbeddedRuntime`, `api.RuntimeVersion` | Versioned embedding facade (`1.0.0-beta`) with compatibility negotiation (`requireCompatible`), feature probes (`supportsFeature`), builder configuration, and stable lifecycle entry points. |
| **Facade** | `host.HostAppBridge` | Single-entry host wrapper (`bindModelData`, `bindHandle`, `registerDevice`, `runScript`, `cancelRunningScript`, `restart`, `close`). |
| **Capabilities** | `host.HostCapability`, `host.CapabilitySet`, `host.HostAuditLog`, `host.HostSession` | Deny-by-default capability gating (`UI`, `EVENTS`, `INPUT`, `TIMER`, `FILESYSTEM`, `PROCESS`, `NETWORK`, `CONTROL`), per-device/command allowlists, centralized quotas, audit trail, and lifecycle teardown. |
| **Opaque Handles** | `runtime.PyHostHandle` | Reflection-free Python handle exposing only whitelisted properties and methods; bound to `HostSession.epoch()` so stale handles raise `RuntimeError` after `resetExecutionState()` / `close()`. |
| **UI & Dispatch** | `ui.UiDispatcher`, `ui.UiToolkit`, `ui.UiEvent`, `ui.HeadlessUiToolkit`, `ui.SwingUiToolkit`, `ui.JavaFxUiAdapter` | Display-independent layout (`FLOW`, `VBOX`, `HBOX`, `GRID`) and widget (`Window`, `Button`, `Label`, `TextField`, `Panel`, `CheckBox`, `ProgressBar`, `ListView`, `Timer`) abstractions with immutable thread ownership, Swing EDT dispatch, and JavaFX Application Thread dispatch (`FxDispatcher`). |
| **Control** | `control.ControlDevice`, `control.ControlRegistry`, `control.SimulatedDevice`, `control.JvmTelemetryAdapter`, `control.HostCommandChannelAdapter`, `control.ScopedWorkspaceAdapter` | Device command execution and live state views gated by `HostCapability.CONTROL` + allowlists (and `FILESYSTEM` + canonical root containment for `ScopedWorkspaceAdapter`). |

---

## 2. Python Host Modules Contract

### `ui` module
- **Requires `HostCapability.UI`**: `ui.Window(title, width=640, height=480)`, `ui.Button(text)`, `ui.Label(text)`, `ui.TextField(text)`, `ui.Panel(layout="flow", spacing=0, columns=2)`, `ui.VBox(spacing=0)`, `ui.HBox(spacing=0)`, `ui.Grid(columns=2, spacing=0)`, `ui.CheckBox(text="", checked=False)`, `ui.ProgressBar(value=0.0, indeterminate=False)`, `ui.ListView(items=[])`.
- **Requires `HostCapability.EVENTS` (+ `INPUT` for interactive widgets)**: `btn.on_click(fn)`, `textfield.on_change(fn)`, `checkbox.on_change(fn)`, `listview.on_select(fn)`, `window.on_close(fn)`. Callbacks receive an opaque `ui.Event` (`type`, `source_id`, `text`, `timestamp`).
- **Requires `HostCapability.TIMER`**: `ui.Timer(interval_ms, callback)` with `.start()` and `.stop()`.

### `control` module
- **Requires `HostCapability.CONTROL` + device/command allowlist**:
  - `ctrl = control.Controller(device_id)`
  - `cmd = control.Command(name, args=None, **kwargs)`
  - `ctrl.send(cmd_or_name, args_dict=None, **kwargs)`
  - `st = ctrl.state()` → returns a **live** `control.State` view (`st.get(key, default=None)`, `st.to_dict()`, plus explicit `st.snapshot()` for a frozen copy).
  - `ctrl.on_event(fn)` (requires `CONTROL` + `EVENTS`), `ctrl.close()`.
  - `control.Timer(interval_ms, callback)` (requires `CONTROL` + `TIMER`).

---

## 3. Lifecycle, Threading & Resource Guarantees

1. **Immutable UI Thread Ownership**:
   - `HeadlessUiToolkit.HeadlessDispatcher` binds `uiThread` permanently at creation (`HeadlessUiToolkit.withDedicatedThread()` or creator-thread with `pumpEvents()`). Off-thread UI mutations raise `RuntimeError`.
   - `SwingUiToolkit` binds to the Swing Event Dispatch Thread; `JavaFxUiAdapter` binds to the host's FX Application Thread with bounded synchronous `runAndWait` timeouts (`TimeoutError` if stalled).
2. **Callback Timeout & Cancellation**:
   - `HostSession.invokeCallback` wraps every host callback in a child `ExecutionContext` honoring `CapabilitySet.callbackTimeout()` and a daemon watchdog (`CALLBACK_WATCHDOG`). Runaway loops or blocking `time.sleep` inside callbacks terminate with `TimeoutError` and clean up thread interrupt state.
   - `HostAppBridge.cancelRunningScript()` / `Jython3Interpreter.cancel()` sets cooperative cancellation flags immediately, aborting active scripts and callbacks with `KeyboardInterrupt`.
3. **Centralized Quotas & Cleanup**:
   - `HostSession` enforces `maxWindows`, `maxTimers`, `maxListenersPerNode`, and `maxCallbackDepth` uniformly across all `UiToolkit` and `ControlModule` backends.
   - `HostAppBridge.restart()` / `Jython3Interpreter.resetExecutionState()` and `close()` close all windows, stop all timers, detach all listeners, increment the session `epoch` (invalidating all previously issued `PyHostHandle`s), and clear Python state.
4. **Async Error Auditing**:
   - Uncaught exceptions in timer callbacks stop the offending timer immediately, record an `async_callback_error` entry in `HostAuditLog` + `HostSession.callbackFailures()`, and notify any host error handler registered via `HostSession.setCallbackErrorHandler(...)`.

---

## 4. Security Boundaries & Pre-Hardware Checklist

- **No arbitrary Java reflection**: `PyHostHandle` objects are not `PyJavaObject`s; `getClass()`, `MethodHandles`, `ClassLoader`, `Unsafe`, `Runtime`, `ProcessBuilder`, `java.io`, and `java.net` remain blocked under `DenyAllPolicy` / `SandboxedPolicy` even when `UI`/`CONTROL` capabilities are granted.
- **No cross-interpreter smuggling**: `PolicySanitizer` rejects `PyHostHandle`s belonging to a different `HostSession` with `SecurityError` (directly or nested inside lists, dicts, modules, generators, or closures).
- **Not a standalone OS sandbox**: In-process policies provide defense-in-depth gating. Untrusted third-party scripts must still run inside OS process/container isolation.
- **Before connecting real hardware or `FILESYSTEM` / `PROCESS` / `NETWORK` capabilities**: conduct an independent security review of every custom `ControlDevice` implementation and host adapter.

---

## 5. Verified Baseline Metrics (Integration Beta)

- **Automated Test Suite**: `486` tests (`0` failures, `0` skipped) including `HostCapabilityTest`, `HeadlessUiTest`, `SwingAndJavaFxDispatchTest`, `HostCapabilitiesSecurityTest`, `HostSecurityAuditTest`, `HostConcurrencyAndTeardownTest`, `ControlDeviceConcurrencyTest`, `OculixCinnamonIntegrationTest`, and `HostDispatchPerformanceTest`.
- **2,000-Cycle UI/Control Teardown Stress**: `0` leaked windows, `0` leaked timers, `0` leaked threads, `0` leaked managed resources.
- **Host Dispatch Throughput (`HostDispatchBenchmark`)**:
  - `ui_event_dispatch_10k`: `54.91 ms` (`182,113 ops/sec`)
  - `control_commands_10k`: `25.90 ms` (`386,051 ops/sec`)
  - `timer_churn_500`: `18.66 ms` (`26,794 ops/sec`)
  - `fx_thread_dispatch_2k`: `42.86 ms` (`46,664 ops/sec`)
