import { expect, test } from "@playwright/test";

test("loads sample EXIF photos and exports a WebM route", async ({ page }) => {
  await page.goto("/");

  await page.getByRole("button", { name: "Load sample EXIF photos" }).click();
  await expect(page.getByText("Route ready. Review points before export.")).toBeVisible();
  await expect(page.getByText("5 export points")).toBeVisible();
  await expect(page.getByText("5/6 with GPS")).toBeVisible();
  await expect(page.getByText("1 skipped")).toBeVisible();
  await expect(page.locator(".leaflet-overlay-pane path").first()).toBeVisible();

  await page.getByRole("button", { name: "Preview animation" }).click();
  await expect(page.getByRole("button", { name: "Export WebM" })).toBeEnabled();

  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("button", { name: "Export WebM" }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toBe("exiftrail-route.webm");
});
