package com.example.bankingrag;

import com.example.bankingrag.PolicySnippets.Snippet;
import com.example.bankingrag.QdrantRestClient.Point;
import com.example.bankingrag.QdrantRestClient.ScoredPoint;
import dev.langchain4j.model.embedding.EmbeddingModel;
import dev.langchain4j.model.embedding.onnx.allminilml6v2.AllMiniLmL6V2EmbeddingModel;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static com.example.bankingrag.Filters.match;
import static com.example.bankingrag.Filters.must;
import static com.example.bankingrag.Filters.mustNot;
import static com.example.bankingrag.Filters.range;
import static com.example.bankingrag.Filters.should;

/**
 * Minimal RAG retrieval demo:
 *   1. Qdrant runs locally (docker run -p 6333:6333 qdrant/qdrant)
 *   2. create a 384-dim collection
 *   3. embed banking policy sentences with all-MiniLM-L6-v2 (384 dims)
 *   4. upsert them with a {"topic": ...} payload
 *   5. similarity search with a query vector, with and without payload filters (must / should / must_not / range)
 *   6. print the top-k results and why they came back
 * Then the same query is run against Cosine, Dot and Euclid collections to compare distance metrics.
 */
public final class Main {

    static final String COLLECTION = "banking_policies";
    static final int VECTOR_SIZE = 384;
    private static final int TOP_K = 5;

    public static void main(String[] args) {
        String url = System.getenv().getOrDefault("QDRANT_URL", "http://localhost:6333");
        QdrantRestClient qdrant = new QdrantRestClient(url);

        // 1. Qdrant is reachable
        System.out.println("Connected to Qdrant " + qdrant.version() + " at " + url);

        // 3. Generate embeddings locally — the model is bundled in the jar, no API key needed.
        EmbeddingModel model = new AllMiniLmL6V2EmbeddingModel();
        List<Point> points = PolicySnippets.ALL.stream()
                .map(s -> new Point(s.id(), embed(model, s.text()), payload(s)))
                .toList();

        // Steps 2 and 4 from the list at the top of this class: create the collection, then store
        // the 12 points in it. (Step 3, embedding, ran just above: points need their vectors first.)
        // - The collection only accepts vectors of VECTOR_SIZE (384) numbers, the size the model outputs.
        // - "Cosine" is how Qdrant measures similarity: the score is the cosine of the angle between the
        //   question's vector and a policy's vector (1.0 = same meaning, 0 = unrelated).
        // - createAndFill() also indexes "topic" and "fee_amount" so filters on them stay fast.
        createAndFill(qdrant, COLLECTION, "Cosine", points);
        System.out.println("Created '" + COLLECTION + "' (size=" + VECTOR_SIZE + ", distance=Cosine) and upserted "
                + points.size() + " points with payload {topic, text, fee_amount}\n");

        // 5 + 6. Search and inspect
        String question = "What fees will I be charged if I spend more than I have?";
        float[] queryVector = embed(model, question);
        System.out.println("Query: \"" + question + "\"");

        filterExamples(qdrant, queryVector);
        distanceMetricExamples(qdrant, points, queryVector);
    }

    private static void filterExamples(QdrantRestClient qdrant, float[] queryVector) {
        print("A) No filter — pure semantic similarity across all topics",
                qdrant.search(COLLECTION, queryVector, TOP_K, null));
        print("B) must: topic = overdraft — similarity ranked only inside that topic",
                qdrant.search(COLLECTION, queryVector, TOP_K, must(match("topic", "overdraft"))));
        print("C) must: topic = loans — the filter is a hard constraint, even when nothing matches well",
                qdrant.search(COLLECTION, queryVector, TOP_K, must(match("topic", "loans"))));
        print("D) should: topic = fraud OR topic = transfers — at least one condition must hold",
                qdrant.search(COLLECTION, queryVector, TOP_K, should(match("topic", "fraud"), match("topic", "transfers"))));
        print("E) must_not: topic = overdraft — the closest results once the obvious topic is excluded",
                qdrant.search(COLLECTION, queryVector, TOP_K, mustNot(match("topic", "overdraft"))));
        print("F) range: 10 <= fee_amount <= 30 — numeric condition; points without fee_amount never match",
                qdrant.search(COLLECTION, queryVector, TOP_K, must(range("fee_amount", 10, 30))));

        System.out.println("""

                Why these results:
                  * Scores are cosine similarity between the query embedding and each snippet's embedding
                    (1.0 = same direction). The query never says "overdraft", yet overdraft snippets rank
                    high because the model maps "spend more than I have" close to "exceeds the balance".
                  * In A) snippets from the "fees" topic (ATM, maintenance) also appear: they share the
                    "fees / charged" meaning. Similarity alone can't tell which policy the user means.
                  * B) applies the payload filter, then ranks by score, so those near-misses disappear.
                    Metadata filters are how RAG keeps retrieved context on-topic.
                  * C) still returns the nearest points that pass the filter, just with low scores.
                    Qdrant never says "nothing relevant" on its own — the application decides that,
                    e.g. with a score threshold.
                  * D) should = OR, E) must_not = NOT, F) range on a number. Clauses can be combined in one
                    filter, e.g. {"must": [...], "must_not": [...]} = AND + NOT. Every filtered field
                    (topic, fee_amount) has a payload index so Qdrant doesn't scan all points.
                """);
    }

