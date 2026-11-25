# Deploying on the homelab with Portainer

Two stacks on one Docker host, both on the external network `trading-desk`:

| Stack          | Compose file         | Contains                     | Redeployed when                              |
|----------------|----------------------|------------------------------|----------------------------------------------|
| `ib-gateway`   | `ib-gateway.yml`     | IB Gateway (IBC)             | credentials, paper/live or IB Gateway version |
| `trading-desk` | `docker-compose.yml` | the app + cloudflared        | every app release                            |

IB Gateway sits in its own stack because every restart of it needs a new 2FA confirmation on IBKR
Mobile. Redeploying `trading-desk` never touches it; the app reconnects to IB Gateway on its own.

Nothing is published on `0.0.0.0`. The app's port 8080 is reachable only by cloudflared, over the
stack's private `edge` network. IB Gateway publishes only `127.0.0.1:4002` (paper API, for tests over an
SSH tunnel) and `127.0.0.1:5900` (VNC, off unless `VNC_SERVER_PASSWORD` is set).

## 0. Once on the host

```
docker network create trading-desk
```

## 1. GHCR as a registry in Portainer (read-only token)

The image `ghcr.io/tsmalib/trading-desk-gateway` is private. Portainer needs a token that can only pull.

1. GitHub > Settings > Developer settings > Personal access tokens > **Tokens (classic)** > Generate new
   token. Scope: **`read:packages`** only. Expiry: your choice (put a reminder in the calendar).
   (Fine-grained tokens do not cover GHCR packages yet; a classic token with only `read:packages` is the
   read-only option.)
2. Portainer > **Registries** > Add registry > **Custom registry**:
   - Name: `ghcr`
   - Registry URL: `ghcr.io`
   - Authentication: on. Username: your GitHub user. Password: the token.
3. When the token expires: generate a new one, edit the registry, paste it. Running containers are
   unaffected; only the next pull needs it.

Images are built by `.github/workflows/release.yml` on every `v*` tag:

```
git tag v0.1.0 && git push origin v0.1.0
```

The first push creates the package; check under GitHub > your profile > Packages >
trading-desk-gateway > Package settings that its visibility is **Private**.

## 2. Stack `ib-gateway`

Portainer > Stacks > Add stack:

- Name: `ib-gateway`
- Build method: **Repository**. URL: `https://github.com/TsMalib/trading-desk-gateway`, reference
  `refs/heads/main`, compose path **`ib-gateway.yml`**. For a private repository, turn on
  Authentication with a GitHub token that has `repo` read access (a fine-grained token with
  *Contents: read-only* on this repository is enough).
- **Automatic updates: off.** (A Git poll or webhook would restart IB Gateway and trigger 2FA.)
- Environment variables (Advanced mode lets you paste `KEY=value` lines):

  | Variable              | Paper                  | Live                   |
  |-----------------------|------------------------|------------------------|
  | `TWS_USERID`          | paper username         | live username          |
  | `TWS_PASSWORD`        | paper password         | live password          |
  | `TRADING_MODE`        | `paper`                | `live`                 |
  | `TIME_ZONE`           | `Europe/Berlin`        | same                   |
  | `AUTO_RESTART_TIME`   | `11:59 PM` (optional)  | same                   |
  | `JAVA_HEAP_SIZE`      | `1024` (optional)      | same                   |
  | `VNC_SERVER_PASSWORD` | empty, or a password   | same                   |

Deploy, then confirm the 2FA push on IBKR Mobile within about 3 minutes (a paper login without 2FA
needs no confirmation). Check `docker logs ib-gateway` for `Login has completed`.

## 3. Stack `trading-desk`

Portainer > Stacks > Add stack:

