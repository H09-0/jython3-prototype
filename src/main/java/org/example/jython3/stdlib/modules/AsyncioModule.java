package org.example.jython3.stdlib.modules;

import org.example.jython3.runtime.ExceptionTypeRegistry;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyBuiltinFunction;
import org.example.jython3.runtime.PyCaller;
import org.example.jython3.runtime.PyClass;
import org.example.jython3.runtime.PyCoroutine;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyException;
import org.example.jython3.runtime.PyFunction;
import org.example.jython3.runtime.PyGenerator;
import org.example.jython3.runtime.PyInstance;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyIterator;
import org.example.jython3.runtime.PyList;
import org.example.jython3.runtime.PyModule;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyOps;
import org.example.jython3.runtime.PySet;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.security.ExecutionContext;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.Deque;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.PriorityQueue;
import java.util.Set;
import java.util.WeakHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;

/**
 * Cooperative single-threaded {@code asyncio} event loop module implementing
 * CPython's core task/coroutine primitives ({@code run}, {@code sleep},
 * {@code gather}, {@code create_task}, {@code wait_for}, {@code Task},
 * {@code Queue}, {@code Event}, {@code Lock}, {@code current_task},
 * {@code all_tasks}, {@code iscoroutine}, {@code iscoroutinefunction},
 * {@code CancelledError}, {@code InvalidStateError}).
 *
 * <p>Bounded by {@link ExecutionContext} instruction/timeout/cancellation limits
 * and {@link #MAX_TASKS} to prevent task-bomb memory exhaustion.
 */
public final class AsyncioModule {
    private static final int MAX_TASKS = 4096;
    private static final ThreadLocal<EventLoop> RUNNING_LOOP = new ThreadLocal<>();

    private static final Map<PyInstance, TaskRecord> TASK_RECORDS =
            Collections.synchronizedMap(new WeakHashMap<>());
    private static final Map<PyInstance, Supplier<PyIterator>> AWAITABLE_FACTORIES =
            Collections.synchronizedMap(new WeakHashMap<>());

    private static final PyClass TASK_CLASS = buildTaskClass();
    private static final PyClass AWAITABLE_CLASS = buildAwaitableClass();

    private AsyncioModule() {}

    private static PyClass buildTaskClass() {
        Map<String, PyObject> methods = new HashMap<>();
        methods.put("done", new PyBuiltinFunction("Task.done", args ->
                PyBool.of(requireTask(args[0]).done)));
        methods.put("cancelled", new PyBuiltinFunction("Task.cancelled", args ->
                PyBool.of(requireTask(args[0]).cancelled)));
        methods.put("get_name", new PyBuiltinFunction("Task.get_name", args ->
                new PyStr(requireTask(args[0]).name)));
        methods.put("cancel", new PyBuiltinFunction("Task.cancel", (args, kwargs) -> {
            TaskRecord tr = requireTask(args[0]);
            return PyBool.of(tr.loop.cancelTask(tr));
        }));
        methods.put("result", new PyBuiltinFunction("Task.result", args -> {
            TaskRecord tr = requireTask(args[0]);
            if (!tr.done) {
                throw PyErrors.errorValue(new PyException(tr.loop.invalidStateCls,
                        List.of(new PyStr("Result is not set."))));
            }
            if (tr.cancelled) {
                throw PyErrors.errorValue(new PyException(tr.loop.cancelledCls, List.of()));
            }
            if (tr.error != null) throw tr.error;
            return tr.result;
        }));
        methods.put("exception", new PyBuiltinFunction("Task.exception", args -> {
            TaskRecord tr = requireTask(args[0]);
            if (!tr.done) {
                throw PyErrors.errorValue(new PyException(tr.loop.invalidStateCls,
                        List.of(new PyStr("Exception is not set."))));
            }
            if (tr.cancelled) {
                throw PyErrors.errorValue(new PyException(tr.loop.cancelledCls, List.of()));
            }
            return tr.error != null ? tr.error.value() : PyNull.INSTANCE;
        }));
        methods.put("add_done_callback", new PyBuiltinFunction("Task.add_done_callback", (args, kwargs) -> {
            if (args.length < 2 || args.length > 2) {
                throw PyErrors.error("TypeError", "add_done_callback() takes 1 positional argument");
            }
            TaskRecord tr = requireTask(args[0]);
            PyObject fn = args[1];
            if (tr.done) {
                PyCaller caller = PyCaller.current();
                if (caller != null) {
                    try { caller.call(fn, new PyObject[]{tr.pyObject}); } catch (InterpreterException ignored) {}
                }
            } else {
                tr.doneCallbacks.add(fn);
            }
            return PyNull.INSTANCE;
        }));
        methods.put("remove_done_callback", new PyBuiltinFunction("Task.remove_done_callback", args -> {
            if (args.length != 2) {
                throw PyErrors.error("TypeError", "remove_done_callback() takes 1 positional argument");
            }
            TaskRecord tr = requireTask(args[0]);
            PyObject fn = args[1];
            int removed = 0;
            var it = tr.doneCallbacks.iterator();
            while (it.hasNext()) {
                if (it.next().equals(fn)) {
                    it.remove();
                    removed++;
                }
            }
            return PyInt.of(removed);
        }));
        methods.put("__await__", new PyBuiltinFunction("Task.__await__", args ->
                new PyIterator(new WaitTaskIterator(requireTask(args[0])))));
        return new PyClass("asyncio.Task", null, methods);
    }

