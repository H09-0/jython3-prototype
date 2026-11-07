package org.example.jython3.differential;

import org.example.jython3.Jython3Interpreter;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyException;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Reusable differential test harness executing identical Python code
 * against host CPython and Jython 3, capturing outputs, errors, exit codes,
 * and comparing semantics.
 * <p>
 * Key capabilities:
 * <ul>
 *   <li>Asynchronous parallel stdout and stderr draining to prevent pipe buffer deadlocks.</li>
 *   <li>1MB output buffer caps to prevent OOM on runaway processes.</li>
 *   <li>Hard process termination with wait to prevent orphan processes.</li>
 *   <li>Machine-readable JSON Lines reporting at {@code build/reports/differential-results.jsonl}.</li>
 * </ul>
 */
public final class DifferentialHarness {

    public static final String JYTHON_VERSION = "v0.10.3-alpha";
    public static final int MAX_OUTPUT_CHARS = 1_000_000;
    public static final Path REPORT_PATH = Path.of("build/reports/differential-results.jsonl");

    public record ExecutionResult(
            boolean success,
            String stdout,
            String stderr,
            String exceptionType,
            String exceptionMessage,
            int exitCode,
            boolean timedOut
    ) {}

    public record ExtractedError(String type, String message) {}

    private static String cpythonPath = null;
    private static String cpythonVersion = null;
    private static boolean cpythonChecked = false;

    private DifferentialHarness() {}

