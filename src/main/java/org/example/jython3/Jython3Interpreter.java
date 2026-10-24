/*====================================================================
 * Jython3Interpreter.java – Phase 10: Production Foundations & OOP
 *
 * Architecture:
 *   Source → ANTLR4 → PythonIndentStream → ParseTree → CompileVisitor → CompiledCode
 *   CompiledCode = int[] bytecode + Object[] constPool
 *   run() decodes bytecode and executes via stack machine
 *
 * New in Phase 10:
 *   • True Python indentation blocks (NEWLINE / INDENT / DEDENT via PythonIndentStream)
 *   • Full Object-Oriented Programming:
 *       - class ClassName[(BaseClass)]: suite
 *       - def __init__(self, ...): constructor
 *       - Instance fields: self.field = val, obj.field
 *       - Bound methods: obj.method(args) binds obj to self
 *       - Inheritance with superclass attribute resolution
 *       - Special methods (__str__, __repr__, __len__, etc.)
 *   • Indentation security: MAX_INDENT_DEPTH limit, IndentationError on bad unindent
 *   • Implicit line joining inside (), [], {}
 *====================================================================*/

package org.example.jython3;

import java.io.BufferedReader;
import java.io.FileReader;
import java.io.IOException;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;

public class Jython3Interpreter {

    // ─── Bytecode Cache ──────────────────────────────────────────────────
    private static final int MAX_CACHE_SIZE = 1024;
    private final Map<String, CompiledCode> bytecodeCache = new ConcurrentHashMap<>();

    public void clearCache() {
        bytecodeCache.clear();
    }