    private static PyClass buildAwaitableClass() {
        Map<String, PyObject> methods = new HashMap<>();
        methods.put("__await__", new PyBuiltinFunction("_Awaitable.__await__", args -> {
            if (args.length != 1 || !(args[0] instanceof PyInstance inst)) {
                throw PyErrors.error("TypeError", "invalid awaitable");
            }
            Supplier<PyIterator> factory = AWAITABLE_FACTORIES.get(inst);
            if (factory == null) {
                throw PyErrors.error("RuntimeError", "awaitable already consumed or invalid");
            }
            return factory.get();
        }));
        return new PyClass("asyncio._Awaitable", null, methods);
    }

    private static TaskRecord requireTask(PyObject obj) {
        if (obj instanceof PyInstance inst) {
            TaskRecord tr = TASK_RECORDS.get(inst);
            if (tr != null) return tr;
        }
        throw PyErrors.error("TypeError", "expected asyncio.Task, got " + PyOps.typeName(obj));
    }

    private static PyInstance newAwaitable(Supplier<PyIterator> factory) {
        PyInstance inst = new PyInstance(AWAITABLE_CLASS);
        AWAITABLE_FACTORIES.put(inst, factory);
        return inst;
    }

    public static PyModule create() {
        Map<String, PyObject> attrs = new HashMap<>();
        PyClass baseExc = ExceptionTypeRegistry.current().type("BaseException");
        PyClass excBase = ExceptionTypeRegistry.current().type("Exception");
        PyClass cancelledCls = new PyClass("CancelledError", baseExc, Map.of(), List.of(baseExc));
        PyClass invalidStateCls = new PyClass("InvalidStateError", excBase, Map.of(), List.of(excBase));
        attrs.put("CancelledError", cancelledCls);
        attrs.put("InvalidStateError", invalidStateCls);
        attrs.put("TimeoutError", ExceptionTypeRegistry.current().type("TimeoutError"));
        attrs.put("Task", TASK_CLASS);

        attrs.put("iscoroutine", new PyBuiltinFunction("asyncio.iscoroutine", args -> {
            PyOps.checkArgCount("iscoroutine", args, 1);
            return PyBool.of(args[0] instanceof PyCoroutine);
        }));

        attrs.put("iscoroutinefunction", new PyBuiltinFunction("asyncio.iscoroutinefunction", args -> {
            PyOps.checkArgCount("iscoroutinefunction", args, 1);
            return PyBool.of(args[0] instanceof PyFunction fn && fn.isAsync());
        }));

        attrs.put("current_task", new PyBuiltinFunction("asyncio.current_task", args -> {
            if (args.length > 1) throw PyErrors.error("TypeError", "current_task() takes at most 1 argument");
            EventLoop loop = RUNNING_LOOP.get();
            if (loop == null) throw PyErrors.error("RuntimeError", "no running event loop");
            return loop.currentTask != null ? loop.currentTask.pyObject : PyNull.INSTANCE;
        }));

        attrs.put("all_tasks", new PyBuiltinFunction("asyncio.all_tasks", args -> {
            if (args.length > 1) throw PyErrors.error("TypeError", "all_tasks() takes at most 1 argument");
            EventLoop loop = RUNNING_LOOP.get();
            if (loop == null) throw PyErrors.error("RuntimeError", "no running event loop");
            Set<PyObject> active = new LinkedHashSet<>();
            for (TaskRecord tr : loop.allTasks) {
                if (!tr.done) active.add(tr.pyObject);
            }
            return new PySet(active);
        }));

        attrs.put("sleep", new PyBuiltinFunction("asyncio.sleep", (args, kwargs) -> {
            if (args.length < 1 || args.length > 2) {
                throw PyErrors.error("TypeError", "sleep() takes 1 or 2 arguments");
            }
            double delay = PyOps.toDouble(args[0]);
            if (Double.isNaN(delay)) {
                throw PyErrors.error("ValueError", "sleep length must be non-NaN");
            }
            long delayNanos = delay <= 0.0 ? 0L : Math.round(delay * 1_000_000_000.0);
            PyObject result = kwargs.containsKey("result") ? kwargs.get("result")
                    : (args.length == 2 ? args[1] : PyNull.INSTANCE);
            return newAwaitable(() -> new PyIterator(new SleepAwaitIterator(delayNanos, result)));
        }));

        attrs.put("create_task", new PyBuiltinFunction("asyncio.create_task", (args, kwargs) -> {
            if (args.length != 1) {
                throw PyErrors.error("TypeError", "create_task() takes 1 positional argument");
            }
            EventLoop loop = RUNNING_LOOP.get();
            if (loop == null) {
                throw PyErrors.error("RuntimeError", "no running event loop");
            }
            if (!(args[0] instanceof PyCoroutine coro)) {
                throw PyErrors.error("TypeError", "a coroutine was expected, got " + PyOps.typeName(args[0]));
            }
            String name = kwargs.containsKey("name") && !(kwargs.get("name") instanceof PyNull)
                    ? kwargs.get("name").toString() : null;
            return loop.createTask(coro, name).pyObject;
        }));

        attrs.put("gather", new PyBuiltinFunction("asyncio.gather", (args, kwargs) -> {
            boolean returnExceptions = kwargs.containsKey("return_exceptions")
                    && PyOps.isTruthy(kwargs.get("return_exceptions"));
            EventLoop loop = RUNNING_LOOP.get();
            if (loop == null) {
                throw PyErrors.error("RuntimeError", "no running event loop");
            }
            if (args.length == 0) {
                return newAwaitable(() -> new PyIterator(new ImmediateValueIterator(new PyList(List.of()))));
            }
            List<TaskRecord> tasks = new ArrayList<>(args.length);
            for (PyObject aw : args) {
                tasks.add(loop.ensureTask(aw));
            }
            return newAwaitable(() -> new PyIterator(new GatherAwaitIterator(tasks, returnExceptions)));
        }));

        attrs.put("wait_for", new PyBuiltinFunction("asyncio.wait_for", (args, kwargs) -> {
            if (args.length < 1 || args.length > 2) {
                throw PyErrors.error("TypeError", "wait_for(aw, timeout) takes 1 or 2 positional arguments");
            }
            EventLoop loop = RUNNING_LOOP.get();
            if (loop == null) {
                throw PyErrors.error("RuntimeError", "no running event loop");
            }
            PyObject timeoutObj = kwargs.containsKey("timeout") ? kwargs.get("timeout")
                    : (args.length == 2 ? args[1] : PyNull.INSTANCE);
            Long timeoutNanos = null;
            if (!(timeoutObj instanceof PyNull)) {
                double secs = PyOps.toDouble(timeoutObj);
                if (Double.isNaN(secs)) throw PyErrors.error("ValueError", "timeout must not be NaN");
                timeoutNanos = secs <= 0.0 ? 0L : Math.round(secs * 1_000_000_000.0);
            }
            TaskRecord task = loop.ensureTask(args[0]);
            Long finalTimeoutNanos = timeoutNanos;
            return newAwaitable(() -> new PyIterator(new WaitForAwaitIterator(loop, task, finalTimeoutNanos)));
        }));

        attrs.put("Queue", PyBuiltinFunction.builtinType("asyncio.Queue", (args, kwargs) -> {
            int maxsize = kwargs.containsKey("maxsize") ? PyOps.toInt(kwargs.get("maxsize"))
                    : (args.length >= 1 ? PyOps.toInt(args[0]) : 0);
            return buildAsyncQueue(Math.max(0, maxsize));
        }));

        attrs.put("Event", PyBuiltinFunction.builtinType("asyncio.Event", (args, kwargs) -> {
            if (args.length != 0) throw PyErrors.error("TypeError", "Event() takes no arguments");
            return buildAsyncEvent();
        }));

        attrs.put("Lock", PyBuiltinFunction.builtinType("asyncio.Lock", (args, kwargs) -> {
            if (args.length != 0) throw PyErrors.error("TypeError", "Lock() takes no arguments");
            return buildAsyncLock();
        }));

        attrs.put("run", new PyBuiltinFunction("asyncio.run", args -> {
            PyOps.checkArgCount("run", args, 1);
            if (!(args[0] instanceof PyCoroutine coro)) {
                throw PyErrors.error("TypeError", "a coroutine was expected, got " + PyOps.typeName(args[0]));
            }
            if (RUNNING_LOOP.get() != null) {
                throw PyErrors.error("RuntimeError", "asyncio.run() cannot be called from a running event loop");
            }
            EventLoop loop = new EventLoop(cancelledCls, invalidStateCls);
            RUNNING_LOOP.set(loop);
            try {
                return loop.runUntilComplete(coro);
            } finally {
                RUNNING_LOOP.remove();
                loop.cleanup();
            }
        }));

        return new PyModule("asyncio", attrs);
    }

