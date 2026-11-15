package org.example.jython3.field;

import org.example.jython3.Jython3Interpreter;

/** Project 1: Analytics & JSON Pipeline (nested JSON, closures, lambdas, comprehensions, sorting). */
public final class AnalyticsScenario implements FieldScenario {

    private static final String CODE = """
        import json
        import math

        raw_orders = [
            '{"id": 101, "user": "Alice", "items": [{"price": 25.5, "qty": 2}, {"price": 10.0, "qty": 1}], "region": "EU"}',
            '{"id": 102, "user": "Bob", "items": [{"price": 120.0, "qty": 1}], "region": "US"}',
            '{"id": 103, "user": "Alice", "items": [{"price": 15.0, "qty": 3}, {"price": 5.5, "qty": 2}], "region": "EU"}',
            '{"id": 104, "user": "Charlie", "items": [{"price": 200.0, "qty": 2}], "region": "APAC"}',
            '{"id": 105, "user": "Bob", "items": [{"price": 45.0, "qty": 2}], "region": "US"}'
        ]

        def compute_order_total(order):
            return sum([item["price"] * item["qty"] for item in order["items"]])

        orders = [json.loads(s) for s in raw_orders]
        totals = [(o["id"], o["user"], o["region"], compute_order_total(o)) for o in orders]

        user_spend = {}
        for oid, user, region, amt in totals:
            user_spend[user] = user_spend.get(user, 0.0) + amt

        ranked = sorted(list(user_spend.items()), key=lambda pair: pair[1], reverse=True)
        top_user, top_spend = ranked[0]
        total_revenue = sum([t[3] for t in totals])
        avg_order = total_revenue / len(totals)
        report_json = json.dumps({"top_user": top_user, "top_spend": top_spend, "avg": avg_order})
        """;

    @Override public String name() { return "Project 1: Analytics & JSON Pipeline"; }
    @Override public String pythonCode() { return CODE; }

    @Override
    public void verify(Jython3Interpreter interp) {
        FieldScenario.assertTrue("top_user == 'Charlie'",
                "Charlie".equals(interp.getGlobal("top_user").toString()));
        FieldScenario.assertTrue("top_spend == 400.0",
                Math.abs(Double.parseDouble(interp.getGlobal("top_spend").toString()) - 400.0) < 1e-6);
        FieldScenario.assertTrue("avg_order == 145.4",
                Math.abs(Double.parseDouble(interp.getGlobal("avg_order").toString()) - 145.4) < 1e-6);
    }
}
