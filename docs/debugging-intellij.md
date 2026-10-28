# Debugging in IntelliJ

This walks through one debug run of the demo: which methods are called, in what order, and what the **Variables** pane shows at each breakpoint. The values are from a real run against Qdrant 1.20.0. Scores can change slightly if you change the model or the text.

## Setup

1. **File → Open** and select `pom.xml`, then choose *Open as Project*. IntelliJ imports it as a Maven project.
2. **File → Project Structure → SDK**: pick JDK 17 or later.
3. Make sure Qdrant is running (`docker start qdrant`), see [testing.md](testing.md).
4. Open `Main.java`, click the green arrow next to `public static void main`, and choose **Debug 'Main.main()'**.

Shortcuts (macOS keymap):

| Action | Key | Use it for |
|---|---|---|
| Step Over | F8 | run the current line |
| Step Into | F7 | go inside the method on this line |
| Smart Step Into | ⇧F7 | choose *which* call to enter when a line has several (e.g. line 45) |
| Step Out | ⇧F8 | finish the current method and return to the caller |
| Resume | ⌘⌥R | run to the next breakpoint |
| Evaluate Expression | ⌥F8 | run any Java expression against the paused state |

## Where to put breakpoints

| # | Location | Why |
|---|---|---|
| 1 | `Main.java:37` | start: `url` is resolved |
| 2 | `Main.java:45` (choose **λ** when asked) | inside the lambda: one stop per policy |
| 3 | `Main.java:54` | before Qdrant is set up |
| 4 | `Main.java:60` | the query vector is created |
| 5 | `QdrantRestClient.java:112` | every search: the request body, including the filter |
| 6 | `QdrantRestClient.java:118` | every search result as it is read |
| 7 | `QdrantRestClient.java:146` | every HTTP call to Qdrant: method, path, response |

Breakpoint 7 stops on every HTTP call (25 per run). Add it when you want to see the traffic, then disable it (right-click → uncheck *Enabled*).

## The call flow

```
Main.main()
├── new QdrantRestClient(url)
├── qdrant.version()                         → send(GET /)
├── new AllMiniLmL6V2EmbeddingModel()        (loads the ONNX model, ~1–2 s)
├── PolicySnippets.ALL.stream().map(...)     12 times:
│     ├── embed(model, s.text())             → float[384]
│     └── payload(s)                         → {topic, text, fee_amount?}
├── createAndFill(qdrant, "banking_policies", "Cosine", points)
│     ├── deleteCollectionIfExists()         → send(DELETE /collections/banking_policies)
│     ├── createCollection()                 → send(PUT /collections/banking_policies)
│     ├── createPayloadIndex("topic")        → send(PUT …/index?wait=true)
│     ├── createPayloadIndex("fee_amount")   → send(PUT …/index?wait=true)
│     └── upsert()                           → send(PUT …/points?wait=true)
├── embed(model, question)                   → queryVector
├── filterExamples()                         6 × search() → send(POST …/points/query), then print()
└── distanceMetricExamples()
      ├── createAndFill(… "_dot", "Dot")     5 HTTP calls
      ├── createAndFill(… "_euclid", "Euclid") 5 HTTP calls
      └── 3 × search() → scoresById() → table
```

## Step by step

### 1. Client creation — `Main.java:36–37`

| Variable | Value |
|---|---|
| `url` | `"http://localhost:6333"` (or `$QDRANT_URL` if set) |
| `qdrant` | `QdrantRestClient` with `baseUrl = "http://localhost:6333"`, an `HttpClient` and an `ObjectMapper` |

### 2. Health check — `Main.java:40` → `version()` → `send()`

Step Into (F7) `qdrant.version()` and then `send(...)`. At `QdrantRestClient.java:146`, step over and look at:

| Variable | Value |
|---|---|
| `method`, `path`, `body` | `"GET"`, `"/"`, `null` (so `publisher` is `noBody()`) |
| `request` | `GET http://localhost:6333/` |
| `response.statusCode()` | `200` |
| `response.body()` | `{"title":"qdrant - vector search engine","version":"1.20.0","commit":"222b2e44…"}` |

`version()` returns `"1.20.0"`. The console prints `Connected to Qdrant 1.20.0 at http://localhost:6333`.

