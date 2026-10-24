package org.example.jython3.runtime;

import java.util.Deque;
import java.util.Map;

import org.example.jython3.compile.CompiledCode;

public record Frame(CompiledCode code, int returnPc,
                    Map<String, PyObject> locals, Deque<PyObject> stack,
                    Deque<ExceptHandler> exceptHandlers) {}
