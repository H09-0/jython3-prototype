package org.example.jython3.runtime;

import java.util.Deque;
import java.util.Map;
import java.util.Set;

import org.example.jython3.compile.CompiledCode;

/**
 * A suspended caller. {@code funcName}, {@code file}, and {@code line} describe
 * the call site so an exception can rebuild a traceback while unwinding.
 * {@code activeExceptions} is the caller's active-exception stack; a callee
 * starts with an empty one, so a bare {@code raise} never sees the caller's
 * handler state.
 * {@code globalCells} records names declared {@code global} in this frame.
 */
public record Frame(CompiledCode code, int returnPc,
                    Map<String, PyObject> locals, Deque<PyObject> stack,
                    Deque<ExceptHandler> exceptHandlers,
                    Deque<PyObject> activeExceptions,
                    String funcName, String file, int line,
                    Map<String, PyObject> globals,
                    Set<String> globalCells,
                    PyFunction function) {

    /** Constructor with globals and globalCells but without function. */
    public Frame(CompiledCode code, int returnPc,
                 Map<String, PyObject> locals, Deque<PyObject> stack,
                 Deque<ExceptHandler> exceptHandlers,
                 Deque<PyObject> activeExceptions,
                 String funcName, String file, int line,
                 Map<String, PyObject> globals,
                 Set<String> globalCells) {
        this(code, returnPc, locals, stack, exceptHandlers, activeExceptions,
             funcName, file, line, globals, globalCells, null);
    }

    /** Convenience constructor without globalCells (null = no global declarations). */
    public Frame(CompiledCode code, int returnPc,
                 Map<String, PyObject> locals, Deque<PyObject> stack,
                 Deque<ExceptHandler> exceptHandlers,
                 Deque<PyObject> activeExceptions,
                 String funcName, String file, int line) {
        this(code, returnPc, locals, stack, exceptHandlers, activeExceptions,
             funcName, file, line, null, null, null);
    }

    /** Constructor with globals but without globalCells. */
    public Frame(CompiledCode code, int returnPc,
                 Map<String, PyObject> locals, Deque<PyObject> stack,
                 Deque<ExceptHandler> exceptHandlers,
                 Deque<PyObject> activeExceptions,
                 String funcName, String file, int line,
                 Map<String, PyObject> globals) {
        this(code, returnPc, locals, stack, exceptHandlers, activeExceptions,
             funcName, file, line, globals, null, null);
    }
}
