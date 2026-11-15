/*====================================================================
 * Jython3Interpreter — virtual machine and public entry point.
 *
 * Source → Jython3Lexer → PythonIndentStream → Jython3Parser
 *        → CompileVisitor → CompiledCode → run()
 *
 * Object model: org.example.jython3.runtime
 * Compiler:     org.example.jython3.compile
 * Java bridge:  org.example.jython3.bridge
 *====================================================================*/

package org.example.jython3;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

import org.example.jython3.bridge.JavaBridge;
import org.example.jython3.compile.CodeBuilder;
import org.example.jython3.compile.CompileVisitor;
import org.example.jython3.compile.CompiledCode;
import org.example.jython3.compile.OpCode;
import org.example.jython3.parse.PythonIndentStream;
import org.example.jython3.runtime.ArithmeticOps;
import org.example.jython3.runtime.BigInt;
import org.example.jython3.runtime.ExceptHandler;
import org.example.jython3.runtime.FastStack;
import org.example.jython3.runtime.Frame;
import org.example.jython3.runtime.ExceptionTypeRegistry;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.ModuleLoader;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyBoundMethod;
import org.example.jython3.runtime.PyBuiltinFunction;
import org.example.jython3.runtime.PyByteArray;
import org.example.jython3.runtime.PyBytes;
import org.example.jython3.runtime.PyCaller;
import org.example.jython3.runtime.PyCell;
import org.example.jython3.runtime.PyClass;
import org.example.jython3.runtime.PyClassMethod;
import org.example.jython3.runtime.PyCodeInfo;
import org.example.jython3.runtime.PyCompiledCodeWrapper;
import org.example.jython3.runtime.PyCoroutine;
import org.example.jython3.runtime.PyDict;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyException;
import org.example.jython3.runtime.PyFloat;
import org.example.jython3.runtime.PyFrameInfo;
import org.example.jython3.runtime.PyFrozenSet;
import org.example.jython3.runtime.PyFunction;
import org.example.jython3.runtime.PyGenerator;
import org.example.jython3.runtime.PyHostHandle;
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
import org.example.jython3.runtime.PySet;
import org.example.jython3.runtime.PySlice;
import org.example.jython3.runtime.PyStaticMethod;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.runtime.PySuper;
import org.example.jython3.runtime.PyTraceback;
import org.example.jython3.runtime.PyTuple;
import org.example.jython3.runtime.SystemExit;
import org.example.jython3.security.AllowlistPolicy;
import org.example.jython3.security.DenyAllPolicy;
import org.example.jython3.security.ExecutionContext;
import org.example.jython3.security.ExecutionLimits;
import org.example.jython3.security.HostAccessPolicy;
import org.example.jython3.security.JavaAccessPolicy;
import org.example.jython3.control.ControlRegistry;
import org.example.jython3.host.CapabilitySet;
import org.example.jython3.host.HostSession;
import org.example.jython3.runtime.PyHostHandle;
import org.example.jython3.security.PolicySanitizer;
import org.example.jython3.stdlib.BuiltinFunctions;
import org.example.jython3.stdlib.BuiltinMethodDispatcher;
import org.example.jython3.stdlib.BuiltinModules;
import org.example.jython3.stdlib.modules.ThreadingModule;
import org.example.jython3.ui.HeadlessUiToolkit;
import org.example.jython3.ui.UiToolkit;

import static org.example.jython3.runtime.PyOps.checkArgCount;
import static org.example.jython3.runtime.PyOps.compareObjects;
import static org.example.jython3.runtime.PyOps.isNumeric;
import static org.example.jython3.runtime.PyOps.isTruthy;
import static org.example.jython3.runtime.PyOps.pyContains;
import static org.example.jython3.runtime.PyOps.pyEquals;
import static org.example.jython3.runtime.PyOps.pyStringFormat;
import static org.example.jython3.runtime.PyOps.toDouble;
import static org.example.jython3.runtime.PyOps.toInt;
import static org.example.jython3.runtime.PyOps.typeName;

public class Jython3Interpreter implements AutoCloseable {

    // ─── Inline Caches ───────────────────────────────────────────────────
    private record AttrCacheEntry(PyClass cachedClass, long cachedClassVersion, PyObject cachedMember) {}

    // ─── Module Loader & Cache ───────────────────────────────────────────
    private final ModuleLoader moduleLoader;

    public void addModulePath(java.nio.file.Path dir) {
        moduleLoader.addSearchPath(dir);
    }

    public void insertModulePath(int index, java.nio.file.Path dir) {
        moduleLoader.insertSearchPath(index, dir);
    }

    public void removeModulePath(java.nio.file.Path dir) {
        moduleLoader.removeSearchPath(dir);
    }

    public void addImportListener(java.util.function.Consumer<java.nio.file.Path> listener) {
        moduleLoader.addImportListener(listener);
    }

    public PyList sysPath() {
        return moduleLoader.sysPath();
    }

    public PyDict sysModules() {
        return moduleLoader.sysModules();
    }

    public void setArgv(List<String> argv) {
        PyModule sysMod = resolveBuiltinModule("sys");
        if (sysMod != null && sysMod.attrs().get("argv") instanceof PyList pyArgv) {
            pyArgv.items().clear();
            if (argv != null) {
                for (String arg : argv) {
                    pyArgv.items().add(new PyStr(arg != null ? arg : ""));
                }
            }
        }
    }

    public PyModule reloadModule(String name) {
        if (name == null || name.isEmpty()) {
            throw PyErrors.error("ImportError", "cannot reload empty module name");
        }
        moduleLoader.invalidate(name);
        return loadDiskModule(name);
    }

    public java.util.List<java.nio.file.Path> moduleSearchPaths() {
        return moduleLoader.searchPaths();
    }
    // ─── Bytecode Cache ──────────────────────────────────────────────────
    private static final int MAX_CACHE_SIZE = 2048;
    private final Map<String, CompiledCode> bytecodeCache;
    /** Gate for every Java class, constructor, method, and field access. */
    private final JavaAccessPolicy javaAccessPolicy;
    /** Gate for host file system, environment, and disk module execution. */
    private final HostAccessPolicy hostAccessPolicy;
    /** Built-in exception classes owned by this interpreter. */
    private final ExceptionTypeRegistry exceptionTypes;
    /** Execution limits (instruction count, timeout, pre-allocation thresholds). */
    private final ExecutionLimits executionLimits;
    /** Active execution context tracking consumption across frames. */
    private volatile ExecutionContext currentContext;
    private volatile boolean enforceLimits = false;
    private volatile boolean closed = false;
    private final HostSession hostSession;
    private final ControlRegistry controlRegistry;
    private UiToolkit uiToolkit = new HeadlessUiToolkit();
    private volatile java.io.PrintStream stdoutOverride = null;
    private volatile java.io.PrintStream stderrOverride = null;

    public void setOut(java.io.PrintStream out) {
        this.stdoutOverride = out;
    }

    public void setErr(java.io.PrintStream err) {
        this.stderrOverride = err;
    }

    public java.io.PrintStream out() {
        java.io.PrintStream s = this.stdoutOverride;
        return s != null ? s : System.out;
    }

    public java.io.PrintStream err() {
        java.io.PrintStream s = this.stderrOverride;
        return s != null ? s : System.err;
    }

    /** Requests cooperative cancellation of the currently running evaluation/generator. */
    public void cancel() {
        ExecutionContext ctx = currentContext;
        if (ctx != null) ctx.cancel();
        this.enforceLimits = true;
    }

    /** Built-in functions and standard types template for module initialization. */
    private final Map<String, PyObject> builtinSymbols;
    private final Map<String, PyBuiltinFunction> builtinTypes;
    private final PyCaller interpreterCaller;

    public void clearCache() {
        bytecodeCache.clear();
        JavaBridge.clearResolutionCache();
    }

    public int getCacheSize() {
        return bytecodeCache.size();
    }

    /**
     * Pre-compiles a script into the bytecode cache. A script that does not
     * parse is skipped: the cache is an optimization, and the real error is
     * reported when the script is executed.
     */
    public void cacheScript(String key, String source) {
        if (source == null) return;
        try {
            CompiledCode code = compile(source);
            if (bytecodeCache.size() < MAX_CACHE_SIZE) {
                bytecodeCache.put(key, code);
            }
        } catch (InterpreterException e) {
            // Invalid source is not cached; it will fail again with a traceback on execution.
        }
    }

    // ─── Performance Profiling ─────────────────────────────────────────────
    private boolean profilingEnabled = false;
    private long compilationTime = 0;
    private long executionTime = 0;
    private int execCount = 0;

    public void enableProfiling(boolean enabled) {
        this.profilingEnabled = enabled;
        if (enabled) {
            resetProfiling();
        }
    }

    public void resetProfiling() {
        compilationTime = 0;
        executionTime = 0;
        execCount = 0;
    }

    public String getProfilingStats() {
        if (execCount == 0) {
            return "No profiling data available";
        }
        return String.format(
                "Executions: %d\nAvg Compilation: %.2f ms\nAvg Execution: %.2f ms\nTotal Time: %.2f ms",
                execCount,
                (double) compilationTime / execCount / 1_000_000,
                (double) executionTime / execCount / 1_000_000,
                (compilationTime + executionTime) / 1_000_000.0
        );
    }

    private static final int NO_ARG = 0xFFFF;

    /** Dispatch table indexed by the stable opcode number, not by enum ordinal. */
    private static final OpCode[] OP_VALUES = OpCode.byCode();

    /** Maximum call stack depth – prevents StackOverflowError from deep recursion. */
    private static final int MAX_CALL_DEPTH = 500;

    /**
     * Python call depth that is not represented on {@link #callStack}: one level per
     * nested {@code run()} (re-entrant calls such as {@code __init__}, {@code __enter__},
     * {@code __exit__}, class bodies, generator draining, isolated module execution),
     * plus any caller frames temporarily hidden when an isolated run clears
     * {@code callStack}. Counted toward {@link #MAX_CALL_DEPTH} so recursion through
     * these paths raises {@code RecursionError} instead of overflowing the Java stack.
     */
    private int nestedDepth = 0;

    private int callDepth() { return callStack.size() + nestedDepth; }

    private void checkCallDepth() {
        if (callDepth() >= MAX_CALL_DEPTH) {
            throw PyErrors.error("RecursionError", "maximum recursion depth exceeded");
        }
    }

    /**
     * Runs {@code code} in a nested interpreter loop. Accounts the nesting in
     * {@link #nestedDepth}, and converts a JVM stack overflow (possible when the host
     * thread has a small stack) into a Python {@code RecursionError} at this boundary
     * so it never escapes to the embedding application as a raw {@link StackOverflowError}.
     */
    private void runNested(CompiledCode code, int hiddenFrames) {
        checkCallDepth();
        int added = hiddenFrames + 1;
        nestedDepth += added;
        try {
            run(code);
        } catch (StackOverflowError overflow) {
            throw PyErrors.error("RecursionError", "maximum recursion depth exceeded (host stack exhausted)");
        } finally {
            nestedDepth -= added;
        }
    }

    // ─── Runtime state ────────────────────────────────────────────────────
    private final Map<String, PyObject> globals;
    private Map<String, PyObject> currentGlobals;
    private FastStack stack;
    private Map<String, PyObject> currentLocals;
    private Map<String, PyObject> closureCaptureLocals;
    private PyObject[] slotLocals;
    /** Set of variable names declared `global` in the current function frame. */
    private java.util.Set<String> globalCells;
    private final Deque<Frame> callStack = new ArrayDeque<>();
    private final Deque<FastStack> freeStacks = new ArrayDeque<>();
    private final Deque<Deque<ExceptHandler>> freeExceptStacks = new ArrayDeque<>();
    private final Deque<Deque<PyObject>> freeActiveExceptions = new ArrayDeque<>();
    private final Deque<Map<String, PyObject>> freeLocals = new ArrayDeque<>();
    private final Deque<PyObject[]> freeSlotArrays = new ArrayDeque<>();
    private static final int MAX_FRAME_POOL = 64;

    private PyObject[] borrowSlots(int needed) {
        if (!freeSlotArrays.isEmpty()) {
            PyObject[] arr = freeSlotArrays.pop();
            if (arr.length >= needed) return arr;
        }
        return new PyObject[Math.max(16, needed)];
    }

    private void recycleSlots(PyObject[] sl, int slotCount) {
        if (sl != null && freeSlotArrays.size() < MAX_FRAME_POOL) {
            int limit = Math.min(sl.length, Math.max(slotCount, 16));
            for (int i = 0; i < limit; i++) sl[i] = null;
            freeSlotArrays.push(sl);
        }
    }

    private Map<String, PyObject> borrowLocals() {
        return freeLocals.isEmpty() ? new HashMap<>(16) : freeLocals.pop();
    }

    private void recycleLocals(Map<String, PyObject> m) {
        if (m != null && m != globals && m != currentGlobals
                && (currentFunction == null || m != currentFunction.closure())
                && m instanceof HashMap<?, ?>
                && freeLocals.size() < MAX_FRAME_POOL && m.size() <= 64) {
            m.clear();
            freeLocals.push(m);
        }
    }

    private FastStack borrowStack() {
        return freeStacks.isEmpty() ? new FastStack() : freeStacks.pop();
    }

    private void recycleStack(FastStack s) {
        if (s != null && freeStacks.size() < MAX_FRAME_POOL) {
            s.clear();
            freeStacks.push(s);
        }
    }

    private Deque<ExceptHandler> borrowExceptStack() {
        return freeExceptStacks.isEmpty() ? new ArrayDeque<>(4) : freeExceptStacks.pop();
    }

    private void recycleExceptStack(Deque<ExceptHandler> d) {
        if (d != null && freeExceptStacks.size() < MAX_FRAME_POOL) {
            d.clear();
            freeExceptStacks.push(d);
        }
    }

    private Deque<PyObject> borrowActiveExceptions() {
        return freeActiveExceptions.isEmpty() ? new ArrayDeque<>(2) : freeActiveExceptions.pop();
    }

    private void recycleActiveExceptions(Deque<PyObject> d) {
        if (d != null && freeActiveExceptions.size() < MAX_FRAME_POOL) {
            d.clear();
            freeActiveExceptions.push(d);
        }
    }
    /** Per-frame exception handler stack — saved/restored on function call boundaries. */
    private Deque<ExceptHandler> exceptStack = new ArrayDeque<>();
    /**
     * Python exceptions currently being handled, innermost last. Bare
     * {@code raise} uses the top; entering a handler pushes and leaving it
     * pops. It is per frame, so a callee never inherits the caller's handler.
     */
    private Deque<PyObject> activeExceptions = new ArrayDeque<>();
    /** Outer active exception context preserved across re-entrant {@code runNested()} calls. */
    private final Deque<PyException> outerActiveContexts = new ArrayDeque<>();
    private static final java.util.concurrent.atomic.AtomicLong NEXT_INTERPRETER_ID =
            new java.util.concurrent.atomic.AtomicLong(1);
    private final long interpreterId = NEXT_INTERPRETER_ID.getAndIncrement();

    /** Currently executing lazy generator frame (null outside {@link #stepGenerator}). */
    private PyGenerator activeGenerator = null;
    /** Source location of the instruction being executed, for tracebacks. */
    private String currentFile = "<stdin>";
    private String currentFunc = "<module>";
    private PyFunction currentFunction = null;
    private int currentLine = 0;
    /** Source text of the current script, used to quote lines in a traceback. */
    private String currentSource = "";

    // ─── Factory Methods ──────────────────────────────────────────────────

    /**
     * Creates an interpreter for trusted scripts (e.g. application internal automation).
     * Grants the standard Java allowlist and permissive host OS access.
     */
    public static Jython3Interpreter trusted() {
        return new Jython3Interpreter(
                AllowlistPolicy.trusted(),
                HostAccessPolicy.PERMISSIVE,
                ExecutionLimits.TRUSTED,
                ExceptionTypeRegistry.standard(),
                CapabilitySet.of(org.example.jython3.host.HostCapability.THREADS));
    }

    /**
     * Creates an interpreter for untrusted or restricted script execution.
     * Denies all Java reflection interop and denies all host OS filesystem/environment access,
     * and strictly enforces instruction and timeout execution limits.
     */
    public static Jython3Interpreter restricted() {
        return new Jython3Interpreter(DenyAllPolicy.INSTANCE, HostAccessPolicy.DENY_ALL, ExecutionLimits.SANDBOXED);
    }

    // ─── Constructor ──────────────────────────────────────────────────────
    public Jython3Interpreter() {
        this(AllowlistPolicy.trusted(), HostAccessPolicy.PERMISSIVE, ExecutionLimits.TRUSTED);
    }

    /**
     * Creates an interpreter whose Java access is controlled by {@code javaAccessPolicy}.
     * <p><b>Security Default:</b> Unless an explicit {@link HostAccessPolicy} is provided,
     * host filesystem and OS access defaults strictly to {@link HostAccessPolicy#DENY_ALL}.
     */
    public Jython3Interpreter(JavaAccessPolicy javaAccessPolicy) {
        this(javaAccessPolicy,
             HostAccessPolicy.DENY_ALL,
             ExecutionLimits.SANDBOXED,
             ExceptionTypeRegistry.standard());
    }

    public Jython3Interpreter(JavaAccessPolicy javaAccessPolicy, HostAccessPolicy hostAccessPolicy) {
        this(javaAccessPolicy, hostAccessPolicy,
             ExecutionLimits.SANDBOXED,
             ExceptionTypeRegistry.standard());
    }

    public Jython3Interpreter(JavaAccessPolicy javaAccessPolicy,
                              HostAccessPolicy hostAccessPolicy,
                              ExecutionLimits executionLimits) {
        this(javaAccessPolicy, hostAccessPolicy, executionLimits, ExceptionTypeRegistry.standard());
    }

    /**
     * Creates an interpreter with explicit access policies, resource limits, and exception-type registry.
     */
    public Jython3Interpreter(JavaAccessPolicy javaAccessPolicy,
                              HostAccessPolicy hostAccessPolicy,
                              ExecutionLimits executionLimits,
                              ExceptionTypeRegistry exceptionTypes) {
        this(javaAccessPolicy, hostAccessPolicy, executionLimits, exceptionTypes, CapabilitySet.none());
    }

    /**
     * Creates an interpreter with explicit access policies, resource limits,
     * exception-type registry, and deny-by-default host {@link CapabilitySet}.
     */
    public Jython3Interpreter(JavaAccessPolicy javaAccessPolicy,
                              HostAccessPolicy hostAccessPolicy,
                              ExecutionLimits executionLimits,
                              ExceptionTypeRegistry exceptionTypes,
                              CapabilitySet capabilities) {
        this.javaAccessPolicy = Objects.requireNonNull(javaAccessPolicy, "javaAccessPolicy");
        this.hostAccessPolicy = Objects.requireNonNull(hostAccessPolicy, "hostAccessPolicy");
        this.executionLimits = Objects.requireNonNull(executionLimits, "executionLimits");
        this.exceptionTypes = Objects.requireNonNull(exceptionTypes, "exceptionTypes");
        this.moduleLoader = new ModuleLoader();
        this.bytecodeCache = new ConcurrentHashMap<>();
        this.controlRegistry = new ControlRegistry();
        this.builtinSymbols = new HashMap<>();
        this.builtinTypes = new HashMap<>();
        CapabilitySet effectiveCaps = capabilities != null ? capabilities : CapabilitySet.none();
        this.globals = effectiveCaps.has(org.example.jython3.host.HostCapability.THREADS)
                ? new ConcurrentHashMap<>() : new HashMap<>();
        this.currentGlobals = this.globals;
        this.hostSession = new HostSession(
                effectiveCaps,
                this::invokeHostCallback,
                this.javaAccessPolicy);
        this.interpreterCaller = createCaller();
        registerBuiltins();
    }

    /** Child-worker constructor sharing globals/session/caches with isolated per-thread VM stacks. */
    private Jython3Interpreter(Jython3Interpreter parent) {
        this.javaAccessPolicy = parent.javaAccessPolicy;
        this.hostAccessPolicy = parent.hostAccessPolicy;
        this.executionLimits = parent.executionLimits;
        this.exceptionTypes = parent.exceptionTypes;
        this.moduleLoader = parent.moduleLoader;
        this.bytecodeCache = parent.bytecodeCache;
        this.controlRegistry = parent.controlRegistry;
        this.uiToolkit = parent.uiToolkit;
        this.builtinSymbols = parent.builtinSymbols;
        this.builtinTypes = parent.builtinTypes;
        this.globals = parent.globals;
        this.currentGlobals = parent.globals;
        this.hostSession = parent.hostSession;
        this.interpreterCaller = createCaller();
    }

    private PyCaller createCaller() {
        return new PyCaller() {
            @Override public PyObject call(PyObject callable, PyObject[] args) {
                return callPyCallable(callable, args);
            }

            @Override public PyObject callWithKw(PyObject callable, PyObject[] posArgs, Map<String, PyObject> kwargs) {
                if (kwargs == null || kwargs.isEmpty()) return callPyCallable(callable, posArgs);
                String[] kwNames = new String[kwargs.size()];
                PyObject[] kwVals = new PyObject[kwargs.size()];
                int idx = 0;
                for (var e : kwargs.entrySet()) {
                    kwNames[idx] = e.getKey();
                    kwVals[idx] = e.getValue();
                    idx++;
                }
                return callPyCallableKw(callable, posArgs, kwNames, kwVals);
            }

            @Override public PyObject stepGenerator(PyGenerator gen) {
                return Jython3Interpreter.this.sendGenerator(gen, PyNull.INSTANCE);
            }

            @Override public PyObject sendGenerator(PyGenerator gen, PyObject value) {
                return Jython3Interpreter.this.sendGenerator(gen, value);
            }

            @Override public PyObject throwGenerator(PyGenerator gen, PyObject[] args) {
                return Jython3Interpreter.this.throwGenerator(gen, args);
            }

            @Override public PyObject closeGenerator(PyGenerator gen) {
                return Jython3Interpreter.this.closeGenerator(gen);
            }

            @Override public ExecutionLimits executionLimits() {
                return executionLimits;
            }

            @Override public ExecutionContext executionContext() {
                return currentContext;
            }

            @Override public PyObject resolveZeroArgSuper() {
                if (currentFunction == null || currentLocals == null || currentFunction.params().isEmpty()) {
                    throw PyErrors.error("RuntimeError", "super(): no arguments");
                }
                String selfName = currentFunction.params().get(0);
                PyObject selfVal = currentLocals.get(selfName);
                if (selfVal instanceof PyCell cell) selfVal = cell.get();
                if (!(selfVal instanceof PyInstance inst)) {
                    throw PyErrors.error("RuntimeError", "super(): arg[0] must be an instance");
                }
                PyClass owner = findDefiningClass(inst.pyClass(), currentFunction);
                return new PySuper(owner != null ? owner : inst.pyClass(), inst);
            }

            @Override public java.util.Map<String, PyObject> currentScope() {
                return currentLocals != null ? currentLocals : globals;
            }

            @Override public java.util.Map<String, PyObject> globalsScope() {
                return currentGlobals != null ? currentGlobals : globals;
            }

            @Override public java.io.PrintStream out() {
                return Jython3Interpreter.this.out();
            }

            @Override public java.io.PrintStream err() {
                return Jython3Interpreter.this.err();
            }
        };
    }

