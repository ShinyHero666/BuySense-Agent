# BuySense Java 17 control plane

BuySense is a bounded multi-domain purchase-decision application built with Spring Boot. The primary domain is 3C electronics; the outdoor-camping pack verifies that the same orchestration can load another catalog, vocabulary, evidence set and compatibility graph without scattering domain rules through the core.

Each production request follows one route:

- CLARIFICATION: required business input is missing, so retrieval stops.
- WORKFLOW: information-complete requests use deterministic services without an LLM call.
- HYBRID: bundles or semantically complex requests may call one Planner and one Critic; both remain behind deterministic policy gates.

## Implemented capabilities

- two versioned Domain Packs backed by one Capability / Workflow Registry;
- ten registered capabilities with a validated, bounded delegation graph;
- Search, Recommendation and Ads retrieval with weighted RRF and deterministic ad policy;
- bounded global bundle enumeration with budget, stock and compatibility checks;
- Planner enrichment that cannot overwrite user hard constraints;
- Critic APPROVE, RETRIEVE and CLARIFY contract with one bounded retrieval retry;
- Catalog, Review and Pricing HTTP Provider contracts with strict provenance;
- an authenticated Python data-plane bridge that reuses the upstream generic and Shopify adapters;
- strict JSON, exact response partitions, bounded I/O and fail-closed fallback defaults;
- server-owned identity, cross-session resource hiding and request-bound idempotency;
- database-atomic admission, bounded execution, Run leases, fencing, renewal and recovery;
- asynchronous Runs, cancellation, durable events, SSE replay and retention cleanup;
- exact proposal confirmation with fresh price, stock and compatibility revalidation;
- retryable failed confirmations and one successful cart draft per proposal;
- payment disabled by design;
- React/ECharts decision console served from the executable application.

## Run locally

~~~bash
mvn test
mvn spring-boot:run
~~~

Open http://127.0.0.1:19090/. Rebuild the console after frontend changes:

~~~bash
cd ../apps/commerce-console
npm install
npm run build
~~~

The browser suite starts and stops its own offline stack:

~~~bash
cd ../apps/commerce-console
npm run test:e2e
~~~

## Reuse the Python/Shopify data plane

The Python service exposes the Java Provider contract only when the bridge is explicitly enabled and protected by a separate Bearer key.

~~~powershell
# Python service
$env:PYTHONPATH="src"
$env:MOYUAN_RETAIL_DATA_MODE="http"
$env:MOYUAN_RETAIL_DATA_PROVIDER="shopify"
$env:MOYUAN_RETAIL_BRIDGE_ENABLED="true"
$env:MOYUAN_RETAIL_BRIDGE_KEY="replace-with-an-internal-bridge-key"
python -m shoprec.server --host 127.0.0.1 --port 18083

# Java control plane
$env:MOYUAN_RETAIL_PROVIDER_ENABLED="true"
$env:MOYUAN_RETAIL_PROVIDER_URL="http://127.0.0.1:18083"
$env:MOYUAN_RETAIL_PROVIDER_KEY="replace-with-the-same-internal-bridge-key"
$env:MOYUAN_RETAIL_FALLBACK_ENABLED="false"
mvn spring-boot:run
~~~

The bridge preserves local-fallback metadata. Java therefore rejects a fallback snapshot as live confirmation pricing instead of silently treating it as remote data.

## ModelPort integration

~~~powershell
$env:MOYUAN_MODELPORT_ENABLED="true"
$env:MOYUAN_MODELPORT_URL="http://127.0.0.1:38082"
$env:MOYUAN_MODELPORT_KEY="replace-with-a-scoped-client-key"
$env:MOYUAN_MODELPORT_MODEL="deepseek-default"
mvn spring-boot:run
~~~

Secrets remain in environment variables and must not be committed.

## Verification boundary

- 42 Java unit, integration and contract tests pass; one credentialed live-model benchmark is deliberately skipped.
- 40 human-authored business cases gate routing, task completion, clarification, hard constraints and ad policy.
- The synthetic retrieval suite has 120 queries over 1,200 generated SPUs. Labels come from catalog attributes, so this is a deterministic regression benchmark, not human relevance judgment.
- 153 Python tests pass, including the authenticated Java bridge and the retained Shopify adapter.
- 82 TypeScript control-plane tests pass.
- Local catalogs are reference snapshots. Without store credentials, no claim is made about a live Shopify store, online conversion or payment execution.

See docs/PROJECT_REVIEW_CN.md for measured results, trade-offs and interview questions.