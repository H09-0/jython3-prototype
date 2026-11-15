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
import org.example.jython3.runtime.PyList;
import org.example.jython3.runtime.PyModule;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyOps;
import org.example.jython3.security.DenyAllPolicy;
import org.example.jython3.stdlib.BuiltinFunctions;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Capability-gated {@code concurrent.futures} module backed by Java 21 Virtual Threads.
 * Enforces {@link HostCapability#THREADS}, {@code maxThreads} quotas, and
 * deterministic cleanup on {@code with} exit or {@link HostSession#reset()}.
 */
public final class ConcurrentFuturesModule {
    private ConcurrentFuturesModule() {}

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

                        final var done = new AtomicBoolean(false);
                        final var resultRef = new AtomicReference<PyObject>(PyNull.INSTANCE);
                        final var errRef = new AtomicReference<Throwable>();
                        final var doneLatch = new java.util.concurrent.CountDownLatch(1);

                        PyObject taskRunner = new PyBuiltinFunction("future.task", tArgs -> {
                            PyCaller caller = PyCaller.current();
                            try {
                                PyObject r = caller.callWithKw(fn, fnArgs, sKwargs);
                                resultRef.set(r != null ? r : PyNull.INSTANCE);
                            } catch (Throwable t) {
                                errRef.set(t);
                            } finally {
                                done.set(true);
                                doneLatch.countDown();
                            }
                            return PyNull.INSTANCE;
                        });

                        synchronized (poolLock) {
                            if (activeWorkers.get() < maxWorkers) {
                                activeWorkers.incrementAndGet();
                                PyObject workerLoop = new PyBuiltinFunction("pool.worker", wArgs -> {
                                    PyCaller caller = PyCaller.current();
                                    try {
                                        while (!shutdown.get() && !session.isClosed()) {
                                            Runnable nextTask = workQueue.poll(20, TimeUnit.MILLISECONDS);
                                            if (nextTask != null) {
                                                nextTask.run();
                                            } else if (shutdown.get()) {
                                                break;
                                            } else {
                                                // Exit idle worker cleanly if queue empty after grace slice
                                                if (workQueue.isEmpty()) break;
                                            }
                                        }
                                    } catch (InterruptedException ie) {
                                        Thread.currentThread().interrupt();
                                    } finally {
                                        activeWorkers.decrementAndGet();
                                    }
                                    return PyNull.INSTANCE;
                                });
                                // Enqueue first before spawning worker so no task is missed
                                workQueue.add(() -> {
                                    PyCaller c = PyCaller.current();
                                    c.call(taskRunner, new PyObject[0]);
                                });
                                ThreadingModule.SpawnedThread st = spawner.spawn(
                                        workerLoop, new PyObject[0], Map.of(),
                                        "PoolWorker-" + activeWorkers.get(), true);
                                spawned.add(st);
                                st.start();
                            } else {
                                workQueue.add(() -> {
                                    PyCaller c = PyCaller.current();
                                    c.call(taskRunner, new PyObject[0]);
                                });
                            }
                        }

                        return PyHostHandle.builder("concurrent.futures.Future")
                                .bindSession(session)
                                .method("done", new PyBuiltinFunction("Future.done", a -> PyBool.of(done.get())))
                                .method("result", new PyBuiltinFunction("Future.result", a -> {
                                    long timeoutMs = -1;
                                    if (a.length >= 1 && !(a[0] instanceof PyNull)) {
                                        double secs = PyOps.toDouble(a[0]);
                                        if (secs < 0) throw PyErrors.error("ValueError", "timeout must be >= 0");
                                        timeoutMs = Math.max(1L, (long) (secs * 1000.0));
                                    }
                                    try {
                                        if (timeoutMs < 0) {
                                            while (!doneLatch.await(50, TimeUnit.MILLISECONDS)) {
                                                if (session.isClosed()) throw PyErrors.error("KeyboardInterrupt", "Session closed");
                                            }
                                        } else if (!doneLatch.await(timeoutMs, TimeUnit.MILLISECONDS)) {
                                            throw PyErrors.error("TimeoutError", "Future timed out");
                                        }
                                    } catch (InterruptedException ie) {
                                        Thread.currentThread().interrupt();
                                        throw PyErrors.error("KeyboardInterrupt", "Interrupted waiting on Future");
                                    }
                                    Throwable err = errRef.get();
                                    if (err != null) {
                                        if (err instanceof InterpreterException ie) throw ie;
                                        if (err instanceof RuntimeException re) throw re;
                                        throw PyErrors.fromJava(err);
                                    }
                                    return resultRef.get();
                                }))
                                .method("exception", new PyBuiltinFunction("Future.exception", a -> {
                                    try {
                                        doneLatch.await();
                                    } catch (InterruptedException ie) {
                                        Thread.currentThread().interrupt();
                                        throw PyErrors.error("KeyboardInterrupt", "Interrupted waiting on Future");
                                    }
                                    Throwable err = errRef.get();
                                    if (err == null) return PyNull.INSTANCE;
                                    if (err instanceof InterpreterException ie) return ie.value();
                                    return PyErrors.fromJava(err).value();
                                }))
                                .build();
                    }))
                    .method("map", new PyBuiltinFunction("ThreadPoolExecutor.map", mArgs -> {
                        if (mArgs.length < 2) throw PyErrors.error("TypeError", "map() takes at least 2 arguments");
                        PyObject fn = mArgs[0];
                        List<PyObject> items = BuiltinFunctions.collectIterable(mArgs[1], DenyAllPolicy.INSTANCE);
                        PyCaller caller = PyCaller.current();
                        // Submit all via submit() handle method or run directly in parallel
                        List<PyObject> futures = new ArrayList<>(items.size());
                        for (PyObject item : items) {
                            // Use sequential fallback if shutdown requested, else submit each item
                            PyObject fut = submitSimple(session, spawner, workQueue, spawned, activeWorkers,
                                    poolLock, maxWorkers, shutdown, fn, new PyObject[]{item});
                            futures.add(fut);
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

        attrs.put("as_completed", new PyBuiltinFunction("concurrent.futures.as_completed", args -> {
            PyOps.checkArgCount("as_completed", args, 1);
            List<PyObject> futs = BuiltinFunctions.collectIterable(args[0], DenyAllPolicy.INSTANCE);
            return new PyIterator(futs, 0);
        }));

        return new PyModule("concurrent.futures", attrs);
    }

    private static PyHostHandle submitSimple(
            HostSession session,
            ThreadingModule.ThreadSpawner spawner,
            BlockingQueue<Runnable> workQueue,
            List<ThreadingModule.SpawnedThread> spawned,
            AtomicInteger activeWorkers,
            Object poolLock,
            int maxWorkers,
            AtomicBoolean shutdown,
            PyObject fn,
            PyObject[] fnArgs) {
        final var resultRef = new AtomicReference<PyObject>(PyNull.INSTANCE);
        final var errRef = new AtomicReference<Throwable>();
        final var doneLatch = new java.util.concurrent.CountDownLatch(1);
        Runnable work = () -> {
            PyCaller caller = PyCaller.current();
            try {
                PyObject r = caller.call(fn, fnArgs);
                resultRef.set(r != null ? r : PyNull.INSTANCE);
            } catch (Throwable t) {
                errRef.set(t);
            } finally {
                doneLatch.countDown();
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
                            if (nextTask != null) nextTask.run();
                            else if (workQueue.isEmpty()) break;
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
        return PyHostHandle.builder("concurrent.futures.Future")
                .bindSession(session)
                .method("result", new PyBuiltinFunction("Future.result", a -> {
                    try {
                        while (!doneLatch.await(50, TimeUnit.MILLISECONDS)) {
                            if (session.isClosed()) throw PyErrors.error("KeyboardInterrupt", "Session closed");
                        }
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                        throw PyErrors.error("KeyboardInterrupt", "Interrupted waiting on Future");
                    }
                    Throwable err = errRef.get();
                    if (err != null) {
                        if (err instanceof InterpreterException ie) throw ie;
                        if (err instanceof RuntimeException re) throw re;
                        throw PyErrors.fromJava(err);
                    }
                    return resultRef.get();
                }))
                .build();
    }
}
