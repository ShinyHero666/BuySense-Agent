import { expect, test, type Route } from "@playwright/test";

test("an engineer can complete the guided offline decision journey", async ({ page }) => {
  await page.goto("/");

  const guide = page.getByRole("dialog", { name: "第一次使用，从一条购买需求开始。" });
  await expect(guide).toBeVisible();
  await expect(guide).toContainText("离线 Replay");
  await expect(guide.getByRole("link", { name: /完整新生指南/ })).toHaveAttribute("href", /START_HERE\.md/);
  await guide.getByRole("button", { name: "开始探索" }).click();

  const domainPack = page.getByLabel("领域包");
  await expect(domainPack).toHaveValue("normal-3c-v1");
  await expect(domainPack.locator('option[value="outdoor-camping-v1"]')).toHaveCount(1);
  await expect(page.getByRole("button", { name: "套装决策" })).toBeVisible();
  const createRequest = page.waitForRequest((request) =>
    request.method() === "POST" && new URL(request.url()).pathname === "/api/v2/runs"
  );
  await page.getByRole("button", { name: "开始决策" }).click();
  expect((await createRequest).postDataJSON()).toMatchObject({ domainPackId: "normal-3c-v1" });
  await expect(page.locator(".run-strip strong")).toHaveText("completed", { timeout: 20_000 });
  await expect(page.locator(".bundle-items article")).toHaveCount(3);
  await expect(page.getByText("独立审核通过")).toBeVisible();
  await expect(page.locator(".run-metadata")).toContainText("normal-3c-v1");
  await expect(page.locator(".run-metadata")).toContainText("commerce-decision-v1");

  await page.getByRole("button", { name: /协作拓扑/ }).click();
  await expect(page.locator(".event-ledger article").first()).toBeVisible();
  await expect(page.getByText(/委派任务|开始任务/).first()).toBeVisible();

  await page.getByRole("button", { name: /质量与运行/ }).click();
  await expect(page.getByText("Recall@10")).toBeVisible();
  await expect(page.getByText("服务健康")).toBeVisible();
  for (const component of ["catalog", "reviews", "pricing"]) {
    const source = page.getByTestId(`retail-source-${component}`);
    await expect(source).toContainText("本地快照");
    await expect(source).toContainText("static");
    await expect(source).toContainText("正常");
    await expect(source.getByText("已降级到本地快照")).toHaveCount(0);
  }
});

test("the quality view exposes an active retail fallback instead of presenting it as remote data", async ({ page }) => {
  await page.route("**/health", async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        status: "DEGRADED",
        model: { mode: "replay", model: "deterministic-replay", status: "offline", latencyMs: 0 },
        dataPlane: {
          mode: "python",
          status: "degraded",
          latencyMs: 4,
          retailSources: {
            catalog: {
              configuredMode: "http",
              effectiveSource: "remote_provider",
              status: "up",
              fallbackActive: false,
              version: "catalog-remote-42",
              providerId: "retail-fixture",
              effectiveProviderId: "retail-fixture",
              telemetry: { requests: 3, errors: 0, fallbacks: 0 },
            },
            reviews: {
              configuredMode: "http",
              effectiveSource: "local_snapshot",
              status: "degraded",
              fallbackActive: true,
              version: "reviews-local-v1",
              providerId: "retail-fixture",
              effectiveProviderId: "normal-3c-v1",
              telemetry: { requests: 2, errors: 1, fallbacks: 1 },
              lastErrorCode: "upstream_timeout",
            },
            pricing: {
              configuredMode: "http",
              effectiveSource: "remote_provider",
              status: "up",
              fallbackActive: false,
              version: "pricing-remote-17",
              providerId: "retail-fixture",
              effectiveProviderId: "retail-fixture",
              telemetry: { requests: 3, errors: 0, fallbacks: 0 },
            },
          },
        },
        agentFramework: "pi-agent-core",
        paymentEnabled: false,
      }),
    });
  });

  await page.goto("/");
  await page.getByRole("dialog").getByRole("button", { name: "开始探索" }).click();
  await page.getByRole("button", { name: /质量与运行/ }).click();

  const catalog = page.getByTestId("retail-source-catalog");
  await expect(catalog).toContainText("远端 Provider");
  await expect(catalog).toContainText("catalog-remote-42");
  await expect(catalog).toContainText("retail-fixture");

  const reviews = page.getByTestId("retail-source-reviews");
  await expect(reviews).toContainText("本地快照");
  await expect(reviews).toContainText("降级");
  await expect(reviews).toContainText("已降级到本地快照");
  await expect(reviews).toContainText("upstream_timeout");
  await expect(reviews).toContainText("请求 2 · 错误 1 · 降级 1");
});

