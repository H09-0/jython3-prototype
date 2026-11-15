package org.example.jython3.stdlib.modules;

import org.example.jython3.runtime.BigInt;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyBuiltinFunction;
import org.example.jython3.runtime.PyCaller;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyIterator;
import org.example.jython3.runtime.PyList;
import org.example.jython3.runtime.PyModule;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyOps;
import org.example.jython3.runtime.PyTuple;
import org.example.jython3.runtime.ArithmeticOps;
import org.example.jython3.security.DenyAllPolicy;
import org.example.jython3.security.ExecutionContext;
import org.example.jython3.stdlib.BuiltinFunctions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;

import static org.example.jython3.stdlib.modules.StdlibSupport.fn;

/**
 * Standard {@code itertools} module providing lazy, limit-checked iterators
 * and bounded combinatorial helpers.
 */
public final class ItertoolsModule {
    private ItertoolsModule() {}

    public static PyModule create() {
        Map<String, PyObject> attrs = new HashMap<>();

        attrs.put("count", new PyBuiltinFunction("itertools.count", (args, kwargs) -> {
            PyObject start = args.length >= 1 ? args[0] : kwargs.getOrDefault("start", PyInt.ZERO);
            PyObject step = args.length >= 2 ? args[1] : kwargs.getOrDefault("step", PyInt.ONE);
            return new PyIterator(new Iterator<PyObject>() {
                private PyObject cur = start;
                @Override public boolean hasNext() {
                    stepBudget();
                    return true;
                }
                @Override public PyObject next() {
                    stepBudget();
                    PyObject out = cur;
                    cur = ArithmeticOps.add(cur, step);
                    return out;
                }
            });
        }));

        attrs.put("repeat", new PyBuiltinFunction("itertools.repeat", (args, kwargs) -> {
            if (args.length < 1 || args.length > 2) throw PyErrors.error("TypeError", "repeat() takes 1 or 2 arguments");
            PyObject elem = args[0];
            PyObject timesObj = args.length == 2 ? args[1] : kwargs.getOrDefault("times", PyNull.INSTANCE);
            boolean bounded = !(timesObj instanceof PyNull);
            long times = bounded ? Math.max(0L, toLongStrict(timesObj)) : -1L;
            return new PyIterator(new Iterator<PyObject>() {
                private long rem = times;
                @Override public boolean hasNext() {
                    stepBudget();
                    return !bounded || rem > 0;
                }
                @Override public PyObject next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    if (bounded) rem--;
                    return elem;
                }
            });
        }));

        attrs.put("cycle", fn("cycle", args -> {
            PyOps.checkArgCount("cycle", args, 1);
            List<PyObject> buf = new ArrayList<>();
            PyIterator src = toIter(args[0]);
            return new PyIterator(new Iterator<PyObject>() {
                private boolean exhaustedSrc = false;
                private int idx = 0;

                @Override public boolean hasNext() {
                    stepBudget();
                    if (!exhaustedSrc) {
                        if (src.hasNext()) return true;
                        exhaustedSrc = true;
                        idx = 0;
                    }
                    return !buf.isEmpty();
                }

                @Override public PyObject next() {
                    stepBudget();
                    if (!exhaustedSrc) {
                        if (src.hasNext()) {
                            PyObject v = src.next();
                            buf.add(v);
                            return v;
                        }
                        exhaustedSrc = true;
                        idx = 0;
                    }
                    if (buf.isEmpty()) throw new NoSuchElementException();
                    PyObject v = buf.get(idx);
                    idx = (idx + 1) % buf.size();
                    return v;
                }
            });
        }));

        attrs.put("chain", new PyBuiltinFunction("itertools.chain", args -> chainIterators(List.of(args))));

