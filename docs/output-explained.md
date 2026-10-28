# The demo output, explained

What `mvn compile exec:java` prints and why each result appears where it does. The numbers are from a real run against Qdrant 1.20.0. If you change the question or the policies in `PolicySnippets.java`, the results will change.

## What "policy" means here

A **policy** is one bank rule, written as one sentence. The project has 12 of them, all made up for this demo, for example:

> An overdraft fee of $35 is charged when a transaction exceeds the available balance.

The same policy appears under different names depending on where you look:

| Where | Name | Example |
|---|---|---|
| `PolicySnippets.java` | a `Snippet` | `new Snippet(1, "overdraft", "An overdraft fee of $35 …", 35.0)` |
| Qdrant | a **point** | id `1`, a vector of 384 numbers, payload `{topic, text, fee_amount}` |
| Search results | a `ScoredPoint` | id `1`, score `0.5754`, the same payload |
| The output | one result line or table row | `#2  score=0.5754  id=1  topic=overdraft …` |

The **id** is the same everywhere (1 to 12), which is how the code knows two results are the same policy.

## Start of the output

```
Connected to Qdrant 1.20.0 at http://localhost:6333
Created 'banking_policies' (size=384, distance=Cosine) and upserted 12 points with payload {topic, text, fee_amount}

Query: "What fees will I be charged if I spend more than I have?"
```

