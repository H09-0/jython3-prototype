package org.example.jython3.security;

/** Denies every Java access. Useful for restricted interpreters and tests. */
public final class DenyAllPolicy implements JavaAccessPolicy {

    public static final DenyAllPolicy INSTANCE = new DenyAllPolicy();

    private DenyAllPolicy() {}

    @Override public boolean canAccessClass(String binaryName) { return false; }
    @Override public boolean canConstructClass(String binaryName, String descriptor) { return false; }
    @Override public boolean canCallMethod(String owner, String name, String descriptor) { return false; }
    @Override public boolean canReadField(String owner, String name) { return false; }
    @Override public boolean canWriteField(String owner, String name) { return false; }
}