    public static synchronized String findCPython() {
        if (!cpythonChecked) {
            cpythonChecked = true;
            for (String candidate : new String[]{"python3.14", "python3.13", "python3"}) {
                try {
                    Process p = new ProcessBuilder(candidate, "--version").start();
                    if (p.waitFor(2, TimeUnit.SECONDS) && p.exitValue() == 0) {
                        cpythonPath = candidate;
                        var r = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8));
                        cpythonVersion = r.readLine();
                        if (cpythonVersion == null || cpythonVersion.isEmpty()) {
                            var er = new BufferedReader(new InputStreamReader(p.getErrorStream(), StandardCharsets.UTF_8));
                            cpythonVersion = er.readLine();
                        }
                        break;
                    }
                } catch (Exception ignored) {}
            }
        }
        return cpythonPath;
    }

    /**
     * Returns the detected CPython version string (e.g. "Python 3.12.3" or "Python 3.14.0a1").
     */
    public static synchronized String getCPythonVersion() {
        findCPython();
        return cpythonVersion != null ? cpythonVersion : "Not available";
    }

    public static ExecutionResult runCPython(String script) {
        return runCPython(script, 5);
    }

    public static ExecutionResult runCPython(String script, int timeoutSeconds) {
        String pyBin = findCPython();
        if (pyBin == null) {
            return new ExecutionResult(false, "", "CPython not available in environment",
                    "EnvironmentError", "", -1, false);
        }
        Process process = null;
        try {
            process = new ProcessBuilder(pyBin, "-c", script)
                    .redirectErrorStream(false)
                    .start();

            Process finalProc = process;
            CompletableFuture<String> stdoutFuture = CompletableFuture.supplyAsync(() ->
                    readStreamCapped(finalProc.getInputStream()));
            CompletableFuture<String> stderrFuture = CompletableFuture.supplyAsync(() ->
                    readStreamCapped(finalProc.getErrorStream()));

            boolean finished = process.waitFor(timeoutSeconds, TimeUnit.SECONDS);
            if (!finished) {
                process.destroyForcibly();
                process.waitFor(2, TimeUnit.SECONDS);
                stdoutFuture.cancel(true);
                stderrFuture.cancel(true);
                return new ExecutionResult(false, "", "Process timed out after " + timeoutSeconds + "s",
                        "TimeoutError", "Process timed out", -1, true);
            }

            String stdout = stdoutFuture.get(2, TimeUnit.SECONDS);
            String stderr = stderrFuture.get(2, TimeUnit.SECONDS);
            int exitCode = process.exitValue();

            ExtractedError err = extractError(stderr);
            return new ExecutionResult(exitCode == 0, stdout, stderr, err.type(), err.message(), exitCode, false);
        } catch (Exception ex) {
            if (process != null && process.isAlive()) {
                process.destroyForcibly();
            }
            return new ExecutionResult(false, "", ex.getMessage(), ex.getClass().getSimpleName(),
                    ex.getMessage(), -1, false);
        }
    }

    public static synchronized ExecutionResult runJython3(String script) {
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
            return new ExecutionResult(true, outStr, "", null, "", 0, false);
        } catch (InterpreterException ie) {
            String errStr = interp.formatTraceback(ie, "<stdin>", script);
            String msg = ie.value() instanceof PyException pe ? pe.message() : ie.getMessage();
            return new ExecutionResult(false, outBuf.toString(StandardCharsets.UTF_8).trim(),
                    errStr, ie.typeName(), msg, 1, false);
        } catch (Exception ex) {
            return new ExecutionResult(false, outBuf.toString(StandardCharsets.UTF_8).trim(),
                    ex.getMessage(), ex.getClass().getSimpleName(), ex.getMessage(), 1, false);
        } finally {
            System.setOut(originalOut);
            System.setErr(originalErr);
        }
    }

    public static void assertMatchesCPython(String script) {
        assertMatchesCPython(deriveCaseId(script), script);
    }

    public static void assertMatchesCPython(String caseId, String script) {
        ExecutionResult cpy = runCPython(script);
        if (!cpy.success() && "EnvironmentError".equals(cpy.exceptionType())) {
            // CPython not installed in this environment
            return;
        }
        ExecutionResult jy3 = runJython3(script);

        boolean stdoutMatch = Objects.equals(cpy.stdout(), jy3.stdout());
        boolean exceptionMatch = !cpy.success() && Objects.equals(cpy.exceptionType(), jy3.exceptionType());
        boolean successMatch = cpy.success() == jy3.success();

        recordJsonReport(caseId, cpy, jy3, stdoutMatch, exceptionMatch);

        assertFalse(cpy.timedOut(), () -> "CPython timed out for case: " + caseId);
        assertFalse(jy3.timedOut(), () -> "Jython timed out for case: " + caseId);

        assertEquals(cpy.success(), jy3.success(),
                () -> "Success status mismatch for case: " + caseId + "\nScript:\n" + script
                        + "\nCPython stdout: " + cpy.stdout() + "\nCPython stderr: " + cpy.stderr()
                        + "\nJython stdout: " + jy3.stdout() + "\nJython stderr: " + jy3.stderr());

        if (cpy.success()) {
            assertEquals(cpy.stdout(), jy3.stdout(), "stdout mismatch for case: " + caseId + "\nScript:\n" + script);
        } else {
            assertEquals(cpy.exceptionType(), jy3.exceptionType(),
                    "Exception type mismatch for case: " + caseId + "\nScript:\n" + script);
        }
    }

    private static String readStreamCapped(java.io.InputStream in) {
        try (var r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
            var sb = new StringBuilder();
            String line;
            while ((line = r.readLine()) != null) {
                if (sb.length() < MAX_OUTPUT_CHARS) {
                    sb.append(line).append('\n');
                }
            }
            return sb.toString().trim();
        } catch (Exception e) {
            return "";
        }
    }

    public static ExtractedError extractError(String stderr) {
        if (stderr == null || stderr.isEmpty()) return new ExtractedError(null, "");
        String[] lines = stderr.split("\n");
        for (int i = lines.length - 1; i >= 0; i--) {
            String line = lines[i].trim();
            int colon = line.indexOf(':');
            if (colon > 0) {
                String candidate = line.substring(0, colon).trim();
                if (candidate.matches("[A-Za-z_][A-Za-z0-9_]*Error|[A-Za-z_][A-Za-z0-9_]*Exception")) {
                    String msg = line.substring(colon + 1).trim();
                    return new ExtractedError(candidate, msg);
                }
            } else if (line.matches("[A-Za-z_][A-Za-z0-9_]*Error|[A-Za-z_][A-Za-z0-9_]*Exception")) {
                return new ExtractedError(line, "");
            }
        }
        return new ExtractedError("UnknownError", stderr);
    }

    private static synchronized void recordJsonReport(
            String caseId,
            ExecutionResult cpy,
            ExecutionResult jy3,
            boolean stdoutMatch,
            boolean exceptionMatch
    ) {
        try {
            if (REPORT_PATH.getParent() != null) {
                Files.createDirectories(REPORT_PATH.getParent());
            }
            String jsonLine = String.format(
                    "{\"case\":\"%s\",\"cpython_version\":\"%s\",\"jython_version\":\"%s\","
                            + "\"stdout_match\":%b,\"exception_match\":%b,\"timed_out\":%b}",
                    escapeJson(caseId),
                    escapeJson(getCPythonVersion()),
                    escapeJson(JYTHON_VERSION),
                    stdoutMatch,
                    exceptionMatch,
                    cpy.timedOut() || jy3.timedOut()
            );
            Files.writeString(REPORT_PATH, jsonLine + "\n",
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception ignored) {}
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n").replace("\r", "");
    }

    private static String deriveCaseId(String script) {
        String trimmed = script.trim();
        String firstLine = trimmed.split("\n", 2)[0];
        if (firstLine.length() > 40) {
            firstLine = firstLine.substring(0, 40);
        }
        return firstLine.replaceAll("[^a-zA-Z0-9_]", "_");
    }
}
