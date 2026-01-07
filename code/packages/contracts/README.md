# Moyuan SAR Agent V2 contracts

`commerce-agent-v2.schema.json` is the source of truth for cross-language run,
identity, event and requirement contracts. Regenerate the dependency-free
control-plane TypeScript types and validators, Python `TypedDict` types, and
browser TypeScript types and validators with:

```bash
python3 packages/contracts/generate_contracts.py
python3 packages/contracts/generate_contracts.py --check
```

Application code imports the generated outputs. The control plane validates the
V2 create-run request and response at the HTTP boundary, plus discovery, fusion,
and bundle requests before sending them to the Python data plane. The browser
validates create/get responses and SSE events before consuming them. Python
services keep their existing domain-level runtime validation and use the
generated `TypedDict` definitions for the shared wire shape. CI uses `--check`
to reject schema changes whose generated artifacts were not committed.
The dependency-free validator intentionally implements the schema keywords used
by this contract; generation fails closed if a new unsupported keyword or
format is introduced. Python output remains compatible with the declared
Python 3.10 minimum without requiring `typing_extensions`.

The schema also owns the replaceable retail-data boundary. A provider exposes
`GET /v1/catalog/{domain_pack_id}`, `POST /v1/reviews/query`, and
`POST /v1/prices/quote`; the Python data plane validates those responses before
they enter discovery or decision logic. Discovery, review, and quote responses
carry sanitized `data_source` provenance. `source` is either
`local_snapshot` or `remote_provider`; `source_version` and `provider_id` are
bounded safe identifiers and must never contain URLs or credentials.
