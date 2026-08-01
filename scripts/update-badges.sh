#!/usr/bin/env bash
# scripts/update-badges.sh — README badge row for a vaelii sibling repo.
#
# Generic sibling edition: everything is derived from the repo the script lives
# in (project.clj, the source tree, the prose tree, the git remote), and both
# vaelii source layouts are detected rather than configured — Maven-style
# src/main + src/test, or the Leiningen default src + test. Rendering matches
# the engine repo's own edition: each badge is a shields.io path-form SVG that
# the script fetches, patches (message text -> true black, key background -> a
# darker grey), scales up, and commits under .github/badges/; the README
# references the local files between the `<!-- badges:start -->` /
# `<!-- badges:end -->` markers (inserted after the H1 on first run). Colors
# are a perceptually-even OKLCH rainbow sized to the row. Regenerating needs
# network (the fetch).
#
# Badges (left to right; a badge drops out when its source is absent):
#   license | release | tests | loc | <domain counts> | docstrings
#   - license / release: project.clj (:license name + defproject version).
#     Without a project.clj the license falls back to the LICENSE header and
#     the release badge is dropped.
#   - tests: deftest count; dropped when the repo has no test tree (the storage
#     siblings are exercised by the engine repo's backend matrix, not in-repo).
#   - domain counts: an optional scripts/badge-extras.sh is sourced between
#     the loc and docstrings badges and appends per-repo fun badges (formats,
#     opcodes, apps, improvers, engines, connectors, tables, ...), each measured
#     from the repo. That file is where a per-repo figure belongs; everything
#     else here is repo-independent.
#   - docstrings: % of public top-level defns carrying a docstring — the one
#     code-quality figure surfaced as a badge (matches the engine repo).
#     `defn-` is not counted: the badge is about the surface a caller reads.
#
# Usage:
#   scripts/update-badges.sh            # measure + rewrite the badge block
#   scripts/update-badges.sh --dry-run  # measure + print, leave README alone
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"

DRY=0
for a in "$@"; do
  case "$a" in
    --dry-run|-n) DRY=1 ;;
  esac
done

# ---- CONFIG (matches the engine repo's update-badges.sh) ----
# The docstrings badge is the raw docstring-coverage % (public top-level defns
# with a docstring). The scorecard also prints test:src / doc:src ratios and naming /
# commented-code counts as plain diagnostics — no weighting, no composite.

# Perceptually-even OKLCH rainbow at fixed bright lightness (>= ~0.78 keeps
# shields' auto text dark on every badge); N is sized to the row at use time.
OKLCH_L=0.82
OKLCH_C=0.12

# Self-hosted badge SVGs. Shields can't serve true-black text or a custom key
# background, so we fetch each shields path-form badge, patch those two colors,
# and commit the SVG here; the README references the local files.
BADGES_DIR=".github/badges"
MSG_TEXT=000            # message text color (true black on the bright OKLCH color)
LABEL_BG=2b2b2b         # darkened key (label) background
BADGE_SCALE=1.2         # enlarge factor (1 = shields' default ~20px tall; 1.2 ~= 24px)

# Source layout, detected rather than set: the storage siblings use the Maven-style
# src/main + src/test tree, this repo and the engine the Leiningen default src + test.
# Everything downstream (the loc/tests counts, the badge link targets) reads these two.
if [[ -d src/main ]]; then
  MAIN='src/main'; TEST='src/test'; SRC_LINK='src/main/clojure'; TEST_LINK='src/test/clojure'
else
  MAIN='src'; TEST='test'; SRC_LINK='src'; TEST_LINK='test'
fi
clj=(--include='*.clj')

# count lines matching a pattern, tolerating zero matches (grep exits 1).
count() { local n; n=$("$@" | wc -l | tr -d ' '); echo "${n:-0}"; }
g() { grep "$@" || true; }

# Print N hexes (no #), evenly spaced around the OKLCH hue circle at OKLCH_L /
# OKLCH_C: OKLCH -> OKLab -> linear sRGB -> gamma sRGB, clamped to gamut.
rainbow_palette() {
  perl -e '
    my ($N,$L,$C)=@ARGV;
    sub g { my $x=shift; $x=$x<=0.0031308?12.92*$x:1.055*($x**(1/2.4))-0.055; $x<0?0:($x>1?1:$x) }
    for my $i (0..$N-1) {
      my $H=6.28318530718*$i/$N; my $a=$C*cos($H); my $b=$C*sin($H);
      my $l=($L+0.3963377774*$a+0.2158037573*$b)**3;
      my $m=($L-0.1055613458*$a-0.0638541728*$b)**3;
      my $s=($L-0.0894841775*$a-1.2914855480*$b)**3;
      my $R= 4.0767416621*$l-3.3077115913*$m+0.2309699292*$s;
      my $G=-1.2684380046*$l+2.6097574011*$m-0.3413193965*$s;
      my $B=-0.0041960863*$l-0.7034186147*$m+1.7076147010*$s;
      printf "%02x%02x%02x ", int(g($R)*255+0.5), int(g($G)*255+0.5), int(g($B)*255+0.5);
    }
  ' "$1" "$OKLCH_L" "$OKLCH_C"
}

