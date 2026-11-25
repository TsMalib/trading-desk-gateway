#!/usr/bin/env bash
# Manual end-to-end run against the IBKR PAPER account. See docs/e2e-paper.md for the checklist.
#
# This script never places, cancels or modifies an order. It reads (MCP read tools, the database, a second
# read-only API client on IB Gateway), it can create proposals through the MCP tools exactly like a research
# bot, and it can stop/start containers for the failure drills. Approvals only happen in Telegram.
#
# Needs: bash, curl, jq; mvn + JDK 21 for `ibkr`/`verify`; a psql command for `db`/`verify`.
#
# Environment:
#   DESK_URL         https://<tunnel-host> of the running gateway (required for MCP calls)
#   MCP_API_KEY      the X-Desk-Key value (required for MCP calls)
#   DESK_PSQL        command that runs psql against the desk database, reading SQL from stdin, e.g.
#                      "ssh homelab docker exec -i postgres psql -U desk -d desk"
#                      "psql postgresql://desk@127.0.0.1:5432/desk"
#   DESK_SSH         ssh host of the Docker host, for the drills (e.g. homelab); empty = local docker
#   APP_CONTAINER    app container (default trading-desk-gateway-1)
#   IBG_CONTAINER    IB Gateway container (default ib-gateway)
#   IB_HOST, IB_PORT paper API for the read-only snapshot (default 127.0.0.1:4002, e.g. via
#                      ssh -L 4002:127.0.0.1:4002 homelab)
#   E2E_DIR          where snapshots are kept (default target/e2e)
#
# Usage: scripts/e2e-paper.sh <command> [args]   (run `scripts/e2e-paper.sh help`)

set -uo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
E2E_DIR="${E2E_DIR:-$ROOT/target/e2e}"
APP_CONTAINER="${APP_CONTAINER:-trading-desk-gateway-1}"
IBG_CONTAINER="${IBG_CONTAINER:-ib-gateway}"
IB_HOST="${IB_HOST:-127.0.0.1}"
IB_PORT="${IB_PORT:-4002}"
SNAPSHOT="$E2E_DIR/ibkr-latest.txt"
mkdir -p "$E2E_DIR"

PASS=0
FAIL=0
WARN=0

# ---------------------------------------------------------------------------------------------------------
# Output helpers
# ---------------------------------------------------------------------------------------------------------

die() { echo "ERROR: $*" >&2; exit 2; }
hdr() { printf '\n== %s ==\n' "$*"; }
ok() { PASS=$((PASS + 1)); printf '  PASS  %s\n' "$*"; }
bad() { FAIL=$((FAIL + 1)); printf '  FAIL  %s\n' "$*"; }
warn() { WARN=$((WARN + 1)); printf '  WARN  %s\n' "$*"; }
check() { # check "<description>" <condition command...>
    local what="$1"; shift
    if "$@"; then ok "$what"; else bad "$what"; fi
}
summary() {
    printf '\n%d passed, %d failed, %d warnings\n' "$PASS" "$FAIL" "$WARN"
    [ "$FAIL" -eq 0 ]
}
need() { command -v "$1" >/dev/null 2>&1 || die "$1 is not installed"; }
ny_now() { TZ=America/New_York date '+%a %Y-%m-%d %H:%M:%S ET' 2>/dev/null || date -u '+%Y-%m-%d %H:%M:%S UTC'; }

# Decimal strings compared as text after normalising: 180.500000 == 180.5 == 180.50; 1E+1 == 10.
norm() {
    local v="${1:-}"
    case "$v" in ""|null|NULL) echo ""; return ;; esac
    if [[ "$v" =~ ^([0-9.]+)E\+?([0-9]+)$ ]]; then
        v="$(awk -v m="${BASH_REMATCH[1]}" -v e="${BASH_REMATCH[2]}" 'BEGIN { printf "%d", m * 10^e }')"
    fi
    if [[ "$v" == *.* ]]; then v="$(echo "$v" | sed -e 's/0*$//' -e 's/\.$//')"; fi
    echo "$v"
}
same() { [ "$(norm "$1")" = "$(norm "$2")" ]; }

# ---------------------------------------------------------------------------------------------------------
# MCP (streamable HTTP): initialize, notifications/initialized, tools/call. 3 requests per call.
# ---------------------------------------------------------------------------------------------------------

