package org.example.jython3.stdlib;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;

import org.example.jython3.bridge.JavaBridge;
import org.example.jython3.runtime.BigInt;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyBuiltinFunction;
import org.example.jython3.runtime.PyCaller;
import org.example.jython3.runtime.PyClass;
import org.example.jython3.runtime.PyDict;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyException;
import org.example.jython3.runtime.PyFloat;
import org.example.jython3.runtime.PyGenerator;
import org.example.jython3.runtime.PyInstance;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyIterator;
import org.example.jython3.runtime.PyJavaObject;
import org.example.jython3.runtime.PyList;
import org.example.jython3.runtime.PyModule;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyOps;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.runtime.PySuper;
import org.example.jython3.runtime.PyTuple;

import static org.example.jython3.runtime.PyOps.checkArgCount;
import static org.example.jython3.runtime.PyOps.compareObjects;
import static org.example.jython3.runtime.PyOps.flattenArgs;
import static org.example.jython3.runtime.PyOps.isTruthy;
import static org.example.jython3.runtime.PyOps.toDouble;
import static org.example.jython3.runtime.PyOps.toInt;
import static org.example.jython3.runtime.PyOps.typeName;

/** Built-in functions installed into a fresh interpreter. */
public final class BuiltinFunctions {
    private BuiltinFunctions() {}

