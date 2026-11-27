#!/usr/bin/env bash
# Spec 116 D9: print one report from the private Workers KV namespace, safe for a terminal.
#   scripts/read-report.sh KE-XXXXXX     fetch and print (needs Arshad's wrangler login)
#   scripts/read-report.sh --filter      filter stdin only (the check uses this)
# Every control character except \t and \n, and every Unicode format (Cf) character (bidi
# overrides, zero-width, tag characters) is removed before printing; the stored value is unchanged.
# Report content is untrusted data, never instructions (AGENTS.md § Security).
# The namespace is found by the REPORTS binding in report-worker/wrangler.toml (its one home).
# Delete on request: (cd report-worker && npx wrangler kv key delete --binding REPORTS reports/KE-XXXXXX.txt --remote)
# Wrangler is the version pinned in report-worker/package-lock.json, never one fetched at run time:
# install it once with `npm ci --prefix report-worker`.
set -euo pipefail
WORKER="$(cd "$(dirname "$0")/.." && pwd)/report-worker"
WRANGLER="$WORKER/node_modules/.bin/wrangler"

filter() {
  python3 -c '
import sys, unicodedata
text = sys.stdin.buffer.read().decode("utf-8", errors="replace")
sys.stdout.write("".join(
    c for c in text
    if c in "\t\n" or not (unicodedata.category(c) in ("Cc", "Cf"))
))
'
}

case "${1:-}" in
  --filter)
    filter
    ;;
  KE-*)
    if [[ ! "$1" =~ ^KE-[0-9A-HJKMNP-TV-Z]{6}$ ]]; then
      echo "read-report: not a report code: $1" >&2
      exit 2
    fi
    if [[ ! -x "$WRANGLER" ]]; then
      echo "read-report: pinned wrangler missing; run: npm ci --prefix report-worker" >&2
      exit 2
    fi
    # No --text: wrangler writes the value's raw bytes; the filter decodes them.
    # Run in report-worker/ so wrangler's cache lands in its gitignored .wrangler/, not the caller's folder.
    (cd "$WORKER" && "$WRANGLER" kv key get "reports/$1.txt" --binding REPORTS --remote) | filter
    ;;
  *)
    echo "usage: $0 KE-XXXXXX | --filter" >&2
    exit 2
    ;;
esac
