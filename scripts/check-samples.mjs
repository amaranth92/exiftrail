import assert from "node:assert/strict";
import exifr from "exifr";

const names = ["01-perth", "02-fremantle", "03-rottnest", "04-margaret-river", "05-albany"];

for (const name of names) {
  const tags = await exifr.parse(`public/samples/${name}.jpg`, { gps: true, exif: true, tiff: true });
  assert.equal(Number.isFinite(tags.latitude), true, `${name} latitude`);
  assert.equal(Number.isFinite(tags.longitude), true, `${name} longitude`);
  assert.equal(typeof (tags.DateTimeOriginal || tags["36867"]), "string", `${name} capture time`);
}

console.log("sample EXIF check passed");