If Qdrant isn't running, `http.send` throws `ConnectException`, and you land in the `catch (IOException e)` block, which rethrows it as `IllegalStateException: Cannot reach Qdrant at http://localhost:6333 …`.

### 3. Model — `Main.java:43`

Step over. It takes a second or two because it loads the ONNX model from the jar. `model` is an `AllMiniLmL6V2EmbeddingModel`. There's nothing useful to inspect inside it.

### 4. Embedding the policies — `Main.java:45` (lambda breakpoint)

It stops 12 times, once per policy. On the first stop:

| Variable | Value |
|---|---|
| `s` | `Snippet[id=1, topic=overdraft, text=An overdraft fee of $35 is charged when…, feeAmount=35.0]` |

Use **Smart Step Into** (⇧F7) and pick `embed` to go inside it (`Main.java:175`):

| Expression | Value |
|---|---|
| `text` | `"An overdraft fee of $35 is charged when a transaction exceeds the available balance."` |
| `model.embed(text).content().vector()` | `float[384]` ≈ `[0.0103, 0.0392, -0.0109, -0.0198, …]` |

Then step into `payload` (`Main.java:166`). On return, `payload` is a `HashMap`:

```
{topic=overdraft, text=An overdraft fee of $35 …, fee_amount=35.0}
```

Resume (⌘⌥R) to the next stop: `s.id() == 2`. That policy has `feeAmount=null`, so its payload has **no** `fee_amount` key. This is why filter F never matches it.

Tip: to stop only on one policy, right-click the breakpoint and set *Condition* to `s.id() == 12`.

After line 46, `points` is a `List` of 12 `Point`s. Expand one to see `id`, `vector` (float[384]) and `payload`.

### 5. Setting up Qdrant — `Main.java:54` → `createAndFill()` (`Main.java:153`)

Step into it. Each line makes one HTTP call. With breakpoint 7 enabled you see:

| Line | `method` | `path` | `body` (as JSON) | `response.body()` |
|---|---|---|---|---|
| 154 delete | `DELETE` | `/collections/banking_policies` | `null` | `{"result":true,…}` (`false` on the very first run: nothing to delete) |
| 155 create | `PUT` | `/collections/banking_policies` | `{"vectors":{"size":384,"distance":"Cosine"}}` | `{"result":true,"status":"ok",…}` |
| 160 index | `PUT` | `/collections/banking_policies/index?wait=true` | `{"field_name":"topic","field_schema":"keyword"}` | `{"result":{"operation_id":…,"status":"completed"},…}` |
| 162 index | `PUT` | same | `{"field_name":"fee_amount","field_schema":"float"}` | same |
| 163 upsert | `PUT` | `/collections/banking_policies/points?wait=true` | `{"points":[{"id":1,"vector":[…384 floats…],"payload":{…}}, …12]}` | `{"result":{"operation_id":…,"status":"completed"},…}` |

`body` is a Jackson `ObjectNode`. To see it as text, use Evaluate Expression (⌥F8) with `json.writeValueAsString(body)`.

Don't expand the upsert body in the Variables pane: it holds 12 × 384 numbers. Evaluate `body.get("points").size()` (→ `12`) or `body.get("points").get(0).get("payload")` instead.

`?wait=true` makes Qdrant reply only once the change is applied, so the searches that follow already see the data.

### 6. Query vector — `Main.java:60`

| Variable | Value |
|---|---|
| `question` | `"What fees will I be charged if I spend more than I have?"` |
| `queryVector` | `float[384]` |

Evaluate `norm(queryVector)` → `1.0` (≈). The model returns vectors of length 1, which is why Dot = Cosine in step 8.

### 7. Searches A–F — `filterExamples()` → `search()`

Each `print(...)` line first calls `qdrant.search(...)`. At breakpoint 5 (`QdrantRestClient.java:112`), evaluate `body.get("filter")` to see the filter Qdrant receives. The whole request is:

```json
{"query":[…384 floats…], "limit":5, "with_payload":true, "filter":{…}}
```

The raw response (`response.body()` in `send`) looks like this:

```json
{"result":{"points":[
   {"id":3,"version":5,"score":0.6376,"payload":{"fee_amount":35.0,"topic":"overdraft","text":"No more than three $35 …"}},
   …]},
 "status":"ok","time":0.0049}
```

