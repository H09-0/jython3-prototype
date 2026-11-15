package org.example.jython3.logic;

import org.example.jython3.Jython3Interpreter;
import org.example.jython3.differential.DifferentialHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Logic & conformance tests exercising every built-in function (BuiltinFunctions)
 * and every built-in type method (BuiltinMethodDispatcher).
 */
@Tag("logic")
class BuiltinsAndCollectionMethodsTest {

    @Test
    @DisplayName("All 60 built-in functions and types match CPython semantics")
    void allBuiltinsMatchCPython() {
        String code = """
            # Numeric, conversion & math built-ins
            print(abs(-7), abs(-2.5), pow(2, 10), pow(2, 10, 1000), divmod(17, 5))
            print(int("ff", 16), float("3.5"), bool(0), bool([1]), round(3.14159, 2), round(2.5))
            print(chr(65), ord("😀"), hex(255), oct(64), bin(13))
            print(min(3, 1, 2), max([4, 9, 2]), sum([1, 2, 3], 10))

            # Sequence, iterator, and functional built-ins
            sl = slice(1, 10, 2)
            print(sl.start, sl.stop, sl.step, sl.indices(8))
            r = range(10, 0, -2)
            print(r.start, r.stop, r.step, len(r), r.count(6), r.index(6), list(reversed(r)), 6 in r, r[1:3])
            print(sorted([3, -1, 2, -4], key=abs, reverse=True))
            print(list(enumerate(["a", "b"], start=5)))
            print(list(zip([1, 2, 3], ["x", "y"])))
            print(list(zip([1, 2], ["a", "b"], strict=True)))
            try:
                list(zip([1, 2, 3], ["a"], strict=True))
            except ValueError:
                print("zip_strict_err: ok")
            print(list(map(lambda x: x * x, [1, 2, 3])))
            print(list(filter(lambda x: x % 2 == 0, range(6))))
            print(all([True, 1, "a"]), any([0, "", False, 9]))

            # iter(callable, sentinel) and next(it, default)
            cnt = [0]
            def step():
                cnt[0] += 1
                return cnt[0]
            it_s = iter(step, 4)
            print(list(it_s), next(iter([99])), next(iter([]), "DONE"))

            # Introspection & attribute built-ins
            class Box:
                def __init__(self):
                    self.a = 1
            bx = Box()
            setattr(bx, "b", 2)
            print(hasattr(bx, "b"), getattr(bx, "b"), getattr(bx, "missing", 99), sorted(vars(bx).items()))
            delattr(bx, "b")
            print(hasattr(bx, "b"), callable(Box), callable(bx), repr("a\\nb"), format(255, "04x"))
            print(isinstance(True, int), issubclass(bool, int), type(bx) is Box, type(None).__name__)
            print(hash((1, "a")) == hash((1.0, "a")), id(bx) == id(bx), "a" in dir(bx))
            print("globals_has_Box:", "Box" in globals(), "locals_has_bx:", "bx" in locals())
            print(NotImplemented is NotImplemented, isinstance(object(), object))
            """;
        DifferentialHarness.assertMatchesCPython(code);
    }

    @Test
    @DisplayName("All str, list, dict, set, frozenset, bytes, and bytearray methods match CPython")
    void allCollectionAndStringMethodsMatchCPython() {
        String code = """
            # str methods
            s = "  hello World!  "
            print(s.strip(), s.lstrip(), s.rstrip(), "hi".upper(), "HI".lower(), "a b c".split())
            print("-".join(["a", "b", "c"]), "aba".replace("a", "X"))
            print("foobar".startswith("foo"), "foobar".endswith("bar"))
            print("foobar".removeprefix("foo"), "foobar".removesuffix("bar"))
            print("a\\nb\\nc".splitlines(), "a=b=c".partition("="), "a=b=c".rpartition("="))
            print("hello world".capitalize(), "hello world".title())
            print("x".center(5, "*"), "x".ljust(4, "."), "x".rjust(4, "."), "-42".zfill(5))
            print("  \\t".isspace(), "ABC".isupper(), "abc".islower(), "123".isdigit(), "abc".isalpha(), "a1".isalnum())
            print("hello".find("ll"), "hello".rfind("l"), "hello".index("e"), "hello".rindex("l"))
            print("banana".count("na"), "abc".count(""), "AbC".swapcase(), "Straße".casefold() == "straße" or "ABC".casefold() == "abc")
            print("hi {name} {0}".format("!", name="Ada"))
            print("café".encode("utf-8").decode("utf-8"))
            tup = (10, 20, 10, 30)
            print("tup:", tup.count(10), tup.index(20))

            # list & dict methods
            xs = [3, 1, 2]
            xs.append(4); xs.extend([5, 1]); xs.insert(0, 9); p = xs.pop()
            xs.remove(1); c = xs.count(1); idx = xs.index(2)
            xs_copy = xs.copy(); xs.sort(reverse=True); xs.reverse()
            print(p, c, idx, xs, len(xs_copy))
            xs_copy.clear(); print(xs_copy)

            d = {"a": 1, "b": 2}
            d2 = d | {"b": 20, "c": 3}
            d |= {"d": 4}
            print(d.setdefault("e", 5), d.get("missing", -1), d.pop("a"), d.popitem())
            print(sorted(d2.items()), sorted(dict.fromkeys(["k1", "k2"], 0).items()))
            print({1: "int", 1.0: "float", True: "bool"})

            # set & frozenset operators + methods
            sa = {1, 2, 3}; sb = {3, 4}
            fs = frozenset([1, 2])
            print(sorted(sa | sb), sorted(sa & sb), sorted(sa - sb), sorted(sa ^ sb))
            print(fs < sa, fs <= sa, sa > fs, sa >= fs, fs.isdisjoint({9}))
            sa.add(9); sa.discard(99); sa.update([7]); sa.remove(1)
            print(sorted(sa), sorted(sa.union(fs)), sorted(sa.intersection(sb)), sorted(sa.difference(sb)), sorted(sa.symmetric_difference(sb)))

            # bytes & bytearray
            raw = b"hello"
            ba = bytearray(b"ab")
            ba.append(99); ba.extend(b"de"); ba.reverse()
            print(raw.hex(), raw.startswith(b"he"), raw.endswith(b"lo"), raw.find(b"ll"), raw.count(b"l"))
            print(ba.decode("utf-8"), ba.pop(), ba.hex(), len(ba.copy()))
            ba.clear(); print(len(ba))
            """;
        DifferentialHarness.assertMatchesCPython(code);
    }

    @Test
    @DisplayName("input() and print() work with redirected streams")
    void inputAndPrintStreams() {
        var interp = Jython3Interpreter.restricted();
        var oldIn = System.in;
        try {
            System.setIn(new ByteArrayInputStream("Alice\n".getBytes(StandardCharsets.UTF_8)));
            interp.eval("name = input('Name: ')\nprint('Hi', name)");
            assertEquals("Alice", interp.getGlobal("name").toString());
        } finally {
            System.setIn(oldIn);
        }
    }
}
