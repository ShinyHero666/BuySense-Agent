# BuySense Java 17 control plane

BuySense is a multi-domain purchase-decision application built with Spring Boot. Its primary business domain is 3C electronics; an outdoor-camping pack proves that the same bounded orchestration can load a second catalog, vocabulary, evidence set and compatibility graph without scattering domain rules through the core.

Production requests never run two paths in parallel. `ExecutionRouter` chooses exactly one:

- `CLARIFICATION`: required business input is missing, so retrieval stops.
- `WORKFLOW`: information-complete requests use deterministic services without an LLM call.
- `HYBRID`: bundles or semantically complex requests may call one Planner and one Critic; both remain behind deterministic policy gates.

## Implemented capabilities

- two versioned Domain Packs backed by one Capability / Workflow Registry;
- ten registered capabilities with a validated, bounded delegation graph;
- server-owned session identity and cross-session resource hiding;
- asynchronous Run lifecycle, request-bound idempotency, cancellation and SSE replay;
- constraint provenance (`USER`, `MODEL`, `SYSTEM`, `CATALOG`);
- Search, Recommendation and Ads retrieval with weighted RRF and an ad guardrail;
- bounded global bundle enumeration with budget, stock and compatibility checks;
- Planner enrichment that cannot overwrite user hard constraints;
- Critic `APPROVE`, `RETRIEVE`, `CLARIFY` contract with one retrieval retry;
- Catalog, Review and Pricing HTTP Provider contracts with strict provenance;
- bounded HTTP I/O, strict JSON parsing, exact response partition checks and explicit fallback telemetry;
- persisted Runs, events, idempotency keys, preferences and cart drafts;
- exact proposal confirmation with payment disabled by design;
- React/ECharts decision console served from the executable application.

## Run locally

```bash
mvn test
mvn spring-boot:run
```

Open `http://127.0.0.1:19090/`. Rebuild the console after frontend changes:

```bash
cd ../apps/commerce-console
npm install
npm run build
```

The cross-platform browser suite starts and stops its own offline stack:

```bash
cd ../apps/commerce-console
npm run test:e2e
```

On Windows it uses Git Bash explicitly and verifies both E2E ports are free before taking ownership.

## ModelPort integration

```powershell
$env:MOYUAN_MODELPORT_ENABLED="true"
$env:MOYUAN_MODELPORT_URL="http://127.0.0.1:38082"
$env:MOYUAN_MODELPORT_KEY="dev-client-key"
$env:MOYUAN_MODELPORT_MODEL="deepseek-default"
mvn spring-boot:run
```

Secrets remain in environment variables and must not be committed. The opt-in live benchmark is skipped during ordinary `mvn test` and must not be reported as verified without its generated report.

## Verification boundary

- 40 human-authored Java business cases gate routing, task completion, clarification, hard constraints and ad policy.
- The committed synthetic retrieval suite contains 120 queries over 1,200 generated SPUs. Its labels come from catalog attributes, so it is a deterministic regression benchmark rather than human relevance judgment.
- Local catalogs are versioned reference snapshots. HTTP Provider tests use local contract servers; no claim is made about a real retailer, Shopify store, online conversion or payment execution.

See [`docs/PROJECT_REVIEW_CN.md`](docs/PROJECT_REVIEW_CN.md) for the measured results, design trade-offs and interview questions.