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
import org.example.jython3.runtime.BigInt;
import org.example.jython3.runtime.ExceptHandler;
import org.example.jython3.runtime.Frame;
import org.example.jython3.runtime.ExceptionTypeRegistry;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyBoundMethod;
import org.example.jython3.runtime.PyBuiltinFunction;
import org.example.jython3.runtime.PyCaller;
import org.example.jython3.runtime.PyClass;
import org.example.jython3.runtime.PyCompiledCodeWrapper;
import org.example.jython3.runtime.PyDict;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyException;
import org.example.jython3.runtime.PyFloat;
import org.example.jython3.runtime.PyFunction;
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
import org.example.jython3.runtime.PySet;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.runtime.PySuper;
import org.example.jython3.runtime.PyTuple;
import org.example.jython3.runtime.SystemExit;
import org.example.jython3.security.AllowlistPolicy;
import org.example.jython3.security.DenyAllPolicy;
import org.example.jython3.security.ExecutionContext;
import org.example.jython3.security.ExecutionLimits;
import org.example.jython3.security.HostAccessPolicy;
import org.example.jython3.security.JavaAccessPolicy;
import org.example.jython3.stdlib.BuiltinFunctions;
import org.example.jython3.stdlib.BuiltinModules;

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

public class Jython3Interpreter {

    // ─── Module Cache & Loading States ───────────────────────────────────
    private enum ModuleState { INITIALIZING, LOADED }
    private record ModuleEntry(PyModule module, ModuleState state) {}
    private final Map<String, ModuleEntry> moduleCache = new ConcurrentHashMap<>();
    // ─── Bytecode Cache ──────────────────────────────────────────────────
    private static final int MAX_CACHE_SIZE = 2048;
    private final Map<String, CompiledCode> bytecodeCache = new ConcurrentHashMap<>();
    /** Gate for every Java class, constructor, method, and field access. */
    private final JavaAccessPolicy javaAccessPolicy;
    /** Gate for host file system, environment, and disk module execution. */
    private final HostAccessPolicy hostAccessPolicy;
    /** Built-in exception classes owned by this interpreter. */
    private final ExceptionTypeRegistry exceptionTypes;
    /** Execution limits (instruction count, timeout, pre-allocation thresholds). */
    private final ExecutionLimits executionLimits;
    /** Active execution context tracking consumption across frames. */
    private ExecutionContext currentContext;

    /** Built-in functions and standard types template for module initialization. */
    private final Map<String, PyObject> builtinSymbols = new HashMap<>();

