"""HA Cloud node-face paths. Pure Python so tests run without Home Assistant."""

from __future__ import annotations

import re

NODE_PAIR_PATH = "/api/oaa_node/pair"
NODE_SESSION_PATH = "/api/oaa_node/session"
NODE_ARTIFACTS_PATH = "/api/oaa_node/artifacts"

HUB_PAIR_PATH = "/api/nodes/pair"
HUB_SESSION_PATH = "/api/nodes/session"
HUB_ARTIFACTS_PATH = "/api/nodes/artifacts"

_SHA256 = re.compile(r"^[0-9a-fA-F]{64}$")


def is_sha256(value: str | None) -> bool:
    return bool(value) and bool(_SHA256.match(value))


def node_public_urls(cloud_base: str | None) -> dict[str, str] | None:
    if not cloud_base:
        return None
    return {
        "pair": f"{cloud_base}{NODE_PAIR_PATH}",
        "session": f"{cloud_base}{NODE_SESSION_PATH}",
        "artifacts": f"{cloud_base}{NODE_ARTIFACTS_PATH}",
        "nodePublicUrl": cloud_base,
        "cloudPairPath": NODE_PAIR_PATH,
        "cloudSessionPath": NODE_SESSION_PATH,
        "cloudArtifactsPath": NODE_ARTIFACTS_PATH,
    }


def bearer_token(query_token: str | None, authorization: str | None) -> str:
    if query_token:
        return query_token
    auth = authorization or ""
    if auth.lower().startswith("bearer "):
        return auth[7:].strip()
    return ""
