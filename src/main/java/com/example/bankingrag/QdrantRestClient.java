package com.example.bankingrag;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Thin client over Qdrant's REST API (port 6333), using only java.net.http + Jackson.
 * Each method maps 1:1 to an endpoint so the HTTP calls stay easy to read and debug.
 */
public final class QdrantRestClient {

    public record Point(long id, float[] vector, Map<String, Object> payload) {}

    public record ScoredPoint(long id, double score, JsonNode payload) {}

    private final String baseUrl;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    public QdrantRestClient(String baseUrl) {
        this.baseUrl = baseUrl.endsWith("/") ? baseUrl.substring(0, baseUrl.length() - 1) : baseUrl;
    }

    /** GET / — returns the server version, used as a health check. */
    public String version() {
        return send("GET", "/", null).path("version").asText();
    }

    /**
     * DELETE /collections/{name} — makes the demo re-runnable from a clean state.
     * A missing collection is not an error: Qdrant answers 200 with {"result": false}.
     */
    public void deleteCollectionIfExists(String collection) {
        send("DELETE", "/collections/" + collection, null);
    }

    /** PUT /collections/{name} — fixed vector size and the distance metric used for scoring. */
    public void createCollection(String collection, int vectorSize, String distance) {
        ObjectNode body = json.createObjectNode();
        body.putObject("vectors").put("size", vectorSize).put("distance", distance);
        send("PUT", "/collections/" + collection, body);
    }

    /**
     * PUT /collections/{name}/index — indexes a payload field so filtering on it doesn't need a full scan.
     * Schema is e.g. "keyword" for exact-match strings or "float" for numeric ranges.
     */
    public void createPayloadIndex(String collection, String field, String schema) {
        ObjectNode body = json.createObjectNode().put("field_name", field).put("field_schema", schema);
        send("PUT", "/collections/" + collection + "/index?wait=true", body);
    }

    /** PUT /collections/{name}/points — insert or overwrite points by id. */
    public void upsert(String collection, List<Point> points) {
        // Builds the JSON body Qdrant expects, piece by piece:
        // {"points": [
        //   {"id": 1, "vector": [0.0103, 0.0392, ...384 numbers...], "payload": {"topic": "overdraft", ...}},
        //   ...
        // ]}
        // the outer { }
        ObjectNode body = json.createObjectNode();
        // "points": [ ] inside it
        ArrayNode arr = body.putArray("points");
        for (Point p : points) {
            // for each point, a new { } in the array, with "id": 1
            ObjectNode node = arr.addObject().put("id", p.id());
            // "vector": [ ... ], adding the 384 numbers one by one
            ArrayNode vec = node.putArray("vector");
            for (float v : p.vector()) vec.add(v);
            // "payload": {...}, converting the Java Map into a JSON object
            node.set("payload", json.valueToTree(p.payload()));
        }
        send("PUT", "/collections/" + collection + "/points?wait=true", body);
    }

    /**
     * POST /collections/{name}/points/query — nearest neighbours to {@code vector},
     * restricted to points matching {@code filter} (null = no filter, see {@link Filters}).
     */
    public List<ScoredPoint> search(String collection, float[] vector, int limit, JsonNode filter) {
        // Builds the JSON request body Qdrant expects, piece by piece:
        // {"query": [...384 numbers...], "limit": 5, "with_payload": true, "filter": {...}}
        // the outer { }
        ObjectNode body = json.createObjectNode();
        // "query": [ ... ], the question's vector, adding the 384 numbers one by one
        ArrayNode q = body.putArray("query");
        for (float v : vector) q.add(v);
        // "limit": 5 = how many results to return (top-k), and "with_payload": true = also return
        // each point's payload (topic, text, fee_amount), not just its id and score
        body.put("limit", limit).put("with_payload", true);
        // "filter": {...}, only when there is one (null = search all points)
        if (filter != null) body.set("filter", filter);

        // Sends the request. Qdrant answers with the results, best first:
        // {"result": {"points": [
        //   {"id": 3, "version": 5, "score": 0.6376, "payload": {"topic": "overdraft", ...}},
        //   ...
        // ]}, "status": "ok", "time": 0.0049}
        // .path("result").path("points") goes down to that list of points
        JsonNode points = send("POST", "/collections/" + collection + "/points/query", body)
                .path("result").path("points");
        // Converts each JSON point into a Java ScoredPoint(id, score, payload), keeping Qdrant's order.
        // Qdrant's "version" field isn't needed, so it's ignored.
        List<ScoredPoint> results = new ArrayList<>();
        for (JsonNode p : points) {
            results.add(new ScoredPoint(p.path("id").asLong(), p.path("score").asDouble(), p.path("payload")));
        }
        return results;
    }

    /**
     * Makes one HTTP call to Qdrant and returns the JSON answer. Every public method goes through here,
     * e.g. send("PUT", "/collections/banking_policies", {"vectors": {...}}).
     * Any failure (Qdrant unreachable, or Qdrant rejecting the request) becomes an IllegalStateException.
     */
    private JsonNode send(String method, String path, JsonNode body) {
        try {
            // The request body. GET and DELETE send none (body == null); the others turn the Jackson
            // JsonNode into a JSON string, e.g. {"vectors":{"size":384,"distance":"Cosine"}}
            HttpRequest.BodyPublisher publisher = body == null
                    ? HttpRequest.BodyPublishers.noBody()
                    : HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body));
            // The request itself:
            // - the full URL: baseUrl + path, e.g. http://localhost:6333/collections/banking_policies
            // - give up if Qdrant hasn't answered after 30 seconds
            // - tell Qdrant the body is JSON
            // - the HTTP method (GET, PUT, POST or DELETE) and the body
            HttpRequest request = HttpRequest.newBuilder(URI.create(baseUrl + path))
                    .timeout(Duration.ofSeconds(30))
                    .header("Content-Type", "application/json")
                    .method(method, publisher)
                    .build();
            // Sends it and waits for the answer. BodyHandlers.ofString() = read the answer body as text.
            HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
            // HTTP status 200–299 = success. Anything else means Qdrant rejected the request, e.g.
            // 400 "Vector dimension error: expected dim: 384, got 3". Include Qdrant's message in the error.
            if (response.statusCode() >= 300) {
                throw new IllegalStateException(method + " " + path + " -> HTTP " + response.statusCode() + ": " + response.body());
            }
            // Success: turn the answer text into a JsonNode the caller can navigate,
            // e.g. {"result": {...}, "status": "ok", "time": 0.004}
            return json.readTree(response.body());
        } catch (IOException e) {
            // The request never got an answer: Qdrant isn't running, the URL is wrong, or it timed out.
            // Say so plainly, and suggest how to start Qdrant.
            throw new IllegalStateException("Cannot reach Qdrant at " + baseUrl + " (" + e + "). "
                    + "Is it running? docker run -p 6333:6333 qdrant/qdrant", e);
        } catch (InterruptedException e) {
            // The thread was told to stop while waiting for the answer. Restore that "stop" flag so the
            // code that called us can still see it, then fail this call.
            // Java clears the flag when it throws InterruptedException, so without this line the stop request
            // would be lost: a caller looping over many searches would check
            // Thread.currentThread().isInterrupted(), get false, and keep going.
            // Nothing interrupts the thread in this demo (it runs on a single thread). This matters if the
            // client is reused in a task that can be cancelled, e.g. future.cancel(true) or a web server
            // cancelling a request.
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