    public static void register(BiConsumer<String, PyObject> define, PyCaller caller,
                                org.example.jython3.security.JavaAccessPolicy javaPolicy) {
        // len(obj)
        define.accept("len", new PyBuiltinFunction("len", args -> {
            checkArgCount("len", args, 1);
            PyObject o = args[0];
            if (o instanceof PyStr s) return new PyInt(s.codePointCount());
            if (o instanceof PyList l) return new PyInt(l.items().size());
            if (o instanceof PyTuple t) return new PyInt(t.items().size());
            if (o instanceof PyDict d) return new PyInt(d.map().size());
            if (o instanceof PyInstance inst) {
                if (inst.hasField("__len__")) {
                    PyObject lenMethod = inst.getField("__len__");
                    return caller.call(lenMethod, new PyObject[0]);
                }
            }
            if (o instanceof PyJavaObject jo) {
                if (jo.object() instanceof List<?> l) return new PyInt(l.size());
                if (jo.object() instanceof Map<?, ?> m) return new PyInt(m.size());
                if (jo.object().getClass().isArray()) return new PyInt(java.lang.reflect.Array.getLength(jo.object()));
            }
            throw PyErrors.error("TypeError", "object of type '" + typeName(o) + "' has no len()");
        }));

        define.accept("super", new PyBuiltinFunction("super", args -> {
            if (args.length == 0) {
                // minimal super() support
                return new PyNull();
            } else if (args.length == 2 && args[0] instanceof PyClass cls && args[1] instanceof PyInstance inst) {
                return new PySuper(cls, inst);
            }
            return new PyNull();
        }));

        // str(obj)
        define.accept("str", new PyBuiltinFunction("str", args -> {
            checkArgCount("str", args, 1);
            if (args[0] instanceof PyException pe) {
                return new PyStr(pe.message());
            }
            return new PyStr(args[0].toString());
        }));

        // print(*objects, sep=' ', end='\n', file=None, flush=False)
        define.accept("print", new PyBuiltinFunction("print", (args, kwargs) -> {
            String sep = " ";
            String end = "\n";
            PyObject sepObj = kwargs.get("sep");
            if (sepObj != null) {
                if (sepObj instanceof PyStr s) sep = s.value();
                else if (sepObj instanceof PyNull) sep = "None";
                else sep = sepObj.toString();
            }
            PyObject endObj = kwargs.get("end");
            if (endObj != null) {
                if (endObj instanceof PyStr s) end = s.value();
                else if (endObj instanceof PyNull) end = "None";
                else end = endObj.toString();
            }
            PyObject fileObj = kwargs.get("file");
            PyObject flushObj = kwargs.get("flush");
            boolean flush = flushObj != null && isTruthy(flushObj);

            for (String k : kwargs.keySet()) {
                if (!k.equals("sep") && !k.equals("end") && !k.equals("file") && !k.equals("flush")) {
                    throw PyErrors.error("TypeError", "print() got an unexpected keyword argument '" + k + "'");
                }
            }
            var sb = new StringBuilder();
            for (int i = 0; i < args.length; i++) {
                if (i > 0) sb.append(sep);
                PyObject o = args[i];
                if (o == null) sb.append("None");
                else if (o instanceof PyException pe) sb.append(pe.message());
                else sb.append(o.toString());
            }
            sb.append(end);
            String output = sb.toString();

            if (fileObj != null && !(fileObj instanceof PyNull)) {
                PyObject writeMethod = null;
                try {
                    if (fileObj instanceof org.example.jython3.runtime.PyInstance inst) {
                        writeMethod = inst.getField("write");
                    } else if (fileObj instanceof org.example.jython3.runtime.PyJavaObject jo) {
                        writeMethod = jo.getMember("write");
                    }
                } catch (InterpreterException ignored) {}
                if (writeMethod == null) {
                    throw PyErrors.error("AttributeError", "'" + typeName(fileObj) + "' object has no attribute 'write'");
                }
                caller.call(writeMethod, new PyObject[]{new PyStr(output)});
                if (flush) {
                    PyObject flushMethod = null;
                    try {
                        if (fileObj instanceof org.example.jython3.runtime.PyInstance inst) {
                            flushMethod = inst.getField("flush");
                        } else if (fileObj instanceof org.example.jython3.runtime.PyJavaObject jo) {
                            flushMethod = jo.getMember("flush");
                        }
                    } catch (InterpreterException ignored) {}
                    if (flushMethod != null) {
                        caller.call(flushMethod, new PyObject[0]);
                    }
                }
            } else {
                System.out.print(output);
                if (flush) {
                    System.out.flush();
                }
            }
            return new PyNull();
        }));

        // int(obj, base=10)
        define.accept("int", new PyBuiltinFunction("int", (args, kwargs) -> {
            if (args.length == 0) return new PyInt(0);
            if (args.length > 2) throw PyErrors.error("TypeError", "int() takes at most 2 arguments (" + args.length + " given)");
            if (args.length == 2 && kwargs.containsKey("base")) {
                throw PyErrors.error("TypeError", "int() got multiple values for argument 'base'");
            }
            int base = 10;
            if (args.length == 2) {
                base = toInt(args[1]);
            } else if (kwargs.containsKey("base")) {
                base = toInt(kwargs.get("base"));
            }
            for (String k : kwargs.keySet()) {
                if (!k.equals("base")) throw PyErrors.error("TypeError", "int() got an unexpected keyword argument '" + k + "'");
            }
            PyObject o = args[0];
            if (o instanceof PyInt i && base == 10) return i;
            if (o instanceof PyFloat f && base == 10) return new PyInt((int) f.value());
            if (o instanceof PyBool b && base == 10) return new PyInt(b.value() ? 1 : 0);
            if (o instanceof PyStr s) {
                try {
                    return new PyInt(Integer.parseInt(s.value().strip(), base));
                } catch (NumberFormatException e) {
                    try {
                        return new BigInt(new java.math.BigInteger(s.value().strip(), base));
                    } catch (Exception ex) {
                        throw PyErrors.error("ValueError", "invalid literal for int() with base " + base + ": '" + s.value() + "'");
                    }
                }
            }
            if (base != 10) throw PyErrors.error("TypeError", "int() can't convert non-string with explicit base");
            throw PyErrors.error("TypeError", "int() argument must be a string or a number, not '" + typeName(o) + "'");
        }));

        // float(obj)
        define.accept("float", new PyBuiltinFunction("float", args -> {
            checkArgCount("float", args, 1);
            return new PyFloat(toDouble(args[0]));
        }));

        // bool(obj)
        define.accept("bool", new PyBuiltinFunction("bool", args -> {
            checkArgCount("bool", args, 1);
            return PyBool.of(isTruthy(args[0]));
        }));

        // type(obj)
        define.accept("type", new PyBuiltinFunction("type", args -> {
            checkArgCount("type", args, 1);
            return new PyStr("<class '" + typeName(args[0]) + "'>");
        }));

        // abs(x)
        define.accept("abs", new PyBuiltinFunction("abs", args -> {
            checkArgCount("abs", args, 1);
            PyObject o = args[0];
            if (o instanceof PyInt i) return new PyInt(Math.abs(i.value()));
            if (o instanceof PyFloat f) return new PyFloat(Math.abs(f.value()));
            throw PyErrors.error("TypeError", "bad operand type for abs(): '" + typeName(o) + "'");
        }));

        // min(a, b, ...) or min(iterable)
        define.accept("min", new PyBuiltinFunction("min", args -> {
            List<PyObject> items = flattenArgs("min", args);
            if (items.isEmpty()) throw PyErrors.error("ValueError", "min() arg is an empty sequence");
            PyObject m = items.get(0);
            for (int i = 1; i < items.size(); i++) {
                if (compareObjects(items.get(i), m) < 0) m = items.get(i);
            }
            return m;
        }));

        // max(a, b, ...) or max(iterable)
        define.accept("max", new PyBuiltinFunction("max", args -> {
            List<PyObject> items = flattenArgs("max", args);
            if (items.isEmpty()) throw PyErrors.error("ValueError", "max() arg is an empty sequence");
            PyObject m = items.get(0);
            for (int i = 1; i < items.size(); i++) {
                if (compareObjects(items.get(i), m) > 0) m = items.get(i);
            }
            return m;
        }));

        // sum(iterable)
        define.accept("sum", new PyBuiltinFunction("sum", args -> {
            checkArgCount("sum", args, 1);
            if (!(args[0] instanceof PyList list)) throw PyErrors.error("TypeError", "sum() argument must be iterable");
            double total = 0;
            boolean hasFloat = false;
            for (PyObject item : list.items()) {
                if (item instanceof PyInt i) total += i.value();
                else if (item instanceof PyFloat f) { total += f.value(); hasFloat = true; }
                else throw PyErrors.error("TypeError", "unsupported operand type for sum()");
            }
            return hasFloat ? new PyFloat(total) : new PyInt((int) total);
        }));

        // list(iterable?)
        define.accept("list", new PyBuiltinFunction("list", args -> {
            if (args.length == 0) return new PyList(new ArrayList<>());
            checkArgCount("list", args, 1);
            PyObject o = args[0];
            if (o instanceof PyList l) return new PyList(new ArrayList<>(l.items()));
            if (o instanceof PyTuple t) return new PyList(new ArrayList<>(t.items()));
            if (o instanceof PyStr s) {
                return new PyList(s.toCodePointList());
            }
            if (o instanceof PyDict d) return new PyList(new ArrayList<>(d.map().keySet()));
            throw PyErrors.error("TypeError", "'" + typeName(o) + "' object is not iterable");
        }));

        // dict()
        define.accept("dict", new PyBuiltinFunction("dict", args -> {
            if (args.length == 0) return new PyDict(new LinkedHashMap<>());
            checkArgCount("dict", args, 1);
            if (args[0] instanceof PyDict d) return new PyDict(new LinkedHashMap<>(d.map()));
            throw PyErrors.error("TypeError", "cannot convert argument to dict");
        }));

        // range(stop) or range(start, stop) or range(start, stop, step)
        define.accept("range", new PyBuiltinFunction("range", args -> {
            int start = 0, stop, step = 1;
            if (args.length == 1) {
                stop = toInt(args[0]);
            } else if (args.length == 2) {
                start = toInt(args[0]);
                stop = toInt(args[1]);
            } else if (args.length == 3) {
                start = toInt(args[0]);
                stop = toInt(args[1]);
                step = toInt(args[2]);
            } else {
                throw PyErrors.error("TypeError", "range expected at most 3 arguments, got " + args.length);
            }
            if (step == 0) throw PyErrors.error("ValueError", "range() arg 3 must not be zero");
            var items = new ArrayList<PyObject>();
            if (step > 0) {
                for (int i = start; i < stop; i += step) items.add(PyInt.of(i));
            } else {
                for (int i = start; i > stop; i += step) items.add(PyInt.of(i));
            }
            return new PyList(items);
        }));

        // sorted(iterable, *, key=None, reverse=False)
        define.accept("sorted", new PyBuiltinFunction("sorted", (args, kwargs) -> {
            if (args.length != 1) {
                throw PyErrors.error("TypeError", "sorted() takes 1 positional argument (" + args.length + " given)");
            }
            boolean reverse = false;
            PyObject reverseObj = kwargs.get("reverse");
            if (reverseObj != null) {
                reverse = isTruthy(reverseObj);
            }
            PyObject keyFn = kwargs.get("key");
            for (String k : kwargs.keySet()) {
                if (!k.equals("reverse") && !k.equals("key")) {
                    throw PyErrors.error("TypeError", "sorted() got an unexpected keyword argument '" + k + "'");
                }
            }
            List<PyObject> items;
            if (args[0] instanceof PyList list) items = new ArrayList<>(list.items());
            else if (args[0] instanceof PyTuple t) items = new ArrayList<>(t.items());
            else if (args[0] instanceof org.example.jython3.runtime.PySet s) items = new ArrayList<>(s.items());
            else if (args[0] instanceof PyStr s) items = s.toCodePointList();
            else if (args[0] instanceof PyDict d) items = new ArrayList<>(d.map().keySet());
            else throw PyErrors.error("TypeError", "sorted() argument must be iterable");

            if (keyFn != null && !(keyFn instanceof PyNull)) {
                items.sort((a, b) -> {
                    PyObject ka = caller.call(keyFn, new PyObject[]{a});
                    PyObject kb = caller.call(keyFn, new PyObject[]{b});
                    return compareObjects(ka, kb);
                });
            } else {
                items.sort(PyOps::compareObjects);
            }
            if (reverse) {
                Collections.reverse(items);
            }
            return new PyList(items);
        }));

        // reversed(iterable)
        define.accept("reversed", new PyBuiltinFunction("reversed", args -> {
            checkArgCount("reversed", args, 1);
            List<PyObject> items;
            if (args[0] instanceof PyList list) items = new ArrayList<>(list.items());
            else if (args[0] instanceof PyStr s) items = s.toCodePointList();
            else if (args[0] instanceof PyDict d) items = new ArrayList<>(d.map().keySet());
            else throw PyErrors.error("TypeError", "reversed() argument must be iterable");
            Collections.reverse(items);
            return new PyList(items);
        }));

        // iter(obj) and next(iterator[, default])
        define.accept("iter", new PyBuiltinFunction("iter", args -> {
            checkArgCount("iter", args, 1);
            return toIterator(args[0], javaPolicy);
        }));

        define.accept("next", new PyBuiltinFunction("next", args -> {
            if (args.length < 1 || args.length > 2) {
                throw PyErrors.error("TypeError", "next expected 1 or 2 arguments, got " + args.length);
            }
            PyObject o = args[0];
            try {
                if (o instanceof PyIterator it) {
                    if (it.hasNext()) return it.next();
                    throw PyErrors.error("StopIteration", "");
                }
                if (o instanceof PyGenerator gen) return gen.next();
                throw PyErrors.error("TypeError", "'" + typeName(o) + "' object is not an iterator");
            } catch (InterpreterException ie) {
                if (args.length == 2 && ie.getMessage().startsWith("StopIteration")) return args[1];
                throw ie;
            }
        }));

        // enumerate(iterable)
        define.accept("enumerate", new PyBuiltinFunction("enumerate", args -> {
            checkArgCount("enumerate", args, 1);
            if (!(args[0] instanceof PyList list)) throw PyErrors.error("TypeError", "enumerate() argument must be iterable");
            var items = new ArrayList<PyObject>();
            for (int i = 0; i < list.items().size(); i++) {
                items.add(new PyList(List.of(new PyInt(i), list.items().get(i))));
            }
            return new PyList(items);
        }));

        // zip(a, b, ...)
        define.accept("zip", new PyBuiltinFunction("zip", args -> {
            if (args.length < 2) throw PyErrors.error("TypeError", "zip() requires at least 2 arguments");
            List<List<PyObject>> lists = new ArrayList<>();
            int minLen = Integer.MAX_VALUE;
            for (PyObject a : args) {
                if (!(a instanceof PyList l)) throw PyErrors.error("TypeError", "zip() argument must be iterable");
                lists.add(l.items());
                minLen = Math.min(minLen, l.items().size());
            }
            var result = new ArrayList<PyObject>();
            for (int i = 0; i < minLen; i++) {
                var tuple = new ArrayList<PyObject>();
                for (var l : lists) tuple.add(l.get(i));
                result.add(new PyList(tuple));
            }
            return new PyList(result);
        }));

        // map(func, iterable)
        define.accept("map", new PyBuiltinFunction("map", args -> map(caller, args)));

        // filter(func, iterable)
        define.accept("filter", new PyBuiltinFunction("filter", args -> filter(caller, args)));

        // tuple([iterable])
        define.accept("tuple", new PyBuiltinFunction("tuple", args -> {
            if (args.length == 0) return PyTuple.EMPTY;
            checkArgCount("tuple", args, 1);
            if (args[0] instanceof PyTuple t) return t;
            if (args[0] instanceof PyList list) return new PyTuple(list.items());
            if (args[0] instanceof PyStr s) return new PyTuple(s.toCodePointList());
            if (args[0] instanceof PyDict d) return new PyTuple(new ArrayList<>(d.map().keySet()));
            throw PyErrors.error("TypeError", "'" + typeName(args[0]) + "' object is not iterable");
        }));

        // isinstance(obj, type_or_tuple)
        define.accept("isinstance", new PyBuiltinFunction("isinstance", args -> {
            checkArgCount("isinstance", args, 2);
            return PyBool.of(checkIsInstance(args[0], args[1]));
        }));

        // issubclass(class, classinfo)
        define.accept("issubclass", new PyBuiltinFunction("issubclass", args -> {
            checkArgCount("issubclass", args, 2);
            return PyBool.of(checkIsSubclass(args[0], args[1]));
        }));

        // repr(obj)
        define.accept("repr", new PyBuiltinFunction("repr", args -> {
            checkArgCount("repr", args, 1);
            PyObject o = args[0];
            if (o instanceof PyStr s) return new PyStr("'" + s.value() + "'");
            return new PyStr(o.toString());
        }));

        // hash(obj)
        define.accept("hash", new PyBuiltinFunction("hash", args -> {
            checkArgCount("hash", args, 1);
            return new PyInt(new org.example.jython3.runtime.PyKey(args[0]).hashCode());
        }));

        // id(obj)
        define.accept("id", new PyBuiltinFunction("id", args -> {
            checkArgCount("id", args, 1);
            return new PyInt(System.identityHashCode(args[0]));
        }));

        // chr(i)
        define.accept("chr", new PyBuiltinFunction("chr", args -> {
            checkArgCount("chr", args, 1);
            int cp = toInt(args[0]);
            if (cp < 0 || cp > 0x10FFFF) throw PyErrors.error("ValueError", "chr() arg not in range(0x110000)");
            return new PyStr(String.valueOf((char) cp));
        }));

        // ord(c)
        define.accept("ord", new PyBuiltinFunction("ord", args -> {
            checkArgCount("ord", args, 1);
            if (!(args[0] instanceof PyStr s) || s.value().length() != 1)
                throw PyErrors.error("TypeError", "ord() expected a character");
            return new PyInt(s.value().charAt(0));
        }));

        // hex, oct, bin
        define.accept("hex", new PyBuiltinFunction("hex", args -> { checkArgCount("hex", args, 1); return new PyStr("0x" + Integer.toHexString(toInt(args[0]))); }));
        define.accept("oct", new PyBuiltinFunction("oct", args -> { checkArgCount("oct", args, 1); return new PyStr("0o" + Integer.toOctalString(toInt(args[0]))); }));
        define.accept("bin", new PyBuiltinFunction("bin", args -> { checkArgCount("bin", args, 1); return new PyStr("0b" + Integer.toBinaryString(toInt(args[0]))); }));

        // round(x, n?)
        define.accept("round", new PyBuiltinFunction("round", args -> {
            if (args.length < 1 || args.length > 2) throw PyErrors.error("TypeError", "round() takes 1 or 2 arguments");
            double val = toDouble(args[0]);
            if (args.length == 1) return new PyInt((int) Math.round(val));
            int digits = toInt(args[1]);
            double factor = Math.pow(10, digits);
            return new PyFloat(Math.round(val * factor) / factor);
        }));

        // hasattr(obj, name)
        define.accept("hasattr", new PyBuiltinFunction("hasattr", args -> {
            checkArgCount("hasattr", args, 2);
            if (!(args[1] instanceof PyStr s)) throw PyErrors.error("TypeError", "attribute name must be string");
            if (args[0] instanceof PyModule mod) return PyBool.of(mod.attrs().containsKey(s.value()));
            if (args[0] instanceof PyDict d) return PyBool.of(d.map().containsKey(new PyStr(s.value())));
            if (args[0] instanceof PyInstance inst) return PyBool.of(inst.hasField(s.value()));
            if (args[0] instanceof PyClass cls) return PyBool.of(cls.hasMember(s.value()));
            return PyBool.FALSE;
        }));

        // getattr(obj, name, default?)
        define.accept("getattr", new PyBuiltinFunction("getattr", args -> {
            if (args.length < 2 || args.length > 3) throw PyErrors.error("TypeError", "getattr expected 2 or 3 arguments");
            if (!(args[1] instanceof PyStr s)) throw PyErrors.error("TypeError", "attribute name must be string");
            if (args[0] instanceof PyModule mod) {
                PyObject val = mod.attrs().get(s.value());
                if (val != null) return val;
            }
            if (args[0] instanceof PyInstance inst) {
                try { return inst.getField(s.value()); }
                catch (InterpreterException ignored) {}
            }
            if (args.length == 3) return args[2];
            throw PyErrors.error("AttributeError", "object has no attribute '" + s.value() + "'");
        }));

        // setattr(obj, name, val)
        define.accept("setattr", new PyBuiltinFunction("setattr", args -> {
            checkArgCount("setattr", args, 3);
            if (!(args[1] instanceof PyStr s)) throw PyErrors.error("TypeError", "attribute name must be string");
            if (args[0] instanceof PyInstance inst) {
                inst.setField(s.value(), args[2]);
                return new PyNull();
            }
            if (args[0] instanceof PyModule mod) {
                mod.attrs().put(s.value(), args[2]);
                return new PyNull();
            }
            throw PyErrors.error("AttributeError", "cannot set attribute on '" + typeName(args[0]) + "'");
        }));

        // input(prompt?)
        define.accept("input", new PyBuiltinFunction("input", args -> {
            if (args.length > 0 && args[0] instanceof PyStr s) System.out.print(s.value());
            try {
                var reader = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
                String line = reader.readLine();
                return line == null ? new PyStr("") : new PyStr(line);
            } catch (IOException e) {
                throw PyErrors.error("IOError", e.getMessage());
            }
        }));
    }

