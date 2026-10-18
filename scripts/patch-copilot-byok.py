#!/usr/bin/env python3
"""Enable 3rd-party model providers (BYOK) in the bundled Copilot extension.

    patch-copilot-byok.py <server-tree>
    patch-copilot-byok.py --check <server-tree>

The bundled Copilot extension gates all BYOK providers (Custom Endpoint,
OpenAI, Anthropic, Gemini, OpenRouter, Azure, xAI, Ollama) behind a token
check that evaluates the caller's GitHub Copilot account:

    function Swe(t, e) {
        return t ? e ? e.isInternal || e.isIndividual || e.isClientBYOKEnabled() : false : true;
    }

Here `t` is whether a GitHub session exists and `e` is the Copilot token.
`isClientBYOKEnabled()` returns `true` only when the token carries a
`client_byok: "1"` claim, which GitHub sends only to Copilot Pro/Individual
accounts (paid, personal) or accounts where an org admin has explicitly
enabled the BYOK flag. Copilot Free, Copilot Business (without the flag),
expired subscriptions, and accounts that have never purchased Copilot all
receive no such claim.

When `Swe` returns `false` two things happen:

  1. The byok-contribution inside the extension unregisters ALL language-model
     chat providers (openai, anthropic, gemini, openrouter, azure, xai, ollama,
     customendpoint, customoai), logging "unregistered providers due to
     enterprise policy."

  2. `_updateClientByokEnabledContext` sets the VS Code context key
     `github.copilot.clientByokEnabled` to false, which disables the
     "Add Models" button in the chat panel for every user who does not hold
     the right GitHub entitlement, including users who want to use their own
     API key with a 3rd-party provider and have no interest in Copilot at all.

The fix replaces `Swe` with a version that always returns `true`, restoring
the behaviour present before Microsoft added the entitlement gate. This
unblocks all BYOK providers regardless of the caller's GitHub account status.

The Copilot extension manifest also hides the `customoai` (OpenAI-Compatible)
vendor on stable builds with:

    "when": "productQualityType != 'stable'"

VSCodroid sets `quality: "stable"`, so that vendor was hidden for every user.
This script removes that guard so the deprecated-but-still-useful vendor
appears alongside the newer `customendpoint` on stable builds.

Both changes are anchored on distinct literal strings that survive
minification, and are verified against the expected final state so that a
silent mismatch fails the build rather than shipping the old behaviour.

Not a patch under patches/: that directory is applied to the microsoft/vscode
TypeScript source with `git apply`, and the Copilot extension is a pre-built
artifact that VSCodroid's build compiles separately from the bundled source.
This script belongs where other post-build artifact edits live, alongside
patch-js-debug-env.py and patch-default-shell.py.
"""

from __future__ import annotations

import json
import re
import sys
from pathlib import Path


# ──────────────────────────────────────────────────────────────────────────────
# Extension bundle (dist/extension.js)
# ──────────────────────────────────────────────────────────────────────────────

EXTENSION_BUNDLE = "extensions/copilot/dist/extension.js"

# The exact minified text of the BYOK gate as emitted by the Copilot extension
# build.  We match this literally so a future Copilot version that changes the
# emitted shape fails the build rather than silently shipping the gate.
_SWE_OLD = "function Swe(t,e){return t?e?e.isInternal||e.isIndividual||e.isClientBYOKEnabled():!1:!0}"

# Replacement: always return true, so every caller may use BYOK providers.
# The argument names `t` and `e` are kept so the surrounding code (which calls
# `Swe(t, e)` and `Swe(!!..., ...)`) remains syntactically valid.
_SWE_NEW = "function Swe(t,e){return!0}"

# What a patched bundle must contain.  Used both by --check and as a
# post-patch assertion.  Short enough to quote in error messages.
EXTENSION_FINGERPRINT = "function Swe(t,e){return!0}"


# ──────────────────────────────────────────────────────────────────────────────
# Extension manifest (package.json)
# ──────────────────────────────────────────────────────────────────────────────

EXTENSION_MANIFEST = "extensions/copilot/package.json"

# The vendor id whose `when` guard we are lifting.
_GUARDED_VENDOR = "customoai"

# The guard expression that hides the vendor on stable builds.
_WHEN_GUARD = "productQualityType != 'stable'"

# What a patched manifest must NOT contain (checked with a simple substring
# search rather than re-parsing the full 200 KB JSON).
MANIFEST_ABSENT_FINGERPRINT = _WHEN_GUARD


