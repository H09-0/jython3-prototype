package org.example.jython3.field;

import org.example.jython3.Jython3Interpreter;

/** Project 2: OOP, Context Managers, Generators, super(), and nonlocal closures. */
public final class OopScenario implements FieldScenario {

    private static final String CODE = """
        class ResourceLock:
            def __init__(self, name, log):
                self.name = name
                self.log = log
            def __enter__(self):
                self.log.append("enter:" + self.name)
                return self
            def __exit__(self, exc_type, exc_val, exc_tb):
                self.log.append("exit:" + self.name)
                return False

        class Task:
            def __init__(self, name):
                self.name = name
            def run(self):
                return "base:" + self.name

        class ComputeTask(Task):
            def __init__(self, name, factor):
                super().__init__(name)
                self.factor = factor
            def run(self):
                return super().run() + ":x" + str(self.factor)

        def make_pipeline(start):
            state = start
            def step(delta):
                nonlocal state
                state = state + delta
                return state
            return step

        def fibonacci_gen(limit):
            a, b = 0, 1
            for _ in range(limit):
                yield a
                a, b = b, a + b

        events = []
        with ResourceLock("db", events):
            t1 = Task("init")
            t2 = ComputeTask("train", 4)
            results = [t.run() for t in [t1, t2]]

        pipe = make_pipeline(10)
        p_vals = [pipe(5), pipe(3), pipe(-2)]
        fibs = list(fibonacci_gen(12))
        """;

    @Override public String name() { return "Project 2: OOP, Context Managers, Generators, Nonlocal"; }
    @Override public String pythonCode() { return CODE; }

    @Override
    public void verify(Jython3Interpreter interp) {
        FieldScenario.assertTrue("events == ['enter:db', 'exit:db']",
                "['enter:db', 'exit:db']".equals(interp.getGlobal("events").toString()));
        FieldScenario.assertTrue("results == ['base:init', 'base:train:x4']",
                "['base:init', 'base:train:x4']".equals(interp.getGlobal("results").toString()));
        FieldScenario.assertTrue("p_vals == [15, 18, 16]",
                "[15, 18, 16]".equals(interp.getGlobal("p_vals").toString()));
        FieldScenario.assertTrue("fibs == [0, 1, 1, 2, 3, 5, 8, 13, 21, 34, 55, 89]",
                "[0, 1, 1, 2, 3, 5, 8, 13, 21, 34, 55, 89]".equals(interp.getGlobal("fibs").toString()));
    }
}
