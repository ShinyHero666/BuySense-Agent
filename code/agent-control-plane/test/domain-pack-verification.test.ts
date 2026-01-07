import assert from "node:assert/strict";
import { readFileSync } from "node:fs";
import { dirname, join } from "node:path";
import test from "node:test";
import { SearchAdsRecsBuyerAgent } from "../src/buyer-agent.js";
import { DOMAIN_PACK_REGISTRY } from "../src/domain-pack.js";

test("VESR: every registered Domain Pack completes its representative workflow", async (context) => {
  const packs = DOMAIN_PACK_REGISTRY.list();
  let verified = 0;
  let verifiedCases = 0;
  for (const pack of packs) {
    await context.test(pack.packId, async () => {
      const cases = pack.assets.queries
        ? JSON.parse(readFileSync(join(dirname(pack.sourcePath), pack.assets.queries), "utf8")) as Array<{
            case_id: string;
            message: string;
            expected_intent: string;
            expected_categories: string[];
            sponsored_allowed: boolean;
            relevant_spu_ids: string[];
          }>
        : pack.exampleQueries.map((message, index) => ({
            case_id: `${pack.packId}-example-${index + 1}`,
            message,
            expected_intent: "",
            expected_categories: index === 0 ? [...pack.defaultBundleCategories] : [],
            sponsored_allowed: !message.includes("不要广告"),
            relevant_spu_ids: [],
          }));
      assert.ok(cases.length > 0);
      for (const [index, fixture] of cases.entries()) {
        const agent = new SearchAdsRecsBuyerAgent({ domain: pack });
        const reply = await agent.handle({
          sessionId: `vesr-${pack.packId}-${fixture.case_id}`,
          userId: "vesr-ci",
          message: fixture.message,
        });
        assert.equal(reply.phase, "proposal", fixture.case_id);
        assert.equal(reply.decision?.critique.verdict, "approved", fixture.case_id);
        assert.equal(reply.decision?.domainPackId, pack.packId, fixture.case_id);
        assert.equal(reply.decision?.workflowId, pack.workflowId, fixture.case_id);
        if (fixture.expected_intent) {
          assert.equal(reply.decision?.plan.intent, fixture.expected_intent, fixture.case_id);
        }
        assert.equal(
          reply.decision?.plan.sponsoredAllowed,
          fixture.sponsored_allowed,
          fixture.case_id,
        );
        const selected = new Set(
          reply.decision?.bundle.items.map((item) => item.product.category) ?? [],
        );
        const expectedCategories = index === 0
          ? new Set([...fixture.expected_categories, ...pack.defaultBundleCategories])
          : new Set(fixture.expected_categories);
        assert.ok(
          [...expectedCategories].every((category) => selected.has(category)),
          `${fixture.case_id}: missing expected bundle category`,
        );
        const selectedSpuIds = new Set(
          reply.decision?.bundle.items.map((item) => item.product.spuId) ?? [],
        );
        assert.ok(
          fixture.relevant_spu_ids.every((spuId) => selectedSpuIds.has(spuId)),
          `${fixture.case_id}: missing relevant SPU in selected bundle`,
        );
        assert.ok(reply.decision?.trace.some((record) =>
          record.event === "run_started" &&
          record.detail.domainPackId === pack.packId &&
          record.detail.workflowId === pack.workflowId
        ));
        verifiedCases += 1;
      }
      verified += 1;
    });
  }
  context.diagnostic(
    `VESR ${verified}/${packs.length} = ${verified / packs.length}; cases=${verifiedCases}`,
  );
  assert.equal(verified, packs.length);
});
