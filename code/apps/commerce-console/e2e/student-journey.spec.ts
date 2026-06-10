import { expect, test } from "@playwright/test";

test("a first-time student can complete the guided offline decision journey", async ({ page }) => {
  await page.goto("/");

  const guide = page.getByRole("dialog", { name: "第一次使用，从一条购买需求开始。" });
  await expect(guide).toBeVisible();
  await expect(guide).toContainText("离线 Replay");
  await expect(guide.getByRole("link", { name: /完整新生指南/ })).toHaveAttribute("href", /START_HERE\.md/);
  await guide.getByRole("button", { name: "开始探索" }).click();

  await expect(page.getByRole("button", { name: "套装决策" })).toBeVisible();
  await page.getByRole("button", { name: "开始决策" }).click();
  await expect(page.locator(".run-strip strong")).toHaveText("completed", { timeout: 20_000 });
  await expect(page.locator(".bundle-items article")).toHaveCount(3);
  await expect(page.getByText("独立审核通过")).toBeVisible();

  await page.getByRole("button", { name: /协作拓扑/ }).click();
  await expect(page.locator(".event-ledger article").first()).toBeVisible();
  await expect(page.getByText(/委派任务|开始任务/).first()).toBeVisible();

  await page.getByRole("button", { name: /质量与运行/ }).click();
  await expect(page.getByText("Recall@10")).toBeVisible();
  await expect(page.getByText("服务健康")).toBeVisible();
});