mcp_post() { # mcp_post <session-id or ""> <json> [header file]
    local sid="$1" body="$2" hdrs="${3:-/dev/null}"
    local args=(-sS -X POST "$DESK_URL/mcp" -H "Content-Type: application/json"
        -H "Accept: application/json, text/event-stream" -H "X-Desk-Key: $MCP_API_KEY" -D "$hdrs")
    [ -n "$sid" ] && args+=(-H "Mcp-Session-Id: $sid")
    curl "${args[@]}" --data "$body"
}

# The body is either one JSON object or an SSE stream of "data: {...}" lines; print the JSON-RPC response.
rpc_json() {
    local raw="$1"
    if [[ "$raw" == \{* ]]; then echo "$raw"; return; fi
    echo "$raw" | sed -n 's/^data: \{0,1\}//p' | jq -c 'select(.id != null)' | tail -1
}

mcp_call() { # mcp_call <tool> [arguments-json] -> prints the tool's text; exit 1 when isError
    local tool="$1" args="${2:-}"
    [ -n "$args" ] || args='{}'
    [ -n "${DESK_URL:-}" ] || die "DESK_URL is not set"
    [ -n "${MCP_API_KEY:-}" ] || die "MCP_API_KEY is not set"
    need curl; need jq
    local h="$E2E_DIR/.mcp-headers" sid init resp
    init="$(mcp_post "" '{"jsonrpc":"2.0","id":1,"method":"initialize","params":{"protocolVersion":"2025-03-26","capabilities":{},"clientInfo":{"name":"e2e-paper","version":"1"}}}' "$h")" \
        || die "MCP initialize failed"
    sid="$(tr -d '\r' < "$h" | sed -n 's/^[Mm][Cc][Pp]-[Ss]ession-[Ii][Dd]: *//p' | tail -1)"
    [ -n "$(rpc_json "$init")" ] || die "MCP initialize returned no JSON-RPC response: $(head -c 300 <<<"$init")"
    mcp_post "$sid" '{"jsonrpc":"2.0","method":"notifications/initialized"}' >/dev/null
    resp="$(rpc_json "$(mcp_post "$sid" "$(jq -nc --arg t "$tool" --argjson a "$args" \
        '{jsonrpc:"2.0",id:2,method:"tools/call",params:{name:$t,arguments:$a}}')")")"
    [ -n "$resp" ] || die "no response to tools/call $tool"
    if [ "$(jq -r '.error != null' <<<"$resp")" = "true" ]; then
        jq -r '.error.message' <<<"$resp"; return 1
    fi
    jq -r '[.result.content[]? | select(.type=="text") | .text] | join("\n")' <<<"$resp"
    [ "$(jq -r '.result.isError // false' <<<"$resp")" != "true" ]
}

pretty() { local t; t="$(cat)"; jq . 2>/dev/null <<<"$t" || echo "$t"; }

# ---------------------------------------------------------------------------------------------------------
# Database
# ---------------------------------------------------------------------------------------------------------

psql_run() { # SQL on stdin
    [ -n "${DESK_PSQL:-}" ] || die "DESK_PSQL is not set (see the header of this script)"
    eval "$DESK_PSQL -X -q -v ON_ERROR_STOP=1"
}
sql_rows() { # unaligned, '|' separated, no headers
    printf '\\pset pager off\n\\pset format unaligned\n\\pset tuples_only on\n\\pset fieldsep |\n%s\n' "$1" | psql_run
}
sql_table() { printf '\\pset pager off\n%s\n' "$1" | psql_run; }
sql_one() { sql_rows "$1" | head -1 | tr -d '\r'; }

int_arg() { [[ "${1:-}" =~ ^[0-9]+$ ]] || die "expected a proposal id, got '${1:-}'"; echo "$1"; }

# ---------------------------------------------------------------------------------------------------------
# IBKR snapshot: a second, read-only API client (id 91) via IbkrBrokerPaperTest.
# ---------------------------------------------------------------------------------------------------------

ibkr_snapshot() {
    case "$IB_PORT" in 4001|4003|7496) die "IB_PORT $IB_PORT is a LIVE port; the snapshot only runs on paper" ;; esac
    need mvn
    local out
    out="$E2E_DIR/ibkr-$(date -u +%Y%m%dT%H%M%SZ).txt"
    echo "Reading IBKR through IB Gateway at $IB_HOST:$IB_PORT (client id ${IB_TEST_CLIENT_ID:-91}) ..." >&2
    (cd "$ROOT" && IB_PAPER_TEST=true IB_HOST="$IB_HOST" IB_PORT="$IB_PORT" \
        mvn -B test -Dtest='IbkrBrokerPaperTest#accountSummary+positions+openOrders+executionsOfTheLastWeek' \
        -Dsurefire.failIfNoSpecifiedTests=false) > "$out.log" 2>&1
    local rc=$?
    grep -E '^(AccountSummary|BrokerPosition|BrokerOpenOrder|BrokerExecution)\[' "$out.log" > "$out"
    cp "$out" "$SNAPSHOT"
    if [ $rc -ne 0 ]; then
        echo "IbkrBrokerPaperTest failed (exit $rc); see $out.log" >&2
        return 1
    fi
    return 0
}

