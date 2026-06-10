import assert from "node:assert/strict";
import test from "node:test";
import {
  NORMAL_3C_DOMAIN,
  SUPPORTED_PRODUCT_CATEGORIES,
} from "../src/domain-pack.js";

test("shared Domain Pack is internally consistent with the active commerce contract", () => {
  assert.equal(NORMAL_3C_DOMAIN.packId, "normal-3c-v1");
  assert.ok(SUPPORTED_PRODUCT_CATEGORIES.has(NORMAL_3C_DOMAIN.defaultCategory));
  assert.ok(SUPPORTED_PRODUCT_CATEGORIES.has(NORMAL_3C_DOMAIN.primaryCategory));
  assert.ok(NORMAL_3C_DOMAIN.defaultBundleCategories.every(
    (category) => SUPPORTED_PRODUCT_CATEGORIES.has(category),
  ));
  assert.equal(
    SUPPORTED_PRODUCT_CATEGORIES.size,
    NORMAL_3C_DOMAIN.categories.length,
  );
});
