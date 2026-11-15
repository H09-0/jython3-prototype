package org.example.jython3.stdlib;

import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyBuiltinFunction;
import org.example.jython3.runtime.PyByteArray;
import org.example.jython3.runtime.PyBytes;
import org.example.jython3.runtime.PyDict;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyFrozenSet;
import org.example.jython3.runtime.PyGenerator;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyList;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyOps;
import org.example.jython3.runtime.PyRange;
import org.example.jython3.runtime.PySet;
import org.example.jython3.runtime.PySlice;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.runtime.PyTuple;
import org.example.jython3.security.JavaAccessPolicy;

import java.util.ArrayList;
import java.util.Collections;
import java.util.regex.Pattern;

import static org.example.jython3.runtime.PyOps.checkArgCount;
import static org.example.jython3.runtime.PyOps.pyEquals;
import static org.example.jython3.runtime.PyOps.toInt;

/**
 * Attribute method dispatch for built-in container and scalar Python types
 * ({@code str}, {@code list}, {@code dict}, {@code set}).
 */
public final class BuiltinMethodDispatcher {
    private BuiltinMethodDispatcher() {}

    public static PyObject strMethod(PyStr s, String method) {
        return switch (method) {
            case "upper" -> new PyBuiltinFunction("str.upper", args -> new PyStr(s.value().toUpperCase()));
            case "lower" -> new PyBuiltinFunction("str.lower", args -> new PyStr(s.value().toLowerCase()));
            case "strip" -> new PyBuiltinFunction("str.strip", args -> new PyStr(s.value().strip()));
            case "split" -> new PyBuiltinFunction("str.split", args -> {
                String[] parts;
                if (args.length > 0 && args[0] instanceof PyStr sep) {
                    parts = s.value().split(Pattern.quote(sep.value()), -1);
                } else {
                    parts = s.value().split("\\s+");
                }
                var items = new ArrayList<PyObject>();
                for (String p : parts) if (!p.isEmpty()) items.add(new PyStr(p));
                return new PyList(items);
            });
            case "join" -> new PyBuiltinFunction("str.join", args -> {
                checkArgCount("str.join", args, 1);
                if (!(args[0] instanceof PyList list)) throw PyErrors.error("TypeError", "join() argument must be iterable");
                var ctx = org.example.jython3.security.ExecutionContext.current();
                int maxStr = ctx != null ? ctx.limits().maxStringLength() : Integer.MAX_VALUE;
                var sb = new StringBuilder();
                for (int i = 0; i < list.items().size(); i++) {
                    if (i > 0) sb.append(s.value());
                    String piece = list.items().get(i).toString();
                    if ((long) sb.length() + piece.length() > maxStr) {
                        throw PyErrors.error("MemoryError", "joined string exceeds maximum allowed length: " + maxStr);
                    }
                    sb.append(piece);
                }
                return new PyStr(sb.toString());
            });
            case "replace" -> new PyBuiltinFunction("str.replace", args -> {
                if (args.length < 2) throw PyErrors.error("TypeError", "replace() takes at least 2 arguments");
                if (!(args[0] instanceof PyStr old) || !(args[1] instanceof PyStr rep))
                    throw PyErrors.error("TypeError", "replace() arguments must be strings");
                var ctx = org.example.jython3.security.ExecutionContext.current();
                int maxStr = ctx != null ? ctx.limits().maxStringLength() : Integer.MAX_VALUE;
                if (rep.value().length() > old.value().length()) {
                    String src = s.value();
                    int occurrences;
                    if (old.value().isEmpty()) {
                        occurrences = src.length() + 1;
                    } else {
                        occurrences = 0;
                        int pos = 0;
                        while ((pos = src.indexOf(old.value(), pos)) >= 0) {
                            occurrences++;
                            pos += old.value().length();
                        }
                    }
                    long estLen = (long) src.length() + (long) occurrences * (rep.value().length() - old.value().length());
                    if (estLen > maxStr) {
                        throw PyErrors.error("MemoryError", "replaced string exceeds maximum allowed length: " + maxStr);
                    }
                }
                return new PyStr(s.value().replace(old.value(), rep.value()));
            });
            case "startswith" -> new PyBuiltinFunction("str.startswith", args -> {
                checkArgCount("str.startswith", args, 1);
                if (args[0] instanceof PyStr prefix) return PyBool.of(s.value().startsWith(prefix.value()));
                if (args[0] instanceof PyTuple tup) {
                    for (PyObject p : tup.items()) {
                        if (!(p instanceof PyStr ps)) throw PyErrors.error("TypeError", "tuple for startswith must only contain str");
                        if (s.value().startsWith(ps.value())) return PyBool.TRUE;
                    }
                    return PyBool.FALSE;
                }
                throw PyErrors.error("TypeError", "startswith first arg must be str or a tuple of str");
            });
            case "endswith" -> new PyBuiltinFunction("str.endswith", args -> {
                checkArgCount("str.endswith", args, 1);
                if (args[0] instanceof PyStr suffix) return PyBool.of(s.value().endsWith(suffix.value()));
                if (args[0] instanceof PyTuple tup) {
                    for (PyObject p : tup.items()) {
                        if (!(p instanceof PyStr ps)) throw PyErrors.error("TypeError", "tuple for endswith must only contain str");
                        if (s.value().endsWith(ps.value())) return PyBool.TRUE;
                    }
                    return PyBool.FALSE;
                }
                throw PyErrors.error("TypeError", "endswith first arg must be str or a tuple of str");
            });
            case "lstrip" -> new PyBuiltinFunction("str.lstrip", args -> {
                if (args.length == 0 || args[0] instanceof PyNull) return new PyStr(s.value().stripLeading());
                String chars = ((PyStr) args[0]).value();
                int idx = 0;
                while (idx < s.value().length() && chars.indexOf(s.value().charAt(idx)) >= 0) idx++;
                return new PyStr(s.value().substring(idx));
            });
            case "rstrip" -> new PyBuiltinFunction("str.rstrip", args -> {
                if (args.length == 0 || args[0] instanceof PyNull) return new PyStr(s.value().stripTrailing());
                String chars = ((PyStr) args[0]).value();
                int end = s.value().length();
                while (end > 0 && chars.indexOf(s.value().charAt(end - 1)) >= 0) end--;
                return new PyStr(s.value().substring(0, end));
            });
            case "removeprefix" -> new PyBuiltinFunction("str.removeprefix", args -> {
                checkArgCount("str.removeprefix", args, 1);
                if (!(args[0] instanceof PyStr pre)) throw PyErrors.error("TypeError", "removeprefix() argument must be str");
                return s.value().startsWith(pre.value()) ? new PyStr(s.value().substring(pre.value().length())) : s;
            });
            case "removesuffix" -> new PyBuiltinFunction("str.removesuffix", args -> {
                checkArgCount("str.removesuffix", args, 1);
                if (!(args[0] instanceof PyStr suf)) throw PyErrors.error("TypeError", "removesuffix() argument must be str");
                return s.value().endsWith(suf.value())
                        ? new PyStr(s.value().substring(0, s.value().length() - suf.value().length())) : s;
            });
            case "splitlines" -> new PyBuiltinFunction("str.splitlines", args -> {
                boolean keepEnds = args.length >= 1 && PyOps.isTruthy(args[0]);
                var res = new ArrayList<PyObject>();
                String raw = s.value();
                int start = 0;
                for (int i = 0; i < raw.length(); i++) {
                    char c = raw.charAt(i);
                    if (c == '\r' || c == '\n') {
                        int lineEnd = i;
                        if (c == '\r' && i + 1 < raw.length() && raw.charAt(i + 1) == '\n') i++;
                        res.add(new PyStr(raw.substring(start, keepEnds ? i + 1 : lineEnd)));
                        start = i + 1;
                    }
                }
                if (start < raw.length()) res.add(new PyStr(raw.substring(start)));
                return new PyList(res);
            });
            case "partition" -> new PyBuiltinFunction("str.partition", args -> {
                checkArgCount("str.partition", args, 1);
                if (!(args[0] instanceof PyStr sep) || sep.value().isEmpty()) {
                    throw PyErrors.error("ValueError", "empty separator");
                }
                int idx = s.value().indexOf(sep.value());
                if (idx < 0) return new PyTuple(java.util.List.of(s, new PyStr(""), new PyStr("")));
                return new PyTuple(java.util.List.of(
                        new PyStr(s.value().substring(0, idx)),
                        sep,
                        new PyStr(s.value().substring(idx + sep.value().length()))));
            });
            case "rpartition" -> new PyBuiltinFunction("str.rpartition", args -> {
                checkArgCount("str.rpartition", args, 1);
                if (!(args[0] instanceof PyStr sep) || sep.value().isEmpty()) {
                    throw PyErrors.error("ValueError", "empty separator");
                }
                int idx = s.value().lastIndexOf(sep.value());
                if (idx < 0) return new PyTuple(java.util.List.of(new PyStr(""), new PyStr(""), s));
                return new PyTuple(java.util.List.of(
                        new PyStr(s.value().substring(0, idx)),
                        sep,
                        new PyStr(s.value().substring(idx + sep.value().length()))));
            });
            case "capitalize" -> new PyBuiltinFunction("str.capitalize", args -> {
                if (s.value().isEmpty()) return s;
                return new PyStr(Character.toUpperCase(s.value().charAt(0)) + s.value().substring(1).toLowerCase());
            });
            case "title" -> new PyBuiltinFunction("str.title", args -> {
                var sb = new StringBuilder(s.value().length());
                boolean nextUpper = true;
                for (int i = 0; i < s.value().length(); i++) {
                    char c = s.value().charAt(i);
                    if (!Character.isLetter(c)) { sb.append(c); nextUpper = true; }
                    else { sb.append(nextUpper ? Character.toUpperCase(c) : Character.toLowerCase(c)); nextUpper = false; }
                }
                return new PyStr(sb.toString());
            });
            case "center", "ljust", "rjust" -> new PyBuiltinFunction("str." + method, args -> {
                if (args.length < 1 || args.length > 2) throw PyErrors.error("TypeError", method + "() takes 1 or 2 arguments");
                int width = toInt(args[0]);
                var ctx = org.example.jython3.security.ExecutionContext.current();
                int maxStr = ctx != null ? Math.min(100_000, ctx.limits().maxStringLength()) : 100_000;
                if (width > maxStr) throw PyErrors.error("MemoryError", "width exceeds limit");
                char fill = ' ';
                if (args.length == 2) {
                    if (!(args[1] instanceof PyStr fs) || fs.value().length() != 1) {
                        throw PyErrors.error("TypeError", "The fill character must be exactly one character long");
                    }
                    fill = fs.value().charAt(0);
                }
                int cur = s.codePointCount();
                if (width <= cur) return s;
                int pad = width - cur;
                if ("ljust".equals(method)) return new PyStr(s.value() + String.valueOf(fill).repeat(pad));
                if ("rjust".equals(method)) return new PyStr(String.valueOf(fill).repeat(pad) + s.value());
                int left = pad / 2;
                return new PyStr(String.valueOf(fill).repeat(left) + s.value() + String.valueOf(fill).repeat(pad - left));
            });
            case "zfill" -> new PyBuiltinFunction("str.zfill", args -> {
                checkArgCount("str.zfill", args, 1);
                int width = toInt(args[0]);
                var ctx = org.example.jython3.security.ExecutionContext.current();
                int maxStr = ctx != null ? Math.min(100_000, ctx.limits().maxStringLength()) : 100_000;
                if (width > maxStr) throw PyErrors.error("MemoryError", "width exceeds limit");
                String raw = s.value();
                if (raw.length() >= width) return s;
                int pad = width - raw.length();
                if (raw.startsWith("+") || raw.startsWith("-")) {
                    return new PyStr(raw.charAt(0) + "0".repeat(pad) + raw.substring(1));
                }
                return new PyStr("0".repeat(pad) + raw);
            });
            case "isspace" -> new PyBuiltinFunction("str.isspace", args -> PyBool.of(!s.value().isEmpty() && s.value().chars().allMatch(Character::isWhitespace)));
            case "isupper" -> new PyBuiltinFunction("str.isupper", args -> PyBool.of(s.value().chars().anyMatch(Character::isLetter) && s.value().chars().filter(Character::isLetter).allMatch(Character::isUpperCase)));
            case "islower" -> new PyBuiltinFunction("str.islower", args -> PyBool.of(s.value().chars().anyMatch(Character::isLetter) && s.value().chars().filter(Character::isLetter).allMatch(Character::isLowerCase)));
            case "format" -> new PyBuiltinFunction("str.format", (args, kwargs) -> {
                return new PyStr(strDotFormat(s.value(), args, kwargs));
            });
            case "find" -> new PyBuiltinFunction("str.find", args -> {
                checkArgCount("str.find", args, 1);
                if (!(args[0] instanceof PyStr sub)) throw PyErrors.error("TypeError", "find() argument must be str");
                return PyInt.of(s.value().indexOf(sub.value()));
            });
            case "rfind" -> new PyBuiltinFunction("str.rfind", args -> {
                checkArgCount("str.rfind", args, 1);
                if (!(args[0] instanceof PyStr sub)) throw PyErrors.error("TypeError", "rfind() argument must be str");
                return PyInt.of(s.value().lastIndexOf(sub.value()));
            });
            case "index" -> new PyBuiltinFunction("str.index", args -> {
                checkArgCount("str.index", args, 1);
                if (!(args[0] instanceof PyStr sub)) throw PyErrors.error("TypeError", "index() argument must be str");
                int idx = s.value().indexOf(sub.value());
                if (idx < 0) throw PyErrors.error("ValueError", "substring not found");
                return PyInt.of(idx);
            });
            case "rindex" -> new PyBuiltinFunction("str.rindex", args -> {
                checkArgCount("str.rindex", args, 1);
                if (!(args[0] instanceof PyStr sub)) throw PyErrors.error("TypeError", "rindex() argument must be str");
                int idx = s.value().lastIndexOf(sub.value());
                if (idx < 0) throw PyErrors.error("ValueError", "substring not found");
                return PyInt.of(idx);
            });
            case "swapcase" -> new PyBuiltinFunction("str.swapcase", args -> {
                checkArgCount("str.swapcase", args, 0);
                var sb = new StringBuilder(s.value().length());
                s.value().codePoints().forEach(cp -> {
                    if (Character.isUpperCase(cp)) sb.appendCodePoint(Character.toLowerCase(cp));
                    else if (Character.isLowerCase(cp)) sb.appendCodePoint(Character.toUpperCase(cp));
                    else sb.appendCodePoint(cp);
                });
                return new PyStr(sb.toString());
            });
            case "casefold" -> new PyBuiltinFunction("str.casefold", args -> {
                checkArgCount("str.casefold", args, 0);
                return new PyStr(s.value().toLowerCase(java.util.Locale.ROOT));
            });
            case "count" -> new PyBuiltinFunction("str.count", args -> {
                checkArgCount("str.count", args, 1);
                if (!(args[0] instanceof PyStr sub)) throw PyErrors.error("TypeError", "count() argument must be str");
                if (sub.value().isEmpty()) return PyInt.of(s.codePointCount() + 1);
                int count = 0; int idx = 0;
                while ((idx = s.value().indexOf(sub.value(), idx)) != -1) { count++; idx += sub.value().length(); }
                return PyInt.of(count);
            });
            case "isdigit" -> new PyBuiltinFunction("str.isdigit", args -> PyBool.of(!s.value().isEmpty() && s.value().chars().allMatch(Character::isDigit)));
            case "isalpha" -> new PyBuiltinFunction("str.isalpha", args -> PyBool.of(!s.value().isEmpty() && s.value().chars().allMatch(Character::isLetter)));
            case "isalnum" -> new PyBuiltinFunction("str.isalnum", args -> PyBool.of(!s.value().isEmpty() && s.value().chars().allMatch(Character::isLetterOrDigit)));
            case "encode" -> new PyBuiltinFunction("str.encode", (args, kwargs) -> {
                String enc = kwargs.containsKey("encoding") ? kwargs.get("encoding").toString()
                        : (args.length >= 1 ? args[0].toString() : "utf-8");
                var cs = PyBytes.resolveCharset(enc);
                return PyBytes.wrapOwned(s.value().getBytes(cs));
            });
            default -> throw PyErrors.error("AttributeError", "'str' object has no attribute '" + method + "'");
        };
    }

