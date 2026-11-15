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
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Capability-gated real workspace filesystem control adapter.
 *
 * <p>Requires BOTH {@link HostCapability#CONTROL} (plus device &amp; command allowlists)
 * AND {@link HostCapability#FILESYSTEM} on the active {@link HostSession}.
 * Enforces strict {@link Path#toRealPath(LinkOption...)} containment inside
 * {@code rootDir} so {@code ..} traversal and symlinks escaping the root are rejected.
 */
public final class ScopedWorkspaceAdapter implements ControlDevice {

    public static final int DEFAULT_MAX_BYTES = 1_048_576; // 1 MB

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
        Path target = resolveContainedExisting(requireRelativePathArg(args));
        if (!Files.isRegularFile(target)) {
            throw PyErrors.error("FileNotFoundError", "Not a regular file");
        }
        try {
            long size = Files.size(target);
            if (size > maxBytes) {
                throw PyErrors.error("ValueError", "file exceeds maxBytes limit (" + maxBytes + ")");
            }
            return new PyStr(Files.readString(target, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw PyErrors.error("OSError", "Failed to read file: " + e.getMessage());
        }
    }

    private PyObject writeText(Map<String, PyObject> args) {
        if (readOnly) {
            throw PyErrors.error("SecurityError", "workspace adapter '" + id + "' is read-only");
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
        Path target = resolveContainedForWrite(rel);
        try {
            Path parent = target.getParent();
            if (parent != null) {
                Files.createDirectories(parent);
                // Re-verify canonical parent after directory creation (no symlink escape)
                if (!parent.toRealPath().startsWith(canonicalRoot)) {
                    throw PyErrors.error("SecurityError", "workspace path escaped root via symlink");
                }
            }
            if (Files.isSymbolicLink(target)) {
                throw PyErrors.error("SecurityError", "refusing to write through symbolic link");
            }
            Files.write(target, bytes);
            return PyInt.of(bytes.length);
        } catch (IOException e) {
            throw PyErrors.error("OSError", "Failed to write file: " + e.getMessage());
        }
    }

    private PyObject listDir(Map<String, PyObject> args) {
        String rel = (args != null && args.get("path") instanceof PyStr s) ? s.value() : ".";
        Path dir = resolveContainedExisting(rel);
        if (!Files.isDirectory(dir)) {
            throw PyErrors.error("FileNotFoundError", "Not a directory");
        }
        try (var stream = Files.list(dir)) {
            List<PyObject> names = new ArrayList<>();
            stream.sorted().forEach(p -> names.add(new PyStr(p.getFileName().toString())));
            return new PyList(names);
        } catch (IOException e) {
            throw PyErrors.error("OSError", "Failed to list directory: " + e.getMessage());
        }
    }

    private boolean exists(Map<String, PyObject> args) {
        String rel = requireRelativePathArg(args);
        Path candidate = canonicalRoot.resolve(rel).normalize();
        if (!candidate.startsWith(canonicalRoot)) {
            throw PyErrors.error("SecurityError", "path escapes workspace root");
        }
        if (!Files.exists(candidate, LinkOption.NOFOLLOW_LINKS)) {
            return false;
        }
        try {
            return candidate.toRealPath().startsWith(canonicalRoot);
        } catch (IOException e) {
            return false;
        }
    }

    private static String requireRelativePathArg(Map<String, PyObject> args) {
        PyObject p = args != null ? args.get("path") : null;
        if (!(p instanceof PyStr s) || s.value().isEmpty()) {
            throw PyErrors.error("TypeError", "requires non-empty str 'path'");
        }
        if (s.value().indexOf('\0') >= 0) {
            throw PyErrors.error("ValueError", "null byte in path");
        }
        Path parsed = Path.of(s.value());
        if (parsed.isAbsolute()) {
            throw PyErrors.error("SecurityError", "absolute paths are forbidden in scoped workspace");
        }
        return s.value();
    }

    private Path resolveContainedExisting(String rel) {
        Path normalized = canonicalRoot.resolve(rel).normalize();
        if (!normalized.startsWith(canonicalRoot)) {
            throw PyErrors.error("SecurityError", "path escapes workspace root");
        }
        try {
            Path real = normalized.toRealPath();
            if (!real.startsWith(canonicalRoot)) {
                throw PyErrors.error("SecurityError", "symlink target escapes workspace root");
            }
            return real;
        } catch (IOException e) {
            throw PyErrors.error("FileNotFoundError", "Path not found: " + rel);
        }
    }

    private Path resolveContainedForWrite(String rel) {
        Path normalized = canonicalRoot.resolve(rel).normalize();
        if (!normalized.startsWith(canonicalRoot)) {
            throw PyErrors.error("SecurityError", "path escapes workspace root");
        }
        // Walk existing ancestor to verify no symlink redirects out of canonicalRoot
        Path probe = normalized.getParent();
        while (probe != null && !Files.exists(probe, LinkOption.NOFOLLOW_LINKS)) {
            probe = probe.getParent();
        }
        if (probe != null) {
            try {
                if (!probe.toRealPath().startsWith(canonicalRoot)) {
                    throw PyErrors.error("SecurityError", "parent symlink escapes workspace root");
                }
            } catch (IOException e) {
                throw PyErrors.error("OSError", "Cannot verify workspace parent: " + e.getMessage());
            }
        }
        return normalized;
    }

    @Override
    public Map<String, PyObject> snapshotState() {
        Map<String, PyObject> s = new LinkedHashMap<>();
        s.put("read_only", PyBool.of(readOnly));
        s.put("ops_count", PyInt.of(opsCount.get()));
        return Collections.unmodifiableMap(s);
    }
}
