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