    /**
     * Output G: runs the same question against the same points in three collections that differ only
     * in their distance metric (Cosine, Dot, Euclid), and prints the scores side by side.
     */
    private static void distanceMetricExamples(QdrantRestClient qdrant, List<Point> points, float[] queryVector) {
        // The Cosine collection was already created in main(). Create two more with the same 12 points,
        // one compared with Dot and one with Euclid. createAndFill() deletes them first, so each run starts clean.
        createAndFill(qdrant, COLLECTION + "_dot", "Dot", points);
        createAndFill(qdrant, COLLECTION + "_euclid", "Euclid", points);

        // Run the same search, with no filter (null), in each of the three collections.
        // Each table row is one policy (one of the 12 sentences = one point, identified by its id).
        // - Cosine results stay a list: their order (best first) decides the order of the table rows.
        // - Dot and Euclid results become id -> score maps (see scoresById), so that for each policy
        //   in the Cosine list we can find its Dot and Euclid scores by id.
        // Explained with an example in docs/output-explained.md, "How the table in G is built".
        List<ScoredPoint> cosine = qdrant.search(COLLECTION, queryVector, TOP_K, null);
        Map<Long, Double> dot = scoresById(qdrant.search(COLLECTION + "_dot", queryVector, TOP_K, null));
        Map<Long, Double> euclid = scoresById(qdrant.search(COLLECTION + "_euclid", queryVector, TOP_K, null));

        // Title and column headers. norm(queryVector) is the query vector's length: it prints 1.0000
        // because the model always outputs vectors of length 1, which is why Dot equals Cosine.
        System.out.printf("%nG) Same query, same points, three distance metrics (query vector length = %.4f)%n", norm(queryVector));
        System.out.println("     id  topic       Cosine (higher=closer)  Dot (higher=closer)  Euclid (lower=closer)  sqrt(2 - 2*cos)");
        // The last column is a check, not data from Qdrant: it calculates Euclid from the Cosine score with
        // sqrt(2 - 2*cos), which holds for length-1 vectors, to show it matches Qdrant's Euclid column.
        // dot.get / euclid.get find a score for every row because all three searches return the same
        // 5 policies: with length-1 vectors the three metrics rank identically. If they didn't, a policy
        // missing from the Dot or Euclid top 5 would print as "null".
        // Explained in docs/output-explained.md, "Why all three searches return the same 5 policies"
        // and "Why the last column is there".
        for (ScoredPoint c : cosine) {
            System.out.printf("     %-3d %-11s %22.4f %20.4f %22.4f %16.4f%n",
                    c.id(), c.payload().path("topic").asText(), c.score(),
                    dot.get(c.id()), euclid.get(c.id()), Math.sqrt(2 - 2 * c.score()));
        }

        // Explanation printed under the table. """ ... """ is a Java text block: a string that keeps
        // its line breaks, so it prints exactly as laid out here.
        System.out.println("""

                Why these results:
                  * all-MiniLM-L6-v2 outputs normalized vectors (length 1.0), so Dot = Cosine exactly:
                    cosine is just the dot product divided by both lengths, and those lengths are 1.
                  * Euclid is a distance, so lower is better, but the ranking is the same. For unit vectors
                    euclid = sqrt(2 - 2*cos), the last column, which matches Qdrant's Euclid score.
                  * The metrics only rank differently when vector length varies (vectors that aren't
                    normalized). That's why the metric must match what the embedding model was trained for.
                """);
    }

    static void createAndFill(QdrantRestClient qdrant, String collection, String distance, List<Point> points) {
        qdrant.deleteCollectionIfExists(collection);
        qdrant.createCollection(collection, VECTOR_SIZE, distance);
        // Payload indexes, like an index on a database column: they let Qdrant find the points that
        // pass a filter without reading every point's payload. Filters work without them, just slower
        // on large collections (with 12 points there's no visible difference).
        // "keyword" = exact text matches, used by the topic filters (searches B–E).
        qdrant.createPayloadIndex(collection, "topic", "keyword");
        // "float" = numbers, which allows range conditions such as 10 <= fee_amount <= 30 (search F).
        qdrant.createPayloadIndex(collection, "fee_amount", "float");
        qdrant.upsert(collection, points);
    }

    static Map<String, Object> payload(Snippet s) {
        Map<String, Object> payload = new HashMap<>();
        payload.put("topic", s.topic());
        payload.put("text", s.text());
        if (s.feeAmount() != null) payload.put("fee_amount", s.feeAmount());
        return payload;
    }

    static float[] embed(EmbeddingModel model, String text) {
        return model.embed(text).content().vector();
    }

    private static double norm(float[] v) {
        double sum = 0;
        for (float x : v) sum += x * x;
        return Math.sqrt(sum);
    }

    /**
     * Turns search results into a map from point id to score, e.g. {3=0.6376, 1=0.5754, ...},
     * so a policy's score can be found by its id. Used for the Dot and Euclid columns of output G.
     * See docs/output-explained.md, "How the table in G is built".
     */
    private static Map<Long, Double> scoresById(List<ScoredPoint> results) {
        // Empty map to fill. A HashMap has no order; the table's order comes from the Cosine list instead.
        Map<Long, Double> scores = new HashMap<>();
        // One entry per result: key = the policy's id, value = the score Qdrant gave it.
        for (ScoredPoint r : results) scores.put(r.id(), r.score());
        return scores;
    }

    private static void print(String title, List<ScoredPoint> results) {
        System.out.println("\n" + title);
        int rank = 1;
        for (ScoredPoint r : results) {
            String fee = r.payload().has("fee_amount") ? "$" + r.payload().path("fee_amount").asInt() : "-";
            System.out.printf("  #%d  score=%.4f  id=%-3d topic=%-10s fee=%-4s %s%n",
                    rank++, r.score(), r.id(), r.payload().path("topic").asText(), fee, r.payload().path("text").asText());
        }
    }

    private Main() {}
}
