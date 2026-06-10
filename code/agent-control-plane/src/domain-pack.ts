import { readFileSync } from "node:fs";
import type { ProductCategory } from "./contracts.js";

interface CategoryDefinition {
  id: ProductCategory;
  label: string;
  terms: string[];
}

interface BrandDefinition {
  name: string;
  terms: string[];
}

export interface CommerceDomainPack {
  schemaVersion: string;
  packId: string;
  defaultCategory: ProductCategory;
  primaryCategory: ProductCategory;
  defaultBundleCategories: ProductCategory[];
  categories: CategoryDefinition[];
  useCases: string[];
  brands: BrandDefinition[];
  protocolTerms: string[];
}

const KNOWN_CONTRACT_CATEGORIES = new Set<ProductCategory>([
  "phone",
  "headphones",
  "charger",
  "cable",
  "case",
]);

function stringArray(value: unknown, field: string): string[] {
  if (
    !Array.isArray(value) ||
    value.length === 0 ||
    !value.every((item) => typeof item === "string" && item.trim())
  ) {
    throw new Error(`domain pack ${field} must be a non-empty string array`);
  }
  return value.map((item) => item.trim());
}

function nonEmptyString(value: unknown, field: string): string {
  if (typeof value !== "string" || !value.trim()) {
    throw new Error(`domain pack ${field} must be a non-empty string`);
  }
  return value.trim();
}

function category(value: unknown, field: string): ProductCategory {
  if (typeof value !== "string" || !KNOWN_CONTRACT_CATEGORIES.has(value as ProductCategory)) {
    throw new Error(`domain pack ${field} is outside the active commerce contract`);
  }
  return value as ProductCategory;
}

function loadNormal3cDomainPack(): CommerceDomainPack {
  const codeRoot = new URL(import.meta.url.includes("/dist/") ? "../../../" : "../../", import.meta.url);
  const raw = JSON.parse(readFileSync(
    new URL("src/shoprec/data/normal_3c_domain_v1.json", codeRoot),
    "utf8",
  )) as Record<string, unknown>;
  if (!Array.isArray(raw.categories) || !Array.isArray(raw.brands)) {
    throw new Error("domain pack categories and brands must be arrays");
  }
  const categories = raw.categories.map((value, index) => {
    if (typeof value !== "object" || value === null || Array.isArray(value)) {
      throw new Error(`domain pack categories[${index}] must be an object`);
    }
    const item = value as Record<string, unknown>;
    if (typeof item.label !== "string" || !item.label.trim()) {
      throw new Error(`domain pack categories[${index}].label must be a string`);
    }
    return {
      id: category(item.id, `categories[${index}].id`),
      label: item.label.trim(),
      terms: stringArray(item.terms, `categories[${index}].terms`),
    };
  });
  const brands = raw.brands.map((value, index) => {
    if (typeof value !== "object" || value === null || Array.isArray(value)) {
      throw new Error(`domain pack brands[${index}] must be an object`);
    }
    const item = value as Record<string, unknown>;
    if (typeof item.name !== "string" || !item.name.trim()) {
      throw new Error(`domain pack brands[${index}].name must be a string`);
    }
    return {
      name: item.name.trim(),
      terms: stringArray(item.terms, `brands[${index}].terms`),
    };
  });
  if (new Set(categories.map((item) => item.id)).size !== categories.length) {
    throw new Error("domain pack category ids must be unique");
  }
  if (new Set(brands.map((item) => item.name)).size !== brands.length) {
    throw new Error("domain pack brand names must be unique");
  }
  const categoryIds = new Set(categories.map((item) => item.id));
  const defaultCategory = category(raw.default_category, "default_category");
  const primaryCategory = category(raw.primary_category, "primary_category");
  const defaultBundleCategories = stringArray(
    raw.default_bundle_categories,
    "default_bundle_categories",
  ).map((value, index) => category(value, `default_bundle_categories[${index}]`));
  for (const [field, value] of [
    ["default_category", defaultCategory],
    ["primary_category", primaryCategory],
    ...defaultBundleCategories.map((value, index) => [
      `default_bundle_categories[${index}]`,
      value,
    ]),
  ] as Array<[string, ProductCategory]>) {
    if (!categoryIds.has(value)) throw new Error(`domain pack ${field} is not declared in categories`);
  }
  return {
    schemaVersion: nonEmptyString(raw.schema_version, "schema_version"),
    packId: nonEmptyString(raw.pack_id, "pack_id"),
    defaultCategory,
    primaryCategory,
    defaultBundleCategories,
    categories,
    useCases: stringArray(raw.use_cases, "use_cases"),
    brands,
    protocolTerms: stringArray(raw.protocol_terms, "protocol_terms"),
  };
}

export const NORMAL_3C_DOMAIN = loadNormal3cDomainPack();
export const SUPPORTED_PRODUCT_CATEGORIES = new Set(
  NORMAL_3C_DOMAIN.categories.map((item) => item.id),
);

export function isSupportedProductCategory(value: unknown): value is ProductCategory {
  return typeof value === "string" && SUPPORTED_PRODUCT_CATEGORIES.has(value as ProductCategory);
}
