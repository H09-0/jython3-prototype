package org.example.jython3;

import org.example.jython3.compile.BytecodeValidator;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for lazy generator expressions compilation, bytecode validation,
 * scoping, nesting, tuple unpacking, multiple conditions, and lambdas.
 */
class GeneratorExpressionTest {

    @Test
    @DisplayName("Generator expressions compile, validate, and produce expected values")
    void generatorExpressionsCompileAndExecute() {
        var interp = Jython3Interpreter.trusted();
        String src = """
            # 1. basic and filtered genexpr
            g1 = (x * 2 for x in [1, 2, 3])
            assert type(g1).__name__ == "generator"
            assert list(g1) == [2, 4, 6]
            assert list(g1) == []  # exhausted

            # 2. multiple if conditions and tuple unpacking
            g2 = (a + b for a, b in [(1, 10), (2, 20), (3, 30)] if a > 1 if b < 30)
            assert list(g2) == [22]

            # 3. nested for clauses inside genexpr
            g3 = ((x, y) for x in [1, 2] for y in [10, 20] if x != 2 or y != 10)
            assert list(g3) == [(1, 10), (1, 20), (2, 20)]

            # 4. nested genexprs & call-site bare genexpr
            assert sum(x * 2 for x in range(5) if x % 2 == 1) == 8
            assert list((x + 1 for x in (y * 10 for y in [1, 2, 3]))) == [11, 21, 31]

            # 5. closures and lambdas inside genexpr, and loop var does not leak
            x = "outer"
            def make_gen(mult):
                offset = 5
                return (v * mult + offset for v in [1, 2, 3])
            assert list(make_gen(10)) == [15, 25, 35]

            # verify loop var in genexpr did not overwrite outer x
            res_leak_check = list((x * 2 for x in [1, 2]))
            assert res_leak_check == [2, 4] and x == "outer"

            # 6. lambdas inside genexpr
            fns = list((lambda a, k=i: a + k for i in range(3)))
            assert [f(10) for f in fns] == [10, 11, 12]

            # 7. genexpr inside list/dict/set and for and try/finally
            c_list = [(v for v in [1, 2])]
            assert list(c_list[0]) == [1, 2]
            assert set((v % 2 for v in [1, 2, 3])) == {0, 1}

            for_seen = []
            for v in (i * 3 for i in [1, 2, 3]):
                for_seen.append(v)
            assert for_seen == [3, 6, 9]

            fin = []
            try:
                assert sum(i for i in [1, 2, 3]) == 6
            finally:
                fin.append("done")
            assert fin == ["done"]

            ok = True
            """;
        BytecodeValidator.validate(interp.compileSource(src));
        String dis = interp.disassemble("g = (x * 2 for x in [1, 2])");
        assertTrue(dis.contains("[generator]"), dis);
        assertTrue(dis.contains("YIELD_VALUE"), dis);

        interp.eval(src);
        assertEquals(PyBool.TRUE, interp.getGlobal("ok"));
    }

    @Test
    @DisplayName("Outer iterable is evaluated once at creation; non-iterable raises TypeError immediately; yield outside fn rejected")
    void outerIterableAndYieldOutsideFnChecks() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            calls = []
            def src():
                calls.append("src")
                return [1, 2, 3]
            g = (x * 10 for x in src())
            assert calls == ["src"]
            assert list(g) == [10, 20, 30]
            assert calls == ["src"]
            """);

        var te = assertThrows(InterpreterException.class, () -> interp.eval("g = (x for x in 123)"));
        assertEquals("TypeError", te.typeName());

        var se = assertThrows(InterpreterException.class, () -> interp.eval("yield 1"));
        assertEquals("SyntaxError", se.typeName());

        var seCls = assertThrows(InterpreterException.class, () -> interp.eval("class C:\n    yield 1"));
        assertEquals("SyntaxError", seCls.typeName());
    }
}