# shields.io path-form field encoding: dash -> -- , space -> _ , % -> %25.
enc() { local s="$1"; s="${s//-/--}"; s="${s// /_}"; s="${s//%/%25}"; printf '%s' "$s"; }

# Fetch a shields path-form badge and patch it: message text -> true black,
# key background -> the darkened grey, and scale up by BADGE_SCALE (add a
# viewBox + grow the root width/height). Write the SVG to $1. Needs network; on
# a failed fetch it leaves the existing file untouched. Args: outfile label message color
make_badge() {
  local out="$1" raw
  raw=$(curl -fsS "https://img.shields.io/badge/$(enc "$2")-$(enc "$3")-$4" 2>/dev/null || true)
  case "$raw" in
    *"<svg"*) printf '%s' "$raw" \
      | sed -E "s/fill=\"#333\"/fill=\"#${MSG_TEXT}\"/g; s/fill=\"#555\"/fill=\"#${LABEL_BG}\"/g" \
      | perl -0777 -pe 'BEGIN{$sc=shift@ARGV} s/width="(\d+)" height="(\d+)"/sprintf(q{width="%.0f" height="%.0f" viewBox="0 0 %d %d"},$1*$sc,$2*$sc,$1,$2)/e' "$BADGE_SCALE" \
      > "$out" ;;
    *) echo "  WARN: badge fetch failed for '$2'; kept existing $out" >&2 ;;
  esac
}

# ---- derive repo identity (project.clj; the org is a constant) ----
REPO_NAME=$(basename "$ROOT")
# The PUBLISHED location, hardcoded rather than read off `origin`. Deriving it meant
# the badge pointed wherever the clone happened to sit, so regenerating in a private
# dev tree rewrote the release badge to a URL the public cannot open — which is what
# it had done. Where the project is published is a fact about the project, not a
# property of a checkout. Same spelling as core's scripts/meta/update-badges.sh.
GH="vaelii/$REPO_NAME"

VERSION=""; LICENSE_NAME=""
if [[ -f project.clj ]]; then
  VERSION=$(grep -m1 -E '^\(defproject' project.clj | grep -oE '"[^"]+"' | head -1 | tr -d '"' || true)
  LICENSE_NAME=$(grep -m1 -E ':license' project.clj | grep -oE ':name[[:space:]]*"[^"]+"' | grep -oE '"[^"]+"' | tr -d '"' || true)
fi
if [[ -z "$LICENSE_NAME" && -f LICENSE ]]; then
  grep -qi 'apache license' LICENSE && LICENSE_NAME="Apache-2.0"
fi
[[ -z "$LICENSE_NAME" ]] && LICENSE_NAME="see LICENSE"

# ---- gather raw counts ----
loc_src=$(find "$MAIN" -name '*.clj' -exec cat {} + | wc -l | tr -d ' ')
loc_test=0; [[ -d "$TEST" ]] && loc_test=$(find "$TEST" -name '*.clj' -exec cat {} + | wc -l | tr -d ' ')
# Prose lives in doc/ in the storage siblings and docs/ here — whichever exists.
DOCS='doc'; [[ -d docs ]] && DOCS='docs'
loc_doc=0;  [[ -d "$DOCS" ]] && loc_doc=$(find "$DOCS" -name '*.md' -exec cat {} + | wc -l | tr -d ' ')

defns=$(count g -rhE '^\(defn ' "$MAIN" "${clj[@]}")
docd=$({ grep -rhEA1 '^\(defn ' "$MAIN" "${clj[@]}" || true; } | { grep -cE '^\s+"' || true; } | tr -d ' ')
snake=$(count g -rhE '^\(defn?-? [a-z]*_' "$MAIN" "${clj[@]}")
commented=$(count g -rhE '^\s*;;+\s*\(' "$MAIN" "${clj[@]}")

tests=$(count g -rhoE '\(deftest' "$TEST" "${clj[@]}")
loc_fmt=$(awk -v n="$loc_src" 'BEGIN{ if (n>=9950) printf "%.0fk", n/1000; else if (n>=1000) printf "%.1fk", n/1000; else printf "%d", n }')

