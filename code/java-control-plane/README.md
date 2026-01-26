# BuySense Java 17 control plane

BuySense is a 3C purchase-decision system built with Spring Boot. It keeps
deterministic search, recommendation, advertising and constraint logic while
using bounded LLM roles only for complex requests.

Production requests do not run two paths in parallel. `ExecutionRouter` sends
each request to one of these paths:

- `WORKFLOW`: information-complete single-product requests, with no model call.
- `HYBRID`: bundles, multiple categories, multiple soft objectives or ambiguous
  language; a Planner and Critic can each be called once.
- `CLARIFICATION`: required business input is missing; retrieval stops and the
  API returns one clarification question.

## Implemented capabilities

- server-owned anonymous session identity;
- asynchronous Run lifecycle, idempotent creation, cancellation and SSE replay;
- constraint provenance (`USER`, `MODEL`, `SYSTEM`, `CATALOG`);
- Search, Recommendation and Ads retrieval channels;
- weighted RRF fusion with an organic-result guardrail;
- bounded global bundle enumeration with budget and compatibility checks;
- Planner query enrichment that cannot overwrite user hard constraints;
- Critic `APPROVE`, `RETRIEVE`, `CLARIFY` contract with one bounded retry;
- deterministic policy gate that rejects unsafe or unnecessary model actions;
- persisted Runs, events, idempotency keys, preferences and cart drafts;
- Flyway migrations with file H2 locally and PostgreSQL by configuration;
- React/ECharts decision console served from the executable application;
- 40 human-authored regression cases and an opt-in live model benchmark;
- cart-draft confirmation with payment disabled by design.

## Run locally

```bash
mvn test
mvn spring-boot:run
```

Open `http://127.0.0.1:19090/`.

Rebuild the console after frontend changes:

```bash
cd ../apps/commerce-console
npm install
npm run build
```

## ModelPort integration

```powershell
$env:MOYUAN_MODELPORT_ENABLED="true"
$env:MOYUAN_MODELPORT_URL="http://127.0.0.1:38082"
$env:MOYUAN_MODELPORT_KEY="dev-client-key"
$env:MOYUAN_MODELPORT_MODEL="deepseek-default"
mvn spring-boot:run
```

Secrets remain in environment variables. They must not be committed.

## Reproduce the live comparison

The live test is skipped in ordinary `mvn test`. Run it only when a working
ModelPort endpoint is available:

```powershell
$env:RUN_LIVE_MODEL_BENCHMARK="true"
mvn '-Dtest=LiveDecisionBenchmarkTest' `
  '-Dbuysense.modelport.enabled=true' `
  '-Dbuysense.modelport.base-url=http://127.0.0.1:38082' `
  '-Dbuysense.modelport.api-key=dev-client-key' `
  '-Dbuysense.modelport.model=deepseek-default' test
```

The report is written to
`target/benchmark/live-decision-comparison.json`. See
[`docs/PROJECT_REVIEW_CN.md`](docs/PROJECT_REVIEW_CN.md) for the architecture,
measured result, design trade-offs and interview questions.

## Data boundary

The local catalog is a 13-SPU reference snapshot. Product names and attributes
support reproducible development, but prices, inventory and ranking scores are
not claimed as live commerce data. The project does not execute payment.
