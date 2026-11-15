package org.example.jython3.stdlib.modules;

import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyModule;
import org.example.jython3.runtime.PyNull;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.security.ExecutionContext;
import org.example.jython3.security.HostAccessPolicy;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.example.jython3.stdlib.modules.StdlibSupport.fn;

/**
 * Standard {@code io} module gated by {@link HostAccessPolicy}, with closed-file
 * enforcement, ancestor/leaf symlink rejection (CWE-59), {@code O_NOFOLLOW}
 * byte-channel I/O, UTF-8 byte-length caps (CWE-400), and thread-safe file locks.
 */
public final class IoModule {
    private static final long MAX_DEFAULT_FILE_BYTES = 10_000_000L;
    private static final Set<String> VALID_MODES = Set.of(
            "r", "rb", "rt", "w", "wb", "wt", "a", "ab", "at",
            "r+", "rb+", "rt+", "w+", "wb+", "wt+", "a+", "ab+", "at+"
    );

    private IoModule() {}

    public static PyModule create(HostAccessPolicy hostPolicy) {
        Map<String, PyObject> attrs = new HashMap<>();
        attrs.put("open", fn("open", args -> {
            if (args.length == 0) throw PyErrors.error("TypeError", "open() missing required argument");
            String filename = args[0].toString();
            if (filename.isEmpty() || filename.indexOf('\0') >= 0) {
                throw PyErrors.error("ValueError", "embedded null byte or empty filename");
            }
            String mode = args.length > 1 ? args[1].toString() : "r";
            if (!VALID_MODES.contains(mode)) {
                throw PyErrors.error("ValueError", "invalid mode: '" + mode + "'");
            }
            Path p = Path.of(filename).toAbsolutePath().normalize();

            boolean isWrite = mode.contains("w") || mode.contains("a") || mode.contains("+");
            if (isWrite && !hostPolicy.canWriteFile(p)) {
                throw PyErrors.error("SecurityError", "access to write file '" + filename + "' is restricted");
            }
            if (!isWrite && !hostPolicy.canReadFile(p)) {
                throw PyErrors.error("SecurityError", "access to read file '" + filename + "' is restricted");
            }
            rejectSymlinksInChain(p, filename);

            final ExecutionContext ctx = ExecutionContext.current();
            final long maxBytes = ctx != null
                    ? Math.min(MAX_DEFAULT_FILE_BYTES, Math.max(1L, ctx.limits().maxStringLength()))
                    : MAX_DEFAULT_FILE_BYTES;
            final Object ioLock = new Object();
            final AtomicBoolean closed = new AtomicBoolean(false);

            try {
                if (isWrite) {
                    Path parent = p.getParent() == null ? Path.of(".") : p.getParent();
                    Files.createDirectories(parent);
                    Path canonicalTarget = parent.toRealPath().resolve(p.getFileName());
                    if (!hostPolicy.canWriteFile(canonicalTarget)) {
                        throw PyErrors.error("SecurityError", "access to write file '" + filename + "' is restricted");
                    }
                    if (Files.isSymbolicLink(canonicalTarget)) {
                        throw PyErrors.error("SecurityError", "refusing to open symbolic link '" + filename + "'");
                    }
                    if (mode.contains("w") && !mode.contains("a")) {
                        writeAllNoFollow(canonicalTarget, new byte[0], true);
                    }
                    Map<String, PyObject> fattrs = new HashMap<>();
                    fattrs.put("name", new PyStr(p.toString()));
                    fattrs.put("mode", new PyStr(mode));
                    fattrs.put("read", fn("read", a -> {
                        if (closed.get()) throw PyErrors.error("ValueError", "I/O operation on closed file");
                        if (!hostPolicy.canReadFile(canonicalTarget)) {
                            throw PyErrors.error("SecurityError", "access to read file '" + filename + "' is restricted");
                        }
                        synchronized (ioLock) {
                            if (closed.get()) throw PyErrors.error("ValueError", "I/O operation on closed file");
                            try {
                                return new PyStr(readAllNoFollow(canonicalTarget, maxBytes));
                            } catch (org.example.jython3.runtime.InterpreterException ie) {
                                throw ie;
                            } catch (Exception ex) {
                                throw PyErrors.error("IOError", ex.getMessage());
                            }
                        }
                    }));
                    fattrs.put("write", fn("write", a -> {
                        if (closed.get()) throw PyErrors.error("ValueError", "I/O operation on closed file");
                        if (!hostPolicy.canWriteFile(canonicalTarget)) {
                            throw PyErrors.error("SecurityError", "access to write file '" + filename + "' is restricted");
                        }
                        if (Files.isSymbolicLink(canonicalTarget)) {
                            throw PyErrors.error("SecurityError", "refusing to write through symbolic link");
                        }
                        String data = a.length > 0 ? a[0].toString() : "";
                        byte[] utf8 = data.getBytes(StandardCharsets.UTF_8);
                        if (utf8.length > maxBytes) {
                            throw PyErrors.error("MemoryError", "write size exceeds limit (" + maxBytes + " bytes)");
                        }
                        synchronized (ioLock) {
                            if (closed.get()) throw PyErrors.error("ValueError", "I/O operation on closed file");
                            try {
                                long existing = Files.exists(canonicalTarget, LinkOption.NOFOLLOW_LINKS)
                                        ? Files.size(canonicalTarget) : 0L;
                                if (existing + utf8.length > maxBytes) {
                                    throw PyErrors.error("MemoryError", "file size exceeds limit (" + maxBytes + " bytes)");
                                }
                                writeAllNoFollow(canonicalTarget, utf8, false);
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
                    if (!Files.exists(p, LinkOption.NOFOLLOW_LINKS)) {
                        throw PyErrors.error("IOError", "No such file or directory: '" + filename + "'");
                    }
                    Path canonicalTarget = p.toRealPath(LinkOption.NOFOLLOW_LINKS);
                    if (!hostPolicy.canReadFile(canonicalTarget)) {
                        throw PyErrors.error("SecurityError", "access to read file '" + filename + "' is restricted");
                    }
                    String content = readAllNoFollow(canonicalTarget, maxBytes);
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

    private static void rejectSymlinksInChain(Path p, String rawName) {
        Path cur = p.getRoot();
        for (Path part : p) {
            cur = cur == null ? part : cur.resolve(part);
            if (Files.isSymbolicLink(cur)) {
                throw PyErrors.error("SecurityError", "refusing to traverse symbolic link for '" + rawName + "'");
            }
        }
    }

    private static String readAllNoFollow(Path target, long maxBytes) throws IOException {
        if (Files.isSymbolicLink(target)) {
            throw PyErrors.error("SecurityError", "refusing to read symbolic link");
        }
        Set<OpenOption> opts = Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
        SeekableByteChannel ch;
        try {
            ch = Files.newByteChannel(target, opts);
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            ch = Files.newByteChannel(target, StandardOpenOption.READ);
        }
        try (SeekableByteChannel channel = ch) {
            long size = channel.size();
            if (size > maxBytes) {
                throw PyErrors.error("MemoryError", "file size exceeds limit (" + maxBytes + " bytes)");
            }
            byte[] buf = new byte[(int) size];
            ByteBuffer bb = ByteBuffer.wrap(buf);
            while (bb.hasRemaining()) {
                if (channel.read(bb) < 0) break;
            }
            return new String(buf, 0, bb.position(), StandardCharsets.UTF_8);
        }
    }

    private static void writeAllNoFollow(Path target, byte[] bytes, boolean truncate) throws IOException {
        if (Files.isSymbolicLink(target)) {
            throw PyErrors.error("SecurityError", "refusing to write symbolic link");
        }
        Set<OpenOption> opts = truncate
                ? Set.of(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS)
                : Set.of(StandardOpenOption.CREATE, StandardOpenOption.APPEND,
                        StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);
        SeekableByteChannel ch;
        try {
            ch = Files.newByteChannel(target, opts);
        } catch (UnsupportedOperationException | IllegalArgumentException e) {
            ch = truncate
                    ? Files.newByteChannel(target, StandardOpenOption.CREATE,
                            StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE)
                    : Files.newByteChannel(target, StandardOpenOption.CREATE,
                            StandardOpenOption.APPEND, StandardOpenOption.WRITE);
        }
        try (SeekableByteChannel channel = ch) {
            ByteBuffer bb = ByteBuffer.wrap(bytes);
            while (bb.hasRemaining()) {
                channel.write(bb);
            }
        }
    }
}
