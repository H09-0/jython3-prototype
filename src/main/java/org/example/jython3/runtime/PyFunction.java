package org.example.jython3.runtime;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.example.jython3.compile.CompiledCode;

/**
 * Python function object supporting PEP 570 positional-only parameters ({@code /}),
 * positional-or-keyword parameters, defaults, {@code *args}, PEP 3102 keyword-only
 * parameters with optional defaults, and {@code **kwargs}.
 */
public final class PyFunction implements PyObject {

    private final String name;
    /** Positional (posonly + pos-or-kw) param names; excludes *args, kwonly, and **kwargs. */
    private final List<String> params;
    /** Number of leading parameters in {@code params} that are positional-only (PEP 570). */
    private final int posonlyCount;
    /** Default values for the last {@code defaults.length} positional params. */
    private final PyObject[] defaults;
    /** Name for *args parameter, null if not declared. */
    private final String argsParam;
    /** PEP 3102 keyword-only parameter names (in declaration order). */
    private final List<String> kwonlyParams;
    /** Parallel to {@code kwonlyParams}: default value or {@code null} when required. */
    private final PyObject[] kwDefaults;
    /** Name for **kwargs parameter, null if not declared. */
    private final String kwargsParam;
    private final CompiledCode code;
    private final Map<String, PyObject> closure;
    private final boolean isAsync;
    private Map<String, PyObject> attrs;

    public PyFunction(String name, List<String> params, CompiledCode code) {
        this(name, params, 0, new PyObject[0], null, List.of(), new PyObject[0], null, code, null, false);
    }

    public PyFunction(String name, List<String> params, CompiledCode code,
                      Map<String, PyObject> closure) {
        this(name, params, 0, new PyObject[0], null, List.of(), new PyObject[0], null, code, closure, false);
    }

    public PyFunction(String name,
                      List<String> params,
                      PyObject[] defaults,
                      String argsParam,
                      String kwargsParam,
                      CompiledCode code,
                      Map<String, PyObject> closure) {
        this(name, params, 0, defaults, argsParam, List.of(), new PyObject[0], kwargsParam, code, closure, false);
    }

    public PyFunction(String name,
                      List<String> params,
                      PyObject[] defaults,
                      String argsParam,
                      String kwargsParam,
                      CompiledCode code,
                      Map<String, PyObject> closure,
                      boolean isAsync) {
        this(name, params, 0, defaults, argsParam, List.of(), new PyObject[0], kwargsParam, code, closure, isAsync);
    }

    public PyFunction(String name,
                      List<String> params,
                      int posonlyCount,
                      PyObject[] defaults,
                      String argsParam,
                      List<String> kwonlyParams,
                      PyObject[] kwDefaults,
                      String kwargsParam,
                      CompiledCode code,
                      Map<String, PyObject> closure,
                      boolean isAsync) {
        this.name = name;
        this.params = List.copyOf(params);
        this.posonlyCount = Math.max(0, Math.min(posonlyCount, this.params.size()));
        this.defaults = defaults == null ? new PyObject[0] : defaults.clone();
        this.argsParam = argsParam;
        this.kwonlyParams = kwonlyParams == null ? List.of() : List.copyOf(kwonlyParams);
        this.kwDefaults = kwDefaults == null ? new PyObject[0] : kwDefaults.clone();
        this.kwargsParam = kwargsParam;
        this.code = code;
        this.closure = closure == null ? new HashMap<>() : new HashMap<>(closure);
        this.isAsync = isAsync;
    }

    public String name()               { return name; }
    public List<String> params()       { return params; }
    public int posonlyCount()          { return posonlyCount; }
    public PyObject[] defaults()       { return defaults; }
    public String argsParam()          { return argsParam; }
    public List<String> kwonlyParams() { return kwonlyParams; }
    public PyObject[] kwDefaults()     { return kwDefaults; }
    public String kwargsParam()        { return kwargsParam; }
    public CompiledCode code()         { return code; }
    public Map<String, PyObject> closure() { return closure; }
    public boolean isAsync()           { return isAsync; }

    public PyObject getExtraAttr(String key) {
        return attrs != null ? attrs.get(key) : null;
    }

    public void setExtraAttr(String key, PyObject val) {
        if (attrs == null) attrs = new HashMap<>(4);
        attrs.put(key, val);
    }

    public Map<String, PyObject> extraAttrs() {
        return attrs != null ? attrs : Collections.emptyMap();
    }

    /** True if this function uses any advanced signature feature. */
    public boolean hasAdvancedSignature() {
        return defaults.length > 0 || argsParam != null || kwargsParam != null
                || posonlyCount > 0 || !kwonlyParams.isEmpty();
    }

    @Override public String toString() { return "<function " + name + ">"; }
}

/** A compiled code wrapper stored in constant pool for class bodies. */
