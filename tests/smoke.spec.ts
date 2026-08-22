import { expect, test } from "@playwright/test";

const samplePhotos = [
  "public/samples/00-no-gps.jpg",
  "public/samples/01-perth.jpg",
  "public/samples/02-fremantle.jpg",
  "public/samples/03-rottnest.jpg",
  "public/samples/04-margaret-river.jpg",
  "public/samples/05-albany.jpg",
];

test("loads sample EXIF photos and exports a WebM route", async ({ page }) => {
  await page.goto("/");

  await page.locator('input[type="file"]').setInputFiles(samplePhotos);
  await expect(page.getByText("Route video preview is ready. Save it and post it anywhere.")).toBeVisible();
  await expect(page.getByText("5 route points")).toBeVisible();
  await expect(page.getByText("1 photos skipped without GPS")).toBeVisible();
  await expect(page.locator(".leaflet-overlay-pane path").first()).toBeVisible();
  await expect(page.getByRole("button", { name: "Save video" })).toBeEnabled();

  const downloadPromise = page.waitForEvent("download");
  await page.getByRole("button", { name: "Save video" }).click();
  const download = await downloadPromise;
  expect(download.suggestedFilename()).toMatch(/^exiftrail-route\.(webm|mp4)$/);
});

test("mobile flow is portrait friendly", async ({ page }) => {
  await page.setViewportSize({ width: 390, height: 844 });
  await page.goto("/");

  await expect(page.getByText("Allow photos and create video", { exact: true })).toBeVisible();
  await expect(page.getByText("No Google Timeline required.")).toBeVisible();
  await expect(page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).resolves.toBe(true);

  await page.locator('input[type="file"]').setInputFiles(samplePhotos);
  await expect(page.getByText("5 route points")).toBeVisible();
  await expect(page.locator(".leaflet-overlay-pane path").first()).toBeVisible();
  await expect(page.getByRole("button", { name: "Save video" })).toBeVisible();
  await expect(page.evaluate(() => document.documentElement.scrollWidth <= window.innerWidth + 1)).resolves.toBe(true);
});

test("date range limits the route points", async ({ page }) => {
  await page.goto("/");

  await page.getByLabel("From", { exact: true }).fill("2024-02-02");
  await page.getByLabel("To", { exact: true }).fill("2024-02-03");
  await page.locator('input[type="file"]').setInputFiles(samplePhotos);

  await expect(page.getByText("2 route points")).toBeVisible();
  await expect(page.getByText("3 outside date range")).toBeVisible();
  await expect(page.getByRole("button", { name: "Save video" })).toBeEnabled();
});