test("the engineering workbench selects and audits the camping pack per run", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("dialog").getByRole("button", { name: "开始探索" }).click();

  const domainPack = page.getByLabel("领域包");
  await domainPack.selectOption("outdoor-camping-v1");
  await expect(domainPack).toHaveValue("outdoor-camping-v1");
  await expect(page.locator("#demand")).toHaveValue(/露营/);

  const createRequest = page.waitForRequest((request) =>
    request.method() === "POST" && new URL(request.url()).pathname === "/api/v2/runs"
  );
  await page.getByRole("button", { name: "开始决策" }).click();
  expect((await createRequest).postDataJSON()).toMatchObject({
    domainPackId: "outdoor-camping-v1",
  });

  await expect(page.locator(".run-strip strong")).toHaveText("completed", { timeout: 20_000 });
  const bundleItems = page.locator(".bundle-items article");
  await expect(bundleItems).toHaveCount(3);
  await expect(bundleItems.filter({ hasText: "camp_stove" })).toHaveCount(1);
  await expect(bundleItems.filter({ hasText: "fuel_canister" })).toHaveCount(1);
  await expect(bundleItems.filter({ hasText: "cookware" })).toHaveCount(1);
  await expect(page.locator(".run-metadata")).toContainText("outdoor-camping-v1");
  await expect(page.locator(".run-metadata")).toContainText("commerce-decision-v1");
  const proposalRunId = await page.locator(".run-strip small").textContent();
  expect(proposalRunId).toBeTruthy();

  await domainPack.selectOption("normal-3c-v1");
  const confirmationRequests: Array<{
    idempotencyKey: string | undefined;
    body: Record<string, unknown>;
  }> = [];
  const delayConfirmation = async (route: Route) => {
    const request = route.request();
    const body = request.postDataJSON() as Record<string, unknown>;
    if (request.method() === "POST" && body.confirmed === true) {
      confirmationRequests.push({
        idempotencyKey: request.headers()["idempotency-key"],
        body,
      });
      await new Promise((resolve) => setTimeout(resolve, 400));
    }
    await route.continue();
  };
  await page.route("**/api/v2/runs", delayConfirmation);
  const confirmationResponse = page.waitForResponse((response) =>
    response.request().method() === "POST" &&
    new URL(response.url()).pathname === "/api/v2/runs"
  );
  const confirmButton = page.getByRole("button", { name: "确认生成草案" });
  await confirmButton.dblclick({ delay: 20 });
  await expect(confirmButton).toBeDisabled();
  const confirmed = await confirmationResponse;
  expect(confirmationRequests).toHaveLength(1);
  expect(confirmationRequests[0]).toEqual({
    idempotencyKey: `confirm-${proposalRunId}`,
    body: expect.objectContaining({
      confirmed: true,
      domainPackId: "outdoor-camping-v1",
      proposalRunId,
    }),
  });
  expect(confirmed.request().postDataJSON()).toMatchObject({
    confirmed: true,
    domainPackId: "outdoor-camping-v1",
    proposalRunId,
  });
  const confirmationRun = await confirmed.json() as { runId: string };
  await page.unroute("**/api/v2/runs", delayConfirmation);
  await expect(page.locator(".run-strip small")).toHaveText(confirmationRun.runId);
  await expect(page.locator(".run-strip strong")).toHaveText("completed", { timeout: 20_000 });
  await expect(page.locator(".run-metadata")).toContainText("outdoor-camping-v1");
  await expect(page.locator(".run-strip")).toContainText("购物车草案");
  await expect(page.locator(".run-strip")).toContainText("未支付");

  const completedRun = await page.evaluate(async (runId) => {
    const response = await fetch(`/api/v2/runs/${encodeURIComponent(runId)}`);
    return response.json();
  }, confirmationRun.runId) as { result: { cartDraft: { draftId: string } } };
  const replay = await page.evaluate(async ({ body, idempotencyKey }) => {
    const response = await fetch("/api/v2/runs", {
      method: "POST",
      headers: {
        "content-type": "application/json",
        "idempotency-key": idempotencyKey,
      },
      body: JSON.stringify(body),
    });
    return {
      status: response.status,
      payload: await response.json(),
    };
  }, {
    idempotencyKey: `confirm-${proposalRunId}`,
    body: confirmationRequests[0]!.body,
  }) as { status: number; payload: { runId: string; idempotentReplay: boolean } };
  expect(replay).toMatchObject({
    status: 200,
    payload: {
      runId: confirmationRun.runId,
      idempotentReplay: true,
    },
  });
  const replayedRun = await page.evaluate(async (runId) => {
    const response = await fetch(`/api/v2/runs/${encodeURIComponent(runId)}`);
    return response.json();
  }, replay.payload.runId) as { result: { cartDraft: { draftId: string } } };
  expect(replayedRun.result.cartDraft.draftId).toBe(completedRun.result.cartDraft.draftId);

  await page.reload();
  await expect(page.getByLabel("领域包")).toHaveValue("outdoor-camping-v1");
  await expect(page.locator(".run-metadata")).toContainText("outdoor-camping-v1");
  await expect(page.locator(".run-strip small")).toHaveText(confirmationRun.runId);
  await expect(page.locator(".run-strip em")).toHaveText(/[1-9][0-9]* events/);
});