    public static PyObject setMethod(PySet set, String method) {
        return setMethod(set, method, org.example.jython3.security.DenyAllPolicy.INSTANCE);
    }

    public static PyObject setMethod(PySet set, String method, JavaAccessPolicy policy) {
        return switch (method) {
            case "add" -> new PyBuiltinFunction("set.add", args -> {
                checkArgCount("set.add", args, 1);
                set.add(args[0]);
                return new PyNull();
            });
            case "remove" -> new PyBuiltinFunction("set.remove", args -> {
                checkArgCount("set.remove", args, 1);
                if (!set.remove(args[0])) {
                    throw PyErrors.error("KeyError", String.valueOf(args[0]));
                }
                return new PyNull();
            });
            case "discard" -> new PyBuiltinFunction("set.discard", args -> {
                checkArgCount("set.discard", args, 1);
                set.remove(args[0]);
                return new PyNull();
            });
            case "pop" -> new PyBuiltinFunction("set.pop", args -> {
                checkArgCount("set.pop", args, 0);
                return set.pop();
            });
            case "clear" -> new PyBuiltinFunction("set.clear", args -> {
                checkArgCount("set.clear", args, 0);
                set.clear();
                return new PyNull();
            });
            case "copy" -> new PyBuiltinFunction("set.copy", args -> {
                checkArgCount("set.copy", args, 0);
                return set.copy();
            });
            case "union" -> new PyBuiltinFunction("set.union", args -> {
                PySet res = set.copy();
                for (PyObject other : args) {
                    for (PyObject el : BuiltinFunctions.collectIterable(other, policy)) {
                        res.add(el);
                    }
                }
                return res;
            });
            case "update" -> new PyBuiltinFunction("set.update", args -> {
                for (PyObject other : args) {
                    for (PyObject el : BuiltinFunctions.collectIterable(other, policy)) {
                        set.add(el);
                    }
                }
                return new PyNull();
            });
            case "intersection" -> new PyBuiltinFunction("set.intersection", args -> {
                if (args.length == 0) return set.copy();
                var otherSets = new ArrayList<PySet>(args.length);
                for (PyObject other : args) {
                    otherSets.add(new PySet(BuiltinFunctions.collectIterable(other, policy)));
                }
                PySet res = new PySet();
                for (PyObject el : set.items()) {
                    boolean inAll = true;
                    for (PySet os : otherSets) {
                        if (!os.contains(el)) { inAll = false; break; }
                    }
                    if (inAll) res.add(el);
                }
                return res;
            });
            case "difference" -> new PyBuiltinFunction("set.difference", args -> {
                PySet res = set.copy();
                for (PyObject other : args) {
                    for (PyObject el : BuiltinFunctions.collectIterable(other, policy)) {
                        res.remove(el);
                    }
                }
                return res;
            });
            case "symmetric_difference" -> new PyBuiltinFunction("set.symmetric_difference", args -> {
                checkArgCount("set.symmetric_difference", args, 1);
                var otherSet = new PySet(BuiltinFunctions.collectIterable(args[0], policy));
                PySet res = new PySet();
                for (PyObject el : set.items()) {
                    if (!otherSet.contains(el)) res.add(el);
                }
                for (PyObject el : otherSet.items()) {
                    if (!set.contains(el)) res.add(el);
                }
                return res;
            });
            case "issubset" -> new PyBuiltinFunction("set.issubset", args -> {
                checkArgCount("set.issubset", args, 1);
                var otherSet = toSetView(args[0], policy);
                if (set.size() > otherSet.size()) return PyBool.FALSE;
                for (PyObject el : set.items()) {
                    if (!otherSet.contains(el)) return PyBool.FALSE;
                }
                return PyBool.TRUE;
            });
            case "issuperset" -> new PyBuiltinFunction("set.issuperset", args -> {
                checkArgCount("set.issuperset", args, 1);
                for (PyObject el : BuiltinFunctions.collectIterable(args[0], policy)) {
                    if (!set.contains(el)) return PyBool.FALSE;
                }
                return PyBool.TRUE;
            });
            case "isdisjoint" -> new PyBuiltinFunction("set.isdisjoint", args -> {
                checkArgCount("set.isdisjoint", args, 1);
                for (PyObject el : BuiltinFunctions.collectIterable(args[0], policy)) {
                    if (set.contains(el)) return PyBool.FALSE;
                }
                return PyBool.TRUE;
            });
            default -> throw PyErrors.error("AttributeError", "'set' object has no attribute '" + method + "'");
        };
    }

