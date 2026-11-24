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

Run the read methods against a running paper IB Gateway (refuses live ports 4001/7496):

```
IB_PAPER_TEST=true mvn test -Dtest=IbkrBrokerPaperTest
```
