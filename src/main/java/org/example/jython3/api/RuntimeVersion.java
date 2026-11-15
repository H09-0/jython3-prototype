package org.example.jython3.api;

import java.util.Objects;

/**
 * Semantic version descriptor for the Jython 3 public embedding API.
 * Hosts (such as Oculix/Cinnamon) use {@link #isCompatibleWith(RuntimeVersion)}
 * to verify major-version compatibility and minimum minor feature level.
 */
public record RuntimeVersion(int major, int minor, int patch, String qualifier) {

    public static final RuntimeVersion CURRENT_API = new RuntimeVersion(1, 0, 0, "beta");

    public RuntimeVersion {
        if (major < 0 || minor < 0 || patch < 0) {
            throw new IllegalArgumentException("version components must be non-negative");
        }
        qualifier = qualifier == null ? "" : qualifier;
    }

    /**
     * Returns {@code true} when this runtime has the same {@code major} version
     * and at least the required {@code minor} (and {@code patch} when minor matches).
     */
    public boolean isCompatibleWith(RuntimeVersion required) {
        Objects.requireNonNull(required, "required");
        if (this.major != required.major) return false;
        if (this.minor != required.minor) return this.minor > required.minor;
        return this.patch >= required.patch;
    }

    @Override
    public String toString() {
        String base = major + "." + minor + "." + patch;
        return qualifier.isEmpty() ? base : base + "-" + qualifier;
    }
}