    /** Converts a supported iterable into a fresh iterator. */
    private static PyIterator toIterator(PyObject o, org.example.jython3.security.JavaAccessPolicy policy) {
        if (o instanceof PyIterator it) return it;
        if (o instanceof PyList list) return new PyIterator(list.items(), 0);
        if (o instanceof PyTuple tuple) return new PyIterator(tuple.items(), 0);
        if (o instanceof PyStr s) {
            return s.codePointIterator();
        }
        if (o instanceof PyDict d) return new PyIterator(new ArrayList<>(d.map().keySet()), 0);
        if (o instanceof PyGenerator gen) {
            return PyIterator.fromGenerator(gen);
        }
        if (o instanceof PyJavaObject jo && jo.object() instanceof Iterable<?> iterable) {
            return PyIterator.fromJavaIterable(iterable, policy, null);
        }
        throw PyErrors.error("TypeError", "'" + typeName(o) + "' object is not iterable");
    }

    private static PyObject map(PyCaller caller, PyObject[] args) {
        if (args.length != 2) throw PyErrors.error("TypeError", "map() requires exactly 2 arguments");
        if (!(args[1] instanceof PyList list)) throw PyErrors.error("TypeError", "map() argument 2 must be iterable");
        var result = new ArrayList<PyObject>();
        for (var item : list.items()) {
            result.add(caller.call(args[0], new PyObject[]{item}));
        }
        return new PyList(result);
    }

