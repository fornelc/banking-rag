package com.example.bankingrag;

import com.example.bankingrag.QdrantRestClient.Point;
import com.example.bankingrag.QdrantRestClient.ScoredPoint;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.qdrant.QdrantContainer;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import static com.example.bankingrag.Filters.match;
import static com.example.bankingrag.Filters.must;
import static com.example.bankingrag.Filters.mustNot;
import static com.example.bankingrag.Filters.range;
import static com.example.bankingrag.Filters.should;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end checks against a real Qdrant started by Testcontainers (Docker must be running).
 * Mirrors the demo's searches A–G and asserts what the demo output shows.
 */
@Testcontainers
class RetrievalTest {

    private static final String QUESTION = "What fees will I be charged if I spend more than I have?";

    @Container
    static final QdrantContainer QDRANT = new QdrantContainer("qdrant/qdrant:v1.20.0");

    static QdrantRestClient qdrant;
    static List<Point> points;
    static float[] queryVector;

    @BeforeAll
    static void indexPolicies() {
        qdrant = new QdrantRestClient("http://" + QDRANT.getHost() + ":" + QDRANT.getMappedPort(6333));
        EmbeddingModel model = new AllMiniLmL6V2EmbeddingModel();
        points = PolicySnippets.ALL.stream()
                .map(s -> new Point(s.id(), Main.embed(model, s.text()), Main.payload(s)))
                .toList();
        queryVector = Main.embed(model, QUESTION);
        Main.createAndFill(qdrant, Main.COLLECTION, "Cosine", points);
    }

    @Test
    void embeddingsHave384Dimensions() {
        assertEquals(Main.VECTOR_SIZE, queryVector.length);
        points.forEach(p -> assertEquals(Main.VECTOR_SIZE, p.vector().length));
    }

    @Test
    void unfilteredSearchFindsOverdraftByMeaning() {
        List<ScoredPoint> results = search(null);
        assertEquals(5, results.size());
        // The question never says "overdraft", but the two $35 overdraft policies rank first.
        assertEquals(Set.of(1L, 3L), Set.of(results.get(0).id(), results.get(1).id()));
        assertScoresDescending(results);
    }

    @Test
    void mustKeepsOnlyThatTopic() {
        assertTopics(search(must(match("topic", "overdraft"))), Set.of("overdraft"));
    }

    @Test
    void mustReturnsLowScoresRatherThanNothing() {
        List<ScoredPoint> results = search(must(match("topic", "loans")));
        assertEquals(2, results.size());
        assertTopics(results, Set.of("loans"));
        assertTrue(results.get(0).score() < 0.5, "loans are a poor match but still returned");
    }

    @Test
    void shouldIsOr() {
        assertTopics(search(should(match("topic", "fraud"), match("topic", "transfers"))), Set.of("fraud", "transfers"));
    }

    @Test
    void mustNotExcludesTopic() {
        List<ScoredPoint> results = search(mustNot(match("topic", "overdraft")));
        assertEquals(5, results.size());
        assertFalse(topics(results).contains("overdraft"));
    }

    @Test
    void rangeMatchesOnlyFeesInBounds() {
        // $12 maintenance (id 5) and $25 wire (id 12). $3, $35 and points without fee_amount are excluded.
        assertEquals(Set.of(5L, 12L), ids(search(must(range("fee_amount", 10, 30)))));
    }

    @Test
    void dotEqualsCosineAndEuclidFollowsForUnitVectors() {
        Main.createAndFill(qdrant, Main.COLLECTION + "_dot", "Dot", points);
        Main.createAndFill(qdrant, Main.COLLECTION + "_euclid", "Euclid", points);
        Map<Long, Double> dot = scoresById(qdrant.search(Main.COLLECTION + "_dot", queryVector, 5, null));
        Map<Long, Double> euclid = scoresById(qdrant.search(Main.COLLECTION + "_euclid", queryVector, 5, null));

        for (ScoredPoint c : search(null)) {
            assertEquals(c.score(), dot.get(c.id()), 1e-4, "dot = cosine for id " + c.id());
            assertEquals(Math.sqrt(2 - 2 * c.score()), euclid.get(c.id()), 1e-3, "euclid = sqrt(2-2cos) for id " + c.id());
        }
    }

    @Test
    void wrongVectorSizeIsRejected() {
        Point bad = new Point(99, new float[]{0.1f, 0.2f, 0.3f}, Map.of("topic", "test"));
        assertThrows(IllegalStateException.class, () -> qdrant.upsert(Main.COLLECTION, List.of(bad)));
    }

    private static List<ScoredPoint> search(com.fasterxml.jackson.databind.JsonNode filter) {
        return qdrant.search(Main.COLLECTION, queryVector, 5, filter);
    }

    private static Set<String> topics(List<ScoredPoint> results) {
        return results.stream().map(r -> r.payload().path("topic").asText()).collect(Collectors.toSet());
    }

    private static Set<Long> ids(List<ScoredPoint> results) {
        return results.stream().map(ScoredPoint::id).collect(Collectors.toSet());
    }

    private static Map<Long, Double> scoresById(List<ScoredPoint> results) {
        return results.stream().collect(Collectors.toMap(ScoredPoint::id, ScoredPoint::score));
    }

    private static void assertTopics(List<ScoredPoint> results, Set<String> expected) {
        assertFalse(results.isEmpty());
        assertTrue(expected.containsAll(topics(results)), "got topics " + topics(results));
    }

    private static void assertScoresDescending(List<ScoredPoint> results) {
        for (int i = 1; i < results.size(); i++) {
            assertTrue(results.get(i - 1).score() >= results.get(i).score());
        }
    }
}
