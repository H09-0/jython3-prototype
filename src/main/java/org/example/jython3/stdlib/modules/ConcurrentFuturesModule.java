package org.example.jython3.stdlib.modules;

import org.example.jython3.host.HostCapability;
import org.example.jython3.host.HostSession;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyBuiltinFunction;
import org.example.jython3.runtime.PyCaller;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyHostHandle;
import org.example.jython3.runtime.PyIterator;
import org.example.jython3.runtime.PyModule;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyOps;
import org.example.jython3.security.DenyAllPolicy;
import org.example.jython3.security.ExecutionContext;
import org.example.jython3.stdlib.BuiltinFunctions;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.IdentityHashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Capability-gated {@code concurrent.futures} module backed by Java 21 Virtual Threads.
 * Supports true completion-ordered {@code as_completed}, {@code Future.cancel()},
 * pool/queue quotas, and bounded cancellation-aware {@code shutdown}.
 */
public final class ConcurrentFuturesModule {
    private ConcurrentFuturesModule() {}

    private static final class FutureState {
        final AtomicBoolean done = new AtomicBoolean(false);
        final AtomicBoolean running = new AtomicBoolean(false);
        final AtomicBoolean cancelled = new AtomicBoolean(false);
        final AtomicReference<PyObject> result = new AtomicReference<>(PyNull.INSTANCE);
        final AtomicReference<Throwable> error = new AtomicReference<>();
        final AtomicReference<ExecutionContext> workerCtx = new AtomicReference<>();
        final AtomicReference<Thread> workerThread = new AtomicReference<>();
        final CountDownLatch latch = new CountDownLatch(1);
        final List<Runnable> onComplete = new CopyOnWriteArrayList<>();
        volatile PyHostHandle handle;

        void markCompleted() {
            if (done.compareAndSet(false, true)) {
                latch.countDown();
                for (Runnable r : onComplete) {
                    r.run();
                }
            }
        }

        void addCompletionListener(Runnable listener) {
            onComplete.add(listener);
            if (done.get()) {
                listener.run();
            }
        }
    }

    private static final Map<PyHostHandle, FutureState> FUTURE_STATES =
            Collections.synchronizedMap(new IdentityHashMap<>());

    public static PyModule createPackage(HostSession session, ThreadingModule.ThreadSpawner spawner) {
        PyModule futuresMod = createFutures(session, spawner);
        Map<String, PyObject> pkgAttrs = new HashMap<>();
        pkgAttrs.put("futures", futuresMod);
        return new PyModule("concurrent", pkgAttrs);
    }

