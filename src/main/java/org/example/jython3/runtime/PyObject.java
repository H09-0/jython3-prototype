package org.example.jython3.runtime;



public sealed interface PyObject permits
        PyInt, BigInt, PyStr, PyNull, PyBool, PyFunction, PyClass, PyInstance, PyBoundMethod, PySuper,
        PyList, PyDict, PyIterator, PyModule, PyBuiltinFunction, PyFloat, PyException,
        PyCompiledCodeWrapper, PyGenerator, PySlice,
        PyJavaClass, PyJavaObject, PyJavaMethod,
        PyJavaPackage {}
