import { test } from "node:test";
import assert from "node:assert/strict";
import {
  dayBounds,
  dayKeyFromMs,
  timelineRangeForDay,
  isLiveEdgeWall,
  LIVE_EDGE_TIP_MS,
  availableTimelineDays,
} from "../src/ui/dvr-timeline.js";

const b = dayBounds("2026-03-15");
const segs = [{ startUtcMs: b.start + 3_600_000, endUtcMs: b.start + 7_200_000 }];

test("dayBounds covers one historical day", () => {
  assert.equal(b.endFull - b.start, 86_400_000);
  assert.equal(dayKeyFromMs(b.start), "2026-03-15");
  assert.equal(b.scrubMax, b.endFull - 1);
});

test("timelineRangeForDay filters segments to the day", () => {
  const r = timelineRangeForDay("2026-03-15", { segments: segs }, b.start + 10_000_000);
  assert.equal(r.segs.length, 1);
  assert.equal(r.isToday, false);
});

test("availableTimelineDays lists recorded days and today", () => {
  const days = availableTimelineDays(segs);
  assert.ok(days.includes("2026-03-15"));
  assert.ok(days.includes(dayKeyFromMs(Date.now())));
});

test("live edge is only at today's tip", () => {
  const today = dayKeyFromMs(Date.now());
  const tr = timelineRangeForDay(today, { segments: segs }, Date.now());
  assert.equal(tr.isToday, true);
  assert.ok(isLiveEdgeWall(tr.scrubMax, tr, LIVE_EDGE_TIP_MS));
  assert.ok(!isLiveEdgeWall(tr.start, tr, LIVE_EDGE_TIP_MS));
});
