#!/usr/bin/env bash
# scripts/fetch-suites.sh — the third-party test material this repo is checked
# against, cached under .cache/ so a checkout fetches each item at most once.
#
# Nothing here is vendored. A conformance suite is somebody else's corpus under
# somebody else's licence, it is large, and it moves; checking one into git
# would make this repo a stale mirror of it. So the suites live in a gitignored
# cache and the tests that need them skip when it is absent (see
# `vaelii.foreign.suite`). What the repo owns is the hand-authored fixtures
# under test/resources/ — those run offline, always.
#
# ONE ITEM IS FETCHED BY CI, and it is the one that can be pinned. `rdf-tests`
# carries a contract — every valid document in all four W3C suites reads, at
# 100%, or an ontology somebody publishes will not open — and a contract that
# runs only when somebody remembers it is a measurement, not a gate. The
# `conformance` job in .github/workflows/test.yml fetches this item at
# $rdf_tests_ref below and runs `lein test :suite`. The OBO items stay local:
# they are PURLs to unversioned ontologies republished on the Foundry's
# schedule, so a CI leg on them reports somebody else's release as our red
# build.
#
# Idempotent by construction: an item whose target already exists is left
# alone, so re-running costs nothing and a first run after checkout is the
# only one that touches the network. `--force` removes the target first, and
# with no item named it does that for every item it would otherwise skip.
#
#   scripts/fetch-suites.sh                 # everything but the opt-in large items
#   scripts/fetch-suites.sh --all           # those too (go-basic.obo, 32 MB)
#   scripts/fetch-suites.sh rdf-tests       # just one item
#   scripts/fetch-suites.sh --force obo     # refetch one
#   scripts/fetch-suites.sh --force         # refetch everything (~30 MB)
#   scripts/fetch-suites.sh --list          # what there is, and what is cached
#
# The cache location is $VAELII_FOREIGN_CACHE, or .cache/ in the repo root.
# The test harness reads the same variable, so pointing both at a shared
# directory lets several checkouts share one copy.

set -euo pipefail

root=$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)
cache="${VAELII_FOREIGN_CACHE:-$root/.cache}"

force=0
want_all=0
items=()

for arg in "$@"; do
  case "$arg" in
    --force) force=1 ;;
    --all)   want_all=1 ;;
    --list)  items=(--list) ;;
    -h|--help)
      # The header, to the first blank line — a line number would need moving
      # every time the header does, and would silently truncate the usage block
      # when somebody forgot.
      sed -n '2,/^$/p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
      exit 0 ;;
    -*) echo "unknown flag $arg" >&2; exit 2 ;;
    *)  items+=("$arg") ;;
  esac
done

# ── the items ────────────────────────────────────────────────────────────
#
# Each is `name|target|size|licence|what it is`. `target` is relative to the
# cache and is the marker: present means fetched. Items whose size is prefixed
# `+` are opt-in and need --all or an explicit name.

read -r -d '' catalogue <<'EOF' || true
rdf-tests|rdf-tests/rdf/rdf11|28 MB|W3C Test Suite + BSD-3|W3C RDF 1.1 syntax suites — Turtle, N-Triples, N-Quads and RDF/XML, with their manifests
obo-registry|obo/ontologies.yml|373 kB|CC-BY 4.0|the OBO Foundry registry: every ontology, its licence, and a PURL to its .obo
obo-small|obo/pato.obo|1.3 MB|CC-BY 4.0|three small real OBO ontologies — uo, ro, pato — for a conversion that is not a fixture
obo-go|obo/go-basic.obo|+32 MB|CC-BY 4.0|the Gene Ontology, the scale the OBO reader is actually aimed at
EOF

field() { echo "$1" | cut -d'|' -f"$2"; }

list_items() {
  printf '  %-14s %-22s %-8s %s\n' ITEM TARGET SIZE STATUS
  while IFS= read -r line; do
    [ -z "$line" ] && continue
    local name target size
    name=$(field "$line" 1); target=$(field "$line" 2); size=$(field "$line" 3)
    local status="missing"
    [ -e "$cache/$target" ] && status="cached"
    [ "${size:0:1}" = "+" ] && status="$status (opt-in)"
    printf '  %-14s %-22s %-8s %s\n' "$name" "$target" "${size#+}" "$status"
  done <<< "$catalogue"
  echo
  echo "  cache: $cache"
}

# ── fetchers ─────────────────────────────────────────────────────────────
#
# curl and git only. No package manager, no language runtime: this has to work
# on a fresh checkout before anything is built.

# The revision `conformance_test`'s floors were measured against, and the one CI
# fetches. Pinned rather than tracked, because a floor is a claim about this
# reader: let the corpus move under it and a red build means the W3C added a
# test, which is a fact about the week rather than about the code. Moving the
# pin is an edit here plus a `lein test :suite` to re-measure, and the numbers
# it prints belong in the commit that moves it.
rdf_tests_ref=767554e135eb6665949d870e6fa7bbc813837293

