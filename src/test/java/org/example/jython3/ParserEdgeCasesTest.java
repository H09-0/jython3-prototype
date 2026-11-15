package org.example.jython3;

import org.antlr.v4.runtime.BaseErrorListener;
import org.antlr.v4.runtime.CharStreams;
import org.antlr.v4.runtime.CommonTokenStream;
import org.antlr.v4.runtime.RecognitionException;
import org.antlr.v4.runtime.Recognizer;
import org.example.jython3.parse.PythonIndentStream;
import org.example.jython3.runtime.InterpreterException;
import org.example.jython3.runtime.PyBool;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Independent parser and grammar edge-case tests: expression statements
 * beginning with '[' or '{', chained comparisons, 'not' vs comparison
 * precedence, multi-item 'with', nested comprehensions/lambdas, and tuple/set/dict
 * literals across all positions.
 */
class ParserEdgeCasesTest {

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
    @DisplayName("Parser accepts all grammar edge cases with zero syntax errors")
    void parserAcceptsEdgeCases() {
        String[] validSnippets = {
                "[1, 2, 3]",
                "[1] in {1}",
                "{'a': 1}",
                "{1, 2, 3}",
                "{[1]: 1}",
                "{{1}: 1}",
                "\"module docstring\"\n'expr stmt'\n1 + 2\n1, 2",
                "x = 1 < 2 < 3 == True",
                "x = not 1 == 2 and not 3 in [1, 2]",
                "with CM() as a, CM() as b:\n    pass",
                "fns = [lambda x, k=i: x + k for i in range(3) if i > 0]",
                "nested = [[a + b for a in (1, 2)] for b in {10, 20}]",
                "d = {(1,): [1, 2], 'k': {3, 4}, 't': (1,)}\n",
                "try:\n    pass\nexcept KeyError as k:\n    pass\nexcept (ValueError, TypeError):\n    pass\nelse:\n    pass\nfinally:\n    raise ValueError('x')",
                "def g():\n    yield 1, 2",
        };
        for (String src : validSnippets) {
            var errs = parseErrors(src);
            assertTrue(errs.isEmpty(), "Unexpected parse errors for:\n" + src + "\nErrors: " + errs);
        }
    }

    @Test
    @DisplayName("Expression statements starting with '[' or '{' execute and raise runtime errors normally")
    void bracketAndBraceExpressionStatements() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            "docstring"
            [1, 2, 3]
            {"k": 1}
            {1, 2}
            log = []
            [log.append(1), log.append(2)]
            {"a": log.append(3)}
            {log.append(4)}
            ok = log == [1, 2, 3, 4]
            """);
        assertEquals(PyBool.TRUE, interp.getGlobal("ok"), String.valueOf(interp.getGlobal("log")));

        var te = assertThrows(InterpreterException.class, () -> interp.eval("[1] in {1}"));
        assertEquals("TypeError", te.typeName());
        var te2 = assertThrows(InterpreterException.class, () -> interp.eval("{[1]: 1}"));
        assertEquals("TypeError", te2.typeName());
    }

    @Test
    @DisplayName("Chained comparisons evaluate middle terms once, short-circuit, and respect precedence")
    void chainedComparisonsAndPrecedence() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            calls = []
            def probe(val):
                calls.append(val)
                return val

            c1 = 1 < probe(5) < 10
            calls1 = list(calls)

            calls = []
            c2 = 10 < probe(5) < probe(99)
            calls2 = list(calls)

            checks = [
                c1 == True and calls1 == [5],
                c2 == False and calls2 == [5],
                (0 == 0 == 0) == True,
                ((0 == 0) == 0) == False,
                (10 > 5 > 2) == True,
                (1 < 2 < 3 < 4) == True,
                (1 < 3 < 2) == False,
                (1 in [1, 2] == True) == False,
                (not 1 == 2) == True,
                (not 2 == True) == True,
                (not 1 in [2, 3]) == True,
                (1 | 2 == 3) == True,
                (1 + 2 * 3 == 7) == True,
            ]
            ok = all(checks)
            """);
        assertEquals(PyBool.TRUE, interp.getGlobal("ok"), String.valueOf(interp.getGlobal("checks")));
    }

    @Test
    @DisplayName("Multi-item with enters left-to-right, exits right-to-left, and handles exceptions & returns")
    void multiItemWith() {
        var interp = Jython3Interpreter.trusted();
        interp.eval("""
            class CM:
                def __init__(self, log, tag, fail_enter=False):
                    self.log = log
                    self.tag = tag
                    self.fail_enter = fail_enter
                def __enter__(self):
                    if self.fail_enter:
                        raise RuntimeError("enter-fail-" + self.tag)
                    self.log.append("enter-" + self.tag)
                    return self.tag
                def __exit__(self, t, v, tb):
                    self.log.append("exit-" + self.tag)
                    return False

            log1 = []
            with CM(log1, "A") as a, CM(log1, "B") as b:
                log1.append(a + b)
            assert log1 == ["enter-A", "enter-B", "AB", "exit-B", "exit-A"], log1

            log2 = []
            def ret_multi(log):
                with CM(log, "A"), CM(log, "B"):
                    return 42
            assert ret_multi(log2) == 42
            assert log2 == ["enter-A", "enter-B", "exit-B", "exit-A"], log2

            # if second __enter__ fails, first __exit__ still runs
            log3 = []
            try:
                with CM(log3, "A"), CM(log3, "B", True):
                    log3.append("body")
            except RuntimeError:
                log3.append("caught")
            assert log3 == ["enter-A", "exit-A", "caught"], log3

            def pair_gen():
                yield 1, 2
                yield 3, 4
            assert list(pair_gen()) == [(1, 2), (3, 4)]

            ok = True
            """);
        assertEquals(PyBool.TRUE, interp.getGlobal("ok"));
    }
}