    private ThreadingModule.SpawnedThread spawnVirtualThread(
            PyObject target, PyObject[] posArgs, Map<String, PyObject> kwArgs, String name, boolean daemon) {
        final ExecutionContext parentCtx = this.currentContext;
        final Jython3Interpreter worker = new Jython3Interpreter(this);
        final var failureRef = new java.util.concurrent.atomic.AtomicReference<Throwable>();
        final Thread[] threadRef = new Thread[1];
        final AutoCloseable cleanup = () -> {
            worker.cancel();
            Thread t = threadRef[0];
            if (t != null && t.isAlive()) {
                t.interrupt();
                try { t.join(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
            }
        };
        hostSession.acquireThreadSlot(cleanup, name);

        Thread vThread = Thread.ofVirtual().name(name).unstarted(() -> {
            ExceptionTypeRegistry prevTypes = ExceptionTypeRegistry.install(worker.exceptionTypes);
            PyCaller prevCaller = PyCaller.CURRENT.get();
            PyCaller.CURRENT.set(worker.interpreterCaller);
            try {
                worker.stack = new FastStack();
                worker.currentGlobals = worker.globals;
                worker.currentLocals = worker.globals;
                worker.currentContext = new ExecutionContext(worker.executionLimits, parentCtx);
                worker.enforceLimits = worker.currentContext.hasActiveLimits();
                worker.interpreterCaller.callWithKw(target, posArgs, kwArgs);
            } catch (Throwable t) {
                failureRef.set(t);
                hostSession.recordCallbackFailure("thread:" + name, t);
            } finally {
                worker.currentContext = null;
                worker.enforceLimits = false;
                if (prevCaller != null) PyCaller.CURRENT.set(prevCaller);
                else PyCaller.CURRENT.remove();
                ExceptionTypeRegistry.restore(prevTypes);
                hostSession.releaseThreadSlot(cleanup);
            }
        });
        threadRef[0] = vThread;

        return new ThreadingModule.SpawnedThread() {
            @Override public String name() { return vThread.getName(); }
            @Override public long ident() { return vThread.threadId(); }
            @Override public boolean isAlive() { return vThread.isAlive(); }
            @Override public void start() {
                try {
                    vThread.start();
                } catch (Throwable t) {
                    hostSession.releaseThreadSlot(cleanup);
                    throw t;
                }
            }
            @Override public void join(long timeoutMs) {
                try {
                    long deadline = timeoutMs > 0 ? System.currentTimeMillis() + timeoutMs : Long.MAX_VALUE;
                    while (vThread.isAlive()) {
                        if (hostSession.isClosed()) {
                            throw PyErrors.error("KeyboardInterrupt", "Session closed while joining thread '" + name + "'");
                        }
                        if (parentCtx != null && parentCtx.isCancelled()) {
                            throw PyErrors.error("KeyboardInterrupt", "Cancelled while joining thread '" + name + "'");
                        }
                        if (timeoutMs == 0) break;
                        long rem = timeoutMs > 0 ? deadline - System.currentTimeMillis() : 50L;
                        if (timeoutMs > 0 && rem <= 0) break;
                        vThread.join(Math.min(50L, Math.max(1L, rem)));
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw PyErrors.error("KeyboardInterrupt", "Interrupted joining thread '" + name + "'");
                }
            }
            @Override public Throwable failure() { return failureRef.get(); }
        };
    }

    private static PyClass findDefiningClass(PyClass start, PyFunction targetFn) {
        if (start == null || targetFn == null) return null;
        var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<PyClass, Boolean>());
        var queue = new java.util.ArrayDeque<PyClass>();
        queue.add(start);
        while (!queue.isEmpty()) {
            PyClass c = queue.removeFirst();
            if (!seen.add(c)) continue;
            for (PyObject member : c.members().values()) {
                if (member == targetFn) return c;
            }
            for (PyClass b : c.getBases()) {
                queue.add(b);
            }
        }
        return null;
    }

    public Jython3Interpreter(JavaAccessPolicy javaAccessPolicy,
                              HostAccessPolicy hostAccessPolicy,
                              ExceptionTypeRegistry exceptionTypes) {
        this(javaAccessPolicy, hostAccessPolicy,
             ExecutionLimits.SANDBOXED,
             exceptionTypes);
    }

    public Jython3Interpreter(JavaAccessPolicy javaAccessPolicy,
                              ExceptionTypeRegistry exceptionTypes) {
        this(javaAccessPolicy, HostAccessPolicy.DENY_ALL,
             ExecutionLimits.SANDBOXED,
             exceptionTypes);
    }

    public ExecutionLimits executionLimits() {
        return executionLimits;
    }

    /** The policy this interpreter applies to host I/O and OS access. */
    public HostAccessPolicy hostAccessPolicy() {
        return hostAccessPolicy;
    }

    /** The policy this interpreter applies to Java access. */
    public JavaAccessPolicy javaAccessPolicy() {
        return javaAccessPolicy;
    }

    /** The capability-gated host session owned by this interpreter. */
    public HostSession hostSession() {
        return hostSession;
    }

    public ControlRegistry controlRegistry() {
        return controlRegistry;
    }

    public UiToolkit uiToolkit() {
        return uiToolkit;
    }

    public void setUiToolkit(UiToolkit uiToolkit) {
        this.uiToolkit = Objects.requireNonNull(uiToolkit, "uiToolkit");
    }

    public boolean isClosed() {
        return closed;
    }

    private static final java.util.concurrent.ScheduledExecutorService CALLBACK_WATCHDOG =
            java.util.concurrent.Executors.newSingleThreadScheduledExecutor(r -> {
                Thread t = new Thread(r, "jython3-callback-watchdog");
                t.setDaemon(true);
                return t;
            });

    /**
     * Invokes a Python callback from a host UI/event/timer/control adapter with
     * thread-safe context installation, bounded callback timeout/limits, and cleanup.
     */
    public synchronized PyObject invokeHostCallback(PyObject callable, PyObject... args) {
        if (closed) {
            throw PyErrors.error("RuntimeError", "interpreter is closed");
        }
        ExceptionTypeRegistry previousTypes = ExceptionTypeRegistry.install(exceptionTypes);
        PyCaller previousCaller = PyCaller.CURRENT.get();
        PyCaller.CURRENT.set(interpreterCaller);
        ExecutionContext prevCtx = currentContext;
        boolean prevEnforce = enforceLimits;

        var cbTimeout = hostSession.capabilities().callbackTimeout();
        var cbLimits = ExecutionLimits.builder()
                .maxInstructions(executionLimits.maxInstructions())
                .timeout(cbTimeout)
                .maxSourceLength(executionLimits.maxSourceLength())
                .maxCollectionSize(executionLimits.maxCollectionSize())
                .maxStringLength(executionLimits.maxStringLength())
                .maxExponent(executionLimits.maxExponent())
                .build();
        ExecutionContext cbCtx = new ExecutionContext(cbLimits, prevCtx);
        final Thread cbThread = Thread.currentThread();
        final var callbackDone = new java.util.concurrent.atomic.AtomicBoolean(false);
        final var watchdogFired = new java.util.concurrent.atomic.AtomicBoolean(false);
        java.util.concurrent.ScheduledFuture<?> watchdogFuture = null;
        if (cbTimeout != null && !cbTimeout.isZero() && !cbTimeout.isNegative()) {
            long timeoutMs = Math.max(1L, cbTimeout.toMillis());
            watchdogFuture = CALLBACK_WATCHDOG.schedule(() -> {
                if (callbackDone.compareAndSet(false, true)) {
                    watchdogFired.set(true);
                    cbCtx.cancelByTimeout();
                    cbThread.interrupt();
                }
            }, timeoutMs, java.util.concurrent.TimeUnit.MILLISECONDS);
        }

        final var savedSlots = slotLocals;
        try {
            currentContext = cbCtx;
            enforceLimits = cbCtx.hasActiveLimits();
            hostSession.bindContext(cbCtx);
            if (stack == null) stack = new FastStack();
            if (currentGlobals == null) currentGlobals = globals;
            if (currentLocals == null) currentLocals = globals;
            slotLocals = null;
            return callPyCallable(callable, args != null ? args : new PyObject[0]);
        } catch (InterpreterException ie) {
            if (watchdogFired.get() && (cbCtx.isTimedOut() || "KeyboardInterrupt".equals(ie.typeName()))) {
                throw PyErrors.error("TimeoutError", "Host callback timed out: limit=" + cbTimeout);
            }
            throw ie;
        } finally {
            slotLocals = savedSlots;
            callbackDone.set(true);
            if (watchdogFuture != null) {
                watchdogFuture.cancel(false);
            }
            if (watchdogFired.get()) {
                Thread.interrupted(); // clear watchdog interrupt so UI/host thread remains clean
            }
            hostSession.bindContext(prevCtx);
            currentContext = prevCtx;
            enforceLimits = prevEnforce;
            if (previousCaller != null) {
                PyCaller.CURRENT.set(previousCaller);
            } else {
                PyCaller.CURRENT.remove();
            }
            ExceptionTypeRegistry.restore(previousTypes);
        }
    }

    @Override
    public void close() {
        closed = true;
        cancel();
        hostSession.beginTeardown();
        synchronized (this) {
            hostSession.close();
            globals.clear();
            moduleLoader.clear();
            freeStacks.clear();
            freeExceptStacks.clear();
            freeActiveExceptions.clear();
        }
    }

    /** The exception classes owned by this interpreter. */
    public ExceptionTypeRegistry exceptionTypes() {
        return exceptionTypes;
    }

    /** Install built-in functions into the global namespace. */
    private void registerBuiltins() {
        builtinTypes.clear();
        BuiltinFunctions.register(builtinSymbols::put, interpreterCaller, javaAccessPolicy, builtinTypes);
        for (var entry : exceptionTypes.types().entrySet()) {
            builtinSymbols.put(entry.getKey(), entry.getValue());
        }
        globals.putAll(builtinSymbols);
    }


    // ─── Public API ───────────────────────────────────────────────────────

    /**
     * Compile and run {@code source}. {@link InterpreterException} is printed
     * as a traceback. {@link SystemExit} is rethrown.
     */
    public void exec(String source) {
        try {
            eval(source);
        } catch (SystemExit e) {
            throw e;
        } catch (InterpreterException e) {
            err().println(formatTraceback(e, "<stdin>", source));
        }
    }

    /**
     * Read and execute a Python script file, attributing traceback frames to
     * {@code scriptPath} and setting {@code __file__} and {@code __name__}.
     */
    public synchronized void evalFile(java.nio.file.Path scriptPath) {
        Objects.requireNonNull(scriptPath, "scriptPath");
        if (!hostAccessPolicy.canExecFile(scriptPath)) {
            throw PyErrors.error("SecurityError", "execution of file '" + scriptPath + "' is restricted");
        }
        String src;
        try {
            src = java.nio.file.Files.readString(scriptPath, java.nio.charset.StandardCharsets.UTF_8);
        } catch (java.io.IOException e) {
            throw PyErrors.error("IOError", "cannot open '" + scriptPath + "': " + e.getMessage());
        }
        String absPath = scriptPath.toAbsolutePath().toString();
        globals.put("__file__", new PyStr(absPath));
        globals.put("__name__", new PyStr("__main__"));
        eval(src, absPath);
    }

    /**
     * Renders a Python-style traceback for {@code e}. Frames are stored
     * innermost-first while unwinding and are printed outermost-first, matching
     * CPython's "most recent call last" ordering.
     */
    public String formatTraceback(InterpreterException e, String filename, String source) {
        if (e.value() instanceof PyException pe) {
            var sb = new StringBuilder();
            var seen = java.util.Collections.newSetFromMap(new java.util.IdentityHashMap<PyException, Boolean>());
            formatExceptionChain(sb, pe, e, filename, source, seen);
            return sb.toString();
        }
        var sb = new StringBuilder("Traceback (most recent call last):\n");
        appendFrames(sb, e.traceback(), e.line(), e.function(), filename, source);
        sb.append(e.getMessage());
        return sb.toString();
    }

    private void formatExceptionChain(StringBuilder sb, PyException pe, InterpreterException primaryCarrier,
                                      String filename, String source, java.util.Set<PyException> seen) {
        if (pe == null || !seen.add(pe)) return;
        if (pe.cause() != null) {
            formatExceptionChain(sb, pe.cause(), null, filename, source, seen);
            sb.append("\n\nThe above exception was the direct cause of the following exception:\n\n");
        } else if (pe.context() != null && !pe.suppressContext()) {
            formatExceptionChain(sb, pe.context(), null, filename, source, seen);
            sb.append("\n\nDuring handling of the above exception, another exception occurred:\n\n");
        }
        sb.append("Traceback (most recent call last):\n");
        var frames = !pe.traceback().isEmpty()
                ? pe.traceback()
                : (primaryCarrier != null ? primaryCarrier.traceback() : List.<InterpreterException.TraceEntry>of());
        int fallbackLine = primaryCarrier != null ? primaryCarrier.line() : 0;
        String fallbackFunc = primaryCarrier != null ? primaryCarrier.function() : null;
        appendFrames(sb, frames, fallbackLine, fallbackFunc, filename, source);
        sb.append(pe);
    }

    private static void appendFrames(StringBuilder sb, List<InterpreterException.TraceEntry> frames,
                                     int fallbackLine, String fallbackFunc, String filename, String source) {
        if (frames.isEmpty()) {
            int line = fallbackLine > 0 ? fallbackLine : 1;
            sb.append("  File \"").append(filename).append("\", line ").append(line)
              .append(", in ").append(fallbackFunc != null ? fallbackFunc : "<module>").append('\n');
            appendSnippet(sb, source, line);
        } else {
            for (int i = frames.size() - 1; i >= 0; i--) {
                var t = frames.get(i);
                String file = t.file() != null ? t.file() : filename;
                String func = t.function() != null ? t.function() : "<module>";
                sb.append("  File \"").append(file).append("\", line ").append(t.line())
                  .append(", in ").append(func).append('\n');
                if (Objects.equals(file, filename)) {
                    appendSnippet(sb, source, t.line());
                }
            }
        }
    }

    private static void appendSnippet(StringBuilder sb, String source, int line) {
        if (source == null || source.isEmpty() || line <= 0) return;
        String[] lines = source.split("\n", -1);
        if (line > lines.length) return;
        String text = lines[line - 1].strip();
        if (!text.isEmpty()) sb.append("    ").append(text).append('\n');
    }

    /** Keeps the frames on the value so they survive after the carrier is dropped. */
    private static void captureTraceback(InterpreterException ie) {
        if (ie.value() instanceof PyException pe) ie.flushTracebackTo(pe);
    }

    private PyException currentContextException() {
        if (activeExceptions.peekLast() instanceof PyException active) {
            return active;
        }
        for (Frame cf : callStack) {
            if (cf.activeExceptions() != null && cf.activeExceptions().peekLast() instanceof PyException active) {
                return active;
            }
        }
        return outerActiveContexts.peekLast();
    }

    /** Drops active exceptions pushed by handlers that the unwind abandoned. */
    private void truncateActiveExceptions(int depth) {
        while (activeExceptions.size() > depth) {
            activeExceptions.removeLast();
        }
    }

    /** Discards intermediate operands or inner loop iterators pushed inside a try block before an exception occurred. */
    private void truncateStack(int depth) {
        while (stack.size() > depth) {
            stack.pop();
        }
    }

    /** Builds an exception value for {@code type} with the given arguments. */
    private static PyException exceptionValue(PyClass type, PyObject[] args) {
        return new PyException(type, List.of(args));
    }

    /**
     * Turns a raised value into the carrier. A class is instantiated with no
     * arguments, an existing value is re-raised, and anything else is a
     * {@code TypeError}, as in Python. A new exception raised while another is
     * being handled keeps the active one as its context.
     */
    private InterpreterException toRaise(PyObject value) {
        PyException exception;
        if (value instanceof PyException pe) {
            exception = pe;
        } else if (value instanceof PyClass cls && cls.isExceptionType()) {
            exception = new PyException(cls, List.of());
        } else {
            throw newRaiseTypeError();
        }

        PyException active = currentContextException();
        if (active != null) {
            exception.attachContextIfAbsent(active);
        }
        return PyErrors.errorValue(exception);
    }

    /**
     * {@code raise exc from cause}. The cause may be an exception instance, an
     * exception class (instantiated with no arguments) or {@code None}; anything
     * else is a {@code TypeError}. Sets {@code __cause__} and
     * {@code __suppress_context__}; {@code __context__} is still recorded.
     */
    private InterpreterException toRaiseFrom(PyObject value, PyObject causeObj) {
        PyException exception;
        if (value instanceof PyException pe) {
            exception = pe;
        } else if (value instanceof PyClass cls && cls.isExceptionType()) {
            exception = new PyException(cls, List.of());
        } else {
            throw newRaiseTypeError();
        }
        PyException cause;
        if (causeObj instanceof PyNull) {
            cause = null;
        } else if (causeObj instanceof PyException ce) {
            cause = ce;
        } else if (causeObj instanceof PyClass cc && cc.isExceptionType()) {
            cause = new PyException(cc, List.of());
        } else {
            PyException error = new PyException(
                    ExceptionTypeRegistry.current().type("TypeError"),
                    "exception causes must derive from BaseException");
            PyException active = currentContextException();
            if (active != null) {
                error.attachContextIfAbsent(active);
            }
            throw PyErrors.errorValue(error);
        }
        exception.setExplicitCause(cause);
        PyException active = currentContextException();
        if (active != null) {
            exception.attachContextIfAbsent(active);
        }
        return PyErrors.errorValue(exception);
    }

    /**
     * The error for raising a value that is not an exception. It goes through
     * the same context rule as any other error raised while a handler is
     * active.
     */
    private InterpreterException newRaiseTypeError() {
        PyException error = new PyException(
                ExceptionTypeRegistry.current().type("TypeError"),
                "exceptions must derive from BaseException");
        PyException active = currentContextException();
        if (active != null) {
            error.attachContextIfAbsent(active);
        }
        return PyErrors.errorValue(error);
    }

    /**
     * Compile and run {@code source}. Failures propagate as
     * {@link InterpreterException} instead of being printed.
     */
    public void eval(String source) {
        eval(source, "<stdin>");
    }

    /**
     * Compile and run {@code source}, attributing any traceback frames to
     * {@code filename}.
     */
    public synchronized void eval(String source, String filename) {
        if (closed) throw PyErrors.error("RuntimeError", "interpreter is closed");
        ExceptionTypeRegistry previousTypes = ExceptionTypeRegistry.install(exceptionTypes);
        PyCaller previousCaller = PyCaller.CURRENT.get();
        PyCaller.CURRENT.set(interpreterCaller);
        try {
            stack = new FastStack();
            currentGlobals = globals;
            currentLocals = globals;
            slotLocals = null;
            exceptStack = new ArrayDeque<>();
            activeExceptions = new ArrayDeque<>();
            callStack.clear();
            nestedDepth = 0;
            outerActiveContexts.clear();
            activeGenerator = null;
            currentContext = new ExecutionContext(executionLimits);
            enforceLimits = currentContext.hasActiveLimits();
            hostSession.bindContext(currentContext);
            currentFile = filename;
            currentFunc = "<module>";
            currentFunction = null;
            currentLine = 0;
            currentSource = source;
            execCount++;

            long compileStart = profilingEnabled ? System.nanoTime() : 0;
            CompiledCode code = compile(source);
            if (profilingEnabled) {
                compilationTime += System.nanoTime() - compileStart;
            }

            long execStart = profilingEnabled ? System.nanoTime() : 0;
            run(code);
            if (profilingEnabled) {
                executionTime += System.nanoTime() - execStart;
            }
        } finally {
            hostSession.bindContext(null);
            activeGenerator = null;
            currentContext = null;
            enforceLimits = false;
            if (previousCaller != null) {
                PyCaller.CURRENT.set(previousCaller);
            } else {
                PyCaller.CURRENT.remove();
            }
            ExceptionTypeRegistry.restore(previousTypes);
        }
    }

    /**
     * Executes precompiled bytecode directly without re-lexing, parsing, or compiling.
     */
    public void evalCode(CompiledCode code) {
        evalCode(code, "<compiled>");
    }

    /**
     * Executes precompiled bytecode directly, attributing traceback frames to {@code filename}.
     */
    public synchronized void evalCode(CompiledCode code, String filename) {
        if (closed) throw PyErrors.error("RuntimeError", "interpreter is closed");
        ExceptionTypeRegistry previousTypes = ExceptionTypeRegistry.install(exceptionTypes);
        PyCaller previousCaller = PyCaller.CURRENT.get();
        PyCaller.CURRENT.set(interpreterCaller);
        try {
            stack = new FastStack();
            currentGlobals = globals;
            currentLocals = globals;
            slotLocals = null;
            exceptStack = new ArrayDeque<>();
            activeExceptions = new ArrayDeque<>();
            callStack.clear();
            nestedDepth = 0;
            outerActiveContexts.clear();
            activeGenerator = null;
            currentContext = new ExecutionContext(executionLimits);
            enforceLimits = currentContext.hasActiveLimits();
            hostSession.bindContext(currentContext);
            currentFile = filename;
            currentFunc = "<module>";
            currentFunction = null;
            currentLine = 0;
            currentSource = "";
            execCount++;

            long execStart = profilingEnabled ? System.nanoTime() : 0;
            run(code);
            if (profilingEnabled) {
                executionTime += System.nanoTime() - execStart;
            }
        } finally {
            hostSession.bindContext(null);
            activeGenerator = null;
            currentContext = null;
            enforceLimits = false;
            if (previousCaller != null) {
                PyCaller.CURRENT.set(previousCaller);
            } else {
                PyCaller.CURRENT.remove();
            }
            ExceptionTypeRegistry.restore(previousTypes);
        }
    }

    /**
     * Resets execution state (globals, locals, stacks, frames) between benchmark or interactive runs
     * without reconstructing the interpreter or re-registering policies.
     */
    public void resetExecutionState() {
        cancel();
        hostSession.invalidateHandles();
        synchronized (this) {
            hostSession.reset();
            globals.clear();
            moduleLoader.clear();
            registerBuiltins();
            currentGlobals = globals;
            currentLocals = globals;
            if (stack != null) stack.clear();
            callStack.clear();
            nestedDepth = 0;
            outerActiveContexts.clear();
            exceptStack.clear();
            activeExceptions.clear();
            freeStacks.clear();
            freeExceptStacks.clear();
            freeActiveExceptions.clear();
            activeGenerator = null;
            globalCells = null;
            currentFunc = "<module>";
            currentFunction = null;
            currentFile = "<stdin>";
            currentLine = 0;
        }
    }

    /**
     * Executes {@code code} within an isolated VM state (fresh stack, callStack, exceptStack)
     * using the specified {@code executionLocals}, ensuring the caller's frames and operand stacks
     * are perfectly preserved and restored upon completion or exception.
     */
    private void executeIsolated(CompiledCode code, Map<String, PyObject> executionLocals, String filename) {
        var savedStack = stack;
        var savedLocals = currentLocals;
        var savedSlots = slotLocals;
        var savedGlobals = currentGlobals;
        var savedExcept = exceptStack;
        var savedActive = activeExceptions;
        var savedCallStack = new ArrayDeque<>(callStack);
        var savedActiveGen = activeGenerator;
        var savedFunc = currentFunc;
        var savedFunction = currentFunction;
        var savedFile = currentFile;
        var savedLine = currentLine;
        try {
            stack = new FastStack();
            currentGlobals = executionLocals;
            currentLocals = executionLocals;
            slotLocals = null;
            exceptStack = new ArrayDeque<>();
            activeExceptions = new ArrayDeque<>();
            callStack.clear();
            activeGenerator = null;
            currentFunc = "<module>";
            currentFunction = null;
            currentFile = filename != null ? filename : "<module>";
            currentLine = 0;
            runNested(code, savedCallStack.size());
        } finally {
            stack = savedStack;
            currentLocals = savedLocals;
            slotLocals = savedSlots;
            currentGlobals = savedGlobals;
            exceptStack = savedExcept;
            activeExceptions = savedActive;
            callStack.clear();
            callStack.addAll(savedCallStack);
            activeGenerator = savedActiveGen;
            currentFunc = savedFunc;
            currentFunction = savedFunction;
            currentFile = savedFile;
            currentLine = savedLine;
        }
    }

    private PyGenerator createGenerator(PyFunction fn, Map<String, PyObject> boundLocals) {
        return new PyGenerator(fn, boundLocals, interpreterId, currentGlobals,
                currentFile, currentLine, currentContext);
    }

    /**
     * Resumes a lazy {@link PyGenerator} frame from its saved {@code pc} until its
     * next {@code YIELD_VALUE}, completion ({@code StopIteration}), or exception.
     */
    private PyObject stepGenerator(PyGenerator gen) {
        return sendGenerator(gen, PyNull.INSTANCE);
    }

    private PyObject sendGenerator(PyGenerator gen, PyObject sendValue) {
        PyGenerator.State prevState = gen.state();
        gen.beginSend(interpreterId, sendValue);
        if (prevState == PyGenerator.State.SUSPENDED) {
            gen.operandStack().push(sendValue != null ? sendValue : PyNull.INSTANCE);
        }
        return executeGeneratorStep(gen, gen.pc(), null);
    }

    private PyObject throwGenerator(PyGenerator gen, PyObject[] args) {
        InterpreterException thrown = buildGeneratorThrowException(args);
        return throwGeneratorException(gen, thrown);
    }

    private PyObject throwGeneratorException(PyGenerator gen, InterpreterException thrown) {
        PyGenerator.State prevState = gen.beginThrow(interpreterId);
        if (prevState != PyGenerator.State.SUSPENDED) {
            throw thrown;
        }
        int[] gbc = gen.code().bytecode();
        int gpc = gen.pc();
        if (gpc < gbc.length && (OP_VALUES[gbc[gpc] >>> 16] == OpCode.YIELD_FROM
                || OP_VALUES[gbc[gpc] >>> 16] == OpCode.AWAIT_FROM)) {
            PyObject sub = gen.operandStack().peek();
            PyGenerator targetGen = (sub instanceof PyGenerator sg) ? sg
                    : (sub instanceof PyCoroutine sc ? sc.runner() : null);
            if (targetGen != null) {
                try {
                    PyObject y = throwGeneratorException(targetGen, thrown);
                    gen.suspendAt(gpc, gen.operandStack(), gen.locals(), gen.exceptHandlers(),
                            gen.activeExceptions(), gen.globalCells(), gen.moduleGlobals(), gen.line());
                    return y;
                } catch (InterpreterException subErr) {
                    gen.operandStack().pop();
                    if ("StopIteration".equals(subErr.typeName())) {
                        PyObject retVal = (subErr.value() instanceof PyException pe && !pe.args().isEmpty())
                                ? pe.args().get(0) : PyNull.INSTANCE;
                        gen.operandStack().push(retVal);
                        return executeGeneratorStep(gen, gbc[gpc] & 0xFFFF, null);
                    }
                    return executeGeneratorStep(gen, gpc, subErr);
                }
            }
            gen.operandStack().pop();
        }
        return executeGeneratorStep(gen, gpc, thrown);
    }

    private PyObject closeGenerator(PyGenerator gen) {
        if (!gen.beginClose(interpreterId)) {
            return PyNull.INSTANCE;
        }
        InterpreterException genExit = PyErrors.errorValue(
                new PyException(exceptionTypes.type("GeneratorExit"), List.of()));
        int[] gbc = gen.code().bytecode();
        int gpc = gen.pc();
        InterpreterException toInject = genExit;
        if (gpc < gbc.length && (OP_VALUES[gbc[gpc] >>> 16] == OpCode.YIELD_FROM
                || OP_VALUES[gbc[gpc] >>> 16] == OpCode.AWAIT_FROM)) {
            PyObject sub = gen.operandStack().pop();
            if (sub instanceof PyGenerator subGen) {
                try {
                    closeGenerator(subGen);
                } catch (InterpreterException closeErr) {
                    toInject = closeErr;
                }
            } else if (sub instanceof PyCoroutine subCoro) {
                try {
                    closeGenerator(subCoro.runner());
                } catch (InterpreterException closeErr) {
                    toInject = closeErr;
                }
            }
        }
        try {
            executeGeneratorStep(gen, gpc, toInject);
            gen.fail();
            throw PyErrors.error("RuntimeError", "generator ignored GeneratorExit");
        } catch (InterpreterException ie) {
            if (("StopIteration".equals(ie.typeName()) && ie.isGeneratorReturn())
                    || "GeneratorExit".equals(ie.typeName())) {
                gen.complete();
                return PyNull.INSTANCE;
            }
            if ("StopIteration".equals(ie.typeName())) {
                gen.fail();
                PyException rtPe = new PyException(exceptionTypes.type("RuntimeError"),
                        List.of(new PyStr("generator raised StopIteration")));
                if (ie.value() instanceof PyException orig) rtPe.setExplicitCause(orig);
                throw PyErrors.errorValue(rtPe);
            }
            gen.fail();
            throw ie;
        }
    }

    private InterpreterException buildGeneratorThrowException(PyObject[] args) {
        if (args.length == 0 || args.length > 3) {
            throw PyErrors.error("TypeError", "throw() takes 1 to 3 arguments");
        }
        PyObject typeOrExc = args[0];
        PyObject valArg = args.length >= 2 ? args[1] : PyNull.INSTANCE;
        if (typeOrExc instanceof PyException pe) {
            if (!(valArg instanceof PyNull)) {
                throw PyErrors.error("TypeError", "instance exception may not have a separate value");
            }
            return PyErrors.errorValue(pe);
        }
        if (typeOrExc instanceof PyClass cls && cls.isExceptionType()) {
            if (valArg instanceof PyNull) {
                return PyErrors.errorValue(new PyException(cls, List.of()));
            }
            if (valArg instanceof PyException pe && pe.type().isSubclassOf(cls)) {
                return PyErrors.errorValue(pe);
            }
            if (valArg instanceof PyTuple tuple) {
                return PyErrors.errorValue(new PyException(cls, tuple.items()));
            }
            return PyErrors.errorValue(new PyException(cls, List.of(valArg)));
        }
        throw PyErrors.error("TypeError", "exceptions must derive from BaseException");
    }

    private PyObject executeGeneratorStep(PyGenerator gen, int resumePc, InterpreterException pendingThrow) {
        if (callDepth() >= MAX_CALL_DEPTH) {
            gen.fail();
            throw PyErrors.error("RecursionError", "maximum recursion depth exceeded");
        }
        ExecutionContext genCtx = gen.creationContext();
        if (genCtx != null && genCtx != currentContext && genCtx.hasActiveLimits()) {
            try {
                genCtx.stepInstruction();
            } catch (InterpreterException ie) {
                gen.fail();
                throw ie;
            }
        }

        var savedFrames = new ArrayDeque<>(callStack);
        var savedStack = stack;
        var savedLocals = currentLocals;
        var savedSlots = slotLocals;
        var savedExcept = exceptStack;
        var savedActive = activeExceptions;
        var savedActiveGen = activeGenerator;
        var savedFunc = currentFunc;
        var savedFunction = currentFunction;
        var savedLine = currentLine;
        var savedFile = currentFile;
        var savedGlobals = currentGlobals;
        var savedGlobalCells = globalCells;
        PyException outerCtx = currentContextException();
        if (outerCtx != null) outerActiveContexts.addLast(outerCtx);

        int hidden = savedFrames.size();
        nestedDepth += hidden + 1;
        try {
            callStack.clear();
            stack = gen.operandStack();
            currentLocals = gen.locals();
            slotLocals = null;
            exceptStack = gen.exceptHandlers();
            activeExceptions = gen.activeExceptions();
            globalCells = gen.globalCells();
            if (gen.moduleGlobals() != null) {
                currentGlobals = gen.moduleGlobals();
            }
            currentFile = gen.file();
            currentLine = gen.line();
            currentFunc = gen.fn().name();
            currentFunction = gen.fn();
            activeGenerator = gen;
            return runAt(gen.code(), resumePc, pendingThrow);
        } catch (StackOverflowError overflow) {
            if (gen.state() == PyGenerator.State.RUNNING) gen.fail();
            throw PyErrors.error("RecursionError", "maximum recursion depth exceeded (host stack exhausted)");
        } catch (InterpreterException ie) {
            if (gen.state() == PyGenerator.State.RUNNING) gen.fail();
            if ("StopIteration".equals(ie.typeName()) && !ie.isGeneratorReturn()) {
                PyException rtPe = new PyException(exceptionTypes.type("RuntimeError"),
                        List.of(new PyStr("generator raised StopIteration")));
                if (ie.value() instanceof PyException orig) {
                    rtPe.setExplicitCause(orig);
                }
                throw PyErrors.errorValue(rtPe);
            }
            throw ie;
        } finally {
            nestedDepth -= (hidden + 1);
            if (outerCtx != null) outerActiveContexts.removeLast();
            activeGenerator = savedActiveGen;
            callStack.clear();
            callStack.addAll(savedFrames);
            stack = savedStack;
            currentLocals = savedLocals;
            slotLocals = savedSlots;
            exceptStack = savedExcept;
            activeExceptions = savedActive;
            currentFunc = savedFunc;
            currentFunction = savedFunction;
            currentLine = savedLine;
            currentFile = savedFile;
            currentGlobals = savedGlobals;
            globalCells = savedGlobalCells;
        }
    }

    public PyObject getGlobal(String name) {
        return globals.get(name);
    }

    public void setGlobal(String name, PyObject value) {
        globals.put(name, PolicySanitizer.sanitize(value, javaAccessPolicy, hostSession));
    }

    // ─── ANTLR compilation with Python Indentation Stream ─────────────────

    /** Compiles Python source without executing it (used by validation/debug tools). */
    public CompiledCode compileSource(String src) throws InterpreterException {
        return compile(src);
    }

    /** Returns a human-readable bytecode listing for {@code src} and nested code units. */
    public String disassemble(String src) throws InterpreterException {
        return org.example.jython3.compile.BytecodeDisassembler.disassemble(compile(src));
    }

    private CompiledCode compile(String src) throws InterpreterException {
        if (src.length() > executionLimits.maxSourceLength()) {
            throw PyErrors.error("ValueError", "Source text exceeds maximum allowed length: " + executionLimits.maxSourceLength());
        }
        CompiledCode cached = bytecodeCache.get(src);
        if (cached != null) return cached;

        var lexer = new Jython3Lexer(CharStreams.fromString(src));
        lexer.removeErrorListeners();
        lexer.addErrorListener(ThrowingErrorListener.INSTANCE);

        // Wrap lexer in PythonIndentStream for INDENT/DEDENT handling
        var indentStream = new PythonIndentStream(lexer);

        var parser = new Jython3Parser(new CommonTokenStream(indentStream));
        parser.removeErrorListeners();
        parser.addErrorListener(ThrowingErrorListener.INSTANCE);

        var b = new CodeBuilder();
        try {
            new CompileVisitor(b).visit(parser.stat());
        } catch (org.example.jython3.compile.CompileError ce) {
            throw PyErrors.error("SyntaxError", ce.getMessage());
        }
        b.emit(OpCode.HALT);
        CompiledCode result = b.build();

        if (bytecodeCache.size() < MAX_CACHE_SIZE) {
            bytecodeCache.put(src, result);
        }
        return result;
    }

    // ─── Stack‑machine execution ──────────────────────────────────────────

    private void run(CompiledCode code) throws InterpreterException {
        runAt(code, 0, null);
    }

    private PyObject runAt(CompiledCode code, int initialPc, InterpreterException pendingThrow) throws InterpreterException {
        int[] bc = code.bytecode();
        Object[] pool = code.constPool();
        PyObject[] pyPool = code.pyConstPool();
        int pc = initialPc;
        if (pendingThrow != null && initialPc > 0) {
            int errLine = code.lineAt(initialPc - 1);
            if (errLine > 0) currentLine = errLine;
        }
        // Frames below this depth belong to an enclosing run() invocation (re-entrant
        // calls from callPyCallableKw: __init__, __enter__/__exit__, builtins calling back
        // into Python). This loop must never return into, or unwind through, them.
        final int baseDepth = callStack.size();

        outer: while (pc < bc.length) {
            if (enforceLimits) {
                currentContext.stepInstruction();
            }
            int instr = bc[pc];
            OpCode op = OP_VALUES[instr >>> 16];
            int ai = instr & 0xFFFF;
            int line = code.lineAt(pc);
            if (line > 0) currentLine = line;

            try {
            if (pendingThrow != null) {
                InterpreterException toThrow = pendingThrow;
                pendingThrow = null;
                throw toThrow;
            }
            switch (op) {
                case LOAD_CONST -> {
                    if (ai == NO_ARG) { stack.push(PyNull.INSTANCE); break; }
                    PyObject preboxed = pyPool[ai];
                    if (preboxed != null) { stack.push(preboxed); break; }
                    Object v = pool[ai];
                    if (v == null) stack.push(PyNull.INSTANCE);
                    else if (v instanceof String s) {
                        // global/nonlocal declarations are encoded as special markers
                        if (s.startsWith("@global:")) {
                            // Register this name as a global cell in the current function scope
                            String gName = s.substring(8);
                            if (currentLocals != null && globalCells == null) {
                                globalCells = new java.util.HashSet<>();
                            }
                            if (globalCells != null) globalCells.add(gName);
                            break; // no stack push — declaration only
                        } else if (s.startsWith("@nonlocal:")) {
                            String nlName = s.substring(10);
                            if ((callStack.isEmpty() && activeGenerator == null)
                                    || currentLocals == null || currentLocals == globals || currentLocals == currentGlobals) {
                                throw PyErrors.error("SyntaxError", "nonlocal declaration not allowed at module level");
                            }
                            PyCell targetCell = null;
                            for (Frame cf : callStack) {
                                if (cf.locals() != null && cf.locals() != globals) {
                                    PyObject existing = cf.locals().get(nlName);
                                    if (existing instanceof PyCell existingCell) {
                                        targetCell = existingCell;
                                        break;
                                    }
                                }
                            }
                            if (targetCell == null) {
                                for (Frame cf : callStack) {
                                    if (cf.locals() != null && cf.locals() != globals) {
                                        PyObject existing = cf.locals().get(nlName);
                                        if (existing != null) {
                                            targetCell = new PyCell(existing);
                                            break;
                                        }
                                    }
                                }
                            }
                            if (targetCell == null) {
                                PyObject existing = currentLocals.get(nlName);
                                if (existing != null) {
                                    targetCell = (existing instanceof PyCell existingCell) ? existingCell : new PyCell(existing);
                                }
                            }
                            if (targetCell == null) {
                                throw PyErrors.error("SyntaxError", "no binding for nonlocal '" + nlName + "' found");
                            }
                            for (Frame cf : callStack) {
                                if (cf.locals() != null && cf.locals() != globals) {
                                    if (cf.locals().containsKey(nlName)) {
                                        cf.locals().put(nlName, targetCell);
                                    }
                                    if (cf.function() != null && cf.function().closure() != null && cf.function().closure().containsKey(nlName)) {
                                        cf.function().closure().put(nlName, targetCell);
                                    }
                                }
                            }
                            currentLocals.put(nlName, targetCell);
                            if (currentFunction != null && currentFunction.closure() != null) {
                                currentFunction.closure().put(nlName, targetCell);
                            }
                            break; // no stack push — declaration only
                        }
                        stack.push(new PyStr(s));
                    }
                    else if (v instanceof Integer i) stack.push(PyInt.of(i));
                    else if (v instanceof Double d) stack.push(new PyFloat(d));
                    else if (v instanceof Boolean b) stack.push(PyBool.of(b));
                    else if (v instanceof org.example.jython3.runtime.BigInt bi) stack.push(bi);
                    else if (v instanceof PyFunction f) {
                        Map<String, PyObject> capSrc = closureCaptureLocals != null ? closureCaptureLocals : currentLocals;
                        if (("<lambda>".equals(f.name()) || "<genexpr>".equals(f.name()))
                                && capSrc != null && capSrc != globals) {
                            var refs = f.code() != null ? f.code().referencedNames() : java.util.Set.<String>of();
                            var fnLocals = f.code() != null ? f.code().localNames() : java.util.Set.<String>of();
                            var captured = new HashMap<String, PyObject>(f.closure());
                            for (Map.Entry<String, PyObject> e : capSrc.entrySet()) {
                                if (refs.contains(e.getKey()) && !fnLocals.contains(e.getKey())) {
                                    captured.put(e.getKey(), e.getValue());
                                }
                            }
                            stack.push(new PyFunction(f.name(), f.params(), f.defaults(),
                                    f.argsParam(), f.kwargsParam(), f.code(), captured));
                        } else {
                            stack.push(f);
                        }
                    }
                    else if (v instanceof CompiledCode cc) stack.push(new PyCompiledCodeWrapper(cc));
                    else throw PyErrors.error("RuntimeError", "unknown constant: " + v);
                }
                case LOAD_SLOT -> {
                    PyObject v = slotLocals[ai];
                    if (v == null) {
                        throw PyErrors.error("UnboundLocalError",
                                "cannot access local variable '" + code.slotNames()[ai] + "' where it is not associated with a value");
                    }
                    stack.push(v);
                }
                case STORE_SLOT -> slotLocals[ai] = stack.pop();
                case LOAD_FAST -> {
                    String n = (String) pool[ai];
                    PyObject v = null;
                    if (globalCells == null && currentLocals != null) {
                        v = currentLocals.get(n);
                    } else if (globalCells != null && globalCells.contains(n)) {
                        v = globals.get(n);
                        if (v == null) v = currentGlobals.get(n);
                    } else if (currentLocals != null) {
                        v = currentLocals.get(n);
                    }
                    if (v == null && currentLocals != globals && currentLocals != null
                            && (globalCells == null || !globalCells.contains(n))
                            && code.localNames().contains(n)) {
                        throw PyErrors.error("UnboundLocalError",
                                "cannot access local variable '" + n + "' where it is not associated with a value");
                    }
                    if (v == null) v = currentGlobals.get(n);
                    if (v == null) throw PyErrors.error("NameError", "name '" + n + "' is not defined");
                    if (v instanceof PyCell cell) {
                        if (!cell.isBound()) {
                            throw PyErrors.error("UnboundLocalError", "cannot access local variable '" + n + "' where it is not associated with a value");
                        }
                        v = cell.get();
                    }
                    stack.push(v);
                }
                case LOAD_GLOBAL -> {
                    String n = (String) pool[ai];
                    PyObject v = currentGlobals.get(n);
                    if (v == null) throw PyErrors.error("NameError", "name '" + n + "' is not defined");
                    stack.push(v);
                }
                case STORE_FAST -> {
                    String storeName = (String) pool[ai];
                    PyObject storeVal = stack.pop();
                    if (globalCells != null && globalCells.contains(storeName)) {
                        globals.put(storeName, storeVal);
                        currentGlobals.put(storeName, storeVal);
                    } else if (currentLocals != null) {
                        PyObject existing = currentLocals.get(storeName);
                        if (existing instanceof PyCell cell) {
                            cell.set(storeVal);
                        } else {
                            currentLocals.put(storeName, storeVal);
                        }
                    } else {
                        globals.put(storeName, storeVal);
                    }
                }
                case STORE_SUBSCR -> {
                    PyObject val = stack.pop();
                    PyObject key = stack.pop();
                    PyObject container = stack.pop();
                    if (container instanceof PyList list && key instanceof PyInt ix) {
                        int i = ix.value();
                        if (i < 0) i += list.items().size();
                        if (i < 0 || i >= list.items().size())
                            throw PyErrors.error("IndexError", "list assignment index out of range");
                        list.items().set(i, val);
                    } else if (container instanceof PyTuple) {
                        throw PyErrors.error("TypeError", "'tuple' object does not support item assignment");
                    } else if (container instanceof PyDict dict) {
                        dict.put(key, val);
                    } else if (container instanceof PyByteArray ba) {
                        ba.setAt(key, val);
                    } else if (container instanceof PyInstance inst) {
                        PyObject setitem = inst.pyClass().lookupMember("__setitem__");
                        if (setitem == null) {
                            throw PyErrors.error("TypeError", "'" + inst.pyClass().name() + "' object does not support item assignment");
                        }
                        callPyCallable(setitem, new PyObject[]{inst, key, val});
                    } else {
                        throw PyErrors.error("TypeError", "'" + typeName(container) + "' object does not support item assignment");
                    }
                }
                case STORE_ATTR -> {
                    String attr = (String) pool[ai];
                    PyObject val = stack.pop();
                    PyObject obj = stack.pop();
                    if (obj instanceof PyInstance inst) {
                        inst.setField(attr, val);
                    } else if (obj instanceof PyModule mod) {
                        mod.attrs().put(attr, val);
                    } else if (obj instanceof PyClass cls) {
                        if ("__bases__".equals(attr)) {
                            if (!(val instanceof PyTuple tup)) {
                                throw PyErrors.error("TypeError", "can only assign tuple (not \"" + typeName(val) + "\") to __bases__");
                            }
                            var newBases = new ArrayList<PyClass>();
                            for (PyObject item : tup.items()) {
                                if (item instanceof PyClass baseCls) newBases.add(baseCls);
                                else if (item instanceof PyBuiltinFunction bf && bf.isType() && "object".equals(bf.name())) {
                                    // object is implicit
                                } else {
                                    throw PyErrors.error("TypeError", "__bases__ items must be classes, not " + typeName(item));
                                }
                            }
                            cls.setBases(newBases);
                        } else {
                            cls.setMember(attr, val);
                        }
                    } else if (obj instanceof PyFunction fn) {
                        fn.setExtraAttr(attr, val);
                    } else if (obj instanceof PyJavaObject jo) {
                        jo.setMember(attr, val);
                    } else if (obj instanceof PyJavaClass jc) {
                        jc.setMember(attr, val);
                    } else if (obj instanceof PyHostHandle hh) {
                        hh.setAttr(attr, val);
                    } else {
                        throw PyErrors.error("AttributeError", "cannot set attribute on '" + typeName(obj) + "'");
                    }
                }
                case DELETE_FAST -> {
                    String n = (String) pool[ai];
                    if (currentLocals != null && currentLocals.get(n) instanceof org.example.jython3.runtime.PyCell cell) {
                        if (!cell.isBound()) throw PyErrors.error("UnboundLocalError",
                                "cannot access local variable '" + n + "' where it is not associated with a value");
                        cell.set(null);
                    } else if (currentLocals != null && currentLocals != globals
                            && (globalCells == null || !globalCells.contains(n))
                            && code.localNames().contains(n)) {
                        if (currentLocals.remove(n) == null) {
                            throw PyErrors.error("UnboundLocalError",
                                    "cannot access local variable '" + n + "' where it is not associated with a value");
                        }
                    } else if ((currentLocals == null || currentLocals.remove(n) == null) && globals.remove(n) == null) {
                        throw PyErrors.error("NameError", "name '" + n + "' is not defined");
                    }
                }
                case DELETE_NAME_SILENT -> {
                    String n = (String) pool[ai];
                    if (currentLocals != null) currentLocals.remove(n);
                    globals.remove(n);
                }
                case DELETE_SUBSCR -> {
                    PyObject key = stack.pop();
                    PyObject container = stack.pop();
                    if (container instanceof PyList list && key instanceof PyInt ix) {
                        int i = ix.value();
                        if (i < 0) i += list.items().size();
                        if (i < 0 || i >= list.items().size())
                            throw PyErrors.error("IndexError", "list index out of range");
                        list.items().remove(i);
                    } else if (container instanceof PyTuple) {
                        throw PyErrors.error("TypeError", "'tuple' object doesn't support item deletion");
                    } else if (container instanceof PyDict dict) {
                        if (dict.remove(key) == null)
                            throw PyErrors.error("KeyError", String.valueOf(key));
                    } else if (container instanceof PyInstance inst) {
                        PyObject delitem = inst.pyClass().lookupMember("__delitem__");
                        if (delitem == null) {
                            throw PyErrors.error("TypeError", "'" + inst.pyClass().name() + "' object does not support item deletion");
                        }
                        callPyCallable(delitem, new PyObject[]{inst, key});
                    } else {
                        throw PyErrors.error("TypeError", "'" + typeName(container) + "' object doesn't support item deletion");
                    }
                }
                case DELETE_ATTR -> {
                    String attr = (String) pool[ai];
                    PyObject obj = stack.pop();
                    if (obj instanceof PyInstance inst) {
                        if (!inst.deleteField(attr)) {
                            throw PyErrors.error("AttributeError", "'" + inst.pyClass().name() + "' object has no attribute '" + attr + "'");
                        }
                    } else if (obj instanceof PyClass cls) {
                        if (cls.removeMember(attr) == null) {
                            throw PyErrors.error("AttributeError", "type object '" + cls.name() + "' has no attribute '" + attr + "'");
                        }
                    } else if (obj instanceof PyModule mod) {
                        if (mod.attrs().remove(attr) == null) {
                            throw PyErrors.error("AttributeError", "module '" + mod.name() + "' has no attribute '" + attr + "'");
                        }
                    } else {
                        throw PyErrors.error("AttributeError", "'" + typeName(obj) + "' object has no attribute '" + attr + "'");
                    }
                }

                // ── Arithmetic ──
                case ADD -> {
                    PyObject r = stack.pop(), l = stack.pop();
                    if (l instanceof PyInt li && r instanceof PyInt ri) {
                        int a = li.value(), b = ri.value();
                        int sum = a + b;
                        if (((a ^ sum) & (b ^ sum)) < 0) {
                            stack.push(new BigInt(java.math.BigInteger.valueOf((long) a + b)));
                        } else {
                            stack.push(PyInt.of(sum));
                        }
                    } else {
                        stack.push(ArithmeticOps.add(l, r, executionLimits));
                    }
                }
                case SUBTRACT -> {
                    PyObject r = stack.pop(), l = stack.pop();
                    if (l instanceof PyInt li && r instanceof PyInt ri) {
                        int a = li.value(), b = ri.value();
                        int diff = a - b;
                        if (((a ^ b) & (a ^ diff)) < 0) {
                            stack.push(new BigInt(java.math.BigInteger.valueOf((long) a - b)));
                        } else {
                            stack.push(PyInt.of(diff));
                        }
                    } else {
                        stack.push(ArithmeticOps.subtract(l, r));
                    }
                }
                case MULTIPLY   -> binaryMultiply();
                case INPLACE_ADD -> {
                    var r = stack.pop();
                    var l = stack.pop();
                    if (l instanceof PyList list) {
                        var extra = BuiltinFunctions.collectIterable(r, javaAccessPolicy);
                        if ((long) list.items().size() + extra.size() > executionLimits.maxCollectionSize()) {
                            throw PyErrors.error("OverflowError",
                                    "List size exceeds maximum allowed limit: " + executionLimits.maxCollectionSize());
                        }
                        list.items().addAll(extra);
                        stack.push(list);
                    } else if (l instanceof PyByteArray ba) {
                        byte[] extra;
                        if (r instanceof PyBytes || r instanceof PyByteArray) {
                            extra = PyBytes.extractBuffer(r);
                        } else {
                            var items = BuiltinFunctions.collectIterable(r, javaAccessPolicy);
                            extra = new byte[items.size()];
                            for (int i = 0; i < items.size(); i++) extra[i] = (byte) PyBytes.extractByteValue(items.get(i));
                        }
                        ba.extend(extra, executionLimits.maxStringLength());
                        stack.push(ba);
                    } else {
                        stack.push(ArithmeticOps.add(l, r, executionLimits));
                    }
                }
                case INPLACE_SUBTRACT -> { var r = stack.pop(); var l = stack.pop(); stack.push(ArithmeticOps.inplaceSubtract(l, r)); }
                case INPLACE_MULTIPLY -> { var r = stack.pop(); var l = stack.pop(); stack.push(ArithmeticOps.inplaceMultiply(l, r, executionLimits)); }
                case INPLACE_AND      -> { var r = stack.pop(); var l = stack.pop(); stack.push(ArithmeticOps.inplaceBitwiseAnd(l, r)); }
                case INPLACE_OR       -> { var r = stack.pop(); var l = stack.pop(); stack.push(ArithmeticOps.inplaceBitwiseOr(l, r)); }
                case INPLACE_XOR      -> { var r = stack.pop(); var l = stack.pop(); stack.push(ArithmeticOps.inplaceBitwiseXor(l, r)); }
                case DIVIDE -> {
                    var r = unwrapBool(stack.pop()); var l = unwrapBool(stack.pop());
                    if ((l instanceof PyInt || l instanceof BigInt) && (r instanceof PyInt || r instanceof BigInt)) {
                        java.math.BigInteger ba = l instanceof PyInt pi ? java.math.BigInteger.valueOf(pi.value()) : ((BigInt) l).value();
                        java.math.BigInteger bb = r instanceof PyInt pi ? java.math.BigInteger.valueOf(pi.value()) : ((BigInt) r).value();
                        stack.push(new PyFloat(PyOps.trueDivideIntegers(ba, bb)));
                    } else if (isNumeric(l) && isNumeric(r)) {
                        double rv = toDouble(r);
                        if (rv == 0.0) throw PyErrors.error("ZeroDivisionError", "float division by zero");
                        double lv = toDouble(l);
                        stack.push(new PyFloat(lv / rv));
                    } else {
                        PyObject dunder = ArithmeticOps.tryBinaryDunder(l, r, "__truediv__", "__rtruediv__");
                        if (dunder != null) stack.push(dunder);
                        else throw PyErrors.error("TypeError", "unsupported operand types for /");
                    }
                }
                case FLOOR_DIVIDE -> {
                    var r = stack.pop(); var l = stack.pop();
                    stack.push(ArithmeticOps.floorDiv(l, r));
                }
                case MODULO -> {
                    var r = stack.pop(); var l = stack.pop();
                    if (l instanceof PyStr fmt) {
                        stack.push(new PyStr(pyStringFormat(fmt.value(), r)));
                    } else {
                        stack.push(ArithmeticOps.mod(l, r));
                    }
                }
                case POWER -> {
                    var r = stack.pop(); var l = stack.pop();
                    stack.push(ArithmeticOps.pow(l, r, executionLimits));
                }
                case UNARY_MINUS -> {
                    var v = unwrapBool(stack.pop());
                    if (v instanceof PyInt i) {
                        long negated = -(long) i.value();
                        if (negated > Integer.MAX_VALUE || negated < Integer.MIN_VALUE) {
                            stack.push(new BigInt(java.math.BigInteger.valueOf(negated)));
                        } else {
                            stack.push(PyInt.of((int) negated));
                        }
                    } else if (v instanceof BigInt bi) {
                        stack.push(new BigInt(bi.value().negate()));
                    } else if (v instanceof PyFloat f) {
                        stack.push(new PyFloat(-f.value()));
                    } else if (v instanceof PyInstance inst) {
                        PyObject neg = inst.pyClass().lookupMember("__neg__");
                        if (neg == null) throw PyErrors.error("TypeError", "bad operand type for unary -: '" + typeName(v) + "'");
                        stack.push(callPyCallable(neg, new PyObject[]{inst}));
                    } else {
                        throw PyErrors.error("TypeError", "bad operand type for unary -: '" + typeName(v) + "'");
                    }
                }
                case BITWISE_AND    -> { var r = stack.pop(); var l = stack.pop(); stack.push(ArithmeticOps.bitwiseAnd(l, r)); }
                case BITWISE_OR     -> { var r = stack.pop(); var l = stack.pop(); stack.push(ArithmeticOps.bitwiseOr(l, r)); }
                case BITWISE_XOR    -> { var r = stack.pop(); var l = stack.pop(); stack.push(ArithmeticOps.bitwiseXor(l, r)); }
                case BITWISE_INVERT -> stack.push(ArithmeticOps.bitwiseInvert(stack.pop()));
                case LSHIFT         -> { var r = stack.pop(); var l = stack.pop(); stack.push(ArithmeticOps.leftShift(l, r, executionLimits)); }
                case RSHIFT         -> { var r = stack.pop(); var l = stack.pop(); stack.push(ArithmeticOps.rightShift(l, r)); }

                // ── Comparison ──
                case COMPARE_EQ -> {
                    var r = stack.pop(); var l = stack.pop();
                    if (l instanceof PyInt li && r instanceof PyInt ri) {
                        stack.push(PyBool.of(li.value() == ri.value()));
                    } else {
                        stack.push(PyBool.of(pyEquals(l, r)));
                    }
                }
                case COMPARE_NEQ -> {
                    var r = stack.pop(); var l = stack.pop();
                    if (l instanceof PyInt li && r instanceof PyInt ri) {
                        stack.push(PyBool.of(li.value() != ri.value()));
                    } else {
                        stack.push(PyBool.of(!pyEquals(l, r)));
                    }
                }
                case COMPARE_LT -> {
                    var r = stack.pop(); var l = stack.pop();
                    if (l instanceof PyInt li && r instanceof PyInt ri) {
                        stack.push(PyBool.of(li.value() < ri.value()));
                    } else if (PyOps.isSetLike(l) && PyOps.isSetLike(r)) {
                        stack.push(PyBool.of(PyOps.setSize(l) < PyOps.setSize(r) && PyOps.setIsSubset(l, r)));
                    } else if (isNan(l) || isNan(r)) stack.push(PyBool.FALSE);
                    else stack.push(PyBool.of(compareObjects(l, r) < 0));
                }
                case COMPARE_GT -> {
                    var r = stack.pop(); var l = stack.pop();
                    if (l instanceof PyInt li && r instanceof PyInt ri) {
                        stack.push(PyBool.of(li.value() > ri.value()));
                    } else if (PyOps.isSetLike(l) && PyOps.isSetLike(r)) {
                        stack.push(PyBool.of(PyOps.setSize(l) > PyOps.setSize(r) && PyOps.setIsSubset(r, l)));
                    } else if (isNan(l) || isNan(r)) stack.push(PyBool.FALSE);
                    else stack.push(PyBool.of(compareObjects(l, r) > 0));
                }
                case COMPARE_LTE -> {
                    var r = stack.pop(); var l = stack.pop();
                    if (l instanceof PyInt li && r instanceof PyInt ri) {
                        stack.push(PyBool.of(li.value() <= ri.value()));
                    } else if (PyOps.isSetLike(l) && PyOps.isSetLike(r)) {
                        stack.push(PyBool.of(PyOps.setIsSubset(l, r)));
                    } else if (isNan(l) || isNan(r)) stack.push(PyBool.FALSE);
                    else stack.push(PyBool.of(compareObjects(l, r) <= 0));
                }
                case COMPARE_GTE -> {
                    var r = stack.pop(); var l = stack.pop();
                    if (l instanceof PyInt li && r instanceof PyInt ri) {
                        stack.push(PyBool.of(li.value() >= ri.value()));
                    } else if (PyOps.isSetLike(l) && PyOps.isSetLike(r)) {
                        stack.push(PyBool.of(PyOps.setIsSubset(r, l)));
                    } else if (isNan(l) || isNan(r)) stack.push(PyBool.FALSE);
                    else stack.push(PyBool.of(compareObjects(l, r) >= 0));
                }
                case COMPARE_IN     -> { var container = stack.pop(); var item = stack.pop(); stack.push(PyBool.of(pyContains(container, item))); }
                case COMPARE_NOT_IN -> { var container = stack.pop(); var item = stack.pop(); stack.push(PyBool.of(!pyContains(container, item))); }
                case COMPARE_IS     -> { var r = stack.pop(); var l = stack.pop(); stack.push(PyBool.of(l == r || (l instanceof PyNull && r instanceof PyNull))); }
                case COMPARE_IS_NOT -> { var r = stack.pop(); var l = stack.pop(); stack.push(PyBool.of(!(l == r || (l instanceof PyNull && r instanceof PyNull)))); }

                // ── Logical ──
                case LOGICAL_NOT -> stack.push(PyBool.of(!isTruthy(stack.pop())));
                case JUMP_IF_TRUE_OR_POP -> {
                    if (isTruthy(stack.peek())) { pc = ai; continue; }
                    else stack.pop();
                }
                case JUMP_IF_FALSE_OR_POP -> {
                    if (!isTruthy(stack.peek())) { pc = ai; continue; }
                    else stack.pop();
                }

                // ── I/O ──
                case PRINT -> out().println(pyPrintStr(stack.pop()));
                case PRINT_MULTI -> {
                    PyList list = (PyList) stack.pop();
                    var sb = new StringBuilder();
                    for (int i = 0; i < list.items().size(); i++) {
                        if (i > 0) sb.append(' ');
                        sb.append(pyPrintStr(list.items().get(i)));
                    }
                    out().println(sb);
                }

                // ── Control flow ──
                case JUMP_IF_FALSE -> { if (!isTruthy(stack.pop())) { pc = ai; continue; } }
                case JUMP -> { pc = ai; continue; }

                // ── Modules ──
                case IMPORT_MODULE -> importModule((String) pool[ai]);

                // ── Functions & Classes ──
                case MAKE_FUNCTION_DEFAULTS -> {
                    // Encoding: arg = (nDefaults << 2) | flags
                    // flags: bit0=hasArgs, bit1=hasKwargs
                    // Stack after LOAD_CONST(prototype): [prototype, def0, def1, ...defN-1]
                    // MAKE_FUNCTION follows immediately after and stores the enriched fn.
                    int encoded   = ai;
                    int nDefaults = encoded >> 2;
                    boolean hasArgs   = (encoded & 1) != 0;
                    boolean hasKwargs = (encoded & 2) != 0;

                    // Pop defaults off stack (pushed in order: first default on bottom)
                    var defaults = new PyObject[nDefaults];
                    for (int i = nDefaults - 1; i >= 0; i--) defaults[i] = stack.pop();

                    // The prototype PyFunction is next on stack
                    PyObject protoObj = stack.pop();
                    if (!(protoObj instanceof PyFunction proto)) {
                        throw PyErrors.error("SystemError", "MAKE_FUNCTION_DEFAULTS: expected PyFunction prototype on stack");
                    }
                    // Build enriched PyFunction with the evaluated defaults
                    var enriched = new PyFunction(
                            proto.name(), proto.params(), defaults,
                            proto.argsParam(), proto.kwargsParam(),
                            proto.code(), proto.closure(), proto.isAsync());
                    stack.push(enriched);
                    // Note: the following MAKE_FUNCTION instruction will capture
                    // closure and store the function — we fall through to it.
                }
                case MAKE_FUNCTION -> {
                    String funcName = (String) pool[ai];
                    PyObject fnObj = stack.pop();
                    if (fnObj instanceof PyFunction fn) {
                        var refs = fn.code() != null ? fn.code().referencedNames() : java.util.Set.<String>of();
                        var fnLocals = fn.code() != null ? fn.code().localNames() : java.util.Set.<String>of();
                        // Capture only referenced free (non-local) enclosing bindings
                        Map<String, PyObject> capSrc = closureCaptureLocals != null ? closureCaptureLocals : currentLocals;
                        if (capSrc != null && capSrc != globals) {
                            if (fn.code() != null && fn.code().constPool() != null) {
                                for (Object cpObj : fn.code().constPool()) {
                                    if (cpObj instanceof String cs && cs.startsWith("@nonlocal:")) {
                                        String nlName = cs.substring(10);
                                        if (capSrc.containsKey(nlName)) {
                                            PyObject curVal = capSrc.get(nlName);
                                            if (!(curVal instanceof PyCell)) {
                                                capSrc.put(nlName, new PyCell(curVal));
                                            }
                                        }
                                    }
                                }
                            }
                            for (Map.Entry<String, PyObject> e : capSrc.entrySet()) {
                                if (!e.getKey().equals(funcName)
                                        && refs.contains(e.getKey())
                                        && !fnLocals.contains(e.getKey())) {
                                    fn.closure().putIfAbsent(e.getKey(), e.getValue());
                                }
                            }
                        }
                    }
                    if (currentLocals != null) {
                        currentLocals.put(funcName, fnObj);
                    } else {
                        globals.put(funcName, fnObj);
                    }
                }
                case BUILD_CLASS -> {
                    String className = (String) pool[ai];
                    PyCompiledCodeWrapper codeWrap = (PyCompiledCodeWrapper) stack.pop();
                    PyObject basesObj = stack.pop();
                    java.util.List<PyClass> bases = new java.util.ArrayList<>();
                    if (basesObj instanceof PyList bl) {
                        for (PyObject b : bl.items()) {
                            if (b instanceof PyClass bcls) bases.add(bcls);
                            else if (b instanceof PyBuiltinFunction bf && bf.isType() && "object".equals(bf.name())) {
                                // 'object' is implicit root
                            } else {
                                throw PyErrors.error("TypeError", "base class must be a type, not " + typeName(b));
                            }
                        }
                    } else if (basesObj instanceof PyClass bc2) {
                        bases.add(bc2);
                    } else if (basesObj instanceof PyBuiltinFunction bf && bf.isType() && "object".equals(bf.name())) {
                        // implicit root
                    } else if (basesObj == null || basesObj instanceof PyNull) {
                        // no bases
                    } else {
                        throw PyErrors.error("TypeError", "base class must be a type, not " + typeName(basesObj));
                    }

                    // Execute class suite in an isolated scope to collect members
                    // The class body gets its own operand stack and handler stack: a handler
                    // belonging to the enclosing code must never be popped by the nested loop.
                    var classLocals = new HashMap<String, PyObject>();
                    var savedLocals = currentLocals;
                    var savedCaptureLocals = closureCaptureLocals;
                    var savedSlots = slotLocals;
                    var savedStack = stack;
                    var savedExceptStack = exceptStack;
                    var savedActiveGen = activeGenerator;
                    try {
                        closureCaptureLocals = (savedLocals != null && savedLocals != globals)
                                ? savedLocals : savedCaptureLocals;
                        currentLocals = classLocals;
                        slotLocals = null;
                        stack = new FastStack();
                        exceptStack = new ArrayDeque<>();
                        activeGenerator = null;
                        runNested(codeWrap.code(), 0);
                    } finally {
                        activeGenerator = savedActiveGen;
                        currentLocals = savedLocals;
                        closureCaptureLocals = savedCaptureLocals;
                        slotLocals = savedSlots;
                        stack = savedStack;
                        exceptStack = savedExceptStack;
                    }

                    var pyClass = new PyClass(className, bases.isEmpty() ? null : bases.get(0), classLocals, bases);
                    if (currentLocals != null) {
                        currentLocals.put(className, pyClass);
                    } else {
                        globals.put(className, pyClass);
                    }
                }
                case CALL_FUNCTION_KW -> {
                    // pool[ai] = KwCallInfo{nPos, kwNames[]}
                    // Stack: ... callable, pos0..posN-1, kw0..kwN-1
                    var kwInfo = (org.example.jython3.compile.KwCallInfo) pool[ai];
                    int nPos    = kwInfo.nPos();
                    String[] kwNames = kwInfo.kwNames();
                    int nKw = kwNames.length;
                    // (debug removed)

                    // Pop keyword values (last pushed = last in list)
                    var kwValues = new PyObject[nKw];
                    for (int i = nKw - 1; i >= 0; i--) kwValues[i] = stack.pop();

                    // Pop positional args
                    var posArgs = new PyObject[nPos];
                    for (int i = nPos - 1; i >= 0; i--) posArgs[i] = stack.pop();

                    PyObject callable = stack.pop();

                    if (callable instanceof PyBoundMethod bm) {
                        PyFunction fn = bm.fn();
                        var callArgs = new PyObject[nPos + 1];
                        callArgs[0] = bm.instance();
                        System.arraycopy(posArgs, 0, callArgs, 1, nPos);
                        if (fn.isAsync() || containsYield(fn.code())) {
                            var genLocals = bindArgs(fn, callArgs, kwNames, kwValues);
                            PyGenerator gen = createGenerator(fn, genLocals);
                            stack.push(fn.isAsync() ? new PyCoroutine(gen) : gen);
                        } else {
                            if (callDepth() >= MAX_CALL_DEPTH) {
                                throw PyErrors.error("RecursionError", "maximum recursion depth exceeded");
                            }
                            callStack.push(new Frame(code, pc + 1, currentLocals, stack, exceptStack,
                                    activeExceptions, currentFunc, currentFile, currentLine, currentGlobals, globalCells, currentFunction, slotLocals));
                            globalCells = null;
                            installFunctionLocals(fn, bindArgs(fn, callArgs, kwNames, kwValues));
                            stack = borrowStack();
                            exceptStack = borrowExceptStack();
                            activeExceptions = borrowActiveExceptions();
                            currentFunc = fn.name();
                            currentFunction = fn;
                            code = fn.code();
                            bc = code.bytecode();
                            pool = code.constPool();
                            pyPool = code.pyConstPool();
                            pc = 0;
                            continue outer;
                        }
                    } else if (callable instanceof PyFunction fn && (fn.isAsync() || containsYield(fn.code()))) {
                        var genLocals = bindArgs(fn, posArgs, kwNames, kwValues);
                        PyGenerator gen = createGenerator(fn, genLocals);
                        stack.push(fn.isAsync() ? new PyCoroutine(gen) : gen);
                    } else if (callable instanceof PyClass cls) {
                        if (cls.isExceptionType()) {
                            stack.push(exceptionValue(cls, posArgs));
                        } else {
                            var inst = new PyInstance(cls);
                            PyObject initObj = cls.lookupMember("__init__");
                            if (initObj instanceof PyFunction initFn) {
                                var initArgs = new PyObject[nPos + 1];
                                initArgs[0] = inst;
                                System.arraycopy(posArgs, 0, initArgs, 1, nPos);
                                callPyCallableKw(initFn, initArgs, kwNames, kwValues);
                            } else if (initObj instanceof PyBuiltinFunction initBf) {
                                var initArgs = new PyObject[nPos + 1];
                                initArgs[0] = inst;
                                System.arraycopy(posArgs, 0, initArgs, 1, nPos);
                                callPyCallableKw(initBf, initArgs, kwNames, kwValues);
                            }
                            stack.push(inst);
                        }
                    } else if (callable instanceof PyFunction fn) {
                        // Regular function call with keyword args — uses inline call path
                        if (callDepth() >= MAX_CALL_DEPTH) {
                            throw PyErrors.error("RecursionError", "maximum recursion depth exceeded");
                        }
                        callStack.push(new Frame(code, pc + 1, currentLocals, stack, exceptStack,
                                activeExceptions, currentFunc, currentFile, currentLine, currentGlobals, globalCells, currentFunction, slotLocals));
                        globalCells = null; // new frame starts with no global declarations
                        installFunctionLocals(fn, bindArgs(fn, posArgs, kwNames, kwValues));
                        stack = borrowStack();
                        exceptStack = borrowExceptStack();
                        activeExceptions = borrowActiveExceptions();
                        currentFunc = fn.name();
                        currentFunction = fn;
                        code = fn.code();
                        bc = code.bytecode();
                        pool = code.constPool();
                        pyPool = code.pyConstPool();
                        pc = 0;
                        continue outer;
                    } else {
                        stack.push(callPyCallableKw(callable, posArgs, kwNames, kwValues));
                    }
                }
                case LIST_EXTEND -> {
                    PyObject iterObj = stack.pop();
                    if (stack.peek() instanceof PyList targetList) {
                        targetList.items().addAll(BuiltinFunctions.collectIterable(iterObj, javaAccessPolicy));
                    }
                }
                case DICT_MERGE -> {
                    PyObject mapObj = stack.pop();
                    if (!(mapObj instanceof PyDict src)) {
                        throw PyErrors.error("TypeError", "argument after ** must be a mapping, not " + typeName(mapObj));
                    }
                    if (stack.peek() instanceof PyDict target) {
                        for (PyObject k : src.keys()) {
                            if (!(k instanceof PyStr)) {
                                throw PyErrors.error("TypeError", "keywords must be strings");
                            }
                            if (target.containsKey(k)) {
                                throw PyErrors.error("TypeError", "got multiple values for keyword argument '" + k + "'");
                            }
                            target.put(k, src.get(k));
                        }
                    }
                }
                case CALL_FUNCTION_EX -> {
                    String[] kwNames = null;
                    PyObject[] kwValues = null;
                    if (ai == 1) {
                        PyDict kwDict = (PyDict) stack.pop();
                        var keys = kwDict.keys();
                        kwNames = new String[keys.size()];
                        kwValues = new PyObject[keys.size()];
                        for (int i = 0; i < keys.size(); i++) {
                            kwNames[i] = ((PyStr) keys.get(i)).value();
                            kwValues[i] = kwDict.get(keys.get(i));
                        }
                    }
                    PyList argsList = (PyList) stack.pop();
                    PyObject[] posArgs = argsList.items().toArray(new PyObject[0]);
                    PyObject callable = stack.pop();
                    if (callable instanceof PyClass cls) {
                        if (cls.isExceptionType()) {
                            stack.push(exceptionValue(cls, posArgs));
                        } else {
                            var inst = new PyInstance(cls);
                            PyObject initObj = cls.lookupMember("__init__");
                            if (initObj instanceof PyFunction initFn) {
                                var initArgs = new PyObject[posArgs.length + 1];
                                initArgs[0] = inst;
                                System.arraycopy(posArgs, 0, initArgs, 1, posArgs.length);
                                callPyCallableKw(initFn, initArgs, kwNames, kwValues);
                            } else if (initObj instanceof PyBuiltinFunction initBf) {
                                var initArgs = new PyObject[posArgs.length + 1];
                                initArgs[0] = inst;
                                System.arraycopy(posArgs, 0, initArgs, 1, posArgs.length);
                                callPyCallableKw(initBf, initArgs, kwNames, kwValues);
                            }
                            stack.push(inst);
                        }
                    } else {
                        stack.push(callPyCallableKw(callable, posArgs, kwNames, kwValues));
                    }
                }
                case CALL_FUNCTION -> {
                    int argc = ai;
                    PyObject peekCallable = stack.peekFromTop(argc);
                    if (peekCallable instanceof PyFunction fn
                            && !fn.isAsync()
                            && fn.defaults().length == 0
                            && fn.argsParam() == null && fn.kwargsParam() == null
                            && argc == fn.params().size() && !containsYield(fn.code())) {
                        if (callDepth() >= MAX_CALL_DEPTH) {
                            throw PyErrors.error("RecursionError", "maximum recursion depth exceeded");
                        }
                        CompiledCode fnCode = fn.code();
                        int slots = fnCode.slotCount();
                        if (slots > 0) {
                            PyObject[] sl = borrowSlots(slots);
                            for (int i = argc - 1; i >= 0; i--) {
                                sl[i] = stack.pop();
                            }
                            stack.pop(); // pop callable
                            callStack.push(new Frame(code, pc + 1, currentLocals, stack, exceptStack,
                                    activeExceptions, currentFunc, currentFile, currentLine, currentGlobals, globalCells, currentFunction, slotLocals));
                            globalCells = null;
                            currentLocals = fn.closure();
                            slotLocals = sl;
                            stack = borrowStack();
                            exceptStack = borrowExceptStack();
                            activeExceptions = borrowActiveExceptions();
                            currentFunc = fn.name();
                            currentFunction = fn;
                            code = fnCode;
                            bc = code.bytecode();
                            pool = code.constPool();
                            pyPool = code.pyConstPool();
                            pc = 0;
                            continue outer;
                        } else if (fn.closure() == null) {
                            var fl = borrowLocals();
                            var params = fn.params();
                            for (int i = argc - 1; i >= 0; i--) {
                                fl.put(params.get(i), stack.pop());
                            }
                            stack.pop(); // pop callable
                            callStack.push(new Frame(code, pc + 1, currentLocals, stack, exceptStack,
                                    activeExceptions, currentFunc, currentFile, currentLine, currentGlobals, globalCells, currentFunction, slotLocals));
                            globalCells = null;
                            currentLocals = fl;
                            slotLocals = null;
                            stack = borrowStack();
                            exceptStack = borrowExceptStack();
                            activeExceptions = borrowActiveExceptions();
                            currentFunc = fn.name();
                            currentFunction = fn;
                            code = fnCode;
                            bc = code.bytecode();
                            pool = code.constPool();
                            pyPool = code.pyConstPool();
                            pc = 0;
                            continue outer;
                        }
                    }
                    if (peekCallable instanceof PyBoundMethod bm) {
                        PyFunction fn = bm.fn();
                        if (!fn.isAsync()
                                && fn.defaults().length == 0
                                && fn.argsParam() == null && fn.kwargsParam() == null
                                && argc + 1 == fn.params().size() && !containsYield(fn.code())) {
                            if (callDepth() >= MAX_CALL_DEPTH) {
                                throw PyErrors.error("RecursionError", "maximum recursion depth exceeded");
                            }
                            CompiledCode fnCode = fn.code();
                            int slots = fnCode.slotCount();
                            if (slots > 0) {
                                PyObject[] sl = borrowSlots(slots);
                                sl[0] = bm.instance();
                                for (int i = argc; i >= 1; i--) {
                                    sl[i] = stack.pop();
                                }
                                stack.pop(); // pop bound method
                                callStack.push(new Frame(code, pc + 1, currentLocals, stack, exceptStack,
                                        activeExceptions, currentFunc, currentFile, currentLine, currentGlobals, globalCells, currentFunction, slotLocals));
                                globalCells = null;
                                currentLocals = fn.closure();
                                slotLocals = sl;
                                stack = borrowStack();
                                exceptStack = borrowExceptStack();
                                activeExceptions = borrowActiveExceptions();
                                currentFunc = fn.name();
                                currentFunction = fn;
                                code = fnCode;
                                bc = code.bytecode();
                                pool = code.constPool();
                                pyPool = code.pyConstPool();
                                pc = 0;
                                continue outer;
                            } else if (fn.closure() == null) {
                                var fl = borrowLocals();
                                var params = fn.params();
                                fl.put(params.get(0), bm.instance());
                                for (int i = argc; i >= 1; i--) {
                                    fl.put(params.get(i), stack.pop());
                                }
                                stack.pop(); // pop bound method
                                callStack.push(new Frame(code, pc + 1, currentLocals, stack, exceptStack,
                                        activeExceptions, currentFunc, currentFile, currentLine, currentGlobals, globalCells, currentFunction, slotLocals));
                                globalCells = null;
                                currentLocals = fl;
                                slotLocals = null;
                                stack = borrowStack();
                                exceptStack = borrowExceptStack();
                                activeExceptions = borrowActiveExceptions();
                                currentFunc = fn.name();
                                currentFunction = fn;
                                code = fnCode;
                                bc = code.bytecode();
                                pool = code.constPool();
                                pyPool = code.pyConstPool();
                                pc = 0;
                                continue outer;
                            }
                        }
                    }
                    if (peekCallable instanceof PyClass cls && !cls.isExceptionType()) {
                        Object[] cache = code.attrCache();
                        AttrCacheEntry entry = (cache != null && pc < cache.length) ? (AttrCacheEntry) cache[pc] : null;
                        PyObject initObj;
                        if (entry != null && entry.cachedClass() == cls && entry.cachedClassVersion() == cls.version()) {
                            initObj = entry.cachedMember();
                        } else {
                            initObj = cls.lookupMember("__init__");
                            if (cache != null && pc < cache.length) {
                                cache[pc] = new AttrCacheEntry(cls, cls.version(), initObj);
                            }
                        }
                        if (initObj == null && argc == 0) {
                            stack.pop(); // pop cls
                            stack.push(new PyInstance(cls));
                            break;
                        }
                        if (initObj instanceof PyFunction initFn
                                && initFn.defaults().length == 0
                                && initFn.argsParam() == null && initFn.kwargsParam() == null
                                && argc + 1 == initFn.params().size() && !containsYield(initFn.code())) {
                            CompiledCode fnCode = initFn.code();
                            int slots = fnCode.slotCount();
                            if (slots > 0) {
                                if (callDepth() >= MAX_CALL_DEPTH) {
                                    throw PyErrors.error("RecursionError", "maximum recursion depth exceeded");
                                }
                                var inst = new PyInstance(cls);
                                PyObject[] sl = borrowSlots(slots);
                                sl[0] = inst;
                                for (int i = argc; i >= 1; i--) {
                                    sl[i] = stack.pop();
                                }
                                stack.pop(); // pop cls
                                callStack.push(new Frame(code, pc + 1, currentLocals, stack, exceptStack,
                                        activeExceptions, currentFunc, currentFile, currentLine, currentGlobals, globalCells, currentFunction, slotLocals, inst));
                                globalCells = null;
                                currentLocals = initFn.closure();
                                slotLocals = sl;
                                stack = borrowStack();
                                exceptStack = borrowExceptStack();
                                activeExceptions = borrowActiveExceptions();
                                currentFunc = initFn.name();
                                currentFunction = initFn;
                                code = fnCode;
                                bc = code.bytecode();
                                pool = code.constPool();
                                pyPool = code.pyConstPool();
                                pc = 0;
                                continue outer;
                            }
                        }
                    }

                    var args = new PyObject[argc];
                    for (int i = argc - 1; i >= 0; i--) args[i] = stack.pop();
                    PyObject callable = stack.pop();

                    if (callable instanceof PyBoundMethod bm) {
                        PyFunction fn = bm.fn();
                        var callArgs = new PyObject[argc + 1];
                        callArgs[0] = bm.instance();
                        System.arraycopy(args, 0, callArgs, 1, argc);
                        if (fn.isAsync() || containsYield(fn.code())) {
                            var genLocals = bindArgs(fn, callArgs, null, null);
                            PyGenerator gen = createGenerator(fn, genLocals);
                            stack.push(fn.isAsync() ? new PyCoroutine(gen) : gen);
                        } else {
                            if (callDepth() >= MAX_CALL_DEPTH) {
                                throw PyErrors.error("RecursionError", "maximum recursion depth exceeded");
                            }
                            callStack.push(new Frame(code, pc + 1, currentLocals, stack, exceptStack,
                                    activeExceptions, currentFunc, currentFile, currentLine, currentGlobals, globalCells, currentFunction, slotLocals));
                            globalCells = null;
                            installFunctionLocals(fn, bindArgs(fn, callArgs, null, null));
                            stack = borrowStack();
                            exceptStack = borrowExceptStack();
                            activeExceptions = borrowActiveExceptions();
                            currentFunc = fn.name();
                            currentFunction = fn;
                            code = fn.code();
                            bc = code.bytecode();
                            pool = code.constPool();
                            pyPool = code.pyConstPool();
                            pc = 0;
                            continue outer;
                        }
                    } else if (callable instanceof PyFunction fn2 && (fn2.isAsync() || containsYield(fn2.code()))) {
                        var genLocals = bindArgs(fn2, args, null, null);
                        PyGenerator gen = createGenerator(fn2, genLocals);
                        stack.push(fn2.isAsync() ? new PyCoroutine(gen) : gen);
                    } else if (callable instanceof PyClass cls) {
                        if (cls.isExceptionType()) {
                            // Calling an exception class produces an exception
                            // value, not a plain instance.
                            stack.push(exceptionValue(cls, args));
                        } else {
                            // Instantiate class
                            var inst = new PyInstance(cls);
                            PyObject initObj = cls.lookupMember("__init__");
                            if (initObj instanceof PyFunction initFn) {
                                var initArgs = new PyObject[argc + 1];
                                initArgs[0] = inst;
                                System.arraycopy(args, 0, initArgs, 1, argc);
                                callPyCallable(initFn, initArgs);
                            } else if (initObj instanceof PyBuiltinFunction initBf) {
                                var initArgs = new PyObject[argc + 1];
                                initArgs[0] = inst;
                                System.arraycopy(args, 0, initArgs, 1, argc);
                                initBf.callWithKw(initArgs, Collections.emptyMap());
                            }
                            stack.push(inst);
                        }
                    } else if (callable instanceof PyFunction fn) {
                        if (fn.isAsync() || containsYield(fn.code())) {
                            var genLocals = bindArgs(fn, args, null, null);
                            PyGenerator gen = createGenerator(fn, genLocals);
                            stack.push(fn.isAsync() ? new PyCoroutine(gen) : gen);
                        } else {
                            if (callDepth() >= MAX_CALL_DEPTH) {
                                throw PyErrors.error("RecursionError", "maximum recursion depth exceeded");
                            }
                            callStack.push(new Frame(code, pc + 1, currentLocals, stack, exceptStack,
                                    activeExceptions, currentFunc, currentFile, currentLine, currentGlobals, globalCells, currentFunction, slotLocals));
                            globalCells = null; // new frame starts with no global declarations
                            installFunctionLocals(fn, bindArgs(fn, args, null, null));
                            stack = borrowStack();
                            exceptStack = borrowExceptStack();
                            activeExceptions = borrowActiveExceptions();
                            currentFunc = fn.name();
                            currentFunction = fn;
                            code = fn.code();
                            bc = code.bytecode();
                            pool = code.constPool();
                            pyPool = code.pyConstPool();
                            pc = 0;
                            continue outer;
                        }
                    } else if (callable instanceof PyBuiltinFunction bf) {
                        stack.push(bf.fn().apply(args));
                    } else if (callable instanceof PyJavaClass jc) {
                        stack.push(jc.newInstance(args));
                    } else if (callable instanceof PyJavaMethod jm) {
                        stack.push(jm.invoke(args));
                    } else if (callable instanceof PyInstance inst) {
                        PyObject callMember = inst.pyClass().lookupMember("__call__");
                        if (callMember == null) {
                            throw PyErrors.error("TypeError", "'" + typeName(callable) + "' object is not callable");
                        }
                        var callArgs = new PyObject[argc + 1];
                        callArgs[0] = inst;
                        System.arraycopy(args, 0, callArgs, 1, argc);
                        stack.push(callPyCallable(callMember, callArgs));
                    } else {
                        throw PyErrors.error("TypeError", "'" + typeName(callable) + "' object is not callable");
                    }
                }
                case YIELD_VALUE -> {
                    PyObject rv = stack.pop();
                    if (activeGenerator != null && callStack.size() == baseDepth) {
                        // Suspend the generator right after this YIELD_VALUE instruction.
                        activeGenerator.suspendAt(pc + 1, stack, currentLocals, exceptStack,
                                activeExceptions, globalCells, currentGlobals, currentLine);
                        return rv;
                    }
                    throw PyErrors.error("SyntaxError", "'yield' outside function");
                }
                case YIELD_FROM -> {
                    if (activeGenerator == null || callStack.size() != baseDepth) {
                        throw PyErrors.error("SyntaxError", "'yield' outside function");
                    }
                    PyObject sentVal = stack.pop();
                    PyObject sub = stack.peek();
                    if (sub instanceof PyGenerator subGen) {
                        try {
                            PyObject y = sendGenerator(subGen, sentVal);
                            activeGenerator.suspendAt(pc, stack, currentLocals, exceptStack,
                                    activeExceptions, globalCells, currentGlobals, currentLine);
                            return y;
                        } catch (InterpreterException ie) {
                            stack.pop(); // pop exhausted or failed subGen
                            if ("StopIteration".equals(ie.typeName())) {
                                PyObject retVal = (ie.value() instanceof PyException pe && !pe.args().isEmpty())
                                        ? pe.args().get(0) : PyNull.INSTANCE;
                                stack.push(retVal);
                                pc = ai;
                                continue;
                            }
                            throw ie;
                        }
                    } else if (sub instanceof PyIterator subIter) {
                        if (!(sentVal instanceof PyNull)) {
                            throw PyErrors.error("TypeError", "can't send non-None value to a non-generator iterator");
                        }
                        try {
                            if (subIter.hasNext()) {
                                PyObject y = subIter.next();
                                activeGenerator.suspendAt(pc, stack, currentLocals, exceptStack,
                                        activeExceptions, globalCells, currentGlobals, currentLine);
                                return y;
                            }
                        } catch (InterpreterException ie) {
                            stack.pop();
                            throw ie;
                        }
                        stack.pop(); // pop exhausted subIter
                        stack.push(PyNull.INSTANCE);
                        pc = ai;
                        continue;
                    } else {
                        throw PyErrors.error("TypeError", "yield from requires an iterator");
                    }
                }
                case GET_AWAITABLE -> {
                    PyObject obj = stack.pop();
                    if (obj instanceof PyCoroutine coro) {
                        stack.push(coro);
                    } else if (obj instanceof PyInstance inst) {
                        PyObject awaitFn = inst.pyClass().lookupMember("__await__");
                        if (awaitFn == null) {
                            throw PyErrors.error("TypeError", "object " + typeName(obj) + " can't be used in 'await' expression");
                        }
                        PyObject itObj = callPyCallable(awaitFn, new PyObject[]{inst});
                        if (itObj instanceof PyIterator || itObj instanceof PyGenerator || itObj instanceof PyCoroutine) {
                            stack.push(itObj);
                        } else if (itObj instanceof PyInstance iterInst && iterInst.pyClass().hasMember("__next__")) {
                            stack.push(BuiltinFunctions.customInstanceIterator(iterInst, interpreterCaller));
                        } else {
                            throw PyErrors.error("TypeError", "__await__() returned a non-iterator of type '" + typeName(itObj) + "'");
                        }
                    } else {
                        throw PyErrors.error("TypeError", "object " + typeName(obj) + " can't be used in 'await' expression");
                    }
                }
                case AWAIT_FROM -> {
                    if (activeGenerator == null || callStack.size() != baseDepth) {
                        throw PyErrors.error("SyntaxError", "'await' outside async function");
                    }
                    PyObject sentVal = stack.pop();
                    PyObject sub = stack.peek();
                    PyGenerator subGen = (sub instanceof PyCoroutine sc) ? sc.runner()
                            : (sub instanceof PyGenerator sg ? sg : null);
                    if (subGen != null) {
                        try {
                            PyObject y = sendGenerator(subGen, sentVal);
                            activeGenerator.suspendAt(pc, stack, currentLocals, exceptStack,
                                    activeExceptions, globalCells, currentGlobals, currentLine);
                            return y;
                        } catch (InterpreterException ie) {
                            stack.pop();
                            if ("StopIteration".equals(ie.typeName())) {
                                PyObject retVal = (ie.value() instanceof PyException pe && !pe.args().isEmpty())
                                        ? pe.args().get(0) : PyNull.INSTANCE;
                                stack.push(retVal);
                                pc = ai;
                                continue;
                            }
                            throw ie;
                        }
                    } else if (sub instanceof PyIterator subIter) {
                        if (!(sentVal instanceof PyNull)) {
                            throw PyErrors.error("TypeError", "can't send non-None value to a non-generator iterator");
                        }
                        try {
                            if (subIter.hasNext()) {
                                PyObject y = subIter.next();
                                activeGenerator.suspendAt(pc, stack, currentLocals, exceptStack,
                                        activeExceptions, globalCells, currentGlobals, currentLine);
                                return y;
                            }
                        } catch (InterpreterException ie) {
                            stack.pop();
                            if ("StopIteration".equals(ie.typeName())) {
                                PyObject retVal = (ie.value() instanceof PyException pe && !pe.args().isEmpty())
                                        ? pe.args().get(0) : PyNull.INSTANCE;
                                stack.push(retVal);
                                pc = ai;
                                continue;
                            }
                            throw ie;
                        }
                        stack.pop();
                        stack.push(PyNull.INSTANCE);
                        pc = ai;
                        continue;
                    } else {
                        throw PyErrors.error("TypeError", "object " + typeName(sub) + " can't be used in 'await' expression");
                    }
                }
                case GET_AITER -> {
                    PyObject obj = stack.pop();
                    if (obj instanceof PyInstance inst) {
                        PyObject aiterFn = inst.pyClass().lookupMember("__aiter__");
                        if (aiterFn == null) {
                            throw PyErrors.error("TypeError",
                                    "'async for' requires an object with __aiter__ method, got " + typeName(obj));
                        }
                        PyObject aiterObj = callPyCallable(aiterFn, new PyObject[]{inst});
                        if (aiterObj instanceof PyInstance aiInst && aiInst.pyClass().hasMember("__anext__")) {
                            stack.push(aiInst);
                        } else {
                            throw PyErrors.error("TypeError",
                                    "'async for' received an object from __aiter__ that does not implement __anext__: " + typeName(aiterObj));
                        }
                    } else {
                        throw PyErrors.error("TypeError",
                                "'async for' requires an object with __aiter__ method, got " + typeName(obj));
                    }
                }
                case RETURN_VALUE -> {
                    PyObject rv = stack.pop();
                    if (callStack.size() <= baseDepth) {
                        if (activeGenerator != null) {
                            activeGenerator.complete();
                            if (rv instanceof PyNull) {
                                throw PyErrors.errorValue(new PyException(exceptionTypes.type("StopIteration"), List.of()))
                                        .markGeneratorReturn();
                            }
                            throw PyErrors.errorValue(
                                    new PyException(exceptionTypes.type("StopIteration"), List.of(rv)))
                                    .markGeneratorReturn();
                        }
                        stack.push(rv);
                        return null;
                    }
                    Frame cf = callStack.pop();
                    recycleSlots(slotLocals, code.slotCount());
                    recycleLocals(currentLocals);
                    recycleStack(stack);
                    recycleExceptStack(exceptStack);
                    recycleActiveExceptions(activeExceptions);
                    code = cf.code();
                    bc = code.bytecode();
                    pool = code.constPool();
                    pyPool = code.pyConstPool();
                    pc = cf.returnPc();
                    currentLocals = cf.locals();
                    slotLocals = cf.slotLocals();
                    if (cf.globals() != null) currentGlobals = cf.globals();
                    globalCells = cf.globalCells(); // restore caller's global declarations
                    stack = cf.stack();
                    exceptStack = cf.exceptHandlers();
                    activeExceptions = cf.activeExceptions();
                    currentFunc = cf.funcName();
                    currentFunction = cf.function();
                    currentFile = cf.file();
                    currentLine = cf.line();
                    stack.push(cf.initInstance() != null ? cf.initInstance() : rv);
                    continue outer;
                }

                // ── Stack ──
                case POP_TOP -> stack.pop();
                case DUP_TOP -> stack.push(stack.peek());
                case DUP_TOP_TWO -> {
                    PyObject top = stack.pop();
                    PyObject second = stack.peek();
                    stack.push(top);
                    stack.push(second);
                    stack.push(top);
                }
                case ROT_TWO -> {
                    PyObject top = stack.pop();
                    PyObject second = stack.pop();
                    stack.push(top);
                    stack.push(second);
                }
                case ROT_THREE -> {
                    PyObject top = stack.pop();
                    PyObject second = stack.pop();
                    PyObject third = stack.pop();
                    stack.push(second);
                    stack.push(top);
                    stack.push(third);
                }

                // ── Data structures ──
                case BUILD_LIST -> {
                    int n = ai;
                    var arr = new PyObject[n];
                    for (int i = n - 1; i >= 0; i--) arr[i] = stack.pop();
                    var items = new ArrayList<PyObject>(n);
                    Collections.addAll(items, arr);
                    stack.push(new PyList(items));
                }
                case BUILD_TUPLE -> {
                    int n = ai;
                    var arr = new PyObject[n];
                    for (int i = n - 1; i >= 0; i--) arr[i] = stack.pop();
                    var items = new ArrayList<PyObject>(n);
                    Collections.addAll(items, arr);
                    stack.push(new PyTuple(items));
                }
                case BUILD_DICT -> {
                    int n = ai;
                    var pairs = new PyObject[n * 2];
                    for (int i = n * 2 - 1; i >= 0; i--) pairs[i] = stack.pop();
                    // Insert straight into PyDict so keys use PyKey hash/eq (and unhashable keys raise TypeError)
                    var dict = new PyDict();
                    for (int i = 0; i < n * 2; i += 2) dict.put(pairs[i], pairs[i + 1]);
                    stack.push(dict);
                }
                case BUILD_SLICE -> {
                    int count = ai;
                    if (count == 3) {
                        PyObject step = stack.pop();
                        PyObject stop = stack.pop();
                        PyObject start = stack.pop();
                        stack.push(new org.example.jython3.runtime.PySlice(start, stop, step));
                    } else {
                        PyObject stop = stack.pop();
                        PyObject start = stack.pop();
                        stack.push(new org.example.jython3.runtime.PySlice(start, stop));
                    }
                }
                case BINARY_SUBSCR -> {
                    PyObject idx = stack.pop();
                    PyObject ctr = stack.pop();
                    if (ctr instanceof PyList list && idx instanceof org.example.jython3.runtime.PySlice slice) {
                        var indices = slice.computeIndices(list.items().size());
                        if (indices.length() > executionLimits.maxCollectionSize()) {
                            throw PyErrors.error("OverflowError", "Slice result exceeds maximum collection size: " + executionLimits.maxCollectionSize());
                        }
                        var resList = new ArrayList<PyObject>(indices.length());
                        for (int i = indices.start(), count = 0; count < indices.length(); i += indices.step(), count++) {
                            resList.add(list.items().get(i));
                        }
                        stack.push(new PyList(resList));
                    } else if (ctr instanceof PyTuple tuple && idx instanceof org.example.jython3.runtime.PySlice slice) {
                        var indices = slice.computeIndices(tuple.items().size());
                        if (indices.length() > executionLimits.maxCollectionSize()) {
                            throw PyErrors.error("OverflowError", "Slice result exceeds maximum collection size: " + executionLimits.maxCollectionSize());
                        }
                        var resList = new ArrayList<PyObject>(indices.length());
                        for (int i = indices.start(), count = 0; count < indices.length(); i += indices.step(), count++) {
                            resList.add(tuple.items().get(i));
                        }
                        stack.push(new PyTuple(resList));
                    } else if (ctr instanceof PyStr s && idx instanceof org.example.jython3.runtime.PySlice slice) {
                        int len = s.codePointCount();
                        var indices = slice.computeIndices(len);
                        if (indices.length() > executionLimits.maxStringLength()) {
                            throw PyErrors.error("OverflowError", "Slice result exceeds maximum string length: " + executionLimits.maxStringLength());
                        }
                        if (!s.hasSurrogates() && indices.step() == 1) {
                            stack.push(new PyStr(s.value().substring(indices.start(), indices.start() + indices.length())));
                        } else {
                            int[] codePoints = s.codePoints();
                            var sb = new StringBuilder(indices.length());
                            for (int i = indices.start(), count = 0; count < indices.length(); i += indices.step(), count++) {
                                sb.appendCodePoint(codePoints[i]);
                            }
                            stack.push(new PyStr(sb.toString()));
                        }
                    } else if (ctr instanceof PyList list && idx instanceof PyInt ix) {
                        int i = ix.value();
                        if (i < 0) i += list.items().size();
                        if (i < 0 || i >= list.items().size())
                            throw PyErrors.error("IndexError", "list index out of range");
                        stack.push(list.items().get(i));
                    } else if (ctr instanceof PyTuple tuple && idx instanceof PyInt ix) {
                        int i = ix.value();
                        if (i < 0) i += tuple.items().size();
                        if (i < 0 || i >= tuple.items().size())
                            throw PyErrors.error("IndexError", "tuple index out of range");
                        stack.push(tuple.items().get(i));
                    } else if (ctr instanceof PyDict dict) {
                        PyObject val = dict.get(idx);
                        if (val == null) throw PyErrors.error("KeyError", String.valueOf(idx));
                        stack.push(val);
                    } else if (ctr instanceof PyStr s && idx instanceof PyInt ix) {
                        stack.push(new PyStr(s.getCodePointAt(ix.value())));
                    } else if (ctr instanceof PyRange r && idx instanceof PySlice sl) {
                        stack.push(r.slice(sl));
                    } else if (ctr instanceof PyRange r) {
                        stack.push(r.getAt(idx));
                    } else if (ctr instanceof PyBytes b && idx instanceof PySlice sl) {
                        stack.push(b.slice(sl));
                    } else if (ctr instanceof PyBytes b) {
                        stack.push(b.getAt(idx));
                    } else if (ctr instanceof PyByteArray ba && idx instanceof PySlice sl) {
                        stack.push(ba.slice(sl));
                    } else if (ctr instanceof PyByteArray ba) {
                        stack.push(ba.getAt(idx));
                    } else if (ctr instanceof PyInstance inst) {
                        PyObject getitem = inst.getField("__getitem__");
                        if (getitem != null) {
                            stack.push(callPyCallable(getitem, new PyObject[]{idx}));
                        } else {
                            throw PyErrors.error("TypeError", "'" + typeName(ctr) + "' object is not subscriptable");
                        }
                    } else if (ctr instanceof PyJavaObject jo) {
                        Object jObj = jo.object();
                        if (jObj instanceof java.util.List<?> l) {
                            if (idx instanceof PyInt ix) {
                                int i = ix.value();
                                if (i < 0) i += l.size();
                                if (i < 0 || i >= l.size())
                                    throw PyErrors.error("IndexError", "Java list index out of range");
                                stack.push(JavaBridge.toPyObject(l.get(i), jo.policy()));
                            } else {
                                throw PyErrors.error("TypeError", "Java list indices must be integers");
                            }
                        } else if (jObj instanceof java.util.Map<?,?> m) {
                            Object key = JavaBridge.toJavaObject(idx, Object.class, jo.policy());
                            Object val = m.get(key);
                            if (val == null && !m.containsKey(key))
                                throw PyErrors.error("KeyError", idx.toString());
                            stack.push(JavaBridge.toPyObject(val, jo.policy()));
                        } else if (jObj.getClass().isArray()) {
                            if (idx instanceof PyInt ix) {
                                int i = ix.value();
                                int len = java.lang.reflect.Array.getLength(jObj);
                                if (i < 0) i += len;
                                if (i < 0 || i >= len)
                                    throw PyErrors.error("IndexError", "Java array index out of range");
                                stack.push(JavaBridge.toPyObject(java.lang.reflect.Array.get(jObj, i), jo.policy()));
                            } else {
                                throw PyErrors.error("TypeError", "Java array indices must be integers");
                            }
                        } else {
                            throw PyErrors.error("TypeError", "Java object '" + jObj.getClass().getName() + "' is not subscriptable");
                        }
                    } else {
                        throw PyErrors.error("TypeError", "unsupported subscript types");
                    }
                }
                case GET_ITER -> {
                    PyObject o = stack.pop();
                    if (o instanceof PyIterator iter) stack.push(iter);
                    else if (o instanceof PyRange r) stack.push(r.iterator());
                    else if (o instanceof PyBytes b) stack.push(b.iterator());
                    else if (o instanceof PyByteArray ba) stack.push(ba.iterator());
                    else if (o instanceof PyList list) stack.push(new PyIterator(list.items(), 0));
                    else if (o instanceof PyTuple tuple) stack.push(new PyIterator(tuple.items(), 0));
                    else if (o instanceof PyStr s) {
                        stack.push(s.codePointIterator());
                    } else if (o instanceof PyDict d) {
                        stack.push(new PyIterator(d.keys(), 0));
                    } else if (o instanceof PySet s) {
                        stack.push(new PyIterator(new ArrayList<>(s.items()), 0));
                    } else if (o instanceof PyFrozenSet fs) {
                        stack.push(new PyIterator(new ArrayList<>(fs.items()), 0));
                    } else if (o instanceof PyGenerator gen) {
                        stack.push(gen);
                    } else if (o instanceof PyInstance inst) {
                        PyObject iterFn = inst.pyClass().lookupMember("__iter__");
                        if (iterFn != null) {
                            PyObject itObj = callPyCallable(iterFn, new PyObject[]{inst});
                            if (itObj instanceof PyIterator || itObj instanceof PyGenerator) {
                                stack.push(itObj);
                            } else if (itObj instanceof PyInstance iterInst && iterInst.pyClass().hasMember("__next__")) {
                                stack.push(BuiltinFunctions.customInstanceIterator(iterInst, interpreterCaller));
                            } else {
                                throw PyErrors.error("TypeError", "iter() returned non-iterator of type '" + typeName(itObj) + "'");
                            }
                        } else if (inst.pyClass().hasMember("__next__")) {
                            stack.push(BuiltinFunctions.customInstanceIterator(inst, interpreterCaller));
                        } else if (inst.pyClass().hasMember("__getitem__")) {
                            stack.push(new PyIterator(BuiltinFunctions.collectIterable(inst, javaAccessPolicy), 0));
                        } else {
                            throw PyErrors.error("TypeError", "'" + typeName(o) + "' is not iterable");
                        }
                    } else if (o instanceof PyJavaObject jo && jo.object() instanceof Iterable<?> iterable) {
                        stack.push(PyIterator.fromJavaIterable(iterable, jo.policy(), currentContext));
                    } else {
                        throw PyErrors.error("TypeError", "'" + typeName(o) + "' is not iterable");
                    }
                }
                case FOR_ITER -> {
                    PyObject top = stack.peek();
                    if (top instanceof PyIterator iter) {
                        PyObject nextVal = iter.fastNext();
                        if (nextVal != null) stack.push(nextVal);
                        else { stack.pop(); pc = ai; continue; }
                    } else if (top instanceof PyGenerator gen) {
                        try {
                            stack.push(stepGenerator(gen));
                        } catch (InterpreterException ie) {
                            if ("StopIteration".equals(ie.typeName())) {
                                stack.pop();
                                pc = ai;
                                continue;
                            }
                            throw ie;
                        }
                    } else {
                        throw PyErrors.error("TypeError", "expected iterator");
                    }
                }

                // ── Attributes ──
                case LOAD_ATTR -> {
                    String attr = (String) pool[ai];
                    PyObject obj = stack.pop();
                    if (obj instanceof PyInstance inst) {
                        PyClass cls = inst.pyClass();
                        if (!cls.hasProperties()) {
                            PyObject val = inst.fields().get(attr);
                            if (val != null) {
                                stack.push(val);
                                break;
                            }
                        }
                        Object[] cache = code.attrCache();
                        AttrCacheEntry entry = (cache != null && pc < cache.length) ? (AttrCacheEntry) cache[pc] : null;
                        PyObject member;
                        if (entry != null && entry.cachedClass() == cls && entry.cachedClassVersion() == cls.version()) {
                            member = entry.cachedMember();
                        } else {
                            member = cls.lookupMember(attr);
                            if (cache != null && pc < cache.length && member != null) {
                                cache[pc] = new AttrCacheEntry(cls, cls.version(), member);
                            }
                        }
                        if (member instanceof PyProperty prop && prop.isDataDescriptor()) {
                            if (prop.fget() == null) {
                                throw PyErrors.error("AttributeError", "property '" + attr + "' of '" + cls.name() + "' object has no getter");
                            }
                            stack.push(callPyCallable(prop.fget(), new PyObject[]{inst}));
                        } else {
                            PyObject val = inst.fields().get(attr);
                            if (val != null) {
                                stack.push(val);
                            } else if (member != null) {
                                if (member instanceof PyProperty prop) {
                                    if (prop.fget() == null) {
                                        throw PyErrors.error("AttributeError", "property '" + attr + "' of '" + cls.name() + "' object has no getter");
                                    }
                                    stack.push(callPyCallable(prop.fget(), new PyObject[]{inst}));
                                } else if (member instanceof PyStaticMethod sm) {
                                    stack.push(sm.callable());
                                } else if (member instanceof PyClassMethod cm) {
                                    stack.push(cm.bind(cls));
                                } else if (member instanceof PyFunction fn) {
                                    stack.push(new PyBoundMethod(inst, fn));
                                } else if (member instanceof PyBuiltinFunction bf) {
                                    stack.push(new PyBuiltinFunction(bf.name(), (a, kw) -> {
                                        PyObject[] bound = new PyObject[a.length + 1];
                                        bound[0] = inst;
                                        System.arraycopy(a, 0, bound, 1, a.length);
                                        return bf.callWithKw(bound, kw);
                                    }));
                                } else {
                                    stack.push(member);
                                }
                            } else if (inst.fields().containsKey(attr)) {
                                stack.push(inst.fields().get(attr));
                            } else if ("__class__".equals(attr)) {
                                stack.push((cls == BuiltinFunctions.OBJECT_CLASS) ? builtinTypes.get("object") : cls);
                            } else {
                                PyObject getattrFn = cls.lookupMember("__getattr__");
                                if (getattrFn instanceof PyFunction || getattrFn instanceof PyBuiltinFunction) {
                                    stack.push(callPyCallable(getattrFn, new PyObject[]{inst, new PyStr(attr)}));
                                } else {
                                    throw PyErrors.error("AttributeError", "'" + cls.name() + "' object has no attribute '" + attr + "'");
                                }
                            }
                        }
                    } else if (obj instanceof PyClass cls) {
                        PyObject val = cls.lookupMember(attr);
                        if (val instanceof PyStaticMethod sm) val = sm.callable();
                        else if (val instanceof PyClassMethod cm) val = cm.bind(cls);
                        if (val == null && "__name__".equals(attr)) val = new PyStr(cls.name());
                        if (val == null && "__class__".equals(attr)) val = builtinTypes.get("type");
                        if (val == null && "__mro__".equals(attr)) val = BuiltinFunctions.classMroTuple(cls, builtinTypes);
                        if (val == null && "__bases__".equals(attr)) val = BuiltinFunctions.classBasesTuple(cls, builtinTypes);
                        if (val == null) throw PyErrors.error("AttributeError", "type object '" + cls.name() + "' has no attribute '" + attr + "'");
                        stack.push(val);
                    } else if (obj instanceof PyProperty prop) {
                        stack.push("__class__".equals(attr) ? builtinTypes.get("property") : prop.getAttr(attr));
                    } else if (obj instanceof PyException pe) {
                        PyObject val = pe.pythonAttribute(attr);
                        if (val == null) throw PyErrors.error("AttributeError", "'" + pe.typeName() + "' object has no attribute '" + attr + "'");
                        stack.push(val);
                    } else if (obj instanceof PyTraceback tb) {
                        PyObject val = tb.pythonAttribute(attr);
                        if (val == null) throw PyErrors.error("AttributeError", "'traceback' object has no attribute '" + attr + "'");
                        stack.push(val);
                    } else if (obj instanceof PyFrameInfo fr) {
                        PyObject val = fr.pythonAttribute(attr);
                        if (val == null) throw PyErrors.error("AttributeError", "'frame' object has no attribute '" + attr + "'");
                        stack.push(val);
                    } else if (obj instanceof PyCodeInfo co) {
                        PyObject val = co.pythonAttribute(attr);
                        if (val == null) throw PyErrors.error("AttributeError", "'code' object has no attribute '" + attr + "'");
                        stack.push(val);
                    } else if (obj instanceof PyFunction fn) {
                        if ("__name__".equals(attr)) stack.push(new PyStr(fn.name()));
                        else if ("__class__".equals(attr)) stack.push(BuiltinFunctions.FUNCTION_TYPE);
                        else {
                            PyObject extra = fn.getExtraAttr(attr);
                            if (extra != null) stack.push(extra);
                            else throw PyErrors.error("AttributeError", "'function' object has no attribute '" + attr + "'");
                        }
                    } else if (obj instanceof PyBuiltinFunction bf) {
                        if ("__name__".equals(attr)) stack.push(new PyStr(bf.name()));
                        else if ("__class__".equals(attr)) stack.push(BuiltinFunctions.typeOf(bf, builtinTypes));
                        else if (bf.isType() && "fromhex".equals(attr) && "bytes".equals(bf.name())) {
                            stack.push(new PyBuiltinFunction("bytes.fromhex", a -> {
                                PyOps.checkArgCount("fromhex", a, 1);
                                if (!(a[0] instanceof PyStr hs)) throw PyErrors.error("TypeError", "fromhex() argument must be str");
                                return PyBytes.wrapOwned(PyBytes.parseHex(hs.value()));
                            }));
                        } else if (bf.isType() && "fromhex".equals(attr) && "bytearray".equals(bf.name())) {
                            stack.push(new PyBuiltinFunction("bytearray.fromhex", a -> {
                                PyOps.checkArgCount("fromhex", a, 1);
                                if (!(a[0] instanceof PyStr hs)) throw PyErrors.error("TypeError", "fromhex() argument must be str");
                                return new PyByteArray(PyBytes.parseHex(hs.value()));
                            }));
                        } else if (bf.isType() && "fromkeys".equals(attr) && "dict".equals(bf.name())) {
                            stack.push(new PyBuiltinFunction("dict.fromkeys", a -> {
                                if (a.length < 1 || a.length > 2) throw PyErrors.error("TypeError", "fromkeys expected at most 2 arguments");
                                PyObject val = a.length == 2 ? a[1] : PyNull.INSTANCE;
                                var d = new PyDict();
                                for (PyObject k : BuiltinFunctions.collectIterable(a[0], javaAccessPolicy)) {
                                    d.put(k, val);
                                }
                                return d;
                            }));
                        }
                        else throw PyErrors.error("AttributeError", "'" + typeName(bf) + "' object has no attribute '" + attr + "'");
                    } else if (obj instanceof PyBoundMethod bm) {
                        if ("__name__".equals(attr)) stack.push(new PyStr(bm.fn().name()));
                        else if ("__class__".equals(attr)) stack.push(BuiltinFunctions.METHOD_TYPE);
                        else throw PyErrors.error("AttributeError", "'method' object has no attribute '" + attr + "'");
                    } else if (obj instanceof PyModule mod) {
                        PyObject val = mod.attrs().get(attr);
                        if (val == null && "__class__".equals(attr)) val = BuiltinFunctions.MODULE_TYPE;
                        if (val == null) throw PyErrors.error("AttributeError", "module '" + mod.name() + "' has no attribute '" + attr + "'");
                        stack.push(val);
                    } else if (obj instanceof PyDict dict) {
                        stack.push("__class__".equals(attr) ? builtinTypes.get("dict") : BuiltinMethodDispatcher.dictMethod(dict, attr));
                    } else if (obj instanceof PyList list) {
                        stack.push("__class__".equals(attr) ? builtinTypes.get("list") : BuiltinMethodDispatcher.listMethod(list, attr));
                    } else if (obj instanceof PySet set) {
                        stack.push("__class__".equals(attr) ? builtinTypes.get("set") : BuiltinMethodDispatcher.setMethod(set, attr, javaAccessPolicy));
                    } else if (obj instanceof PyFrozenSet fs) {
                        stack.push("__class__".equals(attr) ? builtinTypes.get("frozenset") : BuiltinMethodDispatcher.frozenSetMethod(fs, attr, javaAccessPolicy));
                    } else if (obj instanceof PyStr s) {
                        stack.push("__class__".equals(attr) ? builtinTypes.get("str") : BuiltinMethodDispatcher.strMethod(s, attr));
                    } else if (obj instanceof PyJavaObject jo) {
                        stack.push(jo.getMember(attr));
                    } else if (obj instanceof PyJavaClass jc) {
                        stack.push(jc.getMember(attr));
                    } else if (obj instanceof PyJavaPackage jp) {
                        stack.push(jp.getMember(attr));
                    } else if (obj instanceof PySuper sup) {
                        stack.push(sup.getMethod(attr));
                    } else if (obj instanceof PyGenerator gen) {
                        stack.push("__class__".equals(attr)
                                ? BuiltinFunctions.typeOf(gen, builtinTypes)
                                : BuiltinMethodDispatcher.generatorMethod(gen, attr));
                    } else if (obj instanceof PyCoroutine coro) {
                        stack.push("__class__".equals(attr)
                                ? BuiltinFunctions.typeOf(coro, builtinTypes)
                                : BuiltinMethodDispatcher.coroutineMethod(coro, attr));
                    } else if (obj instanceof PySlice sl) {
                        stack.push("__class__".equals(attr)
                                ? BuiltinFunctions.typeOf(sl, builtinTypes)
                                : BuiltinMethodDispatcher.sliceAttr(sl, attr));
                    } else if (obj instanceof PyRange r) {
                        stack.push("__class__".equals(attr)
                                ? BuiltinFunctions.typeOf(r, builtinTypes)
                                : BuiltinMethodDispatcher.rangeAttr(r, attr));
                    } else if (obj instanceof PyBytes b) {
                        stack.push("__class__".equals(attr)
                                ? BuiltinFunctions.typeOf(b, builtinTypes)
                                : BuiltinMethodDispatcher.bytesMethod(b, attr));
                    } else if (obj instanceof PyByteArray ba) {
                        stack.push("__class__".equals(attr)
                                ? BuiltinFunctions.typeOf(ba, builtinTypes)
                                : BuiltinMethodDispatcher.byteArrayMethod(ba, attr));
                    } else if (obj instanceof PyHostHandle hh) {
                        if ("__class__".equals(attr)) {
                            stack.push(BuiltinFunctions.typeOf(hh, builtinTypes));
                        } else {
                            PyObject val = hh.getAttr(attr);
                            if (val == null) {
                                throw PyErrors.error("AttributeError", "'" + hh.handleTypeName() + "' object has no attribute '" + attr + "'");
                            }
                            stack.push(val);
                        }
                    } else if ("__class__".equals(attr)) {
                        stack.push(BuiltinFunctions.typeOf(obj, builtinTypes));
                    } else {
                        throw PyErrors.error("AttributeError", "'" + typeName(obj) + "' object has no attribute '" + attr + "'");
                    }
                }

                // ── Exception handling ──
                case PUSH_EXCEPT_HANDLER -> exceptStack.push(new ExceptHandler(ai, activeExceptions.size(), stack.size()));
                case POP_EXCEPT_HANDLER  -> { if (!exceptStack.isEmpty()) exceptStack.pop(); }
                case BEGIN_EXCEPT_HANDLER -> activeExceptions.addLast(stack.peek());
                case END_EXCEPT_HANDLER -> { if (!activeExceptions.isEmpty()) activeExceptions.removeLast(); }
                case RAISE -> throw toRaise(stack.pop());
                case RAISE_FROM -> {
                    PyObject causeObj = stack.pop();
                    PyObject excObj = stack.pop();
                    throw toRaiseFrom(excObj, causeObj);
                }
                case RAISE_ACTIVE -> {
                    PyObject active = activeExceptions.peekLast();
                    if (active == null) {
                        throw PyErrors.error("RuntimeError", "No active exception to reraise");
                    }
                    throw PyErrors.reraise(active);
                }
                case COMPARE_EXC_TYPE -> {
                    // Stack: [..., exceptionValue, clauseType]
                    // Pops the clause type, keeps the value for binding, and
                    // pushes the match result.
                    PyObject typeObj = stack.pop();
                    PyObject excValue = stack.peek();
                    if (typeObj instanceof PyTuple tuple) {
                        boolean matched = false;
                        for (PyObject item : tuple.items()) {
                            if (!(item instanceof PyClass type) || !type.isExceptionType()) {
                                throw PyErrors.error("TypeError",
                                        "catching classes that do not inherit from BaseException is not allowed");
                            }
                            if (!matched && PyErrors.matches(excValue, type)) {
                                matched = true;
                            }
                        }
                        stack.push(PyBool.of(matched));
                    } else if (typeObj instanceof PyList tupleOrList) {
                        // Python allows a tuple of exception types: except (E1, E2, ...):
                        boolean matched = false;
                        for (PyObject item : tupleOrList.items()) {
                            if (!(item instanceof PyClass type) || !type.isExceptionType()) {
                                throw PyErrors.error("TypeError",
                                        "catching classes that do not inherit from BaseException is not allowed");
                            }
                            if (!matched && PyErrors.matches(excValue, type)) {
                                matched = true;
                            }
                        }
                        stack.push(PyBool.of(matched));
                    } else if (typeObj instanceof PyClass type && type.isExceptionType()) {
                        stack.push(PyBool.of(PyErrors.matches(excValue, type)));
                    } else {
                        throw PyErrors.error("TypeError",
                                "catching classes that do not inherit from BaseException is not allowed");
                    }
                }
                case RERAISE -> {
                    PyObject excObj = (ai == 1) ? activeExceptions.removeLast() : stack.pop();
                    throw PyErrors.reraise(excObj);
                }
                case EXECFILE -> {
                    // Stack top = file path string
                    PyObject pathObj = stack.pop();
                    String filePath = pathObj instanceof PyStr s ? s.value() : pathObj.toString();
                    java.nio.file.Path p = java.nio.file.Path.of(filePath);
                    if (!hostAccessPolicy.canExecFile(p)) {
                        throw PyErrors.error("SecurityError", "execution of file '" + filePath + "' is restricted");
                    }
                    try {
                        String src2 = java.nio.file.Files.readString(p);
                        CompiledCode fileCode = compile(src2);
                        // Execute in isolated VM context sharing current globals
                        executeIsolated(fileCode, currentLocals != null ? currentLocals : globals, filePath);
                    } catch (java.io.IOException ioe) {
                        throw PyErrors.error("IOError", "cannot open '" + filePath + "': " + ioe.getMessage());
                    }
                }
                case HALT -> {
                    if (activeGenerator != null && callStack.size() <= baseDepth) {
                        activeGenerator.complete();
                        throw PyErrors.error("StopIteration", "");
                    }
                    return null;
                }

                // ── Sequence unpacking: a, b = iterable ──────────────────
                case UNPACK_SEQUENCE -> {
                    int count = ai;
                    PyObject seq = stack.pop();
                    // Collect elements from any iterable into a fixed-size list
                    var elems = new java.util.ArrayList<PyObject>(count);
                    if (seq instanceof PyList pl) {
                        for (var e : pl.items()) elems.add(e);
                    } else if (seq instanceof PyTuple pt) {
                        for (var e : pt.items()) elems.add(e);
                    } else if (seq instanceof PyStr s) {
                        for (var e : s.toCodePointList()) elems.add(e);
                    } else if (seq instanceof PyIterator it) {
                        while (it.hasNext()) elems.add(it.next());
                    } else {
                        throw PyErrors.error("TypeError",
                                "cannot unpack non-iterable " + typeName(seq) + " object");
                    }
                    if (elems.size() != count) {
                        throw PyErrors.error("ValueError",
                                "not enough values to unpack (expected " + count +
                                ", got " + elems.size() + ")");
                    }
                    // Push in reverse order so first element is on top
                    for (int i = count - 1; i >= 0; i--) stack.push(elems.get(i));
                }

                // ── global / nonlocal declarations ───────────────────────
                // These are marker constants emitted by the compiler so the
                // name resolver knows to route future STORE_FAST to globals.
                // We handle them in the VM by pre-registering the name.
                case LOAD_GLOBAL_NAME -> {
                    String gName = (String) pool[ai];
                    PyObject gv = currentGlobals.get(gName);
                    if (gv == null) gv = globals.get(gName);
                    if (gv == null) gv = builtinSymbols.get(gName);
                    if (gv == null) throw PyErrors.error("NameError", "name '" + gName + "' is not defined");
                    stack.push(gv);
                }
                case STORE_GLOBAL_NAME -> {
                    String gName = (String) pool[ai];
                    PyObject gv = stack.pop();
                    if (currentGlobals != null) currentGlobals.put(gName, gv);
                    globals.put(gName, gv);
                }

                // ── assert statement ─────────────────────────────────────
                case ASSERT -> {
                    // arg: 0=no msg, 1=has msg on stack
                    // condition was already popped by JUMP_IF_TRUE_OR_POP
                    if (ai == 1) {
                        PyObject msg = stack.pop();
                        throw PyErrors.error("AssertionError", msg.toString());
                    } else {
                        throw PyErrors.error("AssertionError", "");
                    }
                }

                // ── List/Set comprehension accumulators ──────────────────
                case LIST_COMP_INIT -> stack.push(new PyList(new ArrayList<>()));
                case LIST_COMP_APPEND -> {
                    PyObject val = stack.pop();
                    if (stack.peek() instanceof PyList acc) acc.items().add(val);
                }
                case SET_COMP_INIT -> stack.push(new PySet());
                case SET_COMP_ADD -> {
                    PyObject val = stack.pop();
                    if (stack.peek() instanceof PySet acc) acc.add(val);
                }
                case BUILD_SET -> {
                    int n = ai;
                    var items = new PyObject[n];
                    for (int i = n - 1; i >= 0; i--) items[i] = stack.pop();
                    var set = new PySet();
                    for (var item : items) set.add(item);   // validates hashability (TypeError)
                    stack.push(set);
                }

                // ── with statement ───────────────────────────────────────
                case SETUP_WITH -> {
                    // Stack: [context_manager]
                    // After: [exit_fn, enter_result]
                    PyObject cm = stack.pop();
                    PyObject enterFn = getAttr(cm, "__enter__");
                    PyObject exitFn  = getAttr(cm, "__exit__");
                    if (enterFn == null) throw PyErrors.error("AttributeError", "__enter__ not found");
                    if (exitFn  == null) throw PyErrors.error("AttributeError", "__exit__ not found");
                    PyObject entered = callPyCallable(enterFn, new PyObject[0]);
                    stack.push(exitFn);     // bottom: exit_fn
                    stack.push(entered);    // top: __enter__ return value
                }
                case END_WITH -> {
                    // Normal exit: call __exit__(None, None, None)
                    // Stack: [exit_fn] (entered value already consumed by suite)
                    // The suite may have consumed the entered value or not;
                    // exit_fn is below it. We access it via a special pattern:
                    // The compiler arranged stack as [exit_fn, entered_value]
                    // After suite execution, only exit_fn should remain.
                    // We stored exit_fn before entered, so it's under the entered value.
                    // We need to pop both and call exit_fn.
                    PyObject exitFn2 = stack.pop(); // This is exit_fn (bottom of WITH pair)
                    callPyCallable(exitFn2, new PyObject[]{new PyNull(), new PyNull(), new PyNull()});
                }
                case WITH_EXCEPT_EXIT -> {
                    // Exception path: stack has [..., exit_fn, exception_value]
                    // Call __exit__(type, value, traceback)
                    PyObject excVal  = stack.pop();
                    PyObject exitFn3 = stack.pop();
                    PyObject excType = excVal instanceof PyException pe
                            ? pe.type() : new PyNull();
                    PyObject excTb = excVal instanceof PyException pe
                            ? pe.tracebackObject() : new PyNull();
                    PyObject result = callPyCallable(exitFn3,
                            new PyObject[]{excType, excVal, excTb});
                    if (isTruthy(result)) {
                        // Exception suppressed: jump after with
                        stack.push(PyBool.TRUE);
                    } else {
                        // Exception NOT suppressed: keep excVal on stack for RERAISE
                        stack.push(excVal);
                        stack.push(PyBool.FALSE);
                    }
                }
            } // end switch
            } catch (InterpreterException ie) {
                boolean continuation = ie.isContinuation();
                ie.clearContinuation();
                if (!continuation && ie.value() instanceof PyException pe) {
                    PyException activeCtx = currentContextException();
                    if (activeCtx != null) {
                        pe.attachContextIfAbsent(activeCtx);
                    }
                }
                if (!exceptStack.isEmpty()) {
                    ExceptHandler h = exceptStack.pop();
                    truncateActiveExceptions(h.activeDepth());
                    truncateStack(h.stackDepth());
                    if (!continuation) {
                        ie.setLocation(currentFile, currentLine, currentFunc);
                        ie.addFrame(currentFile, currentLine, currentFunc);
                    }
                    captureTraceback(ie);
                    stack.push(ie.value());
                    pc = h.exceptPc();
                    continue outer;
                }
                // No handler in this frame: record it (unless continuing an internal
                // RERAISE / bare raise whose frame was already recorded) and unwind.
                if (!continuation) {
                    ie.setLocation(currentFile, currentLine, currentFunc);
                    ie.addFrame(currentFile, currentLine, currentFunc);
                }
                while (callStack.size() > baseDepth) {
                    Frame cf = callStack.pop();
                    recycleSlots(slotLocals, code.slotCount());
                    recycleLocals(currentLocals);
                    recycleStack(stack);
                    recycleExceptStack(exceptStack);
                    recycleActiveExceptions(activeExceptions);
                    code = cf.code();
                    bc = code.bytecode();
                    pool = code.constPool();
                    pyPool = code.pyConstPool();
                    pc = cf.returnPc();
                    currentLocals = cf.locals();
                    slotLocals = cf.slotLocals();
                    if (cf.globals() != null) currentGlobals = cf.globals();
                    globalCells = cf.globalCells();
                    stack = cf.stack();
                    exceptStack = cf.exceptHandlers();
                    activeExceptions = cf.activeExceptions();
                    currentFunc = cf.funcName();
                    currentFunction = cf.function();
                    currentFile = cf.file();
                    currentLine = cf.line();
                    if (!exceptStack.isEmpty()) {
                        ExceptHandler h = exceptStack.pop();
                        truncateActiveExceptions(h.activeDepth());
                        truncateStack(h.stackDepth());
                        ie.addFrame(currentFile, currentLine, currentFunc);
                        captureTraceback(ie);
                        stack.push(ie.value());
                        pc = h.exceptPc();
                        continue outer;
                    }
                    ie.addFrame(currentFile, currentLine, currentFunc);
                }
                captureTraceback(ie);
                throw ie;
            }
            pc++;
        }
        return null;
    }

    // ─── Arithmetic & type method delegation ───────────────────────────────

    private static PyObject unwrapBool(PyObject o) {
        return ArithmeticOps.unwrapBool(o);
    }

    private void binaryAdd() {
        var r = stack.pop(); var l = stack.pop();
        stack.push(ArithmeticOps.add(l, r));
    }

    private void binarySubtract() {
        var r = stack.pop(); var l = stack.pop();
        stack.push(ArithmeticOps.subtract(l, r));
    }

    private void binaryMultiply() {
        var r = stack.pop(); var l = stack.pop();
        stack.push(ArithmeticOps.multiply(l, r, executionLimits));
    }

    private static String pyPrintStr(PyObject o) {
        if (o == null) return "None";
        if (o instanceof PyException pe) return pe.message();
        return o.toString();
    }

    private PyObject callPyCallable(PyObject callable, PyObject[] args) {
        return callPyCallableKw(callable, args, null, null);
    }

    /**
     * Retrieve an attribute from any PyObject, returning null if absent.
     * Used internally by SETUP_WITH and LOAD_ATTR to avoid duplicate logic.
     */
    private PyObject getAttr(PyObject obj, String attr) {
        if (obj instanceof PyInstance inst) {
            PyObject v = inst.getField(attr);
            if (v != null) return v;
            PyClass cls = inst.pyClass();
            PyObject cv = cls.lookupMember(attr);
            if (cv == null && "__class__".equals(attr)) {
                return (cls == BuiltinFunctions.OBJECT_CLASS) ? builtinTypes.get("object") : cls;
            }
            if (cv instanceof PyFunction fn) return new PyBoundMethod(inst, fn);
            return cv;
        }
        if (obj instanceof PyClass cls) {
            PyObject cv = cls.lookupMember(attr);
            if (cv instanceof PyStaticMethod sm) return sm.callable();
            if (cv instanceof PyClassMethod cm) return cm.bind(cls);
            if (cv != null) return cv;
            if ("__name__".equals(attr)) return new PyStr(cls.name());
            if ("__class__".equals(attr)) return builtinTypes.get("type");
            if ("__mro__".equals(attr)) return BuiltinFunctions.classMroTuple(cls, builtinTypes);
            if ("__bases__".equals(attr)) return BuiltinFunctions.classBasesTuple(cls, builtinTypes);
            return null;
        }
        if (obj instanceof PyException pe) return pe.pythonAttribute(attr);
        if (obj instanceof PyTraceback tb) return tb.pythonAttribute(attr);
        if (obj instanceof PyFrameInfo fr) return fr.pythonAttribute(attr);
        if (obj instanceof PyCodeInfo co) return co.pythonAttribute(attr);
        if (obj instanceof PyFunction fn) {
            if ("__name__".equals(attr)) return new PyStr(fn.name());
            if ("__class__".equals(attr)) return BuiltinFunctions.FUNCTION_TYPE;
            return fn.getExtraAttr(attr);
        }
        if (obj instanceof PyBuiltinFunction bf) {
            if ("__name__".equals(attr)) return new PyStr(bf.name());
            if ("__class__".equals(attr)) return BuiltinFunctions.typeOf(bf, builtinTypes);
            return null;
        }
        if (obj instanceof PyBoundMethod bm) {
            if ("__name__".equals(attr)) return new PyStr(bm.fn().name());
            if ("__class__".equals(attr)) return BuiltinFunctions.METHOD_TYPE;
            return null;
        }
        if (obj instanceof PyModule mod) {
            PyObject val = mod.attrs().get(attr);
            return (val == null && "__class__".equals(attr)) ? BuiltinFunctions.MODULE_TYPE : val;
        }
        if ("__class__".equals(attr)) return BuiltinFunctions.typeOf(obj, builtinTypes);
        if (obj instanceof PyDict dict) return BuiltinMethodDispatcher.dictMethod(dict, attr);
        if (obj instanceof PyList list) return BuiltinMethodDispatcher.listMethod(list, attr);
        if (obj instanceof PySet set)   return BuiltinMethodDispatcher.setMethod(set, attr, javaAccessPolicy);
        if (obj instanceof PyFrozenSet fs) return BuiltinMethodDispatcher.frozenSetMethod(fs, attr, javaAccessPolicy);
        if (obj instanceof PyGenerator gen) return BuiltinMethodDispatcher.generatorMethod(gen, attr);
        if (obj instanceof PyHostHandle hh) return hh.getAttr(attr);
        if (obj instanceof PyJavaObject jo) {
            try {
                return jo.getMember(attr);
            } catch (InterpreterException ignored) {
                return null;
            }
        }
        if (obj instanceof PyStr s)     return BuiltinMethodDispatcher.strMethod(s, attr);
        return null;
    }

    /**
     * Core call dispatcher with optional keyword arguments.
     * Both CALL_FUNCTION and CALL_FUNCTION_KW paths route through here.
     *
     * @param kwNames  keyword argument names, or null/empty for positional-only
     * @param kwValues keyword argument values parallel to kwNames
     */
    private void installFunctionLocals(PyFunction fn, Map<String, PyObject> bound) {
        CompiledCode fc = fn.code();
        int sc = fc.slotCount();
        if (sc > 0) {
            PyObject[] sl = borrowSlots(sc);
            String[] names = fc.slotNames();
            for (int i = 0; i < sc; i++) {
                sl[i] = bound.get(names[i]);
            }
            recycleLocals(bound);
            this.slotLocals = sl;
            this.currentLocals = fn.closure();
        } else {
            this.slotLocals = null;
            this.currentLocals = bound;
        }
    }

    private PyObject callPyCallableKw(PyObject callable, PyObject[] posArgs,
                                      String[] kwNames, PyObject[] kwValues) {
        if (callable instanceof PyBuiltinFunction bf) {
            Map<String, PyObject> kwMap = Collections.emptyMap();
            if (kwNames != null && kwNames.length > 0) {
                kwMap = new LinkedHashMap<>();
                for (int i = 0; i < kwNames.length; i++) {
                    kwMap.put(kwNames[i], kwValues[i]);
                }
            }
            return bf.callWithKw(posArgs, kwMap);
        }
        if (callable instanceof PyBoundMethod bm) {
            var callArgs = new PyObject[posArgs.length + 1];
            callArgs[0] = bm.instance();
            System.arraycopy(posArgs, 0, callArgs, 1, posArgs.length);
            return callPyCallableKw(bm.fn(), callArgs, kwNames, kwValues);
        }
        if (callable instanceof PyJavaClass jc) {
            return jc.newInstance(posArgs);
        }
        if (callable instanceof PyJavaMethod jm) {
            return jm.invoke(posArgs);
        }
        if (callable instanceof PyClass cls) {
            if (cls.isExceptionType()) {
                return exceptionValue(cls, posArgs);
            }
            var inst = new PyInstance(cls);
            PyObject initObj = cls.lookupMember("__init__");
            if (initObj instanceof PyFunction initFn) {
                var initArgs = new PyObject[posArgs.length + 1];
                initArgs[0] = inst;
                System.arraycopy(posArgs, 0, initArgs, 1, posArgs.length);
                callPyCallableKw(initFn, initArgs, kwNames, kwValues);
            } else if (initObj instanceof PyBuiltinFunction initBf) {
                var initArgs = new PyObject[posArgs.length + 1];
                initArgs[0] = inst;
                System.arraycopy(posArgs, 0, initArgs, 1, posArgs.length);
                callPyCallableKw(initBf, initArgs, kwNames, kwValues);
            }
            return inst;
        }
        if (callable instanceof PyInstance inst) {
            PyObject callMember = inst.pyClass().lookupMember("__call__");
            if (callMember != null) {
                var callArgs = new PyObject[posArgs.length + 1];
                callArgs[0] = inst;
                System.arraycopy(posArgs, 0, callArgs, 1, posArgs.length);
                return callPyCallableKw(callMember, callArgs, kwNames, kwValues);
            }
        }
        if (callable instanceof PyFunction fn) {
            if (fn.isAsync() || containsYield(fn.code())) {
                // Generators and coroutines: bind args to locals eagerly, store in suspended object
                var genLocals = bindArgs(fn, posArgs, kwNames, kwValues);
                PyGenerator gen = createGenerator(fn, genLocals);
                return fn.isAsync() ? new PyCoroutine(gen) : gen;
            }
            var savedStack = stack;
            var savedLocals = currentLocals;
            var savedSlots = slotLocals;
            var savedExcept = exceptStack;
            var savedActive = activeExceptions;
            var savedFunc = currentFunc;
            var savedFunction = currentFunction;
            var savedLine = currentLine;
            var savedFile = currentFile;
            var savedGlobals = currentGlobals;
            var savedGlobalCells = globalCells;
            var savedActiveGen = activeGenerator;
            PyException outerCtx = currentContextException();
            if (outerCtx != null) outerActiveContexts.addLast(outerCtx);

            FastStack borrowedStack = null;
            Deque<ExceptHandler> borrowedExcept = null;
            Deque<PyObject> borrowedActive = null;
            try {
                installFunctionLocals(fn, bindArgs(fn, posArgs, kwNames, kwValues));
                stack = borrowedStack = borrowStack();
                exceptStack = borrowedExcept = borrowExceptStack();
                activeExceptions = borrowedActive = borrowActiveExceptions();
                globalCells = null;
                activeGenerator = null;
                currentFunc = fn.name();
                currentFunction = fn;
                runNested(fn.code(), 0);
                return stack.isEmpty() ? new PyNull() : stack.pop();
            } finally {
                activeGenerator = savedActiveGen;
                if (outerCtx != null) outerActiveContexts.removeLast();
                recycleSlots(slotLocals, fn.code().slotCount());
                recycleStack(borrowedStack);
                recycleExceptStack(borrowedExcept);
                recycleActiveExceptions(borrowedActive);
                // Always restore the caller's frame state, including when the callee raises.
                stack = savedStack;
                currentLocals = savedLocals;
                slotLocals = savedSlots;
                exceptStack = savedExcept;
                activeExceptions = savedActive;
                currentFunc = savedFunc;
                currentFunction = savedFunction;
                currentLine = savedLine;
                currentFile = savedFile;
                currentGlobals = savedGlobals;
                globalCells = savedGlobalCells;
            }
        }
        throw PyErrors.error("TypeError", "'" + typeName(callable) + "' object is not callable");
    }

    /**
     * Python argument binding algorithm — CPython-compatible.
     *
     * <p>Algorithm:
     * 1. Fill {@code params[0..n-1]} from positional args left to right.
     * 2. Fill remaining param slots from keyword args by name.
     * 3. Fill any still-empty trailing slots from {@code fn.defaults()} (right-aligned).
     * 4. Collect extra positionals into {@code *argsParam} tuple.
     * 5. Collect extra keywords into {@code **kwargsParam} dict.
     * 6. Raise {@code TypeError} for missing required params or unexpected keywords.
     *
     * @param fn        the function being called
     * @param posArgs   positional arguments from the call site
     * @param kwNames   keyword argument names (may be null)
     * @param kwValues  keyword argument values parallel to kwNames (may be null)
     * @return locals map ready to be installed as the function frame
     */
    private Map<String, PyObject> bindArgs(PyFunction fn,
                                           PyObject[] posArgs,
                                           String[] kwNames,
                                           PyObject[] kwValues) {
        List<String> params     = fn.params();
        PyObject[]   defaults   = fn.defaults();
        String       argsParam  = fn.argsParam();
        String       kwargsParam = fn.kwargsParam();
        int nParams   = params.size();
        int nDefaults = defaults.length;
        int nPos = posArgs.length;

        int totalExpected = (fn.closure() != null ? fn.closure().size() : 0) + nParams + 4;
        Map<String, PyObject> fl = (totalExpected <= 12) ? borrowLocals()
                : new HashMap<>(Math.max(8, (int)(totalExpected / 0.75f) + 1));
        // 1. Seed with closure
        if (fn.closure() != null) fl.putAll(fn.closure());

        // Fast path: pure positional call matching parameter count with no kwargs or varargs
        if (kwNames == null && nPos == nParams && nDefaults == 0 && argsParam == null && kwargsParam == null) {
            for (int i = 0; i < nParams; i++) {
                fl.put(params.get(i), posArgs[i]);
            }
            return fl;
        }

        // Index at which defaults start (right-aligned)
        int defaultStart = nParams - nDefaults;

        // 2. Assign positional args to params
        int nFill = Math.min(nPos, nParams);
        for (int i = 0; i < nFill; i++) fl.put(params.get(i), posArgs[i]);

        // 3. Collect extra positionals into *args tuple (or error)
        if (nPos > nParams) {
            if (argsParam != null) {
                var extra = new ArrayList<PyObject>(nPos - nParams);
                for (int i = nParams; i < nPos; i++) extra.add(posArgs[i]);
                fl.put(argsParam, new PyTuple(extra));
            } else {
                throw PyErrors.error("TypeError",
                    fn.name() + "() takes " + nParams + " positional argument" +
                    (nParams == 1 ? "" : "s") + " but " + nPos + " " +
                    (nPos == 1 ? "was" : "were") + " given");
            }
        } else if (argsParam != null) {
            fl.put(argsParam, PyTuple.EMPTY);  // *args receives empty tuple
        }

        // 4. Assign keyword args by name
        var extraKw = new LinkedHashMap<PyObject, PyObject>();
        if (kwNames != null) {
            for (int i = 0; i < kwNames.length; i++) {
                String kwName = kwNames[i];
                if (params.contains(kwName)) {
                    if (fl.containsKey(kwName)) {
                        throw PyErrors.error("TypeError",
                            fn.name() + "() got multiple values for argument '" + kwName + "'");
                    }
                    fl.put(kwName, kwValues[i]);
                } else if (kwargsParam != null) {
                    extraKw.put(new PyStr(kwName), kwValues[i]);
                } else {
                    throw PyErrors.error("TypeError",
                        fn.name() + "() got an unexpected keyword argument '" + kwName + "'");
                }
            }
        }
        if (kwargsParam != null) {
            fl.put(kwargsParam, new PyDict(extraKw));
        }

        // 5. Fill remaining params with defaults (right-aligned)
        for (int i = nFill; i < nParams; i++) {
            if (!fl.containsKey(params.get(i))) {
                int di = i - defaultStart;
                if (di >= 0 && di < nDefaults) {
                    fl.put(params.get(i), defaults[di]);
                } else {
                    throw PyErrors.error("TypeError",
                        fn.name() + "() missing required positional argument: '" + params.get(i) + "'");
                }
            }
        }

        return fl;
    }

    private String debugStack(FastStack s, int n) {
        if (s == null || s.isEmpty()) return "[]";
        var parts = new java.util.ArrayList<String>();
        int count = Math.min(n, s.size());
        for (int i = 0; i < count; i++) {
            parts.add(String.valueOf(s.get(s.size() - 1 - i)));
        }
        return parts.toString();
    }

    private void bindImportedName(String alias, PyObject value) {
        if (currentLocals != null) {
            currentLocals.put(alias, value);
        } else {
            globals.put(alias, value);
        }
    }

    private void importModule(String spec) {
        // ── from:pkg:item:alias  or  from:pkg:*  ──
        if (spec.startsWith("from:")) {
            String[] parts = spec.split(":", -1);
            String pkg = parts[1];
            String item = parts[2];

            // Built-in modules take precedence before Java class / disk lookup.
            PyModule builtinMod = resolveBuiltinModule(pkg);
            if (builtinMod != null) {
                if ("*".equals(item)) {
                    importStarFromModule(builtinMod);
                    return;
                }
                String alias = parts.length > 3 ? parts[3] : item;
                PyObject attr = builtinMod.attrs().get(item);
                if (attr != null) {
                    bindImportedName(alias, attr);
                    return;
                }
                throw PyErrors.error("ImportError", "cannot import name '" + item + "' from '" + pkg + "'");
            }

            if ("*".equals(item)) {
                if (pkg.contains(".") && isJavaRootPackage(pkg.substring(0, pkg.indexOf('.')))
                        || isJavaRootPackage(pkg)) {
                    try {
                        PyObject resolved = JavaBridge.resolveClassOrPackage(pkg, javaAccessPolicy);
                        if (resolved instanceof PyJavaClass jc) {
                            for (var entry : jc.accessibleStaticMembers().entrySet()) {
                                bindImportedName(entry.getKey(), entry.getValue());
                            }
                            return;
                        }
                    } catch (InterpreterException ie) {
                        if (!"ImportError".equals(ie.typeName())) throw ie;
                    }
                    throw PyErrors.error("ImportError", "'import *' from Java package is not supported");
                }
                PyModule mod = loadDiskModule(pkg);
                importStarFromModule(mod);
                return;
            }

            String alias = parts.length > 3 ? parts[3] : item;
            String fullName = pkg + "." + item;

            if (pkg.contains(".") || isJavaRootPackage(pkg)) {
                String rootPkg = pkg.contains(".") ? pkg.substring(0, pkg.indexOf('.')) : pkg;
                if (isJavaRootPackage(rootPkg)) {
                    if (javaAccessPolicy.canAccessClass(pkg)) {
                        try {
                            PyObject pkgAsClass = JavaBridge.resolveClassOrPackage(pkg, javaAccessPolicy);
                            if (pkgAsClass instanceof PyJavaClass jc) {
                                bindImportedName(alias, jc.getMember(item));
                                return;
                            }
                        } catch (InterpreterException ie) {
                            if (!"ImportError".equals(ie.typeName())) throw ie;
                        }
                    }
                    PyObject resolved = JavaBridge.resolveClassOrPackage(fullName, javaAccessPolicy);
                    if (resolved != null) {
                        bindImportedName(alias, resolved);
                        return;
                    }
                }
            }

            PyModule mod = loadDiskModule(pkg);
            PyObject attr = mod.attrs().get(item);
            if (attr != null) {
                bindImportedName(alias, attr);
                return;
            }
            // Also allow `from pkg import submod` when submod is a child module/package on disk.
            try {
                PyModule subMod = loadDiskModule(fullName);
                bindImportedName(alias, subMod);
                return;
            } catch (InterpreterException ie) {
                if (!"ImportError".equals(ie.typeName())) throw ie;
            }
            if (moduleLoader.isInitializing(pkg)) {
                throw PyErrors.error("ImportError",
                        "cannot import name '" + item + "' from partially initialized module '"
                                + pkg + "' (most likely due to a circular import)");
            }
            throw PyErrors.error("ImportError", "cannot import name '" + item + "' from '" + pkg + "'");
        }

        // ── pkg:alias  or plain pkg ──
        String[] parts = spec.split(":", -1);
        String pkg = parts[0];
        boolean hasAlias = parts.length > 1;
        String alias = hasAlias ? parts[1] : pkg;

        PyModule builtin = resolveBuiltinModule(pkg);
        if (builtin != null) {
            if (!hasAlias && pkg.contains(".")) {
                String topName = pkg.substring(0, pkg.indexOf('.'));
                PyModule top = resolveBuiltinModule(topName);
                bindImportedName(topName, top != null ? top : builtin);
            } else {
                bindImportedName(alias, builtin);
            }
            return;
        }

        String rootPkg = pkg.contains(".") ? pkg.substring(0, pkg.indexOf('.')) : pkg;
        if (isJavaRootPackage(rootPkg)) {
            PyObject javaObj = JavaBridge.resolveClassOrPackage(pkg, javaAccessPolicy);
            if (javaObj != null) {
                bindImportedName(alias, javaObj);
                return;
            }
        }

        PyModule diskMod = loadDiskModule(pkg);
        if (!hasAlias && pkg.contains(".")) {
            // `import a.b.c` binds the top-level package `a` (which already holds `.b.c`)
            PyModule top = moduleLoader.getCached(rootPkg);
            bindImportedName(rootPkg, top != null ? top : diskMod);
        } else {
            bindImportedName(alias, diskMod);
        }
    }

    private void importStarFromModule(PyModule mod) {
        PyObject allObj = mod.attrs().get("__all__");
        if (allObj instanceof PyList list) {
            for (PyObject item : list.items()) {
                if (item instanceof PyStr s) {
                    PyObject val = mod.attrs().get(s.value());
                    if (val == null) {
                        throw PyErrors.error("ImportError", "cannot import name '" + s.value() + "' from '" + mod.name() + "'");
                    }
                    bindImportedName(s.value(), val);
                }
            }
            return;
        }
        if (allObj instanceof PyTuple tuple) {
            for (PyObject item : tuple.items()) {
                if (item instanceof PyStr s) {
                    PyObject val = mod.attrs().get(s.value());
                    if (val == null) {
                        throw PyErrors.error("ImportError", "cannot import name '" + s.value() + "' from '" + mod.name() + "'");
                    }
                    bindImportedName(s.value(), val);
                }
            }
            return;
        }
        for (var entry : mod.attrs().entrySet()) {
            if (!entry.getKey().startsWith("_")) {
                bindImportedName(entry.getKey(), entry.getValue());
            }
        }
    }

    private PyModule loadDiskModule(String pkg) {
        return moduleLoader.load(pkg, hostAccessPolicy, builtinSymbols,
                (module, filePath, content, moduleScope) -> {
                    CompiledCode moduleCode = compile(content);
                    executeIsolated(moduleCode, moduleScope, filePath.toString());
                });
    }

    private PyModule resolveBuiltinModule(String name) {
        PyModule cached = moduleLoader.getCached(name);
        if (cached != null) return cached;
        PyModule mod = switch (name) {
            case "math" -> BuiltinModules.math();
            case "sys"  -> BuiltinModules.sys(moduleLoader.sysPath(), moduleLoader.sysModules(),
                    this::out, this::err);
            case "os"   -> BuiltinModules.os(hostAccessPolicy);
            case "io"   -> BuiltinModules.io(hostAccessPolicy);
            case "json" -> BuiltinModules.json();
            case "re"   -> BuiltinModules.re();
            case "time" -> BuiltinModules.time();
            case "random" -> BuiltinModules.random();
            case "importlib" -> createImportlibModule();
            case "ui" -> BuiltinModules.ui(hostSession, uiToolkit);
            case "control" -> BuiltinModules.control(hostSession, controlRegistry, uiToolkit);
            case "threading" -> BuiltinModules.threading(hostSession, this::spawnVirtualThread);
            case "itertools" -> BuiltinModules.itertools();
            case "queue" -> BuiltinModules.queue(hostSession);
            case "datetime" -> BuiltinModules.datetime();
            case "asyncio" -> BuiltinModules.asyncio();
            case "concurrent" -> BuiltinModules.concurrentPkg(hostSession, this::spawnVirtualThread);
            case "concurrent.futures" -> {
                PyModule pkg = resolveBuiltinModule("concurrent");
                yield pkg != null ? (PyModule) pkg.attrs().get("futures")
                        : BuiltinModules.concurrentFutures(hostSession, this::spawnVirtualThread);
            }
            default     -> null;
        };
        if (mod != null) {
            moduleLoader.registerBuiltin(mod);
        }
        return mod;
    }

    private static boolean isNan(PyObject o) {
        return o instanceof PyFloat f && Double.isNaN(f.value());
    }

    private boolean containsYield(CompiledCode code) {
        return code != null && code.hasYield();
    }

    private static boolean isBuiltinModuleName(String name) {
        return switch (name) {
            case "math", "sys", "os", "io", "json", "re", "time", "random", "importlib",
                 "ui", "control", "threading", "itertools", "queue", "datetime", "asyncio",
                 "concurrent", "concurrent.futures" -> true;
            default -> false;
        };
    }

    private PyModule createImportlibModule() {
        var attrs = new HashMap<String, PyObject>();
        attrs.put("reload", new PyBuiltinFunction("reload", args -> {
            checkArgCount("reload", args, 1);
            if (!(args[0] instanceof PyModule mod)) {
                throw PyErrors.error("TypeError", "reload() argument must be a module");
            }
            String modName = mod.name();
            if (isBuiltinModuleName(modName)) {
                return mod;
            }
            PyModule reloaded = reloadModule(modName);
            mod.attrs().clear();
            mod.attrs().putAll(reloaded.attrs());
            moduleLoader.sysModules().put(new PyStr(modName), mod);
            return mod;
        }));
        return new PyModule("importlib", attrs);
    }

    private static boolean isJavaRootPackage(String name) {
        return name.equals("java") || name.equals("javax") || name.equals("org") ||
               name.equals("com") || name.equals("net") || name.equals("io") ||
               name.equals("sun") || name.equals("jdk");
    }

    // ─── Bytecode public API ──────────────────────────────────────────────
    public int[] compileToBytecode(String src) { return compile(src).bytecode(); }
    public CompiledCode compileToCode(String src) { return compile(src); }

    private static final class ThrowingErrorListener extends BaseErrorListener {
        static final ThrowingErrorListener INSTANCE = new ThrowingErrorListener();
        @Override public void syntaxError(Recognizer<?,?> r, Object s, int l, int c, String m, RecognitionException e) {
            throw PyErrors.error("SyntaxError", "at " + l + ":" + c + " – " + m);
        }
    }
}
