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
the CDI event `BrokerReconnected`. In this stage only the read methods are implemented; the write
methods throw `UnsupportedOperationException` (run IB Gateway with `READ_ONLY_API=yes`).

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

Commands: `/status`, `/positions`, `/pending`, `/kill` and `/resume` (kill switch: blocks new entries at
proposal and approval time), `/flatten` (asks for confirmation, valid 60 s: kill switch on, open proposals
cancelled, our working orders cancelled, every long position sold at market as an `EXIT` proposal of its
own; a position that still has a working SELL order, e.g. a manual stop, is not sold). Commands older than
2 minutes are ignored.

Other packages send alerts with `AlertService.info / warning / critical` (async, never throws, the same
alert at most once per 5 minutes).

Run the read methods against a running paper IB Gateway (refuses live ports 4001/7496):

```
IB_PAPER_TEST=true mvn test -Dtest=IbkrBrokerPaperTest
```
