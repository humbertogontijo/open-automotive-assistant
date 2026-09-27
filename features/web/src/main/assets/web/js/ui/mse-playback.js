/**
 * Streams a DVR recording (fMP4 over the WebRTC data channel) into a <video>
 * via Media Source Extensions. Media time equals source-file time
 * (SourceBuffer.timestampOffset), so `video.currentTime` behaves like the
 * local progressive-MP4 player.
 *
 * `session` is a webrtc-session MediaSession: `transfers` (reqId → handlers
 * `onMeta(meta)`, `onChunk(bytes, eof)`, `onError(e)`) and `send(msg)`.
 */
const BACK_BUFFER_SEC = 30;

export class MsePlayback {
  constructor(session, video, reqId, onError) {
    this.session = session;
    this.video = video;
    this.reqId = reqId;
    this.onError = onError;
    this.queue = [];
    this.ms = null;
    this.sb = null;
    this.url = "";
    this.meta = null;
    this.eof = false;
    this.closed = false;
    this.seekPending = false;
    const self = this;
    this._pumpBound = function () {
      self._pump();
    };
    this._onSeeking = function () {
      self._maybeRestart();
    };
    this._onTime = function () {
      self._evict();
    };
  }

  start(name, offsetMs) {
    const self = this;
    this.session.transfers.set(this.reqId, {
      kind: "playback",
      onMeta: function (m) {
        self._onMeta(m);
      },
      onChunk: function (payload, eof) {
        if (payload.byteLength) self.queue.push({ data: payload });
        if (eof) self.queue.push({ eof: true });
        self._pump();
      },
      onError: function (e) {
        self._fail(e);
      },
    });
    this.video.addEventListener("seeking", this._onSeeking);
    this.video.addEventListener("timeupdate", this._onTime);
    this.session.send({
      type: "playback_open",
      reqId: this.reqId,
      mode: "file",
      name: name,
      offsetMs: Math.max(0, Math.floor(offsetMs || 0)),
    });
  }

  _onMeta(m) {
    if (this.closed) return;
    this.meta = m;
    this.eof = false;
    this.seekPending = false;
    this.queue.push({ reset: m });
    if (!this.ms) {
      if (!window.MediaSource || !MediaSource.isTypeSupported(m.mime)) {
        this._fail(new Error("This browser cannot play " + m.mime));
        return;
      }
      const ms = new MediaSource();
      this.ms = ms;
      this.url = URL.createObjectURL(ms);
      const self = this;
      ms.addEventListener(
        "sourceopen",
        function () {
          if (self.closed) return;
          try {
            self.sb = ms.addSourceBuffer(m.mime);
            self.sb.mode = "segments";
            self.sb.addEventListener("updateend", self._pumpBound);
          } catch (e) {
            self._fail(e);
            return;
          }
          self._pump();
        },
        { once: true },
      );
      try {
        this.video.removeAttribute("src");
      } catch (e) {}
      this.video.srcObject = null;
      this.video.src = this.url;
    }
    this._pump();
  }

  _pump() {
    const sb = this.sb;
    const ms = this.ms;
    if (!sb || !ms || sb.updating || this.closed) return;
    while (this.queue.length) {
      const item = this.queue[0];
      if (item.reset) {
        if (!item.cleared) {
          item.cleared = true;
          try {
            // Previous run may have been cut mid-fragment.
            if (ms.readyState === "open") sb.abort();
            if (sb.buffered.length) {
              sb.remove(0, sb.buffered.end(sb.buffered.length - 1) + 1);
              return;
            }
          } catch (e) {}
        }
        this.queue.shift();
        try {
          sb.timestampOffset = (Number(item.reset.startMs) || 0) / 1000;
        } catch (e) {}
        const dur = Number(item.reset.durationMs) || 0;
        if (dur > 0 && ms.readyState === "open") {
          try {
            ms.duration = dur / 1000;
          } catch (e) {}
        }
        continue;
      }
      if (item.eof) {
        this.queue.shift();
        this.eof = true;
        if (ms.readyState === "open") {
          try {
            ms.endOfStream();
          } catch (e) {}
        }
        continue;
      }
      this.queue.shift();
      try {
        sb.appendBuffer(item.data);
      } catch (e) {
        if (e && e.name === "QuotaExceededError") {
          this.queue.unshift(item);
          this._evict(true);
        } else {
          this._fail(e);
        }
      }
      return;
    }
  }

  _evict(force) {
    const sb = this.sb;
    if (!sb || sb.updating || !sb.buffered.length) return;
    const cut = this.video.currentTime - BACK_BUFFER_SEC;
    const start = sb.buffered.start(0);
    if (cut > start + (force ? 0 : 5)) {
      try {
        sb.remove(start, cut);
      } catch (e) {}
    }
  }

  /** Seek outside what the car has sent → ask it to restart at the new offset. */
  _maybeRestart() {
    if (!this.meta || this.closed || this.seekPending) return;
    const t = this.video.currentTime;
    const sb = this.sb;
    let bufEnd = (Number(this.meta.startMs) || 0) / 1000;
    if (sb) {
      for (let i = 0; i < sb.buffered.length; i++) {
        if (t >= sb.buffered.start(i) && t <= sb.buffered.end(i)) return;
        bufEnd = Math.max(bufEnd, sb.buffered.end(i));
      }
    }
    const startSec = (Number(this.meta.startMs) || 0) / 1000;
    if (t >= startSec && t <= bufEnd + 8 && !this.eof) return;
    this.seekPending = true;
    try {
      this.session.send({ type: "playback_seek", reqId: this.reqId, offsetMs: Math.floor(t * 1000) });
    } catch (e) {
      this._fail(e);
    }
  }

  _fail(e) {
    if (this.closed) return;
    if (typeof this.onError === "function") this.onError(e);
  }

  close() {
    if (this.closed) return;
    this.closed = true;
    this.session.transfers.delete(this.reqId);
    try {
      this.session.send({ type: "playback_close", reqId: this.reqId });
    } catch (e) {}
    this.video.removeEventListener("seeking", this._onSeeking);
    this.video.removeEventListener("timeupdate", this._onTime);
    if (this.sb) this.sb.removeEventListener("updateend", this._pumpBound);
    if (this.url) {
      if (this.video.src === this.url) {
        try {
          this.video.pause();
          this.video.removeAttribute("src");
          this.video.load();
        } catch (e) {}
      }
      URL.revokeObjectURL(this.url);
    }
    this.queue = [];
    this.sb = null;
    this.ms = null;
  }
}