- Name: `trading-desk`
- Build method: **Repository**, same URL and reference, compose path **`docker-compose.yml`**.
- **Automatic updates: off.** Releases are deliberate: you change `DESK_VERSION` and redeploy.
- Environment variables (every one is required unless marked optional):

  | Variable              | Value                                                              |
  |-----------------------|--------------------------------------------------------------------|
  | `DESK_VERSION`        | image tag to run, e.g. `v0.1.0`                                     |
  | `GHCR_OWNER`          | optional, default `tsmalib`                                         |
  | `DESK_MODE`           | `paper` or `live` (must match `TRADING_MODE`)                       |
  | `IB_PORT`             | `4004` paper, `4003` live                                           |
  | `IB_HOST`             | optional, default `ib-gateway`                                      |
  | `IB_CLIENT_ID`        | optional, default `1`                                               |
  | `IB_MARKET_DATA_TYPE` | optional, `1` live data (default) or `3` delayed                    |
  | `TELEGRAM_BOT_TOKEN`  | from @BotFather                                                     |
  | `TELEGRAM_CHAT_ID`    | chat for proposals and alerts                                       |
  | `TELEGRAM_USER_ID`    | your Telegram user id                                               |
  | `MCP_API_KEY`         | `openssl rand -hex 32`                                              |
  | `RISK_WHITELIST`      | optional, e.g. `AAPL,MSFT`                                          |
  | `DESK_DB_URL`         | see section 4                                                      |
  | `DESK_DB_USER`        | database user                                                      |
  | `DESK_DB_PASSWORD`    | database password                                                  |
  | `TUNNEL_TOKEN`        | Cloudflare Tunnel token                                             |

Portainer pulls the image with the `ghcr` registry from section 1 (it matches the registry by host).

### Cloudflare Tunnel

Zero Trust > Networks > Tunnels > Create a tunnel (type *Cloudflared*) > copy the token into
`TUNNEL_TOKEN`. Add a public hostname, e.g. `desk.example.com`, service **`http://gateway:8080`**.
Protect it with a Cloudflare Access policy if the bots can send a service token; the MCP endpoint
requires `X-Desk-Key` either way.

## 4. Connecting to Postgres

There is no Postgres in these stacks. Create the database and user once on your Postgres:

```sql
create user desk with password '...';
create database desk owner desk;
```

Flyway creates the schema on the app's first start.

### Postgres in a container on the same host (preferred)

Attach the app to the Postgres container's network as an external network, so Postgres need not
publish 5432 at all.

1. Find the network:
   ```
   docker inspect <postgres-container> --format '{{json .NetworkSettings.Networks}}'
   ```
   e.g. `postgres_default` (a compose project `postgres` gives `postgres_default`).
2. In `docker-compose.yml` uncomment the `- postgres` line under `gateway.networks` and the `postgres:`
   block under `networks:`, commit, push, and pull and redeploy the stack in Portainer.
3. Set `POSTGRES_NETWORK=postgres_default` (or whatever step 1 showed) in the stack.
4. `DESK_DB_URL=jdbc:postgresql://<postgres-container-or-service-name>:5432/desk`

### Postgres elsewhere (another host, a VM, or bare metal)

Use the host's IP and port 5432:

```
DESK_DB_URL=jdbc:postgresql://192.168.1.10:5432/desk
```

Postgres must listen on that interface (`listen_addresses`) and `pg_hba.conf` must allow the Docker
host's IP for user `desk` (`scram-sha-256`). Firewall 5432 to the Docker host only.

The same fallback works on the same host when Postgres publishes 5432: use the host's LAN IP (not
`localhost`, which is the app container itself).

## 5. Releasing and rolling back

Release:

1. `git tag v0.2.0 && git push origin v0.2.0`; wait for the `release` workflow to go green.
2. Portainer > Stacks > `trading-desk` > Editor/Environment: `DESK_VERSION=v0.2.0` > **Update the
   stack** (turn on *Re-pull image*). Only the app and cloudflared are recreated; IB Gateway keeps its
   session.
3. `/status` in Telegram, or `docker logs` of the gateway container: Flyway ran, IBKR connected.

Roll back: set `DESK_VERSION` back to the previous tag (e.g. `v0.1.0`) and update the stack again.
Every image tag stays in GHCR, so any earlier release can be redeployed. Caveat: Flyway migrations are
forward-only. Flyway ignores migrations newer than the image by default, so an added table or column does
not stop the older image; a dropped or renamed column does (Hibernate's schema `validate` fails at start)
until you undo it by hand. Check `git diff <old-tag> <new-tag> -- src/main/resources/db/migration`
before rolling back.

When `docker-compose.yml` itself changes in a release, use *Pull and redeploy* on the stack so Portainer
fetches the new file from Git, then update with the new `DESK_VERSION`.
