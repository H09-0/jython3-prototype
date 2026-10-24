package org.example.jython3.cli;

import org.example.jython3.Jython3Interpreter;
import org.example.jython3.runtime.SystemExit;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.IOException;

public class Repl {
    public static void main(String[] args) throws IOException {
        Jython3Interpreter py = new Jython3Interpreter();
        BufferedReader br = new BufferedReader(new InputStreamReader(System.in));
        StringBuilder buffer = new StringBuilder();
        System.out.println("\u001B[36mJython3 3.0.0 REPL\u001B[0m (type exit() to quit)");
        while (true) {
            try {
                System.out.print(buffer.length() == 0 ? "\u001B[32m>>> \u001B[0m" : "\u001B[32m... \u001B[0m");
                String line = br.readLine();
                if (line == null) break;
                if (buffer.length() == 0 && line.trim().equals("exit()")) break;
                buffer.append(line).append("\n");
                String src = buffer.toString();
                if (line.trim().isEmpty() && buffer.toString().trim().isEmpty()) continue;
                if (looksComplete(src)) {
                    try {
                        py.exec(src);
                    } catch (SystemExit se) {
                        System.exit(se.code);
                    }
                    buffer.setLength(0);
                }
            } catch (SystemExit se) {
                System.exit(se.code);
            }
        }
    }
    
    private static boolean looksComplete(String src) {
        String trimmed = src.trim();
        if (trimmed.endsWith(":")) return false;
        String[] lines = src.replace("\r", "").split("\n");
        for (String l : lines) {
            String t = l.trim();
            if (t.isEmpty() || t.startsWith("#")) continue;
            if ((t.startsWith("def ") || t.startsWith("class ") || t.startsWith("if ") ||
                    t.startsWith("elif ") || t.startsWith("else:") || t.startsWith("while ") ||
                    t.startsWith("for ") || t.startsWith("try:") || t.startsWith("except") ||
                    t.startsWith("finally:")) && t.endsWith(":")) {
                return false;
            }
        }
        // simple balance check for parens/brackets/braces
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
            if (c == '(') paren++;
            else if (c == ')') paren--;
            else if (c == '[') brack++;
            else if (c == ']') brack--;
            else if (c == '{') brace++;
            else if (c == '}') brace--;
        }
        if (paren > 0 || brack > 0 || brace > 0) return false;
        return true;
    }
}
