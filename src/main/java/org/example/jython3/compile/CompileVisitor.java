package org.example.jython3.compile;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.example.jython3.Jython3BaseVisitor;
import org.example.jython3.Jython3Parser;
import org.example.jython3.runtime.PyFunction;

public class CompileVisitor extends Jython3BaseVisitor<Void> {
    private final CodeBuilder b;
    public CompileVisitor(CodeBuilder b) { this.b = b; }

    // ── Loop context stack for break/continue jump patching ──────────────
    private static class LoopContext {
        final java.util.List<Integer> breakPatches = new java.util.ArrayList<>();
        int continueTarget = -1; // pc of loop-start / FOR_ITER
        int depth;               // loopStack.size() while this loop is active
        boolean isForLoop;       // true if this is a 'for' loop with an iterator on the stack
    }
    private final java.util.Deque<LoopContext> loopStack = new java.util.ArrayDeque<>();

    /**
     * Tracks a {@code try} statement while its parts are compiled, so that
     * {@code break}, {@code continue}, and {@code return} can run any pending
     * {@code finally} bodies and deregister the exception handler.
     */
    private static class TryScope {
        final Jython3Parser.SuiteContext finallySuite;
        final int loopDepth;
        boolean handlerActive = true;
        boolean insideFinally = false;
        /** True while compiling an {@code except} suite of this statement. */
        boolean inHandler = false;
        String boundVar = null;

        TryScope(Jython3Parser.SuiteContext finallySuite, int loopDepth) {
            this.finallySuite = finallySuite;
            this.loopDepth = loopDepth;
        }
    }
    private final java.util.Deque<TryScope> tryStack = new java.util.ArrayDeque<>();

    /**
     * Emits the exit path for scopes crossed by a jump. Scopes are visited
     * innermost first: end the active-exception lifetime, deregister the
     * handler, then run the {@code finally} body. {@code all} is used by
     * {@code return}, which crosses every scope.
     *
     * <p>The {@code END_EXCEPT_HANDLER} instruction also has to be emitted
     * here, not only at the natural end of a suite: {@code return},
     * {@code break}, and {@code continue} jump over it.
     */
    private void emitScopeExits(int targetLoopDepth, boolean all) {
        for (TryScope scope : tryStack) {
            if (!all && scope.loopDepth < targetLoopDepth) break;
            if (scope.inHandler) {
                if (scope.boundVar != null) {
                    b.emit(OpCode.DELETE_NAME_SILENT, scope.boundVar);
                }
                b.emit(OpCode.END_EXCEPT_HANDLER);
            }
            if (scope.handlerActive) {
                b.emit(OpCode.POP_EXCEPT_HANDLER);
            }
            if (scope.finallySuite != null && !scope.insideFinally) {
                boolean prevInside = scope.insideFinally;
                boolean prevActive = scope.handlerActive;
                boolean prevInHandler = scope.inHandler;
                scope.insideFinally = true;
                scope.handlerActive = false;
                scope.inHandler = false;
                try {
                    visit(scope.finallySuite);
                } finally {
                    scope.insideFinally = prevInside;
                    scope.handlerActive = prevActive;
                    scope.inHandler = prevInHandler;
                }
            }
        }
    }