    public int getCacheSize() {
        return bytecodeCache.size();
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

    // ─── Op‑code set ──────────────────────────────────────────────────────
    enum OpCode {
        LOAD_CONST, LOAD_FAST, LOAD_GLOBAL, STORE_FAST, STORE_SUBSCR, STORE_ATTR,
        ADD, SUBTRACT, MULTIPLY, DIVIDE, FLOOR_DIVIDE, MODULO, POWER, UNARY_MINUS,
        COMPARE_EQ, COMPARE_NEQ, COMPARE_LT, COMPARE_GT, COMPARE_LTE, COMPARE_GTE,
        COMPARE_IN, COMPARE_NOT_IN, COMPARE_IS, COMPARE_IS_NOT,
        LOGICAL_NOT,
        PRINT, PRINT_MULTI,
        JUMP_IF_FALSE, JUMP_IF_TRUE_OR_POP, JUMP_IF_FALSE_OR_POP, JUMP,
        IMPORT_MODULE, MAKE_FUNCTION, BUILD_CLASS, CALL_FUNCTION, RETURN_VALUE,
        POP_TOP, DUP_TOP,
        BUILD_LIST, BUILD_DICT, BINARY_SUBSCR, GET_ITER, FOR_ITER,
        DELETE_FAST, DELETE_SUBSCR,
        LOAD_ATTR,
        PUSH_EXCEPT_HANDLER, POP_EXCEPT_HANDLER, RAISE,
        HALT
    }

    private static final int NO_ARG = 0xFFFF;

    // ─── Opcode dispatch cache ────────────────────────────────────────────
    private static final OpCode[] OP_VALUES = OpCode.values();

    /** Maximum call stack depth – prevents StackOverflowError from deep recursion. */
    private static final int MAX_CALL_DEPTH = 500;

    // ─── CompiledCode ─────────────────────────────────────────────────────
    public record CompiledCode(int[] bytecode, Object[] constPool) {}

    // ─── Runtime state ────────────────────────────────────────────────────
    private final Map<String, PyObject> globals = new HashMap<>();
    private Deque<PyObject> stack;
    private Map<String, PyObject> currentLocals;
    private final Deque<Frame> callStack = new ArrayDeque<>();
    /** Per-frame exception handler stack — saved/restored on function call boundaries. */
    private Deque<ExceptHandler> exceptStack = new ArrayDeque<>();
    private record ExceptHandler(int exceptPc) {}
    private record Frame(CompiledCode code, int returnPc,
                         Map<String, PyObject> locals, Deque<PyObject> stack,
                         Deque<ExceptHandler> exceptHandlers) {}

    // ─── Constructor ──────────────────────────────────────────────────────
    public Jython3Interpreter() {
        registerBuiltins();
    }

    /** Register built-in functions into globals. */
    private void registerBuiltins() {
        // len(obj)
        globals.put("len", new PyBuiltinFunction("len", args -> {
            checkArgCount("len", args, 1);
            PyObject o = args[0];
            if (o instanceof PyStr s) return new PyInt(s.value().length());
            if (o instanceof PyList l) return new PyInt(l.items().size());
            if (o instanceof PyDict d) return new PyInt(d.map().size());
            if (o instanceof PyInstance inst) {
                PyObject lenMethod = inst.getField("__len__");
                if (lenMethod != null) return callPyCallable(lenMethod, new PyObject[0]);
            }
            if (o instanceof JavaBridge.PyJavaObject jo) {
                if (jo.object() instanceof List<?> l) return new PyInt(l.size());
                if (jo.object() instanceof Map<?, ?> m) return new PyInt(m.size());
                if (jo.object().getClass().isArray()) return new PyInt(java.lang.reflect.Array.getLength(jo.object()));
            }
            throw new InterpreterException("TypeError: object of type '" + typeName(o) + "' has no len()");
        }));

        // str(obj)
        globals.put("str", new PyBuiltinFunction("str", args -> {
            checkArgCount("str", args, 1);
            return new PyStr(args[0].toString());
        }));

        // int(obj)
        globals.put("int", new PyBuiltinFunction("int", args -> {
            checkArgCount("int", args, 1);
            PyObject o = args[0];
            if (o instanceof PyInt) return o;
            if (o instanceof PyFloat f) return new PyInt((int) f.value());
            if (o instanceof PyBool b) return new PyInt(b.value() ? 1 : 0);
            if (o instanceof PyStr s) {
                try {
                    return new PyInt(Integer.parseInt(s.value().strip()));
                } catch (NumberFormatException e) {
                    throw new InterpreterException("ValueError: invalid literal for int(): '" + s.value() + "'");
                }
            }
            throw new InterpreterException("TypeError: int() argument must be a string or a number, not '" + typeName(o) + "'");
        }));

        // float(obj)
        globals.put("float", new PyBuiltinFunction("float", args -> {
            checkArgCount("float", args, 1);
            return new PyFloat(toDouble(args[0]));
        }));

        // bool(obj)
        globals.put("bool", new PyBuiltinFunction("bool", args -> {
            checkArgCount("bool", args, 1);
            return PyBool.of(isTruthy(args[0]));
        }));

        // type(obj)
        globals.put("type", new PyBuiltinFunction("type", args -> {
            checkArgCount("type", args, 1);
            return new PyStr("<class '" + typeName(args[0]) + "'>");
        }));

        // abs(x)
        globals.put("abs", new PyBuiltinFunction("abs", args -> {
            checkArgCount("abs", args, 1);
            PyObject o = args[0];
            if (o instanceof PyInt i) return new PyInt(Math.abs(i.value()));
            if (o instanceof PyFloat f) return new PyFloat(Math.abs(f.value()));
            throw new InterpreterException("TypeError: bad operand type for abs(): '" + typeName(o) + "'");
        }));

        // min(a, b, ...) or min(iterable)
        globals.put("min", new PyBuiltinFunction("min", args -> {
            List<PyObject> items = flattenArgs("min", args);
            if (items.isEmpty()) throw new InterpreterException("ValueError: min() arg is an empty sequence");
            PyObject m = items.get(0);
            for (int i = 1; i < items.size(); i++) {
                if (compareObjects(items.get(i), m) < 0) m = items.get(i);
            }
            return m;
        }));

        // max(a, b, ...) or max(iterable)
        globals.put("max", new PyBuiltinFunction("max", args -> {
            List<PyObject> items = flattenArgs("max", args);
            if (items.isEmpty()) throw new InterpreterException("ValueError: max() arg is an empty sequence");
            PyObject m = items.get(0);
            for (int i = 1; i < items.size(); i++) {
                if (compareObjects(items.get(i), m) > 0) m = items.get(i);
            }
            return m;
        }));

        // sum(iterable)
        globals.put("sum", new PyBuiltinFunction("sum", args -> {
            checkArgCount("sum", args, 1);
            if (!(args[0] instanceof PyList list)) throw new InterpreterException("TypeError: sum() argument must be iterable");
            double total = 0;
            boolean hasFloat = false;
            for (PyObject item : list.items()) {
                if (item instanceof PyInt i) total += i.value();
                else if (item instanceof PyFloat f) { total += f.value(); hasFloat = true; }
                else throw new InterpreterException("TypeError: unsupported operand type for sum()");
            }
            return hasFloat ? new PyFloat(total) : new PyInt((int) total);
        }));

        // list(iterable?)
        globals.put("list", new PyBuiltinFunction("list", args -> {
            if (args.length == 0) return new PyList(new ArrayList<>());
            checkArgCount("list", args, 1);
            PyObject o = args[0];
            if (o instanceof PyList l) return new PyList(new ArrayList<>(l.items()));
            if (o instanceof PyStr s) {
                var items = new ArrayList<PyObject>();
                for (char ch : s.value().toCharArray()) items.add(new PyStr(String.valueOf(ch)));
                return new PyList(items);
            }
            if (o instanceof PyDict d) return new PyList(new ArrayList<>(d.map().keySet()));
            throw new InterpreterException("TypeError: '" + typeName(o) + "' object is not iterable");
        }));

        // dict()
        globals.put("dict", new PyBuiltinFunction("dict", args -> {
            if (args.length == 0) return new PyDict(new LinkedHashMap<>());
            checkArgCount("dict", args, 1);
            if (args[0] instanceof PyDict d) return new PyDict(new LinkedHashMap<>(d.map()));
            throw new InterpreterException("TypeError: cannot convert argument to dict");
        }));

        // range(stop) or range(start, stop) or range(start, stop, step)
        globals.put("range", new PyBuiltinFunction("range", args -> {
            int start = 0, stop, step = 1;
            if (args.length == 1) {
                stop = toInt(args[0]);
            } else if (args.length == 2) {
                start = toInt(args[0]);
                stop = toInt(args[1]);
            } else if (args.length == 3) {
                start = toInt(args[0]);
                stop = toInt(args[1]);
                step = toInt(args[2]);
            } else {
                throw new InterpreterException("TypeError: range expected at most 3 arguments, got " + args.length);
            }
            if (step == 0) throw new InterpreterException("ValueError: range() arg 3 must not be zero");
            var items = new ArrayList<PyObject>();
            if (step > 0) {
                for (int i = start; i < stop; i += step) items.add(new PyInt(i));
            } else {
                for (int i = start; i > stop; i += step) items.add(new PyInt(i));
            }
            return new PyList(items);
        }));

        // sorted(iterable)
        globals.put("sorted", new PyBuiltinFunction("sorted", args -> {
            checkArgCount("sorted", args, 1);
            if (!(args[0] instanceof PyList list)) throw new InterpreterException("TypeError: sorted() argument must be iterable");
            var items = new ArrayList<>(list.items());
            items.sort(Jython3Interpreter::compareObjects);
            return new PyList(items);
        }));

        // reversed(iterable)
        globals.put("reversed", new PyBuiltinFunction("reversed", args -> {
            checkArgCount("reversed", args, 1);
            if (!(args[0] instanceof PyList list)) throw new InterpreterException("TypeError: reversed() argument must be iterable");
            var items = new ArrayList<>(list.items());
            Collections.reverse(items);
            return new PyList(items);
        }));

        // enumerate(iterable)
        globals.put("enumerate", new PyBuiltinFunction("enumerate", args -> {
            checkArgCount("enumerate", args, 1);
            if (!(args[0] instanceof PyList list)) throw new InterpreterException("TypeError: enumerate() argument must be iterable");
            var items = new ArrayList<PyObject>();
            for (int i = 0; i < list.items().size(); i++) {
                items.add(new PyList(List.of(new PyInt(i), list.items().get(i))));
            }
            return new PyList(items);
        }));

        // zip(a, b, ...)
        globals.put("zip", new PyBuiltinFunction("zip", args -> {
            if (args.length < 2) throw new InterpreterException("TypeError: zip() requires at least 2 arguments");
            List<List<PyObject>> lists = new ArrayList<>();
            int minLen = Integer.MAX_VALUE;
            for (PyObject a : args) {
                if (!(a instanceof PyList l)) throw new InterpreterException("TypeError: zip() argument must be iterable");
                lists.add(l.items());
                minLen = Math.min(minLen, l.items().size());
            }
            var result = new ArrayList<PyObject>();
            for (int i = 0; i < minLen; i++) {
                var tuple = new ArrayList<PyObject>();
                for (var l : lists) tuple.add(l.get(i));
                result.add(new PyList(tuple));
            }
            return new PyList(result);
        }));

        // map(func, iterable)
        globals.put("map", new PyBuiltinFunction("map", this::builtinMap));

        // filter(func, iterable)
        globals.put("filter", new PyBuiltinFunction("filter", this::builtinFilter));

        // isinstance(obj, type_name_or_class)
        globals.put("isinstance", new PyBuiltinFunction("isinstance", args -> {
            checkArgCount("isinstance", args, 2);
            PyObject obj = args[0];
            PyObject typeArg = args[1];
            if (typeArg instanceof PyClass cls && obj instanceof PyInstance inst) {
                return PyBool.of(inst.isInstanceOf(cls));
            }
            String tn = typeName(obj);
            String target = (typeArg instanceof PyStr s) ? s.value() : typeArg.toString();
            return PyBool.of(tn.equalsIgnoreCase(target));
        }));

        // repr(obj)
        globals.put("repr", new PyBuiltinFunction("repr", args -> {
            checkArgCount("repr", args, 1);
            PyObject o = args[0];
            if (o instanceof PyStr s) return new PyStr("'" + s.value() + "'");
            return new PyStr(o.toString());
        }));

        // hash(obj)
        globals.put("hash", new PyBuiltinFunction("hash", args -> {
            checkArgCount("hash", args, 1);
            return new PyInt(args[0].hashCode());
        }));

        // id(obj)
        globals.put("id", new PyBuiltinFunction("id", args -> {
            checkArgCount("id", args, 1);
            return new PyInt(System.identityHashCode(args[0]));
        }));

        // chr(i)
        globals.put("chr", new PyBuiltinFunction("chr", args -> {
            checkArgCount("chr", args, 1);
            int cp = toInt(args[0]);
            if (cp < 0 || cp > 0x10FFFF) throw new InterpreterException("ValueError: chr() arg not in range(0x110000)");
            return new PyStr(String.valueOf((char) cp));
        }));

        // ord(c)
        globals.put("ord", new PyBuiltinFunction("ord", args -> {
            checkArgCount("ord", args, 1);
            if (!(args[0] instanceof PyStr s) || s.value().length() != 1)
                throw new InterpreterException("TypeError: ord() expected a character");
            return new PyInt(s.value().charAt(0));
        }));

        // hex, oct, bin
        globals.put("hex", new PyBuiltinFunction("hex", args -> { checkArgCount("hex", args, 1); return new PyStr("0x" + Integer.toHexString(toInt(args[0]))); }));
        globals.put("oct", new PyBuiltinFunction("oct", args -> { checkArgCount("oct", args, 1); return new PyStr("0o" + Integer.toOctalString(toInt(args[0]))); }));
        globals.put("bin", new PyBuiltinFunction("bin", args -> { checkArgCount("bin", args, 1); return new PyStr("0b" + Integer.toBinaryString(toInt(args[0]))); }));

        // round(x, n?)
        globals.put("round", new PyBuiltinFunction("round", args -> {
            if (args.length < 1 || args.length > 2) throw new InterpreterException("TypeError: round() takes 1 or 2 arguments");
            double val = toDouble(args[0]);
            if (args.length == 1) return new PyInt((int) Math.round(val));
            int digits = toInt(args[1]);
            double factor = Math.pow(10, digits);
            return new PyFloat(Math.round(val * factor) / factor);
        }));

        // hasattr(obj, name)
        globals.put("hasattr", new PyBuiltinFunction("hasattr", args -> {
            checkArgCount("hasattr", args, 2);
            if (!(args[1] instanceof PyStr s)) throw new InterpreterException("TypeError: attribute name must be string");
            if (args[0] instanceof PyModule mod) return PyBool.of(mod.attrs().containsKey(s.value()));
            if (args[0] instanceof PyDict d) return PyBool.of(d.map().containsKey(new PyStr(s.value())));
            if (args[0] instanceof PyInstance inst) return PyBool.of(inst.hasField(s.value()));
            if (args[0] instanceof PyClass cls) return PyBool.of(cls.hasMember(s.value()));
            return PyBool.FALSE;
        }));

        // getattr(obj, name, default?)
        globals.put("getattr", new PyBuiltinFunction("getattr", args -> {
            if (args.length < 2 || args.length > 3) throw new InterpreterException("TypeError: getattr expected 2 or 3 arguments");
            if (!(args[1] instanceof PyStr s)) throw new InterpreterException("TypeError: attribute name must be string");
            if (args[0] instanceof PyModule mod) {
                PyObject val = mod.attrs().get(s.value());
                if (val != null) return val;
            }
            if (args[0] instanceof PyInstance inst) {
                try { return inst.getField(s.value()); }
                catch (InterpreterException ignored) {}
            }
            if (args.length == 3) return args[2];
            throw new InterpreterException("AttributeError: object has no attribute '" + s.value() + "'");
        }));

        // setattr(obj, name, val)
        globals.put("setattr", new PyBuiltinFunction("setattr", args -> {
            checkArgCount("setattr", args, 3);
            if (!(args[1] instanceof PyStr s)) throw new InterpreterException("TypeError: attribute name must be string");
            if (args[0] instanceof PyInstance inst) {
                inst.setField(s.value(), args[2]);
                return new PyNull();
            }
            if (args[0] instanceof PyModule mod) {
                mod.attrs().put(s.value(), args[2]);
                return new PyNull();
            }
            throw new InterpreterException("AttributeError: cannot set attribute on '" + typeName(args[0]) + "'");
        }));

        // input(prompt?)
        globals.put("input", new PyBuiltinFunction("input", args -> {
            if (args.length > 0 && args[0] instanceof PyStr s) System.out.print(s.value());
            try {
                var reader = new java.io.BufferedReader(new java.io.InputStreamReader(System.in));
                String line = reader.readLine();
                return line == null ? new PyStr("") : new PyStr(line);
            } catch (IOException e) {
                throw new InterpreterException("IOError: " + e.getMessage());
            }
        }));
    }

    // ─── Public API ───────────────────────────────────────────────────────

    public void exec(String source) {
        stack = new ArrayDeque<>();
        currentLocals = globals;
        exceptStack = new ArrayDeque<>();
        execCount++;

        try {
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
        } catch (InterpreterException e) {
            System.err.println("Traceback (most recent call last):");
            System.err.println("  File \"<stdin>\", line 1, in <module>");
            System.err.println("    " + source.strip());
            System.err.println(e.getMessage());
        }
    }

    public PyObject getGlobal(String name) {
        return globals.get(name);
    }

    public void setGlobal(String name, PyObject value) {
        globals.put(name, value);
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

    // ─── CodeBuilder ──────────────────────────────────────────────────────

    private static class CodeBuilder {
        private final List<Integer> bytecode = new ArrayList<>();
        private final List<Object> constPool = new ArrayList<>();
        private final Map<Object, Integer> constMap = new HashMap<>();

        int addConst(Object val) {
            if (val == null) { int i = constPool.size(); constPool.add(null); return i; }
            return constMap.computeIfAbsent(val, k -> { int i = constPool.size(); constPool.add(k); return i; });
        }
        void emit(OpCode op, Object cv) { bytecode.add((op.ordinal() << 16) | (addConst(cv) & 0xFFFF)); }
        void emitRaw(OpCode op, int raw) { bytecode.add((op.ordinal() << 16) | (raw & 0xFFFF)); }
        void emit(OpCode op)             { bytecode.add((op.ordinal() << 16) | NO_ARG); }
        int size()                       { return bytecode.size(); }
        void patch(int idx, int arg)     { int old = bytecode.get(idx); bytecode.set(idx, (old & 0xFFFF0000) | (arg & 0xFFFF)); }
        CompiledCode build()             { return new CompiledCode(bytecode.stream().mapToInt(Integer::intValue).toArray(), constPool.toArray()); }
    }

    // ─── CompileVisitor ───────────────────────────────────────────────────

    private class CompileVisitor extends Jython3BaseVisitor<Void> {
        private final CodeBuilder b;
        CompileVisitor(CodeBuilder b) { this.b = b; }

        @Override public Void visitStat(Jython3Parser.StatContext c) {
            for (var stmt : c.stmt()) {
                visit(stmt);
            }
            return null;
        }

        @Override public Void visitStmt(Jython3Parser.StmtContext c) {
            return visitChildren(c);
        }

        @Override public Void visitSuite(Jython3Parser.SuiteContext c) {
            if (c.simple_stmt() != null && !c.simple_stmt().isEmpty()) {
                for (var s : c.simple_stmt()) {
                    visit(s);
                }
            }
            if (c.stmt() != null && !c.stmt().isEmpty()) {
                for (var s : c.stmt()) {
                    visit(s);
                }
            }
            return null;
        }

        @Override public Void visitSimple_stmt(Jython3Parser.Simple_stmtContext c) {
            return visitChildren(c);
        }

        @Override public Void visitCompound_stmt(Jython3Parser.Compound_stmtContext c) {
            return visitChildren(c);
        }

        // ── print(args) ──
        @Override public Void visitPrint_stmt(Jython3Parser.Print_stmtContext c) {
            if (c.arg_list() == null || c.arg_list().expr().isEmpty()) {
                b.emit(OpCode.LOAD_CONST, "");
                b.emit(OpCode.PRINT);
            } else {
                var exprs = c.arg_list().expr();
                if (exprs.size() == 1) {
                    visit(exprs.get(0));
                    b.emit(OpCode.PRINT);
                } else {
                    for (var e : exprs) {
                        visit(e);
                    }
                    b.emitRaw(OpCode.BUILD_LIST, exprs.size());
                    b.emit(OpCode.PRINT_MULTI);
                }
            }
            return null;
        }

        @Override public Void visitAssign_stmt(Jython3Parser.Assign_stmtContext c) {
            visit(c.expr());
            b.emit(OpCode.STORE_FAST, c.ID().getText());
            return null;
        }

        @Override public Void visitSubscript_assign_stmt(Jython3Parser.Subscript_assign_stmtContext c) {
            if (c.LBRACK() != null) {
                // container[key] = expr
                visit(c.expr(0)); // container
                visit(c.expr(1)); // key/index
                visit(c.expr(2)); // value
                b.emit(OpCode.STORE_SUBSCR);
            } else {
                // obj.attr = expr
                visit(c.expr(0)); // obj
                visit(c.expr(1)); // value
                b.emit(OpCode.STORE_ATTR, c.ID().getText());
            }
            return null;
        }

        @Override public Void visitAug_assign_stmt(Jython3Parser.Aug_assign_stmtContext c) {
            String name = c.ID().getText();
            b.emit(OpCode.LOAD_FAST, name);
            visit(c.expr());
            String opText = c.aug_op().getText();
            switch (opText) {
                case "+="  -> b.emit(OpCode.ADD);
                case "-="  -> b.emit(OpCode.SUBTRACT);
                case "*="  -> b.emit(OpCode.MULTIPLY);
                case "/="  -> b.emit(OpCode.DIVIDE);
                case "//=" -> b.emit(OpCode.FLOOR_DIVIDE);
                case "%="  -> b.emit(OpCode.MODULO);
                case "**=" -> b.emit(OpCode.POWER);
            }
            b.emit(OpCode.STORE_FAST, name);
            return null;
        }

        @Override public Void visitImport_stmt(Jython3Parser.Import_stmtContext c) {
            if (c.IMPORT() != null && c.FROM() == null) {
                // import dotted_as_name, ...
                for (var d : c.dotted_as_name()) {
                    String modName = d.dotted_name().getText();
                    String alias = d.ID() != null ? d.ID().getText() : null;
                    b.emit(OpCode.IMPORT_MODULE, alias != null ? modName + ":" + alias : modName);
                }
            } else if (c.FROM() != null) {
                // from dotted_name import ...
                String modName = c.dotted_name().getText();
                if (c.MUL() != null) {
                    b.emit(OpCode.IMPORT_MODULE, "from:" + modName + ":*");
                } else {
                    for (var it : c.import_as_name()) {
                        String name = it.ID(0).getText();
                        String alias = it.ID().size() > 1 ? it.ID(1).getText() : name;
                        b.emit(OpCode.IMPORT_MODULE, "from:" + modName + ":" + name + ":" + alias);
                    }
                }
            }
            return null;
        }

        @Override public Void visitReturn_stmt(Jython3Parser.Return_stmtContext c) {
            if (c.expr() != null) {
                visit(c.expr());
            } else {
                b.emit(OpCode.LOAD_CONST, null);
            }
            b.emit(OpCode.RETURN_VALUE);
            return null;
        }

        @Override public Void visitDel_stmt(Jython3Parser.Del_stmtContext c) {
            if (c.ID() != null) {
                b.emit(OpCode.DELETE_FAST, c.ID().getText());
            } else {
                visit(c.expr(0)); // container
                visit(c.expr(1)); // key
                b.emit(OpCode.DELETE_SUBSCR);
            }
            return null;
        }

        @Override public Void visitPass_stmt(Jython3Parser.Pass_stmtContext c) {
            return null;
        }

        @Override public Void visitBreak_stmt(Jython3Parser.Break_stmtContext c) {
            b.emit(OpCode.HALT);
            return null;
        }

        @Override public Void visitContinue_stmt(Jython3Parser.Continue_stmtContext c) {
            return null;
        }

        @Override public Void visitCall_stmt(Jython3Parser.Call_stmtContext c) {
            if (c.expr() != null) {
                visit(c.expr());
                b.emit(OpCode.LOAD_ATTR, c.ID().getText());
                int n = 0;
                if (c.arg_list() != null) {
                    for (var e : c.arg_list().expr()) { visit(e); n++; }
                }
                b.emitRaw(OpCode.CALL_FUNCTION, n);
            } else {
                b.emit(OpCode.LOAD_FAST, c.ID().getText());
                int n = 0;
                if (c.arg_list() != null) {
                    for (var e : c.arg_list().expr()) { visit(e); n++; }
                }
                b.emitRaw(OpCode.CALL_FUNCTION, n);
            }
            b.emit(OpCode.POP_TOP);
            return null;
        }

        @Override public Void visitFunc_def(Jython3Parser.Func_defContext c) {
            String name = c.ID().getText();
            var params = new ArrayList<String>();
            if (c.param_list() != null) {
                for (var id : c.param_list().ID()) params.add(id.getText());
            }
            var fb = new CodeBuilder();
            var fv = new CompileVisitor(fb);
            fv.visit(c.suite());
            fb.emit(OpCode.LOAD_CONST, null);
            fb.emit(OpCode.RETURN_VALUE);
            fb.emit(OpCode.HALT);
            b.emit(OpCode.LOAD_CONST, new PyFunction(name, params, fb.build()));
            b.emit(OpCode.MAKE_FUNCTION, name);
            return null;
        }

        @Override public Void visitClass_def(Jython3Parser.Class_defContext c) {
            String className = c.ID(0).getText();
            String baseName = c.ID().size() > 1 ? c.ID(1).getText() : null;

            var cb = new CodeBuilder();
            var cv = new CompileVisitor(cb);
            cv.visit(c.suite());
            cb.emit(OpCode.HALT);

            if (baseName != null) {
                b.emit(OpCode.LOAD_FAST, baseName);
            } else {
                b.emit(OpCode.LOAD_CONST, null);
            }
            b.emit(OpCode.LOAD_CONST, cb.build());
            b.emit(OpCode.BUILD_CLASS, className);
            return null;
        }

        @Override public Void visitIf_stmt(Jython3Parser.If_stmtContext c) {
            var jumpToEndPatches = new ArrayList<Integer>();

            // Main IF
            visit(c.expr(0));
            int jumpNext = b.size();
            b.emitRaw(OpCode.JUMP_IF_FALSE, 0);
            visit(c.suite(0));
            int jumpEnd = b.size();
            b.emitRaw(OpCode.JUMP, 0);
            jumpToEndPatches.add(jumpEnd);
            b.patch(jumpNext, b.size());

            // ELIF clauses
            int elifCount = c.ELIF().size();
            for (int i = 0; i < elifCount; i++) {
                visit(c.expr(i + 1));
                jumpNext = b.size();
                b.emitRaw(OpCode.JUMP_IF_FALSE, 0);
                visit(c.suite(i + 1));
                jumpEnd = b.size();
                b.emitRaw(OpCode.JUMP, 0);
                jumpToEndPatches.add(jumpEnd);
                b.patch(jumpNext, b.size());
            }

            // ELSE clause
            if (c.ELSE() != null) {
                int elseIdx = 1 + elifCount;
                visit(c.suite(elseIdx));
            }

            for (int patchIdx : jumpToEndPatches) {
                b.patch(patchIdx, b.size());
            }
            return null;
        }

        @Override public Void visitWhile_stmt(Jython3Parser.While_stmtContext c) {
            int loopStart = b.size();
            visit(c.expr());
            int jumpFalse = b.size();
            b.emitRaw(OpCode.JUMP_IF_FALSE, 0);
            visit(c.suite());
            b.emitRaw(OpCode.JUMP, loopStart);
            b.patch(jumpFalse, b.size());
            return null;
        }

        @Override public Void visitFor_stmt(Jython3Parser.For_stmtContext c) {
            visit(c.expr());
            b.emit(OpCode.GET_ITER);
            int loopTarget = b.size();
            int forIter = b.size();
            b.emitRaw(OpCode.FOR_ITER, 0);
            b.emit(OpCode.STORE_FAST, c.ID().getText());
            visit(c.suite());
            b.emitRaw(OpCode.JUMP, loopTarget);
            b.patch(forIter, b.size());
            return null;
        }

        @Override public Void visitTry_stmt(Jython3Parser.Try_stmtContext c) {
            // Supports: try: suite (except [Type [as var]]: suite)+ [else: suite] [finally: suite]
            var exceptClauses = c.except_clause();

            int pushIdx = b.size();
            b.emitRaw(OpCode.PUSH_EXCEPT_HANDLER, 0);   // placeholder → except handler pc
            visit(c.suite(0));                            // try body
            b.emit(OpCode.POP_EXCEPT_HANDLER);
            int jumpOverIdx = b.size();
            b.emitRaw(OpCode.JUMP, 0);                   // jump over all except blocks

            // Patch PUSH_EXCEPT_HANDLER to point here (exception handler start)
            b.patch(pushIdx, b.size());

            // Handle first except clause (stack machine supports one handler per try block)
            if (!exceptClauses.isEmpty()) {
                var clause = exceptClauses.get(0);
                // Stack top is the exception message string
                if (clause.AS() != null && clause.ID() != null) {
                    // except ExcType as varName: — store exception message in var
                    String varName = clause.ID().getText();
                    b.emit(OpCode.STORE_FAST, varName);
                } else {
                    b.emit(OpCode.POP_TOP);   // discard exception string
                }
                visit(clause.suite());  // except body is inside except_clause, not try_stmt
            }

            // Patch the jump-over to skip past all except bodies
            b.patch(jumpOverIdx, b.size());

            // Handle finally if present (runs after try+except, always)
            if (c.FINALLY() != null) {
                // finally suite is the last suite() in try_stmt (after else if present)
                var suites = c.suite();
                visit(suites.get(suites.size() - 1));
            }

            return null;
        }

        @Override public Void visitExcept_clause(Jython3Parser.Except_clauseContext c) {
            // Handled inside visitTry_stmt — must not be called independently
            return null;
        }

        // ── Expressions ──

        @Override public Void visitPower(Jython3Parser.PowerContext c) {
            visit(c.expr(0)); visit(c.expr(1)); b.emit(OpCode.POWER); return null;
        }

        @Override public Void visitUnaryMinus(Jython3Parser.UnaryMinusContext c) {
            visit(c.expr()); b.emit(OpCode.UNARY_MINUS); return null;
        }

        @Override public Void visitNotExpr(Jython3Parser.NotExprContext c) {
            visit(c.expr()); b.emit(OpCode.LOGICAL_NOT); return null;
        }

        @Override public Void visitMulDivMod(Jython3Parser.MulDivModContext c) {
            visit(c.expr(0));
            visit(c.expr(1));
            String opText = c.op.getText();
            switch (opText) {
                case "*"  -> b.emit(OpCode.MULTIPLY);
                case "/"  -> b.emit(OpCode.DIVIDE);
                case "//" -> b.emit(OpCode.FLOOR_DIVIDE);
                case "%"  -> b.emit(OpCode.MODULO);
            }
            return null;
        }

        @Override public Void visitAddSub(Jython3Parser.AddSubContext c) {
            visit(c.expr(0));
            visit(c.expr(1));
            b.emit(c.op.getText().equals("+") ? OpCode.ADD : OpCode.SUBTRACT);
            return null;
        }

        @Override public Void visitComparison(Jython3Parser.ComparisonContext c) {
            visit(c.expr(0));
            visit(c.expr(1));
            String opText = c.op.getText();
            switch (opText) {
                case "==" -> b.emit(OpCode.COMPARE_EQ);
                case "!=" -> b.emit(OpCode.COMPARE_NEQ);
                case "<"  -> b.emit(OpCode.COMPARE_LT);
                case ">"  -> b.emit(OpCode.COMPARE_GT);
                case "<=" -> b.emit(OpCode.COMPARE_LTE);
                case ">=" -> b.emit(OpCode.COMPARE_GTE);
            }
            return null;
        }

        @Override public Void visitInExpr(Jython3Parser.InExprContext c) {
            visit(c.expr(0)); visit(c.expr(1)); b.emit(OpCode.COMPARE_IN); return null;
        }

        @Override public Void visitNotInExpr(Jython3Parser.NotInExprContext c) {
            visit(c.expr(0)); visit(c.expr(1)); b.emit(OpCode.COMPARE_NOT_IN); return null;
        }

        @Override public Void visitIsExpr(Jython3Parser.IsExprContext c) {
            visit(c.expr(0)); visit(c.expr(1)); b.emit(OpCode.COMPARE_IS); return null;
        }

        @Override public Void visitIsNotExpr(Jython3Parser.IsNotExprContext c) {
            visit(c.expr(0)); visit(c.expr(1)); b.emit(OpCode.COMPARE_IS_NOT); return null;
        }

        @Override public Void visitAndExpr(Jython3Parser.AndExprContext c) {
            visit(c.expr(0));
            int jump = b.size();
            b.emitRaw(OpCode.JUMP_IF_FALSE_OR_POP, 0);
            visit(c.expr(1));
            b.patch(jump, b.size());
            return null;
        }

        @Override public Void visitOrExpr(Jython3Parser.OrExprContext c) {
            visit(c.expr(0));
            int jump = b.size();
            b.emitRaw(OpCode.JUMP_IF_TRUE_OR_POP, 0);
            visit(c.expr(1));
            b.patch(jump, b.size());
            return null;
        }

        @Override public Void visitParen(Jython3Parser.ParenContext c) { visit(c.expr()); return null; }
        @Override public Void visitInt(Jython3Parser.IntContext c) { b.emit(OpCode.LOAD_CONST, Integer.valueOf(c.INT().getText())); return null; }
        @Override public Void visitFloat(Jython3Parser.FloatContext c) { b.emit(OpCode.LOAD_CONST, Double.valueOf(c.FLOAT().getText())); return null; }
        @Override public Void visitStr(Jython3Parser.StrContext c) {
            String raw = c.STRING().getText();
            String unquoted;
            if (raw.startsWith("'''") || raw.startsWith("\"\"\"")) {
                unquoted = raw.substring(3, raw.length() - 3);
            } else {
                unquoted = raw.substring(1, raw.length() - 1);
            }
            b.emit(OpCode.LOAD_CONST, unescapeString(unquoted));
            return null;
        }
        @Override public Void visitTrueLit(Jython3Parser.TrueLitContext c) { b.emit(OpCode.LOAD_CONST, Boolean.TRUE); return null; }
        @Override public Void visitFalseLit(Jython3Parser.FalseLitContext c) { b.emit(OpCode.LOAD_CONST, Boolean.FALSE); return null; }
        @Override public Void visitNoneLit(Jython3Parser.NoneLitContext c) { b.emit(OpCode.LOAD_CONST, null); return null; }
        @Override public Void visitId(Jython3Parser.IdContext c) { b.emit(OpCode.LOAD_FAST, c.ID().getText()); return null; }

        @Override public Void visitFuncCall(Jython3Parser.FuncCallContext c) {
            b.emit(OpCode.LOAD_FAST, c.ID().getText());
            int n = 0;
            if (c.arg_list() != null) {
                for (var e : c.arg_list().expr()) { visit(e); n++; }
            }
            b.emitRaw(OpCode.CALL_FUNCTION, n);
            return null;
        }

        @Override public Void visitListLiteral(Jython3Parser.ListLiteralContext c) {
            int n = 0;
            if (c.expr_list() != null) {
                for (var e : c.expr_list().expr()) { visit(e); n++; }
            }
            b.emitRaw(OpCode.BUILD_LIST, n);
            return null;
        }

        @Override public Void visitDictLiteral(Jython3Parser.DictLiteralContext c) {
            int n = 0;
            if (c.dict_entries() != null) {
                for (var entry : c.dict_entries().dict_entry()) {
                    visit(entry.expr(0)); // key
                    visit(entry.expr(1)); // value
                    n++;
                }
            }
            b.emitRaw(OpCode.BUILD_DICT, n);
            return null;
        }

        @Override public Void visitSubscript(Jython3Parser.SubscriptContext c) {
            visit(c.expr(0));
            visit(c.expr(1));
            b.emit(OpCode.BINARY_SUBSCR);
            return null;
        }

        @Override public Void visitAttrAccess(Jython3Parser.AttrAccessContext c) {
            visit(c.expr());
            b.emit(OpCode.LOAD_ATTR, c.ID().getText());
            return null;
        }

        @Override public Void visitAttrCall(Jython3Parser.AttrCallContext c) {
            visit(c.expr());
            b.emit(OpCode.LOAD_ATTR, c.ID().getText());
            int n = 0;
            if (c.arg_list() != null) {
                for (var e : c.arg_list().expr()) { visit(e); n++; }
            }
            b.emitRaw(OpCode.CALL_FUNCTION, n);
            return null;
        }

        @Override public Void visitRaise_stmt(Jython3Parser.Raise_stmtContext c) {
            if (c.ID() == null) {
                b.emit(OpCode.LOAD_CONST, "");
                b.emit(OpCode.RAISE, "RuntimeError");
                return null;
            }
            if (c.arg_list() != null && !c.arg_list().expr().isEmpty()) {
                visit(c.arg_list().expr().get(0));
            } else {
                b.emit(OpCode.LOAD_CONST, "");
            }
            b.emit(OpCode.RAISE, c.ID().getText());
            return null;
        }
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

            try { switch (op) {
                case LOAD_CONST -> {
                    if (ai == NO_ARG) { stack.push(new PyNull()); break; }
                    Object v = pool[ai];
                    if (v == null) stack.push(new PyNull());
                    else if (v instanceof String s) stack.push(new PyStr(s));
                    else if (v instanceof Integer i) stack.push(new PyInt(i));
                    else if (v instanceof Double d) stack.push(new PyFloat(d));
                    else if (v instanceof Boolean b) stack.push(PyBool.of(b));
                    else if (v instanceof PyFunction f) stack.push(f);
                    else if (v instanceof CompiledCode cc) stack.push(new PyCompiledCodeWrapper(cc));
                    else throw new InterpreterException("Unknown const: " + v);
                }
                case LOAD_FAST -> {
                    String n = (String) pool[ai];
                    PyObject v = currentLocals.get(n);
                    if (v == null) v = globals.get(n);
                    if (v == null) throw new InterpreterException("NameError: name '" + n + "' is not defined");
                    stack.push(v);
                }
                case LOAD_GLOBAL -> {
                    String n = (String) pool[ai];
                    PyObject v = globals.get(n);
                    if (v == null) throw new InterpreterException("NameError: name '" + n + "' is not defined");
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
                            throw new InterpreterException("IndexError: list assignment index out of range");
                        list.items().set(i, val);
                    } else if (container instanceof PyDict dict) {
                        dict.map().put(key, val);
                    } else {
                        throw new InterpreterException("TypeError: '" + typeName(container) + "' object does not support item assignment");
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
                    } else if (obj instanceof JavaBridge.PyJavaObject jo) {
                        jo.setMember(attr, val);
                    } else {
                        throw new InterpreterException("AttributeError: cannot set attribute on '" + typeName(obj) + "'");
                    }
                }
                case DELETE_FAST -> {
                    String n = (String) pool[ai];
                    if (currentLocals.remove(n) == null && globals.remove(n) == null) {
                        throw new InterpreterException("NameError: name '" + n + "' is not defined");
                    }
                }
                case DELETE_SUBSCR -> {
                    PyObject key = stack.pop();
                    PyObject container = stack.pop();
                    if (container instanceof PyList list && key instanceof PyInt ix) {
                        int i = ix.value();
                        if (i < 0) i += list.items().size();
                        if (i < 0 || i >= list.items().size())
                            throw new InterpreterException("IndexError: list index out of range");
                        list.items().remove(i);
                    } else if (container instanceof PyDict dict) {
                        if (dict.map().remove(key) == null)
                            throw new InterpreterException("KeyError: " + key);
                    } else {
                        throw new InterpreterException("TypeError: '" + typeName(container) + "' object doesn't support item deletion");
                    }
                }

                // ── Arithmetic ──
                case ADD -> binaryAdd();
                case SUBTRACT -> binarySubtract();
                case MULTIPLY -> binaryMultiply();
                case DIVIDE -> {
                    var r = stack.pop(); var l = stack.pop();
                    if (l instanceof PyInt li && r instanceof PyInt ri) {
                        if (ri.value() == 0) throw new InterpreterException("ZeroDivisionError: division by zero");
                        if (li.value() % ri.value() == 0) {
                            stack.push(new PyInt(li.value() / ri.value()));
                        } else {
                            stack.push(new PyFloat((double) li.value() / ri.value()));
                        }
                    } else if (isNumeric(l) && isNumeric(r)) {
                        double rv = toDouble(r);
                        if (rv == 0) throw new InterpreterException("ZeroDivisionError: division by zero");
                        stack.push(new PyFloat(toDouble(l) / rv));
                    } else {
                        throw new InterpreterException("TypeError: unsupported operand types for /");
                    }
                }
                case FLOOR_DIVIDE -> {
                    var r = stack.pop(); var l = stack.pop();
                    if (l instanceof PyInt li && r instanceof PyInt ri) {
                        if (ri.value() == 0) throw new InterpreterException("ZeroDivisionError: integer division or modulo by zero");
                        stack.push(new PyInt(Math.floorDiv(li.value(), ri.value())));
                    } else if (isNumeric(l) && isNumeric(r)) {
                        double rv = toDouble(r);
                        if (rv == 0) throw new InterpreterException("ZeroDivisionError: float floor division by zero");
                        stack.push(new PyFloat(Math.floor(toDouble(l) / rv)));
                    } else {
                        throw new InterpreterException("TypeError: unsupported operand types for //");
                    }
                }
                case MODULO -> {
                    var r = stack.pop(); var l = stack.pop();
                    if (l instanceof PyStr fmt) {
                        stack.push(new PyStr(pyStringFormat(fmt.value(), r)));
                    } else if (l instanceof PyInt li && r instanceof PyInt ri) {
                        if (ri.value() == 0) throw new InterpreterException("ZeroDivisionError: integer modulo by zero");
                        stack.push(new PyInt(Math.floorMod(li.value(), ri.value())));
                    } else if (isNumeric(l) && isNumeric(r)) {
                        double rv = toDouble(r), lv = toDouble(l);
                        if (rv == 0) throw new InterpreterException("ZeroDivisionError: float modulo by zero");
                        stack.push(new PyFloat(lv - Math.floor(lv / rv) * rv));
                    } else {
                        throw new InterpreterException("TypeError: unsupported operand types for %");
                    }
                }
                case POWER -> {
                    var r = stack.pop(); var l = stack.pop();
                    if (l instanceof PyInt li && r instanceof PyInt ri && ri.value() >= 0) {
                        stack.push(new PyInt((int) Math.pow(li.value(), ri.value())));
                    } else if (isNumeric(l) && isNumeric(r)) {
                        stack.push(new PyFloat(Math.pow(toDouble(l), toDouble(r))));
                    } else {
                        throw new InterpreterException("TypeError: unsupported operand types for **");
                    }
                }
                case UNARY_MINUS -> {
                    var v = stack.pop();
                    if (v instanceof PyInt i) stack.push(new PyInt(-i.value()));
                    else if (v instanceof PyFloat f) stack.push(new PyFloat(-f.value()));
                    else throw new InterpreterException("TypeError: bad operand type for unary -: '" + typeName(v) + "'");
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
                case MAKE_FUNCTION -> currentLocals.put((String) pool[ai], stack.pop());
                case BUILD_CLASS -> {
                    String className = (String) pool[ai];
                    PyCompiledCodeWrapper codeWrap = (PyCompiledCodeWrapper) stack.pop();
                    PyObject baseObj = stack.pop();
                    PyClass baseClass = (baseObj instanceof PyClass cls) ? cls : null;

                    // Execute class suite in an isolated scope to collect members
                    var classLocals = new HashMap<String, PyObject>();
                    var savedLocals = currentLocals;
                    var savedStack = stack;
                    currentLocals = classLocals;
                    stack = new ArrayDeque<>();
                    run(codeWrap.code());
                    currentLocals = savedLocals;
                    stack = savedStack;

                    var pyClass = new PyClass(className, baseClass, classLocals);
                    currentLocals.put(className, pyClass);
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
                    } else if (callable instanceof PyClass cls) {
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
                    } else if (callable instanceof PyFunction fn) {
                        if (callStack.size() >= MAX_CALL_DEPTH) {
                            throw new InterpreterException("RecursionError: maximum recursion depth exceeded");
                        }
                        callStack.push(new Frame(code, pc + 1, currentLocals, stack, exceptStack));
                        var fl = new HashMap<String, PyObject>();
                        for (int i = 0; i < fn.params().size() && i < argc; i++) {
                            fl.put(fn.params().get(i), args[i]);
                        }
                        currentLocals = fl;
                        stack = new ArrayDeque<>();
                        exceptStack = new ArrayDeque<>();
                        code = fn.code();
                        bc = code.bytecode();
                        pool = code.constPool();
                        pc = 0;
                        continue outer;
                    } else if (callable instanceof PyBuiltinFunction bf) {
                        stack.push(bf.fn().apply(args));
                    } else if (callable instanceof JavaBridge.PyJavaClass jc) {
                        stack.push(jc.newInstance(args));
                    } else if (callable instanceof JavaBridge.PyJavaMethod jm) {
                        stack.push(jm.invoke(args));
                    } else {
                        throw new InterpreterException("TypeError: '" + callable + "' is not callable");
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
                            throw new InterpreterException("IndexError: list index out of range");
                        stack.push(list.items().get(i));
                    } else if (ctr instanceof PyDict dict) {
                        PyObject val = dict.map().get(idx);
                        if (val == null) throw new InterpreterException("KeyError: " + idx);
                        stack.push(val);
                    } else if (ctr instanceof PyStr s && idx instanceof PyInt ix) {
                        int i = ix.value();
                        if (i < 0) i += s.value().length();
                        if (i < 0 || i >= s.value().length())
                            throw new InterpreterException("IndexError: string index out of range");
                        stack.push(new PyStr(String.valueOf(s.value().charAt(i))));
                    } else if (ctr instanceof PyInstance inst) {
                        PyObject getitem = inst.getField("__getitem__");
                        if (getitem != null) {
                            stack.push(callPyCallable(getitem, new PyObject[]{idx}));
                        } else {
                            throw new InterpreterException("TypeError: '" + typeName(ctr) + "' object is not subscriptable");
                        }
                    } else if (ctr instanceof JavaBridge.PyJavaObject jo) {
                        Object jObj = jo.object();
                        if (jObj instanceof java.util.List<?> l) {
                            if (idx instanceof PyInt ix) {
                                int i = ix.value();
                                if (i < 0) i += l.size();
                                if (i < 0 || i >= l.size())
                                    throw new InterpreterException("IndexError: Java list index out of range");
                                stack.push(JavaBridge.toPyObject(l.get(i)));
                            } else {
                                throw new InterpreterException("TypeError: Java list indices must be integers");
                            }
                        } else if (jObj instanceof java.util.Map<?,?> m) {
                            Object key = JavaBridge.toJavaObject(idx, Object.class);
                            Object val = m.get(key);
                            if (val == null && !m.containsKey(key))
                                throw new InterpreterException("KeyError: " + idx);
                            stack.push(JavaBridge.toPyObject(val));
                        } else if (jObj.getClass().isArray()) {
                            if (idx instanceof PyInt ix) {
                                int i = ix.value();
                                int len = java.lang.reflect.Array.getLength(jObj);
                                if (i < 0) i += len;
                                if (i < 0 || i >= len)
                                    throw new InterpreterException("IndexError: Java array index out of range");
                                stack.push(JavaBridge.toPyObject(java.lang.reflect.Array.get(jObj, i)));
                            } else {
                                throw new InterpreterException("TypeError: Java array indices must be integers");
                            }
                        } else {
                            throw new InterpreterException("TypeError: Java object '" + jObj.getClass().getName() + "' is not subscriptable");
                        }
                    } else {
                        throw new InterpreterException("TypeError: unsupported subscript types");
                    }
                }
                case GET_ITER -> {
                    PyObject o = stack.pop();
                    if (o instanceof PyList list) stack.push(new PyIterator(list.items(), 0));
                    else if (o instanceof PyStr s) {
                        var chars = new ArrayList<PyObject>();
                        for (char ch : s.value().toCharArray()) chars.add(new PyStr(String.valueOf(ch)));
                        stack.push(new PyIterator(chars, 0));
                    } else if (o instanceof PyDict d) {
                        stack.push(new PyIterator(new ArrayList<>(d.map().keySet()), 0));
                    } else if (o instanceof JavaBridge.PyJavaObject jo && jo.object() instanceof Iterable<?> iterable) {
                        var items = new ArrayList<PyObject>();
                        for (Object item : iterable) items.add(JavaBridge.toPyObject(item));
                        stack.push(new PyIterator(items, 0));
                    } else {
                        throw new InterpreterException("TypeError: '" + typeName(o) + "' is not iterable");
                    }
                }
                case FOR_ITER -> {
                    PyObject top = stack.peek();
                    if (top instanceof PyIterator iter) {
                        if (iter.hasNext()) stack.push(iter.next());
                        else { stack.pop(); pc = ai; continue; }
                    } else {
                        throw new InterpreterException("TypeError: expected iterator");
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
                        if (val == null) throw new InterpreterException("AttributeError: type object '" + cls.name() + "' has no attribute '" + attr + "'");
                        stack.push(val);
                    } else if (obj instanceof PyModule mod) {
                        PyObject val = mod.attrs().get(attr);
                        if (val == null) throw new InterpreterException("AttributeError: module '" + mod.name() + "' has no attribute '" + attr + "'");
                        stack.push(val);
                    } else if (obj instanceof PyDict dict) {
                        stack.push(dictMethod(dict, attr));
                    } else if (obj instanceof PyList list) {
                        stack.push(listMethod(list, attr));
                    } else if (obj instanceof PyStr s) {
                        stack.push(strMethod(s, attr));
                    } else if (obj instanceof JavaBridge.PyJavaObject jo) {
                        stack.push(jo.getMember(attr));
                    } else if (obj instanceof JavaBridge.PyJavaClass jc) {
                        stack.push(jc.getMember(attr));
                    } else if (obj instanceof JavaBridge.PyJavaPackage jp) {
                        stack.push(jp.getMember(attr));
                    } else {
                        throw new InterpreterException("AttributeError: '" + typeName(obj) + "' object has no attribute '" + attr + "'");
                    }
                }

                // ── Exception handling ──
                case PUSH_EXCEPT_HANDLER -> exceptStack.push(new ExceptHandler(ai));
                case POP_EXCEPT_HANDLER  -> { if (!exceptStack.isEmpty()) exceptStack.pop(); }
                case RAISE -> {
                    PyObject msgObj = stack.pop();
                    String excType = (String) pool[ai];
                    String msg = (msgObj instanceof PyStr s) ? s.value() : msgObj.toString();
                    throw new InterpreterException(excType + ": " + msg);
                }
                case HALT -> { return; }
            } // end switch
            } catch (InterpreterException ie) {
                if (!exceptStack.isEmpty()) {
                    ExceptHandler h = exceptStack.pop();
                    stack.push(new PyStr(ie.getMessage()));
                    pc = h.exceptPc();
                    continue outer;
                }
                while (!callStack.isEmpty()) {
                    Frame cf = callStack.pop();
                    code = cf.code();
                    bc = code.bytecode();
                    pool = code.constPool();
                    pc = cf.returnPc();
                    currentLocals = cf.locals();
                    stack = cf.stack();
                    exceptStack = cf.exceptHandlers();
                    if (!exceptStack.isEmpty()) {
                        ExceptHandler h = exceptStack.pop();
                        stack.push(new PyStr(ie.getMessage()));
                        pc = h.exceptPc();
                        continue outer;
                    }
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
            stack.push(new PyInt(li.value() + ri.value()));
        } else if (isNumeric(l) && isNumeric(r)) {
            stack.push(new PyFloat(toDouble(l) + toDouble(r)));
        } else {
            throw new InterpreterException("TypeError: unsupported operand types for arithmetic");
        }
    }

    private void binarySubtract() {
        var r = stack.pop(); var l = stack.pop();
        if (l instanceof PyInt li && r instanceof PyInt ri) {
            stack.push(new PyInt(li.value() - ri.value()));
        } else if (isNumeric(l) && isNumeric(r)) {
            stack.push(new PyFloat(toDouble(l) - toDouble(r)));
        } else {
            throw new InterpreterException("TypeError: unsupported operand types for arithmetic");
        }
    }

    private void binaryMultiply() {
        var r = stack.pop(); var l = stack.pop();
        if (l instanceof PyStr s && r instanceof PyInt n) {
            stack.push(new PyStr(s.value().repeat(Math.max(0, n.value()))));
        } else if (l instanceof PyInt n && r instanceof PyStr s) {
            stack.push(new PyStr(s.value().repeat(Math.max(0, n.value()))));
        } else if (l instanceof PyList ll && r instanceof PyInt n) {
            var items = new ArrayList<PyObject>();
            for (int i = 0; i < Math.max(0, n.value()); i++) items.addAll(ll.items());
            stack.push(new PyList(items));
        } else if (l instanceof PyInt li && r instanceof PyInt ri) {
            stack.push(new PyInt(li.value() * ri.value()));
        } else if (isNumeric(l) && isNumeric(r)) {
            stack.push(new PyFloat(toDouble(l) * toDouble(r)));
        } else {
            throw new InterpreterException("TypeError: unsupported operand types for arithmetic");
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
                if (!(args[0] instanceof PyList list)) throw new InterpreterException("TypeError: join() argument must be iterable");
                var sb = new StringBuilder();
                for (int i = 0; i < list.items().size(); i++) {
                    if (i > 0) sb.append(s.value());
                    sb.append(list.items().get(i).toString());
                }
                return new PyStr(sb.toString());
            });
            case "replace" -> new PyBuiltinFunction("str.replace", args -> {
                if (args.length < 2) throw new InterpreterException("TypeError: replace() takes at least 2 arguments");
                if (!(args[0] instanceof PyStr old) || !(args[1] instanceof PyStr rep))
                    throw new InterpreterException("TypeError: replace() arguments must be strings");
                return new PyStr(s.value().replace(old.value(), rep.value()));
            });
            case "startswith" -> new PyBuiltinFunction("str.startswith", args -> {
                checkArgCount("str.startswith", args, 1);
                if (!(args[0] instanceof PyStr prefix)) throw new InterpreterException("TypeError: startswith() argument must be str");
                return PyBool.of(s.value().startsWith(prefix.value()));
            });
            case "endswith" -> new PyBuiltinFunction("str.endswith", args -> {
                checkArgCount("str.endswith", args, 1);
                if (!(args[0] instanceof PyStr suffix)) throw new InterpreterException("TypeError: endswith() argument must be str");
                return PyBool.of(s.value().endsWith(suffix.value()));
            });
            case "find" -> new PyBuiltinFunction("str.find", args -> {
                checkArgCount("str.find", args, 1);
                if (!(args[0] instanceof PyStr sub)) throw new InterpreterException("TypeError: find() argument must be str");
                return new PyInt(s.value().indexOf(sub.value()));
            });
            case "count" -> new PyBuiltinFunction("str.count", args -> {
                checkArgCount("str.count", args, 1);
                if (!(args[0] instanceof PyStr sub)) throw new InterpreterException("TypeError: count() argument must be str");
                int count = 0; int idx = 0;
                while ((idx = s.value().indexOf(sub.value(), idx)) != -1) { count++; idx += sub.value().length(); }
                return new PyInt(count);
            });
            case "isdigit" -> new PyBuiltinFunction("str.isdigit", args -> PyBool.of(!s.value().isEmpty() && s.value().chars().allMatch(Character::isDigit)));
            case "isalpha" -> new PyBuiltinFunction("str.isalpha", args -> PyBool.of(!s.value().isEmpty() && s.value().chars().allMatch(Character::isLetter)));
            case "isalnum" -> new PyBuiltinFunction("str.isalnum", args -> PyBool.of(!s.value().isEmpty() && s.value().chars().allMatch(Character::isLetterOrDigit)));
            default -> throw new InterpreterException("AttributeError: 'str' object has no attribute '" + method + "'");
        };
    }