test("a failed confirmation remains retryable after refresh with a new attempt key", async ({ page }) => {
  await page.goto("/");
  await page.getByRole("dialog").getByRole("button", { name: "开始探索" }).click();
  await page.getByRole("button", { name: "开始决策" }).click();
  await expect(page.locator(".run-strip strong")).toHaveText("completed", { timeout: 20_000 });
  const proposalRunId = await page.locator(".run-strip small").textContent();
  expect(proposalRunId).toBeTruthy();

  const failedRunId = "run_ui_failed_confirmation";
  const timestamp = "2026-08-09T12:00:00.000Z";
  const stableKey = `confirm-${proposalRunId}`;
  let firstConfirmationBody: Record<string, unknown> | null = null;
  let simulatedFailure = false;
  const confirmationRoute = async (route: Route) => {
    const request = route.request();
    const body = request.postDataJSON() as Record<string, unknown>;
    if (!simulatedFailure && body.confirmed === true) {
      simulatedFailure = true;
      firstConfirmationBody = body;
      expect(request.headers()["idempotency-key"]).toBe(stableKey);
      await route.fulfill({
        status: 202,
        contentType: "application/json",
        body: JSON.stringify({
          runId: failedRunId,
          domainPackId: "normal-3c-v1",
          workflowId: "commerce-decision-v1",
          status: "queued",
          eventsUrl: `/api/v2/runs/${failedRunId}/events`,
          runUrl: `/api/v2/runs/${failedRunId}`,
          idempotentReplay: false,
        }),
      });
      return;
    }
    await route.continue();
  };
  await page.route("**/api/v2/runs", confirmationRoute);
  await page.route(`**/api/v2/runs/${failedRunId}`, async (route) => {
    await route.fulfill({
      status: 200,
      contentType: "application/json",
      body: JSON.stringify({
        runId: failedRunId,
        identityId: "anon-ui-retry",
        sessionId: "session-ui-retry",
        domainPackId: "normal-3c-v1",
        workflowId: "commerce-decision-v1",
        status: "failed",
        message: "确认生成购物车草案",
        confirmed: true,
        proposalRunId,
        idempotencyKey: stableKey,
        errorCode: "agent_execution_failed",
        createdAt: timestamp,
        updatedAt: timestamp,
        cancelRequested: false,
      }),
    });
  });
  await page.route(`**/api/v2/runs/${failedRunId}/events`, async (route) => {
    const event = {
      eventId: "event_ui_failed_confirmation",
      runId: failedRunId,
      sequence: 1,
      eventType: "run_failed",
      timestamp,
      schemaVersion: "2.0",
      payload: { errorType: "Error" },
    };
    await route.fulfill({
      status: 200,
      headers: { "content-type": "text/event-stream; charset=utf-8" },
      body: `id: 1\nevent: run_failed\ndata: ${JSON.stringify(event)}\n\n`,
    });
  });

  await page.getByRole("button", { name: "确认生成草案" }).click();
  await expect(page.locator(".run-strip strong")).toHaveText("failed");
  await expect(page.getByRole("button", { name: "重试确认" })).toBeVisible();
  expect(firstConfirmationBody).toMatchObject({
    confirmed: true,
    proposalRunId,
    domainPackId: "normal-3c-v1",
  });

  await page.reload();
  await expect(page.locator(".run-strip strong")).toHaveText("failed");
  const retryButton = page.getByRole("button", { name: "重试确认" });
  await expect(retryButton).toBeVisible();
  const retryRequestPromise = page.waitForRequest((request) => {
    if (request.method() !== "POST" || new URL(request.url()).pathname !== "/api/v2/runs") {
      return false;
    }
    const body = request.postDataJSON() as Record<string, unknown>;
    return body.confirmed === true && request.headers()["idempotency-key"] !== stableKey;
  });
  await retryButton.click();
  const retryRequest = await retryRequestPromise;
  expect(retryRequest.headers()["idempotency-key"]).toMatch(
    new RegExp(`^confirm-${proposalRunId}-attempt-`),
  );
  expect(retryRequest.postDataJSON()).toMatchObject({
    confirmed: true,
    proposalRunId,
    domainPackId: "normal-3c-v1",
  });
  await expect(page.locator(".run-strip strong")).toHaveText("completed", { timeout: 20_000 });
  await expect(page.locator(".run-strip")).toContainText("购物车草案");
  await expect(page.locator(".run-strip")).toContainText("未支付");
});
