from __future__ import annotations

import json
import tempfile
import unittest
from pathlib import Path

from shoprec.retail_decision import RetailDecisionService
from shoprec.retail_domain import (
    DEFAULT_DOMAIN_PACK_ID,
    DOMAIN_PACK_REGISTRY,
    NORMAL_3C_DOMAIN_PACK_MODEL,
    load_domain_pack,
)
from shoprec.retail_models import load_retail_catalog


class DomainPackRegistryTest(unittest.TestCase):
    def test_registry_discovers_both_versioned_domain_packs(self) -> None:
        pack_ids = {pack.pack_id for pack in DOMAIN_PACK_REGISTRY.all()}
        self.assertTrue(
            {"normal-3c-v1", "outdoor-camping-v1"}.issubset(pack_ids)
        )
        self.assertEqual(DOMAIN_PACK_REGISTRY.default_pack_id, DEFAULT_DOMAIN_PACK_ID)
        self.assertIs(
            DOMAIN_PACK_REGISTRY.get(),
            NORMAL_3C_DOMAIN_PACK_MODEL,
        )

    def test_public_metadata_is_complete_and_stably_ordered(self) -> None:
        metadata = DOMAIN_PACK_REGISTRY.metadata()
        metadata_ids = [item["id"] for item in metadata]
        self.assertEqual(metadata_ids, sorted(metadata_ids))
        self.assertTrue(
            {"normal-3c-v1", "outdoor-camping-v1"}.issubset(metadata_ids)
        )
        for item in metadata:
            self.assertTrue(item["displayName"])
            self.assertTrue(item["description"])
            self.assertEqual(item["schemaVersion"], "1.0")
            self.assertEqual(item["workflowId"], "commerce-decision-v1")
            self.assertTrue(item["categories"])
            self.assertTrue(item["exampleQueries"])

    def test_every_pack_has_consistent_catalog_evidence_and_query_references(self) -> None:
        for pack in DOMAIN_PACK_REGISTRY.all():
            with self.subTest(pack_id=pack.pack_id):
                catalog = load_retail_catalog(pack=pack)
                decision = RetailDecisionService(catalog, pack=pack)
                product_ids = {spu.spu_id for spu in catalog.spus}
                sku_ids = {
                    sku.sku_id
                    for spu in catalog.spus
                    for sku in spu.skus
                }
                self.assertTrue(product_ids)
                self.assertTrue(
                    set(decision.reviews.products).issubset(product_ids)
                )
                for rule in decision.compatibility.rules:
                    self.assertIn(rule.primary_category, pack.product_categories)
                    self.assertIn(rule.accessory_category, pack.product_categories)
                self.assertEqual(
                    set(decision.compatibility.items_by_sku),
                    sku_ids,
                )

                queries_asset = pack.assets.get("queries")
                if queries_asset is not None:
                    queries = json.loads(pack.asset_path("queries").read_text(encoding="utf-8"))
                    self.assertTrue(queries)
                    for query in queries:
                        self.assertTrue(
                            set(query["expected_categories"]).issubset(
                                pack.product_categories
                            )
                        )
                        self.assertTrue(
                            set(query["relevant_spu_ids"]).issubset(product_ids)
                        )

    def test_manifest_identity_and_metadata_are_required_fail_closed(self) -> None:
        source = json.loads(
            NORMAL_3C_DOMAIN_PACK_MODEL.source_path.read_text(encoding="utf-8")
        )
        with tempfile.TemporaryDirectory() as directory:
            root = Path(directory)
            for filename in source["assets"].values():
                (root / filename).write_text("{}", encoding="utf-8")

            for missing in (
                "display_name",
                "description",
                "workflow_id",
                "capability_profile_id",
                "example_queries",
            ):
                with self.subTest(missing=missing):
                    candidate = dict(source)
                    candidate.pop(missing)
                    path = root / f"missing-{missing}.json"
                    path.write_text(json.dumps(candidate), encoding="utf-8")
                    with self.assertRaises(ValueError):
                        load_domain_pack(path)

            for field, invalid in (
                ("pack_id", "not-versioned"),
                ("workflow_id", "Commerce-v1"),
                ("capability_profile_id", "profile"),
            ):
                with self.subTest(field=field):
                    candidate = dict(source)
                    candidate[field] = invalid
                    path = root / f"invalid-{field}.json"
                    path.write_text(json.dumps(candidate), encoding="utf-8")
                    with self.assertRaises(ValueError):
                        load_domain_pack(path)


if __name__ == "__main__":
    unittest.main()
