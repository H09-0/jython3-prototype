package org.example.jython3;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Method;
import java.util.List;

import org.example.jython3.bridge.JavaBridge;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyJavaClass;
import org.example.jython3.runtime.PyJavaMethod;
import org.example.jython3.runtime.PyJavaObject;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.security.AllowlistPolicy;
import org.example.jython3.security.DenyAllPolicy;
import org.example.jython3.security.JavaAccessPolicy;
import org.junit.jupiter.api.Test;

/**
 * Tests the Java access seam independently of the interpreter. The bridge is
 * useful without the VM, and so is the policy that guards it.
 */
class JavaAccessPolicyTest {

    /** A type the allowlist would reject, so these tests use their own policy. */
    public static final class Sample {
        public static int VALUE = 3;
        public int value;

        public Sample() {}
        public Sample(int value) { this.value = value; }

        public String echo(String text) { return text; }
    }

    private static final String SAMPLE = Sample.class.getName();

    private static JavaAccessPolicy policy(boolean construct, boolean methods,
                                           boolean read, boolean write) {
        return new JavaAccessPolicy() {
            @Override public boolean canAccessClass(String binaryName) {
                return SAMPLE.equals(binaryName);
            }
            @Override public boolean canConstructClass(String binaryName, String descriptor) {
                return construct;
            }
            @Override public boolean canCallMethod(String owner, String name, String descriptor) {
                return methods;
            }
            @Override public boolean canReadField(String owner, String name) {
                return read;
            }
            @Override public boolean canWriteField(String owner, String name) {
                return write;
            }
        };
    }

    private static PyJavaClass resolve(JavaAccessPolicy policy) {
        return assertInstanceOf(PyJavaClass.class,
                JavaBridge.resolveClassOrPackage(SAMPLE, policy));
    }

    @Test
    void trustedPolicyAllowsTheDocumentedSurfaceAndDeniesInternals() {
        JavaAccessPolicy trusted = AllowlistPolicy.trusted();
        assertTrue(trusted.canAccessClass("java.lang.String"));
        assertTrue(trusted.canAccessClass("java.util.ArrayList"));
        assertFalse(trusted.canAccessClass("sun.misc.Unsafe"));
        assertFalse(trusted.canAccessClass("java.lang.System"));
        assertFalse(trusted.canAccessClass("java.lang.reflect.Method"));
        // Security enhancements: thread manipulation, deserialization, class loaders, process handles
        assertFalse(trusted.canAccessClass("java.lang.Thread"));
        assertFalse(trusted.canAccessClass("java.lang.ThreadGroup"));
        assertFalse(trusted.canAccessClass("java.lang.Process"));
        assertFalse(trusted.canAccessClass("java.lang.ProcessHandle"));
        assertFalse(trusted.canAccessClass("java.io.ObjectInputStream"));
        assertFalse(trusted.canAccessClass("java.io.ObjectOutputStream"));
        assertFalse(trusted.canAccessClass("java.net.URLClassLoader"));
        assertFalse(trusted.canAccessClass("java.lang.ClassLoader"));
        assertFalse(trusted.canAccessClass("java.awt.Desktop"));
        // Inner classes of forbidden classes are also rejected
        assertFalse(trusted.canAccessClass("java.lang.Thread$State"));
        assertFalse(trusted.canAccessClass("java.lang.ProcessBuilder$Redirect"));
        // Restricted method names
        assertTrue(trusted.canCallMethod("java.lang.String", "toUpperCase", "()V"));
        assertFalse(trusted.canCallMethod("java.lang.String", "getClass", "()V"));
        assertFalse(trusted.canCallMethod("java.lang.Class", "newInstance", "()V"));
        assertFalse(trusted.canCallMethod("java.lang.Class", "getMethods", "()V"));
        assertFalse(trusted.canCallMethod("java.lang.Class", "getDeclaredMethods", "()V"));
        assertFalse(trusted.canCallMethod("java.lang.ClassLoader", "loadClass", "()V"));
        assertFalse(trusted.canCallMethod("java.lang.Thread", "getThreadGroup", "()V"));
        assertFalse(trusted.canCallMethod("java.lang.Thread", "interrupt", "()V"));
        assertFalse(trusted.canCallMethod("java.io.ObjectInputStream", "readObject", "()V"));
        assertFalse(trusted.canCallMethod("java.awt.Desktop", "getDesktop", "()V"));
        assertFalse(trusted.canCallMethod("java.awt.Desktop", "browse", "()V"));
        assertFalse(trusted.canCallMethod("java.awt.Desktop", "open", "()V"));
    }

    @Test
    void denyAllPolicyDeniesEverything() {
        assertFalse(DenyAllPolicy.INSTANCE.canAccessClass("java.lang.String"));
        assertFalse(DenyAllPolicy.INSTANCE.canConstructClass("java.lang.String", "()V"));
        assertFalse(DenyAllPolicy.INSTANCE.canCallMethod("java.lang.String", "length", "()V"));
        assertFalse(DenyAllPolicy.INSTANCE.canReadField("java.lang.Integer", "MAX_VALUE"));
        assertFalse(DenyAllPolicy.INSTANCE.canWriteField("java.lang.Integer", "MAX_VALUE"));
    }

    @Test
    void classAccessWithoutConstructorAccessCannotInstantiate() {
        PyJavaClass type = resolve(policy(false, true, true, true));
        InterpreterException e = assertThrows(InterpreterException.class,
                () -> type.newInstance(new PyObject[0]));
        assertEquals("SecurityError", e.typeName());
    }

