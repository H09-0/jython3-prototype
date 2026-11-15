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
import org.example.jython3.security.HostAccessPolicy;


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
        attrs.put("maxsize",  new org.example.jython3.runtime.BigInt(java.math.BigInteger.valueOf(Long.MAX_VALUE)));
        attrs.put("exit",     fn("exit", args -> {
            int code = (args.length > 0 && args[0] instanceof PyInt i) ? i.value() : 0;
            throw new SystemExit(code);
        }));

        return new PyModule("sys", attrs);
    }

    // ─── os ───────────────────────────────────────────────────────────────

    public static PyModule os() {
        return os(HostAccessPolicy.PERMISSIVE);
    }

    public static PyModule os(HostAccessPolicy hostPolicy) {
        Map<String, PyObject> attrs = new HashMap<>();

        attrs.put("getcwd",   fn("getcwd",  args -> new PyStr(System.getProperty("user.dir"))));
        attrs.put("getpid",   fn("getpid",  args -> new PyInt((int) ProcessHandle.current().pid())));
        attrs.put("sep",      new PyStr(java.io.File.separator));
        attrs.put("pathsep",  new PyStr(java.io.File.pathSeparator));
        attrs.put("name",     new PyStr("java"));
        // Populate initial environ dictionary if permitted by host policy
        var envMap = new java.util.LinkedHashMap<PyObject, PyObject>();
        if (hostPolicy.canReadEnvironment()) {
            for (var entry : System.getenv().entrySet()) {
                envMap.put(new PyStr(entry.getKey()), new PyStr(entry.getValue()));
            }
        }
        var envDict = new PyDict(envMap);
        attrs.put("environ",  envDict);
        attrs.put("listdir",  fn("listdir", args -> {
            String pathStr = args.length > 0 ? args[0].toString() : ".";
            java.nio.file.Path p = java.nio.file.Path.of(pathStr);
            if (!hostPolicy.canListDirectory(p)) {
                throw PyErrors.error("SecurityError", "access to list directory '" + pathStr + "' is restricted");
            }
            java.io.File dir = p.toFile();
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
        return io(HostAccessPolicy.PERMISSIVE);
    }

    public static PyModule io(HostAccessPolicy hostPolicy) {
        Map<String, PyObject> attrs = new HashMap<>();
        attrs.put("open", fn("open", args -> {
            if (args.length == 0) throw PyErrors.error("TypeError", "open() missing required argument");
            String filename = args[0].toString();
            String mode = args.length > 1 ? args[1].toString() : "r";
            java.nio.file.Path p = java.nio.file.Path.of(filename).normalize();

            boolean isWrite = mode.contains("w") || mode.contains("a") || mode.contains("+");
            if (isWrite && !hostPolicy.canWriteFile(p)) {
                throw PyErrors.error("SecurityError", "access to write file '" + filename + "' is restricted");
            }
            if (!isWrite && !hostPolicy.canReadFile(p)) {
                throw PyErrors.error("SecurityError", "access to read file '" + filename + "' is restricted");
            }

            try {
                if (isWrite) {
                    java.nio.file.Files.createDirectories(p.getParent() == null ? java.nio.file.Path.of(".") : p.getParent());
                    if (mode.contains("w") && !mode.contains("a")) {
                        java.nio.file.Files.writeString(p, "");
                    }
                    java.util.Map<String, PyObject> fattrs = new java.util.HashMap<>();
                    fattrs.put("name", new PyStr(p.toString()));
                    fattrs.put("mode", new PyStr(mode));
                    fattrs.put("read", fn("read", a -> {
                        if (!hostPolicy.canReadFile(p)) throw PyErrors.error("SecurityError", "access to read file '" + filename + "' is restricted");
                        try { return new PyStr(java.nio.file.Files.readString(p)); }
                        catch (Exception ex) { throw PyErrors.error("IOError", ex.getMessage()); }
                    }));
                    fattrs.put("write", fn("write", a -> {
                        if (!hostPolicy.canWriteFile(p)) throw PyErrors.error("SecurityError", "access to write file '" + filename + "' is restricted");
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
        attrs.put("JSONDecodeError", org.example.jython3.runtime.ExceptionTypeRegistry.current().type("JSONDecodeError"));
        attrs.put("dumps", fn("dumps", args -> {
            if (args.length == 0) throw PyErrors.error("TypeError", "dumps() missing 1 required positional argument: 'obj'");
            return new PyStr(simpleJsonDumps(args[0]));
        }));
        attrs.put("loads", fn("loads", args -> {
            if (args.length == 0) throw PyErrors.error("TypeError", "loads() missing 1 required positional argument: 's'");
            if (!(args[0] instanceof PyStr str)) {
                throw PyErrors.error("TypeError", "the JSON object must be str, not '" + org.example.jython3.runtime.PyOps.typeName(args[0]) + "'");
            }
            return new JsonParser(str.value()).parse();
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

    // ─── time ─────────────────────────────────────────────────────────────

    public static PyModule time() {
        Map<String, PyObject> attrs = new HashMap<>();
        attrs.put("time", fn("time", args -> new PyFloat(System.currentTimeMillis() / 1000.0)));
        attrs.put("time_ns", fn("time_ns", args -> {
            java.time.Instant now = java.time.Instant.now();
            java.math.BigInteger nanos = java.math.BigInteger.valueOf(now.getEpochSecond())
                    .multiply(java.math.BigInteger.valueOf(1_000_000_000L))
                    .add(java.math.BigInteger.valueOf(now.getNano()));
            return new org.example.jython3.runtime.BigInt(nanos);
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

    // ─── random ───────────────────────────────────────────────────────────

    public static PyModule random() {
        // Each module instance gets its own isolated RNG instance
        java.util.Random rng = new java.util.Random();
        Map<String, PyObject> attrs = new HashMap<>();
        attrs.put("random", fn("random", args -> new PyFloat(rng.nextDouble())));
        attrs.put("randint", fn("randint", args -> {
            if (args.length != 2) throw PyErrors.error("TypeError", "randint() takes exactly 2 arguments (" + args.length + " given)");
            if (!(args[0] instanceof PyInt || args[0] instanceof org.example.jython3.runtime.BigInt) ||
                !(args[1] instanceof PyInt || args[1] instanceof org.example.jython3.runtime.BigInt)) {
                throw PyErrors.error("TypeError", "randint() arguments must be integers");
            }
            java.math.BigInteger a = (args[0] instanceof PyInt pi)
                    ? java.math.BigInteger.valueOf(pi.value())
                    : ((org.example.jython3.runtime.BigInt) args[0]).value();
            java.math.BigInteger b = (args[1] instanceof PyInt pi)
                    ? java.math.BigInteger.valueOf(pi.value())
                    : ((org.example.jython3.runtime.BigInt) args[1]).value();

            if (a.compareTo(b) > 0) {
                throw PyErrors.error("ValueError", "empty range for randint(" + a + ", " + b + ")");
            }

            // range = b - a + 1
            java.math.BigInteger range = b.subtract(a).add(java.math.BigInteger.ONE);

            // Rejection sampling for uniform distribution without bias or overflow
            java.math.BigInteger randOffset;
            int bits = range.bitLength();
            do {
                randOffset = new java.math.BigInteger(bits, rng);
            } while (randOffset.compareTo(range) >= 0);

            java.math.BigInteger res = a.add(randOffset);
            if (res.compareTo(java.math.BigInteger.valueOf(Integer.MAX_VALUE)) <= 0 &&
                res.compareTo(java.math.BigInteger.valueOf(Integer.MIN_VALUE)) >= 0) {
                return new PyInt(res.intValue());
            }
            return new org.example.jython3.runtime.BigInt(res);
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
            } else if (args.length > 0 && args[0] instanceof org.example.jython3.runtime.BigInt bi) {
                // Incorporate full big integer entropy by hashing its byte array
                rng.setSeed(java.util.Arrays.hashCode(bi.value().toByteArray()));
            } else {
                rng.setSeed(System.currentTimeMillis());
            }
            return new PyNull();
        }));
        return new PyModule("random", attrs);
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

    /** Strict RFC 8259 recursive-descent JSON parser raising JSONDecodeError on malformed input. */
    private static final class JsonParser {
        private static final int MAX_DEPTH = 512;
        private final String src;
        private final int len;
        private int pos = 0;

        JsonParser(String src) {
            this.src = src;
            this.len = src.length();
        }

        PyObject parse() {
            skipWhitespace();
            if (pos >= len) {
                throw decodeError("Expecting value", pos);
            }
            PyObject result = parseValue(0);
            skipWhitespace();
            if (pos < len) {
                throw decodeError("Extra data", pos);
            }
            return result;
        }

        private PyObject parseValue(int depth) {
            if (depth > MAX_DEPTH) {
                throw decodeError("Maximum JSON nesting depth exceeded", pos);
            }
            skipWhitespace();
            if (pos >= len) {
                throw decodeError("Expecting value", pos);
            }
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> parseObject(depth + 1);
                case '[' -> parseArray(depth + 1);
                case '"' -> new PyStr(parseString());
                case 't' -> parseLiteral("true", PyBool.TRUE);
                case 'f' -> parseLiteral("false", PyBool.FALSE);
                case 'n' -> parseLiteral("null", new PyNull());
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) {
                        yield parseNumber();
                    }
                    throw decodeError("Expecting value", pos);
                }
            };
        }

        private PyObject parseObject(int depth) {
            pos++; // consume '{'
            skipWhitespace();
            var map = new java.util.LinkedHashMap<PyObject, PyObject>();
            if (pos < len && src.charAt(pos) == '}') {
                pos++;
                return new PyDict(map);
            }
            while (true) {
                skipWhitespace();
                if (pos >= len || src.charAt(pos) != '"') {
                    throw decodeError("Expecting property name enclosed in double quotes", pos);
                }
                String key = parseString();
                skipWhitespace();
                if (pos >= len || src.charAt(pos) != ':') {
                    throw decodeError("Expecting ':' delimiter", pos);
                }
                pos++; // consume ':'
                PyObject val = parseValue(depth);
                map.put(new PyStr(key), val);
                skipWhitespace();
                if (pos >= len) {
                    throw decodeError("Expecting ',' or '}' delimiter", pos);
                }
                char sep = src.charAt(pos);
                if (sep == '}') {
                    pos++;
                    return new PyDict(map);
                }
                if (sep != ',') {
                    throw decodeError("Expecting ',' delimiter", pos);
                }
                pos++; // consume ','
            }
        }

        private PyObject parseArray(int depth) {
            pos++; // consume '['
            skipWhitespace();
            var list = new java.util.ArrayList<PyObject>();
            if (pos < len && src.charAt(pos) == ']') {
                pos++;
                return new PyList(list);
            }
            while (true) {
                list.add(parseValue(depth));
                skipWhitespace();
                if (pos >= len) {
                    throw decodeError("Expecting ',' or ']' delimiter", pos);
                }
                char sep = src.charAt(pos);
                if (sep == ']') {
                    pos++;
                    return new PyList(list);
                }
                if (sep != ',') {
                    throw decodeError("Expecting ',' delimiter", pos);
                }
                pos++; // consume ','
            }
        }

        private String parseString() {
            pos++; // consume opening '"'
            StringBuilder sb = new StringBuilder();
            while (pos < len) {
                char c = src.charAt(pos++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c < 0x20) {
                    throw decodeError("Invalid control character in string", pos - 1);
                }
                if (c == '\\') {
                    if (pos >= len) {
                        throw decodeError("Unterminated string escape", pos);
                    }
                    char esc = src.charAt(pos++);
                    switch (esc) {
                        case '"' -> sb.append('"');
                        case '\\' -> sb.append('\\');
                        case '/' -> sb.append('/');
                        case 'b' -> sb.append('\b');
                        case 'f' -> sb.append('\f');
                        case 'n' -> sb.append('\n');
                        case 'r' -> sb.append('\r');
                        case 't' -> sb.append('\t');
                        case 'u' -> {
                            if (pos + 4 > len) {
                                throw decodeError("Invalid \\uXXXX escape", pos);
                            }
                            String hex = src.substring(pos, pos + 4);
                            try {
                                sb.append((char) Integer.parseInt(hex, 16));
                            } catch (NumberFormatException nfe) {
                                throw decodeError("Invalid \\uXXXX escape", pos);
                            }
                            pos += 4;
                        }
                        default -> throw decodeError("Invalid \\escape: \\" + esc, pos - 1);
                    }
                } else {
                    sb.append(c);
                }
            }
            throw decodeError("Unterminated string starting", pos);
        }

        private PyObject parseLiteral(String expected, PyObject value) {
            if (!src.startsWith(expected, pos)) {
                throw decodeError("Expecting value", pos);
            }
            pos += expected.length();
            return value;
        }

        private PyObject parseNumber() {
            int start = pos;
            if (src.charAt(pos) == '-') pos++;
            if (pos >= len) throw decodeError("Expecting number", start);
            if (src.charAt(pos) == '0') {
                pos++;
                if (pos < len && Character.isDigit(src.charAt(pos))) {
                    throw decodeError("Leading zeros are not permitted in JSON numbers", start);
                }
            } else if (src.charAt(pos) >= '1' && src.charAt(pos) <= '9') {
                while (pos < len && Character.isDigit(src.charAt(pos))) pos++;
            } else {
                throw decodeError("Expecting digit", start);
            }

            boolean isFloat = false;
            if (pos < len && src.charAt(pos) == '.') {
                isFloat = true;
                pos++;
                if (pos >= len || !Character.isDigit(src.charAt(pos))) {
                    throw decodeError("Expecting digit after decimal point", pos);
                }
                while (pos < len && Character.isDigit(src.charAt(pos))) pos++;
            }
            if (pos < len && (src.charAt(pos) == 'e' || src.charAt(pos) == 'E')) {
                isFloat = true;
                pos++;
                if (pos < len && (src.charAt(pos) == '+' || src.charAt(pos) == '-')) pos++;
                if (pos >= len || !Character.isDigit(src.charAt(pos))) {
                    throw decodeError("Expecting exponent digit", pos);
                }
                while (pos < len && Character.isDigit(src.charAt(pos))) pos++;
            }

            String numStr = src.substring(start, pos);
            if (isFloat) {
                return new PyFloat(Double.parseDouble(numStr));
            }
            try {
                return PyInt.of(Integer.parseInt(numStr));
            } catch (NumberFormatException nfe) {
                return new org.example.jython3.runtime.BigInt(new java.math.BigInteger(numStr));
            }
        }

        private void skipWhitespace() {
            while (pos < len) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        private org.example.jython3.runtime.InterpreterException decodeError(String msg, int charPos) {
            return PyErrors.error("JSONDecodeError", msg + ": char " + charPos);
        }
    }
}