    private PyObject listMethod(PyList list, String method) {
        return switch (method) {
            case "append" -> new PyBuiltinFunction("list.append", args -> { checkArgCount("list.append", args, 1); list.items().add(args[0]); return new PyNull(); });
            case "pop" -> new PyBuiltinFunction("list.pop", args -> {
                if (list.items().isEmpty()) throw new InterpreterException("IndexError: pop from empty list");
                if (args.length > 0) { int i = toInt(args[0]); if (i < 0) i += list.items().size(); return list.items().remove(i); }
                return list.items().removeLast();
            });
            case "extend" -> new PyBuiltinFunction("list.extend", args -> {
                checkArgCount("list.extend", args, 1);
                if (!(args[0] instanceof PyList other)) throw new InterpreterException("TypeError: extend() argument must be iterable");
                list.items().addAll(other.items());
                return new PyNull();
            });
            case "insert" -> new PyBuiltinFunction("list.insert", args -> {
                if (args.length < 2) throw new InterpreterException("TypeError: insert() takes 2 arguments");
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
                throw new InterpreterException("ValueError: list.remove(x): x not in list");
            });
            case "index" -> new PyBuiltinFunction("list.index", args -> {
                checkArgCount("list.index", args, 1);
                for (int i = 0; i < list.items().size(); i++) {
                    if (pyEquals(list.items().get(i), args[0])) return new PyInt(i);
                }
                throw new InterpreterException("ValueError: " + args[0] + " is not in list");
            });
            case "count" -> new PyBuiltinFunction("list.count", args -> {
                checkArgCount("list.count", args, 1);
                int c = 0; for (var item : list.items()) if (pyEquals(item, args[0])) c++;
                return new PyInt(c);
            });
            case "sort" -> new PyBuiltinFunction("list.sort", args -> {
                list.items().sort(Jython3Interpreter::compareObjects);
                return new PyNull();
            });
            case "reverse" -> new PyBuiltinFunction("list.reverse", args -> {
                Collections.reverse(list.items());
                return new PyNull();
            });
            case "copy" -> new PyBuiltinFunction("list.copy", args -> new PyList(new ArrayList<>(list.items())));
            case "clear" -> new PyBuiltinFunction("list.clear", args -> { list.items().clear(); return new PyNull(); });
            default -> throw new InterpreterException("AttributeError: 'list' object has no attribute '" + method + "'");
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
                if (args.length < 1) throw new InterpreterException("TypeError: get() takes at least 1 argument");
                PyObject val = dict.map().get(args[0]);
                if (val != null) return val;
                return (args.length > 1) ? args[1] : new PyNull();
            });
            case "update" -> new PyBuiltinFunction("dict.update", args -> {
                checkArgCount("dict.update", args, 1);
                if (!(args[0] instanceof PyDict other)) throw new InterpreterException("TypeError: update() argument must be a dict");
                dict.map().putAll(other.map());
                return new PyNull();
            });
            case "pop" -> new PyBuiltinFunction("dict.pop", args -> {
                if (args.length < 1) throw new InterpreterException("TypeError: pop() takes at least 1 argument");
                PyObject val = dict.map().remove(args[0]);
                if (val != null) return val;
                if (args.length > 1) return args[1];
                throw new InterpreterException("KeyError: " + args[0]);
            });
            case "clear" -> new PyBuiltinFunction("dict.clear", args -> { dict.map().clear(); return new PyNull(); });
            case "copy" -> new PyBuiltinFunction("dict.copy", args -> new PyDict(new LinkedHashMap<>(dict.map())));
            default -> throw new InterpreterException("AttributeError: 'dict' object has no attribute '" + method + "'");
        };
    }

    // ─── Utility helpers ──────────────────────────────────────────────────

    private static boolean isNumeric(PyObject o) {
        return o instanceof PyInt || o instanceof PyFloat || o instanceof PyBool;
    }

    static double toDouble(PyObject o) {
        return switch (o) {
            case PyInt i -> i.value();
            case PyFloat f -> f.value();
            case PyBool b -> b.value() ? 1.0 : 0.0;
            case PyStr s -> {
                try { yield Double.parseDouble(s.value()); }
                catch (NumberFormatException e) { throw new InterpreterException("ValueError: could not convert string to float: '" + s.value() + "'"); }
            }
            default -> throw new InterpreterException("TypeError: expected number, got " + typeName(o));
        };
    }

    private static int toInt(PyObject o) {
        if (o instanceof PyInt i) return i.value();
        if (o instanceof PyBool b) return b.value() ? 1 : 0;
        if (o instanceof PyFloat f) return (int) f.value();
        throw new InterpreterException("TypeError: expected int, got " + typeName(o));
    }

    static boolean isTruthy(PyObject v) {
        return switch (v) {
            case PyBool b -> b.value();
            case PyInt i -> i.value() != 0;
            case PyStr s -> !s.value().isEmpty();
            case PyNull n -> false;
            case PyFunction f -> true;
            case PyClass c -> true;
            case PyInstance inst -> true;
            case PyBoundMethod bm -> true;
            case PyList l -> !l.items().isEmpty();
            case PyDict d -> !d.map().isEmpty();
            case PyIterator it -> true;
            case PyFloat f -> f.value() != 0.0;
            case PyModule m -> true;
            case PyBuiltinFunction bf -> true;
            case PyException e -> true;
            case PyCompiledCodeWrapper cw -> true;
            case JavaBridge.PyJavaObject jo -> jo.object() != null;
            case JavaBridge.PyJavaClass jc -> true;
            case JavaBridge.PyJavaMethod jm -> true;
            case JavaBridge.PyJavaPackage jp -> true;
        };
    }

    static boolean pyEquals(PyObject a, PyObject b) {
        if (a instanceof PyNull && b instanceof PyNull) return true;
        if (a instanceof PyBool ab && b instanceof PyBool bb) return ab.value() == bb.value();
        if (a instanceof PyInt ai && b instanceof PyInt bi) return ai.value() == bi.value();
        if (a instanceof PyFloat af && b instanceof PyFloat bf) return af.value() == bf.value();
        if (a instanceof PyStr as2 && b instanceof PyStr bs) return as2.value().equals(bs.value());
        if (isNumeric(a) && isNumeric(b)) return toDouble(a) == toDouble(b);
        if (a instanceof PyList al && b instanceof PyList bl) {
            if (al.items().size() != bl.items().size()) return false;
            for (int i = 0; i < al.items().size(); i++) {
                if (!pyEquals(al.items().get(i), bl.items().get(i))) return false;
            }
            return true;
        }
        if (a instanceof PyDict ad && b instanceof PyDict bd) {
            return ad.map().equals(bd.map());
        }
        return false;
    }

    static int compareObjects(PyObject a, PyObject b) {
        if (isNumeric(a) && isNumeric(b)) return Double.compare(toDouble(a), toDouble(b));
        if (a instanceof PyStr as2 && b instanceof PyStr bs) return as2.value().compareTo(bs.value());
        throw new InterpreterException("TypeError: '<' not supported between instances of '" + typeName(a) + "' and '" + typeName(b) + "'");
    }

    static boolean pyContains(PyObject container, PyObject item) {
        if (container instanceof PyList list) {
            for (var el : list.items()) if (pyEquals(el, item)) return true;
            return false;
        }
        if (container instanceof PyStr s && item instanceof PyStr sub) return s.value().contains(sub.value());
        if (container instanceof PyDict dict) return dict.map().containsKey(item);
        throw new InterpreterException("TypeError: argument of type '" + typeName(container) + "' is not iterable");
    }

    static String typeName(PyObject o) {
        return switch (o) {
            case PyInt i -> "int";
            case PyFloat f -> "float";
            case PyStr s -> "str";
            case PyBool b -> "bool";
            case PyNull n -> "NoneType";
            case PyList l -> "list";
            case PyDict d -> "dict";
            case PyFunction f -> "function";
            case PyClass c -> "type";
            case PyInstance inst -> inst.pyClass().name();
            case PyBoundMethod bm -> "method";
            case PyBuiltinFunction bf -> "builtin_function_or_method";
            case PyModule m -> "module";
            case PyIterator it -> "iterator";
            case PyException e -> "Exception";
            case PyCompiledCodeWrapper cw -> "code";
            case JavaBridge.PyJavaClass jc -> "JavaClass(" + jc.clazz().getSimpleName() + ")";
            case JavaBridge.PyJavaObject jo -> "JavaObject(" + (jo.object() == null ? "null" : jo.object().getClass().getSimpleName()) + ")";
            case JavaBridge.PyJavaMethod jm -> "JavaMethod(" + jm.name() + ")";
            case JavaBridge.PyJavaPackage jp -> "JavaPackage(" + jp.packageName() + ")";
        };
    }

    private static void checkArgCount(String name, PyObject[] args, int expected) {
        if (args.length != expected) throw new InterpreterException("TypeError: " + name + "() takes exactly " + expected + " argument" + (expected != 1 ? "s" : "") + " (" + args.length + " given)");
    }

    private List<PyObject> flattenArgs(String name, PyObject[] args) {
        if (args.length == 1 && args[0] instanceof PyList list) return new ArrayList<>(list.items());
        if (args.length > 1) { var items = new ArrayList<PyObject>(); Collections.addAll(items, args); return items; }
        throw new InterpreterException("TypeError: " + name + "() requires at least 1 argument");
    }

    private static String pyStringFormat(String fmt, PyObject args) {
        String result = fmt;
        if (args instanceof PyList list) {
            for (var item : list.items()) {
                result = result.replaceFirst("%s", item.toString());
                result = result.replaceFirst("%d", item.toString());
            }
        } else {
            result = result.replaceFirst("%s", args.toString());
            result = result.replaceFirst("%d", args.toString());
        }
        return result;
    }

    private static String unescapeString(String s) {
        if (!s.contains("\\")) return s;
        var sb = new StringBuilder();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length()) {
                char next = s.charAt(++i);
                switch (next) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '\\' -> sb.append('\\');
                    case '\'' -> sb.append('\'');
                    case '"' -> sb.append('"');
                    default -> { sb.append('\\'); sb.append(next); }
                }
            } else {
                sb.append(c);
            }
        }
        return sb.toString();
    }

    private PyObject builtinMap(PyObject[] args) {
        if (args.length != 2) throw new InterpreterException("TypeError: map() requires exactly 2 arguments");
        if (!(args[1] instanceof PyList list)) throw new InterpreterException("TypeError: map() argument 2 must be iterable");
        var result = new ArrayList<PyObject>();
        for (var item : list.items()) {
            result.add(callPyCallable(args[0], new PyObject[]{item}));
        }
        return new PyList(result);
    }

    private PyObject builtinFilter(PyObject[] args) {
        if (args.length != 2) throw new InterpreterException("TypeError: filter() requires exactly 2 arguments");
        if (!(args[1] instanceof PyList list)) throw new InterpreterException("TypeError: filter() argument 2 must be iterable");
        var result = new ArrayList<PyObject>();
        for (var item : list.items()) {
            PyObject val = callPyCallable(args[0], new PyObject[]{item});
            if (isTruthy(val)) result.add(item);
        }
        return new PyList(result);
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
        if (callable instanceof JavaBridge.PyJavaClass jc) {
            return jc.newInstance(args);
        }
        if (callable instanceof JavaBridge.PyJavaMethod jm) {
            return jm.invoke(args);
        }
        if (callable instanceof PyFunction fn) {
            var savedStack = stack;
            var savedLocals = currentLocals;
            var savedExcept = exceptStack;

            var fl = new HashMap<String, PyObject>();
            for (int i = 0; i < fn.params().size() && i < args.length; i++)
                fl.put(fn.params().get(i), args[i]);

            currentLocals = fl;
            stack = new ArrayDeque<>();
            exceptStack = new ArrayDeque<>();
            run(fn.code());
            PyObject result = stack.isEmpty() ? new PyNull() : stack.pop();

            stack = savedStack;
            currentLocals = savedLocals;
            exceptStack = savedExcept;
            return result;
        }
        throw new InterpreterException("TypeError: '" + callable + "' is not callable");
    }

    private void importModule(String spec) {
        // ── from:pkg:item:alias  or  from:pkg:*  ──
        if (spec.startsWith("from:")) {
            String[] parts = spec.split(":", -1);
            // parts[0]="from", parts[1]=pkg, parts[2]=item, parts[3]=alias (optional)
            String pkg = parts[1];
            String item = parts[2];

            if (item.equals("*")) {
                // from java.math import *  – not really supported, warn
                throw new InterpreterException("ImportError: 'import *' from Java package is not supported");
            }

            String alias = parts.length > 3 ? parts[3] : item;
            String fullName = pkg + "." + item;

            // Try Java bridge first
            JavaBridge.checkSecurity(fullName);
            PyObject resolved = JavaBridge.resolveClassOrPackage(fullName);
            if (resolved != null) {
                globals.put(alias, resolved);
                return;
            }

            // Try built-in module attribute
            PyModule builtinMod = resolveBuiltinModule(pkg);
            if (builtinMod != null) {
                PyObject attr = builtinMod.attrs().get(item);
                if (attr != null) { globals.put(alias, attr); return; }
                throw new InterpreterException("ImportError: cannot import name '" + item + "' from '" + pkg + "'");
            }

            throw new InterpreterException("ImportError: cannot import '" + item + "' from '" + pkg + "'");
        }

        // ── pkg:alias  or plain pkg ──
        String[] parts = spec.split(":", -1);
        String pkg = parts[0];
        String alias = parts.length > 1 ? parts[1] : parts[0];

        // Built-in Python modules
        PyModule builtin = resolveBuiltinModule(pkg);
        if (builtin != null) {
            globals.put(alias, builtin);
            return;
        }

        // Java class or package (if dotted or known Java root package)
        if (pkg.contains(".") || isJavaRootPackage(pkg)) {
            JavaBridge.checkSecurity(pkg);
            PyObject javaObj = JavaBridge.resolveClassOrPackage(pkg);
            if (javaObj != null) {
                globals.put(alias, javaObj);
                return;
            }
        }

        // File-based modules: validate name (no dots, path traversal protection)
        if (!pkg.matches("[A-Za-z_][A-Za-z0-9_]*")) {
            throw new InterpreterException("ImportError: invalid module name '" + pkg + "'");
        }

        try (var br = new BufferedReader(new FileReader("modules/" + pkg + ".py"))) {
            String l;
            while ((l = br.readLine()) != null) if (!l.isBlank()) exec(l);
            // After executing, if alias != pkg, also set alias
            if (!alias.equals(pkg)) {
                PyObject mod = globals.get(pkg);
                if (mod != null) globals.put(alias, mod);
            }
        } catch (IOException e) {
            throw new InterpreterException("ImportError: no module named '" + pkg + "'");
        }
    }

    private PyModule resolveBuiltinModule(String name) {
        return switch (name) {
            case "math" -> BuiltinModules.math();
            case "sys"  -> BuiltinModules.sys();
            case "os"   -> BuiltinModules.os();
            default     -> null;
        };
    }

    private static boolean isJavaRootPackage(String name) {
        return name.equals("java") || name.equals("javax") || name.equals("org") ||
               name.equals("com") || name.equals("net") || name.equals("io");
    }

    // ─── Bytecode public API ──────────────────────────────────────────────
    public int[] compileToBytecode(String src) { return compile(src).bytecode(); }
    public CompiledCode compileToCode(String src) { return compile(src); }

    // ─── Runtime object hierarchy ─────────────────────────────────────────
    public sealed interface PyObject permits
            PyInt, PyStr, PyNull, PyBool, PyFunction, PyClass, PyInstance, PyBoundMethod,
            PyList, PyDict, PyIterator, PyModule, PyBuiltinFunction, PyFloat, PyException,
            PyCompiledCodeWrapper,
            JavaBridge.PyJavaClass, JavaBridge.PyJavaObject, JavaBridge.PyJavaMethod,
            JavaBridge.PyJavaPackage {}

    public record PyInt(int value) implements PyObject { @Override public String toString() { return Integer.toString(value); } }
    public record PyStr(String value) implements PyObject { @Override public String toString() { return value; } }
    public record PyNull() implements PyObject { @Override public String toString() { return "None"; } }

    public record PyBool(boolean value) implements PyObject {
        public static final PyBool TRUE = new PyBool(true);
        public static final PyBool FALSE = new PyBool(false);
        public static PyBool of(boolean v) { return v ? TRUE : FALSE; }
        @Override public String toString() { return value ? "True" : "False"; }
    }

    public record PyFunction(String name, List<String> params, CompiledCode code) implements PyObject {
        @Override public String toString() { return "<function " + name + ">"; }
    }

    /** A compiled code wrapper stored in constant pool for class bodies. */
    public record PyCompiledCodeWrapper(CompiledCode code) implements PyObject {}

    /** Python Class object. */
    public record PyClass(String name, PyClass baseClass, Map<String, PyObject> members) implements PyObject {
        public PyObject lookupMember(String attr) {
            PyObject val = members.get(attr);
            if (val != null) return val;
            if (baseClass != null) return baseClass.lookupMember(attr);
            return null;
        }

        public boolean hasMember(String attr) {
            return lookupMember(attr) != null;
        }

        @Override public String toString() {
            return "<class '" + name + "'>";
        }
    }

    /** Python Class Instance. */
    public static final class PyInstance implements PyObject {
        private final PyClass pyClass;
        private final Map<String, PyObject> fields = new HashMap<>();

        public PyInstance(PyClass pyClass) {
            this.pyClass = pyClass;
        }

        public PyClass pyClass() {
            return pyClass;
        }

        public boolean isInstanceOf(PyClass target) {
            PyClass c = pyClass;
            while (c != null) {
                if (c == target || c.name().equals(target.name())) return true;
                c = c.baseClass();
            }
            return false;
        }

        public PyObject getField(String name) {
            if (fields.containsKey(name)) {
                return fields.get(name);
            }
            PyObject member = pyClass.lookupMember(name);
            if (member instanceof PyFunction fn) {
                return new PyBoundMethod(this, fn);
            }
            if (member != null) {
                return member;
            }
            throw new InterpreterException("AttributeError: '" + pyClass.name() + "' object has no attribute '" + name + "'");
        }

        public void setField(String name, PyObject val) {
            fields.put(name, val);
        }

        public boolean hasField(String name) {
            return fields.containsKey(name) || pyClass.hasMember(name);
        }

        @Override
        public String toString() {
            PyObject strMethod = pyClass.lookupMember("__str__");
            if (strMethod instanceof PyFunction) {
                // If __str__ exists, representation can be formatted
                return "<" + pyClass.name() + " instance>";
            }
            return "<" + pyClass.name() + " instance>";
        }
    }

    /** Bound method (instance + function). */
    public record PyBoundMethod(PyInstance instance, PyFunction fn) implements PyObject {
        @Override public String toString() {
            return "<bound method " + fn.name() + " of " + instance + ">";
        }
    }

    public record PyFloat(double value) implements PyObject {
        @Override public String toString() {
            if (Double.isInfinite(value)) return value > 0 ? "inf" : "-inf";
            if (Double.isNaN(value)) return "nan";
            return (value == Math.floor(value) && !Double.isInfinite(value)) ? (long) value + ".0" : String.valueOf(value);
        }
    }

    public record PyException(String excType, String message) implements PyObject {
        @Override public String toString() { return excType + ": " + message; }
    }

    public record PyModule(String name, Map<String, PyObject> attrs) implements PyObject {
        @Override public String toString() { return "<module '" + name + "'>"; }
    }

    public record PyBuiltinFunction(String name, java.util.function.Function<PyObject[], PyObject> fn) implements PyObject {
        @Override public String toString() { return "<built-in function " + name + ">"; }
    }

    public record PyList(List<PyObject> items) implements PyObject {
        @Override public String toString() {
            var sb = new StringBuilder("[");
            for (int i = 0; i < items.size(); i++) {
                if (i > 0) sb.append(", ");
                var it = items.get(i);
                if (it instanceof PyStr s) sb.append("'").append(s.value()).append("'");
                else sb.append(it);
            }
            return sb.append("]").toString();
        }
    }

    public record PyDict(Map<PyObject, PyObject> map) implements PyObject {
        @Override public String toString() {
            var sb = new StringBuilder("{");
            int i = 0;
            for (var entry : map.entrySet()) {
                if (i++ > 0) sb.append(", ");
                var k = entry.getKey();
                var v = entry.getValue();
                if (k instanceof PyStr s) sb.append("'").append(s.value()).append("'");
                else sb.append(k);
                sb.append(": ");
                if (v instanceof PyStr s) sb.append("'").append(s.value()).append("'");
                else sb.append(v);
            }
            return sb.append("}").toString();
        }
    }

    public static final class PyIterator implements PyObject {
        private final List<PyObject> items; private int cursor;
        public PyIterator(List<PyObject> items, int cursor) { this.items = items; this.cursor = cursor; }
        boolean hasNext() { return cursor < items.size(); }
        PyObject next() { return items.get(cursor++); }
        @Override public String toString() { return "<iterator>"; }
    }

    // ─── Error handling ───────────────────────────────────────────────────
    public static class InterpreterException extends RuntimeException { public InterpreterException(String m) { super(m); } }
    private static final class ThrowingErrorListener extends BaseErrorListener {
        static final ThrowingErrorListener INSTANCE = new ThrowingErrorListener();
        @Override public void syntaxError(Recognizer<?,?> r, Object s, int l, int c, String m, RecognitionException e) {
            throw new InterpreterException("SyntaxError at " + l + ":" + c + " – " + m);
        }
    }
}
