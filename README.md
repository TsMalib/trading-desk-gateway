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
