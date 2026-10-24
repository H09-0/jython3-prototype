package org.example.jython3.cli;

import org.example.jython3.Jython3Interpreter;
import org.example.jython3.runtime.SystemExit;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;

/**
 * Interactive REPL for Jython3.
 *
 * <p>Block detection logic:
 * <ul>
 *   <li>A compound statement header (ending with {@code :}) keeps the prompt in continuation mode.
 *   <li>An empty line while in continuation mode flushes and executes the accumulated buffer.
 *   <li>Unbalanced parentheses / brackets / braces also keep continuation mode.
 * </ul>
 */
public class Repl {
    public static void main(String[] args) throws IOException {
        Jython3Interpreter py = new Jython3Interpreter();
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        StringBuilder buffer = new StringBuilder();
        System.out.println("\u001B[36mJython3 3.0.0 REPL\u001B[0m (type exit() to quit)");
        while (true) {
            try {
                boolean inContinuation = buffer.length() > 0;
                System.out.print(inContinuation ? "\u001B[32m... \u001B[0m" : "\u001B[32m>>> \u001B[0m");
                String line = br.readLine();
                if (line == null) break;
                if (!inContinuation && line.trim().equals("exit()")) break;

                // Empty line in continuation mode → flush buffer
                if (inContinuation && line.trim().isEmpty()) {
                    String src = buffer.toString();
                    buffer.setLength(0);
                    try {
                        py.exec(src);
                    } catch (SystemExit se) {
                        System.exit(se.code);
                    }
                    continue;
                }

                buffer.append(line).append("\n");
                String src = buffer.toString();

                // Only execute immediately for simple (non-block) statements
                if (!inBlock(src)) {
                    buffer.setLength(0);
                    try {
                        py.exec(src);
                    } catch (SystemExit se) {
                        System.exit(se.code);
                    }
                }
                // Otherwise stay in continuation mode until empty line

            } catch (SystemExit se) {
                System.exit(se.code);
            }
        }
    }

    /**
     * Returns {@code true} when the accumulated source is a block header or has unbalanced
     * delimiters, meaning we need more input before executing.
     */
    private static boolean inBlock(String src) {
        // Check last non-empty, non-comment line
        String[] lines = src.replace("\r", "").split("\n");
        String lastReal = "";
        for (int i = lines.length - 1; i >= 0; i--) {
            String t = lines[i].trim();
            if (!t.isEmpty() && !t.startsWith("#")) { lastReal = t; break; }
        }

        // Block header: last real line ends with ':'
        if (lastReal.endsWith(":")) return true;

        // Unbalanced delimiters
        int paren = 0, brack = 0, brace = 0;
        boolean inStr = false;
        char strChar = 0;
        for (int i = 0; i < src.length(); i++) {
            char c = src.charAt(i);
            if ((c == '"' || c == '\'') && (i == 0 || src.charAt(i - 1) != '\\')) {
                if (!inStr) { inStr = true; strChar = c; }
                else if (strChar == c) { inStr = false; }
                continue;
            }
            if (inStr) continue;
            if (c == '(') paren++; else if (c == ')') paren--;
            else if (c == '[') brack++; else if (c == ']') brack--;
            else if (c == '{') brace++; else if (c == '}') brace--;
        }
        return paren > 0 || brack > 0 || brace > 0;
    }
}