        attrs.put("islice", fn("islice", args -> {
            if (args.length < 2 || args.length > 4) throw PyErrors.error("TypeError", "islice() takes 2 to 4 arguments");
            PyIterator src = toIter(args[0]);
            long start = 0;
            long stop = -1; // -1 = unbounded
            long step = 1;
            if (args.length == 2) {
                if (!(args[1] instanceof PyNull)) stop = requireNonNegLong(args[1]);
            } else {
                if (!(args[1] instanceof PyNull)) start = requireNonNegLong(args[1]);
                if (!(args[2] instanceof PyNull)) stop = requireNonNegLong(args[2]);
                if (args.length == 4 && !(args[3] instanceof PyNull)) {
                    step = toLongStrict(args[3]);
                    if (step < 1) throw PyErrors.error("ValueError", "Step for islice() must be a positive integer or None.");
                }
            }
            final long fStart = start, fStop = stop, fStep = step;
            return new PyIterator(new Iterator<PyObject>() {
                private long idx = 0;
                private long nextYield = fStart;
                private PyObject buffered;
                private boolean hasBuffered = false;

                @Override public boolean hasNext() {
                    if (hasBuffered) return true;
                    while (fStop < 0 || idx < fStop) {
                        stepBudget();
                        if (!src.hasNext()) return false;
                        PyObject val = src.next();
                        if (idx == nextYield) {
                            buffered = val;
                            hasBuffered = true;
                            nextYield += fStep;
                            idx++;
                            return true;
                        }
                        idx++;
                    }
                    return false;
                }

                @Override public PyObject next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    PyObject v = buffered;
                    buffered = null;
                    hasBuffered = false;
                    return v;
                }
            });
        }));

        attrs.put("takewhile", fn("takewhile", args -> {
            PyOps.checkArgCount("takewhile", args, 2);
            PyObject pred = args[0];
            PyIterator src = toIter(args[1]);
            PyCaller caller = PyCaller.current();
            return new PyIterator(new Iterator<PyObject>() {
                private PyObject buffered;
                private boolean hasBuffered = false;
                private boolean stopped = false;

                @Override public boolean hasNext() {
                    if (hasBuffered) return true;
                    if (stopped || !src.hasNext()) { stopped = true; return false; }
                    PyObject val = src.next();
                    if (PyOps.isTruthy(caller.call(pred, new PyObject[]{val}))) {
                        buffered = val;
                        hasBuffered = true;
                        return true;
                    }
                    stopped = true;
                    return false;
                }

                @Override public PyObject next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    PyObject v = buffered;
                    buffered = null;
                    hasBuffered = false;
                    return v;
                }
            });
        }));

        attrs.put("dropwhile", fn("dropwhile", args -> {
            PyOps.checkArgCount("dropwhile", args, 2);
            PyObject pred = args[0];
            PyIterator src = toIter(args[1]);
            PyCaller caller = PyCaller.current();
            return new PyIterator(new Iterator<PyObject>() {
                private boolean dropping = true;
                private PyObject buffered;
                private boolean hasBuffered = false;

                @Override public boolean hasNext() {
                    if (hasBuffered) return true;
                    while (src.hasNext()) {
                        PyObject val = src.next();
                        if (dropping) {
                            if (PyOps.isTruthy(caller.call(pred, new PyObject[]{val}))) continue;
                            dropping = false;
                        }
                        buffered = val;
                        hasBuffered = true;
                        return true;
                    }
                    return false;
                }

                @Override public PyObject next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    PyObject v = buffered;
                    buffered = null;
                    hasBuffered = false;
                    return v;
                }
            });
        }));

        attrs.put("filterfalse", fn("filterfalse", args -> {
            PyOps.checkArgCount("filterfalse", args, 2);
            PyObject pred = args[0];
            PyIterator src = toIter(args[1]);
            PyCaller caller = PyCaller.current();
            boolean useBool = pred instanceof PyNull;
            return new PyIterator(new Iterator<PyObject>() {
                private PyObject buffered;
                private boolean hasBuffered = false;

                @Override public boolean hasNext() {
                    if (hasBuffered) return true;
                    while (src.hasNext()) {
                        PyObject val = src.next();
                        boolean keep = useBool ? !PyOps.isTruthy(val)
                                : !PyOps.isTruthy(caller.call(pred, new PyObject[]{val}));
                        if (keep) {
                            buffered = val;
                            hasBuffered = true;
                            return true;
                        }
                    }
                    return false;
                }

                @Override public PyObject next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    PyObject v = buffered;
                    buffered = null;
                    hasBuffered = false;
                    return v;
                }
            });
        }));

        attrs.put("compress", fn("compress", args -> {
            PyOps.checkArgCount("compress", args, 2);
            PyIterator data = toIter(args[0]);
            PyIterator sels = toIter(args[1]);
            return new PyIterator(new Iterator<PyObject>() {
                private PyObject buffered;
                private boolean hasBuffered = false;

                @Override public boolean hasNext() {
                    if (hasBuffered) return true;
                    while (data.hasNext() && sels.hasNext()) {
                        PyObject d = data.next();
                        PyObject s = sels.next();
                        if (PyOps.isTruthy(s)) {
                            buffered = d;
                            hasBuffered = true;
                            return true;
                        }
                    }
                    return false;
                }

                @Override public PyObject next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    PyObject v = buffered;
                    buffered = null;
                    hasBuffered = false;
                    return v;
                }
            });
        }));

        attrs.put("accumulate", new PyBuiltinFunction("itertools.accumulate", (args, kwargs) -> {
            if (args.length < 1 || args.length > 3) throw PyErrors.error("TypeError", "accumulate() takes 1 to 3 arguments");
            PyIterator src = toIter(args[0]);
            PyObject func = args.length >= 2 ? args[1] : kwargs.getOrDefault("func", PyNull.INSTANCE);
            PyObject initial = args.length >= 3 ? args[2] : kwargs.getOrDefault("initial", null);
            PyCaller caller = PyCaller.current();
            return new PyIterator(new Iterator<PyObject>() {
                private PyObject acc = initial;
                private boolean initialized = (initial != null && !(initial instanceof PyNull));
                private boolean emitInitialFirst = initialized;

                @Override public boolean hasNext() {
                    return emitInitialFirst || src.hasNext();
                }

                @Override public PyObject next() {
                    if (emitInitialFirst) {
                        emitInitialFirst = false;
                        return acc;
                    }
                    if (!src.hasNext()) throw new NoSuchElementException();
                    PyObject v = src.next();
                    if (!initialized) {
                        acc = v;
                        initialized = true;
                    } else if (func instanceof PyNull) {
                        acc = ArithmeticOps.add(acc, v);
                    } else {
                        acc = caller.call(func, new PyObject[]{acc, v});
                    }
                    return acc;
                }
            });
        }));

        attrs.put("zip_longest", new PyBuiltinFunction("itertools.zip_longest", (args, kwargs) -> {
            PyObject fill = kwargs.getOrDefault("fillvalue", PyNull.INSTANCE);
            List<PyIterator> iters = new ArrayList<>(args.length);
            for (PyObject a : args) iters.add(toIter(a));
            return new PyIterator(new Iterator<PyObject>() {
                private boolean[] active = new boolean[iters.size()];
                { java.util.Arrays.fill(active, true); }
                private List<PyObject> nextTuple = null;

                @Override public boolean hasNext() {
                    if (nextTuple != null) return true;
                    if (iters.isEmpty()) return false;
                    boolean anyAlive = false;
                    var row = new ArrayList<PyObject>(iters.size());
                    for (int i = 0; i < iters.size(); i++) {
                        if (active[i] && iters.get(i).hasNext()) {
                            anyAlive = true;
                            row.add(iters.get(i).next());
                        } else {
                            active[i] = false;
                            row.add(fill);
                        }
                    }
                    if (!anyAlive) return false;
                    nextTuple = row;
                    return true;
                }

                @Override public PyObject next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    var out = new PyTuple(nextTuple);
                    nextTuple = null;
                    return out;
                }
            });
        }));

        attrs.put("product", new PyBuiltinFunction("itertools.product", (args, kwargs) -> {
            int repeat = kwargs.containsKey("repeat") ? PyOps.toInt(kwargs.get("repeat")) : 1;
            if (repeat < 0) throw PyErrors.error("ValueError", "repeat argument cannot be negative");
            var pools = new ArrayList<List<PyObject>>(args.length * Math.max(1, repeat));
            for (int r = 0; r < repeat; r++) {
                for (PyObject a : args) {
                    pools.add(BuiltinFunctions.collectIterable(a, DenyAllPolicy.INSTANCE));
                }
            }
            List<List<PyObject>> res = new ArrayList<>();
            res.add(List.of());
            int maxColl = maxCollectionLimit();
            for (List<PyObject> pool : pools) {
                if ((long) res.size() * pool.size() > maxColl) {
                    throw PyErrors.error("MemoryError", "itertools.product exceeds maxCollectionSize");
                }
                List<List<PyObject>> next = new ArrayList<>(res.size() * pool.size());
                for (List<PyObject> prefix : res) {
                    for (PyObject item : pool) {
                        var row = new ArrayList<PyObject>(prefix.size() + 1);
                        row.addAll(prefix);
                        row.add(item);
                        next.add(row);
                    }
                }
                res = next;
            }
            var out = new ArrayList<PyObject>(res.size());
            for (var row : res) out.add(new PyTuple(row));
            return new PyIterator(out, 0);
        }));

        attrs.put("permutations", fn("permutations", args -> {
            if (args.length < 1 || args.length > 2) throw PyErrors.error("TypeError", "permutations() takes 1 or 2 arguments");
            List<PyObject> pool = BuiltinFunctions.collectIterable(args[0], DenyAllPolicy.INSTANCE);
            int n = pool.size();
            int r = (args.length == 2 && !(args[1] instanceof PyNull)) ? PyOps.toInt(args[1]) : n;
            if (r < 0) throw PyErrors.error("ValueError", "r must be non-negative");
            if (r > n) return new PyIterator(List.of(), 0);
            int maxColl = maxCollectionLimit();
            var out = new ArrayList<PyObject>();
            boolean[] used = new boolean[n];
            permute(pool, r, used, new ArrayList<>(r), out, maxColl);
            return new PyIterator(out, 0);
        }));

        attrs.put("combinations", fn("combinations", args -> {
            PyOps.checkArgCount("combinations", args, 2);
            List<PyObject> pool = BuiltinFunctions.collectIterable(args[0], DenyAllPolicy.INSTANCE);
            int r = PyOps.toInt(args[1]);
            if (r < 0) throw PyErrors.error("ValueError", "r must be non-negative");
            if (r > pool.size()) return new PyIterator(List.of(), 0);
            int maxColl = maxCollectionLimit();
            var out = new ArrayList<PyObject>();
            combine(pool, r, 0, new ArrayList<>(r), out, maxColl);
            return new PyIterator(out, 0);
        }));

        return new PyModule("itertools", attrs);
    }

    private static PyIterator chainIterators(List<PyObject> iterables) {
        return new PyIterator(new Iterator<PyObject>() {
            private int idx = 0;
            private PyIterator cur = null;

            @Override public boolean hasNext() {
                while (true) {
                    if (cur != null && cur.hasNext()) return true;
                    if (idx >= iterables.size()) return false;
                    cur = toIter(iterables.get(idx++));
                }
            }

            @Override public PyObject next() {
                if (!hasNext()) throw new NoSuchElementException();
                return cur.next();
            }
        });
    }

    private static void permute(List<PyObject> pool, int r, boolean[] used,
                                List<PyObject> cur, List<PyObject> out, int maxColl) {
        if (cur.size() == r) {
            if (out.size() >= maxColl) throw PyErrors.error("MemoryError", "itertools.permutations exceeds maxCollectionSize");
            out.add(new PyTuple(new ArrayList<>(cur)));
            return;
        }
        for (int i = 0; i < pool.size(); i++) {
            if (!used[i]) {
                used[i] = true;
                cur.add(pool.get(i));
                permute(pool, r, used, cur, out, maxColl);
                cur.remove(cur.size() - 1);
                used[i] = false;
            }
        }
    }

    private static void combine(List<PyObject> pool, int r, int start,
                                List<PyObject> cur, List<PyObject> out, int maxColl) {
        if (cur.size() == r) {
            if (out.size() >= maxColl) throw PyErrors.error("MemoryError", "itertools.combinations exceeds maxCollectionSize");
            out.add(new PyTuple(new ArrayList<>(cur)));
            return;
        }
        for (int i = start; i <= pool.size() - (r - cur.size()); i++) {
            cur.add(pool.get(i));
            combine(pool, r, i + 1, cur, out, maxColl);
            cur.remove(cur.size() - 1);
        }
    }

    private static PyIterator toIter(PyObject o) {
        if (o instanceof PyIterator it) return it;
        if (o instanceof org.example.jython3.runtime.PyGenerator gen) return PyIterator.fromGenerator(gen);
        return new PyIterator(BuiltinFunctions.collectIterable(o, DenyAllPolicy.INSTANCE), 0);
    }

    private static void stepBudget() {
        ExecutionContext ctx = ExecutionContext.current();
        if (ctx != null) ctx.stepInstruction();
    }

    private static int maxCollectionLimit() {
        ExecutionContext ctx = ExecutionContext.current();
        return ctx != null ? ctx.limits().maxCollectionSize() : 100_000_000;
    }

    private static long toLongStrict(PyObject o) {
        if (o instanceof PyInt i) return i.value();
        if (o instanceof PyBool b) return b.value() ? 1L : 0L;
        if (o instanceof BigInt bi) return bi.value().longValueExact();
        throw PyErrors.error("TypeError", "an integer is required");
    }

    private static long requireNonNegLong(PyObject o) {
        long v = toLongStrict(o);
        if (v < 0) throw PyErrors.error("ValueError", "Indices for islice() must be None or an integer: 0 <= x <= sys.maxsize.");
        return v;
    }
}