    // ─── Internal Cooperative Tokens (never exposed to Python) ───────────
    private sealed interface SuspendToken permits SleepToken, WaitTasksToken, ParkToken {}

    private record SleepToken(long delayNanos) implements SuspendToken {}

    private record WaitTasksToken(List<TaskRecord> targets, long deadlineNanos) implements SuspendToken {}

    private record ParkToken(List<TaskRecord> parkQueue) implements SuspendToken {}

    // ─── Task Execution Abstraction ──────────────────────────────────────
    private interface StepTarget {
        PyObject send(PyObject val);
        PyObject throwException(PyObject excVal);
        void close();
    }

    private static final class CoroStepTarget implements StepTarget {
        private final PyCoroutine coro;
        CoroStepTarget(PyCoroutine coro) { this.coro = coro; }
        @Override public PyObject send(PyObject val) { return coro.send(val); }
        @Override public PyObject throwException(PyObject excVal) {
            return coro.throwException(new PyObject[]{excVal});
        }
        @Override public void close() { coro.close(); }
    }

    private static final class GenStepTarget implements StepTarget {
        private final PyGenerator gen;
        GenStepTarget(PyGenerator gen) { this.gen = gen; }
        @Override public PyObject send(PyObject val) { return gen.send(val); }
        @Override public PyObject throwException(PyObject excVal) {
            return gen.throwException(new PyObject[]{excVal});
        }
        @Override public void close() { gen.close(); }
    }