field() { # field <record line> <name> -> value of name=... in a Java record toString
    local v
    v="$(sed -n "s/.*[[ ]$2=\([^,]*\)[],].*/\1/p" <<<"$1" | head -1)"
    echo "$v"
}

ib_orders_for() { grep '^BrokerOpenOrder\[' "$SNAPSHOT" | grep "orderRef=P$1-" ; }
ib_order_ref() { grep '^BrokerOpenOrder\[' "$SNAPSHOT" | grep "orderRef=$1," | head -1; }
ib_position() { grep '^BrokerPosition\[' "$SNAPSHOT" | grep "conid=$1," | head -1; }
ib_execs_for() { grep '^BrokerExecution\[' "$SNAPSHOT" | grep "orderRef=P$1-" ; }

# ---------------------------------------------------------------------------------------------------------
# Commands
# ---------------------------------------------------------------------------------------------------------

cmd_help() {
    sed -n '2,24p' "${BASH_SOURCE[0]}" | sed 's/^# \{0,1\}//'
    cat <<'EOF'

Commands
  preflight                 checks before a run: auth, paper mode, USD base, live quote, kill switch, DB, time
  health                    GET /q/health/ready
  account | positions       get_account_summary | get_positions (MCP)
  quote SYM                 get_quote (MCP)
  status ID                 get_proposal_status (MCP)
  mcp TOOL [JSON]           any MCP tool, e.g. mcp get_quote '{"symbol":"AAPL"}'
  entry-args SYM [MODE]     propose_order arguments from the live quote, to paste into the MCP Inspector.
                            MODE: fill (default, marketable limit), passive (limit 0.9% below last, should not
                            fill), edge (limit 0.95% below last, for the drift drill)
  propose-entry SYM [MODE]  the same arguments, sent with propose_order
  stop-args SYM NEWSTOP     propose_stop_update arguments
  exit-args SYM             propose_exit arguments (whole position)
  db ID                     the proposal, its orders, executions, position_meta and audit trail
  ibkr                      refresh and print the IBKR snapshot (account, positions, open orders, executions)
  verify ID [--cached]      DB, IBKR and Telegram link agree for proposal ID (refreshes the snapshot)
  audit-since ISO           audit_log rows since a UTC time, e.g. 2026-11-25T15:00:00Z
  recon-since ISO           RECON_* rows since a UTC time (the "no differences" check)
  nokey                     drill: the MCP endpoint without a key and with a wrong key must be 401
  app-stop | app-start      drill: stop / start the gateway container (APP_CONTAINER)
  ibg-restart               drill: restart IB Gateway (IBG_CONTAINER)
  logs [N]                  last N lines of the gateway log (default 100)
EOF
}

cmd_health() {
    need curl
    local code
    code="$(curl -sS -o "$E2E_DIR/.health" -w '%{http_code}' "$DESK_URL/q/health/ready")" || code=000
    echo "HTTP $code"; cat "$E2E_DIR/.health" 2>/dev/null | pretty
}

