package org.example.jython3.security;

import java.nio.file.Path;

/**
 * Governs Python script access to the host operating system environment
 * (filesystem, environment variables, execfile, disk-based module loading).
 *
 * <p>Separated from {@link JavaAccessPolicy} so that in-process execution capabilities
 * are explicitly enforced across native/built-in Python modules (like {@code io},
 * {@code os}, {@code execfile}) independently of Java reflection gates.
 */
public interface HostAccessPolicy {

    /** Whether the script may read from the specified host file path. */
    boolean canReadFile(Path path);

    /** Whether the script may write to or create the specified host file path. */
    boolean canWriteFile(Path path);

    /** Whether the script may list contents of the specified host directory path. */
    boolean canListDirectory(Path path);

    /** Whether the script may access host environment variables (os.environ). */
    boolean canReadEnvironment();

    /** Whether the script may execute external files via execfile(). */
    boolean canExecFile(Path path);

    /** Whether the script may import Python source files from the filesystem. */
    boolean canImportFromDisk(Path path);

    /** Fully permissive policy for trusted scripts. */
    HostAccessPolicy PERMISSIVE = new HostAccessPolicy() {
        @Override public boolean canReadFile(Path path) { return true; }
        @Override public boolean canWriteFile(Path path) { return true; }
        @Override public boolean canListDirectory(Path path) { return true; }
        @Override public boolean canReadEnvironment() { return true; }
        @Override public boolean canExecFile(Path path) { return true; }
        @Override public boolean canImportFromDisk(Path path) { return true; }
    };

    /** Strict zero-access policy for restricted/sandboxed scripts. */
    HostAccessPolicy DENY_ALL = new HostAccessPolicy() {
        @Override public boolean canReadFile(Path path) { return false; }
        @Override public boolean canWriteFile(Path path) { return false; }
        @Override public boolean canListDirectory(Path path) { return false; }
        @Override public boolean canReadEnvironment() { return false; }
        @Override public boolean canExecFile(Path path) { return false; }
        @Override public boolean canImportFromDisk(Path path) { return false; }
    };
}