    private static final class IterStepTarget implements StepTarget {
        private final PyIterator iter;
        IterStepTarget(PyIterator iter) { this.iter = iter; }
        @Override public PyObject send(PyObject val) {
            if (iter.hasNext()) return iter.next();
            throw PyErrors.errorValue(new PyException(
                    ExceptionTypeRegistry.current().type("StopIteration"), List.of(PyNull.INSTANCE)));
        }
        @Override public PyObject throwException(PyObject excVal) {
            throw PyErrors.errorValue(excVal);
        }
        @Override public void close() {}
    }

    private static final class TaskRecord {
        final StepTarget target;
        final EventLoop loop;
        final PyInstance pyObject;
        final String name;
        boolean done = false;
        boolean cancelled = false;
        boolean cancelRequested = false;
        long waitSeq = 0;
        PyObject result = PyNull.INSTANCE;
        InterpreterException error = null;
        InterpreterException pendingThrow = null;
        final List<TaskRecord> waiters = new ArrayList<>();
        final List<PyObject> doneCallbacks = new ArrayList<>();

        TaskRecord(StepTarget target, String name, EventLoop loop) {
            this.target = target;
            this.name = name;
            this.loop = loop;
            this.pyObject = new PyInstance(TASK_CLASS);
            TASK_RECORDS.put(this.pyObject, this);
        }
    }

    private record TimerEntry(long wakeNanos, TaskRecord task, long expectedSeq) {}

    private static final class EventLoop {
        final PyClass cancelledCls;
        final PyClass invalidStateCls;
        final Deque<TaskRecord> ready = new ArrayDeque<>();
        final PriorityQueue<TimerEntry> timers = new PriorityQueue<>(Comparator.comparingLong(TimerEntry::wakeNanos));
        final List<TaskRecord> allTasks = new ArrayList<>();
        final AtomicInteger taskSeq = new AtomicInteger(1);
        TaskRecord currentTask = null;
        SuspendToken pendingToken = null;

        EventLoop(PyClass cancelledCls, PyClass invalidStateCls) {
            this.cancelledCls = cancelledCls;
            this.invalidStateCls = invalidStateCls;
        }

        void setPendingToken(SuspendToken token) {
            this.pendingToken = token;
        }

        TaskRecord createTask(PyCoroutine coro, String name) {
            return createTaskFromTarget(new CoroStepTarget(coro), name);
        }

        private TaskRecord createTaskFromTarget(StepTarget target, String name) {
            if (allTasks.size() >= MAX_TASKS) {
                throw PyErrors.error("RuntimeError", "maximum asyncio task limit (" + MAX_TASKS + ") exceeded");
            }
            String tName = (name != null) ? name : "Task-" + taskSeq.getAndIncrement();
            TaskRecord tr = new TaskRecord(target, tName, this);
            allTasks.add(tr);
            ready.addLast(tr);
            return tr;
        }

        TaskRecord ensureTask(PyObject aw) {
            if (aw instanceof PyCoroutine coro) {
                return createTask(coro, null);
            }
            if (aw instanceof PyInstance inst) {
                if (inst.pyClass() == TASK_CLASS) {
                    return requireTask(inst);
                }
                PyObject awaitFn = inst.pyClass().lookupMember("__await__");
                if (awaitFn != null) {
                    PyCaller caller = PyCaller.current();
                    if (caller == null) throw PyErrors.error("RuntimeError", "no active interpreter");
                    PyObject itObj = caller.call(awaitFn, new PyObject[]{inst});
                    if (itObj instanceof PyCoroutine c) return createTask(c, null);
                    if (itObj instanceof PyGenerator g) return createTaskFromTarget(new GenStepTarget(g), null);
                    if (itObj instanceof PyIterator it) return createTaskFromTarget(new IterStepTarget(it), null);
                }
            }
            throw PyErrors.error("TypeError", "An asyncio.Future, a coroutine or an awaitable is required, got " + PyOps.typeName(aw));
        }

