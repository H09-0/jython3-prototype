/*
 * BuiltinModules.java – Phase 7: Java-backed standard library modules.
 *
 * Each factory method returns a PyModule whose attribute map contains
 * PyBuiltinFunction entries (and constant PyObject values like math.pi).
 * These modules are resolved by importModule() before
 * falling back to the file-based modules/ directory.
 *
 * Supported modules:
 *   math  – sqrt, sin, cos, floor, ceil, abs, pi, e
 *   sys   – argv, version, platform
 *   os    – getcwd(), sep, name
 */
package org.example.jython3.stdlib;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Map;

import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyBuiltinFunction;
import org.example.jython3.runtime.PyDict;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyFloat;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyList;
import org.example.jython3.runtime.PyModule;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.runtime.SystemExit;


public class BuiltinModules {

    // ─── math ─────────────────────────────────────────────────────────────

    public static PyModule math() {
        Map<String, PyObject> attrs = new HashMap<>();

        // Functions
        attrs.put("sqrt",  fn("sqrt",  args -> new PyFloat(Math.sqrt(toDouble(args[0])))));
        attrs.put("sin",   fn("sin",   args -> new PyFloat(Math.sin(toDouble(args[0])))));
        attrs.put("cos",   fn("cos",   args -> new PyFloat(Math.cos(toDouble(args[0])))));
        attrs.put("tan",   fn("tan",   args -> new PyFloat(Math.tan(toDouble(args[0])))));
        attrs.put("log",   fn("log",   args -> new PyFloat(Math.log(toDouble(args[0])))));
        attrs.put("floor", fn("floor", args -> new PyInt((int) Math.floor(toDouble(args[0])))));
        attrs.put("ceil",  fn("ceil",  args -> new PyInt((int) Math.ceil(toDouble(args[0])))));
        attrs.put("abs",   fn("abs",   args -> {
            var a = args[0];
            if (a instanceof PyInt i) return new PyInt(Math.abs(i.value()));
            return new PyFloat(Math.abs(toDouble(a)));
        }));
        attrs.put("pow",   fn("pow",   args -> new PyFloat(Math.pow(toDouble(args[0]), toDouble(args[1])))));

        // Constants
        attrs.put("pi", new PyFloat(Math.PI));
        attrs.put("e",  new PyFloat(Math.E));
        attrs.put("inf", new PyFloat(Double.POSITIVE_INFINITY));

        return new PyModule("math", attrs);
    }

    // ─── sys ──────────────────────────────────────────────────────────────

    public static PyModule sys() {
        Map<String, PyObject> attrs = new HashMap<>();

        // argv is a mutable list (empty by default – no CLI args in embedded mode)
        attrs.put("argv",     new PyList(new ArrayList<>()));
        attrs.put("version",  new PyStr("3.0.0 (Jython3-prototype)"));
        attrs.put("platform", new PyStr("java"));
        attrs.put("maxsize",  new PyInt(Integer.MAX_VALUE));
        attrs.put("exit",     fn("exit", args -> {
            int code = (args.length > 0 && args[0] instanceof PyInt i) ? i.value() : 0;
            throw new SystemExit(code);
        }));

        return new PyModule("sys", attrs);
    }

    // ─── os ───────────────────────────────────────────────────────────────

    public static PyModule os() {
        Map<String, PyObject> attrs = new HashMap<>();

        attrs.put("getcwd",   fn("getcwd",  args -> new PyStr(System.getProperty("user.dir"))));
        attrs.put("getpid",   fn("getpid",  args -> new PyInt((int) ProcessHandle.current().pid())));
        attrs.put("sep",      new PyStr(java.io.File.separator));
        attrs.put("pathsep",  new PyStr(java.io.File.pathSeparator));
        attrs.put("name",     new PyStr("java"));
        attrs.put("environ",  fn("environ", args -> new PyDict(new java.util.LinkedHashMap<>())));
        attrs.put("listdir",  fn("listdir", args -> {
            String path = args.length > 0 ? args[0].toString() : ".";
            java.io.File dir = new java.io.File(path);
            java.io.File[] files = dir.listFiles();
            var list = new java.util.ArrayList<PyObject>();
            if (files != null) {
                for (java.io.File f : files) list.add(new PyStr(f.getName()));
            }
            return new PyList(list);
        }));

        return new PyModule("os", attrs);
    }

    // ─── io ───────────────────────────────────────────────────────────────