cmd_preflight() {
    need curl; need jq
    hdr "Time"
    echo "  now: $(ny_now)  (entry window 09:45 to 30 min before the close)"

    hdr "MCP authentication"
    local code
    code="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$DESK_URL/mcp" -H 'Content-Type: application/json' \
        --data '{"jsonrpc":"2.0","id":1,"method":"ping"}')"
    check "no key -> 401 (got $code)" [ "$code" = "401" ]

    hdr "Account (get_account_summary)"
    local acc
    if acc="$(mcp_call get_account_summary)"; then
        echo "$acc" | pretty | sed 's/^/  /'
        check "mode is paper" [ "$(jq -r .mode <<<"$acc")" = "paper" ]
        check "account id starts with D (paper)" [[ "$(jq -r .account <<<"$acc")" == D* ]]
        check "base currency is USD (entries in USD need it: FX_RATE/CASH fail otherwise)" \
            [ "$(jq -r .baseCurrency <<<"$acc")" = "USD" ]
        check "kill switch is off" [ "$(jq -r .killSwitch <<<"$acc")" = "false" ]
    else
        bad "get_account_summary: $acc"
    fi

    hdr "Quote (get_quote ${1:-AAPL})"
    local q
    if q="$(mcp_call get_quote "{\"symbol\":\"${1:-AAPL}\"}")"; then
        echo "$q" | pretty | sed 's/^/  /'
        check "quote is live (delayed=false; QUOTE fails on delayed data)" [ "$(jq -r .delayed <<<"$q")" = "false" ]
        check "quote has a last price" [ "$(jq -r '.last // empty' <<<"$q")" != "" ]
    else
        bad "get_quote: $q"
    fi

    hdr "Positions (get_positions)"
    mcp_call get_positions | pretty | sed 's/^/  /'

    hdr "Database"
    local n
    if n="$(sql_one "select count(*) from proposal where status in ('PROPOSED','APPROVED','SUBMITTED');")"; then
        ok "DB reachable; $n proposal(s) open"
        sql_table "select id, kind, status, created_at from proposal where status in ('PROPOSED','APPROVED','SUBMITTED') order by id;"
        echo "  start_of_day_equity: $(sql_one "select value || ' (' || updated_at || ')' from setting where key='start_of_day_equity';")"
    else
        bad "DB not reachable with DESK_PSQL"
    fi
    summary
}

cmd_entry_args() { # prints the JSON
    local sym="${1:?symbol}" mode="${2:-fill}" q
    q="$(mcp_call get_quote "{\"symbol\":\"$sym\"}")" || die "get_quote: $q"
    [ "$(jq -r .delayed <<<"$q")" = "false" ] || echo "warning: delayed quote; QUOTE will fail" >&2
    # Suggested prices only (awk, rounded to cents); the gateway parses the strings as BigDecimal and checks
    # them itself. Stop 2% below entry (1-15% allowed), target 5% above (R/R 2.5, min 2.0).
    jq -r '[.last, .bid, .ask] | map(. // 0 | tostring) | join(" ")' <<<"$q" | awk -v mode="$mode" -v sym="$sym" '
        function up(x) { return int(x * 100 + 0.999999) / 100 }
        function dn(x) { return int(x * 100) / 100 }
        {
            last = $1 + 0; bid = $2 + 0; ask = $3 + 0
            if (last <= 0) { last = (bid + ask) / 2 }
            if (last <= 0) { print "no price in the quote" > "/dev/stderr"; exit 1 }
            if (mode == "fill")         { e = up((ask > 0 ? ask : last) * 1.001) }
            else if (mode == "passive") { e = dn(last * 0.991) }
            else if (mode == "edge")    { e = dn(last * 0.9905) }
            else { print "MODE is fill, passive or edge" > "/dev/stderr"; exit 1 }
            printf "{\"symbol\":\"%s\",\"entryLimit\":\"%.2f\",\"stop\":\"%.2f\",\"target\":\"%.2f\",", sym, e, dn(e * 0.98), up(e * 1.05)
            printf "\"thesis\":\"E2E paper test (%s): liquid large cap, checking the bracket, stop move and exit end to end. Not a real setup.\",", mode
            printf "\"sources\":[\"https://www.nasdaq.com/market-activity/stocks/%s\"],\"earningsSoon\":false,\"proposedBy\":\"e2e-manual\"}\n", tolower(sym)
        }'
}

cmd_propose_entry() {
    local args; args="$(cmd_entry_args "$@")" || exit 1
    echo "propose_order $args"; echo
    mcp_call propose_order "$args"
}

cmd_stop_args() {
    jq -nc --arg s "${1:?symbol}" --arg n "${2:?new stop}" \
        '{symbol:$s,newStop:$n,reason:"E2E paper test: trail the stop up.",proposedBy:"e2e-manual"}'
}

cmd_exit_args() {
    jq -nc --arg s "${1:?symbol}" '{symbol:$s,reason:"E2E paper test: close the position.",proposedBy:"e2e-manual"}'
}