        boolean cancelTask(TaskRecord task) {
            if (task.done) return false;
            task.cancelRequested = true;
            task.waitSeq++;
            task.pendingThrow = PyErrors.errorValue(new PyException(cancelledCls, List.of()));
            if (!ready.contains(task)) {
                ready.addLast(task);
            }
            return true;
        }

        void wakeWaiters(List<TaskRecord> waiters) {
            if (waiters.isEmpty()) return;
            for (TaskRecord w : waiters) {
                if (!w.done && !ready.contains(w)) {
                    w.waitSeq++;
                    ready.addLast(w);
                }
            }
            waiters.clear();
        }

        PyObject runUntilComplete(PyCoroutine mainCoro) {
            TaskRecord mainTask = createTask(mainCoro, null);
            ExecutionContext ctx = ExecutionContext.current();

            while (!mainTask.done) {
                if (ctx != null) ctx.stepInstruction();
                if (Thread.currentThread().isInterrupted()) {
                    throw PyErrors.error("KeyboardInterrupt", "asyncio loop interrupted");
                }

                long now = System.nanoTime();
                while (!timers.isEmpty() && timers.peek().wakeNanos() <= now) {
                    TimerEntry te = timers.poll();
                    TaskRecord t = te.task();
                    if (!t.done && !t.cancelRequested && t.waitSeq == te.expectedSeq()) {
                        t.waitSeq++;
                        if (!ready.contains(t)) ready.addLast(t);
                    }
                }

                if (ready.isEmpty()) {
                    while (!timers.isEmpty() && (timers.peek().task().done
                            || timers.peek().task().cancelRequested
                            || timers.peek().task().waitSeq != timers.peek().expectedSeq())) {
                        timers.poll();
                    }
                    if (timers.isEmpty()) {
                        throw PyErrors.error("RuntimeError",
                                "asyncio event loop stalled with pending unfinished main task");
                    }
                    long waitNanos = timers.peek().wakeNanos() - System.nanoTime();
                    if (waitNanos > 0) {
                        long sliceMs = Math.min(25L, Math.max(1L, waitNanos / 1_000_000L));
                        try {
                            Thread.sleep(sliceMs);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                            throw PyErrors.error("KeyboardInterrupt", "asyncio sleep interrupted");
                        }
                    }
                    continue;
                }

                TaskRecord cur = ready.removeFirst();
                if (cur.done) continue;

                currentTask = cur;
                pendingToken = null;
                try {
                    if (cur.pendingThrow != null) {
                        InterpreterException toThrow = cur.pendingThrow;
                        cur.pendingThrow = null;
                        cur.cancelRequested = false;
                        cur.target.throwException(toThrow.value());
                    } else {
                        cur.target.send(PyNull.INSTANCE);
                    }

                    SuspendToken tok = pendingToken;
                    pendingToken = null;

                    if (tok instanceof SleepToken st) {
                        if (st.delayNanos == 0L) {
                            ready.addLast(cur);
                        } else {
                            long seq = ++cur.waitSeq;
                            timers.add(new TimerEntry(System.nanoTime() + st.delayNanos, cur, seq));
                        }
                    } else if (tok instanceof WaitTasksToken wt) {
                        boolean anyReady = false;
                        for (TaskRecord t : wt.targets()) {
                            if (t.done) {
                                anyReady = true;
                                break;
                            }
                        }
                        if (anyReady || (wt.deadlineNanos() < Long.MAX_VALUE && System.nanoTime() >= wt.deadlineNanos())) {
                            ready.addLast(cur);
                        } else {
                            for (TaskRecord t : wt.targets()) {
                                if (!t.waiters.contains(cur)) {
                                    t.waiters.add(cur);
                                }
                            }
                            if (wt.deadlineNanos() < Long.MAX_VALUE) {
                                long seq = ++cur.waitSeq;
                                timers.add(new TimerEntry(wt.deadlineNanos(), cur, seq));
                            }
                        }
                    } else if (tok instanceof ParkToken pt) {
                        if (!pt.parkQueue().contains(cur)) {
                            pt.parkQueue().add(cur);
                        }
                    } else {
                        ready.addLast(cur);
                    }
                } catch (InterpreterException ie) {
                    pendingToken = null;
                    if ("StopIteration".equals(ie.typeName())) {
                        cur.result = (ie.value() instanceof PyException pe && !pe.args().isEmpty())
                                ? pe.args().get(0) : PyNull.INSTANCE;
                        finishTask(cur, false, null);
                    } else if (ie.value() instanceof PyException pe && pe.type() == cancelledCls) {
                        finishTask(cur, true, ie);
                    } else {
                        finishTask(cur, false, ie);
                    }
                } finally {
                    currentTask = null;
                }
            }

            if (mainTask.cancelled) {
                throw PyErrors.errorValue(new PyException(cancelledCls, List.of()));
            }
            if (mainTask.error != null) {
                throw mainTask.error;
            }
            return mainTask.result;
        }

