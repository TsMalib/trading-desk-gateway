# trading-desk-gateway

A Quarkus 3 (Java 21, Maven) service that sits between AI research bots (Grok, via MCP) and Interactive Brokers (TWS API via IB Gateway). Bots can only PROPOSE trades. The owner approves each one in Telegram. Only this service places orders.

## Non-negotiable rules
- There is no MCP tool that places, cancels or modifies an order. MCP tools: get_account_summary, get_positions, get_quote, propose_order, propose_exit, propose_stop_update, get_proposal_status.
- An order is sent only after: risk checks pass at proposal time, the owner taps Approve in Telegram, and all risk checks pass again with a fresh quote at approval time.
- Entries are IBKR bracket orders: parent LMT BUY (DAY) + child LMT SELL target (GTC) + child STP SELL stop (GTC); parent and first child transmit=false, last child transmit=true. No entry without a stop.
- Long only. A SELL quantity never exceeds the position IBKR reports. Stops only move up (toward safety).
- Idempotency: every order has orderRef "P<proposalId>-<ROLE>"; before sending, check IBKR open orders and the DB for that ref.
- IBKR is the source of truth for positions, orders and executions. The DB stores reasons, links and audit. Reconcile at startup, after reconnect and every 5 minutes.
- Money is BigDecimal, never double. Times are stored as timestamptz (UTC). Market hours are evaluated in America/New_York.
- Every state change writes an audit_log row.
- Paper vs live is a single config switch (ib.mode=paper|live) and is shown in every Telegram message.
- Secrets come from environment variables only.

## Stack
quarkus-rest, quarkus-rest-client-jackson, quarkus-hibernate-orm-panache, quarkus-jdbc-postgresql, quarkus-flyway, quarkus-scheduler, quarkus-smallrye-health, io.quarkiverse.mcp:quarkus-mcp-server-http, IBKR TWS API jar (com.interactivebrokers:tws-api, installed locally from lib/TwsApi.jar). Tests: JUnit 5, Testcontainers Postgres, a FakeBroker implementation of the broker interface.

## Packages
mcp (tools), risk (pure rules), proposal (service + state machine), broker (Broker interface, IbkrBroker, FakeBroker), telegram (bot + approvals + commands), recon (reconciler), store (entities + repositories), config.

## Schema

create table instrument (
  conid          bigint primary key,           -- IBKR contract id
  symbol         text not null,
  primary_exch   text not null,
  currency       char(3) not null,
  isin           text not null,                -- needed for the 30-day rule and tax
  name           text,
  updated_at     timestamptz not null default now()
);

create table proposal (
  id             bigserial primary key,
  conid          bigint references instrument(conid),
  kind           text not null check (kind in ('ENTRY','EXIT','STOP_UPDATE')),
  status         text not null,
  entry_limit    numeric(18,6),
  stop_price     numeric(18,6),
  target_price   numeric(18,6),
  quantity       numeric(18,4),                -- computed by the risk engine
  risk_eur       numeric(18,2),
  reward_risk    numeric(8,2),
  thesis         text,
  sources        jsonb not null default '[]',
  proposed_by    text not null,
  risk_result    jsonb,                        -- every check with pass/fail and values
  warnings       jsonb not null default '[]',  -- e.g. 30-day rule
  telegram_msg_id bigint,
  expires_at     timestamptz,
  created_at     timestamptz not null default now(),
  decided_at     timestamptz
);
create index on proposal (status);
create index on proposal (conid, created_at);

create table broker_order (
  id             bigserial primary key,
  proposal_id    bigint not null references proposal(id),
  ib_order_id    integer not null,
  ib_perm_id     bigint,
  order_ref      text not null unique,          -- P42-ENTRY / -TARGET / -STOP / -EXIT
  role           text not null check (role in ('ENTRY','TARGET','STOP','EXIT')),
  action         text not null check (action in ('BUY','SELL')),
  order_type     text not null,
  quantity       numeric(18,4) not null,
  limit_price    numeric(18,6),
  aux_price      numeric(18,6),                 -- stop trigger
  status         text not null,
  updated_at     timestamptz not null default now()
);

create table execution (
  ib_exec_id     text primary key,
  broker_order_id bigint references broker_order(id),  -- null for EXTERNAL trades
  conid          bigint not null references instrument(conid),
  side           text not null check (side in ('BUY','SELL')),
  quantity       numeric(18,4) not null,
  price          numeric(18,6) not null,
  currency       char(3) not null,
  commission     numeric(18,6),
  realized_pnl   numeric(18,6),                 -- from IBKR's commission report; check its currency on your first fill
  executed_at    timestamptz not null
);
create index on execution (conid, executed_at);

create table position_meta (
  conid          bigint primary key references instrument(conid),
  proposal_id    bigint references proposal(id),
  origin         text not null check (origin in ('GATEWAY','EXTERNAL')),
  entry_price    numeric(18,6),
  current_stop   numeric(18,6),
  target_price   numeric(18,6),
  breakeven_done boolean not null default false,
  opened_at      timestamptz
);

create table setting (
  key            text primary key,              -- kill_switch, start_of_day_equity, ...
  value          text not null,
  updated_at     timestamptz not null default now()
);

create table audit_log (
  id             bigserial primary key,
  at             timestamptz not null default now(),
  actor          text not null,                 -- GROK, USER, SYSTEM, RECONCILER
  event          text not null,
  proposal_id    bigint,
  details        jsonb not null default '{}'
);