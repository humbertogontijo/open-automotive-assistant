/**
 * Live mosaic via HLS (CMAF/fMP4) + hls.js. One <video>.
 * Timestamp is burned into frames on the device (live + DVR).
 */
import Hls from "hls.js/light";
import { appUrl } from "../base.js";

const LIVE_URL = appUrl("/api/dvr/live.m3u8");

/** @type {Hls|null} */
let hls = null;
/** @type {HTMLVideoElement|null} */
let activeVideo = null;
let playing = false;

export function isLivePlaying() {
  return playing && !!hls;
}

export function stopH264Live(video) {
  const v = video || activeVideo;
  playing = false;
  activeVideo = null;
  if (hls) {
    try {
      hls.destroy();
    } catch (e) {}
    hls = null;
  }
  if (v) {
    try {
      v.pause();
      v.removeAttribute("src");
      v.load();
    } catch (e) {}
  }
}

/**
 * @param {HTMLVideoElement} video
 * @returns {Promise<boolean>}
 */
export async function startH264Live(video) {
  if (playing && activeVideo === video && hls) {
    return true;
  }
  stopH264Live(video);
  if (!video) return false;
  if (!Hls.isSupported()) {
    console.warn("HLS.js not supported");
    return false;
  }

  // Preview is started by cameras.js / RoutesDvr; do not POST again here.

  activeVideo = video;
  playing = true;
  video.muted = true;
  video.playsInline = true;
  video.setAttribute("playsinline", "");
  video.setAttribute("webkit-playsinline", "");

  const player = new Hls({
    enableWorker: false,
    lowLatencyMode: true,
    // Catch up by speeding playback instead of hard-seeking (LL mode only).
    maxLiveSyncPlaybackRate: 1.25,
    // Omit liveSyncDurationCount so playlist HOLD-BACK drives the target.
    liveMaxLatencyDuration: 5,
    liveSyncDuration: 2,
    maxBufferLength: 6,
    maxMaxBufferLength: 10,
    backBufferLength: 4,
    liveDurationInfinity: true,
  });
  hls = player;

  return await new Promise(function (resolve) {
    let settled = false;
    function done(ok) {
      if (settled) return;
      settled = true;
      resolve(!!ok);
    }
    player.on(Hls.Events.MANIFEST_PARSED, function () {
      video.play().catch(function () {});
      done(true);
    });
    player.on(Hls.Events.ERROR, function (_evt, data) {
      if (!data || !data.fatal) return;
      console.warn("hls fatal", data.type, data.details);
      try {
        player.destroy();
      } catch (e) {}
      if (hls === player) hls = null;
      playing = false;
      done(false);
    });
    player.loadSource(LIVE_URL);
    player.attachMedia(video);
    setTimeout(function () {
      if (!settled) done(false);
    }, 15000);
  });
}
