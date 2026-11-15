package org.example.jython3.stdlib.modules;

import org.example.jython3.runtime.BigInt;
import org.example.jython3.runtime.PyBuiltinFunction;
import org.example.jython3.runtime.PyDict;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyList;
import org.example.jython3.runtime.PyModule;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.runtime.SystemExit;

import java.io.PrintStream;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

import static org.example.jython3.stdlib.modules.StdlibSupport.fn;

/** Standard {@code sys} module. */
public final class SysModule {
    private SysModule() {}

    public static PyModule create() {
        return create(new PyList(new ArrayList<>()), new PyDict());
    }

    public static PyModule create(PyList path, PyDict modules) {
        return create(path, modules, () -> System.out, () -> System.err);
    }

    public static PyModule create(PyList path, PyDict modules,
                                  Supplier<PrintStream> stdoutSupplier,
                                  Supplier<PrintStream> stderrSupplier) {
        Map<String, PyObject> attrs = new HashMap<>();
        attrs.put("argv",     new PyList(new ArrayList<>()));
        attrs.put("path",     path);
        attrs.put("modules",  modules);
        attrs.put("version",  new PyStr("3.0.0 (Jython3-prototype)"));
        attrs.put("platform", new PyStr("java"));
        attrs.put("maxsize",  new BigInt(BigInteger.valueOf(Long.MAX_VALUE)));
        attrs.put("stdout",   streamModule("sys.stdout", stdoutSupplier));
        attrs.put("stderr",   streamModule("sys.stderr", stderrSupplier));
        attrs.put("exit",     fn("exit", args -> {
            int code = (args.length > 0 && args[0] instanceof PyInt i) ? i.value() : 0;
            throw new SystemExit(code);
        }));
        return new PyModule("sys", attrs);
    }

    private static PyModule streamModule(String name, Supplier<PrintStream> streamSupplier) {
        Map<String, PyObject> attrs = new HashMap<>();
        attrs.put("write", new PyBuiltinFunction("write", args -> {
            if (args.length > 0 && args[0] != null && !(args[0] instanceof PyNull)) {
                String text = args[0] instanceof PyStr s ? s.value() : args[0].toString();
                streamSupplier.get().print(text);
            }
            return PyNull.INSTANCE;
        }));
        attrs.put("flush", new PyBuiltinFunction("flush", args -> {
            streamSupplier.get().flush();
            return PyNull.INSTANCE;
        }));
        return new PyModule(name, attrs);
    }
}
