# BuySense Java Decision Benchmark

Run from `code/java-control-plane`:

```powershell
mvn -q "-Dtest=DecisionEngineBenchmark" "-Dbuysense.benchmark.report=target/benchmark-results/buysense-java-decision-v1.json" test
```

The benchmark executes the current Java `IntentParser` and in-memory
`DecisionEngine`. It uses 120 fixed queries, performs five warm-up rounds and
twenty measured rounds, and checks result availability plus category, budget
and advertising-policy invariants.

Latency excludes LLM, network and database I/O. The local catalog contains only
14 SKUs across 13 SPUs, so latency is recorded for regression diagnostics and
must not be presented as production performance. The resume-safe result is the
current Java correctness result: 2,400 measured decisions, 100% non-empty
results and zero category, budget or advertising-policy violations.
