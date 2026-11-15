package org.example.jython3;

import org.example.jython3.differential.DifferentialHarness;
import org.example.jython3.runtime.InterpreterException;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Conformance tests grounded in PEP 492 (peps.python.org/pep-0492/):
 * native async def coroutines, await, custom __await__, async for (StopAsyncIteration),
 * async with (__aenter__/__aexit__), operator precedence, and compile/runtime errors.
 */
class AsyncAwaitCoroutinesTest {

    @Test
    @DisplayName("PEP 492: async def, await, __await__, async for, and async with match CPython")
    void pep492CoroutinesAndAsyncProtocolsMatchCPython() {
        String code = """
            class YieldStep:
                def __init__(self, val):
                    self.val = val
                def __await__(self):
                    res = yield ("step", self.val)
                    return res + self.val

            async def inner(x):
                a = await YieldStep(x)
                return a * 2

            async def outer(x):
                v = await inner(x)
                return v + 1

            def drive(coro):
                steps = []
                send_val = None
                while True:
                    try:
                        y = coro.send(send_val)
                        steps.append(y)
                        send_val = 10
                    except StopIteration as stop:
                        return (steps, stop.value)

            print(drive(outer(5)))

            class AsyncRange:
                def __init__(self, n):
                    self.i = 0
                    self.n = n
                def __aiter__(self):
                    return self
                async def __anext__(self):
                    if self.i >= self.n:
                        raise StopAsyncIteration()
                    v = self.i
                    self.i += 1
                    await YieldStep(v)
                    return v

            class AsyncCtx:
                def __init__(self, log, suppress=False):
                    self.log = log
                    self.suppress = suppress
                async def __aenter__(self):
                    await YieldStep(1)
                    self.log.append("enter")
                    return "ctx-val"
                async def __aexit__(self, exc_type, exc_val, exc_tb):
                    await YieldStep(2)
                    self.log.append(("exit", exc_type.__name__ if exc_type else None))
                    return self.suppress

            async def test_protocols():
                log = []
                collected = []
                async with AsyncCtx(log) as v:
                    log.append(v)
                    async for item in AsyncRange(3):
                        collected.append(item)
                try:
                    async with AsyncCtx(log, suppress=False):
                        raise ValueError("boom")
                except ValueError:
                    log.append("caught")
                async with AsyncCtx(log, suppress=True):
                    raise KeyError("suppressed")
                return (collected, log)

            print(drive(test_protocols()))

            # Precedence: await binds tighter than + and **, looser than attribute/call
            async def prec():
                return await YieldStep(2) + await YieldStep(3)
            print(drive(prec()))
            """;
        DifferentialHarness.assertMatchesCPython(code);
    }

    @Test
    @DisplayName("PEP 492 static and runtime errors: SyntaxError, TypeError, and PEP 479 inside async def")
    void pep492ErrorPaths() {
        try (var py = new Jython3Interpreter()) {
            // await outside async def -> SyntaxError
            assertEquals("SyntaxError",
                    assertThrows(InterpreterException.class, () -> py.eval("def f():\n    await 1\n")).typeName());
            // async for / async with outside async def -> SyntaxError
            assertEquals("SyntaxError",
                    assertThrows(InterpreterException.class, () -> py.eval("def f():\n    async with x:\n        pass\n")).typeName());
            // yield inside async def -> SyntaxError
            assertEquals("SyntaxError",
                    assertThrows(InterpreterException.class, () -> py.eval("async def f():\n    yield 1\n")).typeName());

            // coroutine is NOT iterable & cannot be used in yield from
            py.eval("""
                async def c():
                    return 42
                co = c()
                """);
            assertEquals("TypeError",
                    assertThrows(InterpreterException.class, () -> py.eval("iter(co)")).typeName());
            assertEquals("TypeError",
                    assertThrows(InterpreterException.class, () -> py.eval("""
                        def g():
                            yield from c()
                        next(g())
                        """)).typeName());
            assertEquals("TypeError",
                    assertThrows(InterpreterException.class, () -> py.eval("""
                        async def bad_await():
                            await 123
                        bad_await().send(None)
                        """)).typeName());
            // PEP 479: StopIteration inside async def -> RuntimeError
            assertEquals("RuntimeError",
                    assertThrows(InterpreterException.class, () -> py.eval("""
                        async def bad_stop():
                            raise StopIteration()
                        bad_stop().send(None)
                        """)).typeName());
        }
    }
}
