# trading-desk-gateway

Quarkus 3 / Java 21 service between AI research bots (MCP) and Interactive Brokers.
Bots propose; the owner approves in Telegram; only this service places orders.
See `CLAUDE.md` for the rules.

## Requirements

- JDK 21+ (the build targets release 21)
- Maven 3.9+
- Docker (Dev Services starts Postgres for `quarkus:dev` and tests)

## IBKR TWS API jar

The TWS API is not on Maven Central. `lib/TwsApi.jar` and `lib/pom.xml` (from the IBKR
API distribution, version 10.50.01) are installed into your local Maven repository as
`com.interactivebrokers:tws-api:10.50.01`.

This happens automatically: the `install-tws` profile in `pom.xml` activates when the jar
is missing from `~/.m2` and installs it in the `validate` phase. To (re)install by hand:

```
mvn -Pinstall-tws validate
```

or directly:

```
mvn install:install-file -Dfile=lib/TwsApi.jar -DpomFile=lib/pom.xml
```

When upgrading the API, replace both files in `lib/` and bump `tws-api.version` in `pom.xml`.
If you use a non-default local repository (`-Dmaven.repo.local`), run the install step by hand.

## Configuration

All config lives under `desk.*` (`DeskConfig`). Every environment variable is listed in
`.env.example`; copy it to `.env` and fill it in. Secrets come from the environment only.

## Deployment

Files: `Dockerfile` (Maven build that installs `lib/TwsApi.jar`, then a JVM runtime image),
`ib-gateway.yml` (IB Gateway only, its own stack), `docker-compose.yml` (the app + cloudflared, image from
GHCR), `.github/workflows/release.yml` (tests, then builds and pushes
`ghcr.io/tsmalib/trading-desk-gateway:<tag>` on every `v*` tag). Step-by-step Portainer setup, Postgres
and rollback: [`docs/portainer.md`](docs/portainer.md).

```
            internet ── Cloudflare Tunnel
                              │
   stack trading-desk   ┌─────┴──────┐  edge   ┌─────────┐
                        │ cloudflared│─────────│ gateway │──── Postgres (existing; shared network
                        └────────────┘         └────┬────┘      or host IP:5432)
                                                    │ trading-desk (external network)
   stack ib-gateway                           ┌─────┴──────┐
                                              │ ib-gateway │ 4004 paper / 4003 live
                                              └────────────┘
```

Nothing is published on `0.0.0.0`. IB Gateway publishes only `127.0.0.1:4002` (paper API) and
`127.0.0.1:5900` (VNC, if enabled).

### First start

1. On the Docker host: `docker network create trading-desk`.
2. Create the database and user on your Postgres (`create user desk ...; create database desk owner desk;`).
   Flyway creates the schema on the first start.
3. Tag a release: `git tag v0.1.0 && git push origin v0.1.0`; wait for the `release` workflow.
4. Portainer: add GHCR as a registry with a `read:packages` token.
5. Deploy stack `ib-gateway` from `ib-gateway.yml` with the **paper** credentials and
   `TRADING_MODE=paper`. Confirm 2FA if asked; `docker logs ib-gateway` shows `Login has completed`.
6. Deploy stack `trading-desk` from `docker-compose.yml` with `DESK_VERSION=v0.1.0`, `DESK_MODE=paper`,
   `IB_PORT=4004` and the rest of the variables (`docs/portainer.md`, section 3).
7. Check: Telegram `/status` answers with `[PAPER]`; `GET https://<tunnel-host>/q/health/ready` is UP
   (DOWN means IB Gateway is not connected). Point the research bots at `https://<tunnel-host>/mcp` with
   header `X-Desk-Key`.

For local testing on the host, the paper API is on `127.0.0.1:4002` (SSH-tunnel it to your laptop:
`ssh -L 4002:127.0.0.1:4002 homelab`), then `mvn quarkus:dev` or `IbkrBrokerPaperTest` as below.

### Switching paper to live

The switch is `desk.mode` (env `DESK_MODE`, `paper|live`); it is shown in every Telegram message. The
app does not cross-check it against the port, so change all of these together:

1. Telegram `/pending`: no open proposals. `/positions`: decide what happens to paper positions (they stay
   in the paper account; the live reconciler will not see them).
2. Stack `ib-gateway`: `TWS_USERID` / `TWS_PASSWORD` = the **live** credentials, `TRADING_MODE=live`.
   Update the stack. IB Gateway restarts and needs a **fresh 2FA** on IBKR Mobile; confirm it and wait
   for `Login has completed`.
