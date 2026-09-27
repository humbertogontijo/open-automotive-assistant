"""Cloud bridge path helpers (no Home Assistant install required)."""

from __future__ import annotations

import importlib.util
from pathlib import Path

_PATH = Path(__file__).resolve().parents[1] / "custom_components" / "open_automotive_assistant" / "cloud_paths.py"
_spec = importlib.util.spec_from_file_location("oaa_cloud_paths", _PATH)
cloud_paths = importlib.util.module_from_spec(_spec)
_spec.loader.exec_module(cloud_paths)


def test_node_public_urls():
    urls = cloud_paths.node_public_urls("https://example.ui.nabu.casa")
    assert urls is not None
    assert urls["pair"] == "https://example.ui.nabu.casa/api/oaa_node/pair"
    assert urls["session"] == "https://example.ui.nabu.casa/api/oaa_node/session"
    assert urls["artifacts"] == "https://example.ui.nabu.casa/api/oaa_node/artifacts"
    assert urls["cloudArtifactsPath"] == cloud_paths.NODE_ARTIFACTS_PATH


def test_node_public_urls_none():
    assert cloud_paths.node_public_urls(None) is None
    assert cloud_paths.node_public_urls("") is None


def test_is_sha256():
    assert cloud_paths.is_sha256("a" * 64)
    assert cloud_paths.is_sha256("0123456789ABCDEF" * 4)
    assert not cloud_paths.is_sha256("a" * 63)
    assert not cloud_paths.is_sha256("../" + "a" * 61)
    assert not cloud_paths.is_sha256(None)


def test_bearer_token():
    assert cloud_paths.bearer_token("q", "Bearer h") == "q"
    assert cloud_paths.bearer_token(None, "Bearer  h ") == "h"
    assert cloud_paths.bearer_token("", "bearer h") == "h"
    assert cloud_paths.bearer_token(None, "Basic x") == ""
    assert cloud_paths.bearer_token(None, None) == ""