# ──────────────────────────────────────────────────────────────────────────────
# Helpers
# ──────────────────────────────────────────────────────────────────────────────

def _read(path: Path) -> str:
    return path.read_text(encoding="utf-8", errors="surrogateescape")


def _write(path: Path, text: str) -> None:
    path.write_text(text, encoding="utf-8", errors="surrogateescape")


# ──────────────────────────────────────────────────────────────────────────────
# Bundle patch
# ──────────────────────────────────────────────────────────────────────────────

def patch_bundle(text: str) -> tuple[str, str]:
    """Return (patched_text, status_line).

    status_line is a human-readable description of what happened.
    Raises ValueError if the old pattern is absent and the new fingerprint is
    also absent (i.e. the file is in an unexpected state).
    """
    if EXTENSION_FINGERPRINT in text:
        return text, "already patched (Swe always returns true)"

    if _SWE_OLD not in text:
        raise ValueError(
            f"Neither the old BYOK gate nor the expected fingerprint was found "
            f"in {EXTENSION_BUNDLE}. The Copilot extension may have changed its "
            f"emitted shape. Update _SWE_OLD in this script to match the new "
            f"bundle, or investigate whether the gating logic has moved."
        )

    count = text.count(_SWE_OLD)
    if count != 1:
        raise ValueError(
            f"Expected exactly one occurrence of the BYOK gate in {EXTENSION_BUNDLE}, "
            f"found {count}. The extension may inline the function; inspect the "
            f"bundle and update _SWE_OLD accordingly."
        )

    new_text = text.replace(_SWE_OLD, _SWE_NEW, 1)

    if EXTENSION_FINGERPRINT not in new_text:
        raise ValueError(
            "Replacement did not produce the expected fingerprint. This is a "
            "bug in this script."
        )

    return new_text, "patched (Swe now always returns true)"


def check_bundle(text: str) -> str | None:
    """Return None if the bundle is correctly patched, or an error string."""
    if EXTENSION_FINGERPRINT not in text:
        return (
            f"BYOK gate fingerprint absent from {EXTENSION_BUNDLE}. "
            f"Expected: {EXTENSION_FINGERPRINT!r}"
        )
    if _SWE_OLD in text:
        return (
            f"Old BYOK gate still present in {EXTENSION_BUNDLE}. "
            f"The patch did not apply."
        )
    return None


# ──────────────────────────────────────────────────────────────────────────────
# Manifest patch
# ──────────────────────────────────────────────────────────────────────────────

def patch_manifest(text: str) -> tuple[str, str]:
    """Return (patched_text, status_line).

    The manifest is a single-line 200 KB JSON file.  We parse it, lift the
    `when` guard from the `customoai` vendor entry, and serialise it back.
    Normalising the whole object would reorder keys and produce an enormous
    diff; instead we do a targeted string substitution on the raw text to
    preserve the original layout as much as possible.
    """
    if MANIFEST_ABSENT_FINGERPRINT not in text:
        return text, f"already patched ({_GUARDED_VENDOR!r} when-guard absent)"

    # Validate that the manifest is parseable and the vendor is present.
    try:
        manifest = json.loads(text)
    except json.JSONDecodeError as exc:
        raise ValueError(f"Cannot parse {EXTENSION_MANIFEST}: {exc}") from exc

    providers = manifest.get("contributes", {}).get("languageModelChatProviders", [])
    if isinstance(providers, dict):
        providers = [providers]

    vendor_entry = next(
        (p for p in providers if p.get("vendor") == _GUARDED_VENDOR),
        None,
    )
    if vendor_entry is None:
        raise ValueError(
            f"Vendor {_GUARDED_VENDOR!r} not found in "
            f"contributes.languageModelChatProviders of {EXTENSION_MANIFEST}. "
            f"The extension may have renamed or removed the vendor."
        )

    current_when = vendor_entry.get("when")
    if current_when != _WHEN_GUARD:
        raise ValueError(
            f"Vendor {_GUARDED_VENDOR!r} has `when` = {current_when!r}, "
            f"expected {_WHEN_GUARD!r}. The guard may have changed; inspect "
            f"the manifest and update _WHEN_GUARD in this script."
        )

    # The manifest is a single minified line.  The guard appears as the JSON
    # string value of the "when" key inside the vendor object.  We remove it
    # by replacing the key-value pair; the surrounding commas are handled by
    # removing the trailing comma of the preceding field.
    #
    # Two forms are possible depending on which field comes before "when":
    #   ,...,"when":"productQualityType != 'stable'",...
    #   ,"when":"productQualityType != 'stable'"
    #
    # We match either and drop the whole key-value pair.
    guard_json = json.dumps(_WHEN_GUARD)           # "productQualityType != 'stable'"
    # The key-value pair as it appears in minified JSON.
    kv = f'"when":{guard_json}'

    # Remove the leading comma (if it came after another field) or trailing
    # comma (if a field follows).  We try both orderings.
    if f",{kv}," in text:
        # Middle of the object: remove key-value and one surrounding comma.
        new_text = text.replace(f",{kv},", ",", 1)
    elif f",{kv}" in text:
        # Last field: remove key-value and the preceding comma.
        new_text = text.replace(f",{kv}", "", 1)
    elif f"{kv}," in text:
        # First field: remove key-value and the trailing comma.
        new_text = text.replace(f"{kv},", "", 1)
    else:
        raise ValueError(
            f"Could not locate {kv!r} with its surrounding commas in "
            f"{EXTENSION_MANIFEST}. The guard is present in the parsed object "
            f"but could not be found for removal in the raw text."
        )

    if MANIFEST_ABSENT_FINGERPRINT in new_text:
        raise ValueError(
            "Guard string still present after removal. This is a bug in this script."
        )

    return new_text, (
        f"patched ({_GUARDED_VENDOR!r} when-guard removed; "
        f"vendor now visible on stable builds)"
    )


