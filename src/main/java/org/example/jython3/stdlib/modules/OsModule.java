package org.example.jython3.stdlib.modules;

import org.example.jython3.runtime.PyDict;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyList;
import org.example.jython3.runtime.PyModule;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.security.HostAccessPolicy;

import java.io.File;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.example.jython3.stdlib.modules.StdlibSupport.fn;

/** Standard {@code os} module gated by {@link HostAccessPolicy}. */
public final class OsModule {
    private OsModule() {}

    public static PyModule create(HostAccessPolicy hostPolicy) {
        Map<String, PyObject> attrs = new HashMap<>();

        attrs.put("getcwd",  fn("getcwd",  args -> {
            Path cwd = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize();
            if (!hostPolicy.canListDirectory(Path.of(".")) || !hostPolicy.canListDirectory(cwd)) {
                throw PyErrors.error("SecurityError", "access to current working directory is restricted");
            }
            return new PyStr(System.getProperty("user.dir"));
        }));
        attrs.put("getpid",  fn("getpid",  args -> {
            if (!hostPolicy.canReadEnvironment()) {
                throw PyErrors.error("SecurityError", "access to process information is restricted");
            }
            return PyInt.of((int) ProcessHandle.current().pid());
        }));
        attrs.put("sep",     new PyStr(File.separator));
        attrs.put("pathsep", new PyStr(File.pathSeparator));
        attrs.put("name",    new PyStr("java"));

        var envMap = new LinkedHashMap<PyObject, PyObject>();
        if (hostPolicy.canReadEnvironment()) {
            for (var entry : System.getenv().entrySet()) {
                envMap.put(new PyStr(entry.getKey()), new PyStr(entry.getValue()));
            }
        }
        attrs.put("environ", new PyDict(envMap));

        attrs.put("listdir", fn("listdir", args -> {
            String pathStr = args.length > 0 ? args[0].toString() : ".";
            if (pathStr.indexOf('\0') >= 0) {
                throw PyErrors.error("ValueError", "embedded null byte in path");
            }
            Path p = Path.of(pathStr).toAbsolutePath().normalize();
            if (!hostPolicy.canListDirectory(p)) {
                throw PyErrors.error("SecurityError", "access to list directory '" + pathStr + "' is restricted");
            }
            if (!java.nio.file.Files.exists(p) || !java.nio.file.Files.isDirectory(p)) {
                throw PyErrors.error("FileNotFoundError", "No such file or directory: '" + pathStr + "'");
            }
            try {
                Path real = p.toRealPath();
                if (!hostPolicy.canListDirectory(real)) {
                    throw PyErrors.error("SecurityError", "access to list directory '" + pathStr + "' is restricted");
                }
            } catch (java.io.IOException ioe) {
                throw PyErrors.error("OSError", ioe.getMessage());
            }
            File dir = p.toFile();
            File[] files = dir.listFiles();
            var list = new ArrayList<PyObject>();
            if (files != null) {
                for (File f : files) list.add(new PyStr(f.getName()));
            }
            return new PyList(list);
        }));

        return new PyModule("os", attrs);
    }
}
