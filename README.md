# BuySense

BuySense is a Java 17 and Spring Boot purchase-decision Agent for 3C electronics and outdoor-camping products. It turns a natural-language requirement into an evidence-grounded product set while keeping budget, inventory, compatibility, advertising, and transaction boundaries deterministic.

> Maintained implementation: [`code/java-control-plane`](code/java-control-plane)

## Architecture

Every request executes the same bounded collaboration chain:

1. Intent Router parses explicit constraints and proposes retrieval intent.
2. Search and Ads run concurrently; Recommendation consumes Search evidence.
3. Search and Recommendation candidates are fused with weighted RRF.
4. Sponsored candidates pass relevance, quality, fatigue, disclosure, and placement gates.
5. A compatibility graph enumerates feasible bundles under the budget.
6. Price, stock, and review evidence are refreshed before confirmation.
7. A deterministic audit runs before Critic; one bounded revision is allowed.
8. Lead formats the final proposal without creating an order or authorizing payment.

The LLM can supplement soft preferences and rank existing SKUs. Java policy code remains authoritative for category, budget, stock, compatibility, advertising, and transaction constraints.

## Engineering Highlights

- Domain Pack registry isolates catalogs, compatibility graphs, review aspects, and policies for 3C and outdoor-camping domains.
- Retail Provider SPI supports local snapshots, HTTP providers, and a read-only Shopify GraphQL adapter with version, scope, response-shape, and freshness checks.
- Asynchronous Run governance uses database admission, bounded executors, leases, fencing tokens, cancellation, idempotent replay, and SSE event recovery.
- Confirmation creates only a cart draft with `paymentAuthorized=false`; no model path can place an order or initiate payment.

## Reproducible Evidence

The committed synthetic deterministic benchmark contains 1,200 SPUs and 120 scenarios:

| Metric | Result |
| --- | ---: |
| Recall@10 | 95.83% |
| NDCG@10 | 99.57% |
| Hard-filter violations | 0 |
| Advertising-policy violations | 0 |

These are offline regression metrics generated from catalog attributes, not production conversion metrics or human relevance labels. The report is versioned at `code/java-control-plane/src/main/resources/data/v2/retrieval_report.json`.

## Run

```powershell
cd code/java-control-plane
mvn spring-boot:run
```

The application listens on `http://127.0.0.1:19090` and serves the bundled web interface from the same process. Local replay mode is deterministic and requires no external credential.

## Verify

```powershell
cd code/java-control-plane
mvn clean test
```

The normal suite covers the bounded collaboration chain, retrieval regression, Domain Packs, Shopify Mock GraphQL contracts, Run governance, identity isolation, SSE, idempotency, and transaction safety. The real-model evaluation is opt-in so CI never consumes model quota silently.

## Optional Real-Model Evaluation

Provide an OpenAI-compatible endpoint through environment variables; never commit a key:

```powershell
$env:BUYSENSE_REAL_MODEL_EVAL='true'
$env:MOYUAN_MODELPORT_URL='https://api.deepseek.com'
$env:MOYUAN_MODELPORT_KEY=$env:DEEPSEEK_API_KEY
$env:MOYUAN_MODELPORT_MODEL='deepseek-chat'
mvn '-Dtest=BuySenseRealModelEvaluationTest' test
```
