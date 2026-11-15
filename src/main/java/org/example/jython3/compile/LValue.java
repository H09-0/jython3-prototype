package org.example.jython3.compile;

import org.example.jython3.Jython3Parser;

import java.util.ArrayList;
import java.util.List;

/**
 * Unified representation of assignment, augmented-assignment, unpacking, and deletion targets.
 */
public sealed interface LValue permits
        LValue.NameTarget,
        LValue.AttributeTarget,
        LValue.SubscriptTarget,
        LValue.TupleTarget {

    record NameTarget(String name) implements LValue {}
    record AttributeTarget(Jython3Parser.ExprContext objectExpr, String attribute) implements LValue {}
    record SubscriptTarget(Jython3Parser.ExprContext containerExpr, Jython3Parser.Slice_itemContext indexExpr) implements LValue {}
    record TupleTarget(List<LValue> elements) implements LValue {}

    static LValue fromTargetList(Jython3Parser.Target_listContext ctx) {
        var targets = ctx.single_target();
        if (targets.size() == 1 && ctx.COMMA().isEmpty()) {
            return fromSingleTarget(targets.get(0));
        }
        List<LValue> elems = new ArrayList<>(targets.size());
        for (var t : targets) {
            elems.add(fromSingleTarget(t));
        }
        return new TupleTarget(elems);
    }

    static LValue fromSingleTarget(Jython3Parser.Single_targetContext ctx) {
        if (ctx instanceof Jython3Parser.TargetNameContext tn) {
            return new NameTarget(tn.ID().getText());
        }
        if (ctx instanceof Jython3Parser.TargetAttrContext ta) {
            return new AttributeTarget(ta.expr(), ta.ID().getText());
        }
        if (ctx instanceof Jython3Parser.TargetSubscriptContext ts) {
            return new SubscriptTarget(ts.expr(), ts.slice_item());
        }
        if (ctx instanceof Jython3Parser.TargetParenTupleContext tp) {
            return fromTargetList(tp.target_list());
        }
        if (ctx instanceof Jython3Parser.TargetListTupleContext tl) {
            return fromTargetList(tl.target_list());
        }
        throw new CompileError("unsupported assignment target");
    }
}
