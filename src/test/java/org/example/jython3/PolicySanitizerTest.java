package org.example.jython3;

import org.example.jython3.compile.CompiledCode;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyClass;
import org.example.jython3.runtime.PyDict;
import org.example.jython3.runtime.PyFunction;
import org.example.jython3.runtime.PyInstance;
import org.example.jython3.runtime.PyInt;
import org.example.jython3.runtime.PyJavaClass;
import org.example.jython3.runtime.PyJavaObject;
import org.example.jython3.runtime.PyList;
import org.example.jython3.runtime.PyObject;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.security.AllowlistPolicy;
import org.example.jython3.security.DenyAllPolicy;
import org.example.jython3.security.JavaAccessPolicy;
import org.example.jython3.security.PolicySanitizer;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for {@link PolicySanitizer} covering circular object graphs, nested Java
 * wrappers, function closures, and class inheritance hierarchies.
 */
public class PolicySanitizerTest {

    public static final class SecretService {
        public String token() { return "top-secret"; }
    }

    private static final JavaAccessPolicy ALLOW_ALL = new JavaAccessPolicy() {
        @Override public boolean canAccessClass(String binaryName) { return true; }
        @Override public boolean canConstructClass(String binaryName, String descriptor) { return true; }
        @Override public boolean canCallMethod(String owner, String name, String descriptor) { return true; }
        @Override public boolean canReadField(String owner, String name) { return true; }
        @Override public boolean canWriteField(String owner, String name) { return true; }
    };

    @Test
    @DisplayName("Self-referential PyList is sanitized without StackOverflowError and enforces policy")
    void testCircularListSanitization() {
        var items = new ArrayList<PyObject>();
        var circularList = new PyList(items);
        items.add(PyInt.of(1));
        items.add(circularList); // self-reference

        PyList sanitized = (PyList) PolicySanitizer.sanitize(circularList, DenyAllPolicy.INSTANCE);
        assertEquals(2, sanitized.items().size());
        assertEquals(PyInt.of(1), sanitized.items().get(0));
        assertSame(sanitized, sanitized.items().get(1), "Circular reference must be preserved in sanitized copy");

        // Inject restricted Java object into circular list -> must throw SecurityError
        items.add(new PyJavaObject(new SecretService(), ALLOW_ALL));
        assertThrows(InterpreterException.class, () -> {
            PolicySanitizer.sanitize(circularList, DenyAllPolicy.INSTANCE);
        });
    }

    @Test
    @DisplayName("Self-referential PyDict is sanitized without StackOverflowError and enforces policy")
    void testCircularDictSanitization() {
        var dict = new PyDict();
        var holder = new PyList(new ArrayList<>());
        dict.put(new PyStr("self"), holder);
        holder.items().add(dict); // cycle: dict -> holder -> dict

        PyDict sanitized = (PyDict) PolicySanitizer.sanitize(dict, DenyAllPolicy.INSTANCE);
        PyList sanitizedHolder = (PyList) sanitized.get(new PyStr("self"));
        assertNotNull(sanitizedHolder);
        assertSame(sanitized, sanitizedHolder.items().get(0), "Circular dict reference must be preserved");

        // Add restricted Java object inside circular dict -> must throw SecurityError
        dict.put(new PyStr("secret"), new PyJavaObject(new SecretService(), ALLOW_ALL));
        assertThrows(InterpreterException.class, () -> {
            PolicySanitizer.sanitize(dict, DenyAllPolicy.INSTANCE);
        });
    }

    @Test
    @DisplayName("Nested Java objects in structures are retargeted to destination policy or rejected")
    void testNestedJavaObjectRetargetingAndRejection() {
        var sbWrapper = new PyJavaObject(new StringBuilder("ok"), ALLOW_ALL);
        var nested = new PyDict();
        nested.put(new PyStr("items"), new PyList(List.of(sbWrapper)));

        // Trusted policy permits StringBuilder and retargets wrapper policy
        var trustedPolicy = AllowlistPolicy.trusted();
        PyDict sanitizedOk = (PyDict) PolicySanitizer.sanitize(nested, trustedPolicy);
        PyList innerList = (PyList) sanitizedOk.get(new PyStr("items"));
        PyJavaObject retargeted = (PyJavaObject) innerList.items().get(0);
        assertSame(trustedPolicy, retargeted.policy());

        // DenyAllPolicy rejects StringBuilder inside nested structure
        assertThrows(InterpreterException.class, () -> {
            PolicySanitizer.sanitize(nested, DenyAllPolicy.INSTANCE);
        });
    }