    public static PyObject frozenSetMethod(PyFrozenSet fs, String method, JavaAccessPolicy policy) {
        return switch (method) {
            case "copy" -> new PyBuiltinFunction("frozenset.copy", args -> {
                checkArgCount("frozenset.copy", args, 0);
                return fs;
            });
            case "union" -> new PyBuiltinFunction("frozenset.union", args -> {
                var acc = new PySet(fs.items());
                for (PyObject other : args) {
                    for (PyObject el : BuiltinFunctions.collectIterable(other, policy)) {
                        acc.add(el);
                    }
                }
                return new PyFrozenSet(acc.items());
            });
            case "intersection" -> new PyBuiltinFunction("frozenset.intersection", args -> {
                if (args.length == 0) return fs;
                var otherSets = new ArrayList<PySet>(args.length);
                for (PyObject other : args) {
                    otherSets.add(new PySet(BuiltinFunctions.collectIterable(other, policy)));
                }
                var kept = new ArrayList<PyObject>();
                for (PyObject el : fs.items()) {
                    boolean inAll = true;
                    for (PySet os : otherSets) {
                        if (!os.contains(el)) { inAll = false; break; }
                    }
                    if (inAll) kept.add(el);
                }
                return new PyFrozenSet(kept);
            });
            case "difference" -> new PyBuiltinFunction("frozenset.difference", args -> {
                var acc = new PySet(fs.items());
                for (PyObject other : args) {
                    for (PyObject el : BuiltinFunctions.collectIterable(other, policy)) {
                        acc.remove(el);
                    }
                }
                return new PyFrozenSet(acc.items());
            });
            case "symmetric_difference" -> new PyBuiltinFunction("frozenset.symmetric_difference", args -> {
                checkArgCount("frozenset.symmetric_difference", args, 1);
                var otherSet = new PySet(BuiltinFunctions.collectIterable(args[0], policy));
                var acc = new ArrayList<PyObject>();
                for (PyObject el : fs.items()) {
                    if (!otherSet.contains(el)) acc.add(el);
                }
                for (PyObject el : otherSet.items()) {
                    if (!fs.contains(el)) acc.add(el);
                }
                return new PyFrozenSet(acc);
            });
            case "issubset" -> new PyBuiltinFunction("frozenset.issubset", args -> {
                checkArgCount("frozenset.issubset", args, 1);
                var otherSet = toSetView(args[0], policy);
                if (fs.size() > otherSet.size()) return PyBool.FALSE;
                for (PyObject el : fs.items()) {
                    if (!otherSet.contains(el)) return PyBool.FALSE;
                }
                return PyBool.TRUE;
            });
            case "issuperset" -> new PyBuiltinFunction("frozenset.issuperset", args -> {
                checkArgCount("frozenset.issuperset", args, 1);
                for (PyObject el : BuiltinFunctions.collectIterable(args[0], policy)) {
                    if (!fs.contains(el)) return PyBool.FALSE;
                }
                return PyBool.TRUE;
            });
            case "isdisjoint" -> new PyBuiltinFunction("frozenset.isdisjoint", args -> {
                checkArgCount("frozenset.isdisjoint", args, 1);
                for (PyObject el : BuiltinFunctions.collectIterable(args[0], policy)) {
                    if (fs.contains(el)) return PyBool.FALSE;
                }
                return PyBool.TRUE;
            });
            default -> throw PyErrors.error("AttributeError", "'frozenset' object has no attribute '" + method + "'");
        };
    }

