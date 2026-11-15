package org.example.jython3.control;

import org.example.jython3.host.HostCapability;
import org.example.jython3.host.HostSession;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.security.DenyAllPolicy;
import org.example.jython3.security.PolicySanitizer;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;

/**
 * Real host-wired control channel adapter for Oculix / Cinnamon subsystems.
 * Routes explicitly registered commands to typed Java handlers without exposing
 * underlying Java service objects or reflection to Python scripts.
 *
 * <p><b>Trust Boundary:</b> Registering {@link ControlDevice} instances and command
 * handlers is a <b>trusted internal host-only API</b> and must never be exposed
 * to untrusted third-party plugins. Arguments, results, and state entries are
 * sanitized against {@link PolicySanitizer} (and the bound {@link HostSession}
 * when {@link Builder#bindSession(HostSession)} is configured).
 */
public final class HostCommandChannelAdapter implements ControlDevice {

    private final String id;
    private final HostSession session;
    private final Map<String, PyObject> state = new ConcurrentHashMap<>();
    private final Map<String, BiFunction<Map<String, PyObject>, Map<String, PyObject>, PyObject>> handlers;

    private HostCommandChannelAdapter(
            String id,
            HostSession session,
            Map<String, PyObject> initialState,
            Map<String, BiFunction<Map<String, PyObject>, Map<String, PyObject>, PyObject>> handlers) {
        this.id = Objects.requireNonNull(id, "id");
        this.session = session;
        this.state.putAll(initialState);
        this.handlers = Map.copyOf(handlers);
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    @Override
    public String id() { return id; }

    @Override
    public PyObject execute(String command, Map<String, PyObject> args) {
        if ("ping".equals(command) && !handlers.containsKey("ping")) {
            return new PyStr("pong:" + id);
        }
        var handler = handlers.get(command);
        if (handler == null) {
            throw PyErrors.error("ValueError",
                    "unknown command '" + command + "' on host adapter '" + id + "'");
        }
        Map<String, PyObject> sanitizedArgs = new LinkedHashMap<>();
        if (args != null) {
            for (var e : args.entrySet()) {
                sanitizedArgs.put(e.getKey(), sanitize("sanitize_adapter_arg", command + ":" + e.getKey(), e.getValue()));
            }
        }
        Map<String, PyObject> safeArgs = Collections.unmodifiableMap(sanitizedArgs);
        PyObject result = handler.apply(state, safeArgs);
        return result != null ? sanitize("sanitize_adapter_result", command, result) : PyNull.INSTANCE;
    }

    @Override
    public Map<String, PyObject> snapshotState() {
        Map<String, PyObject> snap = new LinkedHashMap<>(state.size());
        for (var e : state.entrySet()) {
            snap.put(e.getKey(), sanitize("sanitize_adapter_state", e.getKey(), e.getValue()));
        }
        return Collections.unmodifiableMap(snap);
    }

    public void setHostState(String key, PyObject value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        state.put(key, sanitize("sanitize_adapter_state", key, value));
    }

    private PyObject sanitize(String action, String target, PyObject value) {
        if (value == null || value instanceof PyNull) return value;
        try {
            var policy = session != null ? session.javaAccessPolicy() : DenyAllPolicy.INSTANCE;
            return PolicySanitizer.sanitize(value, policy, session);
        } catch (InterpreterException ex) {
            if (session != null) {
                session.auditLog().recordDenied(HostCapability.CONTROL, action, id + ":" + target, ex.getMessage());
            }
            throw ex;
        }
    }

    public static final class Builder {
        private final String id;
        private HostSession session;
        private final Map<String, PyObject> initialState = new LinkedHashMap<>();
        private final Map<String, BiFunction<Map<String, PyObject>, Map<String, PyObject>, PyObject>> handlers = new LinkedHashMap<>();

        private Builder(String id) {
            this.id = Objects.requireNonNull(id, "id");
        }

        public Builder bindSession(HostSession session) {
            this.session = Objects.requireNonNull(session, "session");
            return this;
        }

        public Builder initialState(String key, PyObject value) {
            initialState.put(Objects.requireNonNull(key, "key"), Objects.requireNonNull(value, "value"));
            return this;
        }

        public Builder onCommand(String command,
                                 BiFunction<Map<String, PyObject>, Map<String, PyObject>, PyObject> handler) {
            handlers.put(Objects.requireNonNull(command, "command"), Objects.requireNonNull(handler, "handler"));
            return this;
        }

        public HostCommandChannelAdapter build() {
            return new HostCommandChannelAdapter(id, session, initialState, handlers);
        }
    }
}