    @Test
    void eachConstructorIsGrantedSeparately() {
        JavaAccessPolicy intOnly = new JavaAccessPolicy() {
            @Override public boolean canAccessClass(String binaryName) { return SAMPLE.equals(binaryName); }
            @Override public boolean canConstructClass(String binaryName, String descriptor) {
                return "(I)V".equals(descriptor);
            }
            @Override public boolean canCallMethod(String owner, String name, String descriptor) { return true; }
            @Override public boolean canReadField(String owner, String name) { return true; }
            @Override public boolean canWriteField(String owner, String name) { return true; }
        };
        PyJavaClass type = resolve(intOnly);

        assertInstanceOf(PyJavaObject.class, type.newInstance(new PyObject[]{new PyInt(7)}));
        assertThrows(InterpreterException.class, () -> type.newInstance(new PyObject[0]),
                "the no-argument constructor was not granted");
    }

    @Test
    void methodAccessIsCheckedAtLookupAndAtInvoke() throws Exception {
        PyJavaClass type = resolve(policy(true, false, true, true));
        PyJavaObject instance = (PyJavaObject) type.newInstance(new PyObject[0]);
        InterpreterException lookup = assertThrows(InterpreterException.class,
                () -> instance.getMember("echo"));
        assertEquals("SecurityError", lookup.typeName());

        Method echo = Sample.class.getMethod("echo", String.class);
        PyJavaMethod wrapper = new PyJavaMethod(new Sample(), Sample.class, "echo",
                List.of(echo), policy(true, false, true, true));
        InterpreterException invoke = assertThrows(InterpreterException.class,
                () -> wrapper.invoke(new PyObject[]{new PyStr("x")}));
        assertEquals("SecurityError", invoke.typeName());
    }

    @Test
    void permittedMethodsStillResolveOverloadsAndConvertValues() {
        PyJavaClass type = new PyJavaClass(java.lang.String.class, AllowlistPolicy.trusted());
        PyObject valueOf = type.getMember("valueOf");

        assertEquals("42", ((PyStr) assertInstanceOf(PyJavaMethod.class, valueOf)
                .invoke(new PyObject[]{new PyInt(42)})).value());
        assertEquals(7, JavaBridge.toJavaObject(new PyInt(7), int.class));
        assertEquals(7, ((PyInt) JavaBridge.toPyObject(7, AllowlistPolicy.trusted())).value());
    }

    @Test
    void fieldAccessIsGrantedSeparatelyFromMethodAccess() {
        JavaAccessPolicy readOnly = policy(true, true, true, false);
        PyJavaClass type = resolve(readOnly);
        assertEquals(3, ((PyInt) type.getMember("VALUE")).value());

        PyJavaObject instance = (PyJavaObject) type.newInstance(new PyObject[0]);
        assertThrows(InterpreterException.class,
                () -> instance.setMember("value", new PyInt(9)));
    }

    @Test
    void permittedFieldWritesWork() {
        PyJavaClass type = resolve(policy(true, true, true, true));
        PyJavaObject instance = (PyJavaObject) type.newInstance(new PyObject[]{new PyInt(1)});
        instance.setMember("value", new PyInt(9));
        assertEquals(9, ((PyInt) instance.getMember("value")).value());
    }

    @Test
    void sandboxedPolicyAllowsOnlyPureDataAndDeniesIOAndNetwork() {
        JavaAccessPolicy sandboxed = org.example.jython3.security.SandboxedPolicy.get();
        // Allowed pure data structures and math
        assertTrue(sandboxed.canAccessClass("java.lang.String"));
        assertTrue(sandboxed.canAccessClass("java.lang.Integer"));
        assertTrue(sandboxed.canAccessClass("java.lang.Math"));
        assertTrue(sandboxed.canAccessClass("java.util.ArrayList"));
        assertTrue(sandboxed.canAccessClass("java.util.HashMap"));
        assertTrue(sandboxed.canAccessClass("java.math.BigInteger"));
        // Completely denies I/O
        assertFalse(sandboxed.canAccessClass("java.io.File"));
        assertFalse(sandboxed.canAccessClass("java.io.FileInputStream"));
        assertFalse(sandboxed.canAccessClass("java.io.FileOutputStream"));
        assertFalse(sandboxed.canAccessClass("java.io.InputStream"));
        // Completely denies Network
        assertFalse(sandboxed.canAccessClass("java.net.Socket"));
        assertFalse(sandboxed.canAccessClass("java.net.URL"));
        assertFalse(sandboxed.canAccessClass("java.net.HttpURLConnection"));
        // Completely denies UI
        assertFalse(sandboxed.canAccessClass("java.awt.Desktop"));
        assertFalse(sandboxed.canAccessClass("java.awt.Color"));
    }

    @Test
    void eachInterpreterKeepsItsOwnPolicy() {
        Jython3Interpreter trusted = new Jython3Interpreter();
        Jython3Interpreter restricted = new Jython3Interpreter(DenyAllPolicy.INSTANCE);

        assertSame(AllowlistPolicy.trusted(), trusted.javaAccessPolicy());
        assertSame(DenyAllPolicy.INSTANCE, restricted.javaAccessPolicy());

        assertDoesNotThrow(() -> trusted.eval("from java.lang import String"));

        InterpreterException e = assertThrows(InterpreterException.class,
                () -> restricted.eval("from java.lang import String"));
        assertEquals("SecurityError", e.typeName());
    }
}
