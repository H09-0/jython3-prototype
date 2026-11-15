package org.example.jython3.runtime;

import org.example.jython3.security.HostAccessPolicy;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * Policy-aware, symlink-safe file module and package loader.
 *
 * <p>Resolves dotted module and package names against controlled search paths,
 * rejecting path traversal, non-package directories lacking {@code __init__.py},
 * and symlinks that escape the search root. Maintains per-interpreter module state
 * ({@code INITIALIZING} / {@code LOADED}) for circular import handling and evicts
 * partially initialized modules if loading fails.
 */
public final class ModuleLoader {

    public enum ModuleState { INITIALIZING, LOADED }

    public record ModuleEntry(PyModule module, ModuleState state) {}

    public record ResolvedModule(Path realFile, boolean isPackage) {}

    @FunctionalInterface
    public interface ModuleRunner {
        void execute(PyModule module, Path filePath, String source, Map<String, PyObject> scope);
    }

    private static final long MAX_MODULE_BYTES = 10_000_000L;
    private final List<Path> searchPaths = new ArrayList<>();
    private final Map<String, ModuleEntry> cache = new ConcurrentHashMap<>();
    private final PyDict sysModules = new PyDict();
    private final PyList sysPath = new PyList(new ArrayList<>());
    private final List<Consumer<Path>> importListeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    public ModuleLoader() {
        addSearchPath(Path.of("modules"));
    }

    public void addImportListener(Consumer<Path> listener) {
        if (listener != null) importListeners.add(listener);
    }

    public synchronized void addSearchPath(Path dir) {
        Objects.requireNonNull(dir, "dir");
        if (!searchPaths.contains(dir)) {
            searchPaths.add(dir);
            sysPath.items().add(new PyStr(dir.toString()));
        }
    }

    public synchronized void insertSearchPath(int index, Path dir) {
        Objects.requireNonNull(dir, "dir");
        int idx = Math.max(0, Math.min(index, searchPaths.size()));
        searchPaths.remove(dir);
        searchPaths.add(idx, dir);
        String s = dir.toString();
        sysPath.items().removeIf(p -> p instanceof PyStr ps && ps.value().equals(s));
        int sysIdx = Math.max(0, Math.min(idx, sysPath.items().size()));
        sysPath.items().add(sysIdx, new PyStr(s));
    }

    public synchronized void removeSearchPath(Path dir) {
        if (dir == null) return;
        searchPaths.remove(dir);
        String s = dir.toString();
        sysPath.items().removeIf(p -> p instanceof PyStr ps && ps.value().equals(s));
    }

    public void invalidate(String name) {
        if (name == null) return;
        cache.remove(name);
        sysModules.remove(new PyStr(name));
    }

    public synchronized List<Path> searchPaths() {
        return List.copyOf(searchPaths);
    }

    public PyDict sysModules() { return sysModules; }

    public PyList sysPath() { return sysPath; }

    public void clear() {
        cache.clear();
        sysModules.clear();
    }

    public void registerBuiltin(PyModule mod) {
        cache.put(mod.name(), new ModuleEntry(mod, ModuleState.LOADED));
        sysModules.put(new PyStr(mod.name()), mod);
    }

    public PyModule getCached(String name) {
        ModuleEntry entry = cache.get(name);
        return entry != null ? entry.module() : null;
    }

    public boolean isInitializing(String name) {
        ModuleEntry entry = cache.get(name);
        return entry != null && entry.state() == ModuleState.INITIALIZING;
    }

    /**
     * Validates that {@code fullName} is a well-formed dotted Python identifier
     * with no path traversal, slashes, empty segments, or control characters.
     */
    public static String[] splitAndValidate(String fullName) {
        if (fullName == null || fullName.isEmpty() || fullName.indexOf('\0') >= 0
                || fullName.contains("/") || fullName.contains("\\")) {
            throw PyErrors.error("ImportError", "invalid module name '" + fullName + "'");
        }
        String[] parts = fullName.split("\\.", -1);
        for (String p : parts) {
            if (!p.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw PyErrors.error("ImportError", "invalid module name '" + fullName + "'");
            }
        }
        return parts;
    }