# ---- docstring coverage + scorecard (matches the engine repo) ----
docstrings=$(awk -v loc_src="$loc_src" -v loc_test="$loc_test" -v loc_doc="$loc_doc" \
  -v defns="$defns" -v docd="$docd" -v snake="$snake" -v commented="$commented" \
  -v repo="$REPO_NAME" '
  BEGIN{
    doc_cov    = defns>0   ? 100.0*docd/defns        : 0
    test_ratio = loc_src>0 ? loc_test/loc_src         : 0
    doc_ratio  = loc_src>0 ? loc_doc/loc_src          : 0
    cm_per1k   = loc_src>0 ? 1000.0*commented/loc_src : 0

    bar="------------------------------------------------------------"
    printf "\n  %s BADGE SCORECARD\n  %s\n", toupper(repo), bar > "/dev/stderr"
    printf "  source %d loc | tests %d loc | docs %d loc\n\n", loc_src, loc_test, loc_doc > "/dev/stderr"
    printf "  CODE QUALITY  (docstring coverage is the badge; rest are diagnostics)\n" > "/dev/stderr"
    printf "    docstring coverage          %7.1f%%   (badge)\n", doc_cov > "/dev/stderr"
    printf "    test:source ratio           %7.2fx\n", test_ratio > "/dev/stderr"
    printf "    doc:source ratio            %7.2fx\n", doc_ratio > "/dev/stderr"
    printf "    naming               %5d snake_case defns\n", snake > "/dev/stderr"
    printf "    commented-out code          %5.2f/1k src lines\n", cm_per1k > "/dev/stderr"
    printf "  %s\n\n", bar > "/dev/stderr"

    printf "%.0f\n", doc_cov
  }')

# docstrings -> the source tree whose defns the coverage is measured over.
DOCSTRINGS_TARGET="$SRC_LINK"

# ---- the badge row (script is source of truth; absent sources drop out) ----
keys=(license); msgs=("$LICENSE_NAME"); links=("LICENSE")
if [[ -n "$VERSION" ]]; then
  keys+=(release); msgs+=("v$VERSION"); links+=("https://github.com/${GH}/releases")
fi
if [[ -d "$TEST" ]]; then
  keys+=(tests); msgs+=("$tests"); links+=("$TEST_LINK")
fi
keys+=(loc); msgs+=("$loc_fmt"); links+=("$SRC_LINK")

# Per-repo fun badges: when scripts/badge-extras.sh exists it is sourced here
# (between loc and docstrings) and appends its measured domain counts — apps,
# improvers, engines, connectors, tables, ... — to keys/msgs/links. It can
# use the count/g helpers defined above.
[[ -f scripts/badge-extras.sh ]] && source scripts/badge-extras.sh

keys+=(docstrings)
msgs+=("${docstrings}%")
links+=("$DOCSTRINGS_TARGET")

read -r -a RAINBOW <<< "$(rainbow_palette "${#keys[@]}")"

[[ "$DRY" == 0 ]] && mkdir -p "$BADGES_DIR"
BLOCK=""
for i in "${!keys[@]}"; do
  slug=${keys[$i]// /-}
  file="$BADGES_DIR/$slug.svg"
  [[ "$DRY" == 0 ]] && make_badge "$file" "${keys[$i]}" "${msgs[$i]}" "${RAINBOW[$i]}"
  BLOCK+="[![${keys[$i]}](${file})](${links[$i]})"$'\n'
done

{ echo "  link targets:"
  echo "    docstrings -> $DOCSTRINGS_TARGET"; } >&2

if [[ "$DRY" == 1 ]]; then
  printf '%s' "$BLOCK" >&2
  echo "  (dry run: README not modified)" >&2
else
  # First run: plant the marker pair right under the H1.
  if ! grep -q '<!-- badges:start' README.md; then
    perl -i -0pe 's/\A(#[^\n]*\n)\n*/$1\n<!-- badges:start: regenerated by scripts\/update-badges.sh (do not hand-edit) -->\n<!-- badges:end -->\n\n/s' README.md
  fi
  BLOCK="$BLOCK" perl -i -0pe '
    my $b = $ENV{BLOCK};
    s/(<!-- badges:start.*?-->\n).*?(<!-- badges:end -->)/$1$b$2/s
      or die "badge markers not found in README.md (expected <!-- badges:start --> ... <!-- badges:end -->)\n";
  ' README.md
  echo "  README badges regenerated: ${#keys[@]} badges | docstrings ${docstrings}%" >&2
fi
