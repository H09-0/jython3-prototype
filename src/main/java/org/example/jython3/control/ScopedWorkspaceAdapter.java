package org.example.jython3.control;

import org.example.jython3.host.HostCapability;
import org.example.jython3.host.HostSession;
import org.example.jython3.runtime.PyBool;
import org.example.jython3.runtime.PyErrors;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyList;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyStr;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.channels.SeekableByteChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.FileSystemException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.NoSuchFileException;
import java.nio.file.NotDirectoryException;
import java.nio.file.OpenOption;
import java.nio.file.Path;
import java.nio.file.SecureDirectoryStream;
import java.nio.file.StandardOpenOption;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Capability-gated real workspace filesystem control adapter.
 *
 * <p>Requires BOTH {@link HostCapability#CONTROL} (plus device &amp; command allowlists)
 * AND {@link HostCapability#FILESYSTEM} on the active {@link HostSession}.
 * Uses {@link SecureDirectoryStream} with {@link LinkOption#NOFOLLOW_LINKS}
 * (kernel {@code openat(..., O_NOFOLLOW)} on POSIX/Linux) so intermediate
 * directories and target files cannot be redirected via symlink races (TOCTOU).
 */
public final class ScopedWorkspaceAdapter implements ControlDevice {

    public static final int DEFAULT_MAX_BYTES = 1_048_576; // 1 MB

    private static final Set<OpenOption> READ_NOFOLLOW =
            Set.of(StandardOpenOption.READ, LinkOption.NOFOLLOW_LINKS);
    private static final Set<OpenOption> WRITE_NOFOLLOW =
            Set.of(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING,
                    StandardOpenOption.WRITE, LinkOption.NOFOLLOW_LINKS);

    private final String id;
    private final Path canonicalRoot;
    private final HostSession session;
    private final boolean readOnly;
    private final int maxBytes;
    private final AtomicInteger opsCount = new AtomicInteger(0);

    public ScopedWorkspaceAdapter(String id, Path rootDir, HostSession session, boolean readOnly) {
        this(id, rootDir, session, readOnly, DEFAULT_MAX_BYTES);
    }

    public ScopedWorkspaceAdapter(String id, Path rootDir, HostSession session, boolean readOnly, int maxBytes) {
        this.id = Objects.requireNonNull(id, "id");
        this.session = Objects.requireNonNull(session, "session");
        this.readOnly = readOnly;
        this.maxBytes = Math.max(1, maxBytes);
        try {
            this.canonicalRoot = Objects.requireNonNull(rootDir, "rootDir").toRealPath();
        } catch (IOException e) {
            throw new IllegalArgumentException("Invalid workspace root: " + rootDir, e);
        }
        if (!Files.isDirectory(this.canonicalRoot)) {
            throw new IllegalArgumentException("Workspace root is not a directory: " + this.canonicalRoot);
        }
    }

    @Override
    public String id() { return id; }

    @Override
    public TrustTier trustTier() { return TrustTier.TRUSTED_HOST; }

    @Override
    public PyObject execute(String command, Map<String, PyObject> args) {
        session.require(HostCapability.FILESYSTEM, command, "workspace:" + id);
        opsCount.incrementAndGet();
        return switch (command) {
            case "read_text" -> readText(args);
            case "write_text" -> writeText(args);
            case "list_dir" -> listDir(args);
            case "exists" -> PyBool.of(exists(args));
            default -> throw PyErrors.error("ValueError",
                    "unknown command '" + command + "' on workspace adapter '" + id + "'");
        };
    }

    private PyObject readText(Map<String, PyObject> args) {
        String rel = requireRelativePathArg(args);
        Path normalized = checkNormalizedInRoot("read_text", rel);
        List<String> segments = relativeSegments(normalized);
        if (segments.isEmpty()) {
            throw PyErrors.error("FileNotFoundError", "Not a regular file: " + rel);
        }
        try (DirectoryStream<Path> rootStream = Files.newDirectoryStream(canonicalRoot)) {
            if (rootStream instanceof SecureDirectoryStream<Path> sds) {
                return readViaSecureStream(sds, segments, rel);
            }
        } catch (IOException e) {
            throw mapIoError("read_text", rel, e);
        }
        return readFallback(normalized, rel);
    }

    private PyObject readViaSecureStream(SecureDirectoryStream<Path> root, List<String> segments, String rel) {
        List<SecureDirectoryStream<Path>> opened = new ArrayList<>();
        try {
            SecureDirectoryStream<Path> dir = traverseExisting(root, segments.subList(0, segments.size() - 1), opened, "read_text", rel);
            Path leaf = Path.of(segments.get(segments.size() - 1));
            var view = dir.getFileAttributeView(leaf, java.nio.file.attribute.BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
            BasicFileAttributes attrs = view.readAttributes();
            if (attrs.isSymbolicLink()) {
                throw denySecurity("read_text", rel, "refusing to read symbolic link");
            }
            if (!attrs.isRegularFile()) {
                throw PyErrors.error("FileNotFoundError", "Not a regular file: " + rel);
            }
            if (attrs.size() > maxBytes) {
                throw PyErrors.error("ValueError", "file exceeds maxBytes limit (" + maxBytes + ")");
            }
            try (SeekableByteChannel ch = dir.newByteChannel(leaf, READ_NOFOLLOW)) {
                return readChannelUtf8(ch, maxBytes);
            }
        } catch (NoSuchFileException e) {
            throw PyErrors.error("FileNotFoundError", "Path not found: " + rel);
        } catch (IOException e) {
            throw mapIoError("read_text", rel, e);
        } finally {
            closeAll(opened);
        }
    }

    private PyObject readFallback(Path normalized, String rel) {
        try {
            Path real = normalized.toRealPath();
            if (!real.startsWith(canonicalRoot)) {
                throw denySecurity("read_text", rel, "symlink target escapes workspace root");
            }
            if (!Files.isRegularFile(real, LinkOption.NOFOLLOW_LINKS)) {
                throw PyErrors.error("FileNotFoundError", "Not a regular file: " + rel);
            }
            if (Files.size(real) > maxBytes) {
                throw PyErrors.error("ValueError", "file exceeds maxBytes limit (" + maxBytes + ")");
            }
            try (SeekableByteChannel ch = Files.newByteChannel(real, READ_NOFOLLOW)) {
                return readChannelUtf8(ch, maxBytes);
            }
        } catch (NoSuchFileException e) {
            throw PyErrors.error("FileNotFoundError", "Path not found: " + rel);
        } catch (IOException e) {
            throw mapIoError("read_text", rel, e);
        }
    }

    private PyObject writeText(Map<String, PyObject> args) {
        if (readOnly) {
            throw denySecurity("write_text", "workspace:" + id, "workspace adapter is read-only");
        }
        String rel = requireRelativePathArg(args);
        PyObject contentObj = args != null ? args.get("content") : null;
        if (!(contentObj instanceof PyStr contentStr)) {
            throw PyErrors.error("TypeError", "write_text requires str 'content'");
        }
        byte[] bytes = contentStr.value().getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxBytes) {
            throw PyErrors.error("ValueError", "content exceeds maxBytes limit (" + maxBytes + ")");
        }
        Path normalized = checkNormalizedInRoot("write_text", rel);
        List<String> segments = relativeSegments(normalized);
        if (segments.isEmpty()) {
            throw denySecurity("write_text", rel, "cannot overwrite workspace root directory");
        }
        try (DirectoryStream<Path> rootStream = Files.newDirectoryStream(canonicalRoot)) {
            if (rootStream instanceof SecureDirectoryStream<Path> sds) {
                return writeViaSecureStream(sds, segments, bytes, rel);
            }
        } catch (IOException e) {
            throw mapIoError("write_text", rel, e);
        }
        return writeFallback(normalized, bytes, rel);
    }

    private PyObject writeViaSecureStream(SecureDirectoryStream<Path> root, List<String> segments, byte[] bytes, String rel) {
        List<SecureDirectoryStream<Path>> opened = new ArrayList<>();
        try {
            SecureDirectoryStream<Path> dir = root;
            Path curPath = canonicalRoot;
            for (int i = 0; i < segments.size() - 1; i++) {
                Path seg = Path.of(segments.get(i));
                curPath = curPath.resolve(seg);
                SecureDirectoryStream<Path> next;
                try {
                    next = dir.newDirectoryStream(seg, LinkOption.NOFOLLOW_LINKS);
                } catch (NoSuchFileException missing) {
                    try {
                        Files.createDirectory(curPath);
                    } catch (FileAlreadyExistsException raced) {
                        // Another thread created it; re-open strictly with NOFOLLOW_LINKS below.
                    }
                    next = dir.newDirectoryStream(seg, LinkOption.NOFOLLOW_LINKS);
                }
                opened.add(next);
                dir = next;
            }
            Path leaf = Path.of(segments.get(segments.size() - 1));
            try (SeekableByteChannel ch = dir.newByteChannel(leaf, WRITE_NOFOLLOW)) {
                ByteBuffer buf = ByteBuffer.wrap(bytes);
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
                return PyInt.of(bytes.length);
            }
        } catch (IOException e) {
            throw mapIoError("write_text", rel, e);
        } finally {
            closeAll(opened);
        }
    }

    private PyObject writeFallback(Path normalized, byte[] bytes, String rel) {
        try {
            Path parent = normalized.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
                if (!parent.toRealPath().startsWith(canonicalRoot)) {
                    throw denySecurity("write_text", rel, "workspace path escaped root via symlink");
                }
            }
            if (Files.isSymbolicLink(normalized)) {
                throw denySecurity("write_text", rel, "refusing to write through symbolic link");
            }
            try (SeekableByteChannel ch = Files.newByteChannel(normalized, WRITE_NOFOLLOW)) {
                ByteBuffer buf = ByteBuffer.wrap(bytes);
                while (buf.hasRemaining()) {
                    ch.write(buf);
                }
            }
            return PyInt.of(bytes.length);
        } catch (IOException e) {
            throw mapIoError("write_text", rel, e);
        }
    }

    private PyObject listDir(Map<String, PyObject> args) {
        String rel = (args != null && args.get("path") instanceof PyStr s) ? s.value() : ".";
        if (rel.indexOf('\0') >= 0) {
            throw PyErrors.error("ValueError", "null byte in path");
        }
        Path parsed = Path.of(rel);
        if (parsed.isAbsolute()) {
            throw denySecurity("list_dir", rel, "absolute paths are forbidden in scoped workspace");
        }
        Path normalized = checkNormalizedInRoot("list_dir", rel);
        List<String> segments = relativeSegments(normalized);

        try (DirectoryStream<Path> rootStream = Files.newDirectoryStream(canonicalRoot)) {
            if (rootStream instanceof SecureDirectoryStream<Path> sds) {
                List<SecureDirectoryStream<Path>> opened = new ArrayList<>();
                try {
                    SecureDirectoryStream<Path> target = traverseExisting(sds, segments, opened, "list_dir", rel);
                    List<PyObject> names = new ArrayList<>();
                    for (Path entry : target) {
                        names.add(new PyStr(entry.getFileName().toString()));
                    }
                    names.sort((a, b) -> a.toString().compareTo(b.toString()));
                    return new PyList(names);
                } finally {
                    closeAll(opened);
                }
            }
        } catch (NoSuchFileException e) {
            throw PyErrors.error("FileNotFoundError", "Path not found: " + rel);
        } catch (IOException e) {
            throw mapIoError("list_dir", rel, e);
        }
        throw PyErrors.error("OSError", "Failed to list directory");
    }

    private boolean exists(Map<String, PyObject> args) {
        String rel = requireRelativePathArg(args);
        Path normalized = checkNormalizedInRoot("exists", rel);
        List<String> segments = relativeSegments(normalized);
        if (segments.isEmpty()) return true;
        try (DirectoryStream<Path> rootStream = Files.newDirectoryStream(canonicalRoot)) {
            if (rootStream instanceof SecureDirectoryStream<Path> sds) {
                List<SecureDirectoryStream<Path>> opened = new ArrayList<>();
                try {
                    SecureDirectoryStream<Path> parent = traverseExisting(
                            sds, segments.subList(0, segments.size() - 1), opened, "exists", rel);
                    Path leaf = Path.of(segments.get(segments.size() - 1));
                    var view = parent.getFileAttributeView(leaf, java.nio.file.attribute.BasicFileAttributeView.class, LinkOption.NOFOLLOW_LINKS);
                    BasicFileAttributes attrs = view.readAttributes();
                    return !attrs.isSymbolicLink();
                } catch (NoSuchFileException | NotDirectoryException e) {
                    return false;
                } finally {
                    closeAll(opened);
                }
            }
        } catch (IOException e) {
            return false;
        }
        return false;
    }

    private SecureDirectoryStream<Path> traverseExisting(
            SecureDirectoryStream<Path> root,
            List<String> dirSegments,
            List<SecureDirectoryStream<Path>> opened,
            String action,
            String rel) throws IOException {
        SecureDirectoryStream<Path> cur = root;
        for (String seg : dirSegments) {
            try {
                cur = cur.newDirectoryStream(Path.of(seg), LinkOption.NOFOLLOW_LINKS);
                opened.add(cur);
            } catch (NotDirectoryException nde) {
                throw denySecurity(action, rel, "non-directory or symlink encountered in path");
            }
        }
        return cur;
    }

    private static PyStr readChannelUtf8(SeekableByteChannel ch, int limit) throws IOException {
        long size = ch.size();
        if (size > limit) {
            throw PyErrors.error("ValueError", "file exceeds maxBytes limit (" + limit + ")");
        }
        byte[] buf = new byte[(int) size];
        ByteBuffer bb = ByteBuffer.wrap(buf);
        while (bb.hasRemaining()) {
            int n = ch.read(bb);
            if (n < 0) break;
        }
        return new PyStr(new String(buf, 0, bb.position(), StandardCharsets.UTF_8));
    }

    private String requireRelativePathArg(Map<String, PyObject> args) {
        PyObject p = args != null ? args.get("path") : null;
        if (!(p instanceof PyStr s) || s.value().isEmpty()) {
            throw PyErrors.error("TypeError", "requires non-empty str 'path'");
        }
        if (s.value().indexOf('\0') >= 0) {
            throw PyErrors.error("ValueError", "null byte in path");
        }
        Path parsed = Path.of(s.value());
        if (parsed.isAbsolute()) {
            throw denySecurity("path_check", s.value(), "absolute paths are forbidden in scoped workspace");
        }
        return s.value();
    }

    private Path checkNormalizedInRoot(String action, String rel) {
        Path normalized = canonicalRoot.resolve(rel).normalize();
        if (!normalized.startsWith(canonicalRoot)) {
            throw denySecurity(action, rel, "path escapes workspace root");
        }
        return normalized;
    }

    private List<String> relativeSegments(Path normalized) {
        Path rel = canonicalRoot.relativize(normalized);
        List<String> out = new ArrayList<>();
        for (Path part : rel) {
            String s = part.toString();
            if (!s.isEmpty() && !".".equals(s)) {
                out.add(s);
            }
        }
        return out;
    }

    private RuntimeException mapIoError(String action, String rel, IOException e) {
        if (e instanceof NotDirectoryException) {
            return denySecurity(action, rel, "symlink or non-directory encountered in path");
        }
        if (e instanceof FileSystemException fse) {
            String reason = fse.getReason();
            if (reason != null && (reason.contains("Symbolic link") || reason.contains("ELOOP") || reason.contains("Too many levels"))) {
                return denySecurity(action, rel, "refusing to follow symbolic link: " + reason);
            }
        }
        if (e instanceof NoSuchFileException) {
            return PyErrors.error("FileNotFoundError", "Path not found: " + rel);
        }
        return PyErrors.error("OSError", "Filesystem error: " + e.getMessage());
    }

    private static void closeAll(List<SecureDirectoryStream<Path>> opened) {
        for (int i = opened.size() - 1; i >= 0; i--) {
            try {
                opened.get(i).close();
            } catch (IOException ignored) {}
        }
    }

    private RuntimeException denySecurity(String action, String target, String reason) {
        session.auditLog().recordDenied(HostCapability.FILESYSTEM, action, target, reason);
        return PyErrors.error("SecurityError", reason);
    }

    @Override
    public Map<String, PyObject> snapshotState() {
        Map<String, PyObject> s = new LinkedHashMap<>();
        s.put("read_only", PyBool.of(readOnly));
        s.put("ops_count", PyInt.of(opsCount.get()));
        return Collections.unmodifiableMap(s);
    }
}
