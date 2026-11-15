package org.example.jython3.stdlib;

import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyBuiltinFunction;
import org.example.jython3.runtime.PyDict;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyList;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyOps;
import org.example.jython3.runtime.PySet;
import org.example.jython3.runtime.PyStr;
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
                var sb = new StringBuilder();
                for (int i = 0; i < list.items().size(); i++) {
                    if (i > 0) sb.append(s.value());
                    sb.append(list.items().get(i).toString());
                }
                return new PyStr(sb.toString());
            });
            case "replace" -> new PyBuiltinFunction("str.replace", args -> {
                if (args.length < 2) throw PyErrors.error("TypeError", "replace() takes at least 2 arguments");
                if (!(args[0] instanceof PyStr old) || !(args[1] instanceof PyStr rep))
                    throw PyErrors.error("TypeError", "replace() arguments must be strings");
                return new PyStr(s.value().replace(old.value(), rep.value()));
            });
            case "startswith" -> new PyBuiltinFunction("str.startswith", args -> {
                checkArgCount("str.startswith", args, 1);
                if (!(args[0] instanceof PyStr prefix)) throw PyErrors.error("TypeError", "startswith() argument must be str");
                return PyBool.of(s.value().startsWith(prefix.value()));
            });
            case "endswith" -> new PyBuiltinFunction("str.endswith", args -> {
                checkArgCount("str.endswith", args, 1);
                if (!(args[0] instanceof PyStr suffix)) throw PyErrors.error("TypeError", "endswith() argument must be str");
                return PyBool.of(s.value().endsWith(suffix.value()));
            });
            case "find" -> new PyBuiltinFunction("str.find", args -> {
                checkArgCount("str.find", args, 1);
                if (!(args[0] instanceof PyStr sub)) throw PyErrors.error("TypeError", "find() argument must be str");
                return PyInt.of(s.value().indexOf(sub.value()));
            });
            case "count" -> new PyBuiltinFunction("str.count", args -> {
                checkArgCount("str.count", args, 1);
                if (!(args[0] instanceof PyStr sub)) throw PyErrors.error("TypeError", "count() argument must be str");
                int count = 0; int idx = 0;
                while ((idx = s.value().indexOf(sub.value(), idx)) != -1) { count++; idx += sub.value().length(); }
                return PyInt.of(count);
            });
            case "isdigit" -> new PyBuiltinFunction("str.isdigit", args -> PyBool.of(!s.value().isEmpty() && s.value().chars().allMatch(Character::isDigit)));
            case "isalpha" -> new PyBuiltinFunction("str.isalpha", args -> PyBool.of(!s.value().isEmpty() && s.value().chars().allMatch(Character::isLetter)));
            case "isalnum" -> new PyBuiltinFunction("str.isalnum", args -> PyBool.of(!s.value().isEmpty() && s.value().chars().allMatch(Character::isLetterOrDigit)));
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
            default -> throw PyErrors.error("AttributeError", "'set' object has no attribute '" + method + "'");
        };
    }

    public static PyObject listMethod(PyList list, String method) {
        return switch (method) {
            case "append" -> new PyBuiltinFunction("list.append", args -> { checkArgCount("list.append", args, 1); list.items().add(args[0]); return new PyNull(); });
            case "pop" -> new PyBuiltinFunction("list.pop", args -> {
                if (list.items().isEmpty()) throw PyErrors.error("IndexError", "pop from empty list");
                if (args.length > 0) { int i = toInt(args[0]); if (i < 0) i += list.items().size(); return list.items().remove(i); }
                return list.items().removeLast();
            });
            case "extend" -> new PyBuiltinFunction("list.extend", args -> {
                checkArgCount("list.extend", args, 1);
                if (!(args[0] instanceof PyList other)) throw PyErrors.error("TypeError", "extend() argument must be iterable");
                list.items().addAll(other.items());
                return new PyNull();
            });
            case "insert" -> new PyBuiltinFunction("list.insert", args -> {
                if (args.length < 2) throw PyErrors.error("TypeError", "insert() takes 2 arguments");
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
            case "sort" -> new PyBuiltinFunction("list.sort", args -> {
                list.items().sort(PyOps::compareObjects);
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
                for (var entry : dict.itemsList()) items.add(new PyList(entry));
                return new PyList(items);
            });
            case "get" -> new PyBuiltinFunction("dict.get", args -> {
                if (args.length < 1) throw PyErrors.error("TypeError", "get() takes at least 1 argument");
                PyObject val = dict.get(args[0]);
                if (val != null) return val;
                return (args.length > 1) ? args[1] : new PyNull();
            });
            case "update" -> new PyBuiltinFunction("dict.update", args -> {
                checkArgCount("dict.update", args, 1);
                if (!(args[0] instanceof PyDict other)) throw PyErrors.error("TypeError", "update() argument must be a dict");
                for (var key : other.keys()) {
                    dict.put(key, other.get(key));
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
}