    private static PySet toSetView(PyObject obj, JavaAccessPolicy policy) {
        if (obj instanceof PySet s) return s;
        return new PySet(BuiltinFunctions.collectIterable(obj, policy));
    }

    public static PyObject bytesMethod(PyBytes b, String method) {
        return switch (method) {
            case "decode" -> new PyBuiltinFunction("bytes.decode", (args, kwargs) -> {
                String enc = kwargs.containsKey("encoding") ? kwargs.get("encoding").toString()
                        : (args.length >= 1 ? args[0].toString() : "utf-8");
                String errs = kwargs.containsKey("errors") ? kwargs.get("errors").toString()
                        : (args.length >= 2 ? args[1].toString() : "strict");
                return new PyStr(b.decode(enc, errs));
            });
            case "hex" -> new PyBuiltinFunction("bytes.hex", args -> {
                checkArgCount("bytes.hex", args, 0);
                return new PyStr(b.toHex());
            });
            case "startswith" -> new PyBuiltinFunction("bytes.startswith", args -> {
                checkArgCount("bytes.startswith", args, 1);
                byte[] pre = PyBytes.extractBuffer(args[0]);
                byte[] data = b.bytesUnsafe();
                if (pre.length > data.length) return PyBool.FALSE;
                for (int i = 0; i < pre.length; i++) if (data[i] != pre[i]) return PyBool.FALSE;
                return PyBool.TRUE;
            });
            case "endswith" -> new PyBuiltinFunction("bytes.endswith", args -> {
                checkArgCount("bytes.endswith", args, 1);
                byte[] suf = PyBytes.extractBuffer(args[0]);
                byte[] data = b.bytesUnsafe();
                if (suf.length > data.length) return PyBool.FALSE;
                int off = data.length - suf.length;
                for (int i = 0; i < suf.length; i++) if (data[off + i] != suf[i]) return PyBool.FALSE;
                return PyBool.TRUE;
            });
            case "find" -> new PyBuiltinFunction("bytes.find", args -> {
                checkArgCount("bytes.find", args, 1);
                return PyInt.of(PyBytes.indexOfBytes(b.bytesUnsafe(), PyBytes.extractBuffer(args[0])));
            });
            case "count" -> new PyBuiltinFunction("bytes.count", args -> {
                checkArgCount("bytes.count", args, 1);
                byte[] hay = b.bytesUnsafe();
                if (args[0] instanceof PyInt || args[0] instanceof PyBool) {
                    int needle = PyBytes.extractByteValue(args[0]);
                    int cnt = 0;
                    for (byte v : hay) if ((v & 0xFF) == needle) cnt++;
                    return PyInt.of(cnt);
                }
                byte[] needle = PyBytes.extractBuffer(args[0]);
                if (needle.length == 0) return PyInt.of(hay.length + 1);
                int cnt = 0;
                for (int i = 0; i <= hay.length - needle.length; ) {
                    boolean match = true;
                    for (int j = 0; j < needle.length; j++) {
                        if (hay[i + j] != needle[j]) { match = false; break; }
                    }
                    if (match) { cnt++; i += needle.length; } else { i++; }
                }
                return PyInt.of(cnt);
            });
            default -> throw PyErrors.error("AttributeError", "'bytes' object has no attribute '" + method + "'");
        };
    }

