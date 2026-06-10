# Moyuan SAR Agent V2 contracts

`commerce-agent-v2.schema.json` is the source of truth for cross-language run,
identity, event and requirement contracts. Regenerate the dependency-free
TypeScript and Python types with:

```bash
python3 packages/contracts/generate_contracts.py
python3 packages/contracts/generate_contracts.py --check
```

Application code imports the generated outputs. CI uses `--check` to reject
schema changes whose generated types were not committed.
