package org.example.jython3.stdlib.modules;

import org.example.jython3.runtime.BigInt;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyList;
import org.example.jython3.runtime.PyModule;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.runtime.SystemExit;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import static org.example.jython3.stdlib.modules.StdlibSupport.fn;

/** Standard {@code sys} module. */
public final class SysModule {
    private SysModule() {}

    public static PyModule create() {
        Map<String, PyObject> attrs = new HashMap<>();
        attrs.put("argv",     new PyList(new ArrayList<>()));
        attrs.put("version",  new PyStr("3.0.0 (Jython3-prototype)"));
        attrs.put("platform", new PyStr("java"));
        attrs.put("maxsize",  new BigInt(BigInteger.valueOf(Long.MAX_VALUE)));
        attrs.put("exit",     fn("exit", args -> {
            int code = (args.length > 0 && args[0] instanceof PyInt i) ? i.value() : 0;
            throw new SystemExit(code);
        }));
        return new PyModule("sys", attrs);
    }
}
