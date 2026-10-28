package com.example.bankingrag;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * Builders for Qdrant filter JSON. A filter has up to three clauses:
 *   must     — every condition must hold (AND)
 *   should   — at least one condition must hold (OR)
 *   must_not — no condition may hold (NOT)
 * Clauses in the same filter are combined with AND, e.g. {"must": [A], "should": [B, C]} = A AND (B OR C).
 * See docs/output-explained.md, "How should works".
 */
public final class Filters {

    private static final JsonNodeFactory JSON = JsonNodeFactory.instance;

    /** {"key": field, "match": {"value": value}} — exact match on a keyword field. */
    public static ObjectNode match(String field, String value) {
        ObjectNode condition = JSON.objectNode().put("key", field);
        condition.putObject("match").put("value", value);
        return condition;
    }

    /** {"key": field, "range": {"gte": min, "lte": max}} — numeric range, bounds inclusive. */
    public static ObjectNode range(String field, double gte, double lte) {
        ObjectNode condition = JSON.objectNode().put("key", field);
        condition.putObject("range").put("gte", gte).put("lte", lte);
        return condition;
    }

    public static ObjectNode must(ObjectNode... conditions) {
        return clause("must", conditions);
    }

    /**
     * OR: a point passes if at least one condition is true.
     * should(match("topic", "fraud"), match("topic", "transfers")) = topic is fraud OR topic is transfers.
     * - Combined with must in the same filter, should is still required (unlike Elasticsearch, where it
     *   only boosts the score): {"must": [A], "should": [B, C]} = A AND (B OR C).
     * - With a single condition it behaves like must ("at least one of one").
     * - For one field matching any of several values, {"match": {"any": [...]}} is shorter; should is
     *   for conditions on different fields, e.g. topic = fraud OR fee_amount >= 20.
     */
    public static ObjectNode should(ObjectNode... conditions) {
        return clause("should", conditions);
    }

    public static ObjectNode mustNot(ObjectNode... conditions) {
        return clause("must_not", conditions);
    }

    private static ObjectNode clause(String name, ObjectNode... conditions) {
        ObjectNode filter = JSON.objectNode();
        for (ObjectNode c : conditions) filter.withArray(name).add(c);
        return filter;
    }

    private Filters() {}
}