    public static PyModule io() {
        Map<String, PyObject> attrs = new HashMap<>();
        attrs.put("open", fn("open", args -> {
            if (args.length == 0) throw PyErrors.error("TypeError", "open() missing required argument");
            String filename = args[0].toString();
            String mode = args.length > 1 ? args[1].toString() : "r";
            try {
                java.nio.file.Path p = java.nio.file.Path.of(filename).normalize();
                if (mode.contains("w") || mode.contains("a") || mode.contains("+")) {
                    java.nio.file.Files.createDirectories(p.getParent() == null ? java.nio.file.Path.of(".") : p.getParent());
                    if (mode.contains("w") && !mode.contains("a")) {
                        java.nio.file.Files.writeString(p, "");
                    }
                    java.util.Map<String, PyObject> fattrs = new java.util.HashMap<>();
                    fattrs.put("name", new PyStr(p.toString()));
                    fattrs.put("mode", new PyStr(mode));
                    fattrs.put("read", fn("read", a -> {
                        try { return new PyStr(java.nio.file.Files.readString(p)); }
                        catch (Exception ex) { throw PyErrors.error("IOError", ex.getMessage()); }
                    }));
                    fattrs.put("write", fn("write", a -> {
                        try {
                            String data = a.length > 0 ? a[0].toString() : "";
                            if (mode.contains("a")) java.nio.file.Files.writeString(p, data, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.APPEND);
                            else java.nio.file.Files.writeString(p, data, java.nio.file.StandardOpenOption.CREATE, java.nio.file.StandardOpenOption.TRUNCATE_EXISTING);
                            return new PyInt(data.length());
                        } catch (Exception ex) { throw PyErrors.error("IOError", ex.getMessage()); }
                    }));
                    fattrs.put("close", fn("close", a -> new PyNull()));
                    return new PyModule("file", fattrs);
                } else {
                    String content = java.nio.file.Files.readString(p);
                    java.util.Map<String, PyObject> fattrs = new java.util.HashMap<>();
                    fattrs.put("name", new PyStr(p.toString()));
                    fattrs.put("read", fn("read", a -> new PyStr(content)));
                    fattrs.put("close", fn("close", a -> new PyNull()));
                    return new PyModule("file", fattrs);
                }
            } catch (Exception e) {
                throw PyErrors.error("IOError", e.getMessage());
            }
        }));
        return new PyModule("io", attrs);
    }

    // ─── json ─────────────────────────────────────────────────────────────