cmd_db() {
    local id; id="$(int_arg "${1:-}")"
    hdr "proposal $id"
    sql_table "select p.id, p.kind, p.status, i.symbol, p.conid, p.entry_limit, p.stop_price, p.target_price, p.quantity,
        p.risk_eur, p.reward_risk, p.telegram_msg_id, p.proposed_by, p.created_at, p.expires_at, p.decided_at
        from proposal p left join instrument i on i.conid = p.conid where p.id = $id;"
    hdr "failed checks (approval re-check if any, else proposal time)"
    sql_table "select c->>'name' as check, c->>'actual' as actual, c->>'limit' as limit
        from proposal p, jsonb_array_elements(coalesce(p.risk_result->'approval'->'checks', p.risk_result->'checks', '[]'::jsonb)) c
        where p.id = $id and coalesce((c->>'passed')::boolean, false) = false;"
    hdr "warnings"
    sql_table "select jsonb_array_elements(warnings) as warning from proposal where id = $id;"
    hdr "broker_order (this proposal)"
    sql_table "select id, order_ref, role, action, order_type, quantity, limit_price, aux_price, status, ib_order_id, ib_perm_id, updated_at
        from broker_order where proposal_id = $id order by id;"
    hdr "execution (this proposal's orders)"
    sql_table "select x.ib_exec_id, b.order_ref, x.side, x.quantity, x.price, x.currency, x.commission, x.realized_pnl, x.executed_at
        from execution x join broker_order b on b.id = x.broker_order_id where b.proposal_id = $id order by x.executed_at;"
    hdr "position_meta (this instrument)"
    sql_table "select m.* from position_meta m join proposal p on p.conid = m.conid where p.id = $id;"
    hdr "audit_log"
    sql_table "select id, at, actor, event, left(details::text, 180) as details from audit_log where proposal_id = $id order by id;"
}

cmd_ibkr() {
    ibkr_snapshot || true
    hdr "IBKR snapshot ($SNAPSHOT)"
    cat "$SNAPSHOT"
}

cmd_audit_since() {
    local t="${1:?UTC time, e.g. 2026-11-25T15:00:00Z}"
    sql_table "select id, at, actor, event, proposal_id, left(details::text, 200) as details from audit_log
        where at >= '$t'::timestamptz order by id;"
}

cmd_recon_since() {
    local t="${1:?UTC time, e.g. 2026-11-25T15:00:00Z}"
    sql_table "select id, at, event, proposal_id, left(details::text, 200) as details from audit_log
        where actor = 'RECONCILER' and at >= '$t'::timestamptz order by id;"
    echo "Last reconcile pass started: $(sql_one "select value from setting where key = 'recon_last_run';")"
}

cmd_nokey() {
    need curl
    local a b
    a="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$DESK_URL/mcp" -H 'Content-Type: application/json' \
        --data '{"jsonrpc":"2.0","id":1,"method":"tools/list"}')"
    b="$(curl -sS -o /dev/null -w '%{http_code}' -X POST "$DESK_URL/mcp" -H 'Content-Type: application/json' \
        -H 'X-Desk-Key: wrong-key' --data '{"jsonrpc":"2.0","id":1,"method":"tools/list"}')"
    check "no X-Desk-Key -> 401 (got $a)" [ "$a" = "401" ]
    check "wrong X-Desk-Key -> 401 (got $b)" [ "$b" = "401" ]
    summary
}

dock() { if [ -n "${DESK_SSH:-}" ]; then ssh "$DESK_SSH" docker "$@"; else docker "$@"; fi; }
cmd_app_stop() { echo "stop $APP_CONTAINER at $(date -u +%FT%TZ)"; dock stop "$APP_CONTAINER"; }
cmd_app_start() { echo "start $APP_CONTAINER at $(date -u +%FT%TZ)"; dock start "$APP_CONTAINER"; }
cmd_ibg_restart() { echo "restart $IBG_CONTAINER at $(date -u +%FT%TZ)"; dock restart "$IBG_CONTAINER"; }
cmd_logs() { dock logs --tail "${1:-100}" "$APP_CONTAINER"; }

# ---- verify ---------------------------------------------------------------------------------------------

working() { case "$1" in Filled|Cancelled|ApiCancelled|Inactive) return 1 ;; *) return 0 ;; esac; }

