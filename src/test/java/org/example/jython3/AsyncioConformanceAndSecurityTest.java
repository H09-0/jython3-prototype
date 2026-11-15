package org.example.jython3;

import org.example.jython3.api.EmbeddedRuntime;
import org.example.jython3.differential.DifferentialHarness;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.security.ExecutionLimits;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.time.Duration;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Conformance & security tests for cooperative asyncio (run, sleep, gather,
 * create_task, wait_for, Task callbacks, Queue, Event, Lock, CancelledError,
 * async with break/continue/return, ExecutionLimits, and task-bomb DoS).
 */
class AsyncioConformanceAndSecurityTest {

    @Test
    @DisplayName("asyncio.run, sleep, gather, create_task, wait_for, Event, Lock, Queue, and callbacks match CPython")
    void asyncioCoreMatchesCPython() {
        String code = """
            import asyncio

            async def worker(name, delay, val):
                res = await asyncio.sleep(delay, result=(name, val * 2))
                return res

            async def main():
                # 1. create_task + gather + sleep(result=...) + done callbacks
                cb_log = []
                t1 = asyncio.create_task(worker("a", 0.02, 5), name="T-a")
                t1.add_done_callback(lambda t: cb_log.append(t.get_name()))
                t2 = asyncio.create_task(worker("b", 0.01, 7), name="T-b")
                res = await asyncio.gather(t1, t2, asyncio.sleep(0, result=("c", 18)))
                print("gather:", res, t1.done(), t1.get_name(), cb_log)

                # 2. gather with return_exceptions=True
                async def fail():
                    await asyncio.sleep(0)
                    raise ValueError("bad")
                mixed = await asyncio.gather(worker("ok", 0, 1), fail(), return_exceptions=True)
                print("mixed:", mixed[0], isinstance(mixed[1], ValueError))

                # 3. wait_for fast finish followed by another sleep (stale timer check)
                fast = await asyncio.wait_for(asyncio.sleep(0.005, result="fast-ok"), timeout=0.08)
                after_fast = await asyncio.sleep(0.02, result="slept-ok")
                print("wait_for_fast:", fast, after_fast)

                # 4. wait_for timeout
                async def slow():
                    await asyncio.sleep(1.0)
                    return "late"
                try:
                    await asyncio.wait_for(slow(), timeout=0.02)
                except TimeoutError:
                    print("wait_for: TimeoutError")

                # 5. task cancellation (uncaught and caught)
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

                async def survivor():
                    try:
                        await asyncio.sleep(1.0)
                    except asyncio.CancelledError:
                        await asyncio.sleep(0.005)
                        return "survived"
                st = asyncio.create_task(survivor())
                await asyncio.sleep(0)
                st.cancel()
                print("survivor:", await st, st.cancelled())

                # 6. asyncio.Event (non-spinning park across sleep)
                ev = asyncio.Event()
                async def setter():
                    await asyncio.sleep(0.02)
                    ev.set()
                async def waiter():
                    await ev.wait()
                    return ev.is_set()
                _, ev_res = await asyncio.gather(setter(), waiter())
                print("event:", ev_res)

                # 7. asyncio.Lock with async with
                lock = asyncio.Lock()
                shared = []
                async def critical(tag):
                    async with lock:
                        shared.append(("enter", tag))
                        await asyncio.sleep(0.005)
                        shared.append(("exit", tag))
                await asyncio.gather(critical(1), critical(2))
                print("lock_clean:", len(shared) == 4 and shared[0][1] == shared[1][1] and shared[2][1] == shared[3][1])

                # 8. asyncio.Queue producer / consumer
                q = asyncio.Queue(maxsize=2)
                async def prod():
                    for i in range(4):
                        await q.put(i)
                async def cons():
                    out = []
                    for _ in range(4):
                        out.append(await q.get())
                        q.task_done()
                    return out
                _, got = await asyncio.gather(prod(), cons())
                await q.join()
                print("queue:", got)

                # 9. current_task + iscoroutine + iscoroutinefunction
                print("introspect:",
                      asyncio.current_task().get_name(),
                      asyncio.iscoroutinefunction(worker),
                      asyncio.iscoroutine(main),
                      isinstance(asyncio.CancelledError(), BaseException))
                return "all-ok"

            c = main()
            print("is_coro_inst:", asyncio.iscoroutine(c))
            print("final:", asyncio.run(c))
            """;
        DifferentialHarness.assertMatchesCPython(code);
    }

    @Test
    @DisplayName("async with runs __aexit__ cleanly on return, break, and continue")
    void asyncWithEarlyExitsMatchCPython() {
        String code = """
            import asyncio

            class LogCtx:
                def __init__(self, tag, log):
                    self.tag = tag
                    self.log = log
                async def __aenter__(self):
                    self.log.append(("enter", self.tag))
                    return self.tag
                async def __aexit__(self, exc_type, exc_val, exc_tb):
                    self.log.append(("exit", self.tag, exc_type))
                    return False

            async def test_exits():
                log = []
                async def do_return():
                    async with LogCtx("r", log):
                        return 99
                r = await do_return()

                for i in range(4):
                    async with LogCtx(("loop", i), log):
                        if i == 1:
                            continue
                        if i == 2:
                            break
                        log.append(("body", i))

                # Ensure exception stack remains intact after early exits
                try:
                    raise KeyError("after")
                except KeyError:
                    log.append("exc-ok")
                return (r, log)

            print(asyncio.run(test_exits()))
            """;
        DifferentialHarness.assertMatchesCPython(code);
    }

    @Test
    @DisplayName("asyncio enforces nested-run prohibition, deadlock detection, ExecutionLimits, and MAX_TASKS bomb protection")
    void asyncioSecurityLimits() {
        var tight = new ExecutionLimits(10_000, Duration.ofMillis(200), 50_000, 1_000, 1_000, 100);
        try (var rt = EmbeddedRuntime.builder().limits(tight).build()) {
            // 1. Nested asyncio.run() raises RuntimeError
            assertEquals("RuntimeError",
                    assertThrows(InterpreterException.class, () -> rt.eval("""
                        import asyncio
                        async def inner(): return 1
                        async def outer(): return asyncio.run(inner())
                        asyncio.run(outer())
                        """)).typeName());

            // 2. Deadlocked event loop (waiting on unset Event with no timers) fails fast with RuntimeError
            assertEquals("RuntimeError",
                    assertThrows(InterpreterException.class, () -> rt.eval("""
                        import asyncio
                        async def stuck():
                            ev = asyncio.Event()
                            await ev.wait()
                        asyncio.run(stuck())
                        """)).typeName());

            // 3. Infinite async loop stopped cleanly by ExecutionLimits
            InterpreterException spinErr = assertThrows(InterpreterException.class, () -> rt.eval("""
                import asyncio
                async def spin():
                    while True:
                        await asyncio.sleep(0)
                asyncio.run(spin())
                """));
            assertTrue("TimeoutError".equals(spinErr.typeName()) || "MemoryError".equals(spinErr.typeName()),
                    "Expected TimeoutError/MemoryError, got: " + spinErr.typeName());

            // 4. Task-bomb (> 4096 tasks) stopped cleanly
            try (var rt2 = EmbeddedRuntime.builder().limits(ExecutionLimits.TRUSTED).build()) {
                InterpreterException bombErr = assertThrows(InterpreterException.class, () -> rt2.eval("""
                    import asyncio
                    async def noop(): return 1
                    async def bomb():
                        for _ in range(5000):
                            asyncio.create_task(noop())
                    asyncio.run(bomb())
                    """));
                assertEquals("RuntimeError", bombErr.typeName());
            }
        }
    }
}