    public static PyModule json() {
        Map<String, PyObject> attrs = new HashMap<>();
        attrs.put("dumps", fn("dumps", args -> {
            if (args.length == 0) return new PyStr("null");
            return new PyStr(simpleJsonDumps(args[0]));
        }));
        attrs.put("loads", fn("loads", args -> {
            if (args.length == 0) return new PyNull();
            String s = args[0].toString().trim();
            if (s.equals("null")) return new PyNull();
            if (s.equals("true")) return PyBool.TRUE;
            if (s.equals("false")) return PyBool.FALSE;
            if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
                return new PyStr(s.substring(1, s.length() - 1));
            }
            if (s.startsWith("[") && s.endsWith("]")) {
                var list = new java.util.ArrayList<PyObject>();
                if (s.equals("[]")) return new PyList(list);
                String inner = s.substring(1, s.length() - 1);
                if (!inner.trim().isEmpty()) {
                    for (String part : splitJsonArray(inner)) {
                        list.add(jsonParseValue(part.trim()));
                    }
                }
                return new PyList(list);
            }
            if (s.startsWith("{") && s.endsWith("}")) {
                var map = new java.util.LinkedHashMap<PyObject, PyObject>();
                if (s.equals("{}")) return new PyDict(map);
                String inner = s.substring(1, s.length() - 1);
                for (String pair : splitJsonObject(inner)) {
                    int colon = pair.indexOf(':');
                    if (colon >= 0) {
                        String k = pair.substring(0, colon).trim();
                        String v = pair.substring(colon + 1).trim();
                        PyObject key = jsonParseValue(k);
                        PyObject val = jsonParseValue(v);
                        map.put(key, val);
                    }
                }
                return new PyDict(map);
            }
            try {
                if (s.contains(".")) return new PyFloat(Double.parseDouble(s));
                return new PyInt(Integer.parseInt(s));
            } catch (Exception e) {
                return new PyStr(s);
            }
        }));
        return new PyModule("json", attrs);
    }

    // ─── re ──────────────────────────────────────────────────────────────

    public static PyModule re() {
        Map<String, PyObject> attrs = new HashMap<>();
        attrs.put("search", fn("search", args -> {
            if (args.length < 2) return new PyNull();
            try {
                java.util.regex.Pattern p = java.util.regex.Pattern.compile(args[0].toString());
                java.util.regex.Matcher m = p.matcher(args[1].toString());
                if (m.find()) return new PyStr(m.group());
                return new PyNull();
            } catch (Exception e) {
                return new PyNull();
            }
        }));
        attrs.put("findall", fn("findall", args -> {
            if (args.length < 2) return new PyList(new java.util.ArrayList<>());
            try {
                java.util.regex.Pattern p = java.util.regex.Pattern.compile(args[0].toString());
                java.util.regex.Matcher m = p.matcher(args[1].toString());
                var list = new java.util.ArrayList<PyObject>();
                while (m.find()) list.add(new PyStr(m.group()));
                return new PyList(list);
            } catch (Exception e) {
                return new PyList(new java.util.ArrayList<>());
            }
        }));
        return new PyModule("re", attrs);
    }

    // ─── Helpers ───────────────────────────────────────────────────────────

    /** Convenience: create a PyBuiltinFunction. */
    private static PyBuiltinFunction fn(
            String name,
            java.util.function.Function<PyObject[], PyObject> impl) {
        return new PyBuiltinFunction(name, impl);
    }

    /** Convert a PyObject to a Java double, throwing TypeError on failure. */
    static double toDouble(PyObject o) {
        return switch (o) {
            case PyInt i   -> i.value();
            case PyFloat f -> f.value();
            default -> throw PyErrors.error("TypeError",
                    "expected number, got " + org.example.jython3.runtime.PyOps.typeName(o));
        };
    }

    private static String simpleJsonDumps(PyObject o) {
        if (o instanceof PyNull) return "null";
        if (o instanceof PyBool b) return b.value() ? "true" : "false";
        if (o instanceof PyInt i) return String.valueOf(i.value());
        if (o instanceof PyFloat f) return String.valueOf(f.value());
        if (o instanceof PyStr s) return "\"" + s.value().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
        if (o instanceof PyList list) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < list.items().size(); i++) {
                if (i > 0) sb.append(",");
                sb.append(simpleJsonDumps(list.items().get(i)));
            }
            sb.append("]");
            return sb.toString();
        }
        if (o instanceof PyDict dict) {
            StringBuilder sb = new StringBuilder("{");
            int i = 0;
            for (var e : dict.map().entrySet()) {
                if (i++ > 0) sb.append(",");
                sb.append(simpleJsonDumps(e.getKey())).append(":").append(simpleJsonDumps(e.getValue()));
            }
            sb.append("}");
            return sb.toString();
        }
        return "\"" + o.toString().replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    private static java.util.List<String> splitJsonArray(String inner) {
        var list = new java.util.ArrayList<String>();
        StringBuilder cur = new StringBuilder();
        int depth = 0;
        boolean inStr = false;
        char strChar = 0;
        for (int i = 0; i < inner.length(); i++) {
            char c = inner.charAt(i);
            if ((c == '"' || c == '\'') && (i == 0 || inner.charAt(i - 1) != '\\')) {
                if (!inStr) { inStr = true; strChar = c; }
                else if (strChar == c) { inStr = false; }
                cur.append(c);
                continue;
            }
            if (inStr) { cur.append(c); continue; }
            if (c == '[' || c == '{') depth++;
            else if (c == ']' || c == '}') depth--;
            if (c == ',' && depth == 0) { list.add(cur.toString()); cur.setLength(0); continue; }
            cur.append(c);
        }
        if (cur.length() > 0) list.add(cur.toString());
        return list;
    }

    private static java.util.List<String> splitJsonObject(String inner) {
        return splitJsonArray(inner);
    }

    private static PyObject jsonParseValue(String s) {
        String t = s.trim();
        if (t.equals("null")) return new PyNull();
        if (t.equals("true")) return PyBool.TRUE;
        if (t.equals("false")) return PyBool.FALSE;
        if (t.startsWith("\"") && t.endsWith("\"") && t.length() >= 2) {
            return new PyStr(t.substring(1, t.length() - 1));
        }
        try {
            if (t.contains(".")) return new PyFloat(Double.parseDouble(t));
            return new PyInt(Integer.parseInt(t));
        } catch (Exception e) {
            return new PyStr(t);
        }
    }
}
