# End-to-end run against the paper account

One manual run, during US market hours, of the whole path with real IBKR paper orders: a research bot's
`propose_order` (played by you in the MCP Inspector), approval in Telegram, the bracket at IBKR, a stop
move, an exit, and then the failure drills from section 11 of the guide ("Paper trading, go-live and
operations"). After every step the database, the Telegram messages and IBKR must agree.

Helper: `scripts/e2e-paper.sh` (run `scripts/e2e-paper.sh help`). It reads the MCP tools, the database and
IBKR, and it can create proposals like a bot. It never approves anything, and it never places, cancels or
modifies an order. Approvals happen only in Telegram.

The run passes when every box below is ticked and every `verify` ends with `0 failed`.

## 0. Preconditions

Two of these are hard requirements in the code. If either fails, every entry is `REJECTED_RISK`.

| Requirement | Why | How to check |
|---|---|---|
| Paper account **base currency is USD** | The risk engine has no FX conversion yet. For a USD stock in a non-USD account, `FX_RATE` and `CASH` fail closed (`RiskContextBuilder`, "known limits"). | `preflight`: `baseCurrency` is `USD` |
| **Live** market data for US stocks on the paper account (`IB_MARKET_DATA_TYPE=1`) | `QUOTE` fails on delayed data, both at proposal and at approval. | `preflight`: `delayed=false`. In Client Portal, the paper account must share the live account's market data subscriptions. |
| `DESK_MODE=paper`, `IB_PORT=4004`, IB Gateway `TRADING_MODE=paper` | Paper only | `/status` shows `[PAPER]`, account `DU...` |
| `RISK_WHITELIST` contains `AAPL,JPM,XOM` | AAPL for the main run; JPM and XOM for the drills. They are in different industries, so `MAX_PER_INDUSTRY` (2) stays out of the way. | `preflight`, `/status` |
| No open positions or proposals from earlier tests | Keeps the reconciler checks clean | `/positions`, `/pending` |
| A normal trading day (not a half day) | The times below assume a 16:00 close. On a half day (13:00 close) the entry window ends at 12:30. | IBKR calendar |

Default risk limits that shape the run: 3 entries **sent** per New York day, 1% maximum drift, stop
1–15% below entry, reward/risk at least 2.0, entry window 09:45 to 15:30 ET. Unfilled entries are
cancelled at 15:55 ET.

## 1. Setup (once)

On your PC (Git Bash):

```bash
# Tools: curl, jq (winget install jqlang.jq), JDK 21 + Maven (for the IBKR snapshot), Node (MCP Inspector)
export DESK_URL=https://<tunnel-host>
export MCP_API_KEY=...                          # from the Portainer stack, never commit it
export DESK_PSQL="ssh homelab docker exec -i <postgres-container> psql -U desk -d desk"
export DESK_SSH=homelab                         # docker commands for the drills run there
# Paper API for the read-only IBKR snapshot (client id 91), in a second terminal:
ssh -N -L 4002:127.0.0.1:4002 homelab
```

`APP_CONTAINER` (default `trading-desk-gateway-1`) and `IBG_CONTAINER` (default `ib-gateway`) override
the container names. Check them with `ssh homelab docker ps`.

**Seeing IBKR.** IB Gateway has no order window. The script's `ibkr` command connects a second read-only
API client (`IbkrBrokerPaperTest`, client id 91) and lists the account, positions, all open orders and
executions. Open orders come from `reqAllOpenOrders`, so client 91 sees the gateway's orders. IBKR may only
report executions to the client that placed them. If `verify` warns about that, set IB Gateway's
*Master API client ID* to 91 (VNC → Configure → Settings → API), or accept the warning: fills are still
checked through the DB and positions. Do not log in to TWS, IBKR Mobile or the Client Portal trading screens
with the paper user during the run. A second session takes over IB Gateway's session
(`EXISTING_SESSION_DETECTED_ACTION=primary` takes it back, but you get a disconnect), except in drill 7,
where that is expected.

**MCP Inspector**

```bash
npx @modelcontextprotocol/inspector
```

Transport **Streamable HTTP**, URL `https://<tunnel-host>/mcp`. Under *Authentication*, add the header
`X-Desk-Key` with the key, then **Connect** and **List Tools**. You should see seven tools and no
place/cancel/modify tool. Paste the JSON that `entry-args` prints into the tool's fields. Prices are
strings (`"187.25"`).

- [ ] `scripts/e2e-paper.sh preflight` ends with `0 failed`
- [ ] Telegram `/status`: `[PAPER]`, connected, Kill `off`, 0 proposals waiting
- [ ] MCP Inspector lists exactly: get_account_summary, get_positions, get_quote, propose_order, propose_exit,
      propose_stop_update, get_proposal_status

## 2. Plan of the day (New York time)

| ET | What | Entries sent today |
|---|---|---|
| 09:45–10:30 | Main run, steps A–C: AAPL entry, approve (drill 3), fill | 1 |
| while AAPL is open | Drills 1 (stop the app), 2 (restart IB Gateway), 6 (lower stop) | 1 |
| ~11:00 | Steps D–E: stop move, exit, final agreement check | 1 |
| ~11:30 | Drill 4 (drift, JPM), drill 9 (no key) | 1 (rejected, not sent) |
| ~12:00 | Drill 7 (manual trade, a non-whitelisted stock, e.g. KO) | 1 |
| 15:05 | Drill 5, part 1: propose XOM, do not tap | 1 |
| 15:10–15:25 | Drill 8, part 1: AAPL entry that fills + JPM entry that rests, approve both | 3 |
| 15:31 | Drill 5, part 2: tap Approve on XOM, expect EXPIRED | 3 |
| 15:35–15:50 | Drill 8, part 2: `/kill`, `/flatten`; then sell KO by hand | 3 |

The order matters. Only sent entries count toward the limit of 3 per day, so the XOM proposal must be made
before the third entry is sent. After `/kill` no new entry can be proposed. It is fine to split this over
two days (day 1: A–E and drills 1, 2, 3, 4, 6, 9; day 2: drills 7, 5, 8).

Write down the UTC time before each drill (`date -u +%FT%TZ`). `audit-since` and `recon-since` take it.

## 3. Main run

### A. Propose (as the bot)

```bash
scripts/e2e-paper.sh entry-args AAPL        # marketable limit just above the ask; stop -2%, target +5%
```

Paste the JSON into **propose_order** in the Inspector and run it.

- [ ] The tool answers `Proposal <id> created (ENTRY AAPL, paper account), status PROPOSED`, with quantity,
      risk, reward/risk and `Warnings: none` (or a 30-day-rule warning, if AAPL was sold at a loss in
      the last 30 days). Note the id as **E1**.
- [ ] Telegram: one message `[PAPER] #E1 BUY AAPL · Apple Inc` with Entry `LMT`, Stop and Target with
      their % distance, Size, Risk (% of equity), R/R, Last with drift, Thesis, Sources, `Expires HH:mm ET ·
      by e2e-manual`, and the buttons **✅ Approve** and **❌ Reject**
- [ ] `scripts/e2e-paper.sh status E1` → `PROPOSED`
- [ ] `scripts/e2e-paper.sh verify E1` → `0 failed` (PROPOSAL_CREATED, TELEGRAM_SENT, telegram_msg_id set,
      no broker_order rows, nothing at IBKR)

### B. Approve, with drill 3 (double tap)

Tap **✅ Approve** twice, as fast as you can.

- [ ] First tap: toast `Re-checking #E1 with a fresh quote…`. The message is edited to
      `✅ APPROVED HH:mm ET · all checks passed again (last …)`, with no failed checks.
- [ ] Second tap: `Proposal #E1 is already APPROVED/SUBMITTED. Nothing changed.`, or a second re-check
      that changes nothing
- [ ] **Drill 3:** `db E1` shows exactly three broker_order rows, `P<E1>-ENTRY`, `-TARGET`, `-STOP`
      (`order_ref` is unique in the DB). `verify E1` passes `exactly one ENTRY order`.
- [ ] audit_log for E1: `STATUS_CHANGED PROPOSED→APPROVED` (actor USER, with telegramUser), then
      `ORDERS_STORED`, `STATUS_CHANGED APPROVED→SUBMITTED`, `ORDER_STATUS` rows

### C. The bracket at IBKR, then the fill

```bash
scripts/e2e-paper.sh ibkr          # all open orders as IBKR reports them
scripts/e2e-paper.sh verify E1
```

- [ ] IBKR has `P<E1>-ENTRY` BUY LMT at the entry limit, **TIF DAY**; `P<E1>-TARGET` SELL LMT at the target,
      **GTC**; `P<E1>-STOP` SELL STP with auxPrice = the stop, **GTC**. Both children have
      `parentId` = the ENTRY's orderId, and all three have the same quantity as the proposal.
      (If the entry fills at once, only TARGET and STOP are still open. That is fine.)
- [ ] Telegram alert `ℹ️ #E1 AAPL: bought <qty> at <avg>. Stop <stop>, target <target>.`
- [ ] `status E1` → `FILLED`
- [ ] `verify E1` → `0 failed`: position_meta GATEWAY for E1; IBKR position = net DB executions; a STOP is
      working for the position's quantity at `current_stop`; the TARGET is at `target_price`
- [ ] `/positions`: `AAPL <qty> @ <avg> USD`, then `stop <stop> · target <target>`, not marked external
- [ ] `get_positions` in the Inspector: origin `GATEWAY`, proposalId E1, the same stop and target

If the price falls 2% after the fill, the stop fills and E1 closes (`closed by STOP`). Start over with A.
If it rises one full R (+2%), the break-even job moves the stop to the entry and alerts. That is expected;
use the new stop as the "current stop" below.

Do drills 1, 2 and 6 now, while AAPL is open (section 4), then continue with D.

### D. Move the stop (propose_stop_update)

Choose a new stop above the current stop and below the last price (`quote AAPL`), for example halfway between
the stop and the entry.

```bash
scripts/e2e-paper.sh stop-args AAPL <newStop>
```

Run **propose_stop_update** with it. Note the id as **S1**.

- [ ] The tool answers `Proposal S1 created (STOP_UPDATE AAPL, …)`
- [ ] Telegram: `#S1 MOVE STOP AAPL` with `Stop <old> → <new> (+x.xx%)`, Last and the share count
- [ ] Approve → `✅ APPROVED`, then the alert `#S1 AAPL: stop moved <old> -> <new>.`
- [ ] `status S1` → `CLOSED` ("done: position closed or stop moved")
- [ ] `verify S1` → `0 failed`: `P<E1>-STOP` keeps its ref and order id. Its aux_price in broker_order, its
      trigger at IBKR and position_meta.current_stop all equal the new stop. STOP_MOVED is in audit_log.
- [ ] `verify E1` still passes (same quantity, new stop)

### E. Exit (propose_exit)

```bash
scripts/e2e-paper.sh exit-args AAPL
```

Run **propose_exit** with it (no quantity, so the whole position). Note the id as **X1**.

- [ ] Telegram: `#X1 SELL AAPL` with `Sell <qty> sh MKT` and `Held <qty> sh (all)`, current stop and target, Reason
- [ ] Approve → `✅ APPROVED`. The gateway cancels `P<E1>-TARGET` and `P<E1>-STOP`, waits for IBKR to confirm,
      then sends `P<X1>-EXIT` MKT SELL.
- [ ] Alert `#X1 AAPL: sold <qty> at market, <avg>. Realized P&L: …`
- [ ] `status X1` → `CLOSED`; `status E1` → `CLOSED`
- [ ] `verify X1` → `0 failed`: the EXIT row is Filled, sold = quantity, no AAPL position at IBKR, no position_meta
- [ ] `verify E1` → `0 failed`: nothing of `P<E1>-*` is still working (TARGET and STOP `Cancelled`)
- [ ] `/positions` → `No positions.` (or only positions unrelated to the run)

### F. Agreement check after the main run

- [ ] `db E1`, `db S1`, `db X1`: every status change has its audit_log row; times are UTC
- [ ] Execution prices and commissions in the DB match the IBKR snapshot (`ibkr`). Check `realized_pnl`
      on the EXIT fill, and note its currency (CLAUDE.md: "check its currency on your first fill").
- [ ] Every Telegram message of the run starts with `[PAPER]`
- [ ] One Telegram message per proposal, edited in place. No duplicate proposal messages; alerts once each.

## 4. Failure drills (guide, section 11)

### Drill 1: stop the gateway container while a bracket is open

While E1 is FILLED, with TARGET and STOP working:

```bash
date -u +%FT%TZ                       # T1
scripts/e2e-paper.sh app-stop
scripts/e2e-paper.sh ibkr             # the app is down; IB Gateway still answers
scripts/e2e-paper.sh app-start        # wait ~1 min
scripts/e2e-paper.sh recon-since T1
scripts/e2e-paper.sh verify E1
```

- [ ] With the app stopped, IBKR still shows `P<E1>-STOP` (and `-TARGET`) working, GTC, at the same price
- [ ] After the start: `/status` answers and readiness is UP (`health`)
- [ ] `recon-since T1` shows **no** `RECON_*` rows (a pass with differences writes `RECON_DONE`), and
      `logs` has no `Reconcile: N difference(s)` line. `recon_last_run` moved past T1.
- [ ] No Telegram alerts, except possibly a restart notice; `verify E1` → `0 failed`

### Drill 2: restart IB Gateway mid-session

```bash
date -u +%FT%TZ                       # T2
scripts/e2e-paper.sh ibg-restart      # paper login, usually no 2FA; watch: ssh homelab docker logs -f ib-gateway
```

- [ ] While it is down: `health` is DOWN, `/status` says IBKR unavailable, and `get_quote` answers
      `The broker is not connected right now`
- [ ] After `Login has completed`: exactly **one** alert,
      `⚠️ IBKR connection restored (IB Gateway socket reconnected). Check positions and orders with /positions.`
      Not a flood: the same alert is sent at most once per 5 minutes.
- [ ] `recon-since T2`: the reconnect pass found no differences; `verify E1` → `0 failed`; the stop is still at IBKR

### Drill 3: tap Approve twice quickly

Done in step B: exactly one bracket.

### Drill 4: approve after the price moved more than 1%

```bash
scripts/e2e-paper.sh propose-entry JPM edge   # entry limit 0.95% below last: passes DRIFT at proposal time
```

Note the id **D1**. Do not tap yet. Watch `scripts/e2e-paper.sh quote JPM` until
`|entryLimit − last| / last > 1%` (last above entryLimit ÷ 0.99). Usually that takes minutes. Then tap
**✅ Approve**. If the price drifts the other way, reject D1 and propose again.

- [ ] The message is edited to `⛔ REJECTED HH:mm ET · the re-check with a fresh quote failed:` with
      `• DRIFT: x.xx% (limit <= 1%)`
- [ ] `status D1` → `REJECTED_RISK`, failedChecks contains DRIFT
- [ ] `verify D1` → `0 failed`: audit `PROPOSED→APPROVED` (USER), then `APPROVED→REJECTED_RISK` (SYSTEM); no
      broker_order rows; nothing at IBKR

### Drill 5: approve after 15:30 New York

An ENTRY expires at the end of the entry window (30 minutes before the close) or after 30 minutes,
whichever is first.

At about 15:05 ET: `scripts/e2e-paper.sh propose-entry XOM passive` → id **T5**.

- [ ] The Telegram message says `Expires 15:30 ET`
- [ ] Do not tap until 15:30. The expiry job runs every minute, so by the time you tap, the message may
      already read `⌛ EXPIRED HH:mm ET · not decided in time`. Tap **✅ Approve** anyway at 15:30–15:31.
- [ ] The tap answers `Proposal #T5 is already EXPIRED. Nothing changed.`, or the approval itself expires it.
      Either way, `status T5` → `EXPIRED` and nothing was sent (`verify T5` → `0 failed`).
- [ ] A new `propose_order` after 15:30 is `REJECTED_RISK` with `ENTRY_WINDOW` failed

### Drill 6: propose_stop_update with a lower stop

While E1 is open: run **propose_stop_update** with a new stop below the current stop.

- [ ] The tool answers `Proposal <id> rejected (STOP_UPDATE AAPL). Failed checks:` with `STOP_ABOVE_CURRENT`
- [ ] No Telegram message for it; `verify <id>` → `0 failed` (REJECTED_RISK, no orders); IBKR's stop unchanged

### Drill 7: a manual trade in the IBKR app

Buy 1 share of a stock that is **not** in the whitelist (e.g. KO) in IBKR Mobile or TWS with the paper user.
That login takes over IB Gateway's session, so you also get drill 2's single "connection restored" alert when
IB Gateway takes it back. Log out of the app right after the order fills.

```bash
date -u +%FT%TZ          # T7; the reconciler runs every 5 minutes and after the reconnect
scripts/e2e-paper.sh recon-since T7
```

- [ ] Exactly **one** alert: `⚠️ KO: IBKR reports 1 shares at … that this service did not buy. Recorded as
      EXTERNAL: it is never sold, stopped or changed here, and /flatten leaves it alone. It has NO stop order.` Possibly
      also `External trade at IBKR, not placed by this service: BUY 1 KO …`, if the execution is visible to the
      gateway's client.
- [ ] `RECON_POSITION_EXTERNAL` in audit_log; position_meta for KO with `origin = EXTERNAL`
- [ ] The next 5-minute pass sends **no** new alert for KO
- [ ] `/positions` marks KO `· external`; `get_positions` shows origin `EXTERNAL`

Keep KO until after drill 8: `/flatten` must leave it alone.

### Drill 8: /kill, then /flatten

At 15:10–15:25 ET, propose and approve two entries:

```bash
scripts/e2e-paper.sh propose-entry AAPL fill      # → K1, fills: a GATEWAY position with a stop
scripts/e2e-paper.sh propose-entry JPM passive    # → K2, limit 0.9% below last: should rest unfilled
```

Approve both in Telegram. Check that K1 is `FILLED` and K2 `SUBMITTED` (`verify K1`, `verify K2`). If K2
fills too, the drill still works with two positions. After drill 5 (15:31), with KO still held:

```bash
date -u +%FT%TZ          # T8
```

1. Send `/kill`.
   - [ ] Reply `🛑 Kill switch ON. New entries are refused at proposal and at approval. Cancelled the unfilled part
         of P<K2>-ENTRY.` Stops and targets stay.
   - [ ] `status K2` → `CANCELLED`; IBKR no longer has `P<K2>-*`; `verify K2` → `0 failed`
   - [ ] K1's `P<K1>-STOP` and `-TARGET` are still working at IBKR
   - [ ] A new `propose_order` is `REJECTED_RISK` with `KILL_SWITCH`; `get_account_summary` shows `killSwitch: true`
2. Send `/flatten`.
   - [ ] Prompt `🔥 Flatten?` that lists K1's AAPL (not KO) and the **🔥 Confirm flatten** button (valid 60 s)
   - [ ] Confirm → `Flattening: cancelling orders and selling positions…`, then a summary
   - [ ] A new EXIT proposal (proposed_by `owner`) for AAPL → `CLOSED`, sold at market; K1 → `CLOSED`
   - [ ] IBKR: no AAPL position, no `P<K1>-*` order working. The stops of the closed positions are gone.
   - [ ] KO is still held and still EXTERNAL (it was skipped)
   - [ ] audit_log: `KILL_SWITCH_ON`, `KILL_SWITCH_CANCEL_ENTRY`, `FLATTEN_REQUESTED`, `FLATTEN_DONE`
3. Clean up: sell KO by hand in the app, then log out.
   - [ ] On the next pass: `ℹ️ KO: the EXTERNAL position is no longer reported by IBKR; forgotten.`
   - [ ] `/resume` → `▶️ Kill switch off`

### Drill 9: the MCP endpoint without the key

```bash
scripts/e2e-paper.sh nokey
```

- [ ] No `X-Desk-Key` → 401; a wrong key → 401 (body `missing or invalid X-Desk-Key header`)
- [ ] The Inspector with the header removed fails to connect

## 5. End of the run

- [ ] `/positions` → no positions from the run; `/pending` → `No open proposals.`; Kill `off`
- [ ] `scripts/e2e-paper.sh ibkr`: no open orders with a `P…-` ref
- [ ] `audit-since <start of run>`: every proposal's status changes are there, and every alert you saw has a
      matching event
- [ ] No `ORDER_OUTCOME_UNKNOWN`, `STATE_MISMATCH`, `RECON_UNRESOLVED` or `POSITION_UNPROTECTED` rows, unless a
      drill explains them
- [ ] Note anything that disagreed (DB vs Telegram vs IBKR) below, with the proposal id and the `verify` output

## 6. Record

| Date | Step / drill | Proposal ids | Result | Notes |
|---|---|---|---|---|
| | A–F main run | E1, S1, X1 | | |
| | 1 stop the app | E1 | | |
| | 2 restart IB Gateway | E1 | | |
| | 3 double tap | E1 | | |
| | 4 drift | D1 | | |
| | 5 after 15:30 | T5 | | |
| | 6 lower stop | | | |
| | 7 manual trade | — | | |
| | 8 /kill, /flatten | K1, K2, exit | | |
| | 9 no key | — | | |