    @Test
    @DisplayName("PyFunction closure is sanitized and rejects forbidden Java wrappers")
    void testPyFunctionClosureSanitization() {
        var dummyCode = new CompiledCode(new int[0], new Object[0], new int[0]);
        Map<String, PyObject> closure = new HashMap<>();
        closure.put("safe", PyInt.of(42));
        closure.put("java_cls", new PyJavaClass(StringBuilder.class, ALLOW_ALL));

        var fn = new PyFunction("my_fn", List.of(), dummyCode, closure);

        // Allowed under trusted policy
        var trustedPolicy = AllowlistPolicy.trusted();
        PyFunction sanitizedFn = (PyFunction) PolicySanitizer.sanitize(fn, trustedPolicy);
        PyJavaClass retargetedCls = (PyJavaClass) sanitizedFn.closure().get("java_cls");
        assertSame(trustedPolicy, retargetedCls.policy());

        // Rejected under DenyAllPolicy
        assertThrows(InterpreterException.class, () -> {
            PolicySanitizer.sanitize(fn, DenyAllPolicy.INSTANCE);
        });
    }

    @Test
    @DisplayName("PyClass with inheritance and PyInstance fields are sanitized across hierarchy")
    void testClassInheritanceAndInstanceSanitization() {
        Map<String, PyObject> baseMembers = new HashMap<>();
        baseMembers.put("base_helper", new PyJavaObject(new StringBuilder("base"), ALLOW_ALL));
        var baseClass = new PyClass("Base", null, baseMembers);

        Map<String, PyObject> subMembers = new HashMap<>();
        subMembers.put("sub_val", PyInt.of(10));
        var subClass = new PyClass("Sub", baseClass, subMembers, List.of(baseClass));

        var instance = new PyInstance(subClass);
        instance.setField("inst_field", new PyStr("hello"));

        // Sanitize under trusted policy -> base class Java object is retargeted
        var trustedPolicy = AllowlistPolicy.trusted();
        PyInstance sanitizedInst = (PyInstance) PolicySanitizer.sanitize(instance, trustedPolicy);
        PyJavaObject sanitizedBaseHelper = (PyJavaObject) sanitizedInst.pyClass().baseClass().lookupMember("base_helper");
        assertNotNull(sanitizedBaseHelper);
        assertSame(trustedPolicy, sanitizedBaseHelper.policy());
        assertEquals(new PyStr("hello"), sanitizedInst.getField("inst_field"));

        // Sanitize under DenyAllPolicy -> must reject restricted object in base class!
        assertThrows(InterpreterException.class, () -> {
            PolicySanitizer.sanitize(instance, DenyAllPolicy.INSTANCE);
        });
    }

    @Test
    @DisplayName("Cycles through PyFunction closures and PyClass members are sanitized without StackOverflowError")
    void testCircularFunctionAndClassSanitization() {
        var dummyCode = new CompiledCode(new int[0], new Object[0], new int[0]);

        // 1. Recursive PyFunction whose closure references itself
        var recFn = new PyFunction("rec", List.of(), dummyCode, new HashMap<>());
        recFn.closure().put("rec", recFn);

        PyFunction sanitizedFn = (PyFunction) PolicySanitizer.sanitize(recFn, DenyAllPolicy.INSTANCE);
        assertSame(sanitizedFn, sanitizedFn.closure().get("rec"), "Self-referential function closure must be preserved");

        // 2. PyClass with a method closing over the class AND a singleton PyInstance of that class
        var cls = new PyClass("Node", null, new HashMap<>());
        var methodFn = new PyFunction("make", List.of(), dummyCode, new HashMap<>());
        methodFn.closure().put("Node", cls);
        cls.setMember("make", methodFn);
        var singleton = new PyInstance(cls);
        singleton.setField("self_inst", singleton);
        cls.setMember("singleton", singleton);

        PyClass sanitizedCls = (PyClass) PolicySanitizer.sanitize(cls, DenyAllPolicy.INSTANCE);
        PyFunction sanitizedMethod = (PyFunction) sanitizedCls.lookupMember("make");
        assertSame(sanitizedCls, sanitizedMethod.closure().get("Node"), "Method closure reference to owning class must be preserved");
        PyInstance sanitizedSingleton = (PyInstance) sanitizedCls.lookupMember("singleton");
        assertSame(sanitizedCls, sanitizedSingleton.pyClass());
        assertSame(sanitizedSingleton, sanitizedSingleton.getField("self_inst"));
    }
}
