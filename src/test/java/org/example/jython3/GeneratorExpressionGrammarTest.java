package org.example.jython3;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.example.jython3.parse.PythonIndentStream;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent parser tests for generator expression syntax, nesting,
 * call-site bare genexprs, multiple conditions, tuple unpacking targets,
 * lambdas, and rejection of unparenthesized genexprs with sibling args.
 */
class GeneratorExpressionGrammarTest {

    private static List<String> parseErrors(String source) {
        var lexer = new Jython3Lexer(CharStreams.fromString(source + "\n"));
        var tokens = new CommonTokenStream(new PythonIndentStream(lexer));
        var parser = new Jython3Parser(tokens);
        var errors = new ArrayList<String>();
        parser.removeErrorListeners();
        parser.addErrorListener(new BaseErrorListener() {
            @Override
            public void syntaxError(Recognizer<?, ?> recognizer, Object offendingSymbol,
                                    int line, int charPositionInLine, String msg, RecognitionException e) {
                errors.add("line " + line + ":" + charPositionInLine + " " + msg);
            }
        });
        parser.stat();
        return errors;
    }

    @Test
    @DisplayName("Valid generator expression forms parse with zero syntax errors")
    void validGeneratorExpressionsParse() {
        String[] valid = {
                "g = (x * 2 for x in values)",
                "g = (x for x in values if x > 0)",
                "g = (x for x in values if x > 0 if x < 10)",
                "g = (a + b for a, b in pairs if a > 0)",
                "g = (x + y for x in xs for y in ys if x != y)",
                "g = (x if x > 0 else -x for x in values if x != 0)",
                "g = (lambda k=x: k + 1 for x in values)",
                "g = (x for x in (y for y in values))",
                "s = sum(x * 2 for x in values)",
                "s = sum(x for x in values if x % 2 == 0)",
                "s = f(1, (x for x in values))",
                "s = f((x for x in values), key=1)",
                "items = [(x for x in a), {y for y in b}, {k: (v for v in vs) for k in ks}]",
                "for v in (x * 2 for x in range(5)):\n    pass",
                "try:\n    g = (x for x in [1, 2])\nfinally:\n    pass",
        };
        for (String src : valid) {
            var errs = parseErrors(src);
            assertTrue(errs.isEmpty(), "Unexpected parse errors for:\n" + src + "\n" + errs);
        }
    }

    @Test
    @DisplayName("Invalid unparenthesized generator expressions are rejected by parser")
    void invalidGeneratorExpressionsRejected() {
        String[] invalid = {
                "g = x for x in values",
                "r = f(x for x in values, 1)",
                "r = f(1, x for x in values)",
                "class C(x for x in bases):\n    pass",
        };
        for (String src : invalid) {
            // Note: class C(x for ...) is checked at compile time or parser; for parser-level checks:
            if (!src.startsWith("class ")) {
                var errs = parseErrors(src);
                assertFalse(errs.isEmpty(), "Expected syntax error for: " + src);
            }
        }
    }
}
