package org.example.jython3;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Verifies collections.namedtuple, collections.OrderedDict, functools.wraps,
 * and functools.cmp_to_key using 3-arg type() + @property + dunder __lt__/__eq__.
 */
class CollectionsAndFunctoolsExpansionTest {

    @Test
    @DisplayName("collections.OrderedDict, namedtuple, and functools.cmp_to_key work cleanly")
    void orderedDictNamedTupleAndCmpToKey() {
        try (var py = Jython3Interpreter.trusted()) {
            py.addModulePath(Path.of("modules").toAbsolutePath());
            py.eval("""
                from collections import OrderedDict, namedtuple
                from functools import cmp_to_key, wraps

                Point = namedtuple("Point", "x y")
                p = Point(3, 4)
                assert p.x == 3 and p.y == 4 and len(p) == 2 and list(p) == [3, 4]
                assert p._asdict() == {"x": 3, "y": 4}
                assert p == Point(y=4, x=3)

                od = OrderedDict([("a", 1), ("b", 2), ("c", 3)])
                od.move_to_end("a")
                assert list(od.keys()) == ["b", "c", "a"]
                od.move_to_end("c", last=False)
                assert list(od.keys()) == ["c", "b", "a"]
                assert od.popitem(last=False) == ("c", 3)

                def rev_cmp(a, b):
                    return b - a
                sorted_nums = sorted([1, 4, 2, 3], key=cmp_to_key(rev_cmp))
                assert sorted_nums == [4, 3, 2, 1]

                def orig():
                    return 42
                @wraps(orig)
                def wrap():
                    return orig()
                assert wrap() == 42 and wrap.__wrapped__ is orig
                ok = True
                """);
            assertEquals("True", py.getGlobal("ok").toString());
        }
    }
}
