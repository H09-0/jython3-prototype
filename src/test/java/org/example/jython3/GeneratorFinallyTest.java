package org.example.jython3;

import org.example.jython3.runtime.PyBool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * Tests try/finally, with, exception metadata (__traceback__, __context__, __cause__),
 * closures, global/nonlocal, and cleanup across lazy generator suspension.
 */
class GeneratorFinallyTest {

    @Test
    @DisplayName("try/finally inside lazy generators executes once at completion or exception, never at yield")
    void tryFinallyAcrossSuspension() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            # 1. Cleanup runs only when generator completes, not during suspension, and never twice
            log1 = []
            def gen1(log):
                try:
                    yield 1
                    yield 2
                finally:
                    log.append("cleanup")
            g1 = gen1(log1)
            assert next(g1) == 1 and log1 == []
            assert next(g1) == 2 and log1 == []
            try:
                next(g1)
            except StopIteration:
                pass
            assert log1 == ["cleanup"]
            try:
                next(g1)
            except StopIteration:
                pass
            assert log1 == ["cleanup"]

            # 2. Exception inside generator triggers finally once and propagates
            log2 = []
            def gen2(log):
                try:
                    yield 10
                    raise ValueError("boom")
                finally:
                    log.append("cleanup2")
            g2 = gen2(log2)
            assert next(g2) == 10 and log2 == []
            caught = False
            try:
                next(g2)
            except ValueError:
                caught = True
            assert caught and log2 == ["cleanup2"]
            # Repeating next() after failure yields StopIteration without re-running finally
            stopped = False
            try:
                next(g2)
            except StopIteration:
                stopped = True
            assert stopped and log2 == ["cleanup2"]

            # 3. Nested try/finally runs inner then outer
            log3 = []
            def gen3(log):
                try:
                    try:
                        yield 1
                    finally:
                        log.append("inner")
                finally:
                    log.append("outer")
            assert list(gen3(log3)) == [1]
            assert log3 == ["inner", "outer"]

            # 4. yield inside try, except, else, finally, with, loop, and nonlocal closure
            log4 = []
            class CM:
                def __init__(self, tag):
                    self.tag = tag
                def __enter__(self):
                    log4.append("enter-" + self.tag)
                    return self
                def __exit__(self, t, v, tb):
                    log4.append("exit-" + self.tag + ":" + ("exc" if t is not None else "ok"))
                    return False

            def gen_all_blocks():
                count = 0
                def inc():
                    nonlocal count
                    count += 1
                    return count
                try:
                    with CM("w"):
                        for i in [1, 2]:
                            yield ("loop", i, inc())
                except KeyError:
                    yield "except"
                else:
                    yield "else"
                finally:
                    yield "in-finally"
                    log4.append("fin-done")

            out4 = list(gen_all_blocks())
            assert out4 == [("loop", 1, 1), ("loop", 2, 2), "else", "in-finally"]
            assert log4 == ["enter-w", "exit-w:ok", "fin-done"]

            # 5. yield inside except branch
            def gen_exc_yield():
                try:
                    raise KeyError("k")
                except KeyError:
                    yield "from-except"
            assert list(gen_exc_yield()) == ["from-except"]

            # 6. with __exit__ sees live traceback when generator raises
            tb_info = []
            class WatchCM:
                def __enter__(self):
                    return self
                def __exit__(self, t, v, tb):
                    tb_info.append((t.__name__, tb is not None, tb.tb_frame.f_code.co_name))
                    return True
            def gen_with_raise():
                with WatchCM():
                    yield 1
                    raise RuntimeError("inside-with")
            assert list(gen_with_raise()) == [1]
            assert tb_info == [("RuntimeError", True, "gen_with_raise")]

            ok = True
            """);
        assertEquals(PyBool.TRUE, interp.getGlobal("ok"));
    }

    @Test
    @DisplayName("Exceptions in suspended generators preserve __traceback__, __context__, and __cause__")
    void exceptionMetadataAndTracebackAcrossSuspension() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            def g():
                yield 1
                raise ValueError("boom")

            def caller():
                it = g()
                assert next(it) == 1
                return next(it)

            try:
                caller()
            except ValueError as e:
                assert e.__traceback__ is not None
                frames = []
                cur = e.__traceback__
                while cur is not None:
                    frames.append((cur.tb_frame.f_code.co_name, cur.tb_lineno, cur.tb_frame.f_code.co_filename))
                    cur = cur.tb_next

            # __context__ and __cause__ preserved across yield inside except
            def chained_gen():
                try:
                    raise KeyError("root")
                except KeyError as k:
                    yield "mid"
                    raise ValueError("wrapped") from k

            cg = chained_gen()
            assert next(cg) == "mid"
            try:
                next(cg)
            except ValueError as ve:
                chain_ok = (
                    ve.__cause__.__class__.__name__ == "KeyError"
                    and ve.__context__.__class__.__name__ == "KeyError"
                    and ve.__suppress_context__ == True
                    and ve.__traceback__ is not None
                )
            assert chain_ok

            # Implicit __context__ for runtime error after yield inside except
            def rt_ctx_gen():
                try:
                    raise KeyError("orig")
                except KeyError:
                    yield 1
                    x = 1 / 0
            rg = rt_ctx_gen()
            assert next(rg) == 1
            try:
                next(rg)
            except ZeroDivisionError as ze:
                rt_ok = ze.__context__.__class__.__name__ == "KeyError" and ze.__traceback__ is not None
            assert rt_ok

            ok = (
                len(frames) >= 3
                and frames[0][0] == "<module>"
                and frames[1][0] == "caller"
                and frames[2][0] == "g"
                and frames[2][1] == 3
            )
            """);
        assertEquals(PyBool.TRUE, interp.getGlobal("ok"), String.valueOf(interp.getGlobal("frames")));
    }
}
