package org.example.jython3;

import org.example.jython3.differential.DifferentialHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Tests for Track 2C: full dunder operator overloading (__add__/__radd__/__sub__/__mul__,
 * __truediv__/__floordiv__/__mod__/__neg__, __eq__/__lt__/__hash__/__bool__/__len__,
 * __getitem__/__setitem__/__delitem__/__contains__, __iter__/__next__, __call__) and
 * dynamic __bases__ mutation with C3 MRO validation.
 */
class DunderAndDynamicBasesTest {

    @Test
    @DisplayName("Arithmetic, comparison, hash, bool, container, iterator, and __call__ dunders match CPython")
    void dunderOperatorsMatchCPython() {
        String code = """
            class Vec:
                def __init__(self, x, y):
                    self.x = x
                    self.y = y
                def __add__(self, other):
                    if isinstance(other, Vec):
                        return Vec(self.x + other.x, self.y + other.y)
                    return NotImplemented
                def __sub__(self, other):
                    return Vec(self.x - other.x, self.y - other.y)
                def __mul__(self, k):
                    return Vec(self.x * k, self.y * k)
                def __rmul__(self, k):
                    return Vec(self.x * k, self.y * k)
                def __neg__(self):
                    return Vec(-self.x, -self.y)
                def __eq__(self, other):
                    return isinstance(other, Vec) and self.x == other.x and self.y == other.y
                def __lt__(self, other):
                    return (self.x * self.x + self.y * self.y) < (other.x * other.x + other.y * other.y)
                def __hash__(self):
                    return hash((self.x, self.y))
                def __bool__(self):
                    return self.x != 0 or self.y != 0
                def __repr__(self):
                    return f"Vec({self.x}, {self.y})"

            v1 = Vec(1, 2)
            v2 = Vec(3, 4)
            print(v1 + v2, v2 - v1, v1 * 3, 2 * v2, -v1)
            print(v1 == Vec(1, 2), v1 == v2, v1 < v2, bool(Vec(0, 0)), bool(v1))
            print(sorted([v2, v1]))
            d = {v1: "origin-ish"}
            print(d[Vec(1, 2)])

            class Bag:
                def __init__(self):
                    self._d = {}
                def __setitem__(self, k, v):
                    self._d[k] = v
                def __getitem__(self, k):
                    return self._d[k]
                def __delitem__(self, k):
                    del self._d[k]
                def __contains__(self, k):
                    return k in self._d
                def __len__(self):
                    return len(self._d)

            bag = Bag()
            bag["a"] = 10
            bag["b"] = 20
            print(len(bag), "a" in bag, bag["a"])
            del bag["a"]
            print(len(bag), "a" in bag, bool(bag))

            class CounterIter:
                def __init__(self, n):
                    self.i = 0
                    self.n = n
                def __iter__(self):
                    return self
                def __next__(self):
                    if self.i >= self.n:
                        raise StopIteration()
                    v = self.i
                    self.i += 1
                    return v

            print(list(CounterIter(4)), sum(CounterIter(5)))

            class Multiplier:
                def __init__(self, factor):
                    self.factor = factor
                def __call__(self, x, offset=0):
                    return x * self.factor + offset

            m = Multiplier(10)
            print(callable(m), m(3, offset=5))
            """;
        DifferentialHarness.assertMatchesCPython(code);
    }

    @Test
    @DisplayName("Dynamic __bases__ mutation updates C3 MRO and rejects inheritance cycles")
    void dynamicBasesMutationMatchesCPython() {
        String code = """
            class A:
                tag = "A"
            class B:
                tag = "B"
            class C(A):
                pass

            c = C()
            print(c.tag, isinstance(c, B))
            C.__bases__ = (B,)
            print(c.tag, isinstance(c, B), isinstance(c, A))

            try:
                A.__bases__ = (A,)
                print("NO_CYCLE_ERR")
            except TypeError:
                print("CYCLE_REJECTED")
            """;
        DifferentialHarness.assertMatchesCPython(code);
    }
}
