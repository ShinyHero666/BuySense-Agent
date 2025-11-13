# Priority Policy Changes, 2026-09-05

Scope: local interview-project improvements. No platform deployment, automatic
domain router, new conversation store, database migration, or Agent framework replacement.

## Business Boundaries

| Concern | Current behavior | Boundary |
| --- | --- | --- |
| Intent | Chinese budgets, negative brands, original-span evidence | Explicit constraints cannot be replaced by model guesses; ambiguous budgets and unknown product categories ask for clarification |
| Missing budget | Search is allowed without a budget ceiling | A product model number is not a budget; no invented default limit |
| Clarification | Returns a question and no confirmable purchase proposal | The next submission must include the complete requirement; no new multi-turn state |
| Category vocabulary | Domain Pack owns product names, including fast-charging adapters | Fast charging alone describes a capability, not an additional product |
| Single product | Search is required; accessory recommendation is optional | Bundles retain the requested accessory categories and bounded handoff fallback |
| Model reranking | 80% deterministic score plus 20% model rank, sorted by the combined score | Unknown SKUs are discarded; a poor candidate cannot jump above a much better one solely through model ordering |
| Organic ranking | Stable bounded scores without candidate-window min-max rescaling | Identity hashes no longer invent long-term preferences; only supplied observed history adds affinity |
| Advertising | Bid-free relevance is checked before bid/quality/exposure ranking | Absolute relevance >= 0.5, quality >= 0.5, and relevance >= 85% of the same-category organic best; at most one disclosed ad in slot two |
| Bundles | Preserve required category coverage, enforce compatibility and maximum cost | Budget is a ceiling, not a 65% spending requirement; equally scored cheaper bundles win |
| Revision | One candidate expansion, fresh quote/review data, then deterministic reaudit | Prior supported Critic objections are rechecked against new evidence, not silently forgotten |
| Model budget | At most four turns per role, shared configurable call budget (default 12) | Disabled roles do not spend remote-call budget; retries and malformed-tool corrections do |
| Failure | At most one retry for transient transport/5xx failure, only with time remaining | No immediate retry for 400/429; HTTP timeout is bounded by the remaining 90-second workflow deadline |

Search score: 0.70 * relevance + 0.18 * explicit brand match + observed affinity.
Recommendation score: 0.70 * relevance + 0.10 * universal ecosystem + 0.08 * peer fit + observed affinity.
Observed affinity is at most 0.12 (0.08 recent brand + 0.04 recent category).
Relevance averages query matching and requested-use-case coverage when use cases exist.
These are bounded, explainable heuristic defaults, not learned probabilities.

## Validation Scope

- Regular automated tests cover intent counterexamples, category coverage, ad noise,
  price ties, history opt-out, actual call budgets, transport timeouts and Critic revision.
- The 30-case catalog regression runs twice per case. Its versioned contract removes
  the old spending floor; compatibility, budget ceiling and transaction checks remain.
- The existing 12 v2 prompts run three times each in local replay. Original Gold files
  remain unchanged. The original-contract report is retained alongside a separately
  named regression report that removes the spending floor and the unsupported
  inference from the SummitFlow brand to an explicit pressure-regulation preference.
  This is reused-task regression, not a fresh blind holdout.
- A 12-query actual-catalog sensitivity check compares recommendation RRF weights
  0.55, 0.9, 1.0 and 1.35 at fixed search weight 1.0. Product-level relevance sets are
  authored from synthetic catalog facts, not inferred from the ranking output.
  All four settings tied at Recall@3=1 and MRR@3=1 in this small check. This cannot
  establish that 0.9 is optimal; the existing default is retained.
- Eight authorized DeepSeek scenarios passed deterministic task and safety checks.
  Search/Recommendation model proposals participated, but all eight final Lead
  messages fell back to the deterministic evidence template after validation.
  This does not establish free-form answer quality or independent-judge agreement.
- No current 12 x 3 x 3 controlled live benchmark or new human calibration is claimed.

Reports are generated under target/evaluation/. API keys remain in environment
variables; hidden grading contracts are never included in target-model input.

## Reproduce Locally

~~~powershell
$env:BUYSENSE_PRIORITY_FROZEN_REGRESSION='true'
mvn test
~~~

The private v2 Gold resource is required for the opt-in frozen-task regression.
Without that resource, omit the environment variable and run the public suite.
BUYSENSE_MODELPORT_MAX_CALLS_PER_RUN overrides the default call budget.

## Resume Impact

The existing four points (multi-role collaboration, retrieval/fusion, product
constraints, and evaluation) do not need expansion for these changes. Keep detailed
trade-offs for the interview. Do not present the small sensitivity check as an
optimal-weight study, or historical live results as a rerun of this policy.
