#!/usr/bin/env python3
"""Offline key provisioning. Writes plaintext only to a new private directory; never installs it."""
import argparse
import hashlib
import json
import os
from pathlib import Path
import re
import secrets
from datetime import datetime, timedelta, timezone


def create(output, key_id, issuer, capabilities, assistants, audiences, channels, days=90):
    if not re.fullmatch(r"[A-Za-z0-9_-]{1,48}", key_id) or not re.fullmatch(r"[A-Za-z0-9_-]{1,64}", issuer):
        raise ValueError("Invalid key ID or issuer")
    scopes = set(capabilities)
    if not scopes or not scopes <= {"TOOLS", "SMS_VERIFICATION", "CONSENT"} or ("TOOLS" in scopes and len(scopes) != 1):
        raise ValueError("TOOLS must be separate from safe-card capabilities")
    if not 1 <= days <= 365 or not assistants or not audiences or not channels:
        raise ValueError("Expiry (1–365 days) and all allowlists are required")
    if not set(audiences) <= {"anonymous", "customer", "employee"}:
        raise ValueError("Invalid audience")
    if any(not re.fullmatch(r"[A-Za-z0-9_.:@/-]{1,160}", item) for item in [*assistants, *audiences, *channels]):
        raise ValueError("Invalid allowlist value")
    output = Path(output)
    output.mkdir(mode=0o700, parents=False, exist_ok=False)
    token = "mgs_trial." + key_id + "." + secrets.token_urlsafe(32)
    key = {
        "enabled": False, "issuer": issuer, "token-sha256": hashlib.sha256(token.encode()).hexdigest(),
        "expires-at": (datetime.now(timezone.utc) + timedelta(days=days)).isoformat(),
        "capabilities": sorted(scopes), "assistant-ids": sorted(set(assistants)),
        "audiences": sorted(set(audiences)), "channels": sorted(set(channels))}
    config = {"mgs": {"trial": {"connector": {"enabled": False, "keys": {key_id: key}}}}}
    for name, value in [("service-token.txt", token + "\n"), ("mgs-config.json", json.dumps(config, ensure_ascii=False, indent=2) + "\n")]:
        fd = os.open(output / name, os.O_WRONLY | os.O_CREAT | os.O_EXCL, 0o600)
        with os.fdopen(fd, "w", encoding="utf-8") as stream:
            stream.write(value)
    return output


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--output", required=True, help="New directory outside the repository")
    parser.add_argument("--key-id", required=True)
    parser.add_argument("--issuer", required=True)
    parser.add_argument("--capability", action="append", required=True)
    parser.add_argument("--assistant", action="append", required=True)
    parser.add_argument("--audience", action="append", required=True)
    parser.add_argument("--channel", action="append", required=True)
    parser.add_argument("--days", type=int, default=90)
    args = parser.parse_args()
    target = create(args.output, args.key_id, args.issuer, args.capability, args.assistant, args.audience, args.channel, args.days)
    print(f"Created private files in {target}. Connector and key remain disabled; no service was changed.")


if __name__ == "__main__":
    main()
