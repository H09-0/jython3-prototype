package org.example.jython3.runtime;

import java.util.Deque;
import java.util.Map;

import org.example.jython3.compile.CompiledCode;

/**
 * A suspended caller. {@code funcName}, {@code file}, and {@code line} describe
 * the call site so an exception can rebuild a traceback while unwinding.
 * {@code activeExceptions} is the caller's active-exception stack; a callee
 * starts with an empty one, so a bare {@code raise} never sees the caller's
 * handler state.
 */
public record Frame(CompiledCode code, int returnPc,
                    Map<String, PyObject> locals, Deque<PyObject> stack,
                    Deque<ExceptHandler> exceptHandlers,
                    Deque<PyObject> activeExceptions,
                    String funcName, String file, int line) {}