- **Connected**: Qdrant answered the health check (`GET /`). If it isn't running, the demo stops here with `Cannot reach Qdrant …`.
- **Created**: the collection was recreated and the 12 policies were embedded and stored (see [Where the data lives](../README.md#where-the-data-lives)).
- **Query**: the question used for every search below. It's embedded once and the same vector is used in A–G.

## How to read one result line

```
#1  score=0.6376  id=3   topic=overdraft  fee=$35  No more than three $35 overdraft fees will be charged …
```

| Part | Meaning |
|---|---|
| `#1` | Rank in this search (1 = closest) |
| `score` | How similar the policy is to the question (cosine similarity, 1.0 = same meaning) |
| `id` | The point id in Qdrant, the same as the `Snippet` id in `PolicySnippets.java` |
| `topic`, `fee` | From the payload. `-` means the policy has no `fee_amount` |
| text | The `text` payload field, sent back by Qdrant with the result |

For this model, scores are roughly:
- **above 0.5**: clearly related
- **0.3 to 0.5**: loosely related
- **below 0.3**: mostly unrelated

That's a guide for all-MiniLM-L6-v2, not a fixed rule. Other models give different ranges.

Each search asks for the top 5 (`TOP_K = 5`). A search returns fewer than 5 when fewer points pass its filter.

## A) No filter

```
A) No filter — pure semantic similarity across all topics
  #1  score=0.6376  id=3   topic=overdraft  fee=$35  No more than three $35 overdraft fees will be charged to an account in a single business day.
  #2  score=0.5754  id=1   topic=overdraft  fee=$35  An overdraft fee of $35 is charged when a transaction exceeds the available balance.
  #3  score=0.5041  id=6   topic=fees       fee=$3   Out-of-network ATM withdrawals incur a $3 fee in addition to any operator surcharge.
  #4  score=0.4599  id=5   topic=fees       fee=$12  The $12 monthly maintenance fee is waived when the average daily balance stays above $1,500.
  #5  score=0.3607  id=4   topic=overdraft  fee=-    Overdrawn balances must be repaid within 30 days or the account may be closed.
```

Qdrant compares the question with all 12 policies and returns the 5 closest.

- **#1 and #2 are the overdraft fees**, even though the question never says "overdraft". "Spend more than I have" means the same as "exceeds the available balance". This is semantic search: matching on meaning, not on words.
- **The ranking isn't perfect.** id 1 describes the situation best, yet id 3 ranks first because it shares more words with the question ("fees … charged"). Similarity is not the same as "best answer".
- **#3 and #4 are general fees (ATM, maintenance).** They match the "fees" part of the question but not the "spend more than I have" part.
- **id 2 (overdraft protection) is missing**, even though it's relevant. Its score (0.3568, see B) is just below #5.

## B) `must`: topic = overdraft

```
  #1  score=0.6376  id=3   topic=overdraft  …
  #2  score=0.5754  id=1   topic=overdraft  …
  #3  score=0.3607  id=4   topic=overdraft  …
  #4  score=0.3568  id=2   topic=overdraft  …
```

- **Only overdraft policies.** The ATM and maintenance fees are gone.
- **The scores are identical to A** (id 3 is 0.6376 in both). A filter only decides which points are allowed; it never changes a score.
- **Only 4 results even though the limit is 5**, because only 4 policies have that topic. id 2 now appears.

## C) `must`: topic = loans

```
  #1  score=0.3270  id=10  topic=loans      fee=-    Late loan payments are charged a fee of 5% of the missed installment …
  #2  score=0.1031  id=9   topic=loans      fee=-    Personal loan applicants need a minimum credit score of 650 …
```

- **Both loan policies come back**, even though neither answers the question.
  - id 10 gets 0.33 because it shares "charged a fee".
  - id 9 gets 0.10 and is unrelated.
- **Qdrant always returns the closest matches it's allowed to.** Deciding "nothing relevant" is the application's job, for example ignoring anything below 0.4. Without that check, an assistant would answer a question about overdrafts using loan policies.

## D) `should`: topic = fraud OR topic = transfers

```
  #1  score=0.2974  id=12  topic=transfers  fee=$25  International wire transfers cost $25 …
  #2  score=0.2461  id=7   topic=fraud      fee=-    Report a lost or stolen debit card immediately …
  #3  score=0.1583  id=8   topic=fraud      fee=-    The bank will never ask for your PIN …
```

- **`should` = OR**: a point passes if at least one condition holds (see [How `should` works](#how-should-works) below).
- **The results are fraud or transfers policies**, all with low scores (< 0.3), because none of them is about the question.
- **id 12 ranks first** because it mentions a cost ("cost $25").

### How `should` works

`should` is Qdrant's **OR**. Search D's code:

```java
should(match("topic", "fraud"), match("topic", "transfers"))
```

builds this JSON, which means "topic is fraud **OR** topic is transfers":

```json
{"should": [
  {"key": "topic", "match": {"value": "fraud"}},
  {"key": "topic", "match": {"value": "transfers"}}
]}
```

The three clauses side by side:

| Clause | Logic | A point passes if… |
|---|---|---|
| `must` | AND | **every** condition is true |
| `should` | OR | **at least one** condition is true |
| `must_not` | NOT | **no** condition is true |

Some details that are good to know (checked against Qdrant 1.20.0):
- **Clauses in the same filter are combined with AND.** `{"must": [A], "should": [B, C]}` means `A AND (B OR C)`. In Qdrant, `should` is not optional when combined with `must`. For example, `must topic = fees` plus `should fee_amount ≥ 100` returns **nothing**, because no fees policy costs $100 or more. That's different from Elasticsearch, where `should` next to `must` only boosts the score.
- **`should` with a single condition behaves like `must`**, because "at least one of one" means that one.
- **For "this field equals any of these values" there's a shorter form.** `{"key": "topic", "match": {"any": ["fraud", "transfers"]}}` returns the same policies as search D (ids 7, 8 and 12). `should` is the general tool, for when the conditions are on different fields, e.g. `topic = fraud OR fee_amount ≥ 20`.

## E) `must_not`: topic = overdraft

```
  #1  score=0.5041  id=6   topic=fees       …
  #2  score=0.4599  id=5   topic=fees       …
  #3  score=0.3270  id=10  topic=loans      …
  #4  score=0.2974  id=12  topic=transfers  …
  #5  score=0.2461  id=7   topic=fraud      …
```

- **`must_not` = NOT**: points matching the condition are excluded.
- **It's A with the overdraft policies removed.** The empty places are filled by the next closest policies: loans, transfers, fraud.
- **This is useful for seeing what a search returns without the obvious topic.**

## F) `range`: 10 ≤ fee_amount ≤ 30

```
  #1  score=0.4599  id=5   topic=fees       fee=$12  …
  #2  score=0.2974  id=12  topic=transfers  fee=$25  …
```

- **Only the $12 and $25 policies match.**
  - $35 (ids 1, 3) is above the range.
  - $3 (id 6) is below the range.
  - Policies without a fee can never match a range.
- **They're still sorted by score, not by fee.**

## First "Why these results" block

This is the program's own summary of A–F, explained in the sections above. Its last point:
- **Filter clauses can be combined.** For example, `{"must": [...], "must_not": [...]}` means AND + NOT.
- **Every filtered field has a payload index** (`topic` as keyword, `fee_amount` as float), so Qdrant doesn't need to scan every point.

## G) Distance metrics

```
G) Same query, same points, three distance metrics (query vector length = 1.0000)
     id  topic       Cosine (higher=closer)  Dot (higher=closer)  Euclid (lower=closer)  sqrt(2 - 2*cos)
     3   overdraft                   0.6376               0.6376                 0.8514           0.8514
     1   overdraft                   0.5754               0.5754                 0.9215           0.9215
     6   fees                        0.5041               0.5041                 0.9959           0.9959
     5   fees                        0.4599               0.4599                 1.0393           1.0393
     4   overdraft                   0.3607               0.3607                 1.1308           1.1308
```

The same question is run against three collections (`banking_policies`, `_dot`, `_euclid`) that hold the same points but use different metrics.

- **The query vector length is 1.0000**: the model always outputs vectors of length 1.
- **Dot = Cosine, digit for digit.** Cosine is the dot product divided by both lengths, and both lengths are 1.
- **Euclid is a distance, so lower = closer.** It gives the same ranking.
- **For length-1 vectors, Euclid = `sqrt(2 − 2·cos)`.** The last column is calculated by the program from the Cosine score and matches Qdrant's Euclid column exactly.
- **With vectors that aren't length 1, the metrics would rank differently.** That's why the metric must match what the embedding model was trained for.

The second "Why these results" block says the same thing in short.

### How the table in G is built

Each row of the table shows **one policy** and its score in all three collections:

```
     id  topic       Cosine   Dot      Euclid
     3   overdraft   0.6376   0.6376   0.8514
```

But the code makes three separate searches, so it gets three separate lists of results:

```
cosine list:  [id 3 → 0.6376, id 1 → 0.5754, id 6 → 0.5041, …]
dot list:     [id 3 → 0.6376, id 1 → 0.5754, …]
euclid list:  [id 3 → 0.8514, id 1 → 0.9215, …]
```

To build a row, the loop goes through the **cosine list**. When it reaches id 3, it still needs id 3's Dot score and Euclid score, which are in the other two lists.

`scoresById()` turns those two lists into maps keyed by id, so the row can ask for them directly:

```java
dot.get(3L)     // → 0.6376  "what score did policy 3 get in the Dot collection?"
euclid.get(3L)  // → 0.8514  "what score did policy 3 get in the Euclid collection?"
```

That's what "look up" means in the code comments: find the score for the same policy (same id) in another search's results.

**Why not just take item 0 of each list?** Each list is sorted by its own metric. Here the order happens to be the same, but in general position 0 of the Dot list could be a different policy than position 0 of the Cosine list. Matching by id is always correct.

### Why all three searches return the same 5 policies

Three things are the same in all three searches:
1. **The same 12 points.** `createAndFill` puts the same vectors and payloads in each collection.
2. **The same question vector.**
3. **The same request: top 5, no filter.**

The only difference is how closeness is measured. And because the model makes every vector length 1, the three metrics always put the policies **in the same order**:
- **Dot gives exactly the same scores as Cosine**, so its top 5 is the same.
- **Euclid gives different numbers, but they move in lockstep with Cosine.** Higher cosine always means smaller distance. So the policy with the best cosine also has the smallest Euclid distance, the second best has the second smallest, and so on.

Same order means the same top 5.

This wouldn't be true if the vectors had different lengths. In the X / Y example in [The idea from the beginning](#the-idea-from-the-beginning), Dot picks a different winner than Cosine and Euclid. In a case like that, a policy could be in the Cosine top 5 but missing from the Dot top 5. Then `dot.get(id)` would find nothing and the table would print `null`.

### Why the last column is there

It's **not necessary** for the program to work; it's a **proof**. Removing it wouldn't break anything.

Output G claims that Euclid ranks the same as Cosine. Just looking at the two columns, that's hard to believe: the Cosine scores (0.6376, 0.5754, …) and the Euclid scores (0.8514, 0.9215, …) look unrelated.

The last column shows they're connected. For length-1 vectors there's an exact formula:

```
distance² = length(q)² + length(p)² − 2 × (q · p)
          = 1 + 1 − 2 × cosine
euclid    = sqrt(2 − 2 × cosine)
```

The program applies that formula to the Cosine score itself, without asking Qdrant. For policy 3: `sqrt(2 − 2 × 0.6376) = sqrt(0.7248) = 0.8514`. That's exactly the number Qdrant returned in the Euclid column.

So the last column is a check you can see. It turns "Euclid is just Cosine in disguise when vectors have length 1" from a claim into something the output demonstrates. The formula also shows why the order can't change: as cosine goes up, `2 − 2 × cosine` goes down, so the distance goes down.

### Cosine vs Dot vs Euclid in one table

All three answer "how close is this policy's vector to the question's vector?", but they measure it differently. Picture each vector as an arrow starting at zero:

| | What it measures | Ignores length? | Better score | Example: q = [1, 0], p = [0.8, 0.6] |
|---|---|---|---|---|
| **Cosine** | the angle between the two arrows | Yes | higher (1 = same direction) | 0.8 |
| **Dot** | direction **and** length: multiply matching numbers and add them up | No | higher | 1×0.8 + 0×0.6 = 0.8 |
| **Euclid** | the straight-line distance between the two arrow tips | No | **lower** (0 = identical) | √((1−0.8)² + (0−0.6)²) = 0.632 |

In this project they agree, because the model makes every vector length 1:
- **Dot = Cosine**, because cosine is the dot product divided by both lengths, and dividing by 1 changes nothing.
- **Euclid gives the same order**, just reversed (lower = closer). For length-1 vectors, Euclid = √(2 − 2 × cosine).

They only disagree when the vectors have different lengths, as the next section shows step by step.

### The idea from the beginning

**What `distanceMetricExamples()` does.** It creates two extra collections, `banking_policies_dot` and `banking_policies_euclid`. They get the same 12 points, but each collection compares vectors in a different way. The same question is searched in all three collections, and the scores are printed next to each other.

**Vectors as arrows.** Think of each vector as an arrow starting at zero. Ours have 384 numbers, but the idea is the same with 2:
- `[1, 0]` is an arrow pointing right.
- Its **length** is `sqrt(1² + 0²) = 1`.
- `[3, 4]` would have length `sqrt(9 + 16) = 5`.

**Three ways to measure "how close".** Take a question vector **q = [1, 0]** and a policy vector **p = [0.8, 0.6]**. Both have length 1.

| Metric | How it's calculated | Result for q and p | Meaning |
|---|---|---|---|
| **Cosine** | the angle between the arrows; length is ignored | 0.8 | 1 = same direction, 0 = unrelated. Higher = closer |
| **Dot** | multiply matching numbers and add: 1×0.8 + 0×0.6 | 0.8 | depends on direction **and** length. Higher = closer |
| **Euclid** | straight-line distance between the arrow tips: `sqrt((1−0.8)² + (0−0.6)²)` | 0.632 | 0 = identical. **Lower** = closer |

**Why Dot = Cosine.** Cosine is defined as the dot product divided by both lengths:

```
cosine = dot / (length of q × length of p)
```

The embedding model always produces vectors of length 1, so this divides by 1 × 1, which changes nothing. That's why the Cosine and Dot columns are identical, digit for digit.

**Why Euclid ranks the same, but lower = closer.** When every arrow has length 1, all the arrow tips sit at the same distance from zero. Then the smaller the angle between two arrows, the closer their tips are. So Euclid puts the policies in the same order as Cosine; only the direction of "better" is reversed.

There's an exact formula for it: `euclid = sqrt(2 − 2 × cosine)`. With the example, `sqrt(2 − 1.6) = 0.632`. That's what the last column of output G checks.

**When the metrics would disagree.** If the arrows had different lengths, take **q = [1, 0]** and two policies:
- **X = [0.8, 0.6]** (length 1)
- **Y = [3, 3]** (length 4.24)

| | Cosine | Dot | Euclid |
|---|---|---|---|
| X | **0.80** | 0.80 | **0.63** |
| Y | 0.71 | **3.00** | 3.61 |
| Winner | X | **Y** | X |

Dot picks Y just because its arrow is long, even though X points more in the question's direction.

That's why the metric must match what the model was trained for. With this model all lengths are 1, so the three metrics agree.

### When to use each

**The main rule: use the metric the embedding model was trained with.** The model's documentation (its "model card") says which one. The metric is chosen when the collection is created and can't be changed later. To switch, you create a new collection and upsert the points again.

| Metric | Use it when | Typical examples |
|---|---|---|
| **Cosine** | Only the *direction* of the vector carries meaning, not its length. This is the usual case for text | Most text embedding models, including all-MiniLM-L6-v2 (its model card recommends cosine similarity). Semantic search, RAG, FAQ matching, finding duplicate documents |
| **Dot** | The model was trained for dot product, or the length of the vector carries meaning (e.g. how popular or how confident something is). Also fine when vectors are already length 1, where it gives the same results as Cosine | Some search models trained specifically for dot product, and recommendation systems where a longer vector means "more popular item" or "stronger preference" |
| **Euclid** | The actual position in space matters: how far apart two things are, not just whether they point the same way. Often used when the model was trained with straight-line distance | Image and face recognition embeddings, sensor or measurement data, numeric features (age, amount, location), and grouping similar items into clusters |

**In this project Cosine is the right choice**, because all-MiniLM-L6-v2 is a text model and its documentation recommends cosine similarity. Since it outputs length-1 vectors, Dot would give identical results (output G shows this), and Euclid would give the same order.

**If you're unsure, check the model card first. If it doesn't say, Cosine is the safe default for text**: it ignores length, so it can't be distorted by long vectors the way Dot can (the X / Y example above).

Two details about how Qdrant handles this:
- **For Cosine collections, Qdrant converts every vector to length 1 when it's stored**, then compares them with the dot product, because that's faster. So the vectors you read back from a Cosine collection can differ from the ones you sent, if they weren't length 1 already.
- **Qdrant also supports a fourth metric, Manhattan**: the distance measured along each axis, like walking city blocks instead of in a straight line. The study guide doesn't cover it, and it's rarely used for text.
