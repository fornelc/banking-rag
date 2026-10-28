# banking-rag

Minimal RAG retrieval demo in Java against a local [Qdrant](https://qdrant.tech).

1. Runs against Qdrant in Docker (REST API on port 6333)
2. Creates a collection with 384-dimensional vectors and cosine distance
3. Embeds 12 banking policy sentences locally with **all-MiniLM-L6-v2**, which outputs 384 dimensions (via `langchain4j-embeddings-all-minilm-l6-v2`, ONNX, no API key)
4. Upserts them as points with payload `{"topic": "...", "text": "...", "fee_amount": 35.0}`
5. Runs a similarity search with no filter and with `must`, `should`, `must_not` and `range` filters
6. Prints the top-k results with scores and explains why they came back
7. Runs the same query against Cosine, Dot and Euclid collections and compares the scores

### What step 3 means

> Embeds 12 banking policy sentences locally with **all-MiniLM-L6-v2**, which outputs 384 dimensions (via `langchain4j-embeddings-all-minilm-l6-v2`, ONNX, no API key)

| Part | Meaning |
|---|---|
| **Embeds** | Turns each sentence into a list of numbers (a vector) that represents its meaning. Sentences with similar meaning get similar numbers, and that's what Qdrant compares |
| **locally** | The model runs on your machine, inside the Java program. No text is sent to an external service, which matters for bank data |
| **all-MiniLM-L6-v2** | The model's name. A small, free, widely used model for sentence embeddings, published on Hugging Face by the sentence-transformers project. *MiniLM* is the model family, *L6* means 6 layers (small and fast), *v2* is the version, and *all* means it was trained on a broad mix of data |
| **outputs 384 dimensions** | Every sentence, short or long, becomes exactly 384 numbers. That's why the collection is created with `size = 384`: Qdrant rejects vectors of any other size. Other models output different sizes (e.g. 768 or 1536) |
| **`langchain4j-embeddings-all-minilm-l6-v2`** | The Maven dependency that provides the model in Java. It's part of [LangChain4j](https://docs.langchain4j.dev). The jar (~83 MB) contains the model file (`all-minilm-l6-v2.onnx`, ~90 MB unpacked) and its tokenizer, plus the `AllMiniLmL6V2EmbeddingModel` class that `Main` uses |
| **ONNX** | Open Neural Network Exchange, a standard file format for trained models. The model was trained in Python, exported to ONNX, and is run here by ONNX Runtime. That's how it works in Java without Python |
| **no API key** | Unlike hosted embedding APIs (e.g. OpenAI), there's no account, no cost per call, and nothing to configure. After Maven downloads the jar once, it also works offline |

In code, it's two lines in `Main.java`:

```java
EmbeddingModel model = new AllMiniLmL6V2EmbeddingModel();      // loads the ONNX model from the jar
float[] vector = model.embed("some text").content().vector();  // → float[384]
```

The same model must be used for the policies and for the questions. A vector from another model would not be comparable (see [Why you can't just type text into Qdrant](#why-you-cant-just-type-text-into-qdrant)).

## Use case

**A bank policy assistant.** Customers or support agents ask questions in their own words, e.g.
*"What happens if I spend more than I have?"*, and get answers that come only from the bank's official policies.

This project is the **retrieval half** of that assistant, the part that finds the right policies:

```
Offline (indexing):  policy documents → split into sentences/paragraphs → embed → upsert into Qdrant
Online (question):   user question → embed → search Qdrant (top-k + filters) → relevant policies
                     → [not built here] put them in an LLM prompt → grounded answer with sources
```

Why this matters for a bank:
- **Users don't use the bank's wording.** "Spend more than I have" never says "overdraft", yet search A finds the overdraft policies. Keyword search (`LIKE '%overdraft%'`) would miss them.
- **Answers must be grounded.** An LLM on its own doesn't know this bank's fees and may invent them. With RAG it only gets the retrieved policy text, and each answer can cite the point it came from.
- **Context must be controlled.** Payload filters restrict what can be retrieved, for example:
  - only the topic the user is asking about (`must topic = overdraft`)
  - exclude content a channel shouldn't see (`must_not`)
  - only products in a price range (`range fee_amount`)
  - in a real system, also by country, product, language or customer segment
- **Policies change often.** Updating a fee means re-embedding and upserting one point. The LLM doesn't need retraining.

Other places the same pattern fits: an internal search for compliance documents, a helper that suggests answers to support agents, or the retriever node of a LangGraph agent (the next exercise).

**Not production-ready as is:** the corpus is 12 made-up sentences (see [Where the data lives](#where-the-data-lives)), there's no LLM step, no score threshold for "I don't know", and the full text lives in Qdrant instead of a system of record (see the study guide's Q2).

## Where the data lives

**The policies are made up.** The 12 sentences (the $35 overdraft fee, the $12 maintenance fee, …) were written for this demo and don't come from any real bank.

| What | Where | In the repo? |
|---|---|---|
| Policy text, topic, fee | Hard-coded in `src/main/java/com/example/bankingrag/PolicySnippets.java` | Yes. This is the source of truth |
| Vectors (384 numbers per policy) | Generated on every run by the embedding model, then sent to Qdrant | No |
| Points in Qdrant (vector + payload) | Inside the Docker container (`banking_policies`, `_dot`, `_euclid`) | No |

What happens to the data in Qdrant:
- **Every run rebuilds it.** The demo deletes the collections and creates them again from `PolicySnippets.java`.
- **`docker stop` / `docker start` keep it.**
- **Deleting the container loses it**, because the container wasn't started with a volume (`-v`). That's fine here: one run of the demo rebuilds everything. To keep Qdrant data in a real setup, mount a volume, e.g. `docker run -p 6333:6333 -v "$(pwd)/qdrant_storage:/qdrant/storage" qdrant/qdrant`.
- **The tests never touch it.** They use their own temporary Qdrant (see [docs/testing.md](docs/testing.md)).

To watch the data being created step by step, see steps 4 (embedding the policies) and 5 (setting up Qdrant) in [docs/debugging-intellij.md](docs/debugging-intellij.md).

### Adding a policy

**In this demo:** add a line to `PolicySnippets.ALL` and run the demo again:

```java
new Snippet(13, "fees", "Paper statements cost $2 per month; electronic statements are free.", 2.0)
```

The `id` must be unique; reusing one overwrites that point. Use `null` as the last value if the policy has no flat fee. Some `RetrievalTest` assertions may need updating if the new policy changes the search results.

**In a real system** policy owners shouldn't edit Java. The "Offline (indexing)" step would be its own process:
1. **Policies live as documents** (PDF, Word, Markdown, or the bank's document system), maintained by the people who own them.
2. **An ingestion job reads them** when they change. It splits them into chunks, embeds each chunk, and upserts it with a payload (topic, source document, date).
3. **The question side doesn't change.** It searches whatever is in Qdrant, so a new policy can be found as soon as it's upserted, with no code change.

### Why you can't just type text into Qdrant

Qdrant stores and compares vectors; it doesn't create them from text. Adding a policy through the dashboard or the API would need its vector, and that vector has to come from **the same embedding model the questions use** (all-MiniLM-L6-v2 here). Vectors from a different model live in a different "space": a search would compare numbers that don't mean the same thing and return nonsense, even if the dimensions happened to match.

## Study guide mapping

How each section of the Qdrant study guide maps to this project:

| Study guide section | Where in the project | What to look at |
|---|---|---|
| **What is Qdrant?** | Qdrant in Docker, `QdrantRestClient.java` | Qdrant is only used for retrieval: store points, search them. Nothing else |
| **Embeddings** | `Main.java` → `embed()`, `AllMiniLmL6V2EmbeddingModel` | Embeddings are created *outside* Qdrant by all-MiniLM-L6-v2 (384 dims), then sent to Qdrant |
| **Point** | `QdrantRestClient.Point` (`id`, `vector`, `payload`), `Main.payload()` | Each policy sentence becomes one point. *Not covered:* UUID ids, multiple named vectors |
| **Collection** | `QdrantRestClient.createCollection()`, `Main.createAndFill()` | Fixed size 384 and a distance metric, both set at creation |
| **Distance metrics** | `Main.distanceMetricExamples()`, output **G** | Same points in Cosine, Dot and Euclid collections. Dot = Cosine because the vectors have length 1. Euclid ranks the same but lower = closer. Explained from the beginning, with small examples, in [docs/output-explained.md](docs/output-explained.md#the-idea-from-the-beginning) |
| **Payload** | `PolicySnippets.java`, `Main.payload()` | `{topic, text, fee_amount}`. `text` is returned to show the answer; `topic` and `fee_amount` are used to filter |
| **Payload filtering** | `Filters.java`, `QdrantRestClient.search()`, outputs **B–F** | `must` (B, C), `should` (D), `must_not` (E), `range` (F) |
| **Payload index** (the "Important" note) | `QdrantRestClient.createPayloadIndex()` | `keyword` index on `topic`, `float` index on `fee_amount` |
| **ANN search and HNSW** | *Not covered* | Qdrant uses HNSW internally, but with 12 points it does a full scan. Showing `hnsw_ef` would need thousands of points |
| **The RAG pattern** | Whole project, partly | Indexing + retrieval are built. The LLM generation step is not |
| **Article 1: Local Quickstart** | `Main.main()` | Same sequence: create collection → upsert → query → filter. Uses real embeddings instead of 4-dim hand-made vectors, and Cosine instead of Dot |
| **Article 2: What is Qdrant?** | Collections, metrics, points, client | Conceptual; the components it lists are all used here |
| **Article 3: Vector search** | Output **A** | Meaning over keywords: "spend more than I have" finds overdraft policies. HNSW part not covered |
| **Q1: Meaning, not exact text** | Output **A** | Top results share almost no words with the query |
| **Q2: Qdrant alongside a relational DB** | *Not covered* | The text is stored in the payload; the guide's pattern would store only an ID and fetch the text from Postgres |
| **Q3: What RAG solves** | *Partly*, see [Use case](#use-case) | Only relevant policies are retrieved (context limit), and policies can be updated without touching a model (knowledge cutoff). No LLM step |

## Run

```bash
docker run -p 6333:6333 qdrant/qdrant      # separate terminal
mvn compile exec:java
```

Needs Java 17+ and Maven. Set `QDRANT_URL` to use a different Qdrant URL than `http://localhost:6333`.
You can browse the collection in the Qdrant dashboard at http://localhost:6333/dashboard.

## Tests and debugging

```bash
mvn test      # 13 tests; Testcontainers starts its own Qdrant, so only Docker needs to be running
```

- [docs/output-explained.md](docs/output-explained.md): what the demo prints, line by line, and why each result appears
- [docs/testing.md](docs/testing.md): what each test checks, and how to run and check the demo by hand
- [docs/debugging-intellij.md](docs/debugging-intellij.md): a step-by-step IntelliJ debug run, with the values each breakpoint shows

## Layout

| File | Purpose |
|---|---|
| `QdrantRestClient.java` | Small REST client (`java.net.http` + Jackson). Each method maps to one Qdrant endpoint |
| `Filters.java` | Builders for Qdrant filter JSON (`match`, `range`, `must`, `should`, `must_not`) |
| `PolicySnippets.java` | The corpus: 12 made-up policy sentences tagged by topic |
| `Main.java` | Steps 1–7 |
| `FiltersTest.java` | Unit tests for the filter JSON (no Docker) |
| `RetrievalTest.java` | End-to-end tests against a Testcontainers Qdrant, one per demo search |

Note: `pom.xml` pins DJL tokenizers to 0.29.0, the last version that ships native libraries for Intel Macs.
