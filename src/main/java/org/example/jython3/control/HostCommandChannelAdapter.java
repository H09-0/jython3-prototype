package org.example.jython3.control;

import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyStr;

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
 */
public final class HostCommandChannelAdapter implements ControlDevice {

    private final String id;
    private final Map<String, PyObject> state = new ConcurrentHashMap<>();
    private final Map<String, BiFunction<Map<String, PyObject>, Map<String, PyObject>, PyObject>> handlers;

    private HostCommandChannelAdapter(
            String id,
            Map<String, PyObject> initialState,
            Map<String, BiFunction<Map<String, PyObject>, Map<String, PyObject>, PyObject>> handlers) {
        this.id = Objects.requireNonNull(id, "id");
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
        Map<String, PyObject> safeArgs = args == null ? Map.of() : Collections.unmodifiableMap(args);
        PyObject result = handler.apply(state, safeArgs);
        return result != null ? result : PyNull.INSTANCE;
    }

    @Override
    public Map<String, PyObject> snapshotState() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(state));
    }

    public void setHostState(String key, PyObject value) {
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(value, "value");
        state.put(key, value);
    }

    public static final class Builder {
        private final String id;
        private final Map<String, PyObject> initialState = new LinkedHashMap<>();
        private final Map<String, BiFunction<Map<String, PyObject>, Map<String, PyObject>, PyObject>> handlers = new LinkedHashMap<>();

        private Builder(String id) {
            this.id = Objects.requireNonNull(id, "id");
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
            return new HostCommandChannelAdapter(id, initialState, handlers);
        }
    }
}
