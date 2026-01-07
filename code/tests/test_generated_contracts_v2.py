from __future__ import annotations

import unittest
from typing import get_type_hints

from shoprec.generated_contracts_v2 import (
    BundleOptimizationWireRequest,
    CreateRunResponse,
    DataSourceMetadataWireRecord,
    DiscoveryWireRequest,
    DiscoveryWireResponse,
    FusionWireRequest,
    PricingQuoteWireRequest,
    PricingQuoteWireResponse,
    RetailCatalogSnapshotWireRecord,
    ReviewEvidenceWireRequest,
    ReviewEvidenceWireResponse,
)
from shoprec.retail_decision import RetailDecisionService
from shoprec.retail_discovery import RetailDiscoveryService


class GeneratedContractsV2Tests(unittest.TestCase):
    def test_create_run_response_keeps_idempotency_replay_on_the_wire(self) -> None:
        self.assertEqual(
            CreateRunResponse.__required_keys__,
            frozenset(
                {
                    "runId",
                    "domainPackId",
                    "workflowId",
                    "status",
                    "eventsUrl",
                    "runUrl",
                    "idempotentReplay",
                }
            ),
        )
        self.assertIs(get_type_hints(CreateRunResponse)["idempotentReplay"], bool)

    def test_python_data_plane_consumes_generated_wire_annotations(self) -> None:
        for method in (
            RetailDiscoveryService.search,
            RetailDiscoveryService.recommend,
            RetailDiscoveryService.ads,
        ):
            self.assertIs(get_type_hints(method)["payload"], DiscoveryWireRequest)
            self.assertIs(
                get_type_hints(method)["return"], DiscoveryWireResponse
            )
        self.assertIs(
            get_type_hints(RetailDecisionService.fuse)["payload"],
            FusionWireRequest,
        )
        self.assertIs(
            get_type_hints(RetailDecisionService.optimize_bundles)["payload"],
            BundleOptimizationWireRequest,
        )
        self.assertIs(
            get_type_hints(RetailDecisionService.review_aspects)["payload"],
            ReviewEvidenceWireRequest,
        )
        self.assertIs(
            get_type_hints(RetailDecisionService.review_aspects)["return"],
            ReviewEvidenceWireResponse,
        )
        self.assertIs(
            get_type_hints(RetailDecisionService.quote)["payload"],
            PricingQuoteWireRequest,
        )
        self.assertIs(
            get_type_hints(RetailDecisionService.quote)["return"],
            PricingQuoteWireResponse,
        )

    def test_retail_provider_contracts_require_provenance_and_versions(self) -> None:
        self.assertEqual(
            DataSourceMetadataWireRecord.__required_keys__,
            frozenset({"source", "source_version", "provider_id"}),
        )
        self.assertIn("data_source", DiscoveryWireResponse.__required_keys__)
        self.assertIn("data_source", ReviewEvidenceWireResponse.__required_keys__)
        self.assertIn("data_source", PricingQuoteWireResponse.__required_keys__)
        self.assertEqual(
            RetailCatalogSnapshotWireRecord.__required_keys__,
            frozenset(
                {
                    "catalog_version",
                    "quote_version",
                    "generated_at",
                    "data_source",
                    "spus",
                }
            ),
        )


if __name__ == "__main__":
    unittest.main()
