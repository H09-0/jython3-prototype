package org.example.jython3.host;

import org.example.jython3.Jython3Interpreter;
import org.example.jython3.control.ControlDevice;
import org.example.jython3.control.HostAttestation;
import org.example.jython3.runtime.BigInt;
import org.example.jython3.runtime.ExceptionTypeRegistry;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyDict;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyFloat;
import org.example.jython3.runtime.PyHostHandle;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyList;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.security.DenyAllPolicy;
import org.example.jython3.security.ExecutionLimits;
import org.example.jython3.security.HostAccessPolicy;
import org.example.jython3.ui.UiToolkit;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Clean embedder facade for host applications (e.g., Oculix IDE / Cinnamon)
 * that bind domain data, a {@link UiToolkit}, and {@link ControlDevice}s to
 * a capability-gated {@link Jython3Interpreter} without exposing raw JVM reflection.
 */
public final class HostAppBridge implements AutoCloseable {

    private final Jython3Interpreter interpreter;

    public HostAppBridge(CapabilitySet capabilities, UiToolkit uiToolkit, ExecutionLimits limits) {
        Objects.requireNonNull(capabilities, "capabilities");
        Objects.requireNonNull(uiToolkit, "uiToolkit");
        this.interpreter = new Jython3Interpreter(
                DenyAllPolicy.INSTANCE,
                HostAccessPolicy.DENY_ALL,
                limits != null ? limits : ExecutionLimits.SANDBOXED,
                ExceptionTypeRegistry.standard(),
                capabilities);
        this.interpreter.setUiToolkit(uiToolkit);
    }

    public Jython3Interpreter interpreter() { return interpreter; }

    public void registerDevice(ControlDevice device) {
        interpreter.controlRegistry().register(device);
    }

    public void registerAttestedDevice(ControlDevice device, HostAttestation attestation) {
        interpreter.controlRegistry().registerAttested(device, attestation);
    }

    /**
     * Binds plain host data (primitives, Strings, Lists, Maps) as native Python
     * values without wrapping raw Java objects.
     */
    public void bindModelData(String name, Object data) {
        interpreter.setGlobal(name, toSafePyData(data));
    }

    /**
     * Binds an explicitly whitelisted opaque {@link PyHostHandle} for host model
     * reads/updates without exposing Java reflection. Rejects handles that belong
     * to another {@link HostSession} or come from an invalidated epoch.
     */
    public void bindHandle(String name, PyHostHandle handle) {
        Objects.requireNonNull(handle, "handle");
        HostSession session = interpreter.hostSession();
        if (handle.session() != session) {
            session.auditLog().recordDenied(HostCapability.EVENTS, "bind_handle", name,
                    "handle belongs to different or missing HostSession");
            throw PyErrors.error("SecurityError",
                    "host capability handle '" + handle.handleTypeName() + "' cannot cross or bypass session boundaries");
        }
        session.requireValidEpoch(handle.sessionEpoch(), handle.handleTypeName());
        interpreter.setGlobal(name, handle);
    }

    public void runScript(String source, String name) {
        interpreter.eval(source, name);
    }

    public PyObject getGlobal(String name) {
        return interpreter.getGlobal(name);
    }

    public void cancelRunningScript() {
        interpreter.cancel();
    }

    /** Tears down all UI/timer/control resources and resets Python state for clean reuse. */
    public void restart() {
        interpreter.resetExecutionState();
    }

    @Override
    public void close() {
        interpreter.close();
    }

    /** Converts plain host DTO values into pure Python data objects (no Java reflection). */
    public static PyObject toSafePyData(Object value) {
        if (value == null) return PyNull.INSTANCE;
        if (value instanceof PyObject po) return po;
        if (value instanceof Boolean b) return PyBool.of(b);
        if (value instanceof Integer i) return PyInt.of(i);
        if (value instanceof Long l) {
            if (l >= Integer.MIN_VALUE && l <= Integer.MAX_VALUE) return PyInt.of(l.intValue());
            return new BigInt(BigInteger.valueOf(l));
        }
        if (value instanceof BigInteger bi) return new BigInt(bi);
        if (value instanceof Float f) return new PyFloat(f.doubleValue());
        if (value instanceof Double d) return new PyFloat(d);
        if (value instanceof String s) return new PyStr(s);
        if (value instanceof Collection<?> col) {
            var items = new ArrayList<PyObject>(col.size());
            for (Object item : col) items.add(toSafePyData(item));
            return new PyList(items);
        }
        if (value instanceof Map<?, ?> map) {
            var dict = new PyDict();
            for (var e : map.entrySet()) {
                dict.put(toSafePyData(e.getKey()), toSafePyData(e.getValue()));
            }
            return dict;
        }
        throw PyErrors.error("TypeError",
                "host model value of type '" + value.getClass().getName() + "' cannot be exposed directly; use PyHostHandle");
    }
}