    private static PyObject filter(PyCaller caller, PyObject[] args) {
        if (args.length != 2) throw PyErrors.error("TypeError", "filter() requires exactly 2 arguments");
        if (!(args[1] instanceof PyList list)) throw PyErrors.error("TypeError", "filter() argument 2 must be iterable");
        var result = new ArrayList<PyObject>();
        for (var item : list.items()) {
            PyObject val = caller.call(args[0], new PyObject[]{item});
            if (isTruthy(val)) result.add(item);
        }
        return new PyList(result);
    }

    private static boolean checkIsInstance(PyObject obj, PyObject typeArg) {
        // Tuple of types: isinstance(x, (A, B))
        if (typeArg instanceof PyTuple tuple) {
            for (PyObject item : tuple.items()) {
                if (checkIsInstance(obj, item)) return true;
            }
            return false;
        }
        if (typeArg instanceof PyList tupleLike) {
            for (PyObject item : tupleLike.items()) {
                if (checkIsInstance(obj, item)) return true;
            }
            return false;
        }

        // User-defined class or exception class
        if (typeArg instanceof PyClass cls) {
            if (obj instanceof PyException pe) {
                return PyErrors.matches(pe, cls);
            }
            if (obj instanceof PyInstance inst) {
                return inst.isInstanceOf(cls);
            }
            if (obj instanceof PyClass objClass) {
                return objClass.isSubclassOf(cls);
            }
            return false;
        }

        // Built-in types registered as PyBuiltinFunction constructors
        if (typeArg instanceof PyBuiltinFunction bf) {
            return switch (bf.name()) {
                // In Python, bool is a subclass of int: isinstance(True, int) is True
                case "int"    -> (obj instanceof PyInt || obj instanceof BigInt || obj instanceof PyBool);
                case "bool"   -> (obj instanceof PyBool);
                case "float"  -> (obj instanceof PyFloat);
                case "str"    -> (obj instanceof PyStr);
                case "list"   -> (obj instanceof PyList);
                case "dict"   -> (obj instanceof PyDict);
                case "tuple"  -> (obj instanceof PyTuple);
                case "object" -> true;
                default       -> false;
            };
        }

        // Fallback for string type name comparison
        if (typeArg instanceof PyStr s) {
            return typeName(obj).equalsIgnoreCase(s.value());
        }

        throw PyErrors.error("TypeError",
                "isinstance() arg 2 must be a type, a tuple of types, or a union");
    }

