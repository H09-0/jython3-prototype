/*
 * BuiltinModules.java – Phase 7: Java-backed standard library modules.
 *
 * Each factory method returns a PyModule whose attribute map contains
 * PyBuiltinFunction entries (and constant PyObject values like math.pi).
 * These modules are resolved by Jython3Interpreter.importModule() before
 * falling back to the file-based modules/ directory.
 *
 * Supported modules:
 *   math  – sqrt, sin, cos, floor, ceil, abs, pi, e
 *   sys   – argv, version, platform
 *   os    – getcwd(), sep, name
 */
package org.example.jython3;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

public class BuiltinModules {

    // ─── math ─────────────────────────────────────────────────────────────

    public static Jython3Interpreter.PyModule math() {
        Map<String, Jython3Interpreter.PyObject> attrs = new HashMap<>();

        // Functions
        attrs.put("sqrt",  fn("sqrt",  args -> new Jython3Interpreter.PyFloat(Math.sqrt(toDouble(args[0])))));
        attrs.put("sin",   fn("sin",   args -> new Jython3Interpreter.PyFloat(Math.sin(toDouble(args[0])))));
        attrs.put("cos",   fn("cos",   args -> new Jython3Interpreter.PyFloat(Math.cos(toDouble(args[0])))));
        attrs.put("tan",   fn("tan",   args -> new Jython3Interpreter.PyFloat(Math.tan(toDouble(args[0])))));
        attrs.put("log",   fn("log",   args -> new Jython3Interpreter.PyFloat(Math.log(toDouble(args[0])))));
        attrs.put("floor", fn("floor", args -> new Jython3Interpreter.PyInt((int) Math.floor(toDouble(args[0])))));
        attrs.put("ceil",  fn("ceil",  args -> new Jython3Interpreter.PyInt((int) Math.ceil(toDouble(args[0])))));
        attrs.put("abs",   fn("abs",   args -> {
            var a = args[0];
            if (a instanceof Jython3Interpreter.PyInt i) return new Jython3Interpreter.PyInt(Math.abs(i.value()));
            return new Jython3Interpreter.PyFloat(Math.abs(toDouble(a)));
        }));
        attrs.put("pow",   fn("pow",   args -> new Jython3Interpreter.PyFloat(Math.pow(toDouble(args[0]), toDouble(args[1])))));

        // Constants
        attrs.put("pi", new Jython3Interpreter.PyFloat(Math.PI));
        attrs.put("e",  new Jython3Interpreter.PyFloat(Math.E));
        attrs.put("inf", new Jython3Interpreter.PyFloat(Double.POSITIVE_INFINITY));

        return new Jython3Interpreter.PyModule("math", attrs);
    }

    // ─── sys ──────────────────────────────────────────────────────────────

    public static Jython3Interpreter.PyModule sys() {
        Map<String, Jython3Interpreter.PyObject> attrs = new HashMap<>();

        // argv is a mutable list (empty by default – no CLI args in embedded mode)
        attrs.put("argv",     new Jython3Interpreter.PyList(new ArrayList<>()));
        attrs.put("version",  new Jython3Interpreter.PyStr("3.0.0 (Jython3-prototype)"));
        attrs.put("platform", new Jython3Interpreter.PyStr("java"));
        attrs.put("maxsize",  new Jython3Interpreter.PyInt(Integer.MAX_VALUE));
        attrs.put("exit",     fn("exit", args -> {
            int code = (args.length > 0 && args[0] instanceof Jython3Interpreter.PyInt i) ? i.value() : 0;
            System.exit(code);
            return new Jython3Interpreter.PyNull();
        }));

        return new Jython3Interpreter.PyModule("sys", attrs);
    }

    // ─── os ───────────────────────────────────────────────────────────────

    public static Jython3Interpreter.PyModule os() {
        Map<String, Jython3Interpreter.PyObject> attrs = new HashMap<>();

        attrs.put("getcwd",   fn("getcwd",  args -> new Jython3Interpreter.PyStr(System.getProperty("user.dir"))));
        attrs.put("getpid",   fn("getpid",  args -> new Jython3Interpreter.PyInt((int) ProcessHandle.current().pid())));
        attrs.put("sep",      new Jython3Interpreter.PyStr(java.io.File.separator));
        attrs.put("pathsep",  new Jython3Interpreter.PyStr(java.io.File.pathSeparator));
        attrs.put("name",     new Jython3Interpreter.PyStr("java"));

        return new Jython3Interpreter.PyModule("os", attrs);
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    /** Convenience: create a PyBuiltinFunction. */
    private static Jython3Interpreter.PyBuiltinFunction fn(
            String name,
            java.util.function.Function<Jython3Interpreter.PyObject[], Jython3Interpreter.PyObject> impl) {
        return new Jython3Interpreter.PyBuiltinFunction(name, impl);
    }

    /** Convert a PyObject to a Java double, throwing TypeError on failure. */
    static double toDouble(Jython3Interpreter.PyObject o) {
        return switch (o) {
            case Jython3Interpreter.PyInt i   -> i.value();
            case Jython3Interpreter.PyFloat f -> f.value();
            default -> throw new Jython3Interpreter.InterpreterException(
                    "TypeError: expected number, got " + o.getClass().getSimpleName());
        };
    }
}
