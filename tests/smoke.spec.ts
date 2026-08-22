import { expect, test } from "@playwright/test";

test("loads sample EXIF photos and exports a WebM route", async ({ page }) => {
  await page.goto("/");

  await page.getByRole("button", { name: "Test EXIF sample" }).click();
  await expect(page.getByText("Route ready. Review points before export.")).toBeVisible();
  await expect(page.getByText("5 export points")).toBeVisible();
  await expect(page.getByText("5/6 with GPS")).toBeVisible();
  await expect(page.getByText("1 skipped")).toBeVisible();
  await expect(page.locator(".leaflet-overlay-pane path").first()).toBeVisible();

  await page.getByRole("button", { name: "Preview" }).click();
  await expect(page.getByRole("button", { name: "Save / share video" })).toBeEnabled();

  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("button", { name: "Save / share video" }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toMatch(/^exiftrail-route\.(webm|mp4)$/);
});

test("mobile flow is portrait friendly", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");

  await expect(page.getByText("Select travel photos", { exact: true })).toBeVisible();
  await expect(page.getByText("Save / share video")).toBeVisible();
  await expect(page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).resolves.toBe(true);

  await page.getByRole("button", { name: "Test EXIF sample" }).click();
  await expect(page.getByText("5 export points")).toBeVisible();
  await expect(page.locator(".leaflet-overlay-pane path").first()).toBeVisible();
  await expect(page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).resolves.toBe(true);
});
