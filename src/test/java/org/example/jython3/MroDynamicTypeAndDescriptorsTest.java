package org.example.jython3;

import org.example.jython3.api.EmbeddedRuntime;
import org.example.jython3.differential.DifferentialHarness;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyHostHandle;
import org.example.jython3.runtime.PyProperty;
import org.example.jython3.runtime.PyStr;
import org.example.jython3.security.DenyAllPolicy;
import org.example.jython3.security.ExecutionLimits;
import org.example.jython3.security.PolicySanitizer;
import org.example.jython3.ui.HeadlessUiToolkit;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Tests for Phase 2: C3 MRO, cooperative diamond super(), dynamic 3-arg type(),
 * @property (getter/setter/deleter), @staticmethod, @classmethod, and security boundaries.
 */
class MroDynamicTypeAndDescriptorsTest {

    @Test
    @DisplayName("C3 MRO, diamond super(), isinstance multiple inheritance, and inconsistent MRO TypeError match CPython")
    void c3MroAndDiamondSuperMatchCPython() {
        String code = """
            class A:
                def who(self):
                    return ["A"]
            class B(A):
                def who(self):
                    return ["B"] + super().who()
            class C(A):
                def who(self):
                    return ["C"] + super().who()
            class D(B, C):
                def who(self):
                    return ["D"] + super().who()

            d = D()
            print(d.who())
            print(isinstance(d, C), isinstance(d, B), isinstance(d, A), issubclass(D, C))
            print([c.__name__ for c in D.__mro__])
            print([c.__name__ for c in D.__bases__])

            class X:
                pass
            class Y:
                pass
            class XY(X, Y):
                pass
            class YX(Y, X):
                pass
            try:
                class Bad(XY, YX):
                    pass
                print("NO_ERROR")
            except TypeError:
                print("MRO_TYPE_ERROR")
            """;
        DifferentialHarness.assertMatchesCPython(code);
    }

    @Test
    @DisplayName("Dynamic 3-arg type(name, bases, dict) and descriptors (@property, @staticmethod, @classmethod) match CPython")
    void dynamicTypeAndDescriptorsMatchCPython() {
        String code = """
            class Base:
                scale = 2
                def __init__(self, v):
                    self._v = v

            def get_val(self):
                return self._v * self.scale
            def set_val(self, val):
                if val < 0:
                    raise ValueError("negative")
                self._v = val
            def del_val(self):
                self._v = 0

            Dyn = type("Dyn", (Base,), {
                "value": property(get_val, set_val, del_val),
                "add": staticmethod(lambda a, b: a + b),
                "from_scaled": classmethod(lambda cls, x: cls(x // cls.scale)),
            })

            obj = Dyn.from_scaled(10)
            print(obj.value, obj.add(3, 4), Dyn.add(5, 6), isinstance(obj, Base), Dyn.__name__)
            obj.value = 12
            print(obj.value)
            del obj.value
            print(obj.value)

            class Box:
                def __init__(self, x):
                    self._x = x
                @property
                def x(self):
                    return self._x
                @x.setter
                def x(self, val):
                    self._x = val + 1
                @staticmethod
                def tag():
                    return "box"
                @classmethod
                def make(cls, val):
                    return cls(val * 10)

            b = Box.make(3)
            print(b.x, b.tag(), Box.tag())
            b.x = 40
            print(b.x)
            """;
        DifferentialHarness.assertMatchesCPython(code);
    }

    @Test
    @DisplayName("Descriptors enforce recursion limits and PolicySanitizer blocks handle smuggling inside property")
    void descriptorSecurityBoundaries() {
        try (var hostA = EmbeddedRuntime.builder().uiToolkit(new HeadlessUiToolkit()).build();
             var hostB = EmbeddedRuntime.builder().limits(ExecutionLimits.SANDBOXED).uiToolkit(new HeadlessUiToolkit()).build()) {

            PyHostHandle hA = PyHostHandle.builder("secret.H")
                    .bindSession(hostA.hostSession())
                    .prop("v", () -> new PyStr("secret"))
                    .build();
            PyProperty smuggledProp = new PyProperty(hA, null, null, null);
            assertEquals("SecurityError",
                    assertThrows(InterpreterException.class,
                            () -> PolicySanitizer.sanitize(smuggledProp, DenyAllPolicy.INSTANCE, hostB.hostSession()))
                            .typeName());

            InterpreterException rec = assertThrows(InterpreterException.class, () -> hostB.eval("""
                class Loop:
                    @property
                    def x(self):
                        return self.x
                Loop().x
                """));
            assertEquals("RecursionError", rec.typeName());
        }
    }
}
