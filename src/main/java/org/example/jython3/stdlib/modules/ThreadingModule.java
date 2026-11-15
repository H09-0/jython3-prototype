package org.example.jython3.stdlib.modules;

import org.example.jython3.host.HostCapability;
import org.example.jython3.host.HostSession;
import org.example.jython3.runtime.BigInt;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyBuiltinFunction;
import org.example.jython3.runtime.PyDict;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyFloat;
import org.example.jython3.runtime.PyHostHandle;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyModule;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyOps;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.runtime.PyTuple;

import java.math.BigInteger;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Capability-gated {@code threading} module backed by Java 21 Virtual Threads
 * ({@link Thread#ofVirtual()}).
 *
 * <p>Requires {@link HostCapability#THREADS}; enforces {@code maxThreads} quotas,
 * inherits parent {@code ExecutionContext} cancellation/timeout limits, and
 * deterministically interrupts and joins live virtual threads on session reset/close.
 */
public final class ThreadingModule {
    private ThreadingModule() {}

    @FunctionalInterface
    public interface ThreadSpawner {
        SpawnedThread spawn(PyObject target, PyObject[] posArgs, Map<String, PyObject> kwArgs,
                            String name, boolean daemon);
    }

    public interface SpawnedThread {
        String name();
        long ident();
        boolean isAlive();
        void start();
        void join(long timeoutMs);
        Throwable failure();
    }

    private static final AtomicLong THREAD_SEQ = new AtomicLong(1);

    public static PyModule create(HostSession session, ThreadSpawner spawner) {
        Objects.requireNonNull(session, "session");
        Objects.requireNonNull(spawner, "spawner");
        Map<String, PyObject> attrs = new HashMap<>();

        attrs.put("Thread", PyBuiltinFunction.builtinType("threading.Thread", (args, kwargs) -> {
            PyObject target = kwargs.getOrDefault("target", args.length >= 2 ? args[1] : PyNull.INSTANCE);
            if (target == null || target instanceof PyNull) {
                throw PyErrors.error("TypeError", "threading.Thread requires callable 'target'");
            }
            PyObject argsObj = kwargs.getOrDefault("args", args.length >= 3 ? args[2] : PyTuple.EMPTY);
            PyObject[] posArgs;
            if (argsObj instanceof PyTuple t) posArgs = t.items().toArray(new PyObject[0]);
            else if (argsObj instanceof org.example.jython3.runtime.PyList l) posArgs = l.items().toArray(new PyObject[0]);
            else if (argsObj instanceof PyNull) posArgs = new PyObject[0];
            else throw PyErrors.error("TypeError", "Thread args must be tuple or list");

            Map<String, PyObject> kwArgs = new LinkedHashMap<>();
            PyObject kwObj = kwargs.getOrDefault("kwargs", args.length >= 4 ? args[3] : PyNull.INSTANCE);
            if (kwObj instanceof PyDict d) {
                for (var e : d.map().entrySet()) {
                    kwArgs.put(e.getKey().toString(), e.getValue());
                }
            }
            String name = (kwargs.get("name") instanceof PyStr ns)
                    ? ns.value()
                    : (args.length >= 5 && args[4] instanceof PyStr ns2 ? ns2.value() : "Thread-" + THREAD_SEQ.getAndIncrement());
            boolean daemon = kwargs.containsKey("daemon") && PyOps.isTruthy(kwargs.get("daemon"));

            final SpawnedThread[] handleRef = new SpawnedThread[1];
            final AtomicBoolean started = new AtomicBoolean(false);

            return PyHostHandle.builder("threading.Thread")
                    .bindSession(session)
                    .prop("name", () -> new PyStr(handleRef[0] != null ? handleRef[0].name() : name))
                    .prop("ident", () -> {
                        if (handleRef[0] == null) return PyNull.INSTANCE;
                        long id = handleRef[0].ident();
                        return id <= Integer.MAX_VALUE ? PyInt.of((int) id) : new BigInt(BigInteger.valueOf(id));
                    })
                    .prop("daemon", () -> PyBool.of(daemon))
                    .method("start", new PyBuiltinFunction("Thread.start", a -> {
                        PyOps.checkArgCount("start", a, 0);
                        if (!started.compareAndSet(false, true)) {
                            throw PyErrors.error("RuntimeError", "threads can only be started once");
                        }
                        session.require(HostCapability.THREADS, "spawn_thread", name);
                        SpawnedThread st = spawner.spawn(target, posArgs, kwArgs, name, daemon);
                        handleRef[0] = st;
                        st.start();
                        return PyNull.INSTANCE;
                    }))
                    .method("join", new PyBuiltinFunction("Thread.join", a -> {
                        if (a.length > 1) throw PyErrors.error("TypeError", "join() takes at most 1 argument");
                        if (!started.get() || handleRef[0] == null) {
                            throw PyErrors.error("RuntimeError", "cannot join thread before it is started");
                        }
                        long timeoutMs = -1;
                        if (a.length == 1 && !(a[0] instanceof PyNull)) {
                            double secs = PyOps.toDouble(a[0]);
                            if (secs < 0 || Double.isNaN(secs)) throw PyErrors.error("ValueError", "timeout must be >= 0");
                            timeoutMs = Double.isInfinite(secs) ? 0 : Math.max(1L, (long) (secs * 1000.0));
                        }
                        handleRef[0].join(timeoutMs);
                        return PyNull.INSTANCE;
                    }))
                    .method("is_alive", new PyBuiltinFunction("Thread.is_alive", a -> {
                        PyOps.checkArgCount("is_alive", a, 0);
                        return PyBool.of(handleRef[0] != null && handleRef[0].isAlive());
                    }))
                    .build();
        }));

        attrs.put("Lock", PyBuiltinFunction.builtinType("threading.Lock", args -> {
            PyOps.checkArgCount("Lock", args, 0);
            return buildLockHandle(session, new ReentrantLock(), false);
        }));

        attrs.put("RLock", PyBuiltinFunction.builtinType("threading.RLock", args -> {
            PyOps.checkArgCount("RLock", args, 0);
            return buildLockHandle(session, new ReentrantLock(), true);
        }));

        attrs.put("Event", PyBuiltinFunction.builtinType("threading.Event", args -> {
            PyOps.checkArgCount("Event", args, 0);
            return buildEventHandle(session);
        }));

        attrs.put("Semaphore", PyBuiltinFunction.builtinType("threading.Semaphore", args -> {
            int value = args.length >= 1 ? PyOps.toInt(args[0]) : 1;
            if (value < 0) throw PyErrors.error("ValueError", "semaphore initial value must be >= 0");
            return buildSemaphoreHandle(session, new Semaphore(value));
        }));

        attrs.put("current_thread", new PyBuiltinFunction("threading.current_thread", args -> {
            PyOps.checkArgCount("current_thread", args, 0);
            Thread cur = Thread.currentThread();
            long tid = cur.threadId();
            return PyHostHandle.builder("threading.Thread")
                    .bindSession(session)
                    .prop("name", () -> new PyStr(cur.getName()))
                    .prop("ident", () -> tid <= Integer.MAX_VALUE ? PyInt.of((int) tid) : new BigInt(BigInteger.valueOf(tid)))
                    .method("is_alive", new PyBuiltinFunction("Thread.is_alive", a -> PyBool.of(cur.isAlive())))
                    .build();
        }));

        attrs.put("active_count", new PyBuiltinFunction("threading.active_count", args -> {
            PyOps.checkArgCount("active_count", args, 0);
            return PyInt.of(1 + session.activeThreadCount());
        }));

        return new PyModule("threading", attrs);
    }

    private static PyHostHandle buildLockHandle(HostSession session, ReentrantLock lock, boolean reentrant) {
        return PyHostHandle.builder(reentrant ? "threading.RLock" : "threading.Lock")
                .bindSession(session)
                .method("acquire", new PyBuiltinFunction("acquire", (args, kwargs) -> {
                    boolean blocking = !kwargs.containsKey("blocking") || PyOps.isTruthy(kwargs.get("blocking"));
                    if (args.length >= 1) blocking = PyOps.isTruthy(args[0]);
                    double timeout = kwargs.containsKey("timeout") ? PyOps.toDouble(kwargs.get("timeout"))
                            : (args.length >= 2 ? PyOps.toDouble(args[1]) : -1.0);
                    if (!reentrant && lock.isHeldByCurrentThread() && !blocking) {
                        return PyBool.FALSE;
                    }
                    if (!blocking) {
                        return PyBool.of(lock.tryLock());
                    }
                    try {
                        if (timeout < 0) {
                            while (!lock.tryLock(50, TimeUnit.MILLISECONDS)) {
                                checkWaitCancelled(session, "Lock");
                            }
                            return PyBool.TRUE;
                        }
                        long deadline = System.currentTimeMillis() + Math.max(0L, (long) (timeout * 1000.0));
                        while (!lock.tryLock(Math.min(50L, Math.max(1L, deadline - System.currentTimeMillis())), TimeUnit.MILLISECONDS)) {
                            checkWaitCancelled(session, "Lock");
                            if (System.currentTimeMillis() >= deadline) return PyBool.FALSE;
                        }
                        return PyBool.TRUE;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw PyErrors.error("KeyboardInterrupt", "Interrupted acquiring lock");
                    }
                }))
                .method("release", new PyBuiltinFunction("release", args -> {
                    if (!lock.isHeldByCurrentThread()) {
                        throw PyErrors.error("RuntimeError", "cannot release un-acquired lock");
                    }
                    lock.unlock();
                    return PyNull.INSTANCE;
                }))
                .method("locked", new PyBuiltinFunction("locked", args -> PyBool.of(lock.isLocked())))
                .method("__enter__", new PyBuiltinFunction("__enter__", args -> {
                    try {
                        while (!lock.tryLock(50, TimeUnit.MILLISECONDS)) {
                            checkWaitCancelled(session, "Lock");
                        }
                        return PyBool.TRUE;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw PyErrors.error("KeyboardInterrupt", "Interrupted entering lock");
                    }
                }))
                .method("__exit__", new PyBuiltinFunction("__exit__", args -> {
                    if (lock.isHeldByCurrentThread()) lock.unlock();
                    return PyBool.FALSE;
                }))
                .build();
    }

    private static PyHostHandle buildEventHandle(HostSession session) {
        final Object monitor = new Object();
        final AtomicBoolean flag = new AtomicBoolean(false);
        return PyHostHandle.builder("threading.Event")
                .bindSession(session)
                .method("is_set", new PyBuiltinFunction("Event.is_set", a -> PyBool.of(flag.get())))
                .method("set", new PyBuiltinFunction("Event.set", a -> {
                    synchronized (monitor) {
                        flag.set(true);
                        monitor.notifyAll();
                    }
                    return PyNull.INSTANCE;
                }))
                .method("clear", new PyBuiltinFunction("Event.clear", a -> {
                    synchronized (monitor) {
                        flag.set(false);
                    }
                    return PyNull.INSTANCE;
                }))
                .method("wait", new PyBuiltinFunction("Event.wait", a -> {
                    long timeoutMs = -1;
                    if (a.length >= 1 && !(a[0] instanceof PyNull)) {
                        double secs = PyOps.toDouble(a[0]);
                        if (secs < 0) throw PyErrors.error("ValueError", "timeout must be >= 0");
                        timeoutMs = Math.max(1L, (long) (secs * 1000.0));
                    }
                    synchronized (monitor) {
                        if (flag.get()) return PyBool.TRUE;
                        if (timeoutMs == 0) return PyBool.of(flag.get());
                        long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : Long.MAX_VALUE;
                        while (!flag.get()) {
                            long rem = timeoutMs > 0 ? deadline - System.currentTimeMillis() : 50L;
                            if (timeoutMs > 0 && rem <= 0) break;
                            try {
                                monitor.wait(Math.min(rem, 50L));
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                                throw PyErrors.error("KeyboardInterrupt", "Interrupted waiting on Event");
                            }
                            if (session.isClosed()) {
                                throw PyErrors.error("KeyboardInterrupt", "Session closed while waiting on Event");
                            }
                        }
                        return PyBool.of(flag.get());
                    }
                }))
                .build();
    }

    private static PyHostHandle buildSemaphoreHandle(HostSession session, Semaphore sem) {
        return PyHostHandle.builder("threading.Semaphore")
                .bindSession(session)
                .method("acquire", new PyBuiltinFunction("Semaphore.acquire", (args, kwargs) -> {
                    boolean blocking = !kwargs.containsKey("blocking") || PyOps.isTruthy(kwargs.get("blocking"));
                    if (args.length >= 1) blocking = PyOps.isTruthy(args[0]);
                    double timeout = kwargs.containsKey("timeout") ? PyOps.toDouble(kwargs.get("timeout"))
                            : (args.length >= 2 && !(args[1] instanceof PyNull) ? PyOps.toDouble(args[1]) : -1.0);
                    if (!blocking) return PyBool.of(sem.tryAcquire());
                    try {
                        if (timeout < 0) {
                            while (!sem.tryAcquire(50, TimeUnit.MILLISECONDS)) {
                                checkWaitCancelled(session, "Semaphore");
                            }
                            return PyBool.TRUE;
                        }
                        long deadline = System.currentTimeMillis() + Math.max(0L, (long) (timeout * 1000.0));
                        while (!sem.tryAcquire(Math.min(50L, Math.max(1L, deadline - System.currentTimeMillis())), TimeUnit.MILLISECONDS)) {
                            checkWaitCancelled(session, "Semaphore");
                            if (System.currentTimeMillis() >= deadline) return PyBool.FALSE;
                        }
                        return PyBool.TRUE;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw PyErrors.error("KeyboardInterrupt", "Interrupted acquiring semaphore");
                    }
                }))
                .method("release", new PyBuiltinFunction("Semaphore.release", args -> {
                    int n = args.length >= 1 ? PyOps.toInt(args[0]) : 1;
                    if (n < 1) throw PyErrors.error("ValueError", "n must be >= 1");
                    sem.release(n);
                    return PyNull.INSTANCE;
                }))
                .method("__enter__", new PyBuiltinFunction("__enter__", args -> {
                    try {
                        while (!sem.tryAcquire(50, TimeUnit.MILLISECONDS)) {
                            checkWaitCancelled(session, "Semaphore");
                        }
                        return PyBool.TRUE;
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw PyErrors.error("KeyboardInterrupt", "Interrupted entering semaphore");
                    }
                }))
                .method("__exit__", new PyBuiltinFunction("__exit__", args -> {
                    sem.release();
                    return PyBool.FALSE;
                }))
                .build();
    }

    private static void checkWaitCancelled(HostSession session, String what) {
        if (session.isClosed()) {
            throw PyErrors.error("KeyboardInterrupt", "Session closed while waiting on " + what);
        }
        var ctx = org.example.jython3.security.ExecutionContext.current();
        if (ctx != null && ctx.isCancelled()) {
            throw PyErrors.error("KeyboardInterrupt", "Cancelled while waiting on " + what);
        }
        var active = session.activeContext();
        if (active != null && active.isCancelled()) {
            throw PyErrors.error("KeyboardInterrupt", "Cancelled while waiting on " + what);
        }
    }
}
