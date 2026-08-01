#!/usr/bin/env bash
# scripts/lint.sh — unified static-analysis runner behind `lein lint`.
#
# Trimmed port of vaelii core's scripts/lint.sh. This artifact has no doc-gen
# system, so the doc / unused-publics gates don't apply: the checks here are
# kondo + shellcheck + cljfmt + reflect (its unit suite runs via `lein test`,
# not this gate). Each check runs and its output + exit code are captured; a
# uniform report prints: one ✓/✗ line per check (green/red glyph), a short
# summary on success, the full captured detail only under a check that FAILED,
# and a dim [Ns] on the slow ones. It runs ALL checks (NOT fail-fast), so one
# pass surfaces every problem. Exit non-zero iff any check failed.
#
# `reflect` (AOT-compiles main, the slowest check) is launched in the background
# up front so it overlaps the fast kondo check, and its row prints last — usually
# already done by then. reflect and cljfmt both shell out to `lein`, and two
# concurrent lein in one checkout race .lein-env, so cljfmt waits for reflect.
#
#   lein lint               # the clean report
#   VERBOSE=1 lein lint     # also dump each check's full output, pass or fail
#   bash scripts/lint.sh -v # same, when run directly
#
# Needs clj-kondo on PATH; reflect needs core on the classpath, which the installed
# snapshot satisfies (scripts/link-checkouts.sh points it at live source instead).
# VAELII_COLOR=always|never forces colour; NO_COLOR off.
set -uo pipefail   # NOT -e: every check must run even after one fails.

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT" || exit 1

# Read the inherited VERBOSE env (or a -v arg) before normalizing it to 0/1 —
# don't reset to 0 first, that would clobber `VERBOSE=1 lein lint`.
if [[ "${1:-}" == "-v" || "${VERBOSE:-0}" == "1" ]]; then VERBOSE=1; else VERBOSE=0; fi

# Colour detection: `lein lint` runs us through lein-shell, which pipes our
# stdout, so a plain `-t 1` is FALSE. Key off TERM (a capable, non-`dumb`
# terminal, not on CI) — it survives that pipe. VAELII_COLOR forces; NO_COLOR off.
color=0
case "$(printf '%s' "${VAELII_COLOR:-}" | tr '[:upper:]' '[:lower:]')" in
  always) color=1 ;;
  never)  color=0 ;;
  *) [[ -z "${NO_COLOR:-}" && -z "${CI:-}" \
        && ( -t 1 || ( -n "${TERM:-}" && "${TERM:-}" != dumb ) ) ]] && color=1 ;;
esac
if [[ $color -eq 1 ]]; then
  GREEN=$'\e[32m'; RED=$'\e[1;31m'; DIM=$'\e[2m'; BOLD=$'\e[1m'; RST=$'\e[0m'
else
  GREEN=''; RED=''; DIM=''; BOLD=''; RST=''
fi

pass=0; fail=0; failed_labels=()
out="$(mktemp -t vaelii-lint.XXXXXX)"           # scratch file reused by the foreground checks
reflect_out="$(mktemp -t vaelii-lint.XXXXXX)"   # the background reflect's captured output
reflect_dur="$(mktemp -t vaelii-lint.XXXXXX)"   # "<rc> <seconds>" written by the bg reflect
reflect_pid=""
trap 'rm -f "$out" "$reflect_out" "$reflect_dur"; [[ -n "$reflect_pid" ]] && kill "$reflect_pid" 2>/dev/null' EXIT

# summary <label> <outfile> — a short one-line success summary, drawn from the
# tool's own output where the figure carries info (kondo counts survive at exit 0).
summary() {
  local label="$1" out="$2" s=""
  case "$label" in
    kondo)    s="$(grep -oE 'errors: [0-9]+, warnings: [0-9]+' "$out" | head -1)" ;;
    cljfmt)   s="formatted" ;;
    reflect)  s="no reflection / boxing" ;;
  esac
  echo "${s:-ok}"
}

# print_status <label> <rc> <outfile> <seconds> — render one result row (the
# label is expected to have been printed already), tallying pass/fail.
print_status() {
  local label="$1" rc="$2" o="$3" t="$4" tstr=""
  (( t >= 2 )) && tstr=" ${DIM}[${t}s]${RST}"   # surface only slow checks
  if [[ $rc -eq 0 ]]; then
    printf '%s✓%s %s%s\n' "$GREEN" "$RST" "$(summary "$label" "$o")" "$tstr"
    pass=$((pass + 1))
    [[ $VERBOSE -eq 1 ]] && sed 's/^/        /' "$o"
  else
    printf '%s✗%s FAILED%s\n' "$RED" "$RST" "$tstr"
    sed 's/^/        /' "$o"
    # shellcheck disable=SC2016  # the backticks are literal output, not a command sub
    [[ "$label" == cljfmt ]] && printf '        %s→ run `lein fix`%s\n' "$DIM" "$RST"
    fail=$((fail + 1)); failed_labels+=("$label")
  fi
}

# check <label> -- <cmd...> — run a foreground check, streaming its row.
check() {
  local label="$1"; shift
  [[ "${1:-}" == "--" ]] && shift
  printf '  %-15s ' "$label"   # label first, then run — a slow check shows what's running
  SECONDS=0
  "$@" >"$out" 2>&1
  local rc=$? t=$SECONDS
  print_status "$label" "$rc" "$out" "$t"
}

printf '%slint%s\n' "$BOLD" "$RST"

# Head start: reflect runs while the (non-lein) kondo + shellcheck checks stream below.
( s=$SECONDS; bash scripts/check-reflection.sh >"$reflect_out" 2>&1; rc=$?
  printf '%s %s\n' "$rc" "$((SECONDS - s))" >"$reflect_dur" ) &
reflect_pid=$!

check kondo          -- clj-kondo --lint src test
check shellcheck     -- shellcheck scripts/*.sh

# cljfmt also shells out to lein; let the background reflect's lein finish first
# so two lein never run at once in this checkout (.lein-env race).
wait "$reflect_pid"
check cljfmt         -- lein lint-cljfmt

# reflect's row, printed last — done by now thanks to the head start.
printf '  %-15s ' reflect
read -r reflect_rc reflect_t < "$reflect_dur" 2>/dev/null || { reflect_rc=1; reflect_t=0; }
print_status reflect "${reflect_rc:-1}" "$reflect_out" "${reflect_t:-0}"

total=$((pass + fail))
if [[ $fail -eq 0 ]]; then
  printf '%slint: %d/%d clean%s\n' "$GREEN" "$pass" "$total" "$RST"
  exit 0
fi
printf '%slint: %d/%d — %s FAILED%s\n' "$RED" "$pass" "$total" "${failed_labels[*]}" "$RST"
exit 1