cmd_verify() {
    local id; id="$(int_arg "${1:-}")"
    if [ "${2:-}" != "--cached" ]; then ibkr_snapshot || warn "IBKR snapshot failed; IBKR checks use $SNAPSHOT"; fi
    [ -s "$SNAPSHOT" ] || die "no IBKR snapshot in $SNAPSHOT"

    local row kind status conid symbol qty stop target msg by created_status
    row="$(sql_one "select p.kind, p.status, p.conid, i.symbol, coalesce(p.quantity::text,''), coalesce(p.stop_price::text,''),
        coalesce(p.target_price::text,''), coalesce(p.telegram_msg_id::text,''), p.proposed_by,
        coalesce((select a.details->>'status' from audit_log a where a.proposal_id = p.id and a.event = 'PROPOSAL_CREATED' order by a.id limit 1), '')
        from proposal p left join instrument i on i.conid = p.conid where p.id = $id;")"
    [ -n "$row" ] || die "no proposal $id"
    IFS='|' read -r kind status conid symbol qty stop target msg by created_status <<<"$row"
    hdr "#$id $kind $symbol: $status (IBKR snapshot $(stat -c %y "$SNAPSHOT" 2>/dev/null | cut -c1-19))"

    # --- audit and Telegram
    local last_to
    last_to="$(sql_one "select details->>'to' from audit_log where proposal_id = $id and event = 'STATUS_CHANGED' order by id desc limit 1;")"
    check "audit: PROPOSAL_CREATED row exists" [ -n "$created_status" ]
    check "audit: last STATUS_CHANGED.to (${last_to:-none}) = proposal.status ($status)" \
        [ "${last_to:-$created_status}" = "$status" ]
    if [ "$created_status" = "PROPOSED" ] && [ "$by" != "owner" ]; then
        check "Telegram: proposal message linked (telegram_msg_id=${msg:-null})" [ -n "$msg" ]
        check "audit: TELEGRAM_SENT row" [ "$(sql_one "select count(*) from audit_log where proposal_id = $id and event = 'TELEGRAM_SENT';")" -ge 1 ]
    elif [ "$created_status" = "REJECTED_RISK" ]; then
        check "Telegram: rejected at proposal time, so no message (telegram_msg_id null)" [ -z "$msg" ]
    fi

    # --- our orders: DB rows vs IBKR open orders
    local rows refs_ok=true n_entry=0
    rows="$(sql_rows "select order_ref, role, action, order_type, quantity, coalesce(limit_price::text,''), coalesce(aux_price::text,''), status, ib_order_id
        from broker_order where proposal_id = $id order by id;")"
    local entry_order_id=""
    while IFS='|' read -r ref role action otype oqty lmt aux ostatus oid; do
        [ -z "$ref" ] && continue
        [[ "$ref" =~ ^P$id-(ENTRY|TARGET|STOP|EXIT)$ ]] || refs_ok=false
        [ "$role" = "ENTRY" ] && { n_entry=$((n_entry + 1)); entry_order_id="$oid"; }
        local ib; ib="$(ib_order_ref "$ref")"
        if working "$ostatus"; then
            if [ -z "$ib" ]; then
                bad "$ref: DB says $ostatus but IBKR has no such open order"
                continue
            fi
            check "$ref: quantity DB $oqty = IBKR $(field "$ib" quantity)" same "$oqty" "$(field "$ib" quantity)"
            [ -n "$lmt" ] && check "$ref: limit DB $lmt = IBKR $(field "$ib" limitPrice)" same "$lmt" "$(field "$ib" limitPrice)"
            [ -n "$aux" ] && check "$ref: stop trigger DB $aux = IBKR $(field "$ib" auxPrice)" same "$aux" "$(field "$ib" auxPrice)"
            check "$ref: $action $otype at IBKR ($(field "$ib" action) $(field "$ib" orderType))" \
                [ "$(field "$ib" action)" = "$action" -a "$(field "$ib" orderType)" = "$otype" ]
            case "$role" in
                ENTRY) check "$ref: TIF DAY ($(field "$ib" tif))" [ "$(field "$ib" tif)" = "DAY" ] ;;
                TARGET|STOP) check "$ref: TIF GTC ($(field "$ib" tif))" [ "$(field "$ib" tif)" = "GTC" ] ;;
            esac
        else
            check "$ref: DB $ostatus and not working at IBKR" [ -z "$ib" ]
        fi
    done <<<"$rows"
    [ -n "$rows" ] && check "order refs are P$id-ENTRY/TARGET/STOP/EXIT" $refs_ok
    if [ "$kind" = "ENTRY" ] && [ -n "$rows" ]; then
        check "exactly one ENTRY order for #$id (got $n_entry)" [ "$n_entry" -eq 1 ]
        check "bracket: ENTRY, TARGET and STOP rows" \
            [ "$(grep -c -E "^P$id-(ENTRY|TARGET|STOP)\|" <<<"$rows")" -eq 3 ]
        local child
        for child in TARGET STOP; do
            local ib; ib="$(ib_order_ref "P$id-$child")"
            [ -n "$ib" ] && check "P$id-$child: child of IBKR order $entry_order_id (parentId $(field "$ib" parentId))" \
                [ "$(field "$ib" parentId)" = "$entry_order_id" ]
        done
    fi
    local ibref
    while read -r ibref; do
        [ -z "$ibref" ] && continue
        local r; r="$(field "$ibref" orderRef)"
        check "IBKR open order $r is in broker_order" grep -q "^$r|" <<<"$rows"
    done <<<"$(ib_orders_for "$id")"

    # --- executions
    local db_execs ib_execs
    db_execs="$(sql_rows "select x.ib_exec_id, b.order_ref, x.side, x.quantity from execution x join broker_order b on b.id = x.broker_order_id where b.proposal_id = $id;")"
    ib_execs="$(ib_execs_for "$id")"
    if [ -n "$ib_execs" ]; then
        while read -r e; do
            [ -z "$e" ] && continue
            local xid; xid="$(field "$e" execId)"
            check "IBKR execution $xid ($(field "$e" orderRef) $(field "$e" side) $(field "$e" quantity)) is in the DB" \
                grep -q "^$xid|" <<<"$db_execs"
        done <<<"$ib_execs"
    elif [ -n "$db_execs" ]; then
        warn "IBKR listed no executions for P$id-* to client 91 (it may only see its own; set IB Gateway's Master API client ID to 91 to see all). DB has $(grep -c . <<<"$db_execs")."
    fi

    # --- position
    local meta pos ibqty
    meta="$(sql_one "select origin, coalesce(proposal_id::text,''), coalesce(current_stop::text,''), coalesce(target_price::text,'') from position_meta where conid = $conid;")"
    pos="$(ib_position "$conid")"
    ibqty="$(norm "$(field "$pos" quantity)")"
    local m_origin m_pid m_stop m_target
    IFS='|' read -r m_origin m_pid m_stop m_target <<<"$meta"

    case "$kind:$status" in
        ENTRY:FILLED)
            check "position_meta: GATEWAY, proposal $id (got ${m_origin:-none} ${m_pid:-})" [ "$m_origin" = "GATEWAY" -a "$m_pid" = "$id" ]
            check "IBKR position for $symbol: ${ibqty:-none}" [ -n "$ibqty" -a "$ibqty" != "0" ]
            local net
            net="$(sql_one "select coalesce(sum(case when x.side='BUY' then x.quantity else -x.quantity end), 0)
                from execution x join broker_order b on b.id = x.broker_order_id where b.proposal_id = $id;")"
            check "net DB executions $net = IBKR position ${ibqty:-0}" same "$net" "${ibqty:-0}"
            local ibstop ibtgt; ibstop="$(ib_order_ref "P$id-STOP")"; ibtgt="$(ib_order_ref "P$id-TARGET")"
            check "a STOP is working at IBKR (no position without a stop)" [ -n "$ibstop" ]
            [ -n "$ibstop" ] && check "STOP quantity $(field "$ibstop" quantity) = position ${ibqty:-0}" same "$(field "$ibstop" quantity)" "${ibqty:-0}"
            [ -n "$ibstop" ] && check "position_meta.current_stop $m_stop = IBKR stop $(field "$ibstop" auxPrice)" same "$m_stop" "$(field "$ibstop" auxPrice)"
            [ -n "$ibtgt" ] && check "TARGET quantity $(field "$ibtgt" quantity) = position ${ibqty:-0}" same "$(field "$ibtgt" quantity)" "${ibqty:-0}"
            [ -n "$ibtgt" ] && check "position_meta.target_price $m_target = IBKR target $(field "$ibtgt" limitPrice)" same "$m_target" "$(field "$ibtgt" limitPrice)"
            ;;
        ENTRY:CLOSED)
            check "position_meta no longer points at #$id" [ "$m_pid" != "$id" ]
            check "nothing of P$id-* is working at IBKR" [ -z "$(ib_orders_for "$id")" ]
            if [ "$m_origin" = "EXTERNAL" ]; then warn "an EXTERNAL position in $symbol exists"; else
                check "IBKR shows no $symbol position (${ibqty:-none})" [ -z "$ibqty" -o "$ibqty" = "0" ]; fi
            ;;
        ENTRY:SUBMITTED|ENTRY:APPROVED)
            check "P$id-ENTRY is working at IBKR" [ -n "$(ib_order_ref "P$id-ENTRY")" ]
            ;;
        ENTRY:CANCELLED|ENTRY:FAILED|ENTRY:EXPIRED|ENTRY:REJECTED_*)
            check "nothing of P$id-* is working at IBKR" [ -z "$(ib_orders_for "$id")" ]
            [ "$status" != "CANCELLED" ] && [ "$status" != "FAILED" ] && \
                check "no broker_order rows (nothing was sent)" [ -z "$rows" ]
            ;;
        STOP_UPDATE:CLOSED)
            local eid; eid="$m_pid"
            if [ -z "$eid" ]; then warn "no position_meta for $symbol any more; stop checks skipped"; else
                local s; s="$(ib_order_ref "P$eid-STOP")"
                check "position_meta.current_stop $m_stop = proposal stop $stop" same "$m_stop" "$stop"
                check "broker_order P$eid-STOP aux_price = $stop" \
                    same "$(sql_one "select aux_price from broker_order where order_ref = 'P$eid-STOP';")" "$stop"
                check "IBKR P$eid-STOP trigger $(field "$s" auxPrice) = $stop" same "$(field "$s" auxPrice)" "$stop"
                check "audit: STOP_MOVED for #$eid or #$id" \
                    [ "$(sql_one "select count(*) from audit_log where event = 'STOP_MOVED' and proposal_id in ($id, $eid);")" -ge 1 ]
            fi
            ;;
        STOP_UPDATE:REJECTED_RISK)
            check "no broker_order rows for a rejected stop update" [ -z "$rows" ]
            ;;
        EXIT:CLOSED)
            check "P$id-EXIT row is Filled" [ "$(sql_one "select status from broker_order where order_ref = 'P$id-EXIT';")" = "Filled" ]
            local sold; sold="$(sql_one "select coalesce(sum(x.quantity),0) from execution x join broker_order b on b.id = x.broker_order_id where b.order_ref = 'P$id-EXIT';")"
            check "sold $sold = proposal quantity $qty" same "$sold" "$qty"
            if [ "$m_origin" = "EXTERNAL" ]; then warn "an EXTERNAL position in $symbol exists"; else
                check "IBKR shows no $symbol position (${ibqty:-none})" [ -z "$ibqty" -o "$ibqty" = "0" ]
                check "no GATEWAY position_meta for $symbol" [ "$m_origin" != "GATEWAY" ]; fi
            ;;
        *) echo "  (no state-specific checks for $kind $status)" ;;
    esac
    summary
}

