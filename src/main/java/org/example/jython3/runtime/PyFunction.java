package org.example.jython3.runtime;

import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.example.jython3.compile.CompiledCode;

/**
 * Python function object.
 *
 * <p>Stores the full CPython-compatible call signature:
 * <ul>
 *   <li>{@code params}    – ordered list of positional/default parameter names (excluding
 *       {@code *args} and {@code **kwargs}).</li>
 *   <li>{@code defaults}  – evaluated default values for the LAST {@code defaults.length}
 *       params.  Empty array when no defaults are declared.</li>
 *   <li>{@code argsParam} – name bound to the {@code *args} tuple, or {@code null}.</li>
 *   <li>{@code kwargsParam} – name bound to the {@code **kwargs} dict, or {@code null}.</li>
 * </ul>
 *
 * <p>Argument binding follows CPython semantics (PEP 3102):
 * positional args fill {@code params} from left; keyword args fill remaining
 * slots by name; leftover positionals go to {@code *argsParam}; leftover
 * keywords go to {@code **kwargsParam}; defaults fill any unfilled trailing
 * slots.
 */
public final class PyFunction implements PyObject {

    private final String name;
    /** Positional/default param names; excludes *args and **kwargs. */
    private final List<String> params;
    /** Default values for the last params.size() - (params.size()-defaults.length) params.
     *  Invariant: defaults.length <= params.size(). */
    private final PyObject[] defaults;
    /** Name for *args parameter, null if not declared. */
    private final String argsParam;
    /** Name for **kwargs parameter, null if not declared. */
    private final String kwargsParam;
    private final CompiledCode code;
    private final Map<String, PyObject> closure;

    /** Simple constructor — no defaults, no *args/**kwargs (backward-compat). */
    public PyFunction(String name, List<String> params, CompiledCode code) {
        this(name, params, new PyObject[0], null, null, code, null);
    }

    /** Constructor with closure — no defaults, no *args/**kwargs (backward-compat). */
    public PyFunction(String name, List<String> params, CompiledCode code,
                      Map<String, PyObject> closure) {
        this(name, params, new PyObject[0], null, null, code, closure);
    }

    /** Full constructor used by MAKE_FUNCTION_DEFAULTS. */
    public PyFunction(String name,
                      List<String> params,
                      PyObject[] defaults,
                      String argsParam,
                      String kwargsParam,
                      CompiledCode code,
                      Map<String, PyObject> closure) {
        this.name = name;
        this.params = List.copyOf(params);
        this.defaults = defaults == null ? new PyObject[0] : defaults.clone();
        this.argsParam = argsParam;
        this.kwargsParam = kwargsParam;
        this.code = code;
        this.closure = closure == null ? new HashMap<>() : new HashMap<>(closure);
    }

    public String name()          { return name; }
    public List<String> params()  { return params; }
    public PyObject[] defaults()  { return defaults; }
    public String argsParam()     { return argsParam; }
    public String kwargsParam()   { return kwargsParam; }
    public CompiledCode code()    { return code; }
    public Map<String, PyObject> closure() { return closure; }

    /** True if this function uses any advanced signature feature. */
    public boolean hasAdvancedSignature() {
        return defaults.length > 0 || argsParam != null || kwargsParam != null;
    }

    @Override public String toString() { return "<function " + name + ">"; }
}

/** A compiled code wrapper stored in constant pool for class bodies. */