def check_manifest(text: str) -> str | None:
    """Return None if the manifest is correctly patched, or an error string."""
    if MANIFEST_ABSENT_FINGERPRINT in text:
        return (
            f"Stable-build guard still present in {EXTENSION_MANIFEST}: "
            f"{MANIFEST_ABSENT_FINGERPRINT!r}"
        )
    return None


# ──────────────────────────────────────────────────────────────────────────────
# Entry point
# ──────────────────────────────────────────────────────────────────────────────

def main() -> int:
    args = sys.argv[1:]
    check_only = False
    if args and args[0] == "--check":
        check_only = True
        args = args[1:]
    if len(args) != 1:
        print(__doc__.strip().splitlines()[0], file=sys.stderr)
        print(
            "usage: patch-copilot-byok.py [--check] <server-tree>",
            file=sys.stderr,
        )
        return 2

    root = Path(args[0])
    bundle_path = root / EXTENSION_BUNDLE
    manifest_path = root / EXTENSION_MANIFEST

    for path in (bundle_path, manifest_path):
        if not path.is_file():
            print(f"FAIL   {path} not found", file=sys.stderr)
            print(
                "       The Copilot extension is missing from this tree.",
                file=sys.stderr,
            )
            return 1

    # ── Bundle ──────────────────────────────────────────────────────────────

    bundle_text = _read(bundle_path)

    if check_only:
        err = check_bundle(bundle_text)
        if err:
            print(f"FAIL   {err}", file=sys.stderr)
            return 1
        print(f"ok     BYOK gate removed: {EXTENSION_BUNDLE}")
    else:
        try:
            new_bundle, status = patch_bundle(bundle_text)
        except ValueError as exc:
            print(f"ERROR  {exc}", file=sys.stderr)
            return 1
        if new_bundle is not bundle_text:
            _write(bundle_path, new_bundle)
        print(f"  {EXTENSION_BUNDLE}: {status}")

        # Post-patch assertion.
        err = check_bundle(new_bundle)
        if err:
            print(f"ERROR  post-patch check failed: {err}", file=sys.stderr)
            return 1

    # ── Manifest ────────────────────────────────────────────────────────────

    manifest_text = _read(manifest_path)

    if check_only:
        err = check_manifest(manifest_text)
        if err:
            print(f"FAIL   {err}", file=sys.stderr)
            return 1
        print(f"ok     {_GUARDED_VENDOR!r} when-guard absent: {EXTENSION_MANIFEST}")
    else:
        try:
            new_manifest, status = patch_manifest(manifest_text)
        except ValueError as exc:
            print(f"ERROR  {exc}", file=sys.stderr)
            return 1
        if new_manifest is not manifest_text:
            _write(manifest_path, new_manifest)
        print(f"  {EXTENSION_MANIFEST}: {status}")

        err = check_manifest(new_manifest)
        if err:
            print(f"ERROR  post-patch check failed: {err}", file=sys.stderr)
            return 1

    return 0


if __name__ == "__main__":
    sys.exit(main())
