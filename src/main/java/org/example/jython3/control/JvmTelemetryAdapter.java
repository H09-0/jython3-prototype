package org.example.jython3.control;

import org.example.jython3.runtime.BigInt;
import org.example.jython3.runtime.PyDict;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyStr;

import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.lang.management.RuntimeMXBean;
import java.lang.management.ThreadMXBean;
import java.math.BigInteger;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * First real host-backed {@link ControlDevice}: exposes live JVM runtime and
 * memory telemetry behind {@code HostCapability.CONTROL} and command allowlists
 * without ever exposing MXBeans or JVM reflection to Python scripts.
 */
public final class JvmTelemetryAdapter implements ControlDevice {

    private final String id;
    private final MemoryMXBean memoryBean = ManagementFactory.getMemoryMXBean();
    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    private final RuntimeMXBean runtimeBean = ManagementFactory.getRuntimeMXBean();
    private volatile String tag = "default";

    public JvmTelemetryAdapter(String id) {
        this.id = Objects.requireNonNull(id, "id");
    }

    @Override
    public String id() { return id; }

    @Override
    public PyObject execute(String command, Map<String, PyObject> args) {
        return switch (command) {
            case "ping" -> new PyStr("pong:" + id);
            case "sample" -> {
                Map<PyObject, PyObject> out = new LinkedHashMap<>();
                for (var e : snapshotState().entrySet()) {
                    out.put(new PyStr(e.getKey()), e.getValue());
                }
                yield new PyDict(out);
            }
            case "set_tag" -> {
                PyObject v = args != null ? args.get("tag") : null;
                if (!(v instanceof PyStr s)) {
                    throw PyErrors.error("TypeError", "set_tag requires str 'tag'");
                }
                if (s.value().length() > 64) {
                    throw PyErrors.error("ValueError", "tag length exceeds 64 chars");
                }
                this.tag = s.value();
                yield s;
            }
            default -> throw PyErrors.error("ValueError",
                    "unknown command '" + command + "' on telemetry adapter '" + id + "'");
        };
    }

    @Override
    public Map<String, PyObject> snapshotState() {
        long used = memoryBean.getHeapMemoryUsage().getUsed();
        long max = memoryBean.getHeapMemoryUsage().getMax();
        Map<String, PyObject> state = new LinkedHashMap<>();
        state.put("tag", new PyStr(tag));
        state.put("heap_used_bytes", toPyNum(used));
        state.put("heap_max_bytes", toPyNum(max));
        state.put("thread_count", PyInt.of(threadBean.getThreadCount()));
        state.put("processors", PyInt.of(Runtime.getRuntime().availableProcessors()));
        state.put("uptime_ms", toPyNum(runtimeBean.getUptime()));
        return Collections.unmodifiableMap(state);
    }

    private static PyObject toPyNum(long v) {
        if (v >= Integer.MIN_VALUE && v <= Integer.MAX_VALUE) {
            return PyInt.of((int) v);
        }
        return new BigInt(BigInteger.valueOf(v));
    }
}
