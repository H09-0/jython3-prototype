package org.example.jython3.stdlib.modules;

import org.example.jython3.runtime.PyFloat;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyModule;
import org.example.jython3.runtime.PyObject;

import java.util.HashMap;
import java.util.Map;

import static org.example.jython3.stdlib.modules.StdlibSupport.fn;
import static org.example.jython3.stdlib.modules.StdlibSupport.toDouble;

/** Standard {@code math} module. */
public final class MathModule {
    private MathModule() {}

    public static PyModule create() {
        Map<String, PyObject> attrs = new HashMap<>();

        attrs.put("sqrt",  fn("sqrt",  args -> new PyFloat(Math.sqrt(toDouble(args[0])))));
        attrs.put("sin",   fn("sin",   args -> new PyFloat(Math.sin(toDouble(args[0])))));
        attrs.put("cos",   fn("cos",   args -> new PyFloat(Math.cos(toDouble(args[0])))));
        attrs.put("tan",   fn("tan",   args -> new PyFloat(Math.tan(toDouble(args[0])))));
        attrs.put("log",   fn("log",   args -> new PyFloat(Math.log(toDouble(args[0])))));
        attrs.put("floor", fn("floor", args -> PyInt.of((int) Math.floor(toDouble(args[0])))));
        attrs.put("ceil",  fn("ceil",  args -> PyInt.of((int) Math.ceil(toDouble(args[0])))));
        attrs.put("abs",   fn("abs",   args -> {
            var a = args[0];
            if (a instanceof PyInt i) return PyInt.of(Math.abs(i.value()));
            return new PyFloat(Math.abs(toDouble(a)));
        }));
        attrs.put("pow",   fn("pow",   args -> new PyFloat(Math.pow(toDouble(args[0]), toDouble(args[1])))));

        attrs.put("pi",  new PyFloat(Math.PI));
        attrs.put("e",   new PyFloat(Math.E));
        attrs.put("inf", new PyFloat(Double.POSITIVE_INFINITY));

        return new PyModule("math", attrs);
    }
}