        private void finishTask(TaskRecord task, boolean wasCancelled, InterpreterException err) {
            task.done = true;
            task.cancelled = wasCancelled;
            task.error = err;
            task.waitSeq++;
            wakeWaiters(task.waiters);
            if (!task.doneCallbacks.isEmpty()) {
                PyCaller caller = PyCaller.current();
                List<PyObject> cbs = new ArrayList<>(task.doneCallbacks);
                task.doneCallbacks.clear();
                if (caller != null) {
                    for (PyObject cb : cbs) {
                        try { caller.call(cb, new PyObject[]{task.pyObject}); } catch (InterpreterException ignored) {}
                    }
                }
            }
        }

        void cleanup() {
            for (TaskRecord tr : allTasks) {
                if (!tr.done) {
                    try { tr.target.close(); } catch (RuntimeException ignored) {}
                    tr.done = true;
                    tr.waitSeq++;
                }
            }
            ready.clear();
            timers.clear();
            allTasks.clear();
        }
    }

    // ─── Await Iterators ─────────────────────────────────────────────────
    private static final class SleepAwaitIterator implements Iterator<PyObject> {
        private final long delayNanos;
        private final PyObject result;
        private boolean yielded = false;

        SleepAwaitIterator(long delayNanos, PyObject result) {
            this.delayNanos = delayNanos;
            this.result = result;
        }

        @Override public boolean hasNext() {
            if (!yielded) return true;
            throw PyErrors.errorValue(new PyException(
                    ExceptionTypeRegistry.current().type("StopIteration"), List.of(result)));
        }

        @Override public PyObject next() {
            if (yielded) throw new NoSuchElementException();
            yielded = true;
            EventLoop loop = RUNNING_LOOP.get();
            if (loop != null) loop.setPendingToken(new SleepToken(delayNanos));
            return PyNull.INSTANCE;
        }
    }

    private static final class ImmediateValueIterator implements Iterator<PyObject> {
        private final PyObject value;
        ImmediateValueIterator(PyObject value) { this.value = value; }
        @Override public boolean hasNext() {
            throw PyErrors.errorValue(new PyException(
                    ExceptionTypeRegistry.current().type("StopIteration"), List.of(value)));
        }
        @Override public PyObject next() { throw new NoSuchElementException(); }
    }

    private static final class WaitTaskIterator implements Iterator<PyObject> {
        private final TaskRecord task;
        WaitTaskIterator(TaskRecord task) { this.task = task; }
        @Override public boolean hasNext() {
            if (!task.done) return true;
            if (task.cancelled) {
                throw PyErrors.errorValue(new PyException(task.loop.cancelledCls, List.of()));
            }
            if (task.error != null) throw task.error;
            throw PyErrors.errorValue(new PyException(
                    ExceptionTypeRegistry.current().type("StopIteration"), List.of(task.result)));
        }
        @Override public PyObject next() {
            if (!hasNext()) throw new NoSuchElementException();
            EventLoop loop = RUNNING_LOOP.get();
            if (loop != null) loop.setPendingToken(new WaitTasksToken(List.of(task), Long.MAX_VALUE));
            return PyNull.INSTANCE;
        }
    }

    private static final class GatherAwaitIterator implements Iterator<PyObject> {
        private final List<TaskRecord> tasks;
        private final boolean returnExceptions;

        GatherAwaitIterator(List<TaskRecord> tasks, boolean returnExceptions) {
            this.tasks = tasks;
            this.returnExceptions = returnExceptions;
        }

        @Override public boolean hasNext() {
            boolean allDone = true;
            for (TaskRecord t : tasks) {
                if (!t.done) {
                    allDone = false;
                } else if (!returnExceptions) {
                    if (t.cancelled) {
                        throw PyErrors.errorValue(new PyException(t.loop.cancelledCls, List.of()));
                    }
                    if (t.error != null) throw t.error;
                }
            }
            if (!allDone) return true;
            List<PyObject> results = new ArrayList<>(tasks.size());
            for (TaskRecord t : tasks) {
                if (t.cancelled) results.add(new PyException(t.loop.cancelledCls, List.of()));
                else if (t.error != null) results.add(t.error.value());
                else results.add(t.result);
            }
            throw PyErrors.errorValue(new PyException(
                    ExceptionTypeRegistry.current().type("StopIteration"), List.of(new PyList(results))));
        }

        @Override public PyObject next() {
            if (!hasNext()) throw new NoSuchElementException();
            List<TaskRecord> pending = new ArrayList<>();
            for (TaskRecord t : tasks) {
                if (!t.done) pending.add(t);
            }
            EventLoop loop = RUNNING_LOOP.get();
            if (loop != null) loop.setPendingToken(new WaitTasksToken(pending, Long.MAX_VALUE));
            return PyNull.INSTANCE;
        }
    }

    private static final class WaitForAwaitIterator implements Iterator<PyObject> {
        private final EventLoop loop;
        private final TaskRecord task;
        private final long deadlineNanos;
        private boolean timedOut = false;

