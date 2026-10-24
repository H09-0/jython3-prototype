package org.example.jython3.runtime;


import org.example.jython3.compile.CompiledCode;

public record PyCompiledCodeWrapper(CompiledCode code) implements PyObject {}

/** Python Class object. */
