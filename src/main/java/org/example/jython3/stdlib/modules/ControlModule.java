package org.example.jython3.stdlib.modules;

import org.example.jython3.control.ControlDevice;
import org.example.jython3.control.ControlRegistry;
import org.example.jython3.host.HostCapability;
import org.example.jython3.host.HostSession;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyBuiltinFunction;
import org.example.jython3.runtime.PyDict;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyHostHandle;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyModule;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.ui.UiToolkit;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Capability-gated Python {@code control} module providing {@code Controller},
 * {@code Command}, {@code State}, {@code Event}, and {@code Timer} abstractions
 * with per-device/per-command authorization and full audit logging.
 */
public final class ControlModule {
    private ControlModule() {}

    public static PyModule create(HostSession session, ControlRegistry registry, UiToolkit uiToolkit) {
        Map<String, PyObject> attrs = new HashMap<>();

        attrs.put("Command", new PyBuiltinFunction("control.Command", (args, kwargs) -> {
            if (args.length < 1 || args.length > 2) {
                throw PyErrors.error("TypeError", "Command(name, args=None) takes 1 or 2 arguments");
            }
            if (!(args[0] instanceof PyStr cmdName)) {
                throw PyErrors.error("TypeError", "Command name must be str");
            }
            Map<String, PyObject> cmdArgs = new LinkedHashMap<>();
            if (args.length == 2 && !(args[1] instanceof PyNull)) {
                if (!(args[1] instanceof PyDict d)) {
                    throw PyErrors.error("TypeError", "Command args must be dict or None");
                }
                for (PyObject k : d.keys()) {
                    cmdArgs.put(k.toString(), d.get(k));
                }
            }
            for (var e : kwargs.entrySet()) {
                cmdArgs.put(e.getKey(), e.getValue());
            }
            PyDict argsDict = new PyDict();
            for (var e : cmdArgs.entrySet()) {
                argsDict.put(new PyStr(e.getKey()), e.getValue());
            }
            return PyHostHandle.builder("control.Command")
                    .prop("name", () -> cmdName)
                    .prop("args", () -> argsDict)
                    .build();
        }));

        attrs.put("Controller", new PyBuiltinFunction("control.Controller", args -> {
            if (args.length != 1 || !(args[0] instanceof PyStr idStr)) {
                throw PyErrors.error("TypeError", "Controller(device_id) requires a str device_id");
            }
            String deviceId = idStr.value();
            session.require(HostCapability.CONTROL, "open_controller", deviceId);
            if (!session.capabilities().canControlDevice(deviceId)) {
                session.auditLog().recordDenied(HostCapability.CONTROL, "open_controller", deviceId, "device not allowlisted");
                throw PyErrors.error("SecurityError", "control access to device '" + deviceId + "' is restricted");
            }
            ControlDevice device = registry.get(deviceId);
            if (device == null) {
                throw PyErrors.error("LookupError", "unknown control device '" + deviceId + "'");
            }
            return createControllerHandle(session, device);
        }));

        attrs.put("Timer", new PyBuiltinFunction("control.Timer", args -> {
            if (args.length != 2) {
                throw PyErrors.error("TypeError", "control.Timer(interval_ms, callback) takes 2 arguments");
            }
            session.require(HostCapability.CONTROL, "create_control_timer", "");
            int ms = (args[0] instanceof PyInt i) ? i.value() : -1;
            if (ms < 1) throw PyErrors.error("ValueError", "timer interval must be >= 1 ms");
            UiToolkit.UiTimer timer = uiToolkit.createTimer(session, ms, args[1]);
            return PyHostHandle.builder("control.Timer")
                    .prop("id", () -> new PyStr(timer.id()))
                    .prop("running", () -> PyBool.of(timer.isRunning()))
                    .method("start", new PyBuiltinFunction("Timer.start", a -> { timer.start(); return PyNull.INSTANCE; }))
                    .method("stop", new PyBuiltinFunction("Timer.stop", a -> { timer.stop(); return PyNull.INSTANCE; }))
                    .build();
        }));

        return new PyModule("control", attrs);
    }

