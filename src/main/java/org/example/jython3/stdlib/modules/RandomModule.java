package org.example.jython3.stdlib.modules;

import org.example.jython3.runtime.BigInt;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyFloat;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyList;
import org.example.jython3.runtime.PyModule;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyStr;

import java.math.BigInteger;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Random;

import static org.example.jython3.stdlib.modules.StdlibSupport.fn;

/** Standard {@code random} module with per-module PRNG isolation and arbitrary-precision rejection sampling. */
public final class RandomModule {
    private RandomModule() {}

    public static PyModule create() {
        Random rng = new Random();
        Map<String, PyObject> attrs = new HashMap<>();
        attrs.put("random", fn("random", args -> new PyFloat(rng.nextDouble())));
        attrs.put("randint", fn("randint", args -> {
            if (args.length != 2) throw PyErrors.error("TypeError", "randint() takes exactly 2 arguments (" + args.length + " given)");
            if (!(args[0] instanceof PyInt || args[0] instanceof BigInt) ||
                !(args[1] instanceof PyInt || args[1] instanceof BigInt)) {
                throw PyErrors.error("TypeError", "randint() arguments must be integers");
            }
            BigInteger a = (args[0] instanceof PyInt pi)
                    ? BigInteger.valueOf(pi.value())
                    : ((BigInt) args[0]).value();
            BigInteger b = (args[1] instanceof PyInt pi)
                    ? BigInteger.valueOf(pi.value())
                    : ((BigInt) args[1]).value();

            if (a.compareTo(b) > 0) {
                throw PyErrors.error("ValueError", "empty range for randint(" + a + ", " + b + ")");
            }

            BigInteger range = b.subtract(a).add(BigInteger.ONE);
            BigInteger randOffset;
            int bits = range.bitLength();
            do {
                randOffset = new BigInteger(bits, rng);
            } while (randOffset.compareTo(range) >= 0);

            BigInteger res = a.add(randOffset);
            if (res.compareTo(BigInteger.valueOf(Integer.MAX_VALUE)) <= 0 &&
                res.compareTo(BigInteger.valueOf(Integer.MIN_VALUE)) >= 0) {
                return PyInt.of(res.intValue());
            }
            return new BigInt(res);
        }));
        attrs.put("choice", fn("choice", args -> {
            if (args.length == 0) throw PyErrors.error("TypeError", "choice() missing 1 required argument");
            if (args[0] instanceof PyList list) {
                if (list.items().isEmpty()) throw PyErrors.error("IndexError", "Cannot choose from an empty sequence");
                return list.items().get(rng.nextInt(list.items().size()));
            }
            if (args[0] instanceof PyStr s) {
                int count = s.codePointCount();
                if (count == 0) throw PyErrors.error("IndexError", "Cannot choose from an empty sequence");
                return new PyStr(s.getCodePointAt(rng.nextInt(count)));
            }
            throw PyErrors.error("TypeError", "object is not subscriptable or sequence");
        }));
        attrs.put("seed", fn("seed", args -> {
            if (args.length > 0 && args[0] instanceof PyInt i) {
                rng.setSeed(i.value());
            } else if (args.length > 0 && args[0] instanceof BigInt bi) {
                rng.setSeed(Arrays.hashCode(bi.value().toByteArray()));
            } else {
                rng.setSeed(System.currentTimeMillis());
            }
            return new PyNull();
        }));
        return new PyModule("random", attrs);
    }
}