    /**
     * Loads {@code fullName} (including all enclosing packages when dotted).
     * Returns the leaf {@link PyModule}.
     */
    public PyModule load(String fullName,
                         HostAccessPolicy hostPolicy,
                         Map<String, PyObject> builtinSymbols,
                         ModuleRunner runner) {
        String[] parts = splitAndValidate(fullName);
        if (parts.length == 1) {
            return loadSingle(fullName, parts, hostPolicy, builtinSymbols, runner);
        }

        PyModule parent = null;
        var prefix = new StringBuilder();
        for (int i = 0; i < parts.length; i++) {
            if (i > 0) prefix.append('.');
            prefix.append(parts[i]);
            String subName = prefix.toString();
            String[] subParts = new String[i + 1];
            System.arraycopy(parts, 0, subParts, 0, i + 1);
            PyModule mod = loadSingle(subName, subParts, hostPolicy, builtinSymbols, runner);
            if (parent != null) {
                parent.attrs().put(parts[i], mod);
            }
            parent = mod;
        }
        return parent;
    }

    private PyModule loadSingle(String fullName, String[] parts,
                                HostAccessPolicy hostPolicy,
                                Map<String, PyObject> builtinSymbols,
                                ModuleRunner runner) {
        ModuleEntry existing = cache.get(fullName);
        if (existing != null) {
            return existing.module();
        }

        ResolvedModule resolved = resolveOnDisk(fullName, parts, hostPolicy);
        if (resolved == null) {
            throw PyErrors.error("ImportError", "no module named '" + fullName + "'");
        }

        String content;
        try {
            long size = Files.size(resolved.realFile());
            if (size > MAX_MODULE_BYTES) {
                throw PyErrors.error("MemoryError",
                        "module '" + fullName + "' exceeds maximum source size (" + MAX_MODULE_BYTES + " bytes)");
            }
            content = Files.readString(resolved.realFile());
        } catch (IOException e) {
            throw PyErrors.error("ImportError", "cannot read module '" + fullName + "': " + e.getMessage());
        }

        Map<String, PyObject> moduleScope = new HashMap<>(builtinSymbols);
        moduleScope.put("__name__", new PyStr(fullName));
        moduleScope.put("__file__", new PyStr(resolved.realFile().toString()));
        String pkgName;
        if (resolved.isPackage()) {
            pkgName = fullName;
        } else {
            int dot = fullName.lastIndexOf('.');
            pkgName = dot > 0 ? fullName.substring(0, dot) : "";
        }
        moduleScope.put("__package__", new PyStr(pkgName));

        PyModule module = new PyModule(fullName, moduleScope);
        cache.put(fullName, new ModuleEntry(module, ModuleState.INITIALIZING));
        sysModules.put(new PyStr(fullName), module);

        try {
            runner.execute(module, resolved.realFile(), content, moduleScope);
            cache.put(fullName, new ModuleEntry(module, ModuleState.LOADED));
            for (Consumer<Path> listener : importListeners) {
                try {
                    listener.accept(resolved.realFile());
                } catch (RuntimeException ignored) {
                }
            }
            return module;
        } catch (RuntimeException ex) {
            cache.remove(fullName);
            sysModules.remove(new PyStr(fullName));
            throw ex;
        }
    }

