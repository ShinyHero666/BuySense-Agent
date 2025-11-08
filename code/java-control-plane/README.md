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
- Catalog, Review and Pricing Provider SPI with strict provenance;
- a Java-native, read-only Shopify Admin GraphQL adapter with fixed queries, scope checks and bounded caching;
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

## Java-native Shopify provider

The default mode uses versioned local snapshots and needs no external credential. To read a real development, staging or production store, configure a read-only Shopify Admin token and let Java call the pinned GraphQL API directly:

~~~powershell
$env:MOYUAN_SHOPIFY_ENABLED="true"
$env:MOYUAN_SHOPIFY_STORE_DOMAIN="your-store.myshopify.com"
$env:MOYUAN_SHOPIFY_ADMIN_ACCESS_TOKEN="replace-with-a-read-only-token"
$env:MOYUAN_SHOPIFY_FALLBACK_ENABLED="false"
$env:MOYUAN_SHOPIFY_CATALOG_CACHE_TTL="5m"
mvn spring-boot:run
~~~

The adapter only sends three fixed read queries, requires `read_products`, rejects every `write_*` scope, disables redirects and proxy forwarding, pins Admin API `2026-07`, bounds request/response sizes and caches the catalog for five minutes. Reviews and prices are refreshed for every decision load; confirmation invalidates the catalog cache and reloads catalog metadata, reviews and quotes. A transient provider failure may use a local snapshot only when fallback is explicitly enabled, and snapshot pricing is never accepted during confirmation.

`MOYUAN_RETAIL_PROVIDER_*` remains available for a generic HTTP implementation of the same Java Provider SPI. Generic HTTP and direct Shopify modes are mutually exclusive. Neither mode requires `server.py` or a Python process.

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

- 49 Java unit, integration and contract tests pass; one credentialed live-model benchmark is deliberately skipped.
- 40 human-authored business cases gate routing, task completion, clarification, hard constraints and ad policy.
- The synthetic retrieval suite has 120 queries over 1,200 generated SPUs. Labels come from catalog attributes, so this is a deterministic regression benchmark, not human relevance judgment.
- Java Shopify tests use a deterministic mock GraphQL server. Without store credentials, no claim is made about a live Shopify store, online conversion or payment execution.

See docs/PROJECT_REVIEW_CN.md for measured results, trade-offs and interview questions.