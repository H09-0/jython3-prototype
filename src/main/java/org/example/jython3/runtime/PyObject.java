package org.example.jython3.runtime;



public sealed interface PyObject permits
        PyInt, BigInt, PyStr, PyNull, PyBool, PyFunction, PyClass, PyInstance, PyBoundMethod, PySuper,
        PyList, PyTuple, PySet, PyFrozenSet, PyDict, PyIterator, PyRange, PyBytes, PyByteArray, PyModule, PyBuiltinFunction, PyFloat, PyException,
        PyCompiledCodeWrapper, PyGenerator, PySlice, PyCell,
        PyProperty, PyStaticMethod, PyClassMethod,
        PyTraceback, PyFrameInfo, PyCodeInfo,
        PyHostHandle,
        PyJavaClass, PyJavaObject, PyJavaMethod,
        PyJavaPackage {}
