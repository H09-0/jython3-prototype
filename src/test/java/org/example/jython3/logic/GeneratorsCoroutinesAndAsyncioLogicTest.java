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
 * Logic & conformance tests for Generators, PEP 380 yield from, PEP 479,
 * PEP 492 native coroutines (async def, await, async for, async with),
 * and the cooperative asyncio module.
 */
@Tag("logic")
class GeneratorsCoroutinesAndAsyncioLogicTest {

    @Test
    @DisplayName("Generators, generator expressions, PEP 380 yield from, .send/.throw/.close, and PEP 479 match CPython")
    void generatorsAndPep479MatchCPython() {
        String code = """
            # Lazy generator expression
            ge = (x * x for x in range(5) if x % 2 == 1)
            print(iter(ge) is ge, list(ge))

            # Generator send / throw / close / try-finally
            events = []
            def gen():
                try:
                    x = yield "first"
                    events.append(("got", x))
                    try:
                        yield "second"
                    except ValueError as e:
                        events.append(("caught", str(e)))
                        yield "recovered"
                finally:
                    events.append("closed")

            g = gen()
            print(next(g), g.send(42), g.throw(ValueError, "err"))
            g.close()
            print(events)

            # PEP 380 yield from with return value
            def sub():
                v = yield 10
                return v + 5
            def delegator():
                res = yield from sub()
                yield ("done", res)
            dg = delegator()
            print(next(dg), dg.send(20), list(dg))

            # PEP 479: StopIteration inside generator -> RuntimeError with __cause__
            def bad_gen():
                yield 1
                raise StopIteration("stop")
            bg = bad_gen()
            next(bg)
            try:
                next(bg)
            except RuntimeError as re_err:
                print("pep479:", type(re_err.__cause__).__name__)
            """;
        DifferentialHarness.assertMatchesCPython(code);
    }

    @Test
    @DisplayName("PEP 492 coroutines (async def, await, async for, async with) and static SyntaxError rules match CPython")
    void pep492CoroutinesMatchCPython() {
        String code = """
            class YieldStep:
                def __init__(self, val):
                    self.val = val
                def __await__(self):
                    res = yield ("step", self.val)
                    return res + self.val

            class AsyncRange:
                def __init__(self, n):
                    self.i = 0; self.n = n
                def __aiter__(self): return self
                async def __anext__(self):
                    if self.i >= self.n: raise StopAsyncIteration()
                    v = self.i; self.i += 1
                    await YieldStep(v)
                    return v

            class AsyncCtx:
                def __init__(self, log, suppress=False):
                    self.log = log; self.suppress = suppress
                async def __aenter__(self):
                    await YieldStep(1)
                    self.log.append("enter")
                    return "ctx"
                async def __aexit__(self, et, ev, tb):
                    await YieldStep(2)
                    self.log.append(("exit", et.__name__ if et else None))
                    return self.suppress

            async def run_all():
                log = []
                items = []
                async with AsyncCtx(log) as v:
                    log.append(v)
                    async for it in AsyncRange(3):
                        items.append(it)
                async with AsyncCtx(log, suppress=True):
                    raise KeyError("suppressed")
                return (items, log)

            def drive(coro):
                steps = []
                sv = None
                while True:
                    try:
                        steps.append(coro.send(sv))
                        sv = 10
                    except StopIteration as stop:
                        return (steps, stop.value)

            print(drive(run_all()))
            """;
        DifferentialHarness.assertMatchesCPython(code);

        try (var py = Jython3Interpreter.restricted()) {
            assertEquals("SyntaxError",
                    assertThrows(InterpreterException.class, () -> py.eval("def f():\n    await 1\n")).typeName());
            assertEquals("SyntaxError",
                    assertThrows(InterpreterException.class, () -> py.eval("async def f():\n    yield 1\n")).typeName());
            assertEquals("TypeError",
                    assertThrows(InterpreterException.class, () -> py.eval("async def c(): return 1\niter(c())")).typeName());
        }
    }