    public static PyModule createFutures(HostSession session, ThreadingModule.ThreadSpawner spawner) {
        Map<String, PyObject> attrs = new HashMap<>();

        attrs.put("ThreadPoolExecutor", PyBuiltinFunction.builtinType("concurrent.futures.ThreadPoolExecutor", (args, kwargs) -> {
            session.require(HostCapability.THREADS, "create_thread_pool", "");
            int maxAllowed = Math.max(1, session.capabilities().maxThreads());
            int requested = maxAllowed;
            if (kwargs.containsKey("max_workers") && !(kwargs.get("max_workers") instanceof PyNull)) {
                requested = PyOps.toInt(kwargs.get("max_workers"));
            } else if (args.length >= 1 && !(args[0] instanceof PyNull)) {
                requested = PyOps.toInt(args[0]);
            }
            if (requested <= 0) {
                throw PyErrors.error("ValueError", "max_workers must be greater than 0");
            }
            final int maxWorkers = Math.min(requested, maxAllowed);
            final AtomicBoolean shutdown = new AtomicBoolean(false);
            final List<ThreadingModule.SpawnedThread> spawned = new ArrayList<>();
            final BlockingQueue<Runnable> workQueue = new LinkedBlockingQueue<>();
            final AtomicInteger activeWorkers = new AtomicInteger(0);
            final Object poolLock = new Object();

            AutoCloseable poolCleanup = () -> {
                shutdown.set(true);
                workQueue.clear();
            };
            session.registerResource(poolCleanup);
            final PyHostHandle[] selfRef = new PyHostHandle[1];

            selfRef[0] = PyHostHandle.builder("concurrent.futures.ThreadPoolExecutor")
                    .bindSession(session)
                    .method("submit", new PyBuiltinFunction("ThreadPoolExecutor.submit", (sArgs, sKwargs) -> {
                        if (shutdown.get() || session.isClosed()) {
                            throw PyErrors.error("RuntimeError", "cannot schedule new futures after shutdown");
                        }
                        if (sArgs.length < 1) {
                            throw PyErrors.error("TypeError", "submit() missing callable argument");
                        }
                        PyObject fn = sArgs[0];
                        PyObject[] fnArgs = new PyObject[sArgs.length - 1];
                        System.arraycopy(sArgs, 1, fnArgs, 0, fnArgs.length);
                        return submitTask(session, spawner, workQueue, spawned, activeWorkers,
                                poolLock, maxWorkers, shutdown, fn, fnArgs, sKwargs);
                    }))
                    .method("map", new PyBuiltinFunction("ThreadPoolExecutor.map", mArgs -> {
                        if (mArgs.length < 2) throw PyErrors.error("TypeError", "map() takes at least 2 arguments");
                        PyObject fn = mArgs[0];
                        List<PyObject> items = BuiltinFunctions.collectIterable(mArgs[1], DenyAllPolicy.INSTANCE);
                        PyCaller caller = PyCaller.current();
                        List<PyObject> futures = new ArrayList<>(items.size());
                        for (PyObject item : items) {
                            futures.add(submitTask(session, spawner, workQueue, spawned, activeWorkers,
                                    poolLock, maxWorkers, shutdown, fn, new PyObject[]{item}, Map.of()));
                        }
                        List<PyObject> results = new ArrayList<>(futures.size());
                        for (PyObject f : futures) {
                            PyObject resFn = ((PyHostHandle) f).getAttr("result");
                            results.add(caller.call(resFn, new PyObject[0]));
                        }
                        return new PyIterator(results, 0);
                    }))
                    .method("shutdown", new PyBuiltinFunction("ThreadPoolExecutor.shutdown", (shArgs, shKwargs) -> {
                        boolean wait = !shKwargs.containsKey("wait") || PyOps.isTruthy(shKwargs.get("wait"));
                        if (shArgs.length >= 1) wait = PyOps.isTruthy(shArgs[0]);
                        shutdown.set(true);
                        if (wait) {
                            List<ThreadingModule.SpawnedThread> snap;
                            synchronized (poolLock) { snap = new ArrayList<>(spawned); }
                            for (var st : snap) st.join(-1);
                        }
                        session.unregisterResource(poolCleanup);
                        return PyNull.INSTANCE;
                    }))
                    .method("__enter__", new PyBuiltinFunction("ThreadPoolExecutor.__enter__", a -> selfRef[0]))
                    .method("__exit__", new PyBuiltinFunction("ThreadPoolExecutor.__exit__", a -> {
                        shutdown.set(true);
                        List<ThreadingModule.SpawnedThread> snap;
                        synchronized (poolLock) { snap = new ArrayList<>(spawned); }
                        for (var st : snap) st.join(-1);
                        session.unregisterResource(poolCleanup);
                        return PyBool.FALSE;
                    }))
                    .build();
            return selfRef[0];
        }));

        attrs.put("as_completed", new PyBuiltinFunction("concurrent.futures.as_completed", (args, kwargs) -> {
            if (args.length < 1 || args.length > 2) {
                throw PyErrors.error("TypeError", "as_completed() takes 1 or 2 arguments");
            }
            Double timeoutSecs = null;
            PyObject rawTo = kwargs.containsKey("timeout") ? kwargs.get("timeout")
                    : (args.length == 2 ? args[1] : PyNull.INSTANCE);
            if (rawTo != null && !(rawTo instanceof PyNull)) {
                double d = PyOps.toDouble(rawTo);
                if (d < 0 || Double.isNaN(d)) throw PyErrors.error("ValueError", "timeout must be >= 0");
                timeoutSecs = d;
            }
            List<PyObject> rawFutures = BuiltinFunctions.collectIterable(args[0], DenyAllPolicy.INSTANCE);
            final BlockingQueue<PyHostHandle> completedQueue = new LinkedBlockingQueue<>();
            final Map<PyHostHandle, Boolean> registered = Collections.synchronizedMap(new IdentityHashMap<>());
            int total = 0;
            for (PyObject obj : rawFutures) {
                if (!(obj instanceof PyHostHandle fh)) {
                    throw PyErrors.error("TypeError", "as_completed() expects Future objects");
                }
                FutureState state = FUTURE_STATES.get(fh);
                if (state == null) {
                    throw PyErrors.error("TypeError", "as_completed() expects Future objects");
                }
                if (registered.put(fh, Boolean.TRUE) == null) {
                    total++;
                    final AtomicBoolean enqueued = new AtomicBoolean(false);
                    state.addCompletionListener(() -> {
                        if (enqueued.compareAndSet(false, true)) {
                            completedQueue.offer(fh);
                        }
                    });
                }
            }

            final int expectedCount = total;
            final long deadlineNanos = timeoutSecs != null
                    ? System.nanoTime() + Math.round(timeoutSecs * 1_000_000_000.0)
                    : Long.MAX_VALUE;
            final boolean hasTimeout = timeoutSecs != null;

            return new PyIterator(new Iterator<PyObject>() {
                private int yielded = 0;
                private PyHostHandle nextReady = null;

                @Override
                public boolean hasNext() {
                    if (nextReady != null) return true;
                    if (yielded >= expectedCount) return false;
                    while (true) {
                        if (session.isClosed()) {
                            throw PyErrors.error("KeyboardInterrupt", "Session closed while waiting in as_completed()");
                        }
                        var ctx = session.activeContext();
                        if (ctx != null && ctx.isCancelled()) {
                            throw PyErrors.error("KeyboardInterrupt", "Cancelled while waiting in as_completed()");
                        }
                        long waitMs = 50L;
                        if (hasTimeout) {
                            long remNanos = deadlineNanos - System.nanoTime();
                            if (remNanos <= 0) {
                                throw PyErrors.error("TimeoutError",
                                        (expectedCount - yielded) + " (of " + expectedCount + ") futures unfinished");
                            }
                            waitMs = Math.max(1L, Math.min(50L, remNanos / 1_000_000L));
                        }
                        try {
                            PyHostHandle h = completedQueue.poll(waitMs, TimeUnit.MILLISECONDS);
                            if (h != null) {
                                nextReady = h;
                                return true;
                            }
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw PyErrors.error("KeyboardInterrupt", "Interrupted in as_completed()");
                        }
                    }
                }

                @Override
                public PyObject next() {
                    if (!hasNext()) throw new NoSuchElementException();
                    PyHostHandle out = nextReady;
                    nextReady = null;
                    yielded++;
                    return out;
                }
            });
        }));

        return new PyModule("concurrent.futures", attrs);
    }

