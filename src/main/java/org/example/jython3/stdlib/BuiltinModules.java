package org.example.jython3.stdlib;

import org.example.jython3.runtime.PyModule;
import org.example.jython3.security.HostAccessPolicy;
import org.example.jython3.stdlib.modules.IoModule;
import org.example.jython3.stdlib.modules.JsonModule;
import org.example.jython3.stdlib.modules.MathModule;
import org.example.jython3.stdlib.modules.OsModule;
import org.example.jython3.stdlib.modules.RandomModule;
import org.example.jython3.stdlib.modules.ReModule;
import org.example.jython3.stdlib.modules.SysModule;
import org.example.jython3.stdlib.modules.TimeModule;

/**
 * Facade exposing all Java-backed standard library modules implemented under
 * {@code org.example.jython3.stdlib.modules}.
 */
public final class BuiltinModules {
    private BuiltinModules() {}

    public static PyModule math()   { return MathModule.create(); }
    public static PyModule sys()    { return SysModule.create(); }
    public static PyModule os()     { return os(HostAccessPolicy.PERMISSIVE); }
    public static PyModule os(HostAccessPolicy hostPolicy) { return OsModule.create(hostPolicy); }
    public static PyModule io()     { return io(HostAccessPolicy.PERMISSIVE); }
    public static PyModule io(HostAccessPolicy hostPolicy) { return IoModule.create(hostPolicy); }
    public static PyModule json()   { return JsonModule.create(); }
    public static PyModule re()     { return ReModule.create(); }
    public static PyModule time()   { return TimeModule.create(); }
    public static PyModule random() { return RandomModule.create(); }
}