    public static PyObject byteArrayMethod(PyByteArray ba, String method) {
        return switch (method) {
            case "decode" -> new PyBuiltinFunction("bytearray.decode", (args, kwargs) -> {
                String enc = kwargs.containsKey("encoding") ? kwargs.get("encoding").toString()
                        : (args.length >= 1 ? args[0].toString() : "utf-8");
                String errs = kwargs.containsKey("errors") ? kwargs.get("errors").toString()
                        : (args.length >= 2 ? args[1].toString() : "strict");
                return new PyStr(ba.decode(enc, errs));
            });
            case "hex" -> new PyBuiltinFunction("bytearray.hex", args -> {
                checkArgCount("bytearray.hex", args, 0);
                return new PyStr(ba.toHex());
            });
            case "append" -> new PyBuiltinFunction("bytearray.append", args -> {
                checkArgCount("bytearray.append", args, 1);
                ba.append(PyBytes.extractByteValue(args[0]));
                return PyNull.INSTANCE;
            });
            case "extend" -> new PyBuiltinFunction("bytearray.extend", args -> {
                checkArgCount("bytearray.extend", args, 1);
                var ctx = org.example.jython3.security.ExecutionContext.current();
                int maxLen = ctx != null ? ctx.limits().maxStringLength() : 100_000_000;
                if (args[0] instanceof PyBytes || args[0] instanceof PyByteArray) {
                    ba.extend(PyBytes.extractBuffer(args[0]), maxLen);
                } else {
                    var items = BuiltinFunctions.collectIterable(args[0], org.example.jython3.security.DenyAllPolicy.INSTANCE);
                    byte[] more = new byte[items.size()];
                    for (int i = 0; i < items.size(); i++) more[i] = (byte) PyBytes.extractByteValue(items.get(i));
                    ba.extend(more, maxLen);
                }
                return PyNull.INSTANCE;
            });
            case "clear" -> new PyBuiltinFunction("bytearray.clear", args -> {
                checkArgCount("bytearray.clear", args, 0);
                ba.clear();
                return PyNull.INSTANCE;
            });
            case "copy" -> new PyBuiltinFunction("bytearray.copy", args -> {
                checkArgCount("bytearray.copy", args, 0);
                return new PyByteArray(ba.copyBytes());
            });
            case "pop" -> new PyBuiltinFunction("bytearray.pop", args -> {
                int idx = args.length >= 1 ? toInt(args[0]) : -1;
                return PyInt.of(ba.pop(idx));
            });
            case "reverse" -> new PyBuiltinFunction("bytearray.reverse", args -> {
                checkArgCount("bytearray.reverse", args, 0);
                ba.reverse();
                return PyNull.INSTANCE;
            });
            default -> bytesMethod(PyBytes.wrapOwned(ba.copyBytes()), method);
        };
    }

