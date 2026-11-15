package org.example.jython3.stdlib.modules;

import org.example.jython3.runtime.BigInt;
import org.example.jython3.runtime.ExceptionTypeRegistry;
import org.example.jython3.runtime.InterpreterException;
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
import org.example.jython3.runtime.PyOps;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.security.ExecutionContext;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.example.jython3.stdlib.modules.StdlibSupport.fn;

/** Standard {@code json} module implementing strict RFC 8259 parsing and serialization. */
public final class JsonModule {
    private static final int MAX_DEPTH = 512;
    private JsonModule() {}

    public static PyModule create() {
        Map<String, PyObject> attrs = new HashMap<>();
        attrs.put("JSONDecodeError", ExceptionTypeRegistry.current().type("JSONDecodeError"));
        attrs.put("dumps", new PyBuiltinFunction("dumps", (args, kwargs) -> {
            if (args.length == 0) throw PyErrors.error("TypeError", "dumps() missing 1 required positional argument: 'obj'");
            int indent = -1;
            PyObject indentObj = kwargs.get("indent");
            if (indentObj != null && !(indentObj instanceof PyNull)) {
                indent = Math.max(0, Math.min(32, PyOps.toInt(indentObj)));
            }
            boolean sortKeys = kwargs.containsKey("sort_keys") && PyOps.isTruthy(kwargs.get("sort_keys"));
            return new PyStr(simpleJsonDumps(args[0],
                    java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<>()),
                    0, indent, sortKeys));
        }));
        attrs.put("loads", fn("loads", args -> {
            if (args.length == 0) throw PyErrors.error("TypeError", "loads() missing 1 required positional argument: 's'");
            if (!(args[0] instanceof PyStr str)) {
                throw PyErrors.error("TypeError", "the JSON object must be str, not '" + PyOps.typeName(args[0]) + "'");
            }
            return new JsonParser(str.value()).parse();
        }));
        return new PyModule("json", attrs);
    }

    private static String escapeJsonString(String raw) {
        var sb = new StringBuilder(raw.length() + 2);
        sb.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\b' -> sb.append("\\b");
                case '\f' -> sb.append("\\f");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        return sb.append('"').toString();
    }

    private static String simpleJsonDumps(PyObject o, java.util.Set<PyObject> active,
                                          int depth, int indent, boolean sortKeys) {
        if (depth > MAX_DEPTH) {
            throw PyErrors.error("RecursionError", "maximum JSON serialization depth exceeded");
        }
        if (o instanceof PyNull) return "null";
        if (o instanceof PyBool b) return b.value() ? "true" : "false";
        if (o instanceof PyInt i) return String.valueOf(i.value());
        if (o instanceof BigInt bi) return bi.value().toString();
        if (o instanceof PyFloat f) {
            if (Double.isNaN(f.value()) || Double.isInfinite(f.value())) {
                throw PyErrors.error("ValueError", "Out of range float values are not JSON compliant");
            }
            return String.valueOf(f.value());
        }
        if (o instanceof PyStr s) return escapeJsonString(s.value());
        if (o instanceof PyList list) {
            if (!active.add(list)) {
                throw PyErrors.error("ValueError", "Circular reference detected");
            }
            try {
                if (list.items().isEmpty()) return "[]";
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < list.items().size(); i++) {
                    if (i > 0) sb.append(",");
                    if (indent >= 0) sb.append('\n').append(" ".repeat((depth + 1) * indent));
                    sb.append(simpleJsonDumps(list.items().get(i), active, depth + 1, indent, sortKeys));
                }
                if (indent >= 0) sb.append('\n').append(" ".repeat(depth * indent));
                sb.append("]");
                return sb.toString();
            } finally {
                active.remove(list);
            }
        }
        if (o instanceof org.example.jython3.runtime.PyTuple tuple) {
            if (!active.add(tuple)) {
                throw PyErrors.error("ValueError", "Circular reference detected");
            }
            try {
                if (tuple.items().isEmpty()) return "[]";
                StringBuilder sb = new StringBuilder("[");
                for (int i = 0; i < tuple.items().size(); i++) {
                    if (i > 0) sb.append(",");
                    if (indent >= 0) sb.append('\n').append(" ".repeat((depth + 1) * indent));
                    sb.append(simpleJsonDumps(tuple.items().get(i), active, depth + 1, indent, sortKeys));
                }
                if (indent >= 0) sb.append('\n').append(" ".repeat(depth * indent));
                sb.append("]");
                return sb.toString();
            } finally {
                active.remove(tuple);
            }
        }
        if (o instanceof PyDict dict) {
            if (!active.add(dict)) {
                throw PyErrors.error("ValueError", "Circular reference detected");
            }
            try {
                if (dict.map().isEmpty()) return "{}";
                List<Map.Entry<PyObject, PyObject>> entries = new ArrayList<>(dict.map().entrySet());
                if (sortKeys) {
                    entries.sort(Comparator.comparing(e -> e.getKey().toString()));
                }
                StringBuilder sb = new StringBuilder("{");
                int i = 0;
                for (var e : entries) {
                    if (i++ > 0) sb.append(",");
                    if (indent >= 0) sb.append('\n').append(" ".repeat((depth + 1) * indent));
                    String keyJson = (e.getKey() instanceof PyStr ks)
                            ? escapeJsonString(ks.value())
                            : escapeJsonString(e.getKey().toString());
                    sb.append(keyJson).append(indent >= 0 ? ": " : ":")
                            .append(simpleJsonDumps(e.getValue(), active, depth + 1, indent, sortKeys));
                }
                if (indent >= 0) sb.append('\n').append(" ".repeat(depth * indent));
                sb.append("}");
                return sb.toString();
            } finally {
                active.remove(dict);
            }
        }
        throw PyErrors.error("TypeError", "Object of type " + PyOps.typeName(o) + " is not JSON serializable");
    }

    private static final class JsonParser {
        private static final int MAX_DEPTH = JsonModule.MAX_DEPTH;
        private final String src;
        private final int len;
        private final ExecutionContext ctx = ExecutionContext.current();
        private int pos = 0;

        JsonParser(String src) {
            this.src = src;
            this.len = src.length();
        }

        PyObject parse() {
            skipWhitespace();
            if (pos >= len) throw decodeError("Expecting value", pos);
            PyObject result = parseValue(0);
            skipWhitespace();
            if (pos < len) throw decodeError("Extra data", pos);
            return result;
        }

        private PyObject parseValue(int depth) {
            if (ctx != null && (pos & 0xFF) == 0) ctx.stepInstruction();
            if (depth > MAX_DEPTH) throw decodeError("Maximum JSON nesting depth exceeded", pos);
            skipWhitespace();
            if (pos >= len) throw decodeError("Expecting value", pos);
            char c = src.charAt(pos);
            return switch (c) {
                case '{' -> parseObject(depth + 1);
                case '[' -> parseArray(depth + 1);
                case '"' -> new PyStr(parseString());
                case 't' -> parseLiteral("true", PyBool.TRUE);
                case 'f' -> parseLiteral("false", PyBool.FALSE);
                case 'n' -> parseLiteral("null", new PyNull());
                default -> {
                    if (c == '-' || (c >= '0' && c <= '9')) yield parseNumber();
                    throw decodeError("Expecting value", pos);
                }
            };
        }

        private PyObject parseObject(int depth) {
            pos++;
            skipWhitespace();
            var map = new LinkedHashMap<PyObject, PyObject>();
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
                pos++;
                PyObject val = parseValue(depth);
                map.put(new PyStr(key), val);
                skipWhitespace();
                if (pos >= len) throw decodeError("Expecting ',' or '}' delimiter", pos);
                char sep = src.charAt(pos);
                if (sep == '}') { pos++; return new PyDict(map); }
                if (sep != ',') throw decodeError("Expecting ',' delimiter", pos);
                pos++;
            }
        }

        private PyObject parseArray(int depth) {
            pos++;
            skipWhitespace();
            var list = new ArrayList<PyObject>();
            if (pos < len && src.charAt(pos) == ']') {
                pos++;
                return new PyList(list);
            }
            while (true) {
                list.add(parseValue(depth));
                skipWhitespace();
                if (pos >= len) throw decodeError("Expecting ',' or ']' delimiter", pos);
                char sep = src.charAt(pos);
                if (sep == ']') { pos++; return new PyList(list); }
                if (sep != ',') throw decodeError("Expecting ',' delimiter", pos);
                pos++;
            }
        }

        private String parseString() {
            pos++;
            StringBuilder sb = new StringBuilder();
            while (pos < len) {
                char c = src.charAt(pos++);
                if (c == '"') return sb.toString();
                if (c < 0x20) throw decodeError("Invalid control character in string", pos - 1);
                if (c == '\\') {
                    if (pos >= len) throw decodeError("Unterminated string escape", pos);
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
                            if (pos + 4 > len) throw decodeError("Invalid \\uXXXX escape", pos);
                            String hex = src.substring(pos, pos + 4);
                            try { sb.append((char) Integer.parseInt(hex, 16)); }
                            catch (NumberFormatException nfe) { throw decodeError("Invalid \\uXXXX escape", pos); }
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
            if (!src.startsWith(expected, pos)) throw decodeError("Expecting value", pos);
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
            if (isFloat) return new PyFloat(Double.parseDouble(numStr));
            try { return PyInt.of(Integer.parseInt(numStr)); }
            catch (NumberFormatException nfe) { return new BigInt(new BigInteger(numStr)); }
        }

        private void skipWhitespace() {
            while (pos < len) {
                char c = src.charAt(pos);
                if (c == ' ' || c == '\t' || c == '\n' || c == '\r') pos++;
                else break;
            }
        }

        private InterpreterException decodeError(String msg, int charPos) {
            return PyErrors.error("JSONDecodeError", msg + ": char " + charPos);
        }
    }
}
