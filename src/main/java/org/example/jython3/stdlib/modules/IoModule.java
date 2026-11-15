package org.example.jython3.stdlib.modules;

import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyModule;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.security.ExecutionContext;
import org.example.jython3.security.HostAccessPolicy;

import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.example.jython3.stdlib.modules.StdlibSupport.fn;

/**
 * Standard {@code io} module gated by {@link HostAccessPolicy}, with closed-file
 * enforcement, symlink rejection, write/read size bounds, and thread-safe file locks.
 */
public final class IoModule {
    private static final long MAX_DEFAULT_FILE_BYTES = 10_000_000L;

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
            if (Files.isSymbolicLink(p)) {
                throw PyErrors.error("SecurityError", "refusing to open symbolic link '" + filename + "'");
            }

            final ExecutionContext ctx = ExecutionContext.current();
            final long maxBytes = ctx != null
                    ? Math.min(MAX_DEFAULT_FILE_BYTES, Math.max(1L, ctx.limits().maxStringLength()))
                    : MAX_DEFAULT_FILE_BYTES;
            final Object ioLock = new Object();
            final AtomicBoolean closed = new AtomicBoolean(false);

            try {
                if (isWrite) {
                    Path parent = p.getParent() == null ? Path.of(".") : p.getParent();
                    if (Files.isSymbolicLink(parent)) {
                        throw PyErrors.error("SecurityError", "refusing to create file under symbolic link directory");
                    }
                    Files.createDirectories(parent);
                    if (mode.contains("w") && !mode.contains("a")) {
                        Files.writeString(p, "");
                    }
                    Map<String, PyObject> fattrs = new HashMap<>();
                    fattrs.put("name", new PyStr(p.toString()));
                    fattrs.put("mode", new PyStr(mode));
                    fattrs.put("read", fn("read", a -> {
                        if (closed.get()) throw PyErrors.error("ValueError", "I/O operation on closed file");
                        if (!hostPolicy.canReadFile(p)) throw PyErrors.error("SecurityError", "access to read file '" + filename + "' is restricted");
                        synchronized (ioLock) {
                            if (closed.get()) throw PyErrors.error("ValueError", "I/O operation on closed file");
                            try {
                                if (Files.size(p) > maxBytes) {
                                    throw PyErrors.error("MemoryError", "file size exceeds limit (" + maxBytes + " bytes)");
                                }
                                return new PyStr(Files.readString(p));
                            } catch (org.example.jython3.runtime.InterpreterException ie) {
                                throw ie;
                            } catch (Exception ex) {
                                throw PyErrors.error("IOError", ex.getMessage());
                            }
                        }
                    }));
                    fattrs.put("write", fn("write", a -> {
                        if (closed.get()) throw PyErrors.error("ValueError", "I/O operation on closed file");
                        if (!hostPolicy.canWriteFile(p)) throw PyErrors.error("SecurityError", "access to write file '" + filename + "' is restricted");
                        if (Files.isSymbolicLink(p)) throw PyErrors.error("SecurityError", "refusing to write through symbolic link");
                        String data = a.length > 0 ? a[0].toString() : "";
                        if (data.length() > maxBytes) {
                            throw PyErrors.error("MemoryError", "write size exceeds limit (" + maxBytes + " chars)");
                        }
                        synchronized (ioLock) {
                            if (closed.get()) throw PyErrors.error("ValueError", "I/O operation on closed file");
                            try {
                                long existing = Files.exists(p, LinkOption.NOFOLLOW_LINKS) ? Files.size(p) : 0L;
                                if (existing + data.length() > maxBytes) {
                                    throw PyErrors.error("MemoryError", "file size exceeds limit (" + maxBytes + " bytes)");
                                }
                                Files.writeString(p, data, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
                                return PyInt.of(data.length());
                            } catch (org.example.jython3.runtime.InterpreterException ie) {
                                throw ie;
                            } catch (Exception ex) {
                                throw PyErrors.error("IOError", ex.getMessage());
                            }
                        }
                    }));
                    fattrs.put("close", fn("close", a -> { closed.set(true); return PyNull.INSTANCE; }));
                    PyModule fileObj = new PyModule("file", fattrs);
                    fattrs.put("__enter__", fn("__enter__", a -> {
                        if (closed.get()) throw PyErrors.error("ValueError", "I/O operation on closed file");
                        return fileObj;
                    }));
                    fattrs.put("__exit__", fn("__exit__", a -> { closed.set(true); return PyNull.INSTANCE; }));
                    return fileObj;
                } else {
                    if (Files.exists(p, LinkOption.NOFOLLOW_LINKS) && Files.size(p) > maxBytes) {
                        throw PyErrors.error("MemoryError", "file size exceeds limit (" + maxBytes + " bytes)");
                    }
                    String content = Files.readString(p);
                    Map<String, PyObject> fattrs = new HashMap<>();
                    fattrs.put("name", new PyStr(p.toString()));
                    fattrs.put("mode", new PyStr(mode));
                    fattrs.put("read", fn("read", a -> {
                        if (closed.get()) throw PyErrors.error("ValueError", "I/O operation on closed file");
                        return new PyStr(content);
                    }));
                    fattrs.put("close", fn("close", a -> { closed.set(true); return PyNull.INSTANCE; }));
                    PyModule fileObj = new PyModule("file", fattrs);
                    fattrs.put("__enter__", fn("__enter__", a -> {
                        if (closed.get()) throw PyErrors.error("ValueError", "I/O operation on closed file");
                        return fileObj;
                    }));
                    fattrs.put("__exit__", fn("__exit__", a -> { closed.set(true); return PyNull.INSTANCE; }));
                    return fileObj;
                }
            } catch (org.example.jython3.runtime.InterpreterException ie) {
                throw ie;
            } catch (Exception e) {
                throw PyErrors.error("IOError", e.getMessage());
            }
        }));
        return new PyModule("io", attrs);
    }
}
