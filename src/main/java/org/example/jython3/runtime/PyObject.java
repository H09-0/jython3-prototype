package org.example.jython3.runtime;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.example.jython3.compile.CompiledCode;

public sealed interface PyObject permits
        PyInt, BigInt, PyStr, PyNull, PyBool, PyFunction, PyClass, PyInstance, PyBoundMethod, PySuper,
        PyList, PyDict, PyIterator, PyModule, PyBuiltinFunction, PyFloat, PyException,
        PyCompiledCodeWrapper, PyGenerator,
        PyJavaClass, PyJavaObject, PyJavaMethod,
        PyJavaPackage {}