    /** Compiles a finally suite without letting it re-run itself on exit. */
    private void visitFinally(TryScope scope) {
        if (scope.finallySuite == null) return;
        boolean prevInside = scope.insideFinally;
        scope.insideFinally = true;
        try {
            visit(scope.finallySuite);
        } finally {
            scope.insideFinally = prevInside;
        }
    }

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
        b.setLine(c.getStart().getLine());
        return visitChildren(c);
    }

    @Override public Void visitCompound_stmt(Jython3Parser.Compound_stmtContext c) {
        b.setLine(c.getStart().getLine());
        return visitChildren(c);
    }

    // ── print(args) ──
    // print() is handled as a keyword — routes through the PRINT opcode for now.
    // After Sprint B we'll unify with the builtin `print` function.
    @Override public Void visitPrint_stmt(Jython3Parser.Print_stmtContext c) {
        if (c.arg_list() == null || c.arg_list().arg_item().isEmpty()) {
            b.emit(OpCode.LOAD_CONST, "");
            b.emit(OpCode.PRINT);
        } else {
            // Collect positional expressions only; keyword args in print() handled via CALL route
            var posItems = c.arg_list().arg_item().stream()
                    .filter(it -> it instanceof Jython3Parser.PositionalArgContext)
                    .toList();
            if (posItems.size() == 1) {
                visit(((Jython3Parser.PositionalArgContext) posItems.get(0)).expr());
                b.emit(OpCode.PRINT);
            } else if (posItems.isEmpty()) {
                b.emit(OpCode.LOAD_CONST, "");
                b.emit(OpCode.PRINT);
            } else {
                for (var item : posItems) {
                    visit(((Jython3Parser.PositionalArgContext) item).expr());
                }
                b.emitRaw(OpCode.BUILD_LIST, posItems.size());
                b.emit(OpCode.PRINT_MULTI);
            }
        }
        return null;
    }

    @Override public Void visitAssign_stmt(Jython3Parser.Assign_stmtContext c) {
        visit(c.expr());
        // Handle chained assignment: a = b = expr  (emits DUP_TOP for all but last)
        var ids = c.ID();
        for (int i = 0; i < ids.size(); i++) {
            if (i < ids.size() - 1) b.emit(OpCode.DUP_TOP);
            b.emit(OpCode.STORE_FAST, ids.get(i).getText());
        }
        return null;
    }

    // ── Tuple unpacking: a, b = expr  or  (a, b) = expr ──────────────────────
    @Override public Void visitTuple_assign_stmt(Jython3Parser.Tuple_assign_stmtContext c) {
        // Compile the right-hand side.  rhs_tuple is either:
        //   RhsTupleMany: multiple expressions  → build a tuple on the stack
        //   RhsSingle:    single expression      → value already on stack
        var rhs = c.rhs_tuple();
        if (rhs instanceof Jython3Parser.RhsTupleManyContext many) {
            int n = many.expr().size();
            for (var e : many.expr()) visit(e);
            b.emitRaw(OpCode.BUILD_TUPLE, n);
        } else {
            visit(((Jython3Parser.RhsSingleContext) rhs).expr());
        }
        int count = c.ID().size();
        b.emitRaw(OpCode.UNPACK_SEQUENCE, count);
        for (var id : c.ID()) b.emit(OpCode.STORE_FAST, id.getText());
        return null;
    }

    // ── global / nonlocal ─────────────────────────────────────────────────────
    @Override public Void visitGlobal_stmt(Jython3Parser.Global_stmtContext c) {
        // Mark each name as global in this function's scope.
        // The VM resolves LOAD_GLOBAL_NAME / STORE_GLOBAL_NAME at runtime.
        for (var id : c.ID()) b.emit(OpCode.LOAD_CONST, "@global:" + id.getText());
        return null;
    }

    @Override public Void visitNonlocal_stmt(Jython3Parser.Nonlocal_stmtContext c) {
        // nonlocal is handled via closure capture; mark names as cell variables.
        for (var id : c.ID()) b.emit(OpCode.LOAD_CONST, "@nonlocal:" + id.getText());
        return null;
    }

    // ── assert ────────────────────────────────────────────────────────────────
    @Override public Void visitAssert_stmt(Jython3Parser.Assert_stmtContext c) {
        // assert expr [, msg]
        // JUMP_IF_FALSE pops condition. If false -> raise AssertionError; if true -> skip
        visit(c.expr(0));
        int jumpFalse = b.size();
        b.emitRaw(OpCode.JUMP_IF_FALSE, 0);

        // Condition was True: skip over the AssertionError raising
        int jumpOk = b.size();
        b.emitRaw(OpCode.JUMP, 0);

        // Condition was False: construct and throw AssertionError
        b.patch(jumpFalse, b.size());
        if (c.expr().size() > 1) {
            visit(c.expr(1));
            b.emitRaw(OpCode.ASSERT, 1);
        } else {
            b.emitRaw(OpCode.ASSERT, 0);
        }
        b.patch(jumpOk, b.size());
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
        // A return leaves every enclosing try block, so run their finally bodies.
        emitScopeExits(0, true);
        b.emit(OpCode.RETURN_VALUE);
        return null;
    }

    @Override public Void visitYield_stmt(Jython3Parser.Yield_stmtContext c) {
        if (c.expr() != null) {
            visit(c.expr());
        } else {
            b.emit(OpCode.LOAD_CONST, null);
        }
        b.emit(OpCode.YIELD_VALUE);
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

    @Override public Void visitExecfile_stmt(Jython3Parser.Execfile_stmtContext c) {
        visit(c.expr());
        b.emit(OpCode.EXECFILE);
        return null;
    }

    /**
     * break — emit a JUMP that will be back-patched to after the enclosing loop.
     * Collects the patch site into the innermost LoopContext.
     */
    @Override public Void visitBreak_stmt(Jython3Parser.Break_stmtContext c) {
        if (!loopStack.isEmpty()) {
            LoopContext target = loopStack.peek();
            emitScopeExits(target.depth, false);
            // In a 'for' loop, the PyIterator sits on the stack throughout the body.
            // When breaking, we must pop it so enclosing scopes / outer loops find a clean stack.
            if (target.isForLoop) {
                b.emit(OpCode.POP_TOP);
            }
            int idx = b.size();
            b.emitRaw(OpCode.JUMP, 0); // target patched after loop ends
            target.breakPatches.add(idx);
        }
        return null;
    }

    /**
     * continue — emit a JUMP directly to the loop-start / FOR_ITER pc.
     * The target is set when the enclosing while/for visitor pushes its LoopContext.
     */
    @Override public Void visitContinue_stmt(Jython3Parser.Continue_stmtContext c) {
        if (!loopStack.isEmpty()) {
            LoopContext target = loopStack.peek();
            emitScopeExits(target.depth, false);
            int t = target.continueTarget;
            if (t >= 0) b.emitRaw(OpCode.JUMP, t);
        }
        return null;
    }

    @Override public Void visitCall_stmt(Jython3Parser.Call_stmtContext c) {
        if (c.expr() != null) {
            visit(c.expr());
            b.emit(OpCode.LOAD_ATTR, c.ID().getText());
        } else {
            b.emit(OpCode.LOAD_FAST, c.ID().getText());
        }
        emitCall(c.arg_list());
        b.emit(OpCode.POP_TOP);
        return null;
    }

    @Override public Void visitFunc_def(Jython3Parser.Func_defContext c) {
        String name = c.ID().getText();
        compileFunctionDef(name, c.param_list(), c.suite());
        return null;
    }

    private void compileFunctionDef(String name,
                                     Jython3Parser.Param_listContext paramList,
                                     Jython3Parser.SuiteContext suite) {
        // ── 1. Parse parameter list ──────────────────────────────────────────
        var positional   = new ArrayList<String>();  // plain and default params
        var defaultExprs = new ArrayList<Jython3Parser.ExprContext>();  // default value exprs
        String argsParam   = null;  // *args name
        String kwargsParam = null;  // **kwargs name

        if (paramList != null) {
            for (var item : paramList.param_item()) {
                if (item instanceof Jython3Parser.SimpleParamContext sp) {
                    if (!defaultExprs.isEmpty())
                        throw new CompileError("non-default argument follows default argument");
                    positional.add(sp.ID().getText());
                } else if (item instanceof Jython3Parser.DefaultParamContext dp) {
                    positional.add(dp.ID().getText());
                    defaultExprs.add(dp.expr());
                } else if (item instanceof Jython3Parser.ArgsParamContext ap) {
                    argsParam = ap.ID().getText();
                } else if (item instanceof Jython3Parser.KwargsParamContext kp) {
                    kwargsParam = kp.ID().getText();
                }
            }
        }

        // ── 2. Build the function prototype WITHOUT defaults baked in ────────
        // Defaults are evaluated at definition time (outer scope), so we
        // push them onto the stack now, before MAKE_FUNCTION_DEFAULTS.
        int nDefaults = defaultExprs.size();
        boolean hasArgs   = argsParam != null;
        boolean hasKwargs = kwargsParam != null;
        int flags = (hasArgs ? 1 : 0) | (hasKwargs ? 2 : 0);

        // ── 3. Compile the function body ─────────────────────────────────────
        var fb = new CodeBuilder();
        var fv = new CompileVisitor(fb);
        fv.visit(suite);
        fb.emit(OpCode.LOAD_CONST, null);
        fb.emit(OpCode.RETURN_VALUE);
        fb.emit(OpCode.HALT);

        // ── 4. Emit: load prototype → push defaults → MAKE_FUNCTION_DEFAULTS ──
        // Build a prototype PyFunction that holds params/argsParam/kwargsParam/code.
        // Defaults are attached by the VM when it processes MAKE_FUNCTION_DEFAULTS.
        b.emit(OpCode.LOAD_CONST,
               new PyFunction(name, positional, new org.example.jython3.runtime.PyObject[0],
                              argsParam, kwargsParam, fb.build(), null));

        if (nDefaults == 0 && !hasArgs && !hasKwargs) {
            // Fast path: simple function, no advanced signature features
            b.emit(OpCode.MAKE_FUNCTION, name);
        } else {
            // Evaluate each default value expression in the enclosing scope
            for (var dExpr : defaultExprs) visit(dExpr);
            // Encode: (nDefaults << 2) | flags
            b.emitRaw(OpCode.MAKE_FUNCTION_DEFAULTS, (nDefaults << 2) | flags);
            // MAKE_FUNCTION_DEFAULTS also captures closure & stores under name;
            // but we still need to emit the name so the VM knows where to store it.
            b.emit(OpCode.MAKE_FUNCTION, name);
        }
    }

    @Override public Void visitDecorated_func_def(Jython3Parser.Decorated_func_defContext c) {
        Jython3Parser.Func_defContext funcDef = c.func_def();
        String name = funcDef.ID().getText();
        // Compile function first, store in current scope
        compileFunctionDef(name, funcDef.param_list(), funcDef.suite());
        // Apply decorators from bottom to top
        List<Jython3Parser.DecoratorContext> decorators = c.decorator();
        for (int i = decorators.size() - 1; i >= 0; i--) {
            // Load decorator
            visit(decorators.get(i).expr());
            // Load function
            b.emit(OpCode.LOAD_FAST, name);
            // Call decorator with function
            b.emitRaw(OpCode.CALL_FUNCTION, 1);
            // Store result back
            b.emit(OpCode.STORE_FAST, name);
        }
        return null;
    }

    @Override public Void visitClass_def(Jython3Parser.Class_defContext c) {
        String className = c.ID().getText();
        compileClassDef(className, c);
        return null;
    }

    private void compileClassDef(String className, Jython3Parser.Class_defContext c) {
        var cb = new CodeBuilder();
        var cv = new CompileVisitor(cb);
        cv.visit(c.suite());
        cb.emit(OpCode.HALT);

        if (c.arg_list() != null && !c.arg_list().arg_item().isEmpty()) {
            var posItems = c.arg_list().arg_item().stream()
                    .filter(it -> it instanceof Jython3Parser.PositionalArgContext)
                    .toList();
            for (var it : posItems) visit(((Jython3Parser.PositionalArgContext) it).expr());
            b.emitRaw(OpCode.BUILD_LIST, posItems.size());
        } else {
            b.emit(OpCode.LOAD_CONST, null);
        }
        b.emit(OpCode.LOAD_CONST, cb.build());
        b.emit(OpCode.BUILD_CLASS, className);
    }

    @Override public Void visitDecorated_class_def(Jython3Parser.Decorated_class_defContext c) {
        Jython3Parser.Class_defContext classDef = c.class_def();
        String className = classDef.ID().getText();
        compileClassDef(className, classDef);
        List<Jython3Parser.DecoratorContext> decorators = c.decorator();
        for (int i = decorators.size() - 1; i >= 0; i--) {
            visit(decorators.get(i).expr());
            b.emit(OpCode.LOAD_FAST, className);
            b.emitRaw(OpCode.CALL_FUNCTION, 1);
            b.emit(OpCode.STORE_FAST, className);
        }
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

        // Push loop context so break/continue know where to jump
        var ctx = new LoopContext();
        ctx.continueTarget = loopStart;
        ctx.isForLoop = false;
        loopStack.push(ctx);
        ctx.depth = loopStack.size();
        visit(c.suite());
        loopStack.pop();

        b.emitRaw(OpCode.JUMP, loopStart);
        int afterLoop = b.size();
        b.patch(jumpFalse, afterLoop);
        // Patch all break jumps to after the loop
        for (int bp : ctx.breakPatches) b.patch(bp, afterLoop);
        return null;
    }

    @Override public Void visitFor_stmt(Jython3Parser.For_stmtContext c) {
        visit(c.expr());
        b.emit(OpCode.GET_ITER);
        int loopTarget = b.size();
        int forIter = b.size();
        b.emitRaw(OpCode.FOR_ITER, 0);
        b.emit(OpCode.STORE_FAST, c.ID().getText());

        // Push loop context: continue → jump back to FOR_ITER
        var ctx = new LoopContext();
        ctx.continueTarget = loopTarget;
        ctx.isForLoop = true;
        loopStack.push(ctx);
        ctx.depth = loopStack.size();
        visit(c.suite());
        loopStack.pop();

        b.emitRaw(OpCode.JUMP, loopTarget);
        int afterLoop = b.size();
        b.patch(forIter, afterLoop);
        // Patch all break jumps to after the loop
        for (int bp : ctx.breakPatches) b.patch(bp, afterLoop);
        return null;
    }

    @Override public Void visitTry_stmt(Jython3Parser.Try_stmtContext c) {
        /*
         * Bytecode layout:
         *
         *   PUSH_EXCEPT_HANDLER <handler-pc>
         *   <try body>
         *   POP_EXCEPT_HANDLER
         *   <else body>                    ; normal path only
         *   JUMP <shared-finally>
         *
         *   <handler-pc>:                  ; stack top = exception value
         *   for each clause:
         *     <clause type expr>; COMPARE_EXC_TYPE
         *     JUMP_IF_FALSE <next clause>
         *     BEGIN_EXCEPT_HANDLER     ; caught value becomes active
         *     bind/discard value; <clause body>; END_EXCEPT_HANDLER
         *     JUMP <shared-finally>
         *   <exceptional-finally>          ; runs before re-raising
         *   RERAISE
         *
         *   <shared-finally>:
         *   <finally body>                 ; normal path and handled exceptions
         */
        var exceptClauses = c.except_clause();
        var suites = c.suite();
        var finallySuite = c.FINALLY() != null ? suites.get(suites.size() - 1) : null;

        var scope = new TryScope(finallySuite, loopStack.size());
        tryStack.push(scope);

        // --- try body ---
        int pushIdx = b.size();
        b.emitRaw(OpCode.PUSH_EXCEPT_HANDLER, 0); // patched to the handler chain below
        visit(suites.get(0));
        b.emit(OpCode.POP_EXCEPT_HANDLER);
        scope.handlerActive = false;

        // --- else body (runs only when the try body completed normally) ---
        if (c.ELSE() != null) {
            int elseOffset = finallySuite != null ? suites.size() - 2 : suites.size() - 1;
            if (elseOffset > 0 && elseOffset < suites.size()) {
                visit(suites.get(elseOffset));
            }
        }

        int jumpOverIdx = b.size();
        b.emitRaw(OpCode.JUMP, 0); // skip the handler chain

        // --- handler chain ---
        b.patch(pushIdx, b.size());
        var clauseEndPatches = new java.util.ArrayList<Integer>();
        for (var clause : exceptClauses) {
            boolean hasType = clause.expr() != null;
            int nextClauseIdx = -1;

            if (hasType) {
                // Resolve the clause expression to a real class object. A name
                // that is not an exception type is rejected by the VM.
                visit(clause.expr());
                b.emit(OpCode.COMPARE_EXC_TYPE);
                nextClauseIdx = b.size();
                b.emitRaw(OpCode.JUMP_IF_FALSE, 0); // try the next clause
            }

            // The caught value becomes the active exception for bare raise.
            // The VM records the previously active one so it can be restored.
            b.emit(OpCode.BEGIN_EXCEPT_HANDLER);

            String boundVarName = null;
            if (clause.AS() != null && clause.ID() != null) {
                boundVarName = clause.ID().getText();
                b.emit(OpCode.STORE_FAST, boundVarName);
            } else {
                b.emit(OpCode.POP_TOP);
            }

            boolean prevActiveScope = scope.handlerActive;
            boolean prevInHandler = scope.inHandler;
            String prevBoundVar = scope.boundVar;
            scope.handlerActive = false;
            scope.inHandler = true;
            scope.boundVar = boundVarName;

            int handlerPushIdx = -1;
            if (finallySuite != null) {
                // If this try statement has a finally suite, register a handler around the
                // except clause body so an exception raised inside the handler still runs finally.
                handlerPushIdx = b.size();
                b.emitRaw(OpCode.PUSH_EXCEPT_HANDLER, 0);
            }

            try {
                visit(clause.suite());
            } finally {
                scope.handlerActive = prevActiveScope;
                scope.inHandler = prevInHandler;
                scope.boundVar = prevBoundVar;
            }

            if (finallySuite != null) {
                b.emit(OpCode.POP_EXCEPT_HANDLER);
            }

            // In Python 3, 'as e' variable is explicitly deleted at the end of the except block.
            if (boundVarName != null) {
                b.emit(OpCode.DELETE_NAME_SILENT, boundVarName);
            }

            // Natural end of the handler: restore the outer active exception.
            // Jumps out of the suite emit the same instruction themselves.
            b.emit(OpCode.END_EXCEPT_HANDLER);

            int jmpEnd = b.size();
            b.emitRaw(OpCode.JUMP, 0);
            clauseEndPatches.add(jmpEnd);

            if (finallySuite != null) {
                // Exceptional path out of this except clause: run finally then reraise.
                b.patch(handlerPushIdx, b.size());
                if (boundVarName != null) {
                    b.emit(OpCode.DELETE_NAME_SILENT, boundVarName);
                }
                b.emit(OpCode.END_EXCEPT_HANDLER);
                visitFinally(scope);
                b.emit(OpCode.RERAISE);
            }

            if (hasType) {
                b.patch(nextClauseIdx, b.size());
            }
        }

        // No clause matched: run the finally body, then propagate the exception.
        visitFinally(scope);
        b.emit(OpCode.RERAISE);

        // Normal completion and handled exceptions skip the re-raise path.
        b.patch(jumpOverIdx, b.size());
        for (int ep : clauseEndPatches) b.patch(ep, b.size());

        // Shared finally body for the normal path and handled exceptions.
        visitFinally(scope);

        tryStack.pop();
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
    @Override public Void visitInt(Jython3Parser.IntContext c) {
        String text = c.INT().getText();
        try {
            b.emit(OpCode.LOAD_CONST, Integer.valueOf(text));
        } catch (NumberFormatException nfe) {
            b.emit(OpCode.LOAD_CONST, new org.example.jython3.runtime.BigInt(new java.math.BigInteger(text)));
        }
        return null;
    }
    @Override public Void visitFloat(Jython3Parser.FloatContext c) { b.emit(OpCode.LOAD_CONST, Double.valueOf(c.FLOAT().getText())); return null; }
    @Override public Void visitStr(Jython3Parser.StrContext c) {
        String raw = c.STRING().getText();
        String unquoted;
        if (raw.startsWith("'''") || raw.startsWith("\"\"\"")) {
            unquoted = raw.substring(3, raw.length() - 3);
        } else {
            unquoted = raw.substring(1, raw.length() - 1);
        }
        b.emit(OpCode.LOAD_CONST, StringEscapes.unescape(unquoted));
        return null;
    }
    @Override public Void visitTrueLit(Jython3Parser.TrueLitContext c) { b.emit(OpCode.LOAD_CONST, Boolean.TRUE); return null; }
    @Override public Void visitFalseLit(Jython3Parser.FalseLitContext c) { b.emit(OpCode.LOAD_CONST, Boolean.FALSE); return null; }
    @Override public Void visitNoneLit(Jython3Parser.NoneLitContext c) { b.emit(OpCode.LOAD_CONST, null); return null; }
    @Override public Void visitId(Jython3Parser.IdContext c) { b.emit(OpCode.LOAD_FAST, c.ID().getText()); return null; }

    // ─────────────────────────────────────────────────────────────────────
    // Shared helper: compile a call arg list into positional + keyword args.
    // Returns true if any keyword argument was present (needs CALL_FUNCTION_KW).
    // ─────────────────────────────────────────────────────────────────────
    private record CallArgs(int nPos, List<String> kwNames) {}

    private CallArgs emitArgList(Jython3Parser.Arg_listContext argList) {
        if (argList == null) return new CallArgs(0, List.of());
        int nPos = 0;
        var kwNames = new ArrayList<String>();
        // First pass: positional args (must precede keyword args per Python grammar)
        for (var item : argList.arg_item()) {
            if (item instanceof Jython3Parser.PositionalArgContext pa) {
                if (!kwNames.isEmpty())
                    throw new CompileError("positional argument follows keyword argument");
                visit(pa.expr());
                nPos++;
            } else if (item instanceof Jython3Parser.KeywordArgContext ka) {
                kwNames.add(ka.ID().getText());
                visit(ka.expr());
            }
        }
        return new CallArgs(nPos, kwNames);
    }

    /** Emit a CALL_FUNCTION or CALL_FUNCTION_KW instruction for the given call. */
    private void emitCall(Jython3Parser.Arg_listContext argList) {
        var ca = emitArgList(argList);
        if (ca.kwNames().isEmpty()) {
            b.emitRaw(OpCode.CALL_FUNCTION, ca.nPos());
        } else {
            // Store KwCallInfo (nPos + kwNames) as a single const-pool entry.
            // The opcode arg is the pool index — fits cleanly in 16 bits.
            var info = new KwCallInfo(ca.nPos(), ca.kwNames().toArray(String[]::new));
            b.emit(OpCode.CALL_FUNCTION_KW, info);  // uses emit(op, Object) → addConst → pool index
        }
    }

    @Override public Void visitFuncCall(Jython3Parser.FuncCallContext c) {
        b.emit(OpCode.LOAD_FAST, c.ID().getText());
        emitCall(c.arg_list());
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

    @Override public Void visitTupleLiteral(Jython3Parser.TupleLiteralContext c) {
        int n = 0;
        if (c.expr_list() != null) {
            for (var e : c.expr_list().expr()) { visit(e); n++; }
        }
        b.emitRaw(OpCode.BUILD_TUPLE, n);
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
        visit(c.expr());
        visit(c.slice_item());
        b.emit(OpCode.BINARY_SUBSCR);
        return null;
    }

    @Override public Void visitSliceSingleIndex(Jython3Parser.SliceSingleIndexContext c) {
        visit(c.expr());
        return null;
    }

    @Override public Void visitSliceDoubleColonStep(Jython3Parser.SliceDoubleColonStepContext c) {
        b.emit(OpCode.LOAD_CONST, null); // start omitted
        b.emit(OpCode.LOAD_CONST, null); // stop omitted
        visit(c.expr());                 // step
        b.emitRaw(OpCode.BUILD_SLICE, 3);
        return null;
    }

    @Override public Void visitSliceOmittedStartStep(Jython3Parser.SliceOmittedStartStepContext c) {
        b.emit(OpCode.LOAD_CONST, null); // start omitted
        visit(c.expr(0));                // stop
        if (c.expr().size() > 1) {
            visit(c.expr(1));            // step
        } else {
            b.emit(OpCode.LOAD_CONST, null); // step omitted
        }
        b.emitRaw(OpCode.BUILD_SLICE, 3);
        return null;
    }

    @Override public Void visitSliceOmittedStart(Jython3Parser.SliceOmittedStartContext c) {
        b.emit(OpCode.LOAD_CONST, null); // start omitted
        visit(c.expr());                 // stop
        b.emitRaw(OpCode.BUILD_SLICE, 2);
        return null;
    }

    @Override public Void visitSliceOmittedAll(Jython3Parser.SliceOmittedAllContext c) {
        b.emit(OpCode.LOAD_CONST, null); // start omitted
        b.emit(OpCode.LOAD_CONST, null); // stop omitted
        b.emitRaw(OpCode.BUILD_SLICE, 2);
        return null;
    }

    @Override public Void visitSliceStartDoubleColonStep(Jython3Parser.SliceStartDoubleColonStepContext c) {
        visit(c.expr(0));                // start
        b.emit(OpCode.LOAD_CONST, null); // stop omitted
        if (c.expr().size() > 1) {
            visit(c.expr(1));            // step
        } else {
            b.emit(OpCode.LOAD_CONST, null); // step omitted
        }
        b.emitRaw(OpCode.BUILD_SLICE, 3);
        return null;
    }

    @Override public Void visitSliceFull(Jython3Parser.SliceFullContext c) {
        visit(c.expr(0)); // start
        visit(c.expr(1)); // stop
        if (c.COLON().size() > 1) {
            if (c.expr().size() > 2) {
                visit(c.expr(2)); // step
            } else {
                b.emit(OpCode.LOAD_CONST, null); // step omitted
            }
            b.emitRaw(OpCode.BUILD_SLICE, 3);
        } else {
            b.emitRaw(OpCode.BUILD_SLICE, 2);
        }
        return null;
    }

    @Override public Void visitSliceStartOnly(Jython3Parser.SliceStartOnlyContext c) {
        visit(c.expr());                 // start
        b.emit(OpCode.LOAD_CONST, null); // stop omitted
        b.emitRaw(OpCode.BUILD_SLICE, 2);
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
        emitCall(c.arg_list());
        return null;
    }

    @Override public Void visitRaise_stmt(Jython3Parser.Raise_stmtContext c) {
        if (c.expr() == null) {
            // Bare raise: re-raise whatever handler is active.
            b.emit(OpCode.RAISE_ACTIVE);
            return null;
        }
        // The expression may be a class (raise E), a call (raise E("msg")), or
        // an existing exception value (raise e). The VM decides which.
        visit(c.expr());
        b.emit(OpCode.RAISE);
        return null;
    }

    // ── with statement ──────────────────────────────────────────────────────
    /**
     * Compiles:  with CM [as VAR]: suite
     *
     * Bytecode layout:
     *   LOAD cm_expr
     *   SETUP_WITH              ; calls __enter__; pushes (exit_fn, entered_value)
     *   [STORE_FAST var | POP_TOP]
     *   PUSH_EXCEPT_HANDLER  handler
     *   <suite>
     *   POP_EXCEPT_HANDLER
     *   END_WITH                ; calls exit_fn(None, None, None)
     *   JUMP after
     *
     *  handler:
     *   WITH_EXCEPT_EXIT        ; calls exit_fn(type, value, tb); pushes bool
     *   JUMP_IF_TRUE_OR_POP    after   ; True = exception suppressed
     *   RERAISE
     *
     *  after:
     */
    @Override public Void visitWith_stmt(Jython3Parser.With_stmtContext c) {
        visit(c.expr());
        b.emit(OpCode.SETUP_WITH);         // calls __enter__; stack: [exit_fn, value]

        // Bind or discard the __enter__ result
        if (c.AS() != null) {
            b.emit(OpCode.STORE_FAST, c.ID().getText());
        } else {
            b.emit(OpCode.POP_TOP);        // discard __enter__ value
        }

        // Register exception handler so we can call __exit__ on error
        int pushIdx = b.size();
        b.emitRaw(OpCode.PUSH_EXCEPT_HANDLER, 0);

        visit(c.suite());

        b.emit(OpCode.POP_EXCEPT_HANDLER);
        b.emit(OpCode.END_WITH);           // normal exit: calls __exit__(None,None,None)
        int jumpAfter = b.size();
        b.emitRaw(OpCode.JUMP, 0);

        // Exception handler path
        b.patch(pushIdx, b.size());
        b.emit(OpCode.WITH_EXCEPT_EXIT);   // calls __exit__(type,val,tb); result on stack
        int suppressJump = b.size();
        b.emitRaw(OpCode.JUMP_IF_TRUE_OR_POP, 0); // suppress if __exit__ returned True
        b.emit(OpCode.RERAISE);

        int afterWith = b.size();
        b.patch(jumpAfter, afterWith);
        b.patch(suppressJump, afterWith);
        return null;
    }

    // ── lambda ────────────────────────────────────────────────────────────────
    @Override public Void visitLambdaExpr(Jython3Parser.LambdaExprContext c) {
        // lambda [params]: body_expr
        // Compiled identically to a named anonymous function.
        // The body is a single expression that is returned.
        compileLambda(c.param_list(), c.expr());
        return null;
    }

    /** Compile a lambda expression into an anonymous PyFunction. */
    private void compileLambda(Jython3Parser.Param_listContext paramList,
                               Jython3Parser.ExprContext bodyExpr) {
        // Parse parameters (same as func_def)
        var positional   = new ArrayList<String>();
        var defaultExprs = new ArrayList<Jython3Parser.ExprContext>();
        String argsParam   = null;
        String kwargsParam = null;
        if (paramList != null) {
            for (var item : paramList.param_item()) {
                if (item instanceof Jython3Parser.SimpleParamContext sp) {
                    positional.add(sp.ID().getText());
                } else if (item instanceof Jython3Parser.DefaultParamContext dp) {
                    positional.add(dp.ID().getText());
                    defaultExprs.add(dp.expr());
                } else if (item instanceof Jython3Parser.ArgsParamContext ap) {
                    argsParam = ap.ID().getText();
                } else if (item instanceof Jython3Parser.KwargsParamContext kp) {
                    kwargsParam = kp.ID().getText();
                }
            }
        }
        int nDefaults = defaultExprs.size();
        boolean hasArgs   = argsParam != null;
        boolean hasKwargs = kwargsParam != null;
        // Compile body: single expression, return its value
        var fb = new CodeBuilder();
        var fv = new CompileVisitor(fb);
        fv.visit(bodyExpr);
        fb.emit(OpCode.RETURN_VALUE);
        fb.emit(OpCode.HALT);
        b.emit(OpCode.LOAD_CONST,
               new PyFunction("<lambda>", positional, new org.example.jython3.runtime.PyObject[0],
                              argsParam, kwargsParam, fb.build(), null));
        if (nDefaults > 0 || hasArgs || hasKwargs) {
            for (var dExpr : defaultExprs) visit(dExpr);
            b.emitRaw(OpCode.MAKE_FUNCTION_DEFAULTS, (nDefaults << 2) | ((hasArgs ? 1 : 0) | (hasKwargs ? 2 : 0)));
            // MAKE_FUNCTION_DEFAULTS enriches the prototype and pushes it back onto the stack!
        }
        // The lambda PyFunction remains on top of stack as an expression value.
    }

    // ── ternary / conditional expression ─────────────────────────────────
    @Override public Void visitTernaryExpr(Jython3Parser.TernaryExprContext c) {
        // expr0 if expr1 else expr2
        visit(c.expr(1));                // condition
        int jumpFalse = b.size();
        b.emitRaw(OpCode.JUMP_IF_FALSE, 0);
        visit(c.expr(0));                // true branch
        int jumpEnd = b.size();
        b.emitRaw(OpCode.JUMP, 0);
        b.patch(jumpFalse, b.size());
        visit(c.expr(2));                // false branch
        b.patch(jumpEnd, b.size());
        return null;
    }

    // ── unary plus ──────────────────────────────────────────────────────────────
    @Override public Void visitUnaryPlus(Jython3Parser.UnaryPlusContext c) {
        visit(c.expr()); // +x is a no-op for int/float; value unchanged
        return null;
    }

    // ── f-strings ───────────────────────────────────────────────────────────────
    /**
     * Compiles f"...{expr}..." literals.
     * Strategy: split the raw token at { } boundaries, emit each part as a
     * string concatenation so the VM builds the final string at runtime.
     * This avoids a dedicated opcode and reuses existing ADD infrastructure.
     */
    @Override public Void visitFStr(Jython3Parser.FStrContext c) {
        String raw = c.FSTRING().getText();
        // Strip the leading f/F prefix and quotes (f"...", f'...')
        String inner = raw.substring(2, raw.length() - 1);
        var parts = splitFString(inner);
        if (parts.isEmpty()) {
            b.emit(OpCode.LOAD_CONST, "");
            return null;
        }
        boolean first = true;
        for (var part : parts) {
            if (part.isExpr()) {
                // Pattern: load str() FIRST, then compile expr, then CALL_FUNCTION(1)
                // Stack: [str_fn, value] -> CALL_FUNCTION(1) -> [str_result]
                b.emit(OpCode.LOAD_FAST, "str");
                try {
                    var lexer  = new org.example.jython3.Jython3Lexer(
                            org.antlr.v4.runtime.CharStreams.fromString(part.text()));
                    lexer.removeErrorListeners();
                    var tokens = new org.antlr.v4.runtime.CommonTokenStream(lexer);
                    var parser = new org.example.jython3.Jython3Parser(tokens);
                    parser.removeErrorListeners();
                    visit(parser.expr());
                    b.emitRaw(OpCode.CALL_FUNCTION, 1);
                } catch (Exception ex) {
                    b.emit(OpCode.POP_TOP); // pop the str_fn we pushed
                    b.emit(OpCode.LOAD_CONST, "{" + part.text() + "}");
                }
            } else {
                b.emit(OpCode.LOAD_CONST, StringEscapes.unescape(part.text()));
            }
            if (!first) b.emit(OpCode.ADD);
            first = false;
        }
        return null;
    }

    /** One segment of an f-string: either a literal or an {expr}. */
    private record FStringPart(String text, boolean isExpr) {}

    /** Split the interior of an f-string into alternating literal / expr parts. */
    private java.util.List<FStringPart> splitFString(String inner) {
        var parts = new ArrayList<FStringPart>();
        var sb = new StringBuilder();
        int i = 0;
        while (i < inner.length()) {
            char ch = inner.charAt(i);
            if (ch == '{') {
                if (i + 1 < inner.length() && inner.charAt(i + 1) == '{') {
                    sb.append('{'); i += 2; continue; // {{ escape
                }
                if (sb.length() > 0) { parts.add(new FStringPart(sb.toString(), false)); sb.setLength(0); }
                int depth = 1; i++;
                var exprBuf = new StringBuilder();
                while (i < inner.length() && depth > 0) {
                    char ec = inner.charAt(i);
                    if (ec == '{') depth++;
                    else if (ec == '}') { if (--depth == 0) break; }
                    exprBuf.append(ec); i++;
                }
                i++; // skip closing }
                parts.add(new FStringPart(exprBuf.toString().trim(), true));
            } else if (ch == '}' && i + 1 < inner.length() && inner.charAt(i + 1) == '}') {
                sb.append('}'); i += 2;
            } else {
                sb.append(ch); i++;
            }
        }
        if (sb.length() > 0) parts.add(new FStringPart(sb.toString(), false));
        return parts;
    }

    // ── comprehensions ────────────────────────────────────────────────────────────
    // ── Unique counter for inline comprehension temporary accumulators ────
    private int compCounter = 0;

    /**
     * List comprehension:  [expr FOR id IN iterable (IF cond)?]
     *
     * Clean variable-based compilation (avoids stack-depth juggling):
     *   acc = []
     *   for id in iterable:
     *       if cond:
     *           acc.append(expr)
     *   TOS = acc
     */
    @Override public Void visitListComp(Jython3Parser.ListCompContext c) {
        String accVar = "__comp_list_" + (compCounter++);
        b.emitRaw(OpCode.BUILD_LIST, 0);
        b.emit(OpCode.STORE_FAST, accVar);

        visit(c.expr(1));                    // iterable
        b.emit(OpCode.GET_ITER);
        int loopTop = b.size();
        int forIter = b.size();
        b.emitRaw(OpCode.FOR_ITER, 0);
        b.emit(OpCode.STORE_FAST, c.ID().getText());

        // Optional IF filter
        int filterJump = -1;
        if (c.expr().size() == 3) {
            visit(c.expr(2));
            filterJump = b.size();
            b.emitRaw(OpCode.JUMP_IF_FALSE, 0);
        }

        // acc.append(expr)
        b.emit(OpCode.LOAD_FAST, accVar);
        b.emit(OpCode.LOAD_ATTR, "append");
        visit(c.expr(0));
        b.emitRaw(OpCode.CALL_FUNCTION, 1);
        b.emit(OpCode.POP_TOP); // discard None returned by append

        if (filterJump >= 0) {
            b.patch(filterJump, b.size());
        }
        b.emitRaw(OpCode.JUMP, loopTop);
        int afterLoop = b.size();
        b.patch(forIter, afterLoop);

        // Result: load acc onto stack
        b.emit(OpCode.LOAD_FAST, accVar);
        return null;
    }

    /**
     * Dict comprehension: {key_expr: val_expr FOR id IN iterable (IF cond)?}
     *
     * Clean variable-based compilation:
     *   acc = {}
     *   for id in iterable:
     *       if cond:
     *           acc[key] = val
     *   TOS = acc
     */
    @Override public Void visitDictComp(Jython3Parser.DictCompContext c) {
        String accVar = "__comp_dict_" + (compCounter++);
        b.emitRaw(OpCode.BUILD_DICT, 0);
        b.emit(OpCode.STORE_FAST, accVar);

        visit(c.expr(2));                    // iterable
        b.emit(OpCode.GET_ITER);
        int loopTop = b.size();
        int forIter = b.size();
        b.emitRaw(OpCode.FOR_ITER, 0);
        b.emit(OpCode.STORE_FAST, c.ID().getText());

        // Optional IF filter
        int filterJump = -1;
        if (c.expr().size() == 4) {
            visit(c.expr(3));
            filterJump = b.size();
            b.emitRaw(OpCode.JUMP_IF_FALSE, 0);
        }

        // acc[key] = val
        b.emit(OpCode.LOAD_FAST, accVar);    // container
        visit(c.expr(0));                    // key
        visit(c.expr(1));                    // val
        b.emit(OpCode.STORE_SUBSCR);

        if (filterJump >= 0) {
            b.patch(filterJump, b.size());
        }
        b.emitRaw(OpCode.JUMP, loopTop);
        int afterLoop = b.size();
        b.patch(forIter, afterLoop);

        b.emit(OpCode.LOAD_FAST, accVar);
        return null;
    }

    /**
     * Set comprehension: {expr FOR id IN iterable (IF cond)?}
     *
     * Clean variable-based compilation using PySet built from list or add method.
     */
    @Override public Void visitSetComp(Jython3Parser.SetCompContext c) {
        String accVar = "__comp_set_" + (compCounter++);
        b.emitRaw(OpCode.BUILD_SET, 0);
        b.emit(OpCode.STORE_FAST, accVar);

        visit(c.expr(1));                    // iterable
        b.emit(OpCode.GET_ITER);
        int loopTop = b.size();
        int forIter = b.size();
        b.emitRaw(OpCode.FOR_ITER, 0);
        b.emit(OpCode.STORE_FAST, c.ID().getText());

        int filterJump = -1;
        if (c.expr().size() == 3) {
            visit(c.expr(2));
            filterJump = b.size();
            b.emitRaw(OpCode.JUMP_IF_FALSE, 0);
        }

        // acc.add(expr)
        b.emit(OpCode.LOAD_FAST, accVar);
        b.emit(OpCode.LOAD_ATTR, "add");
        visit(c.expr(0));
        b.emitRaw(OpCode.CALL_FUNCTION, 1);
        b.emit(OpCode.POP_TOP);

        if (filterJump >= 0) {
            b.patch(filterJump, b.size());
        }
        b.emitRaw(OpCode.JUMP, loopTop);
        int afterLoop = b.size();
        b.patch(forIter, afterLoop);

        b.emit(OpCode.LOAD_FAST, accVar);
        return null;
    }

    /** Set literal: {expr, expr, ...} */
    @Override public Void visitSetLiteral(Jython3Parser.SetLiteralContext c) {
        int n = 0;
        for (var e : c.expr_list().expr()) { visit(e); n++; }
        b.emitRaw(OpCode.BUILD_SET, n);
        return null;
    }
}
