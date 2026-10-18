# Draft: hash-pinned source and patch checks with Nix

Status: draft, nothing here has been run. Every snippet is a sketch to review, not a tested change.

## Goal

Replace the hand-built pinning, fetch-verification and patch-drift machinery in bash with what Nix
already does by construction, and leave the build itself (npm ci, gulp, branding, prune, verify) in
bash. Only the layer around the 26 files in `patches/` changes. The patches stay as plain `.patch`
files, so nothing about authoring or rebasing them changes.

## Scope

In scope:

1. A hash-pinned Code - OSS source tree with `patches/` applied, as a flake output (`applyPatches`).
2. Flake checks: the patches apply to the pinned commit, and to the next upstream tag.
3. A hash-pinned `server-tarball` (already in `flake.nix`) as the only source `fetch-vscode-oss.sh`
   uses, with a small script to bump that hash after a republish.

Out of scope:

- Building the server tree inside Nix. `npm ci` needs fixed-output hashes across VS Code's lockfiles
  and the postinstall downloads (ripgrep and others) are blocked by the sandbox. That layer would get
  longer in Nix, not shorter.
- The Python post-build rewrites (`patch-default-shell.py`, `patch-js-debug-env.py`,
  `patch-copilot-byok.py`) and the verifiers. They stay.

## What goes away, what stays

Line counts are by eye from reading the scripts, not from `wc`.

| Today | After | Notes |
|---|---|---|
| `build-vscode-oss.sh`: VSCODE_COMMIT hex and length validation | `fetchFromGitHub { rev; hash; }` | A wrong or moved commit fails at fetch |
| `build-vscode-oss.sh`: init, fetch, checkout, HEAD vs pin comparison | same | |
| `build-vscode-oss.sh`: package.json version vs VSCODE_VERSION | `checks.pin-matches-version` | no import-from-derivation |
| `build-vscode-oss.sh`: reset, clean, apply loop | `applyPatches` | store source is fresh every time |
| `check-patches-apply.sh` (~130 lines) | flake input plus `checks.patches-apply-next` | |
| `fetch-vscode-oss.sh`: release digest lookup, cache staleness, `VSCODE_OSS_SHA256`, gh handling | `fetchurl { hash; }` | ~200 lines |
| `fetch-vscode-oss.sh`: product.json commit and version check | stays (trimmed) | see "What the hash does not cover" |
| `fetch-vscode-oss.sh`: branding check, verify-server-tree, fingerprints, ripgrep step | stays | |
| `check-patch-fingerprints.py` and the patch manifest | stays | see below |

Rough total: 400+ lines of script, mostly guards and their incident comments, replaced by a few dozen
lines of Nix.

## What the hash does not cover

Be clear about this before deleting anything. The `fetchurl` hash says "these are the bytes I expected".
It does not say they were built from the commit in `VSCODE_COMMIT`, nor from today's `patches/`. The
tarball is still built by a separate workflow, so two checks stay:

- The product.json commit and version comparison in `fetch-vscode-oss.sh`.
- The patch manifest and fingerprint checks (`check-patch-fingerprints.py`). Nix cannot tell whether
  the minified bundle contains a patch's effect, and a patch edited without a republish still has to
  be caught.

Those guards only disappear fully if Nix builds the tree itself, which is out of scope above.

## Sketch: flake.nix changes

Inputs. The second input is only for the drift check; `flake = false` because VS Code has no flake.

```nix
inputs = {
  nixpkgs.url = "github:NixOS/nixpkgs/nixos-unstable";
  # Drift check only. Bump with: nix flake update vscode-next
  vscode-next = { url = "github:microsoft/vscode/1.140.0"; flake = false; };
};

outputs = { self, nixpkgs, vscode-next }:
```

Helpers, inside the existing `let`:

```nix
vscodeCommit = nixpkgs.lib.fileContents ./VSCODE_COMMIT;   # full 40-char SHA is the pin

# Same order as the glob in build-vscode-oss.sh.
patchFiles = map (n: ./patches + "/${n}")
  (nixpkgs.lib.sort nixpkgs.lib.lessThan
    (nixpkgs.lib.filter (nixpkgs.lib.hasSuffix ".patch")
      (builtins.attrNames (builtins.readDir ./patches))));

# --fuzz=0 matters. GNU patch applies with fuzz by default, git apply does not, and the whole
# point of patches/ is that shifted context fails loudly.
mkPatched = pkgs: name: src: pkgs.applyPatches {
  inherit name src;
  patches = patchFiles;
  patchFlags = [ "-p1" "--fuzz=0" ];
};
```

Packages and checks:

```nix
packages = forAllSystems (system:
  let pkgs = pkgsFor system; in rec {
    vscode-src = pkgs.fetchFromGitHub {
      owner = "microsoft"; repo = "vscode";
      rev = vscodeCommit;
      hash = "sha256-AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA=";  # vscode-src
    };
    vscode-patched = mkPatched pkgs "vscode-${vscodeVersion}-vscodroid" vscode-src;
    # ...existing outputs, with server-tarball keeping its hash line tagged for the bump script:
    #   hash = "sha256-...";  # server-tarball
  });

checks = forAllSystems (system:
  let pkgs = pkgsFor system; in {
    patches-apply-pinned = self.packages.${system}.vscode-patched;

    # Informational. Failing here means a bump needs patches rebased, not that the build is broken.
    patches-apply-next = mkPatched pkgs "vscode-next-patched" vscode-next;

    pin-matches-version = pkgs.runCommand "pin-matches-version" { nativeBuildInputs = [ pkgs.jq ]; } ''
      test "$(jq -r .version ${self.packages.${system}.vscode-src}/package.json)" = "${vscodeVersion}"
      touch $out
    '';
  });
```

Usage:

```
nix flake check                      # pinned patches apply, pin matches version
nix build .#checks.x86_64-linux.patches-apply-next   # does 1.140.0 still take the patches
```

## Sketch: bump the server tarball hash

Replaces the "rebuild and publish, then `rm` the cached tarball" dance. Run after the "Build Code - OSS
server" workflow has published the release.

```bash
#!/usr/bin/env bash
# scripts/update-server-hash.sh
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VERSION="$(tr -d '[:space:]' < "$ROOT_DIR/VSCODE_VERSION")"
URL="https://github.com/rmyndharis/VSCodroid/releases/download/server-$VERSION/vscode-reh-web-linux-arm64-$VERSION.tar.gz"

hash="$(nix store prefetch-file --json "$URL" | jq -r .hash)"
sed -i "s|hash = \"sha256-[^\"]*\";  # server-tarball|hash = \"$hash\";  # server-tarball|" "$ROOT_DIR/flake.nix"
grep -q "$hash" "$ROOT_DIR/flake.nix" || { echo "hash line not found in flake.nix" >&2; exit 1; }
echo "server-tarball -> $hash"
```

## Sketch: trimmed fetch-vscode-oss.sh

The download, digest, cache and gh logic collapse to one line. Everything from "Pin" down stays as it
is today, because the hash does not tie the tarball to the commit or the patches.

```bash
#!/usr/bin/env bash
set -euo pipefail
ROOT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
VSCODE_VERSION="${VSCODE_VERSION:-$(cat "$ROOT_DIR/VSCODE_VERSION")}"
VSCODE_COMMIT="${VSCODE_COMMIT:-$(cat "$ROOT_DIR/VSCODE_COMMIT")}"
DEST="$ROOT_DIR/server/vscode-reh"

# Hash-checked by Nix. A tarball that is not the one flake.nix names cannot reach here.
TARBALL="$(nix build "$ROOT_DIR#server-tarball" --no-link --print-out-paths)"

rm -rf "$DEST"; mkdir -p "$DEST"
tar -xzf "$TARBALL" -C "$DEST"

# ...unchanged: Pin (product.json commit/version), Branding, Verify, Patches fingerprints, ripgrep
```

Callers that run without Nix (the GitHub runners for the APK build) need either Nix installed there or
a fallback that keeps the current gh path. See open questions.

## Open questions

1. **No .git in the store source.** `build-vscode-oss.sh` reads the commit time with `git log` for the
   tarball mtime, and the build stamps the commit into product.json. Neither was checked against how
   gulp derives the commit. This only matters if the Nix source is ever fed to the build, so it is not
   a blocker for the checks above.
2. **CI runners.** The APK build runs where `gh` works today. Decide whether those jobs install Nix or
   keep the gh path as a fallback. Dropping gh without Nix on the runner breaks the APK build.
3. **Apply semantics.** Run `git apply --check` and the `applyPatches` check against the same pinned
   commit once and confirm both accept all 26 patches before deleting the bash apply loop. Not yet run.
4. **aarch64.** The flake advertises x86_64-linux and aarch64-linux, but the server tree must be built
   on arm64. The source and check outputs above are architecture-independent, so this does not affect
   them.
5. **Hash churn.** `vscode-src` needs a new hash whenever `VSCODE_COMMIT` moves. That is intended (a
   moved commit without a hash fails), but the bump workflow should say so.

## Side notes from reading the patches

- 0005 replaces the webview CSP's sha256 with `'unsafe-inline'` because the patch changes the inline
  script's digest. A post-patch step that recomputes the digest would keep the tight CSP. Not
  Nix-specific.
- 0019 has a placeholder `index 1111111..2222222` line, so it applies with `git apply` and `patch` but
  will not 3-way merge under `git am -3`.
- 0006 is split across a patch and a Python strip of a 150 KB line in the build script. A
  `postPatch` would put both in one place, if the source is ever built in Nix.

## Suggested order

1. Add `vscode-src`, `vscode-patched` and the three checks. Run `nix flake check` and compare with the
   current `git apply` result (open question 3).
2. Add `vscode-next` and `patches-apply-next`. Retire `check-patches-apply.sh` and its workflow step.
3. Add `update-server-hash.sh`, swap the trimmed fetch script in, and settle the CI question.
