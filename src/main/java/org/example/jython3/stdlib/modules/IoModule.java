package org.example.jython3.stdlib.modules;

import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyModule;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.security.HostAccessPolicy;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;

import static org.example.jython3.stdlib.modules.StdlibSupport.fn;

/** Standard {@code io} module gated by {@link HostAccessPolicy}. */
public final class IoModule {
    private IoModule() {}

    public static PyModule create(HostAccessPolicy hostPolicy) {
        Map<String, PyObject> attrs = new HashMap<>();
        attrs.put("open", fn("open", args -> {
            if (args.length == 0) throw PyErrors.error("TypeError", "open() missing required argument");
            String filename = args[0].toString();
            String mode = args.length > 1 ? args[1].toString() : "r";
            Path p = Path.of(filename).normalize();

            boolean isWrite = mode.contains("w") || mode.contains("a") || mode.contains("+");
            if (isWrite && !hostPolicy.canWriteFile(p)) {
                throw PyErrors.error("SecurityError", "access to write file '" + filename + "' is restricted");
            }
            if (!isWrite && !hostPolicy.canReadFile(p)) {
                throw PyErrors.error("SecurityError", "access to read file '" + filename + "' is restricted");
            }

            try {
                if (isWrite) {
                    Files.createDirectories(p.getParent() == null ? Path.of(".") : p.getParent());
                    if (mode.contains("w") && !mode.contains("a")) {
                        Files.writeString(p, "");
                    }
                    Map<String, PyObject> fattrs = new HashMap<>();
                    fattrs.put("name", new PyStr(p.toString()));
                    fattrs.put("mode", new PyStr(mode));
                    fattrs.put("read", fn("read", a -> {
                        if (!hostPolicy.canReadFile(p)) throw PyErrors.error("SecurityError", "access to read file '" + filename + "' is restricted");
                        try { return new PyStr(Files.readString(p)); }
                        catch (Exception ex) { throw PyErrors.error("IOError", ex.getMessage()); }
                    }));
                    fattrs.put("write", fn("write", a -> {
                        if (!hostPolicy.canWriteFile(p)) throw PyErrors.error("SecurityError", "access to write file '" + filename + "' is restricted");
                        try {
                            String data = a.length > 0 ? a[0].toString() : "";
                            Files.writeString(p, data, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                            return PyInt.of(data.length());
                        } catch (Exception ex) { throw PyErrors.error("IOError", ex.getMessage()); }
                    }));
                    fattrs.put("close", fn("close", a -> new PyNull()));
                    PyModule fileObj = new PyModule("file", fattrs);
                    fattrs.put("__enter__", fn("__enter__", a -> fileObj));
                    fattrs.put("__exit__", fn("__exit__", a -> new PyNull()));
                    return fileObj;
                } else {
                    String content = Files.readString(p);
                    Map<String, PyObject> fattrs = new HashMap<>();
                    fattrs.put("name", new PyStr(p.toString()));
                    fattrs.put("mode", new PyStr(mode));
                    fattrs.put("read", fn("read", a -> new PyStr(content)));
                    fattrs.put("close", fn("close", a -> new PyNull()));
                    PyModule fileObj = new PyModule("file", fattrs);
                    fattrs.put("__enter__", fn("__enter__", a -> fileObj));
                    fattrs.put("__exit__", fn("__exit__", a -> new PyNull()));
                    return fileObj;
                }
            } catch (Exception e) {
                throw PyErrors.error("IOError", e.getMessage());
            }
        }));
        return new PyModule("io", attrs);
    }
}
