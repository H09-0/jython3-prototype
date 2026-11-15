package org.example.jython3.api;

import org.example.jython3.control.ControlDevice;
import org.example.jython3.control.HostAttestation;
import org.example.jython3.host.CapabilitySet;
import org.example.jython3.host.HostAppBridge;
import org.example.jython3.host.HostAuditLog;
import org.example.jython3.host.HostSession;
import org.example.jython3.runtime.PyHostHandle;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.security.ExecutionLimits;
import org.example.jython3.ui.HeadlessUiToolkit;
import org.example.jython3.ui.UiToolkit;

import java.nio.file.Path;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.function.BiConsumer;

/**
 * Versioned public embedding API facade for host applications (Oculix / Cinnamon).
 *
 * <p>Shields embedders from internal VM/compiler packages ({@code compile.*},
 * {@code Jython3Interpreter} internals) while exposing capability-gated UI,
 * control, data binding, cancellation, audit logs, and deterministic teardown.
 */
public final class EmbeddedRuntime implements AutoCloseable {

    public static final Set<String> SUPPORTED_FEATURES = Set.of(
            "capability-gating",
            "opaque-host-handles",
            "headless-ui",
            "swing-edt-ui",
            "javafx-dispatch-adapter",
            "ui-layouts",
            "basic-widgets",
            "simulated-control",
            "live-control-state",
            "lazy-generators",
            "yield-from",
            "callback-timeout-watchdog",
            "audit-log"
    );

    private final HostAppBridge bridge;
    private final UiToolkit uiToolkit;

    private EmbeddedRuntime(Builder builder) {
        this.uiToolkit = builder.uiToolkit != null ? builder.uiToolkit : new HeadlessUiToolkit();
        this.bridge = new HostAppBridge(builder.capabilities, this.uiToolkit, builder.limits);
        for (Path p : builder.modulePaths) {
            this.bridge.interpreter().addModulePath(p);
        }
        if (builder.callbackErrorHandler != null) {
            this.bridge.interpreter().hostSession().setCallbackErrorHandler(builder.callbackErrorHandler);
        }
    }

    public static Builder builder() { return new Builder(); }

    public RuntimeVersion apiVersion() { return RuntimeVersion.CURRENT_API; }

    public boolean supportsFeature(String feature) {
        return feature != null && SUPPORTED_FEATURES.contains(feature);
    }

    public void requireCompatible(RuntimeVersion minimumRequired) {
        if (!apiVersion().isCompatibleWith(minimumRequired)) {
            throw new IllegalStateException("EmbeddedRuntime API " + apiVersion()
                    + " is incompatible with required " + minimumRequired);
        }
    }

    public UiToolkit uiToolkit() { return uiToolkit; }

    public HostSession hostSession() { return bridge.interpreter().hostSession(); }

    public void registerDevice(ControlDevice device) {
        bridge.registerDevice(device);
    }

    public void registerAttestedDevice(ControlDevice device, HostAttestation attestation) {
        bridge.registerAttestedDevice(device, attestation);
    }

    public void lockDeviceRegistrations() {
        bridge.lockDeviceRegistrations();
    }

    public void bindModelData(String name, Object plainData) {
        bridge.bindModelData(name, plainData);
    }

    public void bindHandle(String name, PyHostHandle handle) {
        bridge.bindHandle(name, handle);
    }

    public void eval(String source) {
        bridge.runScript(source, "<embedded>");
    }

    public void eval(String source, String scriptName) {
        bridge.runScript(source, scriptName);
    }

    public PyObject getGlobal(String name) {
        return bridge.getGlobal(name);
    }

    public void cancel() {
        bridge.cancelRunningScript();
    }

    public void restart() {
        bridge.restart();
    }

    public boolean isClosed() {
        return bridge.interpreter().isClosed();
    }

    public List<HostAuditLog.AuditEntry> auditEntries() {
        return bridge.interpreter().hostSession().auditLog().entries();
    }

    public List<HostSession.CallbackFailure> callbackFailures() {
        return bridge.interpreter().hostSession().callbackFailures();
    }

    @Override
    public void close() {
        bridge.close();
    }

    public static final class Builder {
        private CapabilitySet capabilities = CapabilitySet.none();
        private ExecutionLimits limits = ExecutionLimits.SANDBOXED;
        private UiToolkit uiToolkit;
        private final java.util.List<Path> modulePaths = new java.util.ArrayList<>();
        private BiConsumer<String, Throwable> callbackErrorHandler;

        public Builder capabilities(CapabilitySet capabilities) {
            this.capabilities = Objects.requireNonNull(capabilities, "capabilities");
            return this;
        }

        public Builder limits(ExecutionLimits limits) {
            this.limits = Objects.requireNonNull(limits, "limits");
            return this;
        }

        public Builder uiToolkit(UiToolkit uiToolkit) {
            this.uiToolkit = Objects.requireNonNull(uiToolkit, "uiToolkit");
            return this;
        }

        public Builder addModulePath(Path dir) {
            this.modulePaths.add(Objects.requireNonNull(dir, "dir"));
            return this;
        }

        public Builder callbackErrorHandler(BiConsumer<String, Throwable> handler) {
            this.callbackErrorHandler = handler;
            return this;
        }

        public EmbeddedRuntime build() {
            return new EmbeddedRuntime(this);
        }
    }
}
