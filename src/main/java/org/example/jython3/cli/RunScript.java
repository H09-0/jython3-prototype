package org.example.jython3.cli;

import org.example.jython3.Jython3Interpreter;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.SystemExit;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Script runner – executes a Python script file using Jython3Interpreter.
 *
 * <p>Exit codes:
 * <ul>
 *   <li>0  – script completed successfully.
 *   <li>1  – Python-level error (InterpreterException printed as traceback).
 *   <li>2  – usage or I/O error.
 *   <li>N  – explicit sys.exit(N) from the script.
 * </ul>
 */
public class RunScript {
    public static void main(String[] args) {
        if (args.length == 0) {
            System.err.println("Usage: jython3 <script.py> [arg ...] or jython3 -c \"code\"");
            System.exit(2);
        }

        var interpreter = Jython3Interpreter.trusted();

        // Handle inline execution: jython3 -c "<code>"
        if ("-c".equals(args[0])) {
            if (args.length < 2) {
                System.err.println("Argument expected for the -c option");
                System.exit(2);
            }
            String code = args[1];
            try {
                interpreter.eval(code, "<string>");
            } catch (SystemExit se) {
                System.exit(se.code);
            } catch (InterpreterException ie) {
                System.err.println(interpreter.formatTraceback(ie, "<string>", code));
                System.exit(1);
            }
            return;
        }

        String filename = args[0];

        // Forward remaining CLI args as sys.argv
        var argv = new org.example.jython3.runtime.PyList(new java.util.ArrayList<>());
        for (String a : args) argv.items().add(new org.example.jython3.runtime.PyStr(a));
        var sysModule = (org.example.jython3.runtime.PyModule)
                interpreter.getGlobal("sys");
        if (sysModule == null) {
            // import sys so it is available
            interpreter.eval("import sys");
            sysModule = (org.example.jython3.runtime.PyModule) interpreter.getGlobal("sys");
        }
        if (sysModule != null) sysModule.attrs().put("argv", argv);

        try {
            String script = Files.readString(Path.of(filename));

            // Execute via eval() so errors propagate as InterpreterException
            try {
                interpreter.eval(script, filename);
            } catch (SystemExit se) {
                System.exit(se.code);
            } catch (InterpreterException ie) {
                System.err.println(interpreter.formatTraceback(ie, filename, script));
                System.exit(1);
            }

        } catch (SystemExit se) {
            System.exit(se.code);
        } catch (IOException e) {
            System.err.println("Error reading file '" + filename + "': " + e.getMessage());
            System.exit(2);
        }
    }
}