    private ResolvedModule resolveOnDisk(String fullName, String[] parts, HostAccessPolicy hostPolicy) {
        List<Path> candidates = effectiveSearchPaths(hostPolicy);
        for (Path root : candidates) {
            if (!Files.exists(root, LinkOption.NOFOLLOW_LINKS) || !Files.isDirectory(root)) {
                continue;
            }
            Path realRoot;
            try {
                realRoot = root.toRealPath();
            } catch (IOException e) {
                continue;
            }

            Path curDir = root;
            boolean packageChainOk = true;
            for (int i = 0; i < parts.length - 1; i++) {
                curDir = curDir.resolve(parts[i]);
                if (Files.isSymbolicLink(curDir)) {
                    throw PyErrors.error("SecurityError",
                            "symlink traversal blocked while importing '" + fullName + "'");
                }
                if (!Files.isDirectory(curDir) || !Files.exists(curDir.resolve("__init__.py"), LinkOption.NOFOLLOW_LINKS)) {
                    packageChainOk = false;
                    break;
                }
            }
            if (!packageChainOk) continue;

            String leaf = parts[parts.length - 1];

            // 1. Package directory: leaf/__init__.py
            Path pkgDir = curDir.resolve(leaf);
            if (Files.exists(pkgDir, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(pkgDir)) {
                    throw PyErrors.error("SecurityError",
                            "symlink escape blocked while importing '" + fullName + "'");
                }
                Path initFile = pkgDir.resolve("__init__.py");
                if (Files.exists(initFile, LinkOption.NOFOLLOW_LINKS)) {
                    Path realFile = verifyContainedRegularFile(initFile, realRoot, fullName, hostPolicy);
                    return new ResolvedModule(realFile, true);
                }
            }

            // 2. Module file: leaf.py
            Path modFile = curDir.resolve(leaf + ".py");
            if (Files.exists(modFile, LinkOption.NOFOLLOW_LINKS)) {
                Path realFile = verifyContainedRegularFile(modFile, realRoot, fullName, hostPolicy);
                return new ResolvedModule(realFile, false);
            }

            // 3. OculiX/Sikuli bundle directory: leaf.sikuli/leaf.py
            Path sikuliDir = curDir.resolve(leaf + ".sikuli");
            if (Files.exists(sikuliDir, LinkOption.NOFOLLOW_LINKS)) {
                if (Files.isSymbolicLink(sikuliDir)) {
                    throw PyErrors.error("SecurityError",
                            "symlink escape blocked while importing '" + fullName + "'");
                }
                if (Files.isDirectory(sikuliDir)) {
                    try {
                        if (!sikuliDir.toRealPath().startsWith(realRoot)) {
                            throw PyErrors.error("SecurityError",
                                    "sikuli bundle path escape blocked while importing '" + fullName + "'");
                        }
                    } catch (IOException e) {
                        throw PyErrors.error("ImportError", "cannot resolve sikuli bundle for '" + fullName + "'");
                    }
                    Path sikuliScript = sikuliDir.resolve(leaf + ".py");
                    if (Files.exists(sikuliScript, LinkOption.NOFOLLOW_LINKS)) {
                        Path realFile = verifyContainedRegularFile(sikuliScript, realRoot, fullName, hostPolicy);
                        return new ResolvedModule(realFile, false);
                    }
                }
            }
        }
        return null;
    }

    private static Path verifyContainedRegularFile(Path file, Path realRoot, String fullName,
                                                   HostAccessPolicy hostPolicy) {
        if (Files.isSymbolicLink(file)) {
            throw PyErrors.error("SecurityError",
                    "symlink escape blocked while importing '" + fullName + "'");
        }
        Path realFile;
        try {
            realFile = file.toRealPath();
        } catch (IOException e) {
            throw PyErrors.error("ImportError", "cannot resolve module '" + fullName + "'");
        }
        if (!realFile.startsWith(realRoot) || !Files.isRegularFile(realFile)) {
            throw PyErrors.error("SecurityError",
                    "module path escape blocked while importing '" + fullName + "'");
        }
        if (!hostPolicy.canImportFromDisk(realFile)) {
            throw PyErrors.error("SecurityError",
                    "importing module '" + fullName + "' from filesystem is restricted");
        }
        return realFile;
    }

    private List<Path> effectiveSearchPaths(HostAccessPolicy hostPolicy) {
        var result = new ArrayList<Path>(searchPaths);
        for (PyObject entry : sysPath.items()) {
            if (entry instanceof PyStr s && !s.value().isEmpty()) {
                Path p = Path.of(s.value());
                if (!result.contains(p) && hostPolicy.canImportFromDisk(p)) {
                    result.add(p);
                }
            }
        }
        return result;
    }
}
