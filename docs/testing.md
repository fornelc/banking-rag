# Testing the project

There are two ways to test it: run the automated tests, or run the demo and check its output by hand.

## Prerequisites

- **Java 17+** and **Maven**
- **Docker Desktop** running (`open -a Docker` on macOS). Both the tests and the demo need it.

## Option 1: Automated tests

```bash
cd ~/Projects/banking-rag
mvn test
```

You don't need to start Qdrant yourself. [Testcontainers](https://testcontainers.com) starts a temporary `qdrant/qdrant:v1.20.0` container on a random port, runs the tests against it and removes it afterwards. Your own `qdrant` container on port 6333 is never touched.

Expected result:

```
Tests run: 9, Failures: 0, Errors: 0, Skipped: 0 -- in com.example.bankingrag.RetrievalTest
Tests run: 4, Failures: 0, Errors: 0, Skipped: 0 -- in com.example.bankingrag.FiltersTest
Tests run: 13, Failures: 0, Errors: 0, Skipped: 0
BUILD SUCCESS
```

The first run takes longer because Maven downloads the dependencies and Docker pulls the Qdrant image.

### What the tests check

**`FiltersTest`**: unit tests, no Docker. They check that `Filters` builds the JSON Qdrant expects.

| Test | Checks |
|---|---|
| `mustMatchBuildsKeywordCondition` | `{"must":[{"key":"topic","match":{"value":"overdraft"}}]}` |
| `shouldHoldsEveryCondition` | `should` holds every condition passed |
| `mustNotUsesSnakeCaseKey` | the key is `must_not` (Qdrant's spelling), not `mustNot` |
| `rangeHasInclusiveBounds` | `{"range":{"gte":10.0,"lte":30.0}}` |

**`RetrievalTest`**: end-to-end against a real Qdrant. `@BeforeAll` embeds the 12 policies, creates the `banking_policies` collection and upserts them, like the demo does. Each test then asserts what the demo's output shows.

| Test | Demo output | Checks |
|---|---|---|
| `embeddingsHave384Dimensions` | — | every vector has 384 floats |
| `unfilteredSearchFindsOverdraftByMeaning` | A | the top 2 are the $35 overdraft policies (ids 1 and 3), and scores are in descending order |
| `mustKeepsOnlyThatTopic` | B | only `overdraft` results |
| `mustReturnsLowScoresRatherThanNothing` | C | both `loans` points come back even though the score is < 0.5 |
| `shouldIsOr` | D | only `fraud` or `transfers` results |
| `mustNotExcludesTopic` | E | 5 results, none `overdraft` |
| `rangeMatchesOnlyFeesInBounds` | F | exactly ids 5 ($12) and 12 ($25) |
| `dotEqualsCosineAndEuclidFollowsForUnitVectors` | G | Dot score = Cosine score, Euclid = `sqrt(2 - 2*cos)` |
| `wrongVectorSizeIsRejected` | — | upserting a 3-dim vector into a 384-dim collection throws (`HTTP 400 … Vector dimension error: expected dim: 384, got 3`) |

Run a single class or test:

```bash
mvn test -Dtest=FiltersTest                                    # no Docker needed
mvn test -Dtest=RetrievalTest#rangeMatchesOnlyFeesInBounds
```

## Option 2: Run the demo and check the output

### 1. Start Qdrant

If the `qdrant` container already exists (`docker ps -a` lists it), start it again:

```bash
docker start qdrant
```

On a machine that has never had it, use the command from the exercise instead:

```bash
docker run -d --name qdrant -p 6333:6333 qdrant/qdrant
```

Don't run both: the second would fail because port 6333 is already in use.

To check it's up, open http://localhost:6333. It should return JSON with the Qdrant version.

### 2. Run the demo

```bash
cd ~/Projects/banking-rag
mvn compile exec:java
```

### 3. Check the output

- It starts with `Connected to Qdrant 1.20.0 …` and `Created 'banking_policies' … upserted 12 points`.
- **A:** the top results are the two $35 overdraft policies.
- **B:** only overdraft results.
- **C:** only loans results.
- **D:** only fraud and transfers results.
- **E:** no overdraft results.
- **F:** only the $12 and $25 policies.
- **G:** the Cosine and Dot columns are identical, and the Euclid column equals the last column.

For why each result appears where it does, see [output-explained.md](output-explained.md).

### 4. Look at the data in Qdrant

Open http://localhost:6333/dashboard. You'll see three collections (`banking_policies`, `banking_policies_dot`, `banking_policies_euclid`) and can browse the points and their payloads.

### 5. Experiment

Edit `Main.java` and run step 2 again. Each run deletes and recreates the collections, so you can repeat it as often as you like.

- Change `question` in `main()`, for example to "Someone stole my card" or "How do I send money abroad?", and see which topics come up.
- Change `TOP_K`, or the filters in `filterExamples()`.
- Add a sentence to `PolicySnippets.java` and check where it ranks.

If you change the policies or the question, some `RetrievalTest` assertions may need updating, since they check the current results.

### 6. Stop

```bash
docker stop qdrant
```
