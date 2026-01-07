import { existsSync, readFileSync, readdirSync } from "node:fs";
import { basename, dirname, join, resolve } from "node:path";
import { fileURLToPath } from "node:url";
import type { CatalogProduct, ProductCategory } from "./contracts.js";

export interface CategoryDefinition {
  id: ProductCategory;
  label: string;
  terms: string[];
}

export interface BrandDefinition {
  name: string;
  terms: string[];
}

export interface CategoryRequirement {
  connectorsAny: string[];
  protocolsAny: string[];
}

export interface CommerceDomainPack {
  schemaVersion: "1.0";
  packId: string;
  displayName: string;
  description: string;
  workflowId: string;
  capabilityProfileId: string;
  defaultCategory: ProductCategory;
  primaryCategory: ProductCategory;
  defaultBundleCategories: ProductCategory[];
  categories: CategoryDefinition[];
  useCases: string[];
  brands: BrandDefinition[];
  protocolTerms: string[];
  categoryRequirements: Readonly<Record<string, CategoryRequirement>>;
  assets: Readonly<{
    catalog: string;
    reviews: string;
    compatibility: string;
    queries?: string;
  }>;
  exampleQueries: string[];
  sourcePath: string;
}

const VERSIONED_IDENTIFIER = /^[a-z][a-z0-9-]*-v[0-9]+$/;
const CATEGORY_IDENTIFIER = /^[a-z][a-z0-9_]{0,63}$/;

function record(value: unknown, field: string): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new Error(`domain pack ${field} must be an object`);
  }
  return value as Record<string, unknown>;
}

function stringArray(value: unknown, field: string, allowEmpty = false): string[] {
  if (
    !Array.isArray(value) ||
    (!allowEmpty && value.length === 0) ||
    !value.every((item) => typeof item === "string" && item.trim())
  ) {
    throw new Error(`domain pack ${field} must be ${allowEmpty ? "a" : "a non-empty"} string array`);
  }
  return [...new Set(value.map((item) => item.trim()))];
}

function nonEmptyString(value: unknown, field: string): string {
  if (typeof value !== "string" || !value.trim()) {
    throw new Error(`domain pack ${field} must be a non-empty string`);
  }
  return value.trim();
}

function boundedString(value: unknown, field: string, maximum: number): string {
  const parsed = nonEmptyString(value, field);
  if (Array.from(parsed).length > maximum) {
    throw new Error(`domain pack ${field} must contain at most ${maximum} characters`);
  }
  return parsed;
}

function identifier(value: unknown, field: string, pattern = VERSIONED_IDENTIFIER): string {
  const parsed = nonEmptyString(value, field);
  if (parsed.length > 64 || !pattern.test(parsed)) {
    throw new Error(`domain pack ${field} has an invalid identifier`);
  }
  return parsed;
}

function assetName(value: unknown, field: string): string {
  const parsed = nonEmptyString(value, field);
  if (basename(parsed) !== parsed || !parsed.endsWith(".json")) {
    throw new Error(`domain pack ${field} must be a local JSON filename`);
  }
  return parsed;
}