3. Stack `trading-desk`: `DESK_MODE=live`, `IB_PORT=4003`. Update the stack.
4. Telegram `/status` must now show `[LIVE]` and the live account. The first reconciliation
   imports any existing live positions as `EXTERNAL`.

Back to paper: the same steps with the paper credentials, `TRADING_MODE=paper`, `DESK_MODE=paper`,
`IB_PORT=4004`. The loopback port `127.0.0.1:4002` serves only paper; the live API is never published.

The database keeps paper history next to live history. For a clean live start, use a separate database
(`DESK_DB_URL`) for live.

### Rotating the MCP API key

There is one key (`desk.mcp.apiKey`, env `MCP_API_KEY`), read at startup; the old key stops working the
moment the new one is deployed.

1. `openssl rand -hex 32`
2. Portainer > stack `trading-desk` > environment: replace `MCP_API_KEY` > Update the stack. Only the app
   restarts; IB Gateway (and its 2FA session) is untouched. Open proposals and orders survive: state is in
   IBKR and the database, and the reconciler runs at startup.
3. Update the `X-Desk-Key` header in every research bot's MCP configuration. Until then their calls get
   401.

Rotate immediately if the key was exposed; also rotate `TELEGRAM_BOT_TOKEN` (@BotFather `/revoke`) the
same way if the bot token leaked.

## Run

```
mvn quarkus:dev     # Postgres via Dev Services, Flyway migrates at start
mvn test            # Testcontainers Postgres via Dev Services
```

Health: `GET /q/health` (liveness `/q/health/live`, readiness `/q/health/ready`).
Readiness is DOWN while IB Gateway is not connected.

## Broker

`broker.Broker` is the only way to talk to IBKR. `desk.broker` (build time) picks the bean:
`ibkr` (`IbkrBroker`, TWS API; default) or `fake` (`FakeBroker`, in memory; the test profile).
`IbkrBroker` connects in the background at startup and reconnects with backoff (1s doubling to
`desk.ib.reconnectMaxBackoff`, default 60s) when IB Gateway restarts. After each reconnect it fires
the CDI event `BrokerReconnected`. Order news (orderStatus, execDetails joined with the commission
report, late order errors) arrives as the CDI event `BrokerOrderEvent`. Write calls wait for IBKR's
acknowledgement: `BrokerUnavailableException` means nothing was sent, `BrokerTimeoutException` means
sent with an unknown outcome (the reconciler settles it). Writes need IB Gateway with `READ_ONLY_API=no`.

## Orders

`proposal.OrderExecutor` observes `ProposalApproved` and every `BrokerOrderEvent` on one worker thread:

- ENTRY: bracket `P<id>-ENTRY/-TARGET/-STOP`, not sent when the ref is already in `broker_order` or working
  at IBKR; rows stored before sending. Complete parent fill: `FILLED` + `position_meta`. Target or stop
  fill: `CLOSED` with a realized P&L alert. A partial fill keeps `SUBMITTED`; if the parent then ends,
  `FILLED` with that quantity and target/stop are lowered to it.
- EXIT: cancels our target/stop, waits up to `desk.orders.cancelTimeout` for IBKR to confirm, then sells the
  position IBKR reports at market (`P<id>-EXIT`). Partial exits are refused.
- STOP_UPDATE: modifies the stop's trigger in place, up only.
- Every minute in the session (`OrderJobs`): break-even (last >= entry + (entry - original stop) moves the
  stop to the entry, once) and the end-of-day cancel of unfilled entries 5 minutes before IBKR's close.
- IBKR rejections: `FAILED` with the error in `audit_log` and an alert; a refused bracket leg takes the
  whole bracket down.

`recon.Reconciler` runs at startup (once IBKR is connected), after each reconnect and every 5 minutes, on
the order executor's worker. Each pass first runs `recon.OrderReconciler` (replays our executions and order
statuses, so events missed while disconnected still move the proposals), then compares IBKR with the
database. Every difference writes an `audit_log` row (actor `RECONCILER`, events `RECON_*`):

- an execution since the last run that the DB lacks: linked by orderRef and applied like a live fill, or
  stored with `broker_order_id` null (EXTERNAL);
- a position the DB does not know: `position_meta` origin `EXTERNAL`, one alert, never touched again
  (`/flatten` skips it);