fetch_rdf_tests() {
  # A blobless sparse checkout of rdf/rdf11 alone. The whole repo is 41 MB and
  # carries SPARQL and SHACL suites this repo has no reader for; this form is
  # 28 MB and is still a git checkout, so moving the pin later is a fetch rather
  # than a re-download.
  #
  # `init` + `fetch <sha>` and not `clone --depth 1`, because a shallow clone can
  # only land on a branch tip and the whole point of the pin is to land off it.
  local dst="$cache/rdf-tests"
  git init -q "$dst"
  git -C "$dst" remote remove origin 2>/dev/null || true
  git -C "$dst" remote add origin https://github.com/w3c/rdf-tests.git
  git -C "$dst" fetch -q --depth 1 --filter=blob:none origin "$rdf_tests_ref"
  git -C "$dst" sparse-checkout set rdf/rdf11 >/dev/null
  git -C "$dst" checkout -q FETCH_HEAD
}

check_rdf_tests_pin() {
  # A cache sitting at some other revision is the quiet version of the failure the
  # pin exists to stop — the floors pass or fail against a corpus nobody named, and
  # a local run then disagrees with CI for a reason neither prints. Say so; don't
  # refetch 28 MB somebody may have moved deliberately.
  local at
  at=$(git -C "$cache/rdf-tests" rev-parse HEAD 2>/dev/null || echo "unknown")
  if [ "$at" != "$rdf_tests_ref" ]; then
    echo "           ⚠ at ${at:0:12}, pinned at ${rdf_tests_ref:0:12} — the floors were"
    echo "             measured at the pin.  Re-fetch: $0 --force rdf-tests"
  fi
}

fetch_obo_registry() {
  mkdir -p "$cache/obo"
  curl -sfL --retry 3 --max-time 120 \
    -o "$cache/obo/ontologies.yml" \
    https://obofoundry.org/registry/ontologies.yml
}

fetch_obo_small() {
  mkdir -p "$cache/obo"
  # uo, ro and pato in that order: smallest first, so a failure is cheap. All
  # three are Foundry ontologies with a relations layer, which is the part of
  # the OBO reader a hand-authored fixture is least able to stress.
  for o in uo ro pato; do
    curl -sfL --retry 3 --max-time 180 \
      -o "$cache/obo/$o.obo" "https://purl.obolibrary.org/obo/$o.obo"
  done
}

fetch_obo_go() {
  mkdir -p "$cache/obo"
  curl -sfL --retry 3 --max-time 600 \
    -o "$cache/obo/go-basic.obo" \
    https://purl.obolibrary.org/obo/go/go-basic.obo
}

# ── run ──────────────────────────────────────────────────────────────────

if [ "${items[0]:-}" = "--list" ]; then
  list_items
  exit 0
fi

mkdir -p "$cache"

fetched=0
skipped=0
while IFS= read -r line; do
  [ -z "$line" ] && continue
  name=$(field "$line" 1)
  target=$(field "$line" 2)
  size=$(field "$line" 3)
  licence=$(field "$line" 4)
  blurb=$(field "$line" 5)
  optin=0
  [ "${size:0:1}" = "+" ] && optin=1

  # selection: named explicitly, or (nothing named and not opt-in), or --all
  if [ ${#items[@]} -gt 0 ]; then
    printf '%s\n' "${items[@]}" | grep -qx "$name" || continue
  elif [ "$optin" = 1 ] && [ "$want_all" = 0 ]; then
    continue
  fi

  if [ "$force" = 1 ]; then
    # ${cache:?} so an unset cache can never make this an `rm -rf /`
    rm -rf "${cache:?}/${target:?}"
    [ "$name" = "rdf-tests" ] && rm -rf "${cache:?}/rdf-tests"
  fi

  if [ -e "$cache/$target" ]; then
    echo "  cached   $name"
    [ "$name" = "rdf-tests" ] && check_rdf_tests_pin
    skipped=$((skipped + 1))
    continue
  fi

  echo "  fetching $name  (${size#+}) — $blurb"
  echo "           licence: $licence"
  case "$name" in
    rdf-tests)    fetch_rdf_tests ;;
    obo-registry) fetch_obo_registry ;;
    obo-small)    fetch_obo_small ;;
    obo-go)       fetch_obo_go ;;
    *) echo "no fetcher for $name" >&2; exit 1 ;;
  esac
  fetched=$((fetched + 1))
done <<< "$catalogue"

echo
echo "  $fetched fetched, $skipped already cached — $cache"
if [ "$fetched" -gt 0 ]; then
  echo
  echo "  These are third-party corpora under the licences named above. They are"
  echo "  cached for testing and are not redistributed by this repo."
fi