`search()` reads `result.points` (line 112–113). Then the loop at line 118 turns each element `p` into a `ScoredPoint(id, score, payload)`; Qdrant's `version` field is ignored. At breakpoint 6, `p` is one element of that array.

What each call sends and what `results` holds when `search()` returns:

| Line | Search | `filter` | `results` (id → score) |
|---|---|---|---|
| 69 | A | `null` (no `filter` key sent) | 3 → 0.6376, 1 → 0.5754, 6 → 0.5041, 5 → 0.4599, 4 → 0.3607 |
| 71 | B | `{"must":[{"key":"topic","match":{"value":"overdraft"}}]}` | 3 → 0.6376, 1 → 0.5754, 4 → 0.3607, 2 → 0.3568 |
| 73 | C | `{"must":[{"key":"topic","match":{"value":"loans"}}]}` | 10 → 0.3270, 9 → 0.1031 |
| 75 | D | `{"should":[{…"fraud"…},{…"transfers"…}]}` | 12 → 0.2974, 7 → 0.2461, 8 → 0.1583 |
| 77 | E | `{"must_not":[{…"overdraft"…}]}` | 6 → 0.5041, 5 → 0.4599, 10 → 0.3270, 12 → 0.2974, 7 → 0.2461 |
| 79 | F | `{"must":[{"key":"fee_amount","range":{"gte":10.0,"lte":30.0}}]}` | 5 → 0.4599, 12 → 0.2974 |

Things to notice in the debugger:
- **The same point has the same score in every search.** Point 6 is 0.5041 in A and E. The filter only decides *which* points can be returned, it doesn't change the scores.
- **B, C, D and F return fewer than `limit` (5) results**, because fewer points pass the filter. `results.size()` is 4, 2, 3 and 2.

### 8. Distance metrics — `distanceMetricExamples()` (`Main.java:104`)

Lines 107–108 call `createAndFill` twice. That's the same 5 HTTP calls as step 5, but with `"distance":"Dot"` and `"distance":"Euclid"` in the create body.

After line 118:

| Variable | Value |
|---|---|
| `cosine` | `List<ScoredPoint>`: 3 → 0.6376, 1 → 0.5754, 6 → 0.5041, 5 → 0.4599, 4 → 0.3607 |
| `dot` | `HashMap {3=0.6376, 1=0.5754, 6=0.5041, 5=0.4599, 4=0.3607}`: same as cosine |
| `euclid` | `HashMap {3=0.8514, 1=0.9215, 6=0.9959, 5=1.0393, 4=1.1308}`: lower = closer |

Check the formula in Evaluate Expression: `Math.sqrt(2 - 2 * cosine.get(0).score())` → `0.8514`, the same as `euclid.get(3L)`.

## Debugging the tests

Open `RetrievalTest`, click the green arrow next to the class (or a single test) and choose **Debug**. Docker must be running; you don't need to start Qdrant yourself.

- **`@BeforeAll indexPolicies()` runs once** before the tests. It does steps 3–5 above, against the Testcontainers Qdrant.
- **`qdrant.baseUrl` is not port 6333.** It's a random port, e.g. `http://localhost:55012`. Evaluate `QDRANT.getMappedPort(6333)` to get it.
- **While paused, the container is still running**, so you can open `http://localhost:<that port>/dashboard` in a browser and look at the data the test is using. `docker ps` also lists it.
- To see why a test fails, put a breakpoint on its `assert…` line and inspect `results`. For example, `topics(results)` in Evaluate Expression shows which topics came back.

## Catching errors

Every Qdrant or connection error goes through `send()` and becomes an `IllegalStateException`. To stop exactly where it happens:

**Run → View Breakpoints (⌘⇧F8) → + → Java Exception Breakpoints → `IllegalStateException`**

| Situation | Where it stops | Message |
|---|---|---|
| Qdrant not running | `QdrantRestClient.java:158` | `Cannot reach Qdrant at http://localhost:6333 (java.net.ConnectException) …` |
| Wrong vector size | `QdrantRestClient.java:150` | `PUT …/points?wait=true -> HTTP 400: {"status":{"error":"Wrong input: Vector dimension error: expected dim: 384, got 3"}}` |
| Filter on a field name that doesn't exist | no error | the search returns an empty list: no point has the field, so none match |