# ---------------------------------------------------------------------------------------------------------

c="${1:-help}"; shift || true
case "$c" in
    help|-h|--help) cmd_help ;;
    preflight) cmd_preflight "$@" ;;
    health) cmd_health ;;
    account) mcp_call get_account_summary | pretty ;;
    positions) mcp_call get_positions | pretty ;;
    quote) mcp_call get_quote "{\"symbol\":\"${1:?symbol}\"}" | pretty ;;
    status) mcp_call get_proposal_status "{\"proposalId\":$(int_arg "${1:-}")}" | pretty ;;
    mcp) mcp_call "${1:?tool}" "${2:-}" | pretty ;;
    entry-args) cmd_entry_args "$@" ;;
    propose-entry) cmd_propose_entry "$@" ;;
    stop-args) cmd_stop_args "$@" ;;
    exit-args) cmd_exit_args "$@" ;;
    db) cmd_db "$@" ;;
    ibkr) cmd_ibkr ;;
    verify) cmd_verify "$@" ;;
    audit-since) cmd_audit_since "$@" ;;
    recon-since) cmd_recon_since "$@" ;;
    nokey) cmd_nokey ;;
    app-stop) cmd_app_stop ;;
    app-start) cmd_app_start ;;
    ibg-restart) cmd_ibg_restart ;;
    logs) cmd_logs "$@" ;;
    *) echo "unknown command: $c" >&2; cmd_help; exit 2 ;;
esac
