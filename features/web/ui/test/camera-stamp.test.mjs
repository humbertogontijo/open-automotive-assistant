import { test } from "node:test";
import assert from "node:assert/strict";
import { fmtStamp, unwrapRtpMs } from "../src/ui/camera-stamp.js";
import { orderRoles } from "../src/ui/dvr-timeline.js";

const PERIOD = 4294967296 / 90;

function rtpOf(ms) {
  return (ms * 90) % 4294967296;
}

test("unwrapRtpMs recovers capture time near the car clock", () => {
  const capture = 1_790_000_000_123;
  assert.ok(Math.abs(unwrapRtpMs(rtpOf(capture), capture + 150) - capture) < 1);
  assert.ok(Math.abs(unwrapRtpMs(rtpOf(capture), capture - 150) - capture) < 1);
});

test("unwrapRtpMs picks the nearest period across a wrap", () => {
  const k = Math.ceil(1_790_000_000_000 / PERIOD);
  const wrap = k * PERIOD;
  const before = wrap - 40;
  const after = wrap + 40;
  assert.ok(Math.abs(unwrapRtpMs(rtpOf(before), after) - before) < 1);
  assert.ok(Math.abs(unwrapRtpMs(rtpOf(after), before) - after) < 1);
});

test("fmtStamp matches the exported burn-in format", () => {
  const ms = new Date(2026, 9, 1, 7, 5, 9).getTime();
  assert.equal(fmtStamp(ms), "2026-10-01 07:05:09");
  assert.equal(fmtStamp(0), "");
});

test("orderRoles sorts front, right, rear, left and drops duplicates", () => {
  assert.deepEqual(orderRoles(["rear", "cam4", "left", "front", "rear", "", "right"]), [
    "front",
    "right",
    "rear",
    "left",
    "cam4",
  ]);
});