    private static PyHostHandle submitTask(
            HostSession session,
            ThreadingModule.ThreadSpawner spawner,
            BlockingQueue<Runnable> workQueue,
            List<ThreadingModule.SpawnedThread> spawned,
            AtomicInteger activeWorkers,
            Object poolLock,
            int maxWorkers,
            AtomicBoolean shutdown,
            PyObject fn,
            PyObject[] fnArgs,
            Map<String, PyObject> kwArgs) {
        final FutureState state = new FutureState();
        Runnable work = () -> {
            if (state.cancelled.get() || shutdown.get() || session.isClosed()) {
                state.markCompleted();
                return;
            }
            if (!state.running.compareAndSet(false, true)) {
                return;
            }
            PyCaller caller = PyCaller.current();
            state.workerThread.set(Thread.currentThread());
            state.workerCtx.set(ExecutionContext.current());
            try {
                if (state.cancelled.get()) {
                    state.markCompleted();
                    return;
                }
                PyObject r = kwArgs.isEmpty() ? caller.call(fn, fnArgs) : caller.callWithKw(fn, fnArgs, kwArgs);
                state.result.set(r != null ? r : PyNull.INSTANCE);
            } catch (Throwable t) {
                state.error.set(t);
            } finally {
                state.running.set(false);
                state.workerThread.set(null);
                state.workerCtx.set(null);
                state.markCompleted();
            }
        };

        synchronized (poolLock) {
            workQueue.add(work);
            if (activeWorkers.get() < maxWorkers) {
                activeWorkers.incrementAndGet();
                PyObject workerLoop = new PyBuiltinFunction("pool.worker", wArgs -> {
                    try {
                        while (!shutdown.get() && !session.isClosed()) {
                            Runnable nextTask = workQueue.poll(20, TimeUnit.MILLISECONDS);
                            if (nextTask != null) {
                                nextTask.run();
                            } else if (workQueue.isEmpty()) {
                                break;
                            }
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    } finally {
                        activeWorkers.decrementAndGet();
                    }
                    return PyNull.INSTANCE;
                });
                ThreadingModule.SpawnedThread st = spawner.spawn(
                        workerLoop, new PyObject[0], Map.of(),
                        "PoolWorker-" + activeWorkers.get(), true);
                spawned.add(st);
                st.start();
            }
        }

        PyHostHandle futureHandle = PyHostHandle.builder("concurrent.futures.Future")
                .bindSession(session)
                .method("done", new PyBuiltinFunction("Future.done", a -> PyBool.of(state.done.get())))
                .method("cancelled", new PyBuiltinFunction("Future.cancelled", a -> PyBool.of(state.cancelled.get())))
                .method("running", new PyBuiltinFunction("Future.running", a -> PyBool.of(state.running.get())))
                .method("cancel", new PyBuiltinFunction("Future.cancel", a -> {
                    if (state.done.get() || state.running.get()) {
                        return PyBool.FALSE;
                    }
                    if (state.cancelled.compareAndSet(false, true)) {
                        workQueue.remove(work);
                        state.markCompleted();
                        return PyBool.TRUE;
                    }
                    return PyBool.TRUE;
                }))
                .method("result", new PyBuiltinFunction("Future.result", a -> awaitFutureResult(state, a, session)))
                .method("exception", new PyBuiltinFunction("Future.exception", a -> {
                    awaitFutureCompletion(state, a, session);
                    if (state.cancelled.get()) {
                        throw PyErrors.error("RuntimeError", "Future was cancelled");
                    }
                    Throwable err = state.error.get();
                    if (err == null) return PyNull.INSTANCE;
                    if (err instanceof InterpreterException ie) return ie.value();
                    return PyErrors.fromJava(err).value();
                }))
                .build();
        state.handle = futureHandle;
        FUTURE_STATES.put(futureHandle, state);
        return futureHandle;
    }

    private static PyObject awaitFutureResult(FutureState state, PyObject[] a, HostSession session) {
        awaitFutureCompletion(state, a, session);
        if (state.cancelled.get()) {
            throw PyErrors.error("RuntimeError", "Future was cancelled");
        }
        Throwable err = state.error.get();
        if (err != null) {
            if (err instanceof InterpreterException ie) throw ie;
            if (err instanceof RuntimeException re) throw re;
            throw PyErrors.fromJava(err);
        }
        return state.result.get();
    }

    private static void awaitFutureCompletion(FutureState state, PyObject[] a, HostSession session) {
        long timeoutMs = -1;
        if (a.length >= 1 && !(a[0] instanceof PyNull)) {
            double secs = PyOps.toDouble(a[0]);
            if (secs < 0) throw PyErrors.error("ValueError", "timeout must be >= 0");
            timeoutMs = Math.max(1L, (long) (secs * 1000.0));
        }
        try {
            if (timeoutMs < 0) {
                while (!state.latch.await(50, TimeUnit.MILLISECONDS)) {
                    if (session.isClosed()) throw PyErrors.error("KeyboardInterrupt", "Session closed");
                    var ctx = session.activeContext();
                    if (ctx != null && ctx.isCancelled()) throw PyErrors.error("KeyboardInterrupt", "Cancelled");
                }
            } else {
                long deadline = System.currentTimeMillis() + timeoutMs;
                while (!state.latch.await(Math.min(50L, Math.max(1L, deadline - System.currentTimeMillis())), TimeUnit.MILLISECONDS)) {
                    if (session.isClosed()) throw PyErrors.error("KeyboardInterrupt", "Session closed");
                    if (System.currentTimeMillis() >= deadline) {
                        throw PyErrors.error("TimeoutError", "Future timed out");
                    }
                }
            }
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
            throw PyErrors.error("KeyboardInterrupt", "Interrupted waiting on Future");
        }
    }
}
