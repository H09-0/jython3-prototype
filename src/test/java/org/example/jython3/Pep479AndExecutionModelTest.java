package org.example.jython3;

import org.example.jython3.differential.DifferentialHarness;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Conformance tests grounded directly in:
 * - Python Execution Model §4.2.2 (docs.python.org/3/reference/executionmodel.html):
 *   function-local binding triggers UnboundLocalError instead of falling back to globals.
 * - PEP 479 (peps.python.org/pep-0479/):
 *   StopIteration escaping a generator body becomes RuntimeError with __cause__.
 */
class Pep479AndExecutionModelTest {

    @Test
    @DisplayName("Execution Model §4.2.2: function locals shadow globals and raise UnboundLocalError before assignment")
    void unboundLocalErrorShadowsGlobalsMatchCPython() {
        String code = """
            x = 100
            def read_before_assign():
                try:
                    return x
                except UnboundLocalError:
                    return "caught-unbound"
                x = 200

            def del_then_read():
                y = 5
                del y
                try:
                    return y
                except UnboundLocalError:
                    return "caught-del"

            def closure_with_local_shadow():
                z = 10
                def inner():
                    try:
                        return z
                    except UnboundLocalError:
                        return "shadowed-z"
                    z = 20
                return inner()

            print(read_before_assign(), del_then_read(), closure_with_local_shadow(), x)
            """;
        DifferentialHarness.assertMatchesCPython(code);
    }

    @Test
    @DisplayName("PEP 479: unhandled StopIteration inside generator or g.throw(StopIteration) becomes RuntimeError")
    void pep479StopIterationInGeneratorMatchesCPython() {
        String code = """
            def bad_next():
                it = iter([])
                yield next(it)

            g1 = bad_next()
            try:
                next(g1)
                print("NO_ERR")
            except RuntimeError as e:
                print("pep479_next:", type(e.__cause__).__name__)

            def explicit_raise():
                yield 1
                raise StopIteration("stop")

            g2 = explicit_raise()
            print("first:", next(g2))
            try:
                next(g2)
            except RuntimeError:
                print("pep479_raise: RuntimeError")
            try:
                next(g2)
            except StopIteration:
                print("after_err: StopIteration")

            def throw_target():
                yield 42

            g3 = throw_target()
            next(g3)
            try:
                g3.throw(StopIteration)
            except RuntimeError:
                print("pep479_throw: RuntimeError")
            """;
        DifferentialHarness.assertMatchesCPython(code);
    }
}