- a GATEWAY position without a working stop: critical alert on every pass;
- a GATEWAY position whose quantity differs from its stored executions (split, corporate action): one alert
  per new quantity; a stop or target for more shares than held is lowered to the position, one for fewer
  shares raises a critical alert;
- a `position_meta` IBKR shows no position for: GATEWAY → entry `CLOSED` with the P&L of the stored SELL
  executions and its leftover orders cancelled; EXTERNAL → forgotten. Both alert.

Daily loss (`proposal.DailyLossGuard`): at 09:30 New York `start_of_day_equity` is recorded (or at the first
check after the open, if the service was down then). Every minute, today's P&L = NetLiquidation −
start_of_day_equity, which is realized + unrealized P&L in the base currency. At or below
`-desk.risk.dailyLossPct` the kill switch is engaged and a critical alert sent, once per day.

Weekly summary (`telegram.WeeklySummary`): Fridays 10 minutes after IBKR's close (13:10 on a half day),
the week's closed trades with P&L and R, win rate, average R, open risk (entry − stop per position) and
30-day-rule warnings.

Check it against the paper account while `mvn quarkus:dev` runs (dev profile only; these endpoints
are not in a prod build):

```
GET /dev/broker/snapshot               # summary, positions, open orders
GET /dev/broker/contract/AAPL          # conid, ISIN, industry, today's session
GET /dev/broker/quote/AAPL
GET /dev/broker/executions?since=2026-11-20T00:00:00Z
```

## MCP

Research bots connect to `POST /mcp` (streamable HTTP; legacy SSE at `/mcp/sse`). Every request under
`/mcp` needs header `X-Desk-Key: $MCP_API_KEY` (401 otherwise) and is rate limited to
`desk.mcp.rateLimitPerMinute` (default 30) per minute: one budget for authenticated requests, one per
client IP for the rest (429 with `Retry-After`). A client that re-initializes for every call uses 3
requests per call.

Tools: `get_account_summary`, `get_positions`, `get_quote`, `propose_order`, `propose_exit`,
`propose_stop_update`, `get_proposal_status`. None sends anything to IBKR. Prices and quantities are
decimal strings (`"187.25"`).

A proposal is stored as `PROPOSED` (fires the CDI event `ProposalCreated`, expires after
`desk.proposals.ttl`, default 30m; an ENTRY at the latest at the end of the entry window,
`closeBufferMinutes` before the day's close as IBKR reports it) or `REJECTED_RISK` with every check in
`risk_result`. See `ProposalStatus` for the state machine; every transition writes `audit_log`.

## Telegram

The bot long-polls `getUpdates` (timeout 50 s) on the managed executor and only handles updates whose
chat is `TELEGRAM_CHAT_ID` and whose sender is `TELEGRAM_USER_ID`; everything else is ignored. Every
message starts with `[PAPER]` or `[LIVE]`.

Each `PROPOSED` proposal is sent with **Approve** / **Reject** buttons (`a:<id>` / `r:<id>`). A tap is
answered at once, then the message is edited to show the outcome:

- Reject: `REJECTED_USER`.
- Approve: `ProposalService.approve` re-runs every check with fresh IBKR data (quote, account, positions,
  open orders). All pass: `APPROVED` and the CDI event `ProposalApproved` (an entry's quantity can only go
  down). A check fails: `APPROVED` then `REJECTED_RISK`, and the message lists the failed checks. IBKR
  unreachable: nothing changes and the buttons stay.
- A tap on a proposal that is already decided only says so.

Commands: `/status`, `/positions`, `/pending`, `/kill` (kill switch on: blocks new entries at proposal and
approval time, cancels the unfilled part of every working ENTRY parent and approved entries not sent yet;
stops and targets stay), `/resume`, `/flatten` (asks for confirmation, valid 60 s: kill switch on, open
proposals cancelled, our working orders cancelled, every GATEWAY position sold at market as an `EXIT`
proposal of its own; EXTERNAL positions and a position that still has a working SELL order, e.g. a manual
stop, are not sold). Commands older than 2 minutes are ignored.

Other packages send alerts with `AlertService.info / warning / critical` (async, never throws, the same
alert at most once per 5 minutes).

Run the read methods against a running paper IB Gateway (refuses live ports 4001/7496):

```
IB_PAPER_TEST=true mvn test -Dtest=IbkrBrokerPaperTest
```
