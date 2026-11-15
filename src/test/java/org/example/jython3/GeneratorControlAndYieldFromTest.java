package org.example.jython3;

import org.example.jython3.compile.BytecodeValidator;
import org.example.jython3.compile.CompiledCode;
import org.example.jython3.compile.OpCode;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Tests for yield expressions, generator send/throw/close, PEP 342 GeneratorExit,
 * and PEP 380 yield from delegation (including return values, send, throw, close,
 * try/finally cleanup, and recursion limits).
 */
class GeneratorControlAndYieldFromTest {

    @Test
    @DisplayName("Generator send(), yield expression, throw(), and close()")
    void sendThrowAndClose() {
        var interp = Jython3Interpreter.trusted();
        String src = """
            # 1. Accumulator coroutine via send() and parenthesized yield expression
            def accum():
                total = 0
                while True:
                    delta = yield total
                    if delta is None:
                        break
                    total += (yield total + delta) + delta

            # Simple send() coroutine
            def echo_add():
                a = yield "ready"
                b = (yield a * 10) + 1
                yield b

            g = echo_add()
            assert g.send(None) == "ready"
            assert g.send(3) == 30
            assert g.send(5) == 6
            try:
                next(g)
                end_ok = False
            except StopIteration:
                end_ok = True
            assert end_ok

            # 2. throw() caught inside generator and yielding replacement
            def catch_throw():
                log = []
                try:
                    yield 1
                    yield 2
                except ValueError as e:
                    log.append("caught:" + str(e))
                    yield 99
                finally:
                    log.append("fin")
                yield tuple(log)

            gt = catch_throw()
            assert next(gt) == 1
            assert gt.throw(ValueError, "bad") == 99
            assert gt.throw(KeyError("ignored-after")) if False else next(gt) == ("caught:bad", "fin")

            # 3. close() runs finally and with.__exit__ cleanly
            clog = []
            class CM:
                def __enter__(self):
                    clog.append("enter")
                    return self
                def __exit__(self, t, v, tb):
                    clog.append("exit:" + (t.__name__ if t is not None else "None"))
                    return False

            def managed():
                try:
                    with CM():
                        yield "a"
                        yield "b"
                finally:
                    clog.append("finally")

            gm = managed()
            assert next(gm) == "a"
            gm.close()
            assert clog == ["enter", "exit:GeneratorExit", "finally"]
            gm.close()  # idempotent on completed generator
            assert clog == ["enter", "exit:GeneratorExit", "finally"]

            # close() on just-created (CREATED) generator does not run body
            gm2 = managed()
            gm2.close()
            assert clog == ["enter", "exit:GeneratorExit", "finally"]

            ok = True
            """;
        BytecodeValidator.validate(interp.compileSource(src));
        interp.eval(src);
        assertEquals(PyBool.TRUE, interp.getGlobal("ok"));

        // send(non-None) on just-started generator raises TypeError
        var te = assertThrows(InterpreterException.class, () -> interp.eval("""
            def g():
                yield 1
            g().send(42)
            """));
        assertEquals("TypeError", te.typeName());

        // Generator catching GeneratorExit and yielding raises RuntimeError
        var re = assertThrows(InterpreterException.class, () -> interp.eval("""
            def bad_close():
                try:
                    yield 1
                except GeneratorExit:
                    yield 2
            g = bad_close()
            next(g)
            g.close()
            """));
        assertEquals("RuntimeError", re.typeName());
        assertTrue(re.getMessage().contains("GeneratorExit"), re.getMessage());

        // throw() into CREATED or COMPLETED generator raises the thrown exception directly
        var th = assertThrows(InterpreterException.class, () -> interp.eval("""
            def g():
                yield 1
            g().throw(ValueError("not-started"))
            """));
        assertEquals("ValueError", th.typeName());
    }

    @Test
    @DisplayName("PEP 380 yield from delegates values, return value, send, throw, close, and enforces recursion")
    void yieldFromDelegation() {
        var interp = Jython3Interpreter.trusted();
        String src = """
            # 1. yield from non-generator iterables & generator expressions
            def chain_iters():
                yield from [1, 2]
                yield from (x * 10 for x in range(2, 4))
                yield from "ab"
            assert list(chain_iters()) == [1, 2, 20, 30, "a", "b"]

            # 2. yield from subgenerator captures return value + forwards send()
            def sub_coro():
                x = yield "sub-1"
                y = yield x + 1
                return (x, y)

            def outer_coro():
                res = yield from sub_coro()
                yield ("done", res)

            oc = outer_coro()
            assert next(oc) == "sub-1"
            assert oc.send(10) == 11
            assert oc.send(20) == ("done", (10, 20))

            # 3. yield from forwards throw() into subgenerator; uncaught bubbles into outer try/except
            log = []
            def sub_throw():
                try:
                    x = yield "s1"
                    yield "s2:" + str(x)
                except ValueError as e:
                    log.append("sub-caught:" + str(e))
                    yield "from-sub-except"
                finally:
                    log.append("sub-fin")
                return "sub-ret"

            def outer_throw():
                try:
                    r = yield from sub_throw()
                    yield "after:" + r
                except KeyError:
                    log.append("outer-caught-key")
                    yield "outer-recovered"
                finally:
                    log.append("outer-fin")

            ot = outer_throw()
            assert next(ot) == "s1"
            assert ot.throw(ValueError("v1")) == "from-sub-except"
            assert next(ot) == "after:sub-ret"
            assert log == ["sub-caught:v1", "sub-fin"]

            # Uncaught KeyError in sub_throw triggers sub-fin then outer except
            log = []
            ot2 = outer_throw()
            assert next(ot2) == "s1"
            assert ot2.throw(KeyError("k1")) == "outer-recovered"
            assert log == ["sub-fin", "outer-caught-key"]

            # 4. close() forwards into subgenerator's finally before outer's finally
            clog = []
            def sub_close():
                try:
                    yield 1
                finally:
                    clog.append("sub-closed")
            def outer_close():
                try:
                    yield from sub_close()
                finally:
                    clog.append("outer-closed")
            oc2 = outer_close()
            assert next(oc2) == 1
            oc2.close()
            assert clog == ["sub-closed", "outer-closed"]

            ok = True
            """;
        BytecodeValidator.validate(interp.compileSource(src));
        String dis = interp.disassemble("def f():\n    yield from [1, 2]");
        assertTrue(dis.contains("YIELD_FROM"), dis);

        interp.eval(src);
        assertEquals(PyBool.TRUE, interp.getGlobal("ok"));

        // Deep recursive yield from raises RecursionError, never StackOverflowError
        var rec = assertThrows(InterpreterException.class, () -> interp.eval("""
            def rec_yf(n):
                if n <= 0:
                    yield 0
                else:
                    yield from rec_yf(n - 1)
            next(rec_yf(1000))
            """));
        assertEquals("RecursionError", rec.typeName());

        // BytecodeValidator rejects YIELD_FROM outside generator function
        int yfInstr = (OpCode.YIELD_FROM.code() << 16) | 3;
        var bad = new CompiledCode(new int[]{
                OpCode.LOAD_CONST.code() << 16 | 0xFFFF,
                OpCode.LOAD_CONST.code() << 16 | 0xFFFF,
                yfInstr,
                OpCode.HALT.code() << 16
        }, new Object[0], new int[]{1, 1, 1, 1});
        assertThrows(IllegalStateException.class, () -> BytecodeValidator.validate(bad));
    }
}
