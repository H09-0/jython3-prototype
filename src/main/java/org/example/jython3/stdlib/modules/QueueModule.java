package org.example.jython3.stdlib.modules;

import org.example.jython3.host.HostSession;
import org.example.jython3.runtime.ExceptionTypeRegistry;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyBuiltinFunction;
import org.example.jython3.runtime.PyClass;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyHostHandle;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyModule;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyOps;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Thread-safe {@code queue} module ({@code Queue}, {@code LifoQueue},
 * {@code PriorityQueue}, {@code Empty}, {@code Full}) designed for
 * multi-threaded Virtual Thread producer/consumer pipelines.
 */
public final class QueueModule {
    private QueueModule() {}

    public static PyModule create(HostSession session) {
        Map<String, PyObject> attrs = new HashMap<>();
        PyClass excBase = ExceptionTypeRegistry.current().type("Exception");
        PyClass emptyCls = new PyClass("Empty", excBase, Map.of(), List.of(excBase));
        PyClass fullCls = new PyClass("Full", excBase, Map.of(), List.of(excBase));
        attrs.put("Empty", emptyCls);
        attrs.put("Full", fullCls);

        attrs.put("Queue", PyBuiltinFunction.builtinType("queue.Queue", (args, kwargs) -> {
            int maxsize = parseMaxsize(args, kwargs);
            return buildQueueHandle(session, maxsize, Mode.FIFO, emptyCls, fullCls);
        }));

        attrs.put("LifoQueue", PyBuiltinFunction.builtinType("queue.LifoQueue", (args, kwargs) -> {
            int maxsize = parseMaxsize(args, kwargs);
            return buildQueueHandle(session, maxsize, Mode.LIFO, emptyCls, fullCls);
        }));

        attrs.put("PriorityQueue", PyBuiltinFunction.builtinType("queue.PriorityQueue", (args, kwargs) -> {
            int maxsize = parseMaxsize(args, kwargs);
            return buildQueueHandle(session, maxsize, Mode.PRIORITY, emptyCls, fullCls);
        }));

        return new PyModule("queue", attrs);
    }

    private enum Mode { FIFO, LIFO, PRIORITY }

    private static int parseMaxsize(PyObject[] args, Map<String, PyObject> kwargs) {
        PyObject raw = kwargs.getOrDefault("maxsize", args.length >= 1 ? args[0] : PyInt.ZERO);
        return Math.max(0, PyOps.toInt(raw));
    }

