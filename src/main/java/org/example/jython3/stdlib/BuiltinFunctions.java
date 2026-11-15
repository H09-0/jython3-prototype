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
import org.example.jython3.runtime.PyBoundMethod;
import org.example.jython3.runtime.PyBuiltinFunction;
import org.example.jython3.runtime.PyByteArray;
import org.example.jython3.runtime.PyBytes;
import org.example.jython3.runtime.PyCaller;
import org.example.jython3.runtime.PyClass;
import org.example.jython3.runtime.PyClassMethod;
import org.example.jython3.runtime.PyCodeInfo;
import org.example.jython3.runtime.PyDict;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyException;
import org.example.jython3.runtime.PyFloat;
import org.example.jython3.runtime.PyFrameInfo;
import org.example.jython3.runtime.PyFunction;
import org.example.jython3.runtime.PyTraceback;
import org.example.jython3.runtime.PyGenerator;
import org.example.jython3.runtime.PyInstance;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyIterator;
import org.example.jython3.runtime.PyJavaClass;
import org.example.jython3.runtime.PyJavaMethod;
import org.example.jython3.runtime.PyJavaObject;
import org.example.jython3.runtime.PyJavaPackage;
import org.example.jython3.runtime.PyList;
import org.example.jython3.runtime.PyModule;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyOps;
import org.example.jython3.runtime.PyProperty;
import org.example.jython3.runtime.PyRange;
import org.example.jython3.runtime.PySlice;
import org.example.jython3.runtime.PyStaticMethod;
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

    /** Marker class for bare {@code object()} instances; maps back to the interpreter's {@code object} type. */
    public static final PyClass OBJECT_CLASS = new PyClass("object", null, Collections.emptyMap());

    private static PyBuiltinFunction uninstantiableType(String name) {
        return PyBuiltinFunction.builtinType(name, args -> {
            throw PyErrors.error("TypeError", "cannot create '" + name + "' instances");
        });
    }

    public static final PyBuiltinFunction NONE_TYPE = uninstantiableType("NoneType");
    public static final PyBuiltinFunction FUNCTION_TYPE = uninstantiableType("function");
    public static final PyBuiltinFunction BUILTIN_FN_TYPE = uninstantiableType("builtin_function_or_method");
    public static final PyBuiltinFunction METHOD_TYPE = uninstantiableType("method");
    public static final PyBuiltinFunction GENERATOR_TYPE = uninstantiableType("generator");
    public static final PyBuiltinFunction COROUTINE_TYPE = uninstantiableType("coroutine");
    public static final PyBuiltinFunction ITERATOR_TYPE = uninstantiableType("iterator");
    public static final PyBuiltinFunction MODULE_TYPE = uninstantiableType("module");
    public static final PyBuiltinFunction SLICE_TYPE = PyBuiltinFunction.builtinType("slice", args -> {
        if (args.length == 1) {
            return new PySlice(PyNull.INSTANCE, args[0], PyNull.INSTANCE);
        }
        if (args.length == 2) {
            return new PySlice(args[0], args[1], PyNull.INSTANCE);
        }
        if (args.length == 3) {
            return new PySlice(args[0], args[1], args[2]);
        }
        throw PyErrors.error("TypeError", "slice() takes 1 to 3 arguments (" + args.length + " given)");
    });
    public static final PyBuiltinFunction TRACEBACK_TYPE = uninstantiableType("traceback");
    public static final PyBuiltinFunction FRAME_TYPE = uninstantiableType("frame");
    public static final PyBuiltinFunction CODE_TYPE = uninstantiableType("code");

    public static void register(BiConsumer<String, PyObject> define, PyCaller caller,
                                org.example.jython3.security.JavaAccessPolicy javaPolicy) {
        register(define, caller, javaPolicy, new java.util.HashMap<>());
    }

    public static void register(BiConsumer<String, PyObject> define, PyCaller caller,
                                org.example.jython3.security.JavaAccessPolicy javaPolicy,
                                Map<String, PyBuiltinFunction> builtinTypes) {
        BiConsumer<String, PyBuiltinFunction> defineType = (name, fn) -> {
            builtinTypes.put(name, fn);
            define.accept(name, fn);
        };
        // len(obj)
        define.accept("len", new PyBuiltinFunction("len", args -> {
            checkArgCount("len", args, 1);
            PyObject o = args[0];
            if (o instanceof PyStr s) return new PyInt(s.codePointCount());
            if (o instanceof PyList l) return new PyInt(l.items().size());
            if (o instanceof PyTuple t) return new PyInt(t.items().size());
            if (o instanceof PyDict d) return new PyInt(d.size());
            if (o instanceof org.example.jython3.runtime.PySet set) return new PyInt(set.size());
            if (o instanceof org.example.jython3.runtime.PyFrozenSet fs) return new PyInt(fs.size());
            if (o instanceof PyRange r) {
                java.math.BigInteger lb = r.lengthBig();
                return lb.bitLength() < 32 ? PyInt.of(lb.intValue()) : new BigInt(lb);
            }
            if (o instanceof PyBytes b) return PyInt.of(b.length());
            if (o instanceof PyByteArray ba) return PyInt.of(ba.length());
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
                return caller.resolveZeroArgSuper();
            } else if (args.length == 2 && args[0] instanceof PyClass cls && args[1] instanceof PyInstance inst) {
                return new PySuper(cls, inst);
            }
            throw PyErrors.error("TypeError", "super() expects 0 or 2 arguments (type, obj)");
        }));

        // str(obj)
        defineType.accept("str", PyBuiltinFunction.builtinType("str", args -> {
            if (args.length == 0) return new PyStr("");
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
            if (sepObj != null && !(sepObj instanceof PyNull)) {
                if (sepObj instanceof PyStr s) sep = s.value();
                else throw PyErrors.error("TypeError", "sep must be None or a string, not " + typeName(sepObj));
            }
            PyObject endObj = kwargs.get("end");
            if (endObj != null && !(endObj instanceof PyNull)) {
                if (endObj instanceof PyStr s) end = s.value();
                else throw PyErrors.error("TypeError", "end must be None or a string, not " + typeName(endObj));
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
                java.io.PrintStream out = caller != null ? caller.out() : System.out;
                out.print(output);
                if (flush) {
                    out.flush();
                }
            }
            return new PyNull();
        }));

        // int(obj, base=10)
        defineType.accept("int", PyBuiltinFunction.builtinType("int", (args, kwargs) -> {
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
            if (o instanceof BigInt bi && base == 10) return bi;
            if (o instanceof PyFloat f && base == 10) return new PyInt((int) f.value());
            if (o instanceof PyBool b && base == 10) return new PyInt(b.value() ? 1 : 0);
            if (o instanceof PyStr s) {
                String stripped = s.value().strip();
                if (stripped.length() > 10_000) {
                    throw PyErrors.error("ValueError",
                            "Exceeds the limit (10000 digits) for integer string conversion");
                }
                try {
                    return new PyInt(Integer.parseInt(stripped, base));
                } catch (NumberFormatException e) {
                    try {
                        return new BigInt(new java.math.BigInteger(stripped, base));
                    } catch (Exception ex) {
                        throw PyErrors.error("ValueError", "invalid literal for int() with base " + base + ": '" + s.value() + "'");
                    }
                }
            }
            if (base != 10) throw PyErrors.error("TypeError", "int() can't convert non-string with explicit base");
            throw PyErrors.error("TypeError", "int() argument must be a string or a number, not '" + typeName(o) + "'");
        }));

        // float(obj)
        defineType.accept("float", PyBuiltinFunction.builtinType("float", args -> {
            if (args.length == 0) return new PyFloat(0.0);
            checkArgCount("float", args, 1);
            return new PyFloat(toDouble(args[0]));
        }));

        // bool(obj)
        defineType.accept("bool", PyBuiltinFunction.builtinType("bool", args -> {
            if (args.length == 0) return PyBool.FALSE;
            checkArgCount("bool", args, 1);
            return PyBool.of(isTruthy(args[0]));
        }));

        define.accept("NotImplemented", org.example.jython3.runtime.PyNotImplemented.INSTANCE);

        // object()
        defineType.accept("object", PyBuiltinFunction.builtinType("object", args -> {
            checkArgCount("object", args, 0);
            return new PyInstance(OBJECT_CLASS);
        }));

        // type(obj) or type(name, bases, dict)
        defineType.accept("type", PyBuiltinFunction.builtinType("type", args -> {
            if (args.length == 1) {
                return typeOf(args[0], builtinTypes);
            }
            if (args.length == 3) {
                if (!(args[0] instanceof PyStr nameStr)) {
                    throw PyErrors.error("TypeError", "type() argument 1 must be str, not " + typeName(args[0]));
                }
                if (!(args[1] instanceof PyTuple basesTuple)) {
                    throw PyErrors.error("TypeError", "type() argument 2 must be tuple, not " + typeName(args[1]));
                }
                if (!(args[2] instanceof PyDict nsDict)) {
                    throw PyErrors.error("TypeError", "type() argument 3 must be dict, not " + typeName(args[2]));
                }
                var bases = new ArrayList<PyClass>();
                for (PyObject b : basesTuple.items()) {
                    if (b instanceof PyClass bc) {
                        bases.add(bc);
                    } else if (b instanceof PyBuiltinFunction bf && bf.isType() && "object".equals(bf.name())) {
                        // 'object' is the implicit base of all PyClass instances
                    } else {
                        throw PyErrors.error("TypeError", "type() bases must be classes, not " + typeName(b));
                    }
                }
                var members = new java.util.HashMap<String, PyObject>();
                for (var entry : nsDict.map().entrySet()) {
                    PyObject k = entry.getKey();
                    if (!(k instanceof PyStr ks)) {
                        throw PyErrors.error("TypeError", "type() namespace keys must be str");
                    }
                    members.put(ks.value(), entry.getValue());
                }
                return new PyClass(nameStr.value(), bases.isEmpty() ? null : bases.get(0), members, bases);
            }
            throw PyErrors.error("TypeError", "type() takes 1 or 3 arguments");
        }));

        // property(fget=None, fset=None, fdel=None, doc=None)
        defineType.accept("property", PyBuiltinFunction.builtinType("property", (args, kwargs) -> {
            if (args.length > 4) {
                throw PyErrors.error("TypeError", "property() takes at most 4 arguments (" + args.length + " given)");
            }
            PyObject fget = args.length >= 1 ? args[0] : kwargs.getOrDefault("fget", PyNull.INSTANCE);
            PyObject fset = args.length >= 2 ? args[1] : kwargs.getOrDefault("fset", PyNull.INSTANCE);
            PyObject fdel = args.length >= 3 ? args[2] : kwargs.getOrDefault("fdel", PyNull.INSTANCE);
            PyObject docObj = args.length >= 4 ? args[3] : kwargs.getOrDefault("doc", PyNull.INSTANCE);
            String doc = (docObj instanceof PyStr s) ? s.value() : null;
            return new PyProperty(
                    fget instanceof PyNull ? null : fget,
                    fset instanceof PyNull ? null : fset,
                    fdel instanceof PyNull ? null : fdel,
                    doc);
        }));

        // staticmethod(func)
        defineType.accept("staticmethod", PyBuiltinFunction.builtinType("staticmethod", args -> {
            checkArgCount("staticmethod", args, 1);
            return new PyStaticMethod(args[0]);
        }));

        // classmethod(func)
        defineType.accept("classmethod", PyBuiltinFunction.builtinType("classmethod", args -> {
            checkArgCount("classmethod", args, 1);
            return new PyClassMethod(args[0]);
        }));

        // abs(x)
        define.accept("abs", new PyBuiltinFunction("abs", args -> {
            checkArgCount("abs", args, 1);
            PyObject o = args[0];
            if (o instanceof PyInt i) {
                if (i.value() == Integer.MIN_VALUE) {
                    return new BigInt(java.math.BigInteger.valueOf(Integer.MIN_VALUE).abs());
                }
                return PyInt.of(Math.abs(i.value()));
            }
            if (o instanceof BigInt bi) return new BigInt(bi.value().abs());
            if (o instanceof PyBool b) return b.value() ? PyInt.ONE : PyInt.ZERO;
            if (o instanceof PyFloat f) return new PyFloat(Math.abs(f.value()));
            throw PyErrors.error("TypeError", "bad operand type for abs(): '" + typeName(o) + "'");
        }));

        // pow(base, exp[, mod])
        define.accept("pow", new PyBuiltinFunction("pow", args -> {
            if (args.length < 2 || args.length > 3) {
                throw PyErrors.error("TypeError", "pow() takes 2 or 3 arguments (" + args.length + " given)");
            }
            if (args.length == 2 || args[2] instanceof PyNull) {
                return org.example.jython3.runtime.ArithmeticOps.pow(args[0], args[1], caller.executionLimits());
            }
            java.math.BigInteger base = toBigIntStrict(args[0], "pow");
            java.math.BigInteger exp = toBigIntStrict(args[1], "pow");
            java.math.BigInteger mod = toBigIntStrict(args[2], "pow");
            if (mod.signum() == 0) {
                throw PyErrors.error("ValueError", "pow() 3rd argument cannot be 0");
            }
            if (exp.signum() < 0) {
                try {
                    return toPyInteger(base.modPow(exp, mod.abs()));
                } catch (ArithmeticException ae) {
                    throw PyErrors.error("ValueError", "base is not invertible for the given modulus");
                }
            }
            java.math.BigInteger res = base.modPow(exp, mod.abs());
            if (mod.signum() < 0 && res.signum() != 0) {
                res = res.add(mod);
            }
            return toPyInteger(res);
        }));

        // divmod(a, b)
        define.accept("divmod", new PyBuiltinFunction("divmod", args -> {
            checkArgCount("divmod", args, 2);
            PyObject q = org.example.jython3.runtime.ArithmeticOps.floorDiv(args[0], args[1]);
            PyObject r = org.example.jython3.runtime.ArithmeticOps.mod(args[0], args[1]);
            return new PyTuple(List.of(q, r));
        }));

        // slice(stop) or slice(start, stop[, step])
        defineType.accept("slice", SLICE_TYPE);

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

        // sum(iterable, start=0)
        define.accept("sum", new PyBuiltinFunction("sum", (args, kwargs) -> {
            if (args.length < 1 || args.length > 2) {
                throw PyErrors.error("TypeError", "sum() takes at most 2 arguments (" + args.length + " given)");
            }
            if (args.length == 2 && kwargs.containsKey("start")) {
                throw PyErrors.error("TypeError", "sum() got multiple values for argument 'start'");
            }
            for (String k : kwargs.keySet()) {
                if (!"start".equals(k)) {
                    throw PyErrors.error("TypeError", "sum() got an unexpected keyword argument '" + k + "'");
                }
            }
            PyObject total = args.length == 2 ? args[1] : (kwargs.containsKey("start") ? kwargs.get("start") : PyInt.of(0));
            if (total instanceof PyStr) {
                throw PyErrors.error("TypeError", "sum() can't sum strings [use ''.join(seq) instead]");
            }
            PyIterator it = toIterator(args[0], javaPolicy);
            var ctx = org.example.jython3.security.ExecutionContext.current();
            var limits = caller.executionLimits();
            int ticks = 0;
            while (it.hasNext()) {
                if (ctx != null && (++ticks & 0x3F) == 0) ctx.stepInstruction();
                total = org.example.jython3.runtime.ArithmeticOps.add(total, it.next(), limits);
            }
            return total;
        }));

        // bytes([source[, encoding[, errors]]])
        defineType.accept("bytes", PyBuiltinFunction.builtinType("bytes", args -> {
            if (args.length == 0) return PyBytes.EMPTY;
            return PyBytes.wrapOwned(buildBytesArray(args, javaPolicy, caller.executionLimits().maxStringLength()));
        }));

        // bytearray([source[, encoding[, errors]]])
        defineType.accept("bytearray", PyBuiltinFunction.builtinType("bytearray", args -> {
            if (args.length == 0) return new PyByteArray();
            return new PyByteArray(buildBytesArray(args, javaPolicy, caller.executionLimits().maxStringLength()));
        }));
        // list(iterable?)
        defineType.accept("list", PyBuiltinFunction.builtinType("list", args -> {
            if (args.length == 0) return new PyList(new ArrayList<>());
            checkArgCount("list", args, 1);
            return new PyList(collectIterable(args[0], javaPolicy));
        }));

        // set() / set(iterable) — elements keyed by PyKey (unhashable elements raise TypeError)
        defineType.accept("set", PyBuiltinFunction.builtinType("set", args -> {
            if (args.length == 0) return new org.example.jython3.runtime.PySet();
            checkArgCount("set", args, 1);
            return new org.example.jython3.runtime.PySet(collectIterable(args[0], javaPolicy));
        }));

        // frozenset() / frozenset(iterable) — immutable, hashable set keyed by PyKey
        defineType.accept("frozenset", PyBuiltinFunction.builtinType("frozenset", args -> {
            if (args.length == 0) return org.example.jython3.runtime.PyFrozenSet.EMPTY;
            checkArgCount("frozenset", args, 1);
            if (args[0] instanceof org.example.jython3.runtime.PyFrozenSet fs) return fs;
            return new org.example.jython3.runtime.PyFrozenSet(collectIterable(args[0], javaPolicy));
        }));

        // dict([mapping_or_iterable], **kwargs)
        defineType.accept("dict", PyBuiltinFunction.builtinType("dict", (args, kwargs) -> {
            if (args.length > 1) {
                throw PyErrors.error("TypeError", "dict expected at most 1 argument, got " + args.length);
            }
            var map = new LinkedHashMap<org.example.jython3.runtime.PyKey, PyObject>();
            var d = new PyDict();
            if (args.length == 1) {
                if (args[0] instanceof PyDict src) {
                    for (var e : src.map().entrySet()) d.put(e.getKey(), e.getValue());
                } else {
                    List<PyObject> pairs = collectIterable(args[0], javaPolicy);
                    for (PyObject pair : pairs) {
                        List<PyObject> kv = collectIterable(pair, javaPolicy);
                        if (kv.size() != 2) {
                            throw PyErrors.error("ValueError",
                                    "dictionary update sequence element has length " + kv.size() + "; 2 is required");
                        }
                        d.put(kv.get(0), kv.get(1));
                    }
                }
            }
            for (var kw : kwargs.entrySet()) {
                d.put(new PyStr(kw.getKey()), kw.getValue());
            }
            return d;
        }));

        // range(stop) or range(start, stop) or range(start, stop, step) -> lazy O(1) PyRange
        defineType.accept("range", PyBuiltinFunction.builtinType("range", args -> {
            java.math.BigInteger start = java.math.BigInteger.ZERO;
            java.math.BigInteger stop;
            java.math.BigInteger step = java.math.BigInteger.ONE;
            if (args.length == 1) {
                stop = toBigIntStrict(args[0], "range");
            } else if (args.length == 2) {
                start = toBigIntStrict(args[0], "range");
                stop = toBigIntStrict(args[1], "range");
            } else if (args.length == 3) {
                start = toBigIntStrict(args[0], "range");
                stop = toBigIntStrict(args[1], "range");
                step = toBigIntStrict(args[2], "range");
            } else {
                throw PyErrors.error("TypeError", "range expected at most 3 arguments, got " + args.length);
            }
            return new PyRange(start, stop, step);
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
            List<PyObject> items = collectIterable(args[0], javaPolicy);

            if (keyFn != null && !(keyFn instanceof PyNull)) {
                record KeyedItem(PyObject key, PyObject item) {}
                var decorated = new ArrayList<KeyedItem>(items.size());
                for (PyObject item : items) {
                    decorated.add(new KeyedItem(caller.call(keyFn, new PyObject[]{item}), item));
                }
                decorated.sort((a, b) -> compareObjects(a.key(), b.key()));
                items = new ArrayList<>(decorated.size());
                for (KeyedItem ki : decorated) {
                    items.add(ki.item());
                }
            } else {
                items.sort(PyOps::compareObjects);
            }
            if (reverse) {
                Collections.reverse(items);
            }
            return new PyList(items);
        }));

        // reversed(seq)
        define.accept("reversed", new PyBuiltinFunction("reversed", args -> {
            checkArgCount("reversed", args, 1);
            PyObject seq = args[0];
            if (seq instanceof PyRange r) {
                return r.reversedIterator();
            }
            if (seq instanceof PyInstance inst && inst.hasField("__reversed__")) {
                return toIterator(caller.call(inst.getField("__reversed__"), new PyObject[0]), javaPolicy);
            }
            List<PyObject> items = collectIterable(seq, javaPolicy);
            Collections.reverse(items);
            return new PyList(items);
        }));

        // iter(obj) or iter(callable, sentinel) and next(iterator[, default])
        define.accept("iter", new PyBuiltinFunction("iter", args -> {
            if (args.length == 1) {
                if (args[0] instanceof PyGenerator gen) return gen;
                return toIterator(args[0], javaPolicy);
            }
            if (args.length == 2) {
                PyObject fn = args[0];
                PyObject sentinel = args[1];
                return new PyIterator(new java.util.Iterator<PyObject>() {
                    private PyObject buffered = null;
                    private boolean hasBuffered = false;
                    private boolean exhausted = false;

                    @Override
                    public boolean hasNext() {
                        if (hasBuffered) return true;
                        if (exhausted) return false;
                        try {
                            PyObject nextVal = caller.call(fn, new PyObject[0]);
                            if (PyOps.pyEquals(nextVal, sentinel)) {
                                exhausted = true;
                                return false;
                            }
                            buffered = nextVal;
                            hasBuffered = true;
                            return true;
                        } catch (InterpreterException ie) {
                            if ("StopIteration".equals(ie.typeName())) {
                                exhausted = true;
                                return false;
                            }
                            exhausted = true;
                            throw ie;
                        }
                    }

                    @Override
                    public PyObject next() {
                        if (!hasNext()) throw new java.util.NoSuchElementException();
                        PyObject out = buffered;
                        buffered = null;
                        hasBuffered = false;
                        return out;
                    }
                });
            }
            throw PyErrors.error("TypeError", "iter expected at most 2 arguments, got " + args.length);
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
                if (o instanceof PyInstance inst) {
                    PyObject nextFn = inst.pyClass().lookupMember("__next__");
                    if (nextFn != null) {
                        return caller.call(nextFn, new PyObject[]{inst});
                    }
                }
                throw PyErrors.error("TypeError", "'" + typeName(o) + "' object is not an iterator");
            } catch (InterpreterException ie) {
                if (args.length == 2 && ie.getMessage().startsWith("StopIteration")) return args[1];
                throw ie;
            }
        }));

        // enumerate(iterable, start=0)
        define.accept("enumerate", new PyBuiltinFunction("enumerate", (args, kwargs) -> {
            if (args.length < 1 || args.length > 2) {
                throw PyErrors.error("TypeError", "enumerate() takes 1 or 2 arguments (" + args.length + " given)");
            }
            if (args.length == 2 && kwargs.containsKey("start")) {
                throw PyErrors.error("TypeError", "enumerate() got multiple values for argument 'start'");
            }
            for (String k : kwargs.keySet()) {
                if (!"start".equals(k)) {
                    throw PyErrors.error("TypeError", "enumerate() got an unexpected keyword argument '" + k + "'");
                }
            }
            int start = 0;
            if (args.length == 2) start = toInt(args[1]);
            else if (kwargs.containsKey("start")) start = toInt(kwargs.get("start"));
            List<PyObject> src = collectIterable(args[0], javaPolicy);
            var items = new ArrayList<PyObject>(src.size());
            for (int i = 0; i < src.size(); i++) {
                items.add(new PyTuple(List.of(PyInt.of(start + i), src.get(i))));
            }
            return new PyList(items);
        }));

        // zip(*iterables, strict=False) (PEP 618)
        define.accept("zip", new PyBuiltinFunction("zip", (args, kwargs) -> {
            for (String k : kwargs.keySet()) {
                if (!"strict".equals(k)) {
                    throw PyErrors.error("TypeError", "zip() got an unexpected keyword argument '" + k + "'");
                }
            }
            boolean strict = kwargs.containsKey("strict") && isTruthy(kwargs.get("strict"));
            if (args.length == 0) return new PyList(new ArrayList<>());
            List<PyIterator> iters = new ArrayList<>(args.length);
            for (PyObject a : args) {
                iters.add(toIterator(a, javaPolicy));
            }
            var ctx = org.example.jython3.security.ExecutionContext.current();
            int maxColl = ctx != null ? ctx.limits().maxCollectionSize() : 100_000_000;
            int ticks = 0;
            var result = new ArrayList<PyObject>();
            while (true) {
                if (ctx != null && (++ticks & 0x3F) == 0) ctx.stepInstruction();
                var tupleItems = new ArrayList<PyObject>(iters.size());
                for (int i = 0; i < iters.size(); i++) {
                    PyIterator it = iters.get(i);
                    if (!it.hasNext()) {
                        if (strict) {
                            if (i > 0) {
                                throw PyErrors.error("ValueError",
                                        "zip() argument " + (i + 1) + " is shorter than argument 1");
                            }
                            for (int j = 1; j < iters.size(); j++) {
                                if (iters.get(j).hasNext()) {
                                    throw PyErrors.error("ValueError",
                                            "zip() argument " + (j + 1) + " is longer than argument 1");
                                }
                            }
                        }
                        return new PyList(result);
                    }
                    tupleItems.add(it.next());
                }
                if (result.size() >= maxColl) {
                    throw PyErrors.error("MemoryError", "zip() exceeds maximum collection limit (" + maxColl + ")");
                }
                result.add(new PyTuple(tupleItems));
            }
        }));

        // map(func, iterable)
        define.accept("map", new PyBuiltinFunction("map", args -> map(caller, javaPolicy, args)));

        // filter(func, iterable)
        define.accept("filter", new PyBuiltinFunction("filter", args -> filter(caller, javaPolicy, args)));

        // all(iterable)
        define.accept("all", new PyBuiltinFunction("all", args -> {
            checkArgCount("all", args, 1);
            var ctx = org.example.jython3.security.ExecutionContext.current();
            int ticks = 0;
            PyIterator it = toIterator(args[0], javaPolicy);
            while (it.hasNext()) {
                if (ctx != null && (++ticks & 0x3F) == 0) ctx.stepInstruction();
                if (!isTruthy(it.next())) return PyBool.FALSE;
            }
            return PyBool.TRUE;
        }));

        // any(iterable)
        define.accept("any", new PyBuiltinFunction("any", args -> {
            checkArgCount("any", args, 1);
            var ctx = org.example.jython3.security.ExecutionContext.current();
            int ticks = 0;
            PyIterator it = toIterator(args[0], javaPolicy);
            while (it.hasNext()) {
                if (ctx != null && (++ticks & 0x3F) == 0) ctx.stepInstruction();
                if (isTruthy(it.next())) return PyBool.TRUE;
            }
            return PyBool.FALSE;
        }));

        // callable(obj)
        define.accept("callable", new PyBuiltinFunction("callable", args -> {
            checkArgCount("callable", args, 1);
            PyObject o = args[0];
            boolean ok = o instanceof PyFunction
                    || o instanceof PyBuiltinFunction
                    || o instanceof PyBoundMethod
                    || o instanceof PyClass
                    || o instanceof PyJavaClass
                    || o instanceof PyJavaMethod
                    || (o instanceof PyInstance inst && inst.pyClass().hasMember("__call__"));
            return PyBool.of(ok);
        }));

        // tuple([iterable])
        defineType.accept("tuple", PyBuiltinFunction.builtinType("tuple", args -> {
            if (args.length == 0) return PyTuple.EMPTY;
            checkArgCount("tuple", args, 1);
            if (args[0] instanceof PyTuple t) return t;
            if (args[0] instanceof PyList list) return new PyTuple(list.items());
            if (args[0] instanceof PyStr s) return new PyTuple(s.toCodePointList());
            if (args[0] instanceof PyDict d) return new PyTuple(new ArrayList<>(d.map().keySet()));
            if (args[0] instanceof org.example.jython3.runtime.PySet s) return new PyTuple(new ArrayList<>(s.items()));
            return new PyTuple(collectIterable(args[0], javaPolicy));
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
            return new PyStr(pyRepr(args[0]));
        }));

        // format(value, format_spec="")
        define.accept("format", new PyBuiltinFunction("format", args -> {
            if (args.length < 1 || args.length > 2) {
                throw PyErrors.error("TypeError", "format() takes 1 or 2 arguments (" + args.length + " given)");
            }
            String spec = "";
            if (args.length == 2) {
                if (!(args[1] instanceof PyStr s)) {
                    throw PyErrors.error("TypeError", "format() argument 2 must be str, not " + typeName(args[1]));
                }
                spec = s.value();
            }
            return new PyStr(formatValue(args[0], spec, caller.executionLimits().maxStringLength()));
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
            return new PyStr(Character.toString(cp));
        }));

        // ord(c)
        define.accept("ord", new PyBuiltinFunction("ord", args -> {
            checkArgCount("ord", args, 1);
            if (!(args[0] instanceof PyStr s) || s.value().codePointCount(0, s.value().length()) != 1)
                throw PyErrors.error("TypeError", "ord() expected a character");
            return PyInt.of(s.value().codePointAt(0));
        }));

        // hex, oct, bin
        define.accept("hex", new PyBuiltinFunction("hex", args -> {
            checkArgCount("hex", args, 1);
            return new PyStr(formatIntBase(toBigIntStrict(args[0], "hex"), 16, "0x"));
        }));
        define.accept("oct", new PyBuiltinFunction("oct", args -> {
            checkArgCount("oct", args, 1);
            return new PyStr(formatIntBase(toBigIntStrict(args[0], "oct"), 8, "0o"));
        }));
        define.accept("bin", new PyBuiltinFunction("bin", args -> {
            checkArgCount("bin", args, 1);
            return new PyStr(formatIntBase(toBigIntStrict(args[0], "bin"), 2, "0b"));
        }));

        // round(x, n?)
        define.accept("round", new PyBuiltinFunction("round", args -> {
            if (args.length < 1 || args.length > 2) throw PyErrors.error("TypeError", "round() takes 1 or 2 arguments");
            if (args.length == 1) {
                if (args[0] instanceof PyInt || args[0] instanceof BigInt) return args[0];
                if (args[0] instanceof PyBool b) return b.value() ? PyInt.ONE : PyInt.ZERO;
                double val = toDouble(args[0]);
                if (Double.isNaN(val) || Double.isInfinite(val)) {
                    throw PyErrors.error(Double.isNaN(val) ? "ValueError" : "OverflowError",
                            "cannot convert float " + val + " to integer");
                }
                java.math.BigInteger rounded = java.math.BigDecimal.valueOf(val)
                        .setScale(0, java.math.RoundingMode.HALF_EVEN)
                        .toBigInteger();
                return toPyInteger(rounded);
            }
            int digits = toInt(args[1]);
            if (args[0] instanceof PyInt || args[0] instanceof BigInt || args[0] instanceof PyBool) {
                java.math.BigInteger bi = toBigIntStrict(args[0], "round");
                if (digits >= 0) return toPyInteger(bi);
                java.math.BigDecimal bd = new java.math.BigDecimal(bi)
                        .setScale(digits, java.math.RoundingMode.HALF_EVEN);
                return toPyInteger(bd.toBigInteger());
            }
            double val = toDouble(args[0]);
            if (Double.isNaN(val) || Double.isInfinite(val)) return args[0];
            if (digits > 308 || digits < -308) return args[0];
            return new PyFloat(java.math.BigDecimal.valueOf(val)
                    .setScale(digits, java.math.RoundingMode.HALF_EVEN).doubleValue());
        }));

        // hasattr(obj, name)
        define.accept("hasattr", new PyBuiltinFunction("hasattr", args -> {
            checkArgCount("hasattr", args, 2);
            if (!(args[1] instanceof PyStr s)) throw PyErrors.error("TypeError", "attribute name must be string");
            String name = s.value();
            if ("__class__".equals(name) && !(args[0] instanceof PyJavaObject)
                    && !(args[0] instanceof PyJavaClass) && !(args[0] instanceof PyJavaPackage)) {
                return PyBool.TRUE;
            }
            if (args[0] instanceof PyModule mod) return PyBool.of(mod.attrs().containsKey(name));
            if (args[0] instanceof PyDict d) return PyBool.of(d.map().containsKey(new PyStr(name)));
            if (args[0] instanceof PyInstance inst) return PyBool.of(inst.hasField(name));
            if (args[0] instanceof PyClass cls) return PyBool.of(cls.hasMember(name) || "__name__".equals(name) || "__mro__".equals(name) || "__bases__".equals(name));
            if (args[0] instanceof PyProperty prop) {
                return PyBool.of("fget".equals(name) || "fset".equals(name) || "fdel".equals(name)
                        || "__doc__".equals(name) || "getter".equals(name) || "setter".equals(name) || "deleter".equals(name));
            }
            if (args[0] instanceof PyException pe) return PyBool.of(pe.pythonAttribute(name) != null);
            if (args[0] instanceof PyTraceback tb) return PyBool.of(tb.pythonAttribute(name) != null);
            if (args[0] instanceof PyFrameInfo fr) return PyBool.of(fr.pythonAttribute(name) != null);
            if (args[0] instanceof PyCodeInfo co) return PyBool.of(co.pythonAttribute(name) != null);
            if (args[0] instanceof PyFunction || args[0] instanceof PyBuiltinFunction || args[0] instanceof PyBoundMethod) {
                return PyBool.of("__name__".equals(name));
            }
            if (args[0] instanceof PyGenerator) {
                return PyBool.of("send".equals(name) || "throw".equals(name) || "close".equals(name));
            }
            if (args[0] instanceof org.example.jython3.runtime.PyCoroutine) {
                return PyBool.of("send".equals(name) || "throw".equals(name) || "close".equals(name) || "__await__".equals(name));
            }
            if (args[0] instanceof PySlice) {
                return PyBool.of("start".equals(name) || "stop".equals(name) || "step".equals(name) || "indices".equals(name));
            }
            if (args[0] instanceof PyRange) {
                return PyBool.of("start".equals(name) || "stop".equals(name) || "step".equals(name)
                        || "count".equals(name) || "index".equals(name));
            }
            if (args[0] instanceof org.example.jython3.runtime.PyHostHandle hh) {
                return PyBool.of(hh.hasAttr(name));
            }
            return PyBool.FALSE;
        }));

        // getattr(obj, name, default?)
        define.accept("getattr", new PyBuiltinFunction("getattr", args -> {
            if (args.length < 2 || args.length > 3) throw PyErrors.error("TypeError", "getattr expected 2 or 3 arguments");
            if (!(args[1] instanceof PyStr s)) throw PyErrors.error("TypeError", "attribute name must be string");
            String name = s.value();
            if (args[0] instanceof PyModule mod) {
                PyObject val = mod.attrs().get(name);
                if (val != null) return val;
            } else if (args[0] instanceof PyInstance inst) {
                if ("__class__".equals(name) && !inst.fields().containsKey(name) && !inst.pyClass().hasMember(name)) {
                    return inst.pyClass() == OBJECT_CLASS ? builtinTypes.get("object") : inst.pyClass();
                }
                try {
                    return inst.getField(name);
                } catch (InterpreterException ignored) {
                    // fall through to default / AttributeError
                }
            } else if (args[0] instanceof PyClass cls) {
                PyObject val = cls.lookupMember(name);
                if (val instanceof PyStaticMethod sm) return sm.callable();
                if (val instanceof PyClassMethod cm) return cm.bind(cls);
                if (val != null) return val;
                if ("__name__".equals(name)) return new PyStr(cls.name());
                if ("__mro__".equals(name)) return classMroTuple(cls, builtinTypes);
                if ("__bases__".equals(name)) return classBasesTuple(cls, builtinTypes);
            } else if (args[0] instanceof PyProperty prop) {
                try {
                    return prop.getAttr(name);
                } catch (InterpreterException ie) {
                    if (!"AttributeError".equals(ie.typeName())) throw ie;
                }
            } else if (args[0] instanceof PyException pe) {
                PyObject val = pe.pythonAttribute(name);
                if (val != null) return val;
            } else if (args[0] instanceof PyTraceback tb) {
                PyObject val = tb.pythonAttribute(name);
                if (val != null) return val;
            } else if (args[0] instanceof PyFrameInfo fr) {
                PyObject val = fr.pythonAttribute(name);
                if (val != null) return val;
            } else if (args[0] instanceof PyCodeInfo co) {
                PyObject val = co.pythonAttribute(name);
                if (val != null) return val;
            } else if (args[0] instanceof PyFunction fn && "__name__".equals(name)) {
                return new PyStr(fn.name());
            } else if (args[0] instanceof PyBuiltinFunction bf && "__name__".equals(name)) {
                return new PyStr(bf.name());
            } else if (args[0] instanceof PyBoundMethod bm && "__name__".equals(name)) {
                return new PyStr(bm.fn().name());
            } else if (args[0] instanceof PyGenerator gen) {
                try {
                    return BuiltinMethodDispatcher.generatorMethod(gen, name);
                } catch (InterpreterException ie) {
                    if (!"AttributeError".equals(ie.typeName())) throw ie;
                }
            } else if (args[0] instanceof org.example.jython3.runtime.PyCoroutine coro) {
                try {
                    return BuiltinMethodDispatcher.coroutineMethod(coro, name);
                } catch (InterpreterException ie) {
                    if (!"AttributeError".equals(ie.typeName())) throw ie;
                }
            } else if (args[0] instanceof PySlice sl) {
                try {
                    return BuiltinMethodDispatcher.sliceAttr(sl, name);
                } catch (InterpreterException ie) {
                    if (!"AttributeError".equals(ie.typeName())) throw ie;
                }
            } else if (args[0] instanceof PyRange r) {
                try {
                    return BuiltinMethodDispatcher.rangeAttr(r, name);
                } catch (InterpreterException ie) {
                    if (!"AttributeError".equals(ie.typeName())) throw ie;
                }
            } else if (args[0] instanceof org.example.jython3.runtime.PyHostHandle hh) {
                PyObject val = hh.getAttr(name);
                if (val != null) return val;
            } else if (args[0] instanceof PyJavaObject jo) {
                try {
                    return jo.getMember(name);
                } catch (InterpreterException ie) {
                    if (!"AttributeError".equals(ie.typeName())) throw ie;
                }
            } else if (args[0] instanceof PyJavaClass jc) {
                try {
                    return jc.getMember(name);
                } catch (InterpreterException ie) {
                    if (!"AttributeError".equals(ie.typeName())) throw ie;
                }
            }
            if ("__class__".equals(name) && !(args[0] instanceof PyJavaObject)
                    && !(args[0] instanceof PyJavaClass) && !(args[0] instanceof PyJavaPackage)) {
                return typeOf(args[0], builtinTypes);
            }
            if (args.length == 3) return args[2];
            throw PyErrors.error("AttributeError", "object has no attribute '" + name + "'");
        }));

        // setattr(obj, name, val)
        define.accept("setattr", new PyBuiltinFunction("setattr", args -> {
            checkArgCount("setattr", args, 3);
            if (!(args[1] instanceof PyStr s)) throw PyErrors.error("TypeError", "attribute name must be string");
            if (args[0] instanceof PyInstance inst) {
                inst.setField(s.value(), args[2]);
                return new PyNull();
            }
            if (args[0] instanceof org.example.jython3.runtime.PyClass cls) {
                cls.setMember(s.value(), args[2]);
                return new PyNull();
            }
            if (args[0] instanceof PyModule mod) {
                mod.attrs().put(s.value(), args[2]);
                return new PyNull();
            }
            if (args[0] instanceof org.example.jython3.runtime.PyHostHandle hh) {
                hh.setAttr(s.value(), args[2]);
                return new PyNull();
            }
            throw PyErrors.error("AttributeError", "cannot set attribute on '" + typeName(args[0]) + "'");
        }));

        // delattr(obj, name)
        define.accept("delattr", new PyBuiltinFunction("delattr", args -> {
            checkArgCount("delattr", args, 2);
            if (!(args[1] instanceof PyStr s)) throw PyErrors.error("TypeError", "attribute name must be string");
            String name = s.value();
            if (args[0] instanceof PyInstance inst) {
                if (!inst.deleteField(name)) {
                    throw PyErrors.error("AttributeError", "'" + inst.pyClass().name() + "' object has no attribute '" + name + "'");
                }
                return new PyNull();
            }
            if (args[0] instanceof PyClass cls) {
                if (cls.removeMember(name) == null) {
                    throw PyErrors.error("AttributeError", "type object '" + cls.name() + "' has no attribute '" + name + "'");
                }
                return new PyNull();
            }
            if (args[0] instanceof PyModule mod) {
                if (mod.attrs().remove(name) == null) {
                    throw PyErrors.error("AttributeError", "module '" + mod.name() + "' has no attribute '" + name + "'");
                }
                return new PyNull();
            }
            throw PyErrors.error("AttributeError", "'" + typeName(args[0]) + "' object has no attribute '" + name + "'");
        }));

        // vars([obj])
        define.accept("vars", new PyBuiltinFunction("vars", args -> {
            if (args.length > 1) {
                throw PyErrors.error("TypeError", "vars expected at most 1 argument, got " + args.length);
            }
            if (args.length == 0) {
                Map<String, PyObject> scope = caller.currentScope();
                if (scope == null) {
                    throw PyErrors.error("RuntimeError", "vars() without arguments is not available here");
                }
                return scopeToDict(scope);
            }
            PyObject o = args[0];
            if (o instanceof PyInstance inst) return stringMapToDict(inst.fields());
            if (o instanceof PyModule mod) return stringMapToDict(mod.attrs());
            if (o instanceof PyClass cls) return stringMapToDict(cls.members());
            throw PyErrors.error("TypeError", "vars() argument must have __dict__ attribute");
        }));

        // dir([obj])
        define.accept("dir", new PyBuiltinFunction("dir", args -> {
            if (args.length > 1) {
                throw PyErrors.error("TypeError", "dir expected at most 1 argument, got " + args.length);
            }
            if (args.length == 0) {
                Map<String, PyObject> scope = caller.currentScope();
                if (scope == null) {
                    throw PyErrors.error("RuntimeError", "dir() without arguments is not available here");
                }
                var names = new ArrayList<String>();
                for (String k : scope.keySet()) {
                    if (!org.example.jython3.compile.CompiledCode.isSyntheticName(k)) names.add(k);
                }
                Collections.sort(names);
                var res = new ArrayList<PyObject>(names.size());
                for (String n : names) res.add(new PyStr(n));
                return new PyList(res);
            }
            return dirObject(args[0], javaPolicy);
        }));
        define.accept("globals", new PyBuiltinFunction("globals", args -> {
            checkArgCount("globals", args, 0);
            Map<String, PyObject> scope = caller.globalsScope();
            if (scope == null) {
                throw PyErrors.error("RuntimeError", "globals() is not available here");
            }
            return scopeToDict(scope);
        }));
        define.accept("locals", new PyBuiltinFunction("locals", args -> {
            checkArgCount("locals", args, 0);
            Map<String, PyObject> scope = caller.currentScope();
            if (scope == null) {
                throw PyErrors.error("RuntimeError", "locals() is not available here");
            }
            return scopeToDict(scope);
        }));
        define.accept("input", new PyBuiltinFunction("input", args -> {
            java.io.PrintStream out = caller != null ? caller.out() : System.out;
            if (args.length > 0 && args[0] instanceof PyStr s) out.print(s.value());
            try {
                var reader = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
                String line = reader.readLine();
                return line == null ? new PyStr("") : new PyStr(line);
            } catch (IOException e) {
                throw PyErrors.error("IOError", e.getMessage());
            }
        }));
    }

    /** Collects any supported iterable (including generators, iterators, sets, Java iterables) into a mutable List. */
    public static List<PyObject> collectIterable(PyObject o, org.example.jython3.security.JavaAccessPolicy policy) {
        if (o instanceof PyList list) return new ArrayList<>(list.items());
        if (o instanceof PyTuple tuple) return new ArrayList<>(tuple.items());
        if (o instanceof org.example.jython3.runtime.PySet s) return new ArrayList<>(s.items());
        if (o instanceof org.example.jython3.runtime.PyFrozenSet fs) return new ArrayList<>(fs.items());
        if (o instanceof PyStr s) return s.toCodePointList();
        if (o instanceof PyDict d) return d.keys();
        var ctx = org.example.jython3.security.ExecutionContext.current();
        int maxColl = ctx != null ? ctx.limits().maxCollectionSize() : 100_000_000;
        if (o instanceof PyRange r) {
            if (r.lengthBig().compareTo(java.math.BigInteger.valueOf(maxColl)) > 0) {
                throw PyErrors.error("MemoryError",
                        "range materialization (" + r.lengthBig() + ") exceeds maximum collection limit (" + maxColl + ")");
            }
        }
        PyIterator it = toIterator(o, policy);
        var items = new ArrayList<PyObject>();
        int ticks = 0;
        while (it.hasNext()) {
            if (ctx != null && (++ticks & 0x3F) == 0) ctx.stepInstruction();
            if (items.size() >= maxColl) {
                throw PyErrors.error("MemoryError",
                        "iterable materialization exceeds maximum collection limit (" + maxColl + ")");
            }
            items.add(it.next());
        }
        return items;
    }

    /** Converts a supported iterable into a fresh iterator. */
    private static PyIterator toIterator(PyObject o, org.example.jython3.security.JavaAccessPolicy policy) {
        if (o instanceof PyIterator it) return it;
        if (o instanceof PyRange r) return r.iterator();
        if (o instanceof PyBytes b) return b.iterator();
        if (o instanceof PyByteArray ba) return ba.iterator();
        if (o instanceof PyList list) return new PyIterator(list.items(), 0);
        if (o instanceof PyTuple tuple) return new PyIterator(tuple.items(), 0);
        if (o instanceof org.example.jython3.runtime.PySet s) return new PyIterator(new ArrayList<>(s.items()), 0);
        if (o instanceof org.example.jython3.runtime.PyFrozenSet fs) return new PyIterator(new ArrayList<>(fs.items()), 0);
        if (o instanceof PyStr s) {
            return s.codePointIterator();
        }
        if (o instanceof PyDict d) return new PyIterator(d.keys(), 0);
        if (o instanceof PyGenerator gen) {
            return PyIterator.fromGenerator(gen);
        }
        if (o instanceof PyJavaObject jo && jo.object() instanceof Iterable<?> iterable) {
            return PyIterator.fromJavaIterable(iterable, policy, org.example.jython3.security.ExecutionContext.current());
        }
        if (o instanceof PyInstance inst) {
            PyCaller caller = PyCaller.current();
            if (caller != null) {
                PyObject iterFn = inst.pyClass().lookupMember("__iter__");
                if (iterFn != null) {
                    PyObject itObj = caller.call(iterFn, new PyObject[]{inst});
                    if (itObj instanceof PyIterator pit) return pit;
                    if (itObj instanceof PyGenerator pgen) return PyIterator.fromGenerator(pgen);
                    if (itObj instanceof PyInstance iterInst && iterInst.pyClass().hasMember("__next__")) {
                        return customInstanceIterator(iterInst, caller);
                    }
                    throw PyErrors.error("TypeError", "iter() returned non-iterator of type '" + typeName(itObj) + "'");
                }
                if (inst.pyClass().hasMember("__next__")) {
                    return customInstanceIterator(inst, caller);
                }
                PyObject getitem = inst.pyClass().lookupMember("__getitem__");
                if (getitem != null) {
                    return new PyIterator(new java.util.Iterator<PyObject>() {
                        private int idx = 0;
                        private PyObject buffered;
                        private boolean hasBuffered = false;
                        private boolean done = false;

                        @Override public boolean hasNext() {
                            if (hasBuffered) return true;
                            if (done) return false;
                            try {
                                buffered = caller.call(getitem, new PyObject[]{inst, PyInt.of(idx++)});
                                hasBuffered = true;
                                return true;
                            } catch (InterpreterException ie) {
                                if ("IndexError".equals(ie.typeName()) || "StopIteration".equals(ie.typeName())) {
                                    done = true;
                                    return false;
                                }
                                done = true;
                                throw ie;
                            }
                        }

                        @Override public PyObject next() {
                            if (!hasNext()) throw new java.util.NoSuchElementException();
                            PyObject v = buffered;
                            buffered = null;
                            hasBuffered = false;
                            return v;
                        }
                    });
                }
            }
        }
        throw PyErrors.error("TypeError", "'" + typeName(o) + "' object is not iterable");
    }

    public static PyIterator customInstanceIterator(PyInstance iterInst, PyCaller caller) {
        PyObject nextFn = iterInst.pyClass().lookupMember("__next__");
        return new PyIterator(new java.util.Iterator<PyObject>() {
            private PyObject buffered = null;
            private boolean hasBuffered = false;
            private boolean exhausted = false;

            @Override public boolean hasNext() {
                if (hasBuffered) return true;
                if (exhausted) return false;
                try {
                    buffered = caller.call(nextFn, new PyObject[]{iterInst});
                    hasBuffered = true;
                    return true;
                } catch (InterpreterException ie) {
                    if ("StopIteration".equals(ie.typeName())) {
                        exhausted = true;
                        return false;
                    }
                    exhausted = true;
                    throw ie;
                }
            }

            @Override public PyObject next() {
                if (!hasNext()) throw new java.util.NoSuchElementException();
                PyObject v = buffered;
                buffered = null;
                hasBuffered = false;
                return v;
            }
        });
    }

    private static PyObject map(PyCaller caller, org.example.jython3.security.JavaAccessPolicy policy, PyObject[] args) {
        if (args.length != 2) throw PyErrors.error("TypeError", "map() requires exactly 2 arguments");
        List<PyObject> items = collectIterable(args[1], policy);
        var result = new ArrayList<PyObject>(items.size());
        for (var item : items) {
            result.add(caller.call(args[0], new PyObject[]{item}));
        }
        return new PyList(result);
    }

    private static PyObject filter(PyCaller caller, org.example.jython3.security.JavaAccessPolicy policy, PyObject[] args) {
        if (args.length != 2) throw PyErrors.error("TypeError", "filter() requires exactly 2 arguments");
        List<PyObject> items = collectIterable(args[1], policy);
        var result = new ArrayList<PyObject>();
        boolean identity = args[0] instanceof PyNull;
        for (var item : items) {
            boolean keep = identity ? isTruthy(item) : isTruthy(caller.call(args[0], new PyObject[]{item}));
            if (keep) result.add(item);
        }
        return new PyList(result);
    }

    public static PyStr pyReprStr(PyObject o) {
        return new PyStr(pyRepr(o));
    }

    public static String formatValuePublic(PyObject val, String spec) {
        var ctx = org.example.jython3.security.ExecutionContext.current();
        int maxLen = ctx != null ? ctx.limits().maxStringLength() : 1_000_000;
        return formatValue(val, spec, maxLen);
    }

    private static String pyRepr(PyObject o) {
        if (o instanceof PyStr s) {
            String raw = s.value();
            char quote = (raw.indexOf('\'') >= 0 && raw.indexOf('"') < 0) ? '"' : '\'';
            var sb = new StringBuilder();
            sb.append(quote);
            for (int i = 0; i < raw.length(); i++) {
                char ch = raw.charAt(i);
                switch (ch) {
                    case '\\' -> sb.append("\\\\");
                    case '\n' -> sb.append("\\n");
                    case '\r' -> sb.append("\\r");
                    case '\t' -> sb.append("\\t");
                    default -> {
                        if (ch == quote) sb.append('\\').append(quote);
                        else if (ch < 0x20 || ch == 0x7F) sb.append(String.format("\\x%02x", (int) ch));
                        else sb.append(ch);
                    }
                }
            }
            return sb.append(quote).toString();
        }
        if (o instanceof PyException pe) {
            var sb = new StringBuilder(pe.typeName()).append('(');
            for (int i = 0; i < pe.args().size(); i++) {
                if (i > 0) sb.append(", ");
                sb.append(pyRepr(pe.args().get(i)));
            }
            return sb.append(')').toString();
        }
        return String.valueOf(o);
    }

    private static PyDict scopeToDict(Map<String, PyObject> scope) {
        var d = new PyDict();
        for (var entry : scope.entrySet()) {
            String k = entry.getKey();
            if (org.example.jython3.compile.CompiledCode.isSyntheticName(k)) continue;
            PyObject v = entry.getValue();
            if (v instanceof org.example.jython3.runtime.PyCell cell) {
                if (!cell.isBound()) continue;
                v = cell.get();
            }
            if (v != null) d.put(new PyStr(k), v);
        }
        return d;
    }

    private static PyDict stringMapToDict(Map<String, PyObject> map) {
        var d = new PyDict();
        for (var entry : map.entrySet()) {
            d.put(new PyStr(entry.getKey()), entry.getValue());
        }
        return d;
    }

    private static PyList dirObject(PyObject o, org.example.jython3.security.JavaAccessPolicy policy) {
        var names = new java.util.TreeSet<String>();
        names.add("__class__");
        if (o instanceof PyInstance inst) {
            names.addAll(inst.fields().keySet());
            collectClassMembers(inst.pyClass(), names);
        } else if (o instanceof PyClass cls) {
            names.add("__name__");
            names.add("__mro__");
            names.add("__bases__");
            collectClassMembers(cls, names);
        } else if (o instanceof PyModule mod) {
            names.addAll(mod.attrs().keySet());
        } else if (o instanceof PyException) {
            Collections.addAll(names, "__cause__", "__context__", "__suppress_context__", "__traceback__", "args");
        } else if (o instanceof PyTraceback) {
            Collections.addAll(names, "tb_frame", "tb_lasti", "tb_lineno", "tb_next");
        } else if (o instanceof PyFrameInfo) {
            Collections.addAll(names, "f_code", "f_lineno");
        } else if (o instanceof PyCodeInfo) {
            Collections.addAll(names, "co_filename", "co_name");
        } else if (o instanceof PyDict) {
            Collections.addAll(names, "clear", "copy", "get", "items", "keys", "pop", "update", "values");
        } else if (o instanceof PyList) {
            Collections.addAll(names, "append", "clear", "copy", "count", "extend", "index", "insert", "pop", "remove", "reverse", "sort");
        } else if (o instanceof org.example.jython3.runtime.PySet) {
            Collections.addAll(names, "add", "clear", "copy", "difference", "discard", "intersection",
                    "isdisjoint", "issubset", "issuperset", "pop", "remove", "symmetric_difference", "union", "update");
        } else if (o instanceof org.example.jython3.runtime.PyFrozenSet) {
            Collections.addAll(names, "copy", "difference", "intersection", "isdisjoint",
                    "issubset", "issuperset", "symmetric_difference", "union");
        } else if (o instanceof PyGenerator) {
            Collections.addAll(names, "close", "send", "throw");
        } else if (o instanceof org.example.jython3.runtime.PyCoroutine) {
            Collections.addAll(names, "__await__", "close", "send", "throw");
        } else if (o instanceof PySlice) {
            Collections.addAll(names, "indices", "start", "step", "stop");
        } else if (o instanceof PyRange) {
            Collections.addAll(names, "count", "index", "start", "step", "stop");
        } else if (o instanceof PyBytes) {
            Collections.addAll(names, "count", "decode", "endswith", "find", "hex", "startswith");
        } else if (o instanceof PyByteArray) {
            Collections.addAll(names, "append", "clear", "copy", "count", "decode", "endswith", "extend", "find", "hex", "pop", "reverse", "startswith");
        } else if (o instanceof org.example.jython3.runtime.PyHostHandle hh) {
            names.addAll(hh.attrNames());
        } else if (o instanceof PyStr) {
            Collections.addAll(names, "count", "endswith", "find", "isalnum", "isalpha", "isdigit", "join", "lower", "replace", "split", "startswith", "strip", "upper");
        } else if (o instanceof PyFunction || o instanceof PyBuiltinFunction || o instanceof PyBoundMethod) {
            names.add("__name__");
        } else if (o instanceof PyJavaObject jo && jo.object() != null) {
            Class<?> clazz = jo.object().getClass();
            if (policy == null || policy.canAccessClass(clazz.getName())) {
                for (java.lang.reflect.Method m : clazz.getMethods()) {
                    if (java.lang.reflect.Modifier.isPublic(m.getModifiers()) && !m.isSynthetic()) {
                        if (policy == null || policy.canCallMethod(clazz.getName(), m.getName(),
                                org.example.jython3.security.JavaDescriptors.of(m.getParameterTypes()))) {
                            names.add(m.getName());
                        }
                    }
                }
                for (java.lang.reflect.Field f : clazz.getFields()) {
                    if (java.lang.reflect.Modifier.isPublic(f.getModifiers()) && !f.isSynthetic()) {
                        if (policy == null || policy.canReadField(clazz.getName(), f.getName())) {
                            names.add(f.getName());
                        }
                    }
                }
            }
        } else if (o instanceof PyJavaClass jc) {
            Class<?> clazz = jc.clazz();
            if (policy == null || policy.canAccessClass(clazz.getName())) {
                names.addAll(jc.accessibleStaticMembers().keySet());
            }
        }
        var res = new ArrayList<PyObject>(names.size());
        for (String n : names) res.add(new PyStr(n));
        return new PyList(res);
    }

    private static void collectClassMembers(PyClass cls, java.util.Set<String> out) {
        var seen = Collections.newSetFromMap(new java.util.IdentityHashMap<PyClass, Boolean>());
        var q = new java.util.ArrayDeque<PyClass>();
        q.add(cls);
        while (!q.isEmpty()) {
            PyClass cur = q.removeFirst();
            if (!seen.add(cur)) continue;
            out.addAll(cur.members().keySet());
            q.addAll(cur.getBases());
        }
    }

    /**
     * Canonical Python type object for {@code obj} ({@code type(obj)} and {@code obj.__class__}).
     */
    public static PyObject typeOf(PyObject obj, Map<String, PyBuiltinFunction> builtinTypes) {
        if (obj instanceof PyException pe) return pe.type();
        if (obj instanceof PyInstance inst) {
            PyClass cls = inst.pyClass();
            return cls == OBJECT_CLASS ? builtinTypes.get("object") : cls;
        }
        if (obj instanceof PyClass) return builtinTypes.get("type");
        if (obj instanceof PyBool) return builtinTypes.get("bool");
        if (obj instanceof PyInt || obj instanceof BigInt) return builtinTypes.get("int");
        if (obj instanceof PyFloat) return builtinTypes.get("float");
        if (obj instanceof PyStr) return builtinTypes.get("str");
        if (obj instanceof PyList) return builtinTypes.get("list");
        if (obj instanceof PyDict) return builtinTypes.get("dict");
        if (obj instanceof PyTuple) return builtinTypes.get("tuple");
        if (obj instanceof org.example.jython3.runtime.PySet) return builtinTypes.get("set");
        if (obj instanceof org.example.jython3.runtime.PyFrozenSet) return builtinTypes.get("frozenset");
        if (obj instanceof PyBuiltinFunction bf) {
            return bf.isType() ? builtinTypes.get("type") : BUILTIN_FN_TYPE;
        }
        if (obj instanceof PyFunction) return FUNCTION_TYPE;
        if (obj instanceof PyBoundMethod) return METHOD_TYPE;
        if (obj instanceof PyNull) return NONE_TYPE;
        if (obj instanceof PyGenerator) return GENERATOR_TYPE;
        if (obj instanceof org.example.jython3.runtime.PyCoroutine) return COROUTINE_TYPE;
        if (obj instanceof PyIterator) return ITERATOR_TYPE;
        if (obj instanceof PyRange) return builtinTypes.get("range");
        if (obj instanceof PyBytes) return builtinTypes.get("bytes");
        if (obj instanceof PyByteArray) return builtinTypes.get("bytearray");
        if (obj instanceof PyModule) return MODULE_TYPE;
        if (obj instanceof PySlice) return SLICE_TYPE;
        if (obj instanceof PyTraceback) return TRACEBACK_TYPE;
        if (obj instanceof PyFrameInfo) return FRAME_TYPE;
        if (obj instanceof PyCodeInfo) return CODE_TYPE;
        if (obj instanceof PyProperty) return builtinTypes.get("property");
        if (obj instanceof PyStaticMethod) return builtinTypes.get("staticmethod");
        if (obj instanceof PyClassMethod) return builtinTypes.get("classmethod");
        if (obj instanceof org.example.jython3.runtime.PyNotImplemented) return new PyStr("<class 'NotImplementedType'>");
        return new PyStr("<class '" + typeName(obj) + "'>");
    }

    public static PyTuple classMroTuple(PyClass cls, Map<String, PyBuiltinFunction> builtinTypes) {
        var list = new ArrayList<PyObject>(cls.mro().size() + 1);
        for (PyClass c : cls.mro()) list.add(c);
        PyBuiltinFunction objType = builtinTypes.get("object");
        if (objType != null) list.add(objType);
        return new PyTuple(list);
    }

    public static PyTuple classBasesTuple(PyClass cls, Map<String, PyBuiltinFunction> builtinTypes) {
        if (cls.getBases().isEmpty()) {
            PyBuiltinFunction objType = builtinTypes.get("object");
            return objType != null ? new PyTuple(List.of(objType)) : PyTuple.EMPTY;
        }
        var list = new ArrayList<PyObject>(cls.getBases().size());
        for (PyClass b : cls.getBases()) list.add(b);
        return new PyTuple(list);
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

        // Built-in type objects
        if (typeArg instanceof PyBuiltinFunction bf && bf.isType()) {
            return switch (bf.name()) {
                // In Python, bool is a subclass of int: isinstance(True, int) is True
                case "int"    -> (obj instanceof PyInt || obj instanceof BigInt || obj instanceof PyBool);
                case "bool"   -> (obj instanceof PyBool);
                case "float"  -> (obj instanceof PyFloat);
                case "str"    -> (obj instanceof PyStr);
                case "list"   -> (obj instanceof PyList);
                case "dict"   -> (obj instanceof PyDict);
                case "tuple"  -> (obj instanceof PyTuple);
                case "set"    -> (obj instanceof org.example.jython3.runtime.PySet);
                case "frozenset" -> (obj instanceof org.example.jython3.runtime.PyFrozenSet);
                case "object" -> true;
                case "type"   -> (obj instanceof PyClass || (obj instanceof PyBuiltinFunction b && b.isType()));
                case "NoneType" -> (obj instanceof PyNull);
                case "function" -> (obj instanceof PyFunction);
                case "builtin_function_or_method" -> (obj instanceof PyBuiltinFunction b && !b.isType());
                case "method" -> (obj instanceof PyBoundMethod);
                case "generator" -> (obj instanceof PyGenerator);
                case "coroutine" -> (obj instanceof org.example.jython3.runtime.PyCoroutine);
                case "iterator" -> (obj instanceof PyIterator);
                case "range" -> (obj instanceof PyRange);
                case "bytes" -> (obj instanceof PyBytes);
                case "bytearray" -> (obj instanceof PyByteArray);
                case "module" -> (obj instanceof PyModule);
                case "slice" -> (obj instanceof PySlice);
                case "traceback" -> (obj instanceof PyTraceback);
                case "frame" -> (obj instanceof PyFrameInfo);
                case "code" -> (obj instanceof PyCodeInfo);
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
        if (!(derived instanceof PyClass || (derived instanceof PyBuiltinFunction bf && bf.isType()))) {
            throw PyErrors.error("TypeError", "issubclass() arg 1 must be a class");
        }
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

        if (!(target instanceof PyClass || (target instanceof PyBuiltinFunction bt && bt.isType()))) {
            throw PyErrors.error("TypeError",
                    "issubclass() arg 2 must be a class, a tuple of classes, or a union");
        }

        // User-defined classes
        if (derived instanceof PyClass c1) {
            if (target instanceof PyClass c2) return c1.isSubclassOf(c2);
            PyBuiltinFunction b2 = (PyBuiltinFunction) target;
            return "object".equals(b2.name());
        }

        // Built-in types
        PyBuiltinFunction b1 = (PyBuiltinFunction) derived;
        if (target instanceof PyClass) return false;
        PyBuiltinFunction b2 = (PyBuiltinFunction) target;
        String dName = b1.name();
        String tName = b2.name();
        if (dName.equals(tName)) return true;
        // In Python, bool is a subclass of int
        if ("bool".equals(dName) && "int".equals(tName)) return true;
        return "object".equals(tName);
    }

    private static java.math.BigInteger toBigIntStrict(PyObject o, String fn) {
        if (o instanceof PyInt i) return java.math.BigInteger.valueOf(i.value());
        if (o instanceof BigInt bi) return bi.value();
        if (o instanceof PyBool b) return b.value() ? java.math.BigInteger.ONE : java.math.BigInteger.ZERO;
        throw PyErrors.error("TypeError", "'" + typeName(o) + "' object cannot be interpreted as an integer in " + fn + "()");
    }

    private static PyObject toPyInteger(java.math.BigInteger bi) {
        if (bi.bitLength() < 32) return PyInt.of(bi.intValue());
        return new BigInt(bi);
    }

    private static String formatIntBase(java.math.BigInteger val, int radix, String prefix) {
        if (val.signum() < 0) {
            return "-" + prefix + val.negate().toString(radix);
        }
        return prefix + val.toString(radix);
    }

    private static byte[] buildBytesArray(PyObject[] args,
                                          org.example.jython3.security.JavaAccessPolicy policy,
                                          int maxLen) {
        if (args.length > 3) {
            throw PyErrors.error("TypeError", "bytes() takes at most 3 arguments (" + args.length + " given)");
        }
        PyObject src = args[0];
        if (src instanceof PyStr s) {
            if (args.length < 2) {
                throw PyErrors.error("TypeError", "string argument without an encoding");
            }
            if (!(args[1] instanceof PyStr encStr)) {
                throw PyErrors.error("TypeError", "encoding must be str, not " + typeName(args[1]));
            }
            byte[] out = s.value().getBytes(PyBytes.resolveCharset(encStr.value()));
            if (out.length > maxLen) {
                throw PyErrors.error("MemoryError", "bytes length exceeds limit (" + maxLen + ")");
            }
            return out;
        }
        if (args.length > 1) {
            throw PyErrors.error("TypeError", "encoding without a string argument");
        }
        if (src instanceof PyInt || src instanceof PyBool || src instanceof BigInt) {
            long n = toBigIntStrict(src, "bytes").longValueExact();
            if (n < 0) throw PyErrors.error("ValueError", "negative count");
            if (n > maxLen) throw PyErrors.error("MemoryError", "bytes count exceeds limit (" + maxLen + ")");
            return new byte[(int) n];
        }
        if (src instanceof PyBytes b) return b.bytesUnsafe().clone();
        if (src instanceof PyByteArray ba) return ba.copyBytes();
        List<PyObject> items = collectIterable(src, policy);
        if (items.size() > maxLen) {
            throw PyErrors.error("MemoryError", "bytes length exceeds limit (" + maxLen + ")");
        }
        byte[] out = new byte[items.size()];
        for (int i = 0; i < items.size(); i++) {
            out[i] = (byte) PyBytes.extractByteValue(items.get(i));
        }
        return out;
    }

    private static String formatValue(PyObject val, String spec, int maxStrLen) {
        if (spec == null || spec.isEmpty()) {
            return val.toString();
        }
        int widthLimit = Math.min(10_000, Math.max(1, maxStrLen));
        // Parse Python format mini-language: [[fill]align][sign][#][0][width][grouping][.precision][type]
        int idx = 0;
        int len = spec.length();
        char fill = ' ';
        char align = 0;
        if (idx + 1 < len && "<>=^".indexOf(spec.charAt(idx + 1)) >= 0) {
            fill = spec.charAt(idx);
            align = spec.charAt(idx + 1);
            idx += 2;
        } else if (idx < len && "<>=^".indexOf(spec.charAt(idx)) >= 0) {
            align = spec.charAt(idx++);
        }
        char sign = '-';
        if (idx < len && "+- ".indexOf(spec.charAt(idx)) >= 0) {
            sign = spec.charAt(idx++);
        }
        boolean altForm = false;
        if (idx < len && spec.charAt(idx) == '#') {
            altForm = true;
            idx++;
        }
        boolean zeroPad = false;
        if (idx < len && spec.charAt(idx) == '0') {
            zeroPad = true;
            if (align == 0) {
                fill = '0';
                align = '=';
            }
            idx++;
        }
        int widthStart = idx;
        while (idx < len && Character.isDigit(spec.charAt(idx))) idx++;
        int width = 0;
        if (idx > widthStart) {
            try {
                width = Integer.parseInt(spec.substring(widthStart, idx));
            } catch (NumberFormatException nfe) {
                throw PyErrors.error("ValueError", "Format width exceeds limit");
            }
            if (width > widthLimit) {
                throw PyErrors.error("MemoryError", "Formatted string width (" + width + ") exceeds limit (" + widthLimit + ")");
            }
        }
        char grouping = 0;
        if (idx < len && (spec.charAt(idx) == ',' || spec.charAt(idx) == '_')) {
            grouping = spec.charAt(idx++);
        }
        int precision = -1;
        if (idx < len && spec.charAt(idx) == '.') {
            idx++;
            int pStart = idx;
            while (idx < len && Character.isDigit(spec.charAt(idx))) idx++;
            if (idx == pStart) throw PyErrors.error("ValueError", "Format specifier missing precision");
            try {
                precision = Integer.parseInt(spec.substring(pStart, idx));
            } catch (NumberFormatException nfe) {
                throw PyErrors.error("ValueError", "Format precision exceeds limit");
            }
            if (precision > 1000) throw PyErrors.error("ValueError", "Format precision exceeds limit");
        }
        char type = 0;
        if (idx < len) {
            type = spec.charAt(idx++);
        }
        if (idx != len) {
            throw PyErrors.error("ValueError", "Invalid format specifier '" + spec + "'");
        }

        boolean isStrVal = val instanceof PyStr;
        if (type == 0) {
            if (isStrVal) type = 's';
            else if (val instanceof PyFloat) type = 'g';
            else if (val instanceof PyInt || val instanceof BigInt || val instanceof PyBool) type = 'd';
            else type = 's';
        }

        String prefix = "";
        String body;
        boolean numeric = (val instanceof PyInt || val instanceof BigInt || val instanceof PyBool || val instanceof PyFloat);

        if (type == 's') {
            if (!(val instanceof PyStr) && (sign != '-' || altForm || zeroPad || grouping != 0)) {
                throw PyErrors.error("ValueError", "Unknown format code 's' for object");
            }
            String s = val.toString();
            if (precision >= 0 && s.length() > precision) s = s.substring(0, precision);
            body = s;
            if (align == 0) align = '<';
        } else if (type == 'd' || type == 'b' || type == 'o' || type == 'x' || type == 'X') {
            if (val instanceof PyFloat) {
                throw PyErrors.error("ValueError", "Unknown format code '" + type + "' for object of type 'float'");
            }
            java.math.BigInteger bi = toBigIntStrict(val, "format");
            boolean neg = bi.signum() < 0;
            java.math.BigInteger absBi = bi.abs();
            if (neg) prefix = "-";
            else if (sign == '+' || sign == ' ') prefix = String.valueOf(sign);

            int radix = switch (type) {
                case 'b' -> 2;
                case 'o' -> 8;
                case 'x', 'X' -> 16;
                default -> 10;
            };
            if (altForm && type != 'd') {
                prefix += switch (type) {
                    case 'b' -> "0b";
                    case 'o' -> "0o";
                    case 'x' -> "0x";
                    case 'X' -> "0X";
                    default -> "";
                };
            }
            body = absBi.toString(radix);
            if (type == 'X') body = body.toUpperCase();
            if (grouping != 0) body = groupDigits(body, grouping, type == 'd' ? 3 : 4);
            if (align == 0) align = '>';
        } else if (type == 'f' || type == 'F' || type == 'e' || type == 'E' || type == 'g' || type == 'G' || type == '%') {
            if (!numeric) {
                throw PyErrors.error("ValueError", "Unknown format code '" + type + "' for object of type '" + typeName(val) + "'");
            }
            double d = toDouble(val);
            if (type == '%') d *= 100.0;
            int prec = precision >= 0 ? precision : 6;
            boolean neg = d < 0 || (d == 0.0 && Double.doubleToRawLongBits(d) < 0);
            double absD = Math.abs(d);
            if (neg) prefix = "-";
            else if (sign == '+' || sign == ' ') prefix = String.valueOf(sign);

            String fmtType = switch (type) {
                case 'f', 'F', '%' -> "%." + prec + "f";
                case 'e' -> "%." + prec + "e";
                case 'E' -> "%." + prec + "E";
                case 'g' -> precision >= 0 ? "%." + Math.max(1, prec) + "g" : "%.16g";
                case 'G' -> precision >= 0 ? "%." + Math.max(1, prec) + "G" : "%.16G";
                default -> "%s";
            };
            body = String.format(java.util.Locale.ROOT, fmtType, absD);
            if (type == 'g' || type == 'G') {
                if (precision < 0 && body.contains(".") && !body.contains("e") && !body.contains("E")) {
                    body = body.replaceAll("0+$", "").replaceAll("\\.$", "");
                }
            }
            if (grouping != 0) {
                int dot = body.indexOf('.');
                String intPart = dot >= 0 ? body.substring(0, dot) : body;
                String rest = dot >= 0 ? body.substring(dot) : "";
                body = groupDigits(intPart, grouping, 3) + rest;
            }
            if (type == '%') body += "%";
            if (align == 0) align = '>';
        } else {
            throw PyErrors.error("ValueError", "Unknown format code '" + type + "'");
        }

        int totalLen = prefix.length() + body.length();
        if (totalLen >= width) {
            return prefix + body;
        }
        int pad = width - totalLen;
        String padStr = String.valueOf(fill).repeat(pad);
        return switch (align) {
            case '<' -> prefix + body + padStr;
            case '=' -> prefix + padStr + body;
            case '^' -> {
                int left = pad / 2;
                int right = pad - left;
                yield String.valueOf(fill).repeat(left) + prefix + body + String.valueOf(fill).repeat(right);
            }
            default -> padStr + prefix + body;
        };
    }

    private static String groupDigits(String digits, char sep, int groupSize) {
        if (digits.length() <= groupSize) return digits;
        var sb = new StringBuilder(digits.length() + digits.length() / groupSize);
        int rem = digits.length() % groupSize;
        if (rem > 0) sb.append(digits, 0, rem);
        for (int i = rem; i < digits.length(); i += groupSize) {
            if (sb.length() > 0) sb.append(sep);
            sb.append(digits, i, i + groupSize);
        }
        return sb.toString();
    }
}
