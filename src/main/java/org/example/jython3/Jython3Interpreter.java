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
import org.example.jython3.runtime.PyStr;
import org.example.jython3.runtime.PySuper;
import org.example.jython3.runtime.SystemExit;
import org.example.jython3.security.AllowlistPolicy;
import org.example.jython3.security.DenyAllPolicy;
import org.example.jython3.security.HostAccessPolicy;
import org.example.jython3.security.JavaAccessPolicy;
import org.example.jython3.security.SandboxedPolicy;
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
    private Deque<PyObject> stack;
    private Map<String, PyObject> currentLocals;
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
        return new Jython3Interpreter(AllowlistPolicy.trusted(), HostAccessPolicy.PERMISSIVE);
    }

    /**
     * Creates an interpreter for untrusted or restricted script execution.
     * Denies all Java reflection interop and denies all host OS filesystem/environment access.
     */
    public static Jython3Interpreter restricted() {
        return new Jython3Interpreter(DenyAllPolicy.INSTANCE, HostAccessPolicy.DENY_ALL);
    }

    // ─── Constructor ──────────────────────────────────────────────────────
    public Jython3Interpreter() {
        this(AllowlistPolicy.trusted(), HostAccessPolicy.PERMISSIVE);
    }

    /**
     * Creates an interpreter whose Java access is controlled by {@code javaAccessPolicy}.
     * <p><b>Security Default:</b> Unless an explicit {@link HostAccessPolicy} is provided,
     * host filesystem and OS access defaults strictly to {@link HostAccessPolicy#DENY_ALL}.
     */
    public Jython3Interpreter(JavaAccessPolicy javaAccessPolicy) {
        this(javaAccessPolicy, HostAccessPolicy.DENY_ALL, ExceptionTypeRegistry.standard());
    }

    public Jython3Interpreter(JavaAccessPolicy javaAccessPolicy, HostAccessPolicy hostAccessPolicy) {
        this(javaAccessPolicy, hostAccessPolicy, ExceptionTypeRegistry.standard());
    }

    /**
     * Creates an interpreter with explicit access policies and exception-type registry.
     */
    public Jython3Interpreter(JavaAccessPolicy javaAccessPolicy,
                              HostAccessPolicy hostAccessPolicy,
                              ExceptionTypeRegistry exceptionTypes) {
        this.javaAccessPolicy = Objects.requireNonNull(javaAccessPolicy, "javaAccessPolicy");
        this.hostAccessPolicy = Objects.requireNonNull(hostAccessPolicy, "hostAccessPolicy");
        this.exceptionTypes = Objects.requireNonNull(exceptionTypes, "exceptionTypes");
        PyCaller.CURRENT.set(new PyCaller() {
            @Override public PyObject call(PyObject callable, PyObject[] args) {
                return callPyCallable(callable, args);
            }

            @Override public List<PyObject> collectYields(PyFunction fn, PyObject[] args) {
                return runGenerator(fn, args);
            }
        });
        registerBuiltins();
    }

    public Jython3Interpreter(JavaAccessPolicy javaAccessPolicy,
                              ExceptionTypeRegistry exceptionTypes) {
        this(javaAccessPolicy, HostAccessPolicy.DENY_ALL, exceptionTypes);
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
        BuiltinFunctions.register(this::setGlobal, this::callPyCallable, javaAccessPolicy);
        // Built-in exception classes must resolve as real objects so that
        // `except ValueError` and `class E(Exception)` work.
        for (var entry : exceptionTypes.types().entrySet()) {
            globals.putIfAbsent(entry.getKey(), entry.getValue());
        }
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
            currentLocals = globals;
            exceptStack = new ArrayDeque<>();
            activeExceptions = new ArrayDeque<>();
            callStack.clear();
            yieldCollector = null;
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
        var savedExcept = exceptStack;
        var savedActive = activeExceptions;
        var savedCallStack = new ArrayDeque<>(callStack);
        var savedCollector = yieldCollector;
        var savedFunc = currentFunc;
        var savedFile = currentFile;
        var savedLine = currentLine;
        try {
            stack = new ArrayDeque<>();
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
            var locals = new HashMap<String, PyObject>();
            if (fn.closure() != null) locals.putAll(fn.closure());
            for (int i = 0; i < fn.params().size() && i < args.length; i++) {
                locals.put(fn.params().get(i), args[i]);
            }
            callStack.clear();
            stack = new ArrayDeque<>();
            currentLocals = locals;
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
                    else if (v instanceof String s) stack.push(new PyStr(s));
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
                    if (currentLocals != null) {
                        v = currentLocals.get(n);
                    }
                    if (v == null) v = globals.get(n);
                    if (v == null) throw PyErrors.error("NameError", "name '" + n + "' is not defined");
                    stack.push(v);
                }
                case LOAD_GLOBAL -> {
                    String n = (String) pool[ai];
                    PyObject v = globals.get(n);
                    if (v == null) throw PyErrors.error("NameError", "name '" + n + "' is not defined");
                    stack.push(v);
                }
                case STORE_FAST -> currentLocals.put((String) pool[ai], stack.pop());
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
                    } else if (container instanceof PyDict dict) {
                        dict.map().put(key, val);
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
                    } else if (container instanceof PyDict dict) {
                        if (dict.map().remove(key) == null)
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
                    var r = stack.pop(); var l = stack.pop();
                    if (l instanceof PyInt li && r instanceof PyInt ri) {
                        if (ri.value() == 0) throw PyErrors.error("ZeroDivisionError", "division by zero");
                        if (li.value() % ri.value() == 0) {
                            stack.push(new PyInt(li.value() / ri.value()));
                        } else {
                            stack.push(new PyFloat((double) li.value() / ri.value()));
                        }
                    } else if (isNumeric(l) && isNumeric(r)) {
                        double rv = toDouble(r);
                        if (rv == 0) throw PyErrors.error("ZeroDivisionError", "division by zero");
                        stack.push(new PyFloat(toDouble(l) / rv));
                    } else {
                        throw PyErrors.error("TypeError", "unsupported operand types for /");
                    }
                }
                case FLOOR_DIVIDE -> {
                    var r = stack.pop(); var l = stack.pop();
                    if (l instanceof PyInt li && r instanceof PyInt ri) {
                        if (ri.value() == 0) throw PyErrors.error("ZeroDivisionError", "integer division or modulo by zero");
                        stack.push(new PyInt(Math.floorDiv(li.value(), ri.value())));
                    } else if (isNumeric(l) && isNumeric(r)) {
                        double rv = toDouble(r);
                        if (rv == 0) throw PyErrors.error("ZeroDivisionError", "float floor division by zero");
                        stack.push(new PyFloat(Math.floor(toDouble(l) / rv)));
                    } else {
                        throw PyErrors.error("TypeError", "unsupported operand types for //");
                    }
                }
                case MODULO -> {
                    var r = stack.pop(); var l = stack.pop();
                    if (l instanceof PyStr fmt) {
                        stack.push(new PyStr(pyStringFormat(fmt.value(), r)));
                    } else if (l instanceof PyInt li && r instanceof PyInt ri) {
                        if (ri.value() == 0) throw PyErrors.error("ZeroDivisionError", "integer modulo by zero");
                        stack.push(new PyInt(Math.floorMod(li.value(), ri.value())));
                    } else if (isNumeric(l) && isNumeric(r)) {
                        double rv = toDouble(r), lv = toDouble(l);
                        if (rv == 0) throw PyErrors.error("ZeroDivisionError", "float modulo by zero");
                        stack.push(new PyFloat(lv - Math.floor(lv / rv) * rv));
                    } else {
                        throw PyErrors.error("TypeError", "unsupported operand types for %");
                    }
                }
                case POWER -> {
                    var r = stack.pop(); var l = stack.pop();
                    if (l instanceof PyInt li && r instanceof PyInt ri && ri.value() >= 0) {
                        stack.push(new PyInt((int) Math.pow(li.value(), ri.value())));
                    } else if (isNumeric(l) && isNumeric(r)) {
                        stack.push(new PyFloat(Math.pow(toDouble(l), toDouble(r))));
                    } else {
                        throw PyErrors.error("TypeError", "unsupported operand types for **");
                    }
                }
                case UNARY_MINUS -> {
                    var v = stack.pop();
                    if (v instanceof PyInt i) stack.push(new PyInt(-i.value()));
                    else if (v instanceof PyFloat f) stack.push(new PyFloat(-f.value()));
                    else throw PyErrors.error("TypeError", "bad operand type for unary -: '" + typeName(v) + "'");
                }

                // ── Comparison ──
                case COMPARE_EQ     -> { var r = stack.pop(); var l = stack.pop(); stack.push(PyBool.of(pyEquals(l, r))); }
                case COMPARE_NEQ    -> { var r = stack.pop(); var l = stack.pop(); stack.push(PyBool.of(!pyEquals(l, r))); }
                case COMPARE_LT     -> { var r = stack.pop(); var l = stack.pop(); stack.push(PyBool.of(compareObjects(l, r) < 0)); }
                case COMPARE_GT     -> { var r = stack.pop(); var l = stack.pop(); stack.push(PyBool.of(compareObjects(l, r) > 0)); }
                case COMPARE_LTE    -> { var r = stack.pop(); var l = stack.pop(); stack.push(PyBool.of(compareObjects(l, r) <= 0)); }
                case COMPARE_GTE    -> { var r = stack.pop(); var l = stack.pop(); stack.push(PyBool.of(compareObjects(l, r) >= 0)); }
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
                case PRINT -> System.out.println(stack.pop());
                case PRINT_MULTI -> {
                    PyList list = (PyList) stack.pop();
                    var sb = new StringBuilder();
                    for (int i = 0; i < list.items().size(); i++) {
                        if (i > 0) sb.append(' ');
                        sb.append(list.items().get(i).toString());
                    }
                    System.out.println(sb);
                }

                // ── Control flow ──
                case JUMP_IF_FALSE -> { if (!isTruthy(stack.pop())) { pc = ai; continue; } }
                case JUMP -> { pc = ai; continue; }

                // ── Modules ──
                case IMPORT_MODULE -> importModule((String) pool[ai]);

                // ── Functions & Classes ──
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
                        stack.push(new PyGenerator(fn2, args));
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
                            stack.push(new PyGenerator(fn, args));
                        } else {
                            if (callStack.size() >= MAX_CALL_DEPTH) {
                                throw PyErrors.error("RecursionError", "maximum recursion depth exceeded");
                            }
                            callStack.push(new Frame(code, pc + 1, currentLocals, stack, exceptStack,
                                    activeExceptions, currentFunc, currentFile, currentLine));
                            var fl = new HashMap<String, PyObject>();
                            if (fn.closure() != null) {
                                fl.putAll(fn.closure());
                            }
                            for (int i = 0; i < fn.params().size() && i < argc; i++) {
                                fl.put(fn.params().get(i), args[i]);
                            }
                            currentLocals = fl;
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
                case BUILD_DICT -> {
                    int n = ai;
                    var map = new LinkedHashMap<PyObject, PyObject>();
                    var pairs = new PyObject[n * 2];
                    for (int i = n * 2 - 1; i >= 0; i--) pairs[i] = stack.pop();
                    for (int i = 0; i < n * 2; i += 2) map.put(pairs[i], pairs[i + 1]);
                    stack.push(new PyDict(map));
                }
                case BINARY_SUBSCR -> {
                    PyObject idx = stack.pop();
                    PyObject ctr = stack.pop();
                    if (ctr instanceof PyList list && idx instanceof PyInt ix) {
                        int i = ix.value();
                        if (i < 0) i += list.items().size();
                        if (i < 0 || i >= list.items().size())
                            throw PyErrors.error("IndexError", "list index out of range");
                        stack.push(list.items().get(i));
                    } else if (ctr instanceof PyDict dict) {
                        PyObject val = dict.map().get(idx);
                        if (val == null) throw PyErrors.error("KeyError", String.valueOf(idx));
                        stack.push(val);
                    } else if (ctr instanceof PyStr s && idx instanceof PyInt ix) {
                        int i = ix.value();
                        if (i < 0) i += s.value().length();
                        if (i < 0 || i >= s.value().length())
                            throw PyErrors.error("IndexError", "string index out of range");
                        stack.push(new PyStr(String.valueOf(s.value().charAt(i))));
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
                    else if (o instanceof PyStr s) {
                        var chars = new ArrayList<PyObject>();
                        for (char ch : s.value().toCharArray()) chars.add(new PyStr(String.valueOf(ch)));
                        stack.push(new PyIterator(chars, 0));
                    } else if (o instanceof PyDict d) {
                        stack.push(new PyIterator(new ArrayList<>(d.map().keySet()), 0));
                    } else if (o instanceof PyGenerator gen) {
                        // Eagerly drain the generator into a list then wrap as iterator
                        var items = new ArrayList<PyObject>();
                        while (!gen.isDone()) {
                            try { items.add(gen.next()); }
                            catch (InterpreterException ie) {
                                if ("StopIteration".equals(ie.typeName())) break;
                                throw ie;
                            }
                        }
                        stack.push(new PyIterator(items, 0));
                    } else if (o instanceof PyJavaObject jo && jo.object() instanceof Iterable<?> iterable) {
                        var items = new ArrayList<PyObject>();
                        for (Object item : iterable) items.add(JavaBridge.toPyObject(item, jo.policy()));
                        stack.push(new PyIterator(items, 0));
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
                    if (typeObj instanceof PyList tupleOrList) {
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

    private void binaryAdd() {
        var r = stack.pop(); var l = stack.pop();
        if (l instanceof PyStr ls && r instanceof PyStr rs) {
            stack.push(new PyStr(ls.value() + rs.value()));
        } else if (l instanceof PyList ll && r instanceof PyList rl) {
            var items = new ArrayList<>(ll.items());
            items.addAll(rl.items());
            stack.push(new PyList(items));
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
        var r = stack.pop(); var l = stack.pop();
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
        var r = stack.pop(); var l = stack.pop();
        if (l instanceof PyStr s && r instanceof PyInt n) {
            stack.push(new PyStr(s.value().repeat(Math.max(0, (int)Math.min(Integer.MAX_VALUE, n.value())))));
        } else if (l instanceof PyInt n && r instanceof PyStr s) {
            stack.push(new PyStr(s.value().repeat(Math.max(0, (int)Math.min(Integer.MAX_VALUE, n.value())))));
        } else if (l instanceof PyStr s && r instanceof BigInt n) {
            stack.push(new PyStr(s.value().repeat(Math.max(0, n.value().intValue()))));
        } else if (l instanceof BigInt n && r instanceof PyStr s) {
            stack.push(new PyStr(s.value().repeat(Math.max(0, n.value().intValue()))));
        } else if (l instanceof PyList ll && r instanceof PyInt n) {
            var items = new ArrayList<PyObject>();
            long count = Math.max(0, n.value());
            for (long i = 0; i < count && items.size() < 100000; i++) items.addAll(ll.items());
            stack.push(new PyList(items));
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
            case "keys" -> new PyBuiltinFunction("dict.keys", args -> new PyList(new ArrayList<>(dict.map().keySet())));
            case "values" -> new PyBuiltinFunction("dict.values", args -> new PyList(new ArrayList<>(dict.map().values())));
            case "items" -> new PyBuiltinFunction("dict.items", args -> {
                var items = new ArrayList<PyObject>();
                for (var entry : dict.map().entrySet()) items.add(new PyList(List.of(entry.getKey(), entry.getValue())));
                return new PyList(items);
            });
            case "get" -> new PyBuiltinFunction("dict.get", args -> {
                if (args.length < 1) throw PyErrors.error("TypeError", "get() takes at least 1 argument");
                PyObject val = dict.map().get(args[0]);
                if (val != null) return val;
                return (args.length > 1) ? args[1] : new PyNull();
            });
            case "update" -> new PyBuiltinFunction("dict.update", args -> {
                checkArgCount("dict.update", args, 1);
                if (!(args[0] instanceof PyDict other)) throw PyErrors.error("TypeError", "update() argument must be a dict");
                dict.map().putAll(other.map());
                return new PyNull();
            });
            case "pop" -> new PyBuiltinFunction("dict.pop", args -> {
                if (args.length < 1) throw PyErrors.error("TypeError", "pop() takes at least 1 argument");
                PyObject val = dict.map().remove(args[0]);
                if (val != null) return val;
                if (args.length > 1) return args[1];
                throw PyErrors.error("KeyError", String.valueOf(args[0]));
            });
            case "clear" -> new PyBuiltinFunction("dict.clear", args -> { dict.map().clear(); return new PyNull(); });
            case "copy" -> new PyBuiltinFunction("dict.copy", args -> new PyDict(new LinkedHashMap<>(dict.map())));
            default -> throw PyErrors.error("AttributeError", "'dict' object has no attribute '" + method + "'");
        };
    }

    private PyObject callPyCallable(PyObject callable, PyObject[] args) {
        if (callable instanceof PyBuiltinFunction bf) {
            return bf.fn().apply(args);
        }
        if (callable instanceof PyBoundMethod bm) {
            var callArgs = new PyObject[args.length + 1];
            callArgs[0] = bm.instance();
            System.arraycopy(args, 0, callArgs, 1, args.length);
            return callPyCallable(bm.fn(), callArgs);
        }
        if (callable instanceof PyJavaClass jc) {
            return jc.newInstance(args);
        }
        if (callable instanceof PyJavaMethod jm) {
            return jm.invoke(args);
        }
        if (callable instanceof PyFunction fn) {
            if (containsYield(fn.code())) {
                return new PyGenerator(fn, args);
            }
            var savedStack = stack;
            var savedLocals = currentLocals;
            var savedExcept = exceptStack;
            var savedActive = activeExceptions;
            var savedFunc = currentFunc;
            var savedLine = currentLine;

            var fl = new HashMap<String, PyObject>();
            if (fn.closure() != null) {
                fl.putAll(fn.closure());
            }
            for (int i = 0; i < fn.params().size() && i < args.length; i++)
                fl.put(fn.params().get(i), args[i]);

            currentLocals = fl;
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

            try {
                PyObject resolved = JavaBridge.resolveClassOrPackage(fullName, javaAccessPolicy);
                if (resolved != null) {
                    globals.put(alias, resolved);
                    return;
                }
            } catch (InterpreterException ex) {
                throw ex;
            }

            PyModule builtinMod = resolveBuiltinModule(pkg);
            if (builtinMod != null) {
                PyObject attr = builtinMod.attrs().get(item);
                if (attr != null) { globals.put(alias, attr); return; }
                throw PyErrors.error("ImportError", "cannot import name '" + item + "' from '" + pkg + "'");
            }

            PyModule mod = loadDiskModule(pkg);
            PyObject attr = mod.attrs().get(item);
            if (attr != null) {
                if (currentLocals != null) currentLocals.put(alias, attr);
                else globals.put(alias, attr);
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
            if (currentLocals != null) currentLocals.put(alias, builtin);
            else globals.put(alias, builtin);
            return;
        }

        if (pkg.contains(".") || isJavaRootPackage(pkg)) {
            try {
                PyObject javaObj = JavaBridge.resolveClassOrPackage(pkg, javaAccessPolicy);
                if (javaObj != null) {
                    if (currentLocals != null) currentLocals.put(alias, javaObj);
                    else globals.put(alias, javaObj);
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
        if (currentLocals != null) currentLocals.put(alias, diskMod);
        else globals.put(alias, diskMod);
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
        Map<String, PyObject> moduleScope = new HashMap<>();
        // Inject built-ins and standard types into module globals
        for (var e : globals.entrySet()) {
            moduleScope.putIfAbsent(e.getKey(), e.getValue());
        }

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