        WaitForAwaitIterator(EventLoop loop, TaskRecord task, Long timeoutNanos) {
            this.loop = loop;
            this.task = task;
            this.deadlineNanos = timeoutNanos == null ? Long.MAX_VALUE
                    : System.nanoTime() + timeoutNanos;
        }

        @Override public boolean hasNext() {
            if (!task.done) {
                if (System.nanoTime() >= deadlineNanos) {
                    timedOut = true;
                    loop.cancelTask(task);
                    throw PyErrors.error("TimeoutError", "coroutine timed out");
                }
                return true;
            }
            if (timedOut) {
                throw PyErrors.error("TimeoutError", "coroutine timed out");
            }
            if (task.cancelled) throw PyErrors.errorValue(new PyException(loop.cancelledCls, List.of()));
            if (task.error != null) throw task.error;
            throw PyErrors.errorValue(new PyException(
                    ExceptionTypeRegistry.current().type("StopIteration"), List.of(task.result)));
        }

        @Override public PyObject next() {
            if (!hasNext()) throw new NoSuchElementException();
            loop.setPendingToken(new WaitTasksToken(List.of(task), deadlineNanos));
            return PyNull.INSTANCE;
        }
    }

    private static PyObject buildAsyncQueue(int maxsize) {
        final Deque<PyObject> items = new ArrayDeque<>();
        final int[] unfinished = {0};
        final List<TaskRecord> notEmptyWaiters = new ArrayList<>();
        final List<TaskRecord> notFullWaiters = new ArrayList<>();
        final List<TaskRecord> joinWaiters = new ArrayList<>();
        final PyClass queueCls = new PyClass("asyncio.Queue", null, Map.of());
        var inst = new PyInstance(queueCls);

        inst.setField("qsize", new PyBuiltinFunction("Queue.qsize", a -> PyInt.of(items.size())));
        inst.setField("empty", new PyBuiltinFunction("Queue.empty", a -> PyBool.of(items.isEmpty())));
        inst.setField("full", new PyBuiltinFunction("Queue.full", a -> PyBool.of(maxsize > 0 && items.size() >= maxsize)));
        inst.setField("put_nowait", new PyBuiltinFunction("Queue.put_nowait", a -> {
            PyOps.checkArgCount("put_nowait", a, 1);
            if (maxsize > 0 && items.size() >= maxsize) throw PyErrors.error("RuntimeError", "QueueFull");
            items.addLast(a[0]);
            unfinished[0]++;
            EventLoop loop = RUNNING_LOOP.get();
            if (loop != null) loop.wakeWaiters(notEmptyWaiters);
            return PyNull.INSTANCE;
        }));
        inst.setField("get_nowait", new PyBuiltinFunction("Queue.get_nowait", a -> {
            if (items.isEmpty()) throw PyErrors.error("RuntimeError", "QueueEmpty");
            PyObject out = items.removeFirst();
            EventLoop loop = RUNNING_LOOP.get();
            if (loop != null) loop.wakeWaiters(notFullWaiters);
            return out;
        }));
        inst.setField("put", new PyBuiltinFunction("Queue.put", a -> {
            PyOps.checkArgCount("put", a, 1);
            PyObject val = a[0];
            return newAwaitable(() -> new PyIterator(new Iterator<PyObject>() {
                @Override public boolean hasNext() {
                    if (maxsize > 0 && items.size() >= maxsize) return true;
                    items.addLast(val);
                    unfinished[0]++;
                    EventLoop loop = RUNNING_LOOP.get();
                    if (loop != null) loop.wakeWaiters(notEmptyWaiters);
                    throw PyErrors.errorValue(new PyException(
                            ExceptionTypeRegistry.current().type("StopIteration"), List.of(PyNull.INSTANCE)));
                }
                @Override public PyObject next() {
                    EventLoop loop = RUNNING_LOOP.get();
                    if (loop != null) loop.setPendingToken(new ParkToken(notFullWaiters));
                    return PyNull.INSTANCE;
                }
            }));
        }));
        inst.setField("get", new PyBuiltinFunction("Queue.get", a ->
                newAwaitable(() -> new PyIterator(new Iterator<PyObject>() {
                    @Override public boolean hasNext() {
                        if (items.isEmpty()) return true;
                        PyObject out = items.removeFirst();
                        EventLoop loop = RUNNING_LOOP.get();
                        if (loop != null) loop.wakeWaiters(notFullWaiters);
                        throw PyErrors.errorValue(new PyException(
                                ExceptionTypeRegistry.current().type("StopIteration"), List.of(out)));
                    }
                    @Override public PyObject next() {
                        EventLoop loop = RUNNING_LOOP.get();
                        if (loop != null) loop.setPendingToken(new ParkToken(notEmptyWaiters));
                        return PyNull.INSTANCE;
                    }
                }))));
        inst.setField("task_done", new PyBuiltinFunction("Queue.task_done", a -> {
            if (unfinished[0] <= 0) throw PyErrors.error("ValueError", "task_done() called too many times");
            unfinished[0]--;
            if (unfinished[0] == 0) {
                EventLoop loop = RUNNING_LOOP.get();
                if (loop != null) loop.wakeWaiters(joinWaiters);
            }
            return PyNull.INSTANCE;
        }));
        inst.setField("join", new PyBuiltinFunction("Queue.join", a ->
                newAwaitable(() -> new PyIterator(new Iterator<PyObject>() {
                    @Override public boolean hasNext() {
                        if (unfinished[0] > 0) return true;
                        throw PyErrors.errorValue(new PyException(
                                ExceptionTypeRegistry.current().type("StopIteration"), List.of(PyNull.INSTANCE)));
                    }
                    @Override public PyObject next() {
                        EventLoop loop = RUNNING_LOOP.get();
                        if (loop != null) loop.setPendingToken(new ParkToken(joinWaiters));
                        return PyNull.INSTANCE;
                    }
                }))));
        return inst;
    }

