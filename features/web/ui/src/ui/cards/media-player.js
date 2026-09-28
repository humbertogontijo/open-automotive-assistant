import { html, nothing } from "lit";
import { fmt } from "../../format.js";
import { t, entityValueLabel } from "../../i18n.js";
import { icon, controlShell } from "./shared.js";
import { OaaCard } from "./card-element.js";
import { slider } from "./slider.js";

export function mediaAttr(c, camel, snake) {
  if (c[camel] != null && c[camel] !== "") return c[camel];
  const attrs = c.attributes || {};
  if (attrs[camel] != null && attrs[camel] !== "") return attrs[camel];
  if (snake) {
    if (c[snake] != null && c[snake] !== "") return c[snake];
    if (attrs[snake] != null && attrs[snake] !== "") return attrs[snake];
  }
  return null;
}

function finite(v, fallback) {
  const n = Number(v);
  return v != null && v !== "" && !isNaN(n) ? n : fallback;
}

class OaaMediaPlayerCard extends OaaCard {
  renderCard(c) {
    const locked = this.locked;
    const playing = c.value === "playing";
    const title = mediaAttr(c, "mediaTitle", "media_title") || t("media_player.nothing", "Nothing playing");
    const sub = [mediaAttr(c, "mediaArtist", "media_artist"), mediaAttr(c, "mediaAlbum", "media_album")]
      .filter(Boolean)
      .join(" · ");
    const volMax = finite(mediaAttr(c, "volumeMax", "volume_max"), finite(c.max, null));
    const volMin = finite(mediaAttr(c, "volumeMin", "volume_min"), finite(c.min, null));
    const vol = finite(mediaAttr(c, "volume"), volMin);
    const transport = (cmd, name, label, cls = "") =>
      html`<wa-button
        class=${cls}
        appearance=${cls ? "filled" : "outlined"}
        variant=${cls ? "brand" : "neutral"}
        title=${label}
        ?disabled=${locked}
        @click=${() => this.send(cmd)}
        >${icon(name, label)}</wa-button
      >`;

    return controlShell(c, {
      restore: this.restore,
      pinnable: false,
      cls: "media-player-card " + (playing ? "is-playing" : ""),
      bodyCls: "media-player-body",
      iconName: c.icon || "sound",
      hint: entityValueLabel(c) || fmt(c.value),
      body: html`
        <div class="media-now">
          <div class="media-title">${title}</div>
          ${sub ? html`<div class="media-artist">${sub}</div>` : nothing}
        </div>
        <wa-button-group class="media-transport" label=${t("media_player.transport", "Transport")}>
          ${transport("previous", "previous", t("media_player.previous", "Previous"))}
          ${playing
            ? transport("pause", "pause", t("media_player.pause", "Pause"), "media-main")
            : transport("play", "play", t("media_player.play", "Play"), "media-main")}
          ${transport("next", "next", t("media_player.next", "Next"))}
        </wa-button-group>
        ${volMin != null && volMax != null
          ? slider({
              value: vol,
              min: volMin,
              max: volMax,
              suffix: "/" + volMax,
              label: t("media_player.volume", "Volume"),
              disabled: locked,
              onCommit: (v) => this.send("volume:" + Math.round(v)),
            })
          : nothing}
      `,
    });
  }
}
customElements.define("oaa-media-player-card", OaaMediaPlayerCard);
