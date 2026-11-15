package org.example.jython3.logic;

import org.example.jython3.Jython3Interpreter;
import org.example.jython3.differential.DifferentialHarness;
import org.example.jython3.runtime.InterpreterException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Logic & conformance tests for OOP, C3 MRO, cooperative super(), dynamic type(),
 * dynamic __bases__ mutation, @property/@staticmethod/@classmethod, __getattr__,
 * inline attribute cache invalidation, and full dunder operator overloading.
 */
@Tag("logic")
class OopMroDescriptorsAndDundersTest {

    @Test
    @DisplayName("C3 MRO, diamond super(), dynamic type(), __bases__, descriptors, and __getattr__ match CPython")
    void oopMroDescriptorsAndDynamicBasesMatchCPython() {
        String code = """
            # C3 MRO Diamond
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
            print("mro:", [cls.__name__ for cls in D.__mro__], D().who())

            # Dynamic 3-arg type() & dynamic __bases__ mutation
            Dyn = type("Dyn", (A,), {"tag": 77, "get_tag": lambda self: self.tag})
            print("dyn:", Dyn().get_tag(), Dyn.__bases__[0].__name__)
            class Base1:
                x = "from-1"
            class Base2:
                x = "from-2"
            class Sub(Base1):
                pass
            inst = Sub()
            print("before_bases:", inst.x)
            Sub.__bases__ = (Base2,)
            print("after_bases:", inst.x)

            # @property (getter/setter/deleter), @staticmethod, @classmethod, decorator order
            class Temp:
                unit = "C"
                def __init__(self, c):
                    self._c = c
                @property
                def c(self):
                    return self._c
                @c.setter
                def c(self, val):
                    self._c = val
                @c.deleter
                def c(self):
                    self._c = 0
                @staticmethod
                def add(x, y):
                    return x + y
                @classmethod
                def make(cls, c):
                    return (cls.__name__, cls.unit, c)

            t = Temp(25)
            t.c = 30
            print("prop:", t.c, Temp.add(2, 3), Temp.make(10))
            del t.c
            print("prop_del:", t.c)

            # __getattr__ delegation
            class ProxyObj:
                def __init__(self):
                    self.real = 123
                def __getattr__(self, name):
                    return "dyn:" + name
            p = ProxyObj()
            print("getattr:", p.real, p.missing)
            """;
        DifferentialHarness.assertMatchesCPython(code);
    }

    @Test
    @DisplayName("Full dunder operator overloading & NotImplemented fallback match CPython")
    void dundersAndNotImplementedMatchCPython() {
        String code = """
            class Vec:
                def __init__(self, x):
                    self.x = x
                def __add__(self, o):
                    if isinstance(o, Vec): return Vec(self.x + o.x)
                    if isinstance(o, int): return Vec(self.x + o)
                    return NotImplemented
                def __radd__(self, o):
                    return self.__add__(o)
                def __sub__(self, o):
                    return Vec(self.x - (o.x if isinstance(o, Vec) else o))
                def __mul__(self, o):
                    return Vec(self.x * (o.x if isinstance(o, Vec) else o))
                def __truediv__(self, o):
                    return self.x / o
                def __floordiv__(self, o):
                    return self.x // o
                def __mod__(self, o):
                    return self.x % o
                def __pow__(self, o):
                    return self.x ** o
                def __neg__(self):
                    return Vec(-self.x)
                def __eq__(self, o):
                    return isinstance(o, Vec) and self.x == o.x
                def __lt__(self, o):
                    return self.x < o.x
                def __hash__(self):
                    return hash(self.x)
                def __bool__(self):
                    return self.x != 0
                def __len__(self):
                    return abs(self.x)
                def __call__(self, mul):
                    return self.x * mul
                def __repr__(self):
                    return f"Vec({self.x})"

            v1 = Vec(5); v2 = Vec(2)
            print(v1 + v2, 10 + v1, v1 - v2, v1 * 3, v1 / 2, v1 // 2, v1 % 3, v2 ** 4, -v1)
            print(v1 == Vec(5), v2 < v1, bool(Vec(0)), len(Vec(-3)), v1(4), {v1: "hit"}[Vec(5)])

            class Bag:
                def __init__(self):
                    self.data = {"k": 10}
                def __getitem__(self, k): return self.data[k]
                def __setitem__(self, k, v): self.data[k] = v
                def __delitem__(self, k): del self.data[k]
                def __contains__(self, k): return k in self.data
                def __iter__(self):
                    self._it = iter(sorted(self.data.items()))
                    return self
                def __next__(self):
                    return next(self._it)

            bg = Bag()
            bg["m"] = 20
            del bg["k"]
            print("m" in bg, bg["m"], list(bg))
            """;
        DifferentialHarness.assertMatchesCPython(code);
    }

    @Test
    @DisplayName("Monomorphic inline attribute caches invalidate cleanly on class and instance updates")
    void inlineAttributeCacheInvalidation() {
        var py = Jython3Interpreter.restricted();
        py.eval("""
            class Item:
                x = 1
                def m(self):
                    return 10
            it = Item()
            def read_x(o):
                return o.x + o.m()
            r1 = read_x(it)
            Item.x = 2
            Item.m = lambda self: 20
            r2 = read_x(it)
            it.x = 100
            r3 = read_x(it)
            """);
        assertEquals("11", py.getGlobal("r1").toString());
        assertEquals("22", py.getGlobal("r2").toString());
        assertEquals("120", py.getGlobal("r3").toString());

        assertEquals("TypeError",
                assertThrows(InterpreterException.class,
                        () -> py.eval("class X: pass\nX.__bases__ = (123,)")).typeName());
    }
}
