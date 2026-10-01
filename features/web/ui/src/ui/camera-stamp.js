/**
 * Per-frame wall-clock stamp for camera tiles. The car never draws on live or
 * recorded video; the client overlays the frame's capture time instead.
 */

/** RTP timestamps are car-clock ms × 90 mod 2^32 (live: the capture time itself). */
const RTP_PERIOD_MS = 4294967296 / 90;

/** Nearest car time (ms) to `nearMs` whose RTP timestamp (mod 2^32) is `rtpTs`. */
export function unwrapRtpMs(rtpTs, nearMs) {
  const base = Number(rtpTs) / 90;
  const k = Math.round((nearMs - base) / RTP_PERIOD_MS);
  return base + k * RTP_PERIOD_MS;
}

/**
 * @typedef {{ fromRtpMs: number, live?: boolean, rtpMs?: number, wallMs?: number, speed?: number }} ReplayAnchor
 */

/**
 * Capture time of a frame sent at car time `rtpMs`, through the replay anchor of
 * its epoch (the last one whose `fromRtpMs` ≤ `rtpMs`; `anchors` sorted by it).
 * Live epochs and frames before any anchor map to `rtpMs` itself.
 * @param {number} rtpMs
 * @param {ReplayAnchor[]} anchors
 */
export function wallFromRtp(rtpMs, anchors) {
  let a = null;
  for (let i = 0; i < anchors.length && anchors[i].fromRtpMs <= rtpMs; i++) a = anchors[i];
  if (!a || a.live) return rtpMs;
  return Number(a.wallMs) + (rtpMs - Number(a.rtpMs)) * (Number(a.speed) || 1);
}

function pad(n) {
  return n < 10 ? "0" + n : String(n);
}

/** Local "YYYY-MM-DD HH:mm:ss" (the format the car burns into exports). */
export function fmtStamp(ms) {
  const n = Number(ms);
  if (!(n > 0)) return "";
  const d = new Date(n);
  return (
    d.getFullYear() +
    "-" +
    pad(d.getMonth() + 1) +
    "-" +
    pad(d.getDate()) +
    " " +
    pad(d.getHours()) +
    ":" +
    pad(d.getMinutes()) +
    ":" +
    pad(d.getSeconds())
  );
}

/**
 * Call `onFrame(metadata)` for every presented frame of `video`
 * (requestVideoFrameCallback; a 4 Hz timer with `null` metadata where it is missing).
 * @param {HTMLVideoElement} video
 * @param {(metadata: VideoFrameCallbackMetadata | null) => void} onFrame
 * @returns {() => void} stop
 */
export function watchFrames(video, onFrame) {
  let stopped = false;
  if (typeof video.requestVideoFrameCallback === "function") {
    let handle = 0;
    const step = function (_now, metadata) {
      if (stopped) return;
      onFrame(metadata);
      handle = video.requestVideoFrameCallback(step);
    };
    handle = video.requestVideoFrameCallback(step);
    // rVFC is silent while paused or between sources; keep the stamp honest on seeks.
    const onSeeked = function () {
      onFrame(null);
    };
    video.addEventListener("seeked", onSeeked);
    return function () {
      stopped = true;
      video.removeEventListener("seeked", onSeeked);
      try {
        video.cancelVideoFrameCallback(handle);
      } catch (e) {}
    };
  }
  const timer = setInterval(function () {
    if (!stopped && video.readyState >= 2) onFrame(null);
  }, 250);
  return function () {
    stopped = true;
    clearInterval(timer);
  };
}
