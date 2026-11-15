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
        LValue.TupleTarget,
        LValue.StarTarget {

    record NameTarget(String name) implements LValue {}
    record AttributeTarget(Jython3Parser.ExprContext objectExpr, String attribute) implements LValue {}
    record SubscriptTarget(Jython3Parser.ExprContext containerExpr, Jython3Parser.Slice_itemContext indexExpr) implements LValue {}
    record TupleTarget(List<LValue> elements) implements LValue {}
    record StarTarget(LValue inner) implements LValue {}

    static LValue fromTargetList(Jython3Parser.Target_listContext ctx) {
        var targets = ctx.single_target();
        if (targets.size() == 1 && ctx.COMMA().isEmpty()) {
            LValue single = fromSingleTarget(targets.get(0));
            if (single instanceof StarTarget) {
                throw new CompileError("starred assignment target must be in a list or tuple");
            }
            return single;
        }
        List<LValue> elems = new ArrayList<>(targets.size());
        int starCount = 0;
        for (var t : targets) {
            LValue lv = fromSingleTarget(t);
            if (lv instanceof StarTarget) {
                starCount++;
                if (starCount > 1) {
                    throw new CompileError("multiple starred expressions in assignment");
                }
            }
            elems.add(lv);
        }
        return new TupleTarget(elems);
    }

    static LValue fromSingleTarget(Jython3Parser.Single_targetContext ctx) {
        if (ctx instanceof Jython3Parser.TargetStarContext ts) {
            LValue inner = fromSingleTarget(ts.single_target());
            if (inner instanceof StarTarget) {
                throw new CompileError("cannot use nested starred assignment target");
            }
            return new StarTarget(inner);
        }
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
