package org.example.jython3.control;

import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyStr;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Safe, deterministic in-memory simulated device for testing and dry-run
 * control automation without touching physical hardware or OS APIs.
 */
public final class SimulatedDevice implements ControlDevice {

    private final String id;
    private final Map<String, PyObject> state = new LinkedHashMap<>();
    private final List<String> commandHistory = new ArrayList<>();

    public SimulatedDevice(String id) {
        this.id = id;
        this.state.put("power", PyBool.FALSE);
        this.state.put("level", PyInt.ZERO);
        this.state.put("mode", new PyStr("idle"));
    }

    @Override public String id() { return id; }

    @Override
    public synchronized PyObject execute(String command, Map<String, PyObject> args) {
        commandHistory.add(command);
        return switch (command) {
            case "ping" -> new PyStr("pong:" + id);
            case "power_on" -> { state.put("power", PyBool.TRUE); yield PyBool.TRUE; }
            case "power_off" -> { state.put("power", PyBool.FALSE); yield PyBool.FALSE; }
            case "toggle" -> {
                boolean cur = state.get("power") instanceof PyBool b && b.value();
                PyBool next = PyBool.of(!cur);
                state.put("power", next);
                yield next;
            }
            case "set_level" -> {
                PyObject v = args != null ? args.get("value") : null;
                if (v == null) throw PyErrors.error("ValueError", "set_level requires 'value'");
                state.put("level", v);
                yield v;
            }
            case "set_mode" -> {
                PyObject v = args != null ? args.get("mode") : null;
                if (!(v instanceof PyStr)) throw PyErrors.error("TypeError", "set_mode requires str 'mode'");
                state.put("mode", v);
                yield v;
            }
            case "reset" -> {
                state.put("power", PyBool.FALSE);
                state.put("level", PyInt.ZERO);
                state.put("mode", new PyStr("idle"));
                yield PyNull.INSTANCE;
            }
            default -> throw PyErrors.error("ValueError", "unknown command '" + command + "' on device '" + id + "'");
        };
    }

    @Override
    public synchronized Map<String, PyObject> snapshotState() {
        return Collections.unmodifiableMap(new LinkedHashMap<>(state));
    }

    public synchronized List<String> commandHistory() {
        return List.copyOf(commandHistory);
    }
}
