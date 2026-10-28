package com.example.bankingrag;

import org.junit.jupiter.api.Test;

import static com.example.bankingrag.Filters.match;
import static com.example.bankingrag.Filters.must;
import static com.example.bankingrag.Filters.mustNot;
import static com.example.bankingrag.Filters.range;
import static com.example.bankingrag.Filters.should;
import static org.junit.jupiter.api.Assertions.assertEquals;

/** Checks the filter JSON matches Qdrant's format. No Docker needed. */
class FiltersTest {

    @Test
    void mustMatchBuildsKeywordCondition() {
        assertEquals("{\"must\":[{\"key\":\"topic\",\"match\":{\"value\":\"overdraft\"}}]}",
                must(match("topic", "overdraft")).toString());
    }

    @Test
    void shouldHoldsEveryCondition() {
        assertEquals("{\"should\":[{\"key\":\"topic\",\"match\":{\"value\":\"fraud\"}},"
                        + "{\"key\":\"topic\",\"match\":{\"value\":\"transfers\"}}]}",
                should(match("topic", "fraud"), match("topic", "transfers")).toString());
    }

    @Test
    void mustNotUsesSnakeCaseKey() {
        assertEquals("{\"must_not\":[{\"key\":\"topic\",\"match\":{\"value\":\"overdraft\"}}]}",
                mustNot(match("topic", "overdraft")).toString());
    }

    @Test
    void rangeHasInclusiveBounds() {
        assertEquals("{\"key\":\"fee_amount\",\"range\":{\"gte\":10.0,\"lte\":30.0}}",
                range("fee_amount", 10, 30).toString());
    }
}