export function loadDomainPack(path: string): CommerceDomainPack {
  const raw = record(JSON.parse(readFileSync(path, "utf8")), "root");
  if (!Array.isArray(raw.categories) || !Array.isArray(raw.brands)) {
    throw new Error("domain pack categories and brands must be arrays");
  }
  if (raw.categories.length === 0 || raw.categories.length > 100) {
    throw new Error("domain pack categories must contain between 1 and 100 items");
  }
  if (raw.brands.length === 0) throw new Error("domain pack brands must not be empty");
  const categories = raw.categories.map((value, index) => {
    const item = record(value, `categories[${index}]`);
    return {
      id: identifier(item.id, `categories[${index}].id`, CATEGORY_IDENTIFIER),
      label: boundedString(item.label, `categories[${index}].label`, 120),
      terms: stringArray(item.terms, `categories[${index}].terms`),
    };
  });
  const brands = raw.brands.map((value, index) => {
    const item = record(value, `brands[${index}]`);
    return {
      name: nonEmptyString(item.name, `brands[${index}].name`),
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
  const category = (value: unknown, field: string): ProductCategory => {
    const parsed = identifier(value, field, CATEGORY_IDENTIFIER);
    if (!categoryIds.has(parsed)) throw new Error(`domain pack ${field} is not declared in categories`);
    return parsed;
  };
  const defaultCategory = category(raw.default_category, "default_category");
  const primaryCategory = category(raw.primary_category, "primary_category");
  const defaultBundleCategories = stringArray(
    raw.default_bundle_categories,
    "default_bundle_categories",
  ).map((value, index) => category(value, `default_bundle_categories[${index}]`));
  const rawAssets = record(raw.assets, "assets");
  const requirements: Record<string, CategoryRequirement> = {};
  if (raw.category_requirements !== undefined) {
    for (const [categoryId, value] of Object.entries(record(
      raw.category_requirements,
      "category_requirements",
    ))) {
      category(categoryId, `category_requirements.${categoryId}`);
      const item = record(value, `category_requirements.${categoryId}`);
      const unknownFields = Object.keys(item).filter(
        (field) => !["connectors_any", "protocols_any"].includes(field),
      );
      if (unknownFields.length > 0) {
        throw new Error(
          `domain pack category_requirements.${categoryId} has unknown fields: ${unknownFields.sort().join(", ")}`,
        );
      }
      requirements[categoryId] = {
        connectorsAny: stringArray(
          item.connectors_any ?? [],
          `category_requirements.${categoryId}.connectors_any`,
          true,
        ),
        protocolsAny: stringArray(
          item.protocols_any ?? [],
          `category_requirements.${categoryId}.protocols_any`,
          true,
        ),
      };
    }
  }
  const schemaVersion = nonEmptyString(raw.schema_version, "schema_version");
  if (schemaVersion !== "1.0") throw new Error("unsupported domain pack schema_version");
  const exampleQueries = stringArray(raw.example_queries, "example_queries");
  if (exampleQueries.length > 20) {
    throw new Error("domain pack example_queries must contain at most 20 items");
  }
  const pack: CommerceDomainPack = {
    schemaVersion,
    packId: identifier(raw.pack_id, "pack_id"),
    displayName: boundedString(raw.display_name, "display_name", 120),
    description: boundedString(raw.description, "description", 500),
    workflowId: identifier(raw.workflow_id, "workflow_id"),
    capabilityProfileId: identifier(
      raw.capability_profile_id,
      "capability_profile_id",
    ),
    defaultCategory,
    primaryCategory,
    defaultBundleCategories,
    categories,
    useCases: stringArray(raw.use_cases, "use_cases"),
    brands,
    protocolTerms: stringArray(raw.protocol_terms, "protocol_terms"),
    categoryRequirements: requirements,
    assets: {
      catalog: assetName(rawAssets.catalog, "assets.catalog"),
      reviews: assetName(rawAssets.reviews, "assets.reviews"),
      compatibility: assetName(rawAssets.compatibility, "assets.compatibility"),
      ...(rawAssets.queries === undefined
        ? {}
        : { queries: assetName(rawAssets.queries, "assets.queries") }),
    },
    exampleQueries: exampleQueries.map((query, index) =>
      boundedString(query, `example_queries[${index}]`, 2_000)
    ),
    sourcePath: resolve(path),
  };
  for (const [kind, filename] of Object.entries(pack.assets)) {
    if (!existsSync(join(dirname(pack.sourcePath), filename))) {
      throw new Error(`domain pack ${pack.packId} ${kind} asset does not exist: ${filename}`);
    }
  }
  return pack;
}

export class DomainPackRegistry {
  readonly #packs: ReadonlyMap<string, CommerceDomainPack>;

  constructor(packs: Iterable<CommerceDomainPack>) {
    const registered = new Map<string, CommerceDomainPack>();
    for (const pack of packs) {
      if (registered.has(pack.packId)) throw new Error(`duplicate domain pack: ${pack.packId}`);
      registered.set(pack.packId, pack);
    }
    if (registered.size === 0) throw new Error("domain pack registry must not be empty");
    this.#packs = registered;
  }

  static fromDirectory(directory: string): DomainPackRegistry {
    const packs = readdirSync(directory, { withFileTypes: true })
      .filter((entry) => entry.isFile() && entry.name.endsWith(".json"))
      .flatMap((entry) => {
        const path = join(directory, entry.name);
        const raw = JSON.parse(readFileSync(path, "utf8")) as unknown;
        return typeof raw === "object" && raw !== null && !Array.isArray(raw) &&
            Object.hasOwn(raw, "pack_id")
          ? [loadDomainPack(path)]
          : [];
      });
    return new DomainPackRegistry(packs);
  }

  get(packId: string): CommerceDomainPack {
    const pack = this.#packs.get(packId);
    if (!pack) throw new Error(`unknown_domain_pack:${packId}`);
    return pack;
  }

  has(packId: string): boolean {
    return this.#packs.has(packId);
  }

  list(): CommerceDomainPack[] {
    return [...this.#packs.values()].sort((left, right) => left.packId.localeCompare(right.packId));
  }
}

function catalogRecord(value: unknown, field: string): Record<string, unknown> {
  if (typeof value !== "object" || value === null || Array.isArray(value)) {
    throw new Error(`catalog ${field} must be an object`);
  }
  return value as Record<string, unknown>;
}

function catalogStrings(value: unknown, field: string): string[] {
  if (!Array.isArray(value) || !value.every((item) => typeof item === "string")) {
    throw new Error(`catalog ${field} must be a string array`);
  }
  return value;
}

/** Load the same versioned catalog snapshot used by the Python data plane. */
export function loadCatalogForDomainPack(pack: CommerceDomainPack): CatalogProduct[] {
  const path = join(dirname(pack.sourcePath), pack.assets.catalog);
  const raw = catalogRecord(JSON.parse(readFileSync(path, "utf8")), "root");
  if (!Array.isArray(raw.spus)) throw new Error("catalog spus must be an array");
  const catalogVersion = nonEmptyString(raw.catalog_version, "catalog.catalog_version");
  const quoteVersion = nonEmptyString(raw.quote_version, "catalog.quote_version");
  const categories = new Set(pack.categories.map((item) => item.id));
  return raw.spus.flatMap((spuValue, spuIndex) => {
    const spu = catalogRecord(spuValue, `spus[${spuIndex}]`);
    const spuId = nonEmptyString(spu.spu_id, `catalog.spus[${spuIndex}].spu_id`);
    const category = nonEmptyString(spu.category, `catalog.${spuId}.category`);
    if (!categories.has(category)) throw new Error(`catalog category is outside ${pack.packId}: ${category}`);
    if (!Array.isArray(spu.skus)) throw new Error(`catalog ${spuId}.skus must be an array`);
    return spu.skus.flatMap((skuValue, skuIndex) => {
      const sku = catalogRecord(skuValue, `${spuId}.skus[${skuIndex}]`);
      const skuId = nonEmptyString(sku.sku_id, `catalog.${spuId}.sku_id`);
      if (!Array.isArray(sku.offers)) throw new Error(`catalog ${skuId}.offers must be an array`);
      const ecosystem = nonEmptyString(sku.ecosystem, `catalog.${skuId}.ecosystem`);
      if (!["ios", "android", "universal"].includes(ecosystem)) {
        throw new Error(`catalog ${skuId}.ecosystem is unsupported`);
      }
      return sku.offers.map((offerValue, offerIndex): CatalogProduct => {
        const offer = catalogRecord(offerValue, `${skuId}.offers[${offerIndex}]`);
        const price = offer.price;
        const stock = offer.stock;
        if (typeof price !== "number" || !Number.isFinite(price) || price < 0) {
          throw new Error(`catalog ${skuId}.price must be non-negative`);
        }
        if (!Number.isInteger(stock) || (stock as number) < 0) {
          throw new Error(`catalog ${skuId}.stock must be a non-negative integer`);
        }
        return {
          spuId,
          productId: spuId,
          skuId,
          offerId: nonEmptyString(offer.offer_id, `catalog.${skuId}.offer_id`),
          title: nonEmptyString(sku.title, `catalog.${skuId}.title`),
          category,
          brand: nonEmptyString(spu.brand, `catalog.${spuId}.brand`),
          price,
          stock: stock as number,
          tags: catalogStrings(spu.tags, `catalog.${spuId}.tags`),
          ecosystem: ecosystem as CatalogProduct["ecosystem"],
          connectors: catalogStrings(sku.connectors, `catalog.${skuId}.connectors`),
          protocols: catalogStrings(sku.protocols, `catalog.${skuId}.protocols`),
          ...(typeof sku.max_power_watts === "number" ? { maxPowerWatts: sku.max_power_watts } : {}),
          sponsored: offer.sponsored === true,
          ...(typeof offer.ad_bid === "number" ? { adBid: offer.ad_bid } : {}),
          ...(typeof offer.ad_quality === "number" ? { adQuality: offer.ad_quality } : {}),
          catalogVersion,
          currency: "CNY",
          quoteVersion,
          quoteValidUntil: nonEmptyString(offer.valid_until, `catalog.${skuId}.valid_until`),
          dataSource: {
            source: "local_snapshot",
            sourceVersion: catalogVersion,
            providerId: pack.packId,
          },
        };
      });
    });
  });
}

const codeRoot = new URL(import.meta.url.includes("/dist/") ? "../../../" : "../../", import.meta.url);
export const DOMAIN_PACK_DATA_DIRECTORY = resolve(
  fileURLToPath(new URL("src/shoprec/data/", codeRoot)),
);
export const DOMAIN_PACK_REGISTRY = DomainPackRegistry.fromDirectory(DOMAIN_PACK_DATA_DIRECTORY);
export const DEFAULT_DOMAIN_PACK_ID = "normal-3c-v1";
export const NORMAL_3C_DOMAIN = DOMAIN_PACK_REGISTRY.get(DEFAULT_DOMAIN_PACK_ID);
export const SUPPORTED_PRODUCT_CATEGORIES = new Set(
  DOMAIN_PACK_REGISTRY.list().flatMap((pack) => pack.categories.map((item) => item.id)),
);

export function isSupportedProductCategory(
  value: unknown,
  pack: CommerceDomainPack = NORMAL_3C_DOMAIN,
): value is ProductCategory {
  return typeof value === "string" && pack.categories.some((item) => item.id === value);
}
