package org.example.jython3.stdlib;

import org.example.jython3.control.ControlRegistry;
import org.example.jython3.host.HostSession;
import org.example.jython3.runtime.PyDict;
import org.example.jython3.runtime.PyList;
import org.example.jython3.runtime.PyModule;
import org.example.jython3.security.HostAccessPolicy;
import org.example.jython3.stdlib.modules.AsyncioModule;
import org.example.jython3.stdlib.modules.ConcurrentFuturesModule;
import org.example.jython3.stdlib.modules.ControlModule;
import org.example.jython3.stdlib.modules.DatetimeModule;
import org.example.jython3.stdlib.modules.IoModule;
import org.example.jython3.stdlib.modules.ItertoolsModule;
import org.example.jython3.stdlib.modules.JsonModule;
import org.example.jython3.stdlib.modules.MathModule;
import org.example.jython3.stdlib.modules.OsModule;
import org.example.jython3.stdlib.modules.QueueModule;
import org.example.jython3.stdlib.modules.RandomModule;
import org.example.jython3.stdlib.modules.ReModule;
import org.example.jython3.stdlib.modules.SysModule;
import org.example.jython3.stdlib.modules.ThreadingModule;
import org.example.jython3.stdlib.modules.TimeModule;
import org.example.jython3.stdlib.modules.UiModule;
import org.example.jython3.ui.UiToolkit;

/**
 * Facade exposing all Java-backed standard library modules implemented under
 * {@code org.example.jython3.stdlib.modules}.
 */
public final class BuiltinModules {
    private BuiltinModules() {}

    public static PyModule math()   { return MathModule.create(); }
    public static PyModule sys()    { return SysModule.create(); }
    public static PyModule sys(PyList path, PyDict modules) { return SysModule.create(path, modules); }
    public static PyModule sys(PyList path, PyDict modules,
                               java.util.function.Supplier<java.io.PrintStream> out,
                               java.util.function.Supplier<java.io.PrintStream> err) {
        return SysModule.create(path, modules, out, err);
    }
    public static PyModule os()     { return os(HostAccessPolicy.PERMISSIVE); }
    public static PyModule os(HostAccessPolicy hostPolicy) { return OsModule.create(hostPolicy); }
    public static PyModule io()     { return io(HostAccessPolicy.PERMISSIVE); }
    public static PyModule io(HostAccessPolicy hostPolicy) { return IoModule.create(hostPolicy); }
    public static PyModule json()   { return JsonModule.create(); }
    public static PyModule re()     { return ReModule.create(); }
    public static PyModule time()   { return TimeModule.create(); }
    public static PyModule random() { return RandomModule.create(); }
    public static PyModule ui(HostSession session, UiToolkit toolkit) { return UiModule.create(session, toolkit); }
    public static PyModule control(HostSession session, ControlRegistry registry, UiToolkit toolkit) {
        return ControlModule.create(session, registry, toolkit);
    }
    public static PyModule threading(HostSession session, ThreadingModule.ThreadSpawner spawner) {
        return ThreadingModule.create(session, spawner);
    }
    public static PyModule itertools() { return ItertoolsModule.create(); }
    public static PyModule queue(HostSession session) { return QueueModule.create(session); }
    public static PyModule datetime() { return DatetimeModule.create(); }
    public static PyModule asyncio() { return AsyncioModule.create(); }
    public static PyModule concurrentPkg(HostSession session, ThreadingModule.ThreadSpawner spawner) {
        return ConcurrentFuturesModule.createPackage(session, spawner);
    }
    public static PyModule concurrentFutures(HostSession session, ThreadingModule.ThreadSpawner spawner) {
        return ConcurrentFuturesModule.createFutures(session, spawner);
    }
}
