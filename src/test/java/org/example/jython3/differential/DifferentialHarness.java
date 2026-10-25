package org.example.jython3.differential;

import org.example.jython3.Jython3Interpreter;
import org.example.jython3.runtime.InterpreterException;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Reusable differential test harness executing identical Python code
 * against host CPython and Jython 3, capturing outputs, errors, exit codes,
 * and comparing semantics.
 */
public final class DifferentialHarness {

    public record ExecutionResult(
            boolean success,
            String stdout,
            String stderr,
            String exceptionType,
            int exitCode
    ) {}

    private static String cpythonPath = null;
    private static boolean cpythonChecked = false;

    public static synchronized String findCPython() {
        if (!cpythonChecked) {
            cpythonChecked = true;
            // Check for python3.14 or python3
            for (String candidate : new String[]{"python3.14", "python3"}) {
                try {
                    Process p = new ProcessBuilder(candidate, "--version").start();
                    if (p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0) {
                        cpythonPath = candidate;
                        break;
                    }
                } catch (Exception ignored) {}
            }
        }
        return cpythonPath;
    }

    public static ExecutionResult runCPython(String script) {
        String pyBin = findCPython();
        if (pyBin == null) {
            return new ExecutionResult(false, "", "CPython not available in environment", "EnvironmentError", -1);
        }
        try {
            Process process = new ProcessBuilder(pyBin, "-c", script)
                    .redirectErrorStream(false)
                    .start();

            var stdoutReader = new BufferedReader(new InputStreamReader(process.getInputStream(), StandardCharsets.UTF_8));
            var stderrReader = new BufferedReader(new InputStreamReader(process.getErrorStream(), StandardCharsets.UTF_8));

            var stdout = new StringBuilder();
            String line;
            while ((line = stdoutReader.readLine()) != null) stdout.append(line).append('\n');

            var stderr = new StringBuilder();
            while ((line = stderrReader.readLine()) != null) stderr.append(line).append('\n');

            boolean finished = process.waitFor(5, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                return new ExecutionResult(false, stdout.toString().trim(), "Timed out", "TimeoutError", -1);
            }

            int exitCode = process.exitValue();
            String errStr = stderr.toString().trim();
            String excType = extractExceptionType(errStr);
            return new ExecutionResult(exitCode == 0, stdout.toString().trim(), errStr, excType, exitCode);
        } catch (Exception ex) {
            return new ExecutionResult(false, "", ex.getMessage(), ex.getClass().getSimpleName(), -1);
        }
    }

    public static ExecutionResult runJython3(String script) {
        var interp = Jython3Interpreter.trusted();
        var outBuf = new ByteArrayOutputStream();
        var errBuf = new ByteArrayOutputStream();
        var originalOut = System.out;
        var originalErr = System.err;

        System.setOut(new PrintStream(outBuf, true, StandardCharsets.UTF_8));
        System.setErr(new PrintStream(errBuf, true, StandardCharsets.UTF_8));

        try {
            interp.eval(script);
            String outStr = outBuf.toString(StandardCharsets.UTF_8).trim();
            return new ExecutionResult(true, outStr, "", null, 0);
        } catch (InterpreterException ie) {
            String errStr = interp.formatTraceback(ie, "<stdin>", script);
            return new ExecutionResult(false, outBuf.toString(StandardCharsets.UTF_8).trim(), errStr, ie.typeName(), 1);
        } catch (Exception ex) {
            return new ExecutionResult(false, outBuf.toString(StandardCharsets.UTF_8).trim(), ex.getMessage(), ex.getClass().getSimpleName(), 1);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    public static void assertMatchesCPython(String script) {
        ExecutionResult cpy = runCPython(script);
        if (!cpy.success() && "EnvironmentError".equals(cpy.exceptionType())) {
            // CPython not installed in this environment
            return;
        }
        ExecutionResult jy3 = runJython3(script);

        assertEquals(cpy.success(), jy3.success(),
                () -> "Success status mismatch for script: " + script + "\nCPython stdout: " + cpy.stdout() + "\nCPython stderr: " + cpy.stderr() + "\nJython stdout: " + jy3.stdout() + "\nJython stderr: " + jy3.stderr());

        if (cpy.success()) {
            assertEquals(cpy.stdout(), jy3.stdout(), "stdout mismatch for script:\n" + script);
        } else {
            assertEquals(cpy.exceptionType(), jy3.exceptionType(), "Exception type mismatch for script:\n" + script);
        }
    }

    private static String extractExceptionType(String stderr) {
        if (stderr == null || stderr.isEmpty()) return null;
        String[] lines = stderr.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            int colon = line.indexOf(':');
            if (colon > 0) {
                String candidate = line.substring(0, colon).trim();
                if (candidate.matches("[A-Za-z_][A-Za-z0-9_]*Error|[A-Za-z_][A-Za-z0-9_]*Exception")) {
                    return candidate;
                }
            }
        }
        return "UnknownError";
    }
}