    private static PyHostHandle buildQueueHandle(
            HostSession session, int maxsize, Mode mode, PyClass emptyCls, PyClass fullCls) {
        final ReentrantLock lock = new ReentrantLock();
        final Condition notEmpty = lock.newCondition();
        final Condition notFull = lock.newCondition();
        final Condition allTasksDone = lock.newCondition();
        final Deque<PyObject> deque = mode != Mode.PRIORITY ? new ArrayDeque<>() : null;
        final PriorityQueue<PyObject> pq = mode == Mode.PRIORITY
                ? new PriorityQueue<>(PyOps::compareObjects) : null;
        final int[] unfinished = {0};

        java.util.function.IntSupplier sizeFn = () -> deque != null ? deque.size() : pq.size();

        return PyHostHandle.builder("queue." + mode.name())
                .bindSession(session)
                .method("qsize", new PyBuiltinFunction("Queue.qsize", a -> {
                    lock.lock();
                    try { return PyInt.of(sizeFn.getAsInt()); }
                    finally { lock.unlock(); }
                }))
                .method("empty", new PyBuiltinFunction("Queue.empty", a -> {
                    lock.lock();
                    try { return PyBool.of(sizeFn.getAsInt() == 0); }
                    finally { lock.unlock(); }
                }))
                .method("full", new PyBuiltinFunction("Queue.full", a -> {
                    lock.lock();
                    try { return PyBool.of(maxsize > 0 && sizeFn.getAsInt() >= maxsize); }
                    finally { lock.unlock(); }
                }))
                .method("put", new PyBuiltinFunction("Queue.put", (args, kwargs) -> {
                    if (args.length < 1) throw PyErrors.error("TypeError", "put() missing 'item'");
                    PyObject item = args[0];
                    boolean block = kwargs.containsKey("block") ? PyOps.isTruthy(kwargs.get("block"))
                            : (args.length < 2 || PyOps.isTruthy(args[1]));
                    Double timeout = parseTimeout(args, kwargs, 2);
                    try {
                        lock.lockInterruptibly();
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw PyErrors.error("KeyboardInterrupt", "Interrupted waiting on queue");
                    }
                    try {
                        if (maxsize > 0) {
                            if (!block) {
                                if (sizeFn.getAsInt() >= maxsize) throw PyErrors.errorValue(new org.example.jython3.runtime.PyException(fullCls, List.of()));
                            } else if (timeout == null) {
                                while (sizeFn.getAsInt() >= maxsize) {
                                    awaitCheck(notFull, session);
                                }
                            } else {
                                long nanos = Math.max(0L, (long) (timeout * 1_000_000_000.0));
                                while (sizeFn.getAsInt() >= maxsize) {
                                    if (nanos <= 0L) throw PyErrors.errorValue(new org.example.jython3.runtime.PyException(fullCls, List.of()));
                                    nanos = awaitNanosCheck(notFull, nanos, session);
                                }
                            }
                        }
                        if (mode == Mode.LIFO) deque.addLast(item);
                        else if (mode == Mode.FIFO) deque.addLast(item);
                        else pq.add(item);
                        unfinished[0]++;
                        notEmpty.signal();
                        return PyNull.INSTANCE;
                    } finally {
                        lock.unlock();
                    }
                }))
                .method("put_nowait", new PyBuiltinFunction("Queue.put_nowait", args -> {
                    if (args.length != 1) throw PyErrors.error("TypeError", "put_nowait() takes 1 argument");
                    lock.lock();
                    try {
                        if (maxsize > 0 && sizeFn.getAsInt() >= maxsize) {
                            throw PyErrors.errorValue(new org.example.jython3.runtime.PyException(fullCls, List.of()));
                        }
                        if (mode == Mode.PRIORITY) pq.add(args[0]);
                        else deque.addLast(args[0]);
                        unfinished[0]++;
                        notEmpty.signal();
                        return PyNull.INSTANCE;
                    } finally {
                        lock.unlock();
                    }
                }))
                .method("get", new PyBuiltinFunction("Queue.get", (args, kwargs) -> {
                    boolean block = kwargs.containsKey("block") ? PyOps.isTruthy(kwargs.get("block"))
                            : (args.length < 1 || PyOps.isTruthy(args[0]));
                    Double timeout = parseTimeout(args, kwargs, 1);
                    lock.lock();
                    try {
                        if (!block) {
                            if (sizeFn.getAsInt() == 0) throw PyErrors.errorValue(new org.example.jython3.runtime.PyException(emptyCls, List.of()));
                        } else if (timeout == null) {
                            while (sizeFn.getAsInt() == 0) {
                                awaitCheck(notEmpty, session);
                            }
                        } else {
                            long nanos = Math.max(0L, (long) (timeout * 1_000_000_000.0));
                            while (sizeFn.getAsInt() == 0) {
                                if (nanos <= 0L) throw PyErrors.errorValue(new org.example.jython3.runtime.PyException(emptyCls, List.of()));
                                nanos = awaitNanosCheck(notEmpty, nanos, session);
                            }
                        }
                        PyObject out = switch (mode) {
                            case FIFO -> deque.removeFirst();
                            case LIFO -> deque.removeLast();
                            case PRIORITY -> pq.remove();
                        };
                        notFull.signal();
                        return out;
                    } finally {
                        lock.unlock();
                    }
                }))
                .method("get_nowait", new PyBuiltinFunction("Queue.get_nowait", args -> {
                    lock.lock();
                    try {
                        if (sizeFn.getAsInt() == 0) {
                            throw PyErrors.errorValue(new org.example.jython3.runtime.PyException(emptyCls, List.of()));
                        }
                        PyObject out = switch (mode) {
                            case FIFO -> deque.removeFirst();
                            case LIFO -> deque.removeLast();
                            case PRIORITY -> pq.remove();
                        };
                        notFull.signal();
                        return out;
                    } finally {
                        lock.unlock();
                    }
                }))
                .method("task_done", new PyBuiltinFunction("Queue.task_done", args -> {
                    lock.lock();
                    try {
                        int n = unfinished[0] - 1;
                        if (n < 0) throw PyErrors.error("ValueError", "task_done() called too many times");
                        unfinished[0] = n;
                        if (n == 0) allTasksDone.signalAll();
                        return PyNull.INSTANCE;
                    } finally {
                        lock.unlock();
                    }
                }))
                .method("join", new PyBuiltinFunction("Queue.join", args -> {
                    lock.lock();
                    try {
                        while (unfinished[0] > 0) {
                            awaitCheck(allTasksDone, session);
                        }
                        return PyNull.INSTANCE;
                    } finally {
                        lock.unlock();
                    }
                }))
                .build();
    }

    private static Double parseTimeout(PyObject[] args, Map<String, PyObject> kwargs, int posIdx) {
        PyObject raw = kwargs.containsKey("timeout") ? kwargs.get("timeout")
                : (args.length > posIdx ? args[posIdx] : PyNull.INSTANCE);
        if (raw == null || raw instanceof PyNull) return null;
        double d = PyOps.toDouble(raw);
        if (d < 0 || Double.isNaN(d)) throw PyErrors.error("ValueError", "'timeout' must be a non-negative number");
        return d;
    }

    private static void awaitCheck(Condition cond, HostSession session) {
        try {
            cond.await(50, TimeUnit.MILLISECONDS);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw PyErrors.error("KeyboardInterrupt", "Interrupted waiting on queue");
        }
        if (session.isClosed()) {
            throw PyErrors.error("KeyboardInterrupt", "Session closed while waiting on queue");
        }
        var ctx = session.activeContext();
        if (ctx != null && ctx.isCancelled()) {
            throw PyErrors.error("KeyboardInterrupt", "Execution cancelled while waiting on queue");
        }
    }

    private static long awaitNanosCheck(Condition cond, long nanos, HostSession session) {
        try {
            long slice = Math.min(nanos, 50_000_000L);
            long rem = cond.awaitNanos(slice);
            long consumed = slice - Math.max(0L, rem);
            if (session.isClosed()) {
                throw PyErrors.error("KeyboardInterrupt", "Session closed while waiting on queue");
            }
            var ctx = session.activeContext();
            if (ctx != null && ctx.isCancelled()) {
                throw PyErrors.error("KeyboardInterrupt", "Execution cancelled while waiting on queue");
            }
            return nanos - consumed;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw PyErrors.error("KeyboardInterrupt", "Interrupted waiting on queue");
        }
    }
}
