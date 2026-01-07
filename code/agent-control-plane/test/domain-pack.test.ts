import assert from "node:assert/strict";
import { mkdtempSync, readFileSync, rmSync, writeFileSync } from "node:fs";
import { tmpdir } from "node:os";
import { join } from "node:path";
import test from "node:test";
import {
  DOMAIN_PACK_REGISTRY,
  DomainPackRegistry,
  loadDomainPack,
  NORMAL_3C_DOMAIN,
  SUPPORTED_PRODUCT_CATEGORIES,
} from "../src/domain-pack.js";
import { buildRetrievalPlan } from "../src/router.js";

test("shared Domain Pack is internally consistent with the active commerce contract", () => {
  assert.equal(NORMAL_3C_DOMAIN.packId, "normal-3c-v1");
  assert.ok(SUPPORTED_PRODUCT_CATEGORIES.has(NORMAL_3C_DOMAIN.defaultCategory));
  assert.ok(SUPPORTED_PRODUCT_CATEGORIES.has(NORMAL_3C_DOMAIN.primaryCategory));
  assert.ok(NORMAL_3C_DOMAIN.defaultBundleCategories.every(
    (category) => SUPPORTED_PRODUCT_CATEGORIES.has(category),
  ));
  assert.ok(NORMAL_3C_DOMAIN.categories.every(
    (category) => SUPPORTED_PRODUCT_CATEGORIES.has(category.id),
  ));
});

test("outdoor camping is discovered as a data-owned Domain Pack", () => {
  const camping = DOMAIN_PACK_REGISTRY.get("outdoor-camping-v1");
  assert.equal(camping.workflowId, "commerce-decision-v1");
  assert.equal(camping.capabilityProfileId, "commerce-bounded-v1");
  assert.deepEqual(
    camping.defaultBundleCategories,
    ["camp_stove", "fuel_canister", "cookware"],
  );
  assert.ok(camping.exampleQueries.length >= 2);
});

test("Domain Pack routing preserves low-price explicit budgets as hard constraints", () => {
  const camping = DOMAIN_PACK_REGISTRY.get("outdoor-camping-v1");
  const plan = buildRetrievalPlan("预算80元，推荐低温高海拔用的气罐", camping);
  assert.equal(plan.requirements.budgetMax, 80);
  assert.ok(plan.requirements.hardFields.includes("budgetMax"));
  assert.ok(plan.requirements.constraints.some((constraint) =>
    constraint.field === "budgetMax" && constraint.strength === "hard"
  ));
});

test("a third pack can be registered from manifest and assets without a core allowlist", () => {
  const directory = mkdtempSync(join(tmpdir(), "moyuan-domain-pack-"));
  try {
    for (const asset of ["catalog.json", "reviews.json", "compatibility.json"]) {
      writeFileSync(join(directory, asset), "{}", "utf8");
    }
    writeFileSync(join(directory, "fixture_domain.json"), JSON.stringify({
      schema_version: "1.0",
      pack_id: "fixture-books-v1",
      display_name: "图书测试包",
      description: "仅用于验证注册表扩展边界。",
      workflow_id: "commerce-decision-v1",
      capability_profile_id: "commerce-bounded-v1",
      assets: {
        catalog: "catalog.json",
        reviews: "reviews.json",
        compatibility: "compatibility.json",
      },
      default_category: "book",
      primary_category: "book",
      default_bundle_categories: ["book"],
      categories: [{ id: "book", label: "图书", terms: ["书"] }],
      use_cases: ["学习"],
      brands: [{ name: "Fixture", terms: ["fixture"] }],
      protocol_terms: ["isbn"],
      example_queries: ["推荐一本学习用书"],
    }), "utf8");
    const registry = DomainPackRegistry.fromDirectory(directory);
    assert.equal(registry.get("fixture-books-v1").defaultCategory, "book");
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});

test("Domain Pack manifests reject missing metadata and non-versioned identities", () => {
  const directory = mkdtempSync(join(tmpdir(), "moyuan-domain-pack-invalid-"));
  try {
    const source = JSON.parse(readFileSync(NORMAL_3C_DOMAIN.sourcePath, "utf8")) as Record<
      string,
      unknown
    >;
    for (const filename of Object.values(source.assets as Record<string, string>)) {
      writeFileSync(join(directory, filename), "{}", "utf8");
    }
    for (const field of [
      "display_name",
      "description",
      "workflow_id",
      "capability_profile_id",
      "example_queries",
    ]) {
      const candidate = { ...source };
      delete candidate[field];
      const path = join(directory, `missing-${field}.json`);
      writeFileSync(path, JSON.stringify(candidate), "utf8");
      assert.throws(() => loadDomainPack(path), /domain pack|identifier/);
    }
    for (const [field, value] of [
      ["pack_id", "not-versioned"],
      ["workflow_id", "Commerce-v1"],
      ["capability_profile_id", "profile"],
    ] as const) {
      const path = join(directory, `invalid-${field}.json`);
      writeFileSync(path, JSON.stringify({ ...source, [field]: value }), "utf8");
      assert.throws(() => loadDomainPack(path), /invalid identifier/);
    }
  } finally {
    rmSync(directory, { recursive: true, force: true });
  }
});