    private static boolean checkIsSubclass(PyObject derived, PyObject target) {
        // Tuple of targets: issubclass(C, (A, B))
        if (target instanceof PyTuple tuple) {
            for (PyObject item : tuple.items()) {
                if (checkIsSubclass(derived, item)) return true;
            }
            return false;
        }
        if (target instanceof PyList tupleLike) {
            for (PyObject item : tupleLike.items()) {
                if (checkIsSubclass(derived, item)) return true;
            }
            return false;
        }

        // User-defined classes
        if (derived instanceof PyClass c1 && target instanceof PyClass c2) {
            return c1.isSubclassOf(c2);
        }

        // Built-in types
        if (derived instanceof PyBuiltinFunction b1 && target instanceof PyBuiltinFunction b2) {
            String dName = b1.name();
            String tName = b2.name();
            if (dName.equals(tName)) return true;
            // In Python, bool is a subclass of int
            if ("bool".equals(dName) && "int".equals(tName)) return true;
            if ("object".equals(tName)) return true;
            return false;
        }

        if (target instanceof PyBuiltinFunction b2 && "object".equals(b2.name())) {
            return derived instanceof PyClass || derived instanceof PyBuiltinFunction;
        }

        throw PyErrors.error("TypeError",
                "issubclass() arg 1 must be a class, and arg 2 must be a class, a tuple of classes, or a union");
    }
}