    private static PyObject buildAsyncEvent() {
        final boolean[] flag = {false};
        final List<TaskRecord> waiters = new ArrayList<>();
        final PyClass eventCls = new PyClass("asyncio.Event", null, Map.of());
        var inst = new PyInstance(eventCls);

        inst.setField("is_set", new PyBuiltinFunction("Event.is_set", a -> PyBool.of(flag[0])));
        inst.setField("set", new PyBuiltinFunction("Event.set", a -> {
            flag[0] = true;
            EventLoop loop = RUNNING_LOOP.get();
            if (loop != null) loop.wakeWaiters(waiters);
            return PyNull.INSTANCE;
        }));
        inst.setField("clear", new PyBuiltinFunction("Event.clear", a -> {
            flag[0] = false;
            return PyNull.INSTANCE;
        }));
        inst.setField("wait", new PyBuiltinFunction("Event.wait", a ->
                newAwaitable(() -> new PyIterator(new Iterator<PyObject>() {
                    @Override public boolean hasNext() {
                        if (!flag[0]) return true;
                        throw PyErrors.errorValue(new PyException(
                                ExceptionTypeRegistry.current().type("StopIteration"), List.of(PyBool.TRUE)));
                    }
                    @Override public PyObject next() {
                        EventLoop loop = RUNNING_LOOP.get();
                        if (loop != null) loop.setPendingToken(new ParkToken(waiters));
                        return PyNull.INSTANCE;
                    }
                }))));
        return inst;
    }

    private static PyObject buildAsyncLock() {
        final boolean[] locked = {false};
        final List<TaskRecord> waiters = new ArrayList<>();
        final PyClass lockCls = new PyClass("asyncio.Lock", null, Map.of());
        var inst = new PyInstance(lockCls);

        PyBuiltinFunction acquireFn = new PyBuiltinFunction("Lock.acquire", a ->
                newAwaitable(() -> new PyIterator(new Iterator<PyObject>() {
                    @Override public boolean hasNext() {
                        if (locked[0]) return true;
                        locked[0] = true;
                        throw PyErrors.errorValue(new PyException(
                                ExceptionTypeRegistry.current().type("StopIteration"), List.of(PyBool.TRUE)));
                    }
                    @Override public PyObject next() {
                        EventLoop loop = RUNNING_LOOP.get();
                        if (loop != null) loop.setPendingToken(new ParkToken(waiters));
                        return PyNull.INSTANCE;
                    }
                })));
        PyBuiltinFunction releaseFn = new PyBuiltinFunction("Lock.release", a -> {
            if (!locked[0]) throw PyErrors.error("RuntimeError", "Lock is not acquired.");
            locked[0] = false;
            EventLoop loop = RUNNING_LOOP.get();
            if (loop != null) loop.wakeWaiters(waiters);
            return PyNull.INSTANCE;
        });

        inst.setField("locked", new PyBuiltinFunction("Lock.locked", a -> PyBool.of(locked[0])));
        inst.setField("acquire", acquireFn);
        inst.setField("release", releaseFn);
        inst.setField("__aenter__", new PyBuiltinFunction("Lock.__aenter__", a ->
                newAwaitable(() -> new PyIterator(new Iterator<PyObject>() {
                    @Override public boolean hasNext() {
                        if (locked[0]) return true;
                        locked[0] = true;
                        throw PyErrors.errorValue(new PyException(
                                ExceptionTypeRegistry.current().type("StopIteration"), List.of(PyNull.INSTANCE)));
                    }
                    @Override public PyObject next() {
                        EventLoop loop = RUNNING_LOOP.get();
                        if (loop != null) loop.setPendingToken(new ParkToken(waiters));
                        return PyNull.INSTANCE;
                    }
                }))));
        inst.setField("__aexit__", new PyBuiltinFunction("Lock.__aexit__", a -> {
            if (locked[0]) {
                locked[0] = false;
                EventLoop loop = RUNNING_LOOP.get();
                if (loop != null) loop.wakeWaiters(waiters);
            }
            return newAwaitable(() -> new PyIterator(new ImmediateValueIterator(PyNull.INSTANCE)));
        }));
        return inst;
    }
}