    private static PyHostHandle createControllerHandle(HostSession session, ControlDevice device) {
        final List<PyObject> listeners = new CopyOnWriteArrayList<>();
        final boolean[] closed = {false};

        AutoCloseable cleanup = () -> {
            closed[0] = true;
            listeners.clear();
        };
        session.registerResource(cleanup);

        return PyHostHandle.builder("control.Controller")
                .prop("device_id", () -> new PyStr(device.id()))
                .prop("closed", () -> PyBool.of(closed[0]))
                .method("send", new PyBuiltinFunction("Controller.send", (args, kwargs) -> {
                    if (closed[0]) {
                        throw PyErrors.error("RuntimeError", "controller '" + device.id() + "' is closed");
                    }
                    String cmdName;
                    Map<String, PyObject> cmdArgs = new LinkedHashMap<>();
                    if (args.length >= 1 && args[0] instanceof PyHostHandle ch && "control.Command".equals(ch.handleTypeName())) {
                        cmdName = ch.getAttr("name").toString();
                        PyObject aObj = ch.getAttr("args");
                        if (aObj instanceof PyDict d) {
                            for (PyObject k : d.keys()) cmdArgs.put(k.toString(), d.get(k));
                        }
                    } else if (args.length >= 1 && args[0] instanceof PyStr s) {
                        cmdName = s.value();
                        if (args.length >= 2 && args[1] instanceof PyDict d) {
                            for (PyObject k : d.keys()) cmdArgs.put(k.toString(), d.get(k));
                        }
                    } else {
                        throw PyErrors.error("TypeError", "send() expects command name or control.Command");
                    }
                    cmdArgs.putAll(kwargs);
                    session.requireControlCommand(device.id(), cmdName);
                    PyObject res = device.execute(cmdName, cmdArgs);

                    if (!listeners.isEmpty()) {
                        PyHostHandle ev = PyHostHandle.builder("control.Event")
                                .prop("type", () -> new PyStr("command"))
                                .prop("device_id", () -> new PyStr(device.id()))
                                .prop("command", () -> new PyStr(cmdName))
                                .prop("result", () -> res)
                                .build();
                        for (PyObject cb : List.copyOf(listeners)) {
                            session.invokeCallback(cb, ev);
                        }
                    }
                    return res;
                }))
                .method("state", new PyBuiltinFunction("Controller.state", a -> {
                    session.require(HostCapability.CONTROL, "read_state", device.id());
                    if (!session.capabilities().canControlDevice(device.id())) {
                        throw PyErrors.error("SecurityError", "control access to device '" + device.id() + "' is restricted");
                    }
                    Map<String, PyObject> snap = device.snapshotState();
                    PyDict dict = new PyDict();
                    for (var e : snap.entrySet()) dict.put(new PyStr(e.getKey()), e.getValue());
                    return PyHostHandle.builder("control.State")
                            .prop("device_id", () -> new PyStr(device.id()))
                            .method("get", new PyBuiltinFunction("State.get", ga -> {
                                if (ga.length < 1 || ga.length > 2) throw PyErrors.error("TypeError", "State.get() takes 1 or 2 arguments");
                                PyObject v = snap.get(ga[0].toString());
                                if (v != null) return v;
                                return ga.length == 2 ? ga[1] : PyNull.INSTANCE;
                            }))
                            .method("to_dict", new PyBuiltinFunction("State.to_dict", ga -> dict))
                            .build();
                }))
                .method("on_event", new PyBuiltinFunction("Controller.on_event", a -> {
                    if (a.length != 1) throw PyErrors.error("TypeError", "on_event() takes 1 callback");
                    session.require(HostCapability.CONTROL, "on_event", device.id());
                    session.require(HostCapability.EVENTS, "on_event", device.id());
                    if (listeners.size() >= session.capabilities().maxListenersPerNode()) {
                        throw PyErrors.error("RuntimeError", "maximum listeners exceeded");
                    }
                    listeners.add(a[0]);
                    return PyNull.INSTANCE;
                }))
                .method("close", new PyBuiltinFunction("Controller.close", a -> {
                    closed[0] = true;
                    listeners.clear();
                    session.unregisterResource(cleanup);
                    return PyNull.INSTANCE;
                }))
                .build();
    }
}
