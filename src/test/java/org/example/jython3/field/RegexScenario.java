package org.example.jython3.field;

import org.example.jython3.Jython3Interpreter;
import org.example.jython3.runtime.PyList;

/** Project 3: Regex, Raw Strings, Unicode/Emoji Slicing, and Monte Carlo Simulation. */
public final class RegexScenario implements FieldScenario {

    private static final String CODE = """
        import re
        import random
        import math

        log_lines = [
            "[2026-11-15] ERROR user=42 latency=120ms msg=timeout 🚀",
            "[2026-11-15] INFO  user=17 latency=15ms  msg=ok ✅",
            "[2026-11-15] WARN  user=99 latency=450ms msg=slow ⚠️",
            "[2026-11-15] ERROR user=88 latency=999ms msg=crash 💥"
        ]

        errors = []
        latencies = []
        for line in log_lines:
            nums = re.findall(r"\\d+", line)
            latencies.append(int(nums[-1]))
            if "ERROR" in line:
                errors.append(line)

        emoji_str = "🔥🚀✨Jython3🎯"
        emoji_len = len(emoji_str)
        emoji_rev = emoji_str[::-1]
        emoji_mid = emoji_str[3:10]

        random.seed(42)
        inside = 0
        samples = 5000
        for _ in range(samples):
            x = random.random()
            y = random.random()
            if x * x + y * y <= 1.0:
                inside += 1
        pi_est = 4.0 * inside / samples
        """;

    @Override public String name() { return "Project 3: Regex, Unicode/Emoji, Monte Carlo"; }
    @Override public String pythonCode() { return CODE; }

    @Override
    public void verify(Jython3Interpreter interp) {
        FieldScenario.assertTrue("len(errors) == 2",
                ((PyList) interp.getGlobal("errors")).items().size() == 2);
        FieldScenario.assertTrue("emoji_len == 11",
                "11".equals(interp.getGlobal("emoji_len").toString()));
        FieldScenario.assertTrue("emoji_mid == 'Jython3'",
                "Jython3".equals(interp.getGlobal("emoji_mid").toString()));
        double pi = Double.parseDouble(interp.getGlobal("pi_est").toString());
        FieldScenario.assertTrue("pi_est ~ 3.14 (got " + pi + ")", pi > 3.0 && pi < 3.3);
    }
}
