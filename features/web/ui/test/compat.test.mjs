import { test } from "node:test";
import assert from "node:assert/strict";
import { compareVersions, carVersionSkew, carServesPage } from "../src/compat.js";

test("compareVersions orders numerically and ignores suffixes", () => {
  assert.equal(compareVersions("0.1.10", "0.1.9"), 1);
  assert.equal(compareVersions("0.1.7", "0.1.7"), 0);
  assert.equal(compareVersions("0.1", "0.1.0"), 0);
  assert.equal(compareVersions("0.1.7-dev", "0.1.8"), -1);
  assert.equal(compareVersions("1.0.0+abc", "1.0.0"), 0);
});

test("compareVersions is null for missing or non-numeric versions", () => {
  assert.equal(compareVersions(undefined, "0.1.7"), null);
  assert.equal(compareVersions("0.1.7", ""), null);
  assert.equal(compareVersions("dev", "0.1.7"), null);
});

test("carVersionSkew", () => {
  assert.equal(carVersionSkew("0.1.8", "0.1.7"), "newer");
  assert.equal(carVersionSkew("0.1.6", "0.1.7"), "older");
  assert.equal(carVersionSkew("0.1.7", "0.1.7"), null);
  assert.equal(carVersionSkew(null, "0.1.7"), null);
});

test("carServesPage uses the car's list, else the pages every car had", () => {
  assert.equal(carServesPage("cameras", { pages: ["home", "cameras"] }), true);
  assert.equal(carServesPage("lab", { pages: ["home", "cameras"] }), false);
  assert.equal(carServesPage("lab", {}), true);
  assert.equal(carServesPage("lab", null), true);
  assert.equal(carServesPage("some_future_page", {}), false);
});