    @Test
    @DisplayName("asyncio full API (run, sleep, create_task, gather, wait_for, Task, Queue, Event, Lock) matches CPython")
    void asyncioFullApiMatchesCPython() {
        String code = """
            import asyncio

            async def worker(tag, delay, val):
                return await asyncio.sleep(delay, result=(tag, val * 2))

            class LogCtx:
                def __init__(self, tag, log):
                    self.tag = tag; self.log = log
                async def __aenter__(self):
                    self.log.append(("enter", self.tag))
                    return self.tag
                async def __aexit__(self, et, ev, tb):
                    self.log.append(("exit", self.tag, et))
                    return False

            async def main():
                cb = []
                t1 = asyncio.create_task(worker("a", 0.02, 5), name="T-a")
                t1.add_done_callback(lambda t: cb.append(t.get_name()))
                t2 = asyncio.create_task(worker("b", 0.01, 7), name="T-b")
                g = await asyncio.gather(t1, t2, asyncio.sleep(0, result=("c", 18)))
                print("gather:", g, t1.done(), t1.result(), t1.exception(), cb)

                async def fail():
                    await asyncio.sleep(0)
                    raise ValueError("bad")
                mixed = await asyncio.gather(worker("ok", 0, 1), fail(), return_exceptions=True)
                print("mixed:", mixed[0], isinstance(mixed[1], ValueError))

                fast = await asyncio.wait_for(asyncio.sleep(0.005, result="fast"), timeout=0.08)
                after = await asyncio.sleep(0.02, result="after")
                print("fast:", fast, after)

                async def slow():
                    await asyncio.sleep(1.0)
                try:
                    await asyncio.wait_for(slow(), timeout=0.02)
                except TimeoutError:
                    print("timeout: ok")

                # CancelledError caught vs uncaught
                cancelled_seen = False
                async def cancellable():
                    nonlocal cancelled_seen
                    try:
                        await asyncio.sleep(1.0)
                    except asyncio.CancelledError:
                        cancelled_seen = True
                        raise
                ct = asyncio.create_task(cancellable())
                await asyncio.sleep(0)
                ct.cancel()
                try:
                    await ct
                except asyncio.CancelledError:
                    print("cancelled:", ct.cancelled(), cancelled_seen)

                # Event, Lock, Queue
                ev = asyncio.Event()
                async def setter():
                    await asyncio.sleep(0.01)
                    ev.set()
                await asyncio.gather(setter(), ev.wait())

                lock = asyncio.Lock()
                shared = []
                async def crit(k):
                    async with lock:
                        shared.append(("in", k))
                        await asyncio.sleep(0.005)
                        shared.append(("out", k))
                await asyncio.gather(crit(1), crit(2))

                q = asyncio.Queue(maxsize=2)
                async def prod():
                    for i in range(4): await q.put(i)
                async def cons():
                    out = []
                    for _ in range(4):
                        out.append(await q.get())
                        q.task_done()
                    return out
                _, qres = await asyncio.gather(prod(), cons())
                await q.join()

                # async with early return/break/continue
                alog = []
                async def aw_ret():
                    async with LogCtx("r", alog):
                        return 77
                rval = await aw_ret()
                for i in range(3):
                    async with LogCtx(i, alog):
                        if i == 1: continue
                        if i == 2: break
                        alog.append(("body", i))

                print("sync:", ev.is_set(), len(shared) == 4, qres, rval, alog)
                print("meta:", asyncio.current_task().get_name(), asyncio.iscoroutinefunction(worker),
                      len(asyncio.all_tasks()) >= 1)
                return "done"

            coro = main()
            print("iscoro:", asyncio.iscoroutine(coro), asyncio.run(coro))
            """;
        DifferentialHarness.assertMatchesCPython(code);
    }
}