    public static PyObject rangeAttr(PyRange r, String attr) {
        return switch (attr) {
            case "start" -> r.startPy();
            case "stop" -> r.stopPy();
            case "step" -> r.stepPy();
            case "count" -> new PyBuiltinFunction("range.count", args -> {
                checkArgCount("range.count", args, 1);
                return r.contains(args[0]) ? PyInt.ONE : PyInt.ZERO;
            });
            case "index" -> new PyBuiltinFunction("range.index", args -> {
                checkArgCount("range.index", args, 1);
                return r.indexOf(args[0]);
            });
            default -> throw PyErrors.error("AttributeError", "'range' object has no attribute '" + attr + "'");
        };
    }

    public static PyObject sliceAttr(PySlice sl, String attr) {
        return switch (attr) {
            case "start" -> sl.start();
            case "stop" -> sl.stop();
            case "step" -> sl.step();
            case "indices" -> new PyBuiltinFunction("slice.indices", args -> {
                checkArgCount("slice.indices", args, 1);
                int len = toInt(args[0]);
                if (len < 0) throw PyErrors.error("ValueError", "length should not be negative");
                PySlice.Indices idx = sl.computeIndices(len);
                return new PyTuple(java.util.List.of(PyInt.of(idx.start()), PyInt.of(idx.stop()), PyInt.of(idx.step())));
            });
            default -> throw PyErrors.error("AttributeError", "'slice' object has no attribute '" + attr + "'");
        };
    }

    public static PyObject generatorMethod(PyGenerator gen, String method) {
        return switch (method) {
            case "send" -> new PyBuiltinFunction("generator.send", args -> {
                checkArgCount("generator.send", args, 1);
                return gen.send(args[0]);
            });
            case "throw" -> new PyBuiltinFunction("generator.throw", args -> {
                if (args.length < 1 || args.length > 3) {
                    throw PyErrors.error("TypeError", "throw() takes 1 to 3 arguments (" + args.length + " given)");
                }
                return gen.throwException(args);
            });
            case "close" -> new PyBuiltinFunction("generator.close", args -> {
                checkArgCount("generator.close", args, 0);
                return gen.close();
            });
            default -> throw PyErrors.error("AttributeError", "'generator' object has no attribute '" + method + "'");
        };
    }

    public static PyObject coroutineMethod(org.example.jython3.runtime.PyCoroutine coro, String method) {
        return switch (method) {
            case "send" -> new PyBuiltinFunction("coroutine.send", args -> {
                checkArgCount("coroutine.send", args, 1);
                return coro.send(args[0]);
            });
            case "throw" -> new PyBuiltinFunction("coroutine.throw", args -> {
                if (args.length < 1 || args.length > 3) {
                    throw PyErrors.error("TypeError", "throw() takes 1 to 3 arguments (" + args.length + " given)");
                }
                return coro.throwException(args);
            });
            case "close" -> new PyBuiltinFunction("coroutine.close", args -> {
                checkArgCount("coroutine.close", args, 0);
                return coro.close();
            });
            case "__await__" -> new PyBuiltinFunction("coroutine.__await__", args -> {
                checkArgCount("coroutine.__await__", args, 0);
                return coro.runner();
            });
            default -> throw PyErrors.error("AttributeError", "'coroutine' object has no attribute '" + method + "'");
        };
    }

    public static PyObject tupleMethod(PyTuple tuple, String method) {
        return switch (method) {
            case "count" -> new PyBuiltinFunction("tuple.count", args -> {
                checkArgCount("tuple.count", args, 1);
                int c = 0;
                for (var item : tuple.items()) if (pyEquals(item, args[0])) c++;
                return PyInt.of(c);
            });
            case "index" -> new PyBuiltinFunction("tuple.index", args -> {
                checkArgCount("tuple.index", args, 1);
                for (int i = 0; i < tuple.items().size(); i++) {
                    if (pyEquals(tuple.items().get(i), args[0])) return PyInt.of(i);
                }
                throw PyErrors.error("ValueError", "tuple.index(x): x not in tuple");
            });
            default -> throw PyErrors.error("AttributeError", "'tuple' object has no attribute '" + method + "'");
        };
    }

