package org.example.jython3.stdlib.modules;

import org.example.jython3.runtime.BigInt;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyFloat;
import org.example.jython3.runtime.PyModule;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.runtime.PyObject;

import java.math.BigInteger;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;

import static org.example.jython3.stdlib.modules.StdlibSupport.fn;
import static org.example.jython3.stdlib.modules.StdlibSupport.toDouble;

/** Standard {@code time} module. */
public final class TimeModule {
    private TimeModule() {}

    public static PyModule create() {
        Map<String, PyObject> attrs = new HashMap<>();
        attrs.put("time", fn("time", args -> new PyFloat(System.currentTimeMillis() / 1000.0)));
        attrs.put("time_ns", fn("time_ns", args -> {
            Instant now = Instant.now();
            BigInteger nanos = BigInteger.valueOf(now.getEpochSecond())
                    .multiply(BigInteger.valueOf(1_000_000_000L))
                    .add(BigInteger.valueOf(now.getNano()));
            return new BigInt(nanos);
        }));
        attrs.put("sleep", fn("sleep", args -> {
            if (args.length == 0) throw PyErrors.error("TypeError", "sleep() takes at least 1 argument");
            double secs = toDouble(args[0]);
            if (Double.isNaN(secs)) {
                throw PyErrors.error("ValueError", "Invalid value NaN (not a number)");
            }
            if (Double.isInfinite(secs)) {
                throw PyErrors.error("OverflowError", "cannot convert float infinity to integer");
            }
            if (secs < 0.0) {
                throw PyErrors.error("ValueError", "sleep length must be non-negative");
            }
            double millisDouble = secs * 1000.0;
            if (millisDouble > Long.MAX_VALUE - 1000) {
                throw PyErrors.error("OverflowError", "sleep length is too large");
            }
            try {
                long millis = (long) millisDouble;
                int nanos = (int) ((millisDouble - millis) * 1_000_000);
                Thread.sleep(millis, Math.max(0, Math.min(999999, nanos)));
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw PyErrors.error("KeyboardInterrupt", "sleep interrupted");
            }
            return new PyNull();
        }));
        attrs.put("perf_counter", fn("perf_counter", args -> new PyFloat(System.nanoTime() / 1_000_000_000.0)));
        attrs.put("monotonic", fn("monotonic", args -> new PyFloat(System.nanoTime() / 1_000_000_000.0)));
        return new PyModule("time", attrs);
    }
}
