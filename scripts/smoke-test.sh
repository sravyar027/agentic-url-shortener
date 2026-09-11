#!/usr/bin/env bash
set -euo pipefail
base_url="${1:-http://localhost:8080}"
created="$(curl -fsS -X POST "$base_url/api/urls" -H 'content-type: application/json' -H 'X-Api-Key: local-user-key' -H 'Idempotency-Key: smoke-create-1' -d '{"url":"https://example.com/docs"}')"
code="$(printf '%s' "$created" | sed -n 's/.*"code":"\([^"]*\)".*/\1/p')"
test -n "$code"
test "$(curl -sS -o /dev/null -w '%{http_code}' "$base_url/$code")" = "302"
analytics="$(curl -fsS "$base_url/api/urls/$code/analytics" -H 'X-Api-Key: local-user-key')"
printf '%s' "$analytics" | grep -q '"visits":1'
curl -fsS "$base_url/workflow/demo" -H 'X-Api-Key: local-reviewer-key' | grep -q 'release=WAITING_APPROVAL'
curl -fsS -X POST "$base_url/workflow/release" -H 'X-Api-Key: local-release-key' -H 'X-Change-Approval: APPROVED' -H 'X-Approval-Token: local-demo-token' | grep -q 'release=SUCCEEDED'
curl -fsS "$base_url/workflow/replan" -H 'X-Api-Key: local-reviewer-key' | grep -q 'implement'
curl -fsS "$base_url/metrics" | grep -q 'url_shortener_links_created_total'
printf 'Smoke test passed for code %s\n' "$code"
