"""Reviewer policy subsystem of the SignalHarvester agent pipeline."""

from __future__ import annotations

import ipaddress
import json
import re
import urllib.error
import urllib.parse
import urllib.request
from pathlib import Path
from typing import Any

def _safe_reviewer_endpoint(value: str) -> bool:
    """Reject credential-bearing, local and ambiguous HTTP endpoints in reviewed policy."""
    if not isinstance(value, str) or any(ch.isspace() for ch in value):
        return False
    try:
        parts = urllib.parse.urlsplit(value)
        hostname = parts.hostname
        if (parts.scheme != "https" or not hostname or parts.username is not None
                or parts.password is not None or parts.query or parts.fragment
                or parts.port not in (None, 443) or value != parts.geturl()):
            return False
        if (hostname == "localhost" or hostname.endswith(".localhost")
                or hostname.endswith((".local", ".internal", ".test", ".invalid"))
                or "." not in hostname or not re.fullmatch(r"[a-z0-9.-]+", hostname)):
            return False
        try:
            ipaddress.ip_address(hostname)
        except ValueError:
            pass
        else:
            return False
        return (parts.path.endswith("/chat/completions")
                and parts.path.startswith("/") and ".." not in parts.path)
    except ValueError:
        return False


def _reviewer_endpoint_registry() -> dict[str, str]:
    """Load a separately reviewed URL-to-request-profile trust registry."""
    registry = json.loads((Path(__file__).resolve().parent.parent / "reviewer-endpoints.json").read_text(encoding="utf-8"))
    if (type(registry) is not dict or set(registry) != {"schemaVersion", "endpoints"}
            or type(registry["schemaVersion"]) is not int or registry["schemaVersion"] != 1
            or not isinstance(registry["endpoints"], list) or not 1 <= len(registry["endpoints"]) <= 16):
        raise ValueError("Invalid independent reviewer endpoint registry")
    profiles: dict[str, str] = {}
    for item in registry["endpoints"]:
        if (type(item) is not dict or set(item) != {"url", "requestProfile"}
                or not _safe_reviewer_endpoint(item["url"])
                or not isinstance(item["requestProfile"], str)
                or item["requestProfile"] not in {"openai-chat-completions", "openrouter-private"}
                or item["url"] in profiles):
            raise ValueError("Unsafe or duplicate reviewer registry endpoint")
        profiles[item["url"]] = item["requestProfile"]
    return profiles


def _validated_openrouter_routing(value: Any) -> dict[str, Any]:
    """Require exact privacy and provider routing policy before any source diff can leave the repo."""
    if not isinstance(value, dict) or set(value) != {"only", "allow_fallbacks", "require_parameters", "data_collection", "zdr"}:
        raise ValueError("OpenRouter reviewer requires an explicit private providerRouting profile")
    only = value["only"]
    if (not isinstance(only, list) or not 1 <= len(only) <= 8
            or any(not isinstance(name, str) or not re.fullmatch(r"[A-Za-z0-9_-]{2,64}", name) for name in only)
            or len(set(only)) != len(only)):
        raise ValueError("OpenRouter providerRouting.only must name explicitly approved upstreams")
    for key, expected in (("allow_fallbacks", False), ("require_parameters", True),
                          ("data_collection", "deny"), ("zdr", True)):
        if type(value[key]) is not type(expected) or value[key] != expected:
            raise ValueError(f"OpenRouter private reviewer requires providerRouting.{key}={expected}")
    return value