    public static PyObject listMethod(PyList list, String method) {
        var ctx = org.example.jython3.security.ExecutionContext.current();
        int maxColl = ctx != null ? ctx.limits().maxCollectionSize() : 100_000_000;
        return switch (method) {
            case "append" -> new PyBuiltinFunction("list.append", args -> {
                checkArgCount("list.append", args, 1);
                if (list.items().size() >= maxColl) {
                    throw PyErrors.error("OverflowError", "List size exceeds maximum allowed limit: " + maxColl);
                }
                list.items().add(args[0]);
                return new PyNull();
            });
            case "pop" -> new PyBuiltinFunction("list.pop", args -> {
                if (list.items().isEmpty()) throw PyErrors.error("IndexError", "pop from empty list");
                if (args.length > 0) { int i = toInt(args[0]); if (i < 0) i += list.items().size(); return list.items().remove(i); }
                return list.items().remove(list.items().size() - 1);
            });
            case "extend" -> new PyBuiltinFunction("list.extend", args -> {
                checkArgCount("list.extend", args, 1);
                var extra = (args[0] instanceof PyList other)
                        ? other.items()
                        : BuiltinFunctions.collectIterable(args[0], org.example.jython3.security.DenyAllPolicy.INSTANCE);
                if ((long) list.items().size() + extra.size() > maxColl) {
                    throw PyErrors.error("OverflowError", "List size exceeds maximum allowed limit: " + maxColl);
                }
                list.items().addAll(extra);
                return new PyNull();
            });
            case "insert" -> new PyBuiltinFunction("list.insert", args -> {
                if (args.length < 2) throw PyErrors.error("TypeError", "insert() takes 2 arguments");
                if (list.items().size() >= maxColl) {
                    throw PyErrors.error("OverflowError", "List size exceeds maximum allowed limit: " + maxColl);
                }
                int i = toInt(args[0]); if (i < 0) i += list.items().size(); if (i < 0) i = 0;
                if (i > list.items().size()) i = list.items().size();
                list.items().add(i, args[1]);
                return new PyNull();
            });
            case "remove" -> new PyBuiltinFunction("list.remove", args -> {
                checkArgCount("list.remove", args, 1);
                for (int i = 0; i < list.items().size(); i++) {
                    if (pyEquals(list.items().get(i), args[0])) { list.items().remove(i); return new PyNull(); }
                }
                throw PyErrors.error("ValueError", "list.remove(x): x not in list");
            });
            case "index" -> new PyBuiltinFunction("list.index", args -> {
                checkArgCount("list.index", args, 1);
                for (int i = 0; i < list.items().size(); i++) {
                    if (pyEquals(list.items().get(i), args[0])) return PyInt.of(i);
                }
                throw PyErrors.error("ValueError", args[0] + " is not in list");
            });
            case "count" -> new PyBuiltinFunction("list.count", args -> {
                checkArgCount("list.count", args, 1);
                int c = 0; for (var item : list.items()) if (pyEquals(item, args[0])) c++;
                return PyInt.of(c);
            });
            case "sort" -> new PyBuiltinFunction("list.sort", (args, kwargs) -> {
                if (args.length > 0) throw PyErrors.error("TypeError", "sort() takes no positional arguments");
                boolean reverse = kwargs.containsKey("reverse") && PyOps.isTruthy(kwargs.get("reverse"));
                PyObject keyFn = kwargs.get("key");
                if (keyFn != null && !(keyFn instanceof PyNull)) {
                    var caller = org.example.jython3.runtime.PyCaller.current();
                    record Keyed(PyObject k, PyObject v) {}
                    var dec = new ArrayList<Keyed>(list.items().size());
                    for (PyObject item : list.items()) {
                        dec.add(new Keyed(caller.call(keyFn, new PyObject[]{item}), item));
                    }
                    dec.sort((a, b) -> PyOps.compareObjects(a.k(), b.k()));
                    list.items().clear();
                    for (Keyed kv : dec) list.items().add(kv.v());
                } else {
                    list.items().sort(PyOps::compareObjects);
                }
                if (reverse) Collections.reverse(list.items());
                return new PyNull();
            });
            case "reverse" -> new PyBuiltinFunction("list.reverse", args -> {
                Collections.reverse(list.items());
                return new PyNull();
            });
            case "copy" -> new PyBuiltinFunction("list.copy", args -> new PyList(new ArrayList<>(list.items())));
            case "clear" -> new PyBuiltinFunction("list.clear", args -> { list.items().clear(); return new PyNull(); });
            default -> throw PyErrors.error("AttributeError", "'list' object has no attribute '" + method + "'");
        };
    }

