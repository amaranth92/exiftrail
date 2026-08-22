import assert from "node:assert/strict";
import exifr from "exifr";

const names = ["01-perth", "02-fremantle", "03-rottnest", "04-margaret-river", "05-albany"];

const noGpsTags = await exifr.parse("public/samples/00-no-gps.jpg", { gps: true, exif: true, tiff: true });
assert.equal(Number.isFinite(noGpsTags.latitude), false, "no-gps latitude should be missing");
assert.equal(Number.isFinite(noGpsTags.longitude), false, "no-gps longitude should be missing");

for (const name of names) {
  const tags = await exifr.parse(`public/samples/${name}.jpg`, { gps: true, exif: true, tiff: true });
  assert.equal(Number.isFinite(tags.latitude), true, `${name} latitude`);
  assert.equal(Number.isFinite(tags.longitude), true, `${name} longitude`);
  assert.equal(typeof (tags.DateTimeOriginal || tags["36867"]), "string", `${name} capture time`);
}

console.log("sample EXIF check passed");
