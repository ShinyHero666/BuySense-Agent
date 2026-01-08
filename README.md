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

## Evidence Provenance

- Objective catalog facts such as specifications, connectors, protocols, price, and stock come from versioned catalog snapshots in local evaluation and from PIM/Retail Provider integrations in production.
- Review evidence is an independent aggregate containing sample size, aspect mentions, sentiment, and confidence. The committed review data is a synthetic offline snapshot of that service contract; it is neither live scraping nor merchant-authored customer feedback.
- Merchant claims must keep a distinct source type and cannot be presented as objective facts or customer opinion. The LLM may summarize returned evidence, but it cannot invent or overwrite it; missing evidence is reported explicitly.

### External Review Provider

Set `BUYSENSE_REVIEW_PROVIDER_ENABLED=true`, `BUYSENSE_REVIEW_PROVIDER_URL`, and optionally `BUYSENSE_REVIEW_PROVIDER_KEY`. The Agent calls `POST /v1/reviews/query` with a Domain Pack ID and product IDs. The service must return a versioned aggregate for every requested product or list it as missing:

`sample_size`, `dimension`, `aspect`, `mention_count`, `sentiment`, `confidence`, and `summary`.

The gateway validates and stamps provenance before the evidence reaches the Agent. A vendor that only returns raw review text still needs an adapter that performs cleaning and aspect aggregation. Optional fallback is explicit in both the Artifact and final response.


## Reproducible Benchmark

- The regression partition contains 30 scenarios with two trials each across the 3C and outdoor-camping Domain Packs.
- The frozen capability holdout contains 12 tasks with three trials each. Public prompts and hidden grading contracts are stored separately, prompt tuning is forbidden, and both files are fingerprinted with SHA-256.
- Java graders own budget, stock, compatibility, advertising, transaction safety, and trajectory checks. An independent blinded model judge scores only evidence quality and trade-off explanation; selected samples then require blinded human calibration.
- Controlled runs compare the full bounded Agent with a deterministic no-model baseline and a no-Critic ablation under the same task and data snapshots.

The v2 controlled run completed all 12 tasks x 3 trials x 3 system variants. The full
bounded-Agent variant passed every deterministic redline and independent model judgment;
blinded human calibration on four qualitative cases agreed with the judge 4/4. Cohen's Kappa
is reported as undefined because every human label was PASS. Hidden grading contracts, human
labels, and raw model reports remain local by design; the repository publishes the task sets,
evaluation framework, and a non-sensitive loader fixture.

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

Private capability regression also requires the local hidden grading contract:

```powershell
$env:BUYSENSE_LOCAL_CAPABILITY_BENCHMARK='true'
mvn '-Dtest=BuySenseCapabilityBenchmarkTest' test
```

## Optional Real-Model Evaluation

Provide an OpenAI-compatible endpoint through environment variables; never commit a key:

```powershell
$env:BUYSENSE_REAL_MODEL_EVAL='true'
$env:BUYSENSE_MODELPORT_URL='https://api.deepseek.com'
$env:BUYSENSE_MODELPORT_KEY=$env:DEEPSEEK_API_KEY
$env:BUYSENSE_MODELPORT_MODEL='<target-model>'
$env:BUYSENSE_MODELPORT_THINKING_MODE='disabled'
mvn '-Dtest=BuySenseRealModelEvaluationTest' test
```

Run the frozen 12 x 3 controlled benchmark only after configuring a separate judge model:

```powershell
$env:BUYSENSE_BENCHMARK_EVAL='true'
$env:BUYSENSE_MODELPORT_URL='https://api.deepseek.com'
$env:BUYSENSE_MODELPORT_KEY=$env:DEEPSEEK_API_KEY
$env:BUYSENSE_MODELPORT_MODEL='<target-model>'
$env:BUYSENSE_MODELPORT_THINKING_MODE='disabled'
$env:BUYSENSE_JUDGE_URL='https://api.deepseek.com'
$env:BUYSENSE_JUDGE_KEY=$env:DEEPSEEK_API_KEY
$env:BUYSENSE_JUDGE_MODEL='<independent-judge-model>'
mvn '-Dtest=BuySenseControlledBenchmarkTest' test
```

The command produces per-variant JSON and Markdown reports plus deltas for `deterministic-no-model`, `no-critic-ablation`, and `full-bounded-agent`. The example uses one provider but distinct target and judge models; a different judge provider is stronger evidence. Setting `BUYSENSE_ALLOW_SAME_MODEL_JUDGE=true` permits a same-model provisional run, but that result must not be described as independent judging.