    public static PyObject dictMethod(PyDict dict, String method) {
        return switch (method) {
            case "keys" -> new PyBuiltinFunction("dict.keys", args -> new PyList(dict.keys()));
            case "values" -> new PyBuiltinFunction("dict.values", args -> new PyList(dict.values()));
            case "items" -> new PyBuiltinFunction("dict.items", args -> {
                var items = new ArrayList<PyObject>();
                for (var entry : dict.itemsList()) items.add(new PyTuple(entry));
                return new PyList(items);
            });
            case "get" -> new PyBuiltinFunction("dict.get", args -> {
                if (args.length < 1) throw PyErrors.error("TypeError", "get() takes at least 1 argument");
                PyObject val = dict.get(args[0]);
                if (val != null) return val;
                return (args.length > 1) ? args[1] : new PyNull();
            });
            case "setdefault" -> new PyBuiltinFunction("dict.setdefault", args -> {
                if (args.length < 1 || args.length > 2) throw PyErrors.error("TypeError", "setdefault expected 1 or 2 arguments");
                PyObject existing = dict.get(args[0]);
                if (existing != null) return existing;
                PyObject defVal = args.length == 2 ? args[1] : new PyNull();
                dict.put(args[0], defVal);
                return defVal;
            });
            case "popitem" -> new PyBuiltinFunction("dict.popitem", args -> {
                checkArgCount("dict.popitem", args, 0);
                var keys = dict.keys();
                if (keys.isEmpty()) throw PyErrors.error("KeyError", "popitem(): dictionary is empty");
                PyObject lastKey = keys.get(keys.size() - 1);
                PyObject val = dict.remove(lastKey);
                return new PyTuple(java.util.List.of(lastKey, val));
            });
            case "update" -> new PyBuiltinFunction("dict.update", (args, kwargs) -> {
                if (args.length > 1) throw PyErrors.error("TypeError", "update expected at most 1 argument");
                if (args.length == 1) {
                    if (args[0] instanceof PyDict other) {
                        for (var key : other.keys()) dict.put(key, other.get(key));
                    } else {
                        for (PyObject pair : BuiltinFunctions.collectIterable(args[0], org.example.jython3.security.DenyAllPolicy.INSTANCE)) {
                            var kv = BuiltinFunctions.collectIterable(pair, org.example.jython3.security.DenyAllPolicy.INSTANCE);
                            if (kv.size() != 2) throw PyErrors.error("ValueError", "dictionary update sequence element has length " + kv.size() + "; 2 is required");
                            dict.put(kv.get(0), kv.get(1));
                        }
                    }
                }
                for (var e : kwargs.entrySet()) {
                    dict.put(new PyStr(e.getKey()), e.getValue());
                }
                return new PyNull();
            });
            case "pop" -> new PyBuiltinFunction("dict.pop", args -> {
                if (args.length < 1) throw PyErrors.error("TypeError", "pop() takes at least 1 argument");
                PyObject val = dict.remove(args[0]);
                if (val != null) return val;
                if (args.length > 1) return args[1];
                throw PyErrors.error("KeyError", String.valueOf(args[0]));
            });
            case "clear" -> new PyBuiltinFunction("dict.clear", args -> { dict.clear(); return new PyNull(); });
            case "copy" -> new PyBuiltinFunction("dict.copy", args -> new PyDict(dict.map()));
            default -> throw PyErrors.error("AttributeError", "'dict' object has no attribute '" + method + "'");
        };
    }

    private static String strDotFormat(String fmt, PyObject[] args, java.util.Map<String, PyObject> kwargs) {
        var ctx = org.example.jython3.security.ExecutionContext.current();
        int maxStr = ctx != null ? ctx.limits().maxStringLength() : 100_000_000;
        var sb = new StringBuilder(Math.min(fmt.length() + 16, maxStr));
        int autoIdx = 0;
        boolean usedAuto = false;
        boolean usedManual = false;
        int i = 0;
        int n = fmt.length();
        while (i < n) {
            char ch = fmt.charAt(i);
            if (ch == '{') {
                if (i + 1 < n && fmt.charAt(i + 1) == '{') { sb.append('{'); i += 2; continue; }
                int close = fmt.indexOf('}', i + 1);
                if (close < 0) throw PyErrors.error("ValueError", "Single '{' encountered in format string");
                String field = fmt.substring(i + 1, close);
                i = close + 1;

                int colon = field.indexOf(':');
                String keyPart = colon >= 0 ? field.substring(0, colon) : field;
                String spec = colon >= 0 ? field.substring(colon + 1) : "";
                char conv = 0;
                int bang = keyPart.indexOf('!');
                if (bang >= 0) {
                    if (bang + 1 < keyPart.length()) conv = keyPart.charAt(bang + 1);
                    keyPart = keyPart.substring(0, bang);
                }

                PyObject val;
                if (keyPart.isEmpty()) {
                    if (usedManual) throw PyErrors.error("ValueError", "cannot switch from manual field specification to automatic field numbering");
                    usedAuto = true;
                    if (autoIdx >= args.length) throw PyErrors.error("IndexError", "Replacement index " + autoIdx + " out of range");
                    val = args[autoIdx++];
                } else if (keyPart.chars().allMatch(Character::isDigit)) {
                    if (usedAuto) throw PyErrors.error("ValueError", "cannot switch from automatic field numbering to manual field specification");
                    usedManual = true;
                    int idx;
                    try { idx = Integer.parseInt(keyPart); }
                    catch (NumberFormatException nfe) { throw PyErrors.error("IndexError", "Replacement index " + keyPart + " out of range"); }
                    if (idx < 0 || idx >= args.length) throw PyErrors.error("IndexError", "Replacement index " + idx + " out of range");
                    val = args[idx];
                } else {
                    val = kwargs.get(keyPart);
                    if (val == null) throw PyErrors.error("KeyError", keyPart);
                }
                if (conv == 'r') {
                    val = BuiltinFunctions.pyReprStr(val);
                } else if (conv == 's') {
                    val = new PyStr(val.toString());
                }
                sb.append(spec.isEmpty() ? val.toString() : BuiltinFunctions.formatValuePublic(val, spec));
                if (sb.length() > maxStr) {
                    throw PyErrors.error("MemoryError", "formatted string exceeds maximum allowed length: " + maxStr);
                }
            } else if (ch == '}') {
                if (i + 1 < n && fmt.charAt(i + 1) == '}') { sb.append('}'); i += 2; continue; }
                throw PyErrors.error("ValueError", "Single '}' encountered in format string");
            } else {
                sb.append(ch);
                i++;
            }
        }
        return sb.toString();
    }
}
