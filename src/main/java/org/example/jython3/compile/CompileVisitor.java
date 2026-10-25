package org.example.jython3.compile;

import java.util.ArrayList;
import java.util.List;

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
        compileFunctionDef(name, c.param_list(), c.suite());
        return null;
    }

    private void compileFunctionDef(String name, Jython3Parser.Param_listContext paramList, Jython3Parser.SuiteContext suite) {
        var params = new ArrayList<String>();
        if (paramList != null) {
            for (var id : paramList.ID()) params.add(id.getText());
        }
        var fb = new CodeBuilder();
        var fv = new CompileVisitor(fb);
        fv.visit(suite);
        fb.emit(OpCode.LOAD_CONST, null);
        fb.emit(OpCode.RETURN_VALUE);
        fb.emit(OpCode.HALT);
        b.emit(OpCode.LOAD_CONST, new PyFunction(name, params, fb.build()));
        b.emit(OpCode.MAKE_FUNCTION, name);
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

        if (c.arg_list() != null && !c.arg_list().expr().isEmpty()) {
            for (var e : c.arg_list().expr()) {
                visit(e);
            }
            b.emitRaw(OpCode.BUILD_LIST, c.arg_list().expr().size());
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

    @Override public Void visitTupleLiteral(Jython3Parser.TupleLiteralContext c) {
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
}
