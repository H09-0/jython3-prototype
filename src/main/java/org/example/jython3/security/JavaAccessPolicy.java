package org.example.jython3.security;

/**
 * Decides what Python code may do with Java classes, constructors, methods,
 * and fields.
 *
 * <p>A policy is supplied per interpreter. It is not a sandbox: it is the
 * language-level gate in front of reflection, and untrusted code still needs
 * an OS-level process or container boundary.
 *
 * <p>Access to a class does not imply access to its constructors or members.
 * Hosts can grant read-only views of a type, or permit a single constructor
 * while denying the rest.
 */
public interface JavaAccessPolicy {

    /** Whether the class may be referenced at all. */
    boolean canAccessClass(String binaryName);

    /**
     * Whether a constructor may be invoked. The descriptor covers parameter
     * types only, for example {@code (Ljava/lang/String;I)V}.
     */
    boolean canConstructClass(String binaryName, String constructorDescriptor);

    /**
     * Whether a method may be invoked. The descriptor covers parameter types
     * only, for example {@code (I)Ljava/lang/String;}.
     */
    boolean canCallMethod(String owner, String methodName, String descriptor);

    /** Whether a field may be read. */
    boolean canReadField(String owner, String fieldName);

    /** Whether a field may be written. */
    boolean canWriteField(String owner, String fieldName);
}