    public void clearCache() {
        bytecodeCache.clear();
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

    // ─── Runtime state ────────────────────────────────────────────────────
    private final Map<String, PyObject> globals = new HashMap<>();
    private Map<String, PyObject> currentGlobals = globals;
    private Deque<PyObject> stack;
    private Map<String, PyObject> currentLocals;
    /** Set of variable names declared `global` in the current function frame. */
    private java.util.Set<String> globalCells;
    private final Deque<Frame> callStack = new ArrayDeque<>();
    /** Per-frame exception handler stack — saved/restored on function call boundaries. */
    private Deque<ExceptHandler> exceptStack = new ArrayDeque<>();
    /**
     * Python exceptions currently being handled, innermost last. Bare
     * {@code raise} uses the top; entering a handler pushes and leaving it
     * pops. It is per frame, so a callee never inherits the caller's handler.
     */
    private Deque<PyObject> activeExceptions = new ArrayDeque<>();
    /** Non-null while a generator function is being drained; collects yielded values. */
    private List<PyObject> yieldCollector = null;
    /** Source location of the instruction being executed, for tracebacks. */
    private String currentFile = "<stdin>";
    private String currentFunc = "<module>";
    private int currentLine = 0;
    /** Source text of the current script, used to quote lines in a traceback. */
    private String currentSource = "";

    // ─── Factory Methods ──────────────────────────────────────────────────

    /**
     * Creates an interpreter for trusted scripts (e.g. application internal automation).
     * Grants the standard Java allowlist and permissive host OS access.
     */
    public static Jython3Interpreter trusted() {
        return new Jython3Interpreter(AllowlistPolicy.trusted(), HostAccessPolicy.PERMISSIVE, ExecutionLimits.TRUSTED);
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
        this.javaAccessPolicy = Objects.requireNonNull(javaAccessPolicy, "javaAccessPolicy");
        this.hostAccessPolicy = Objects.requireNonNull(hostAccessPolicy, "hostAccessPolicy");
        this.executionLimits = Objects.requireNonNull(executionLimits, "executionLimits");
        this.exceptionTypes = Objects.requireNonNull(exceptionTypes, "exceptionTypes");
        PyCaller.CURRENT.set(new PyCaller() {
            @Override public PyObject call(PyObject callable, PyObject[] args) {
                return callPyCallable(callable, args);
            }

            @Override public List<PyObject> collectYields(PyFunction fn, PyObject[] args) {
                return runGenerator(fn, args);
            }

            @Override public List<PyObject> collectYieldsWithLocals(PyFunction fn,
                    java.util.Map<String, PyObject> locals) {
                return runGeneratorWithLocals(fn, locals);
            }
        });
        registerBuiltins();
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

    /** The exception classes owned by this interpreter. */
    public ExceptionTypeRegistry exceptionTypes() {
        return exceptionTypes;
    }

    /** Install built-in functions into the global namespace. */
    private void registerBuiltins() {
        BuiltinFunctions.register(builtinSymbols::put, this::callPyCallable, javaAccessPolicy);
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
            System.err.println(formatTraceback(e, "<stdin>", source));
        }
    }

    /**
     * Renders a Python-style traceback for {@code e}. Frames are stored
     * innermost-first while unwinding and are printed outermost-first, matching
     * CPython's "most recent call last" ordering.
     */
    public String formatTraceback(InterpreterException e, String filename, String source) {
        var sb = new StringBuilder("Traceback (most recent call last):\n");
        var frames = e.traceback();
        if (frames.isEmpty()) {
            int line = e.line() > 0 ? e.line() : 1;
            sb.append("  File \"").append(filename).append("\", line ").append(line)
              .append(", in ").append(e.function() != null ? e.function() : "<module>").append('\n');
            appendSnippet(sb, source, line);
        } else {
            for (int i = frames.size() - 1; i >= 0; i--) {
                var t = frames.get(i);
                String file = t.file() != null ? t.file() : filename;
                String func = t.function() != null ? t.function() : "<module>";
                sb.append("  File \"").append(file).append("\", line ").append(t.line())
                  .append(", in ").append(func).append('\n');
                appendSnippet(sb, source, t.line());
            }
        }
        sb.append(e.getMessage());
        return sb.toString();
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
        if (ie.value() instanceof PyException pe) pe.attachTraceback(ie.traceback());
    }

    /** Drops active exceptions pushed by handlers that the unwind abandoned. */
    private void truncateActiveExceptions(int depth) {
        while (activeExceptions.size() > depth) {
            activeExceptions.removeLast();
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

        if (activeExceptions.peekLast() instanceof PyException active) {
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
        if (activeExceptions.peekLast() instanceof PyException active) {
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
    public void eval(String source, String filename) {
        ExceptionTypeRegistry previousTypes = ExceptionTypeRegistry.install(exceptionTypes);
        try {
            stack = new ArrayDeque<>();
            currentGlobals = globals;
            currentLocals = globals;
            exceptStack = new ArrayDeque<>();
            activeExceptions = new ArrayDeque<>();
            callStack.clear();
            yieldCollector = null;
            currentContext = new ExecutionContext(executionLimits);
            currentFile = filename;
            currentFunc = "<module>";
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
            ExceptionTypeRegistry.restore(previousTypes);
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
        var savedGlobals = currentGlobals;
        var savedExcept = exceptStack;
        var savedActive = activeExceptions;
        var savedCallStack = new ArrayDeque<>(callStack);
        var savedCollector = yieldCollector;
        var savedFunc = currentFunc;
        var savedFile = currentFile;
        var savedLine = currentLine;
        try {
            stack = new ArrayDeque<>();
            currentGlobals = executionLocals;
            currentLocals = executionLocals;
            exceptStack = new ArrayDeque<>();
            activeExceptions = new ArrayDeque<>();
            callStack.clear();
            yieldCollector = null;
            currentFunc = "<module>";
            currentFile = filename != null ? filename : "<module>";
            currentLine = 0;
            run(code);
        } finally {
            stack = savedStack;
            currentLocals = savedLocals;
            currentGlobals = savedGlobals;
            exceptStack = savedExcept;
            activeExceptions = savedActive;
            callStack.clear();
            callStack.addAll(savedCallStack);
            yieldCollector = savedCollector;
            currentFunc = savedFunc;
            currentFile = savedFile;
            currentLine = savedLine;
        }
    }

    /**
     * Runs a generator function to completion and returns every yielded value.
     * The function executes on an isolated call stack so the caller's frames are
     * not disturbed by the generator's return instruction.
     */
    private List<PyObject> runGenerator(PyFunction fn, PyObject[] args) {
        return runGeneratorWithLocals(fn, bindArgs(fn, args, null, null));
    }

    private List<PyObject> runGeneratorWithLocals(PyFunction fn, Map<String, PyObject> initialLocals) {
        var collected = new ArrayList<PyObject>();
        var savedFrames = new ArrayDeque<>(callStack);
        var savedStack = stack;
        var savedLocals = currentLocals;
        var savedExcept = exceptStack;
        var savedActive = activeExceptions;
        var savedCollector = yieldCollector;
        var savedFunc = currentFunc;
        var savedLine = currentLine;
        try {
            callStack.clear();
            stack = new ArrayDeque<>();
            currentLocals = new HashMap<>(initialLocals);
            exceptStack = new ArrayDeque<>();
            activeExceptions = new ArrayDeque<>();
            currentFunc = fn.name();
            yieldCollector = collected;
            run(fn.code());
        } finally {
            yieldCollector = savedCollector;
            callStack.clear();
            callStack.addAll(savedFrames);
            stack = savedStack;
            currentLocals = savedLocals;
            exceptStack = savedExcept;
            activeExceptions = savedActive;
            currentFunc = savedFunc;
            currentLine = savedLine;
        }
        return collected;
    }

    public PyObject getGlobal(String name) {
        return globals.get(name);
    }

    public void setGlobal(String name, PyObject value) {
        globals.put(name, sanitizeForPolicy(value, javaAccessPolicy));
    }

    /**
     * Ensures any injected host object or wrapper adheres to this interpreter's policy.
     * Recursively verifies collections (PyList, PyDict), functions, modules, classes,
     * and instances, retargets wrappers, and handles circular references safely via IdentityHashMap.
     */
    private static PyObject sanitizeForPolicy(PyObject val, JavaAccessPolicy policy) {
        return sanitizeForPolicy(val, policy, new java.util.IdentityHashMap<>());
    }

    private static PyObject sanitizeForPolicy(PyObject val, JavaAccessPolicy policy,
                                              java.util.Map<PyObject, PyObject> visited) {
        if (val == null || val instanceof PyNull) return val;
        PyObject seen = visited.get(val);
        if (seen != null) return seen;

        if (val instanceof PyJavaClass jc) {
            if (!policy.canAccessClass(jc.clazz().getName())) {
                throw JavaBridge.denied(jc.clazz().getName());
            }
            PyJavaClass res = new PyJavaClass(jc.clazz(), policy);
            visited.put(val, res);
            return res;
        }
        if (val instanceof PyJavaObject jo) {
            if (!policy.canAccessClass(jo.object().getClass().getName())) {
                throw JavaBridge.denied(jo.object().getClass().getName());
            }
            PyJavaObject res = new PyJavaObject(jo.object(), policy);
            visited.put(val, res);
            return res;
        }
        if (val instanceof PyList list) {
            var sanitizedItems = new ArrayList<PyObject>();
            var res = new PyList(sanitizedItems);
            visited.put(val, res);
            for (PyObject item : list.items()) {
                sanitizedItems.add(sanitizeForPolicy(item, policy, visited));
            }
            return res;
        }
        if (val instanceof PyDict dict) {
            var sanitizedMap = new LinkedHashMap<PyObject, PyObject>();
            var res = new PyDict(sanitizedMap);
            visited.put(val, res);
            for (var entry : dict.map().entrySet()) {
                sanitizedMap.put(sanitizeForPolicy(entry.getKey(), policy, visited),
                                 sanitizeForPolicy(entry.getValue(), policy, visited));
            }
            return res;
        }
        if (val instanceof PyModule mod) {
            var sanitizedAttrs = new HashMap<String, PyObject>();
            var res = new PyModule(mod.name(), sanitizedAttrs);
            visited.put(val, res);
            for (var entry : mod.attrs().entrySet()) {
                sanitizedAttrs.put(entry.getKey(), sanitizeForPolicy(entry.getValue(), policy, visited));
            }
            return res;
        }
        if (val instanceof PyFunction fn) {
            var sanitizedClosure = new HashMap<String, PyObject>();
            var res = new PyFunction(fn.name(), fn.params(), fn.code(), sanitizedClosure);
            visited.put(val, res);
            for (var entry : fn.closure().entrySet()) {
                sanitizedClosure.put(entry.getKey(), sanitizeForPolicy(entry.getValue(), policy, visited));
            }
            return res;
        }
        if (val instanceof PyClass cls) {
            var sanitizedMembers = new HashMap<String, PyObject>();
            var sanitizedBases = new ArrayList<PyClass>();
            PyClass sanitizedBase = cls.baseClass() != null
                    ? (PyClass) sanitizeForPolicy(cls.baseClass(), policy, visited)
                    : null;
            var res = new PyClass(cls.name(), sanitizedBase, sanitizedMembers, sanitizedBases);
            visited.put(val, res);
            for (PyClass b : cls.getBases()) {
                sanitizedBases.add((PyClass) sanitizeForPolicy(b, policy, visited));
            }
            for (var entry : cls.members().entrySet()) {
                sanitizedMembers.put(entry.getKey(), sanitizeForPolicy(entry.getValue(), policy, visited));
            }
            return res;
        }
        if (val instanceof PyInstance inst) {
            PyClass sanitizedCls = (PyClass) sanitizeForPolicy(inst.pyClass(), policy, visited);
            var res = new PyInstance(sanitizedCls);
            visited.put(val, res);
            for (var entry : inst.fields().entrySet()) {
                res.setField(entry.getKey(), sanitizeForPolicy(entry.getValue(), policy, visited));
            }
            return res;
        }
        return val;
    }

    // ─── ANTLR compilation with Python Indentation Stream ─────────────────

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
        new CompileVisitor(b).visit(parser.stat());
        b.emit(OpCode.HALT);
        CompiledCode result = b.build();

        if (bytecodeCache.size() < MAX_CACHE_SIZE) {
            bytecodeCache.put(src, result);
        }
        return result;
    }

    // ─── Stack‑machine execution ──────────────────────────────────────────

    private void run(CompiledCode code) throws InterpreterException {
        int[] bc = code.bytecode();
        Object[] pool = code.constPool();
        int pc = 0;

        outer: while (pc < bc.length) {
            if (currentContext != null) {
                currentContext.stepInstruction();
            }
            int instr = bc[pc];
            OpCode op = OP_VALUES[instr >>> 16];
            int ai = instr & 0xFFFF;
            int line = code.lineAt(pc);
            if (line > 0) currentLine = line;

            try { switch (op) {
                case LOAD_CONST -> {
                    if (ai == NO_ARG) { stack.push(new PyNull()); break; }
                    Object v = pool[ai];
                    if (v == null) stack.push(new PyNull());
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
                            break; // nonlocal is handled via closure; skip marker
                        }
                        stack.push(new PyStr(s));
                    }
                    else if (v instanceof Integer i) stack.push(new PyInt(i));
                    else if (v instanceof Double d) stack.push(new PyFloat(d));
                    else if (v instanceof Boolean b) stack.push(PyBool.of(b));
                    else if (v instanceof org.example.jython3.runtime.BigInt bi) stack.push(bi);
                    else if (v instanceof PyFunction f) stack.push(f);
                    else if (v instanceof CompiledCode cc) stack.push(new PyCompiledCodeWrapper(cc));
                    else throw PyErrors.error("RuntimeError", "unknown constant: " + v);
                }
                case LOAD_FAST -> {
                    String n = (String) pool[ai];
                    PyObject v = null;
                    // If declared global, read from global scope directly
                    if (globalCells != null && globalCells.contains(n)) {
                        v = globals.get(n);
                        if (v == null) v = currentGlobals.get(n);
                    } else if (currentLocals != null) {
                        v = currentLocals.get(n);
                    }
                    if (v == null) v = currentGlobals.get(n);
                    if (v == null) throw PyErrors.error("NameError", "name '" + n + "' is not defined");
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
                    // If this name was declared 'global', write to the global scope
                    if (globalCells != null && globalCells.contains(storeName)) {
                        globals.put(storeName, storeVal);
                        currentGlobals.put(storeName, storeVal);
                    } else if (currentLocals != null) {
                        currentLocals.put(storeName, storeVal);
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
                        cls.members().put(attr, val);
                    } else if (obj instanceof PyJavaObject jo) {
                        jo.setMember(attr, val);
                    } else {
                        throw PyErrors.error("AttributeError", "cannot set attribute on '" + typeName(obj) + "'");
                    }
                }
                case DELETE_FAST -> {
                    String n = (String) pool[ai];
                    if (currentLocals.remove(n) == null && globals.remove(n) == null) {
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
                    } else {
                        throw PyErrors.error("TypeError", "'" + typeName(container) + "' object doesn't support item deletion");
                    }
                }

                // ── Arithmetic ──
                case ADD -> binaryAdd();
                case SUBTRACT -> binarySubtract();
                case MULTIPLY -> binaryMultiply();
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
                        throw PyErrors.error("TypeError", "unsupported operand types for /");
                    }
                }
                case FLOOR_DIVIDE -> {
                    var r = unwrapBool(stack.pop()); var l = unwrapBool(stack.pop());
                    if (l instanceof PyInt li && r instanceof PyInt ri) {
                        if (ri.value() == 0) throw PyErrors.error("ZeroDivisionError", "integer division or modulo by zero");
                        stack.push(new PyInt(Math.floorDiv(li.value(), ri.value())));
                    } else if ((l instanceof PyInt || l instanceof BigInt) && (r instanceof PyInt || r instanceof BigInt)) {
                        java.math.BigInteger ba = l instanceof PyInt pi ? java.math.BigInteger.valueOf(pi.value()) : ((BigInt) l).value();
                        java.math.BigInteger bb = r instanceof PyInt pi ? java.math.BigInteger.valueOf(pi.value()) : ((BigInt) r).value();
                        java.math.BigInteger q = PyOps.floorDiv(ba, bb);
                        if (q.compareTo(java.math.BigInteger.valueOf(Integer.MAX_VALUE)) <= 0 &&
                            q.compareTo(java.math.BigInteger.valueOf(Integer.MIN_VALUE)) >= 0) {
                            stack.push(new PyInt(q.intValue()));
                        } else {
                            stack.push(new BigInt(q));
                        }
                    } else if (isNumeric(l) && isNumeric(r)) {
                        double rv = toDouble(r);
                        if (rv == 0.0) throw PyErrors.error("ZeroDivisionError", "float floor division by zero");
                        stack.push(new PyFloat(Math.floor(toDouble(l) / rv)));
                    } else {
                        throw PyErrors.error("TypeError", "unsupported operand types for //");
                    }
                }
                case MODULO -> {
                    var r = stack.pop(); var l = stack.pop();
                    if (l instanceof PyStr fmt) {
                        stack.push(new PyStr(pyStringFormat(fmt.value(), r)));
                    } else {
                        l = unwrapBool(l);
                        r = unwrapBool(r);
                        if (l instanceof PyInt li && r instanceof PyInt ri) {
                            if (ri.value() == 0) throw PyErrors.error("ZeroDivisionError", "integer modulo by zero");
                            stack.push(new PyInt(Math.floorMod(li.value(), ri.value())));
                        } else if ((l instanceof PyInt || l instanceof BigInt) && (r instanceof PyInt || r instanceof BigInt)) {
                            java.math.BigInteger ba = l instanceof PyInt pi ? java.math.BigInteger.valueOf(pi.value()) : ((BigInt) l).value();
                            java.math.BigInteger bb = r instanceof PyInt pi ? java.math.BigInteger.valueOf(pi.value()) : ((BigInt) r).value();
                            java.math.BigInteger rem = PyOps.floorMod(ba, bb);
                            if (rem.compareTo(java.math.BigInteger.valueOf(Integer.MAX_VALUE)) <= 0 &&
                                rem.compareTo(java.math.BigInteger.valueOf(Integer.MIN_VALUE)) >= 0) {
                                stack.push(new PyInt(rem.intValue()));
                            } else {
                                stack.push(new BigInt(rem));
                            }
                        } else if (isNumeric(l) && isNumeric(r)) {
                            double rv = toDouble(r), lv = toDouble(l);
                            if (rv == 0.0) throw PyErrors.error("ZeroDivisionError", "float modulo by zero");
                            stack.push(new PyFloat(lv - Math.floor(lv / rv) * rv));
                        } else {
                            throw PyErrors.error("TypeError", "unsupported operand types for %");
                        }
                    }
                }
                case POWER -> {
                    var r = unwrapBool(stack.pop()); var l = unwrapBool(stack.pop());
                    if (isNumeric(l) && isNumeric(r)) {
                        // Integer exact exponentiation
                        if ((l instanceof PyInt || l instanceof BigInt) && (r instanceof PyInt || r instanceof BigInt)) {
                            java.math.BigInteger base = l instanceof PyInt pi ? java.math.BigInteger.valueOf(pi.value()) : ((BigInt) l).value();
                            java.math.BigInteger exp = r instanceof PyInt pi ? java.math.BigInteger.valueOf(pi.value()) : ((BigInt) r).value();
                            if (exp.compareTo(java.math.BigInteger.valueOf(executionLimits.maxExponent())) > 0) {
                                throw PyErrors.error("ValueError", "Exponent exceeds maximum allowed limit: " + executionLimits.maxExponent());
                            }
                            if (exp.compareTo(java.math.BigInteger.ZERO) < 0) {
                                // Negative exponent produces float: 2 ** -3 -> 0.125
                                double baseD = toDouble(l instanceof PyInt ? l : new BigInt(base));
                                double expD = exp.doubleValue();
                                double res = Math.pow(baseD, expD);
                                if (Double.isInfinite(res) && !Double.isInfinite(baseD)) {
                                    throw PyErrors.error("OverflowError", "(34, 'Numerical result out of range')");
                                }
                                stack.push(new PyFloat(res));
                            } else {
                                int expInt = exp.intValueExact();
                                java.math.BigInteger res = base.pow(expInt);
                                if (res.compareTo(java.math.BigInteger.valueOf(Integer.MAX_VALUE)) <= 0 &&
                                    res.compareTo(java.math.BigInteger.valueOf(Integer.MIN_VALUE)) >= 0) {
                                    stack.push(new PyInt(res.intValue()));
                                } else {
                                    stack.push(new BigInt(res));
                                }
                            }
                        } else {
                            double exp = toDouble(r);
                            if (exp > executionLimits.maxExponent()) {
                                throw PyErrors.error("ValueError", "Exponent exceeds maximum allowed limit: " + executionLimits.maxExponent());
                            }
                            double base = toDouble(l);
                            double res = Math.pow(base, exp);
                            if (Double.isInfinite(res) && !Double.isInfinite(base)) {
                                throw PyErrors.error("OverflowError", "(34, 'Numerical result out of range')");
                            }
                            stack.push(new PyFloat(res));
                        }
                    } else {
                        throw PyErrors.error("TypeError", "unsupported operand types for **");
                    }
                }
                case UNARY_MINUS -> {
                    var v = unwrapBool(stack.pop());
                    if (v instanceof PyInt i) {
                        long negated = -(long) i.value();
                        if (negated > Integer.MAX_VALUE || negated < Integer.MIN_VALUE) {
                            stack.push(new BigInt(java.math.BigInteger.valueOf(negated)));
                        } else {
                            stack.push(new PyInt((int) negated));
                        }
                    } else if (v instanceof BigInt bi) {
                        stack.push(new BigInt(bi.value().negate()));
                    } else if (v instanceof PyFloat f) {
                        stack.push(new PyFloat(-f.value()));
                    } else {
                        throw PyErrors.error("TypeError", "bad operand type for unary -: '" + typeName(v) + "'");
                    }
                }

                // ── Comparison ──
                case COMPARE_EQ     -> { var r = stack.pop(); var l = stack.pop(); stack.push(PyBool.of(pyEquals(l, r))); }
                case COMPARE_NEQ    -> { var r = stack.pop(); var l = stack.pop(); stack.push(PyBool.of(!pyEquals(l, r))); }
                case COMPARE_LT     -> {
                    var r = stack.pop(); var l = stack.pop();
                    if (isNan(l) || isNan(r)) stack.push(PyBool.FALSE);
                    else stack.push(PyBool.of(compareObjects(l, r) < 0));
                }
                case COMPARE_GT     -> {
                    var r = stack.pop(); var l = stack.pop();
                    if (isNan(l) || isNan(r)) stack.push(PyBool.FALSE);
                    else stack.push(PyBool.of(compareObjects(l, r) > 0));
                }
                case COMPARE_LTE    -> {
                    var r = stack.pop(); var l = stack.pop();
                    if (isNan(l) || isNan(r)) stack.push(PyBool.FALSE);
                    else stack.push(PyBool.of(compareObjects(l, r) <= 0));
                }
                case COMPARE_GTE    -> {
                    var r = stack.pop(); var l = stack.pop();
                    if (isNan(l) || isNan(r)) stack.push(PyBool.FALSE);
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
                case PRINT -> System.out.println(pyPrintStr(stack.pop()));
                case PRINT_MULTI -> {
                    PyList list = (PyList) stack.pop();
                    var sb = new StringBuilder();
                    for (int i = 0; i < list.items().size(); i++) {
                        if (i > 0) sb.append(' ');
                        sb.append(pyPrintStr(list.items().get(i)));
                    }
                    System.out.println(sb);
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
                            proto.code(), proto.closure());
                    stack.push(enriched);
                    // Note: the following MAKE_FUNCTION instruction will capture
                    // closure and store the function — we fall through to it.
                }
                case MAKE_FUNCTION -> {
                    String funcName = (String) pool[ai];
                    PyObject fnObj = stack.pop();
                    if (fnObj instanceof PyFunction fn) {
                        // Capture all current locals (closure) except the function name itself
                        if (currentLocals != null) {
                            for (Map.Entry<String, PyObject> e : currentLocals.entrySet()) {
                                if (!e.getKey().equals(funcName)) {
                                    fn.closure().putIfAbsent(e.getKey(), e.getValue());
                                }
                            }
                        } else {
                            for (Map.Entry<String, PyObject> e : globals.entrySet()) {
                                fn.closure().putIfAbsent(e.getKey(), e.getValue());
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
                        }
                    } else if (basesObj instanceof PyClass bc2) {
                        bases.add(bc2);
                    } else if (basesObj == null || basesObj instanceof PyNull) {
                        // no bases
                    }

                    // Execute class suite in an isolated scope to collect members
                    var classLocals = new HashMap<String, PyObject>();
                    var savedLocals = currentLocals;
                    var savedStack = stack;
                    currentLocals = classLocals;
                    stack = new ArrayDeque<>();
                    run(codeWrap.code());
                    currentLocals = savedLocals;
                    stack = savedStack;

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
                        var callArgs = new PyObject[nPos + 1];
                        callArgs[0] = bm.instance();
                        System.arraycopy(posArgs, 0, callArgs, 1, nPos);
                        stack.push(callPyCallableKw(bm.fn(), callArgs, kwNames, kwValues));
                    } else if (callable instanceof PyFunction fn && containsYield(fn.code())) {
                        var genLocals = bindArgs(fn, posArgs, kwNames, kwValues);
                        stack.push(new PyGenerator(fn, genLocals));
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
                            }
                            stack.push(inst);
                        }
                    } else if (callable instanceof PyFunction fn) {
                        // Regular function call with keyword args — uses inline call path
                        if (callStack.size() >= MAX_CALL_DEPTH) {
                            throw PyErrors.error("RecursionError", "maximum recursion depth exceeded");
                        }
                        callStack.push(new Frame(code, pc + 1, currentLocals, stack, exceptStack,
                                activeExceptions, currentFunc, currentFile, currentLine, currentGlobals, globalCells));
                        globalCells = null; // new frame starts with no global declarations
                        currentLocals = bindArgs(fn, posArgs, kwNames, kwValues);
                        stack = new ArrayDeque<>();
                        exceptStack = new ArrayDeque<>();
                        activeExceptions = new ArrayDeque<>();
                        currentFunc = fn.name();
                        code = fn.code();
                        bc = code.bytecode();
                        pool = code.constPool();
                        pc = 0;
                        continue outer;
                    } else {
                        stack.push(callPyCallableKw(callable, posArgs, kwNames, kwValues));
                    }
                }
                case CALL_FUNCTION -> {
                    int argc = ai;
                    var args = new PyObject[argc];
                    for (int i = argc - 1; i >= 0; i--) args[i] = stack.pop();
                    PyObject callable = stack.pop();

                    if (callable instanceof PyBoundMethod bm) {
                        var callArgs = new PyObject[argc + 1];
                        callArgs[0] = bm.instance();
                        System.arraycopy(args, 0, callArgs, 1, argc);
                        stack.push(callPyCallable(bm.fn(), callArgs));
                    } else if (callable instanceof PyFunction fn2 && containsYield(fn2.code())) {
                        var genLocals = bindArgs(fn2, args, null, null);
                        stack.push(new PyGenerator(fn2, genLocals));
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
                            }
                            stack.push(inst);
                        }
                    } else if (callable instanceof PyFunction fn) {
                        if (containsYield(fn.code())) {
                            var genLocals = bindArgs(fn, args, null, null);
                            stack.push(new PyGenerator(fn, genLocals));
                        } else {
                            if (callStack.size() >= MAX_CALL_DEPTH) {
                                throw PyErrors.error("RecursionError", "maximum recursion depth exceeded");
                            }
                            callStack.push(new Frame(code, pc + 1, currentLocals, stack, exceptStack,
                                    activeExceptions, currentFunc, currentFile, currentLine, currentGlobals, globalCells));
                            globalCells = null; // new frame starts with no global declarations
                            currentLocals = bindArgs(fn, args, null, null);
                            stack = new ArrayDeque<>();
                            exceptStack = new ArrayDeque<>();
                            activeExceptions = new ArrayDeque<>();
                            currentFunc = fn.name();
                            code = fn.code();
                            bc = code.bytecode();
                            pool = code.constPool();
                            pc = 0;
                            continue outer;
                        }
                    } else if (callable instanceof PyBuiltinFunction bf) {
                        stack.push(bf.fn().apply(args));
                    } else if (callable instanceof PyJavaClass jc) {
                        stack.push(jc.newInstance(args));
                    } else if (callable instanceof PyJavaMethod jm) {
                        stack.push(jm.invoke(args));
                    } else {
                        throw PyErrors.error("TypeError", "'" + callable + "' is not callable");
                    }
                }
                case YIELD_VALUE -> {
                    PyObject rv = stack.pop();
                    if (yieldCollector != null) {
                        // Draining a generator: record the value and keep executing.
                        yieldCollector.add(rv);
                    } else if (callStack.isEmpty()) {
                        stack.push(rv);
                        return;
                    } else {
                        // For generator-like behavior in simple cases, treat yield similar to return value
                        Frame cf = callStack.pop();
                        code = cf.code();
                        bc = code.bytecode();
                        pool = code.constPool();
                        pc = cf.returnPc();
                        currentLocals = cf.locals();
                        stack = cf.stack();
                        exceptStack = cf.exceptHandlers();
                        activeExceptions = cf.activeExceptions();
                        currentFunc = cf.funcName();
                        currentFile = cf.file();
                        currentLine = cf.line();
                        stack.push(rv);
                        continue outer;
                    }
                }
                case RETURN_VALUE -> {
                    PyObject rv = stack.pop();
                    if (callStack.isEmpty()) { stack.push(rv); return; }
                    Frame cf = callStack.pop();
                    code = cf.code();
                    bc = code.bytecode();
                    pool = code.constPool();
                    pc = cf.returnPc();
                    currentLocals = cf.locals();
                    if (cf.globals() != null) currentGlobals = cf.globals();
                    globalCells = cf.globalCells(); // restore caller's global declarations
                    stack = cf.stack();
                    exceptStack = cf.exceptHandlers();
                    activeExceptions = cf.activeExceptions();
                    stack.push(rv);
                    continue outer;
                }

                // ── Stack ──
                case POP_TOP -> stack.pop();
                case DUP_TOP -> stack.push(stack.peek());

                // ── Data structures ──
                case BUILD_LIST -> {
                    int n = ai;
                    var items = new ArrayList<PyObject>(n);
                    for (int i = 0; i < n; i++) items.addFirst(stack.pop());
                    stack.push(new PyList(items));
                }
                case BUILD_TUPLE -> {
                    int n = ai;
                    var items = new ArrayList<PyObject>(n);
                    for (int i = 0; i < n; i++) items.addFirst(stack.pop());
                    stack.push(new PyTuple(items));
                }
                case BUILD_DICT -> {
                    int n = ai;
                    var map = new LinkedHashMap<PyObject, PyObject>();
                    var pairs = new PyObject[n * 2];
                    for (int i = n * 2 - 1; i >= 0; i--) pairs[i] = stack.pop();
                    for (int i = 0; i < n * 2; i += 2) map.put(pairs[i], pairs[i + 1]);
                    stack.push(new PyDict(map));
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
                        int[] codePoints = s.codePoints();
                        var indices = slice.computeIndices(codePoints.length);
                        if (indices.length() > executionLimits.maxStringLength()) {
                            throw PyErrors.error("OverflowError", "Slice result exceeds maximum string length: " + executionLimits.maxStringLength());
                        }
                        var sb = new StringBuilder();
                        for (int i = indices.start(), count = 0; count < indices.length(); i += indices.step(), count++) {
                            sb.appendCodePoint(codePoints[i]);
                        }
                        stack.push(new PyStr(sb.toString()));
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
                    else if (o instanceof PyList list) stack.push(new PyIterator(list.items(), 0));
                    else if (o instanceof PyTuple tuple) stack.push(new PyIterator(tuple.items(), 0));
                    else if (o instanceof PyStr s) {
                        stack.push(s.codePointIterator());
                    } else if (o instanceof PyDict d) {
                        stack.push(new PyIterator(new ArrayList<>(d.map().keySet()), 0));
                    } else if (o instanceof PyGenerator gen) {
                        stack.push(PyIterator.fromGenerator(gen));
                    } else if (o instanceof PyJavaObject jo && jo.object() instanceof Iterable<?> iterable) {
                        stack.push(PyIterator.fromJavaIterable(iterable, jo.policy(), currentContext));
                    } else {
                        throw PyErrors.error("TypeError", "'" + typeName(o) + "' is not iterable");
                    }
                }
                case FOR_ITER -> {
                    PyObject top = stack.peek();
                    if (top instanceof PyIterator iter) {
                        if (iter.hasNext()) stack.push(iter.next());
                        else { stack.pop(); pc = ai; continue; }
                    } else {
                        throw PyErrors.error("TypeError", "expected iterator");
                    }
                }

                // ── Attributes ──
                case LOAD_ATTR -> {
                    String attr = (String) pool[ai];
                    PyObject obj = stack.pop();
                    if (obj instanceof PyInstance inst) {
                        stack.push(inst.getField(attr));
                    } else if (obj instanceof PyClass cls) {
                        PyObject val = cls.lookupMember(attr);
                        if (val == null) throw PyErrors.error("AttributeError", "type object '" + cls.name() + "' has no attribute '" + attr + "'");
                        stack.push(val);
                    } else if (obj instanceof PyModule mod) {
                        PyObject val = mod.attrs().get(attr);
                        if (val == null) throw PyErrors.error("AttributeError", "module '" + mod.name() + "' has no attribute '" + attr + "'");
                        stack.push(val);
                    } else if (obj instanceof PyDict dict) {
                        stack.push(dictMethod(dict, attr));
                    } else if (obj instanceof PyList list) {
                        stack.push(listMethod(list, attr));
                    } else if (obj instanceof PySet set) {
                        stack.push(setMethod(set, attr));
                    } else if (obj instanceof PyStr s) {
                        stack.push(strMethod(s, attr));
                    } else if (obj instanceof PyJavaObject jo) {
                        stack.push(jo.getMember(attr));
                    } else if (obj instanceof PyJavaClass jc) {
                        stack.push(jc.getMember(attr));
                    } else if (obj instanceof PyJavaPackage jp) {
                        stack.push(jp.getMember(attr));
                    } else if (obj instanceof PySuper sup) {
                        stack.push(sup.getMethod(attr));
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
                case RAISE_ACTIVE -> {
                    PyObject active = activeExceptions.peekLast();
                    if (active == null) {
                        throw PyErrors.error("RuntimeError", "No active exception to reraise");
                    }
                    throw PyErrors.errorValue(active);
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
                    PyObject excObj = stack.pop();
                    throw PyErrors.errorValue(excObj);
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
                case HALT -> { return; }

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
                case SET_COMP_INIT -> stack.push(new org.example.jython3.runtime.PySet(new java.util.LinkedHashSet<>()));
                case SET_COMP_ADD -> {
                    PyObject val = stack.pop();
                    if (stack.peek() instanceof org.example.jython3.runtime.PySet acc) acc.add(val);
                }
                case BUILD_SET -> {
                    int n = ai;
                    var set = new java.util.LinkedHashSet<PyObject>();
                    var items = new PyObject[n];
                    for (int i = n - 1; i >= 0; i--) items[i] = stack.pop();
                    for (var item : items) set.add(item);
                    stack.push(new org.example.jython3.runtime.PySet(set));
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
                    PyObject result = callPyCallable(exitFn3,
                            new PyObject[]{excType, excVal, new PyNull()});
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
                if (!exceptStack.isEmpty()) {
                    ExceptHandler h = exceptStack.pop();
                    truncateActiveExceptions(h.activeDepth());
                    ie.setLocation(currentFile, currentLine, currentFunc);
                    ie.addFrame(currentFile, currentLine, currentFunc);
                    captureTraceback(ie);
                    stack.push(ie.value());
                    pc = h.exceptPc();
                    continue outer;
                }
                // No handler in this frame: record it and unwind one level.
                ie.setLocation(currentFile, currentLine, currentFunc);
                ie.addFrame(currentFile, currentLine, currentFunc);
                while (!callStack.isEmpty()) {
                    Frame cf = callStack.pop();
                    code = cf.code();
                    bc = code.bytecode();
                    pool = code.constPool();
                    pc = cf.returnPc();
                    currentLocals = cf.locals();
                    if (cf.globals() != null) currentGlobals = cf.globals();
                    stack = cf.stack();
                    exceptStack = cf.exceptHandlers();
                    activeExceptions = cf.activeExceptions();
                    currentFunc = cf.funcName();
                    currentFile = cf.file();
                    currentLine = cf.line();
                    if (!exceptStack.isEmpty()) {
                        ExceptHandler h = exceptStack.pop();
                        truncateActiveExceptions(h.activeDepth());
                        ie.addFrame(currentFile, currentLine, currentFunc);
                        captureTraceback(ie);
                        stack.push(ie.value());
                        pc = h.exceptPc();
                        continue outer;
                    }
                    ie.addFrame(currentFile, currentLine, currentFunc);
                }
                throw ie;
            }
            pc++;
        }
    }

    // ─── Arithmetic helpers ───────────────────────────────────────────────

    private static PyObject unwrapBool(PyObject o) {
        return (o instanceof PyBool b) ? new PyInt(b.value() ? 1 : 0) : o;
    }

    private void binaryAdd() {
        var r = unwrapBool(stack.pop()); var l = unwrapBool(stack.pop());
        if (l instanceof PyStr ls && r instanceof PyStr rs) {
            stack.push(new PyStr(ls.value() + rs.value()));
        } else if (l instanceof PyList ll && r instanceof PyList rl) {
            var items = new ArrayList<>(ll.items());
            items.addAll(rl.items());
            stack.push(new PyList(items));
        } else if (l instanceof PyTuple lt && r instanceof PyTuple rt) {
            var items = new ArrayList<>(lt.items());
            items.addAll(rt.items());
            stack.push(new PyTuple(items));
        } else if (l instanceof PyTuple && !(r instanceof PyTuple)) {
            throw PyErrors.error("TypeError", "can only concatenate tuple (not \"" + typeName(r) + "\") to tuple");
        } else if (!(l instanceof PyTuple) && r instanceof PyTuple) {
            throw PyErrors.error("TypeError", "can only concatenate " + typeName(l) + " (not \"tuple\") to " + typeName(l));
        } else if (l instanceof PyInt li && r instanceof PyInt ri) {
            long lv = li.value();
            long rv = ri.value();
            try {
                long res = Math.addExact(lv, rv);
                if (res > Integer.MAX_VALUE || res < Integer.MIN_VALUE) {
                    stack.push(new BigInt(java.math.BigInteger.valueOf(res)));
                } else {
                    stack.push(new PyInt((int)res));
                }
            } catch (ArithmeticException ae) {
                stack.push(new BigInt(java.math.BigInteger.valueOf(lv).add(java.math.BigInteger.valueOf(rv))));
            }
        } else if (l instanceof BigInt bi && r instanceof BigInt bri) {
            stack.push(new BigInt(bi.value().add(bri.value())));
        } else if (l instanceof BigInt bi && r instanceof PyInt ri) {
            stack.push(new BigInt(bi.value().add(java.math.BigInteger.valueOf(ri.value()))));
        } else if (l instanceof PyInt li && r instanceof BigInt bri) {
            stack.push(new BigInt(java.math.BigInteger.valueOf(li.value()).add(bri.value())));
        } else if (isNumeric(l) && isNumeric(r)) {
            stack.push(new PyFloat(toDouble(l) + toDouble(r)));
        } else {
            throw PyErrors.error("TypeError", "unsupported operand types for arithmetic");
        }
    }

    private void binarySubtract() {
        var r = unwrapBool(stack.pop()); var l = unwrapBool(stack.pop());
        if (l instanceof PyInt li && r instanceof PyInt ri) {
            long lv = li.value();
            long rv = ri.value();
            try {
                long res = Math.subtractExact(lv, rv);
                if (res > Integer.MAX_VALUE || res < Integer.MIN_VALUE) {
                    stack.push(new BigInt(java.math.BigInteger.valueOf(res)));
                } else {
                    stack.push(new PyInt((int)res));
                }
            } catch (ArithmeticException ae) {
                stack.push(new BigInt(java.math.BigInteger.valueOf(lv).subtract(java.math.BigInteger.valueOf(rv))));
            }
        } else if (l instanceof BigInt bi && r instanceof BigInt bri) {
            stack.push(new BigInt(bi.value().subtract(bri.value())));
        } else if (l instanceof BigInt bi && r instanceof PyInt ri) {
            stack.push(new BigInt(bi.value().subtract(java.math.BigInteger.valueOf(ri.value()))));
        } else if (l instanceof PyInt li && r instanceof BigInt bri) {
            stack.push(new BigInt(java.math.BigInteger.valueOf(li.value()).subtract(bri.value())));
        } else if (isNumeric(l) && isNumeric(r)) {
            stack.push(new PyFloat(toDouble(l) - toDouble(r)));
        } else {
            throw PyErrors.error("TypeError", "unsupported operand types for arithmetic");
        }
    }

    private void binaryMultiply() {
        var r = unwrapBool(stack.pop()); var l = unwrapBool(stack.pop());
        if (l instanceof PyStr s && r instanceof PyInt n) {
            long newLen = (long) s.value().length() * Math.max(0, n.value());
            if (newLen > executionLimits.maxStringLength()) {
                throw PyErrors.error("OverflowError", "String length exceeds maximum allowed limit: " + executionLimits.maxStringLength());
            }
            stack.push(new PyStr(s.value().repeat((int) Math.max(0, n.value()))));
        } else if (l instanceof PyInt n && r instanceof PyStr s) {
            long newLen = (long) s.value().length() * Math.max(0, n.value());
            if (newLen > executionLimits.maxStringLength()) {
                throw PyErrors.error("OverflowError", "String length exceeds maximum allowed limit: " + executionLimits.maxStringLength());
            }
            stack.push(new PyStr(s.value().repeat((int) Math.max(0, n.value()))));
        } else if (l instanceof PyStr s && r instanceof BigInt n) {
            long newLen = (long) s.value().length() * Math.max(0, n.value().longValue());
            if (newLen > executionLimits.maxStringLength()) {
                throw PyErrors.error("OverflowError", "String length exceeds maximum allowed limit: " + executionLimits.maxStringLength());
            }
            stack.push(new PyStr(s.value().repeat((int) Math.max(0, n.value().intValue()))));
        } else if (l instanceof BigInt n && r instanceof PyStr s) {
            long newLen = (long) s.value().length() * Math.max(0, n.value().longValue());
            if (newLen > executionLimits.maxStringLength()) {
                throw PyErrors.error("OverflowError", "String length exceeds maximum allowed limit: " + executionLimits.maxStringLength());
            }
            stack.push(new PyStr(s.value().repeat((int) Math.max(0, n.value().intValue()))));
        } else if (l instanceof PyList ll && r instanceof PyInt n) {
            long newSize = (long) ll.items().size() * Math.max(0, n.value());
            if (newSize > executionLimits.maxCollectionSize()) {
                throw PyErrors.error("OverflowError", "List size exceeds maximum allowed limit: " + executionLimits.maxCollectionSize());
            }
            var items = new ArrayList<PyObject>();
            long count = Math.max(0, n.value());
            for (long i = 0; i < count; i++) items.addAll(ll.items());
            stack.push(new PyList(items));
        } else if (l instanceof PyTuple tt && r instanceof PyInt n) {
            long count = Math.max(0, n.value());
            long newSize = (long) tt.items().size() * count;
            if (newSize > executionLimits.maxCollectionSize()) {
                throw PyErrors.error("OverflowError", "Tuple size exceeds maximum allowed limit: " + executionLimits.maxCollectionSize());
            }
            var items = new ArrayList<PyObject>();
            for (long i = 0; i < count; i++) items.addAll(tt.items());
            stack.push(new PyTuple(items));
        } else if (l instanceof PyInt n && r instanceof PyTuple tt) {
            long count = Math.max(0, n.value());
            long newSize = (long) tt.items().size() * count;
            if (newSize > executionLimits.maxCollectionSize()) {
                throw PyErrors.error("OverflowError", "Tuple size exceeds maximum allowed limit: " + executionLimits.maxCollectionSize());
            }
            var items = new ArrayList<PyObject>();
            for (long i = 0; i < count; i++) items.addAll(tt.items());
            stack.push(new PyTuple(items));
        } else if (l instanceof PyInt li && r instanceof PyInt ri) {
            long lv = li.value();
            long rv = ri.value();
            try {
                long res = Math.multiplyExact(lv, rv);
                if (res > Integer.MAX_VALUE || res < Integer.MIN_VALUE) {
                    stack.push(new BigInt(java.math.BigInteger.valueOf(res)));
                } else {
                    stack.push(new PyInt((int)res));
                }
            } catch (ArithmeticException ae) {
                stack.push(new BigInt(java.math.BigInteger.valueOf(lv).multiply(java.math.BigInteger.valueOf(rv))));
            }
        } else if (l instanceof BigInt bi && r instanceof BigInt bri) {
            stack.push(new BigInt(bi.value().multiply(bri.value())));
        } else if (l instanceof BigInt bi && r instanceof PyInt ri) {
            stack.push(new BigInt(bi.value().multiply(java.math.BigInteger.valueOf(ri.value()))));
        } else if (l instanceof PyInt li && r instanceof BigInt bri) {
            stack.push(new BigInt(java.math.BigInteger.valueOf(li.value()).multiply(bri.value())));
        } else if (isNumeric(l) && isNumeric(r)) {
            stack.push(new PyFloat(toDouble(l) * toDouble(r)));
        } else {
            throw PyErrors.error("TypeError", "unsupported operand types for arithmetic");
        }
    }

    // ─── Type method helpers ──────────────────────────────────────────────

    private PyObject strMethod(PyStr s, String method) {
        return switch (method) {
            case "upper" -> new PyBuiltinFunction("str.upper", args -> new PyStr(s.value().toUpperCase()));
            case "lower" -> new PyBuiltinFunction("str.lower", args -> new PyStr(s.value().toLowerCase()));
            case "strip" -> new PyBuiltinFunction("str.strip", args -> new PyStr(s.value().strip()));
            case "split" -> new PyBuiltinFunction("str.split", args -> {
                String[] parts;
                if (args.length > 0 && args[0] instanceof PyStr sep) {
                    parts = s.value().split(java.util.regex.Pattern.quote(sep.value()), -1);
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
                return new PyInt(s.value().indexOf(sub.value()));
            });
            case "count" -> new PyBuiltinFunction("str.count", args -> {
                checkArgCount("str.count", args, 1);
                if (!(args[0] instanceof PyStr sub)) throw PyErrors.error("TypeError", "count() argument must be str");
                int count = 0; int idx = 0;
                while ((idx = s.value().indexOf(sub.value(), idx)) != -1) { count++; idx += sub.value().length(); }
                return new PyInt(count);
            });
            case "isdigit" -> new PyBuiltinFunction("str.isdigit", args -> PyBool.of(!s.value().isEmpty() && s.value().chars().allMatch(Character::isDigit)));
            case "isalpha" -> new PyBuiltinFunction("str.isalpha", args -> PyBool.of(!s.value().isEmpty() && s.value().chars().allMatch(Character::isLetter)));
            case "isalnum" -> new PyBuiltinFunction("str.isalnum", args -> PyBool.of(!s.value().isEmpty() && s.value().chars().allMatch(Character::isLetterOrDigit)));
            default -> throw PyErrors.error("AttributeError", "'str' object has no attribute '" + method + "'");
        };
    }

    private PyObject setMethod(PySet set, String method) {
        return switch (method) {
            case "add" -> new PyBuiltinFunction("set.add", args -> {
                checkArgCount("set.add", args, 1);
                set.add(args[0]);
                return new PyNull();
            });
            default -> throw PyErrors.error("AttributeError", "'set' object has no attribute '" + method + "'");
        };
    }

    private static String pyPrintStr(PyObject o) {
        if (o == null) return "None";
        if (o instanceof PyException pe) return pe.message();
        return o.toString();
    }

    private PyObject listMethod(PyList list, String method) {
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
                    if (pyEquals(list.items().get(i), args[0])) return new PyInt(i);
                }
                throw PyErrors.error("ValueError", args[0] + " is not in list");
            });
            case "count" -> new PyBuiltinFunction("list.count", args -> {
                checkArgCount("list.count", args, 1);
                int c = 0; for (var item : list.items()) if (pyEquals(item, args[0])) c++;
                return new PyInt(c);
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

    private PyObject dictMethod(PyDict dict, String method) {
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
            if (cv instanceof PyFunction fn) return new PyBoundMethod(inst, fn);
            return cv;
        }
        if (obj instanceof PyClass cls) return cls.lookupMember(attr);
        if (obj instanceof PyModule mod) return mod.attrs().get(attr);
        if (obj instanceof PyDict dict) return dictMethod(dict, attr);
        if (obj instanceof PyList list) return listMethod(list, attr);
        if (obj instanceof PySet set)   return setMethod(set, attr);
        if (obj instanceof PyStr s)     return strMethod(s, attr);
        return null;
    }

    /**
     * Core call dispatcher with optional keyword arguments.
     * Both CALL_FUNCTION and CALL_FUNCTION_KW paths route through here.
     *
     * @param kwNames  keyword argument names, or null/empty for positional-only
     * @param kwValues keyword argument values parallel to kwNames
     */
    private PyObject callPyCallableKw(PyObject callable, PyObject[] posArgs,
                                      String[] kwNames, PyObject[] kwValues) {
        if (callable instanceof PyBuiltinFunction bf) {
            // Built-ins only receive positional args; keyword args are unsupported at this layer.
            // Keyword args to builtins (like print(end='')) require Sprint B stdlib rewrite.
            return bf.fn().apply(posArgs);
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
        if (callable instanceof PyFunction fn) {
            if (containsYield(fn.code())) {
                // Generators: bind args to locals eagerly, store in generator
                var genLocals = bindArgs(fn, posArgs, kwNames, kwValues);
                return new PyGenerator(fn, genLocals);
            }
            var savedStack = stack;
            var savedLocals = currentLocals;
            var savedExcept = exceptStack;
            var savedActive = activeExceptions;
            var savedFunc = currentFunc;
            var savedLine = currentLine;

            currentLocals = bindArgs(fn, posArgs, kwNames, kwValues);
            stack = new ArrayDeque<>();
            exceptStack = new ArrayDeque<>();
            activeExceptions = new ArrayDeque<>();
            currentFunc = fn.name();
            run(fn.code());
            PyObject result = stack.isEmpty() ? new PyNull() : stack.pop();

            stack = savedStack;
            currentLocals = savedLocals;
            exceptStack = savedExcept;
            activeExceptions = savedActive;
            currentFunc = savedFunc;
            currentLine = savedLine;
            return result;
        }
        throw PyErrors.error("TypeError", "'" + callable + "' is not callable");
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
        var fl = new HashMap<String, PyObject>();
        // 1. Seed with closure
        if (fn.closure() != null) fl.putAll(fn.closure());

        List<String> params     = fn.params();
        PyObject[]   defaults   = fn.defaults();
        String       argsParam  = fn.argsParam();
        String       kwargsParam = fn.kwargsParam();
        int nParams   = params.size();
        int nDefaults = defaults.length;
        // Index at which defaults start (right-aligned)
        int defaultStart = nParams - nDefaults;

        // 2. Assign positional args to params
        int nPos = posArgs.length;
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

    private String debugStack(Deque<PyObject> s, int n) {
        var items = new java.util.ArrayDeque<>(s);
        var parts = new java.util.ArrayList<String>();
        for (int i = 0; i < n && !items.isEmpty(); i++) parts.add(String.valueOf(items.pop()));
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

            if (item.equals("*")) {
                throw PyErrors.error("ImportError", "'import *' from Java package is not supported");
            }

            String alias = parts.length > 3 ? parts[3] : item;
            String fullName = pkg + "." + item;

            // Check built-in modules first before attempting Java class resolution
            PyModule builtinMod = resolveBuiltinModule(pkg);
            if (builtinMod != null) {
                PyObject attr = builtinMod.attrs().get(item);
                if (attr != null) {
                    bindImportedName(alias, attr);
                    return;
                }
                throw PyErrors.error("ImportError", "cannot import name '" + item + "' from '" + pkg + "'");
            }

            if (pkg.contains(".") || isJavaRootPackage(pkg)) {
                try {
                    PyObject resolved = JavaBridge.resolveClassOrPackage(fullName, javaAccessPolicy);
                    if (resolved != null) {
                        bindImportedName(alias, resolved);
                        return;
                    }
                } catch (InterpreterException ex) {
                    throw ex;
                }
            }

            PyModule mod = loadDiskModule(pkg);
            PyObject attr = mod.attrs().get(item);
            if (attr != null) {
                bindImportedName(alias, attr);
                return;
            }
            throw PyErrors.error("ImportError", "cannot import name '" + item + "' from '" + pkg + "'");
        }

        // ── pkg:alias  or plain pkg ──
        String[] parts = spec.split(":", -1);
        String pkg = parts[0];
        String alias = parts.length > 1 ? parts[1] : parts[0];

        PyModule builtin = resolveBuiltinModule(pkg);
        if (builtin != null) {
            bindImportedName(alias, builtin);
            return;
        }

        if (pkg.contains(".") || isJavaRootPackage(pkg)) {
            try {
                PyObject javaObj = JavaBridge.resolveClassOrPackage(pkg, javaAccessPolicy);
                if (javaObj != null) {
                    bindImportedName(alias, javaObj);
                    return;
                }
            } catch (InterpreterException ex) {
                throw ex;
            }
        }

        if (!pkg.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw PyErrors.error("ImportError", "invalid module name '" + pkg + "'");
        }

        PyModule diskMod = loadDiskModule(pkg);
        bindImportedName(alias, diskMod);
    }

    /**
     * Loads, compiles, and executes a Python source file from the modules/ directory.
     * <p>Enforces full module lifecycle:
     * <ul>
     *   <li>Returns cached module if already LOADED.
     *   <li>Returns initializing module if a circular import occurs.
     *   <li>Executes module code in an isolated scope (its own dict) so caller state is untouched.
     *   <li>Propagates any error up to the caller without swallowing it.
     *   <li>Evicts partially initialized module from cache on failure.
     * </ul>
     */
    private PyModule loadDiskModule(String pkg) {
        ModuleEntry entry = moduleCache.get(pkg);
        if (entry != null) {
            return entry.module();
        }

        java.nio.file.Path p = java.nio.file.Path.of("modules", pkg + ".py").normalize();
        if (!p.startsWith(java.nio.file.Path.of("modules").normalize())) {
            throw PyErrors.error("ImportError", "no module named '" + pkg + "'");
        }
        if (!hostAccessPolicy.canImportFromDisk(p)) {
            throw PyErrors.error("SecurityError", "importing module '" + pkg + "' from filesystem is restricted");
        }

        String content;
        try {
            content = java.nio.file.Files.readString(p);
        } catch (java.io.IOException e) {
            throw PyErrors.error("ImportError", "no module named '" + pkg + "'");
        }

        CompiledCode moduleCode = compile(content);
        Map<String, PyObject> moduleScope = new HashMap<>(builtinSymbols);

        PyModule module = new PyModule(pkg, moduleScope);
        moduleCache.put(pkg, new ModuleEntry(module, ModuleState.INITIALIZING));

        try {
            executeIsolated(moduleCode, moduleScope, p.toString());
            moduleCache.put(pkg, new ModuleEntry(module, ModuleState.LOADED));
            return module;
        } catch (InterpreterException ie) {
            // Remove from cache so subsequent imports don't see a broken module
            moduleCache.remove(pkg);
            throw ie;
        }
    }

    private PyModule resolveBuiltinModule(String name) {
        return switch (name) {
            case "math" -> BuiltinModules.math();
            case "sys"  -> BuiltinModules.sys();
            case "os"   -> BuiltinModules.os(hostAccessPolicy);
            case "io"   -> BuiltinModules.io(hostAccessPolicy);
            case "json" -> BuiltinModules.json();
            case "re"   -> BuiltinModules.re();
            case "time" -> BuiltinModules.time();
            case "random" -> BuiltinModules.random();
            default     -> null;
        };
    }

    private static boolean isNan(PyObject o) {
        return o instanceof PyFloat f && Double.isNaN(f.value());
    }

    private boolean containsYield(CompiledCode code) {
        if (code == null || code.bytecode() == null) return false;
        int[] bc = code.bytecode();
        for (int instr : bc) {
            int op = instr >>> 16;
            if (op >= 0 && op < OP_VALUES.length && OP_VALUES[op] == OpCode.YIELD_VALUE) {
                return true;
            }
        }
        return false;
    }

    private static boolean isJavaRootPackage(String name) {
        return name.equals("java") || name.equals("javax") || name.equals("org") ||
               name.equals("com") || name.equals("net") || name.equals("io");
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
