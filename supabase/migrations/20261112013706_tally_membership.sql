-- Tally's relay, version 2: members, admins, approval-based joining and key rotation (docs/DESIGN.md, "Sync" and
-- "Membership"). This replaces the single family token of 20261111150251_tally_relay.sql.
--
-- The relay still holds only ciphertext: records, files, invite boxes, join requests and key boxes are all sealed on
-- the phones. What it holds in the clear is who may call it: per-member credentials (the SHA-256 of a random secret
-- each phone made), each member's role (admin/member) and status (active/removed/left), the family's key epoch, and
-- timestamps. It never holds a key, a name, an amount or a public key.
--
-- Access model, as before: RLS on for every table with no policies, no table or sequence grants to anon/authenticated,
-- so the Data API reaches nothing directly. Everything goes through the security definer functions below, with a fixed
-- empty search_path. Every function that changes a family takes that family's transaction-scoped advisory lock before it
-- checks the caller, so membership changes, rotations and pushes to one family are serialised and each sees the result
-- of the one before it (two admins acting at once end in one consistent order; the loser gets 'stale' and retries).
--
-- Error codes (SQLSTATE), read by the app (sync/Relay.kt):
--   28000  unauthorised: unknown family, unknown member, wrong secret, or a member who left
--   TA001  removed: the caller's own credentials are right but an admin removed them
--   42501  not an admin
--   TA002  stale: the epoch or the member list the caller acted on is no longer current (sync, then try again)
--   TA003  a limit is reached (members, invites, requests, records, file bytes, new families per hour)
--   TA004  refused by a rule (the last admin, the creator, removing yourself, an unknown or inactive target)
--   TA005  the invite or request cannot be used (unknown, expired, used, cancelled, decided)
--   22023 / 54000  malformed or too large input
--
-- Limits (docs/DESIGN.md, "Limits"): 20 active members per family, 5 live invites per family, 5 open requests per family,
-- 20 000 records per family, 100 MiB of file blobs per family, 20 new families per hour across the relay. Invites live
-- 24 hours, requests 72 hours; expired ones are deleted by the next invite or request in that family and by the daily
-- tally_ping.
--
-- What this migration drops: the five version-1 RPC functions (tally_family_create(text,text), tally_push(text,text,jsonb),
-- tally_pull(text,text,bigint,integer), tally_blob_put(text,text,text,text), tally_blob_get(text,text,text)) and the
-- private helper tally_private.tally_auth(text,text). It does NOT drop the version-1 tables tally_families, tally_records,
-- tally_blobs or the sequence tally_seq: their rows (test families made by 0.1.0/0.2.0) stay, unreachable by any
-- function, until a later migration removes them.

-- ---------------------------------------------------------------- version 1 functions

drop function if exists public.tally_family_create(text, text);
drop function if exists public.tally_push(text, text, jsonb);
drop function if exists public.tally_pull(text, text, bigint, integer);
drop function if exists public.tally_blob_put(text, text, text, text);
drop function if exists public.tally_blob_get(text, text, text);
drop function if exists tally_private.tally_auth(text, text);

-- ---------------------------------------------------------------- tables

create table public.tally_family (
  id            text primary key,
  creator       text,                                   -- member id; null once the creator has left
  epoch         integer not null default 1,              -- the newest key epoch; pushes must use it
  rotate        boolean not null default false,          -- someone left: an admin's phone rotates the key on its next sync
  record_count  integer not null default 0,
  blob_bytes    bigint not null default 0,
  created_at    timestamptz not null default now(),
  constraint tally_family_id_format check (id ~ '^[A-Za-z0-9_-]{22}$'),
  constraint tally_family_epoch check (epoch >= 1)
);
create index tally_family_created_at on public.tally_family (created_at);

create table public.tally_member (
  family_id     text not null references public.tally_family (id) on delete cascade,
  id            text not null,
  secret_hash   text not null,
  notice_hash   text,                                    -- a second, weaker credential: it can only ask "any requests?"
  role          text not null default 'member',
  status        text not null default 'active',
  has_epoch     integer not null default 0,              -- the newest epoch this member was given a key for
  joined_at     timestamptz not null default now(),
  primary key (family_id, id),
  constraint tally_member_id_format check (id ~ '^[A-Za-z0-9_-]{1,64}$'),
  constraint tally_member_hash_format check (secret_hash ~ '^[0-9a-f]{64}$'),
  constraint tally_member_notice_format check (notice_hash is null or notice_hash ~ '^[0-9a-f]{64}$'),
  constraint tally_member_role check (role in ('admin', 'member')),
  constraint tally_member_status check (status in ('active', 'removed', 'left'))
);

create table public.tally_invite (
  family_id     text not null references public.tally_family (id) on delete cascade,
  id            text not null,                           -- derived on the phones from the invite's secret; the secret never comes here
  created_by    text not null,
  box           text not null,                           -- the invite's secret, sealed under the family key
  used          boolean not null default false,
  created_at    timestamptz not null default now(),
  expires_at    timestamptz not null,
  primary key (family_id, id),
  constraint tally_invite_id_format check (id ~ '^[A-Za-z0-9_-]{22}$'),
  constraint tally_invite_box_size check (length(box) between 1 and 4096)
);
create index tally_invite_expires_at on public.tally_invite (expires_at);

create table public.tally_request (
  family_id     text not null references public.tally_family (id) on delete cascade,
  invite_id     text not null,
  member_id     text not null,
  secret_hash   text not null,
  notice_hash   text,
  blob          text not null,                           -- name and public key, sealed under a key only the invite's holders have
  status        text not null default 'pending',
  created_at    timestamptz not null default now(),
  expires_at    timestamptz not null,
  primary key (family_id, invite_id),
  constraint tally_request_member_format check (member_id ~ '^[A-Za-z0-9_-]{1,64}$'),
  constraint tally_request_hash_format check (secret_hash ~ '^[0-9a-f]{64}$'),
  constraint tally_request_notice_format check (notice_hash is null or notice_hash ~ '^[0-9a-f]{64}$'),
  constraint tally_request_blob_size check (length(blob) between 1 and 8192),
  constraint tally_request_status check (status in ('pending', 'denied'))
);
create index tally_request_expires_at on public.tally_request (expires_at);

create table public.tally_box (
  family_id     text not null,
  member_id     text not null,
  epoch         integer not null,
  box           text not null,                           -- family keys sealed to that member's public key
  created_at    timestamptz not null default now(),
  primary key (family_id, member_id, epoch),
  foreign key (family_id, member_id) references public.tally_member (family_id, id) on delete cascade,
  constraint tally_box_size check (length(box) between 1 and 32768)
);

-- One sequence for every record upsert; see tally_push for why paging over it never misses a row.
create sequence public.tally_record_seq as bigint;

create table public.tally_record (
  family_id  text not null references public.tally_family (id) on delete cascade,
  id         text not null,
  kind       text not null,
  blob       text not null,
  seq        bigint not null default nextval('public.tally_record_seq'),
  primary key (family_id, id),
  constraint tally_record_kind check (kind in ('loan', 'payment', 'file', 'member'))
);
create index tally_record_family_seq on public.tally_record (family_id, seq);

create table public.tally_blob (
  family_id  text not null references public.tally_family (id) on delete cascade,
  id         text not null,
  data       text not null,
  primary key (family_id, id)
);

alter table public.tally_family  enable row level security;
alter table public.tally_member  enable row level security;
alter table public.tally_invite  enable row level security;
alter table public.tally_request enable row level security;
alter table public.tally_box     enable row level security;
alter table public.tally_record  enable row level security;
alter table public.tally_blob    enable row level security;

revoke all on table public.tally_family, public.tally_member, public.tally_invite, public.tally_request,
  public.tally_box, public.tally_record, public.tally_blob from public, anon, authenticated;
revoke all on sequence public.tally_record_seq from public, anon, authenticated;

-- ---------------------------------------------------------------- private helpers (not exposed)

create schema if not exists tally_private;
revoke all on schema tally_private from public, anon, authenticated;

-- The family's lock: every change to a family runs under it, taken before the caller is checked, so the check sees
-- every change that committed before it (a member removed a moment ago is refused, not let through on a stale read).
create function tally_private.tally_lock(p_family text)
returns void
language sql
volatile
security definer
set search_path = ''
as $$
  select pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('tally:' || coalesce(p_family, ''), 0));
$$;

create function tally_private.tally_hash(p_secret text)
returns text
language sql
immutable
security definer
set search_path = ''
as $$
  select encode(pg_catalog.sha256(convert_to(p_secret, 'UTF8')), 'hex');
$$;

create function tally_private.tally_ms(p_at timestamptz)
returns bigint
language sql
immutable
security definer
set search_path = ''
as $$
  select (extract(epoch from p_at) * 1000)::bigint;
$$;

-- The calling member, or an exception: 28000 for anything that does not identify an active member (the same error for an
-- unknown family, an unknown member and a wrong secret), TA001 for a member whose secret is right but who was removed.
create function tally_private.tally_member_auth(p_family text, p_member text, p_secret text)
returns public.tally_member
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v public.tally_member;
begin
  if p_family is null or p_member is null or p_secret is null or length(p_secret) > 256 then
    raise exception 'tally: unauthorised' using errcode = '28000';
  end if;
  select * into v from public.tally_member m where m.family_id = p_family and m.id = p_member;
  if not found or v.secret_hash <> tally_private.tally_hash(p_secret) then
    raise exception 'tally: unauthorised' using errcode = '28000';
  end if;
  if v.status = 'removed' then
    raise exception 'tally: removed' using errcode = 'TA001';
  end if;
  if v.status <> 'active' then
    raise exception 'tally: unauthorised' using errcode = '28000';
  end if;
  return v;
end;
$$;

create function tally_private.tally_admin_auth(p_family text, p_member text, p_secret text)
returns public.tally_member
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v public.tally_member;
begin
  v := tally_private.tally_member_auth(p_family, p_member, p_secret);
  if v.role <> 'admin' then
    raise exception 'tally: not an admin' using errcode = '42501';
  end if;
  return v;
end;
$$;

-- Deletes a family's expired requests, and expired invites no open request still needs.
create function tally_private.tally_tidy(p_family text)
returns void
language sql
volatile
security definer
set search_path = ''
as $$
  delete from public.tally_request r where r.family_id = p_family and r.expires_at < now();
  delete from public.tally_invite i where i.family_id = p_family and i.expires_at < now()
    and not exists (select 1 from public.tally_request r where r.family_id = i.family_id and r.invite_id = i.id);
$$;

-- Checks a list of key boxes [{ "member": id, "box": text }, ...] for one epoch and stores them: every recipient must be
-- an active member of the family, at most once. Raises TA002 when a recipient is not active (the caller's member list
-- is out of date). A recipient that already has a box for this epoch keeps it. Returns how many were stored.
create function tally_private.tally_put_boxes(p_family text, p_epoch integer, p_boxes jsonb)
returns integer
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  b jsonb;
  k integer;
begin
  if p_boxes is null or jsonb_typeof(p_boxes) <> 'array' or jsonb_array_length(p_boxes) > 64 then
    raise exception 'tally: boxes must be an array' using errcode = '22023';
  end if;
  for b in select e.value from jsonb_array_elements(p_boxes) as e(value) loop
    if not coalesce(jsonb_typeof(b) = 'object'
         and jsonb_typeof(b -> 'member') = 'string' and jsonb_typeof(b -> 'box') = 'string'
         and length(b ->> 'box') between 1 and 32768, false) then
      raise exception 'tally: bad box' using errcode = '22023';
    end if;
    if not exists (select 1 from public.tally_member m
                   where m.family_id = p_family and m.id = (b ->> 'member') and m.status = 'active') then
      raise exception 'tally: not a member now' using errcode = 'TA002';
    end if;
  end loop;
  if (select count(distinct e.value ->> 'member') from jsonb_array_elements(p_boxes) as e(value)) <> jsonb_array_length(p_boxes) then
    raise exception 'tally: a member twice' using errcode = '22023';
  end if;
  insert into public.tally_box (family_id, member_id, epoch, box)
    select p_family, e.value ->> 'member', p_epoch, e.value ->> 'box' from jsonb_array_elements(p_boxes) as e(value)
    on conflict on constraint tally_box_pkey do nothing;
  get diagnostics k = row_count;
  update public.tally_member m set has_epoch = greatest(m.has_epoch, p_epoch)
    where m.family_id = p_family and m.id in (select e.value ->> 'member' from jsonb_array_elements(p_boxes) as e(value));
  return k;
end;
$$;

-- A new epoch: boxes for the remaining members (the caller's included), the family moves to it, and every live invite and
-- open request goes (they were made under the old key, possibly by someone who is no longer a member).
create function tally_private.tally_new_epoch(p_family text, p_caller text, p_epoch integer, p_boxes jsonb)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  cur integer;
begin
  select f.epoch into cur from public.tally_family f where f.id = p_family;
  if p_epoch is null or p_epoch <> cur + 1 then
    raise exception 'tally: stale epoch' using errcode = 'TA002';
  end if;
  if p_boxes is null or jsonb_typeof(p_boxes) <> 'array'
     or not exists (select 1 from jsonb_array_elements(p_boxes) as e(value) where e.value ->> 'member' = p_caller) then
    raise exception 'tally: the caller needs a box too' using errcode = '22023';
  end if;
  perform tally_private.tally_put_boxes(p_family, p_epoch, p_boxes);
  update public.tally_family f set epoch = p_epoch, rotate = false where f.id = p_family;
  delete from public.tally_request r where r.family_id = p_family;
  delete from public.tally_invite i where i.family_id = p_family;
end;
$$;

revoke all on all functions in schema tally_private from public, anon, authenticated;

-- ---------------------------------------------------------------- creating a family

-- Registers a family with its creator as the first member and admin, at epoch 1. Idempotent for the creator (the same
-- credentials again succeed, so a retried create is harmless); anything else for an existing id is 28000.
create function public.tally_family_create(p_family text, p_member text, p_secret_hash text, p_notice_hash text)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
  if p_family is null or p_family !~ '^[A-Za-z0-9_-]{22}$' then
    raise exception 'tally: bad family id' using errcode = '22023';
  end if;
  if p_member is null or p_member !~ '^[A-Za-z0-9_-]{1,64}$' then
    raise exception 'tally: bad member id' using errcode = '22023';
  end if;
  if p_secret_hash is null or p_secret_hash !~ '^[0-9a-f]{64}$' or (p_notice_hash is not null and p_notice_hash !~ '^[0-9a-f]{64}$') then
    raise exception 'tally: bad hash' using errcode = '22023';
  end if;
  perform tally_private.tally_lock(p_family);
  if exists (select 1 from public.tally_family f where f.id = p_family) then
    if exists (select 1 from public.tally_family f join public.tally_member m on m.family_id = f.id
               where f.id = p_family and f.creator = p_member and m.id = p_member and m.secret_hash = p_secret_hash and m.status = 'active') then
      return;
    end if;
    raise exception 'tally: unauthorised' using errcode = '28000';
  end if;
  -- the relay-wide limit on new families, counted under one lock so two creates cannot both slip under it
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('tally:create', 0));
  if (select count(*) from public.tally_family f where f.created_at > now() - interval '1 hour') >= 20 then
    raise exception 'tally: too many new families, try later' using errcode = 'TA003';
  end if;
  insert into public.tally_family (id, creator, epoch) values (p_family, p_member, 1);
  insert into public.tally_member (family_id, id, secret_hash, notice_hash, role, status, has_epoch)
    values (p_family, p_member, p_secret_hash, p_notice_hash, 'admin', 'active', 1);
end;
$$;

-- ---------------------------------------------------------------- the family as one member sees it

-- {
--   epoch, creator, rotate, me: { role },
--   members:  [ { id, role, since (epoch ms), has } ]           active members, longest-standing first
--   boxes:    [ { epoch, box } ]                                the caller's key boxes with epoch > p_after
--   invites:  [ { id, by, expires } ]                           live invites (not used, not expired)
--   requests: [ { invite, member, blob, at, expires, inviteBox, invitedBy } ]   open requests; admins only, else []
-- }
create function public.tally_state(p_family text, p_member text, p_secret text, p_after integer)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  me public.tally_member;
  f public.tally_family;
begin
  -- the checks of tally_member_auth, inline so that this function stays read-only
  if p_family is null or p_member is null or p_secret is null or length(p_secret) > 256 then
    raise exception 'tally: unauthorised' using errcode = '28000';
  end if;
  select * into me from public.tally_member m where m.family_id = p_family and m.id = p_member;
  if not found or me.secret_hash <> tally_private.tally_hash(p_secret) then
    raise exception 'tally: unauthorised' using errcode = '28000';
  end if;
  if me.status = 'removed' then raise exception 'tally: removed' using errcode = 'TA001'; end if;
  if me.status <> 'active' then raise exception 'tally: unauthorised' using errcode = '28000'; end if;
  select * into f from public.tally_family x where x.id = p_family;
  return jsonb_build_object(
    'epoch', f.epoch,
    'creator', f.creator,
    'rotate', f.rotate,
    'me', jsonb_build_object('role', me.role),
    'members', coalesce((
      select jsonb_agg(jsonb_build_object('id', m.id, 'role', m.role, 'since', tally_private.tally_ms(m.joined_at), 'has', m.has_epoch)
                       order by m.joined_at, m.id)
      from public.tally_member m where m.family_id = p_family and m.status = 'active'), '[]'::jsonb),
    'boxes', coalesce((
      select jsonb_agg(jsonb_build_object('epoch', b.epoch, 'box', b.box) order by b.epoch)
      from public.tally_box b where b.family_id = p_family and b.member_id = p_member and b.epoch > coalesce(p_after, 0)), '[]'::jsonb),
    'invites', coalesce((
      select jsonb_agg(jsonb_build_object('id', i.id, 'by', i.created_by, 'expires', tally_private.tally_ms(i.expires_at)) order by i.created_at)
      from public.tally_invite i where i.family_id = p_family and not i.used and i.expires_at > now()), '[]'::jsonb),
    'requests', case when me.role <> 'admin' then '[]'::jsonb else coalesce((
      select jsonb_agg(jsonb_build_object(
               'invite', r.invite_id, 'member', r.member_id, 'blob', r.blob,
               'at', tally_private.tally_ms(r.created_at), 'expires', tally_private.tally_ms(r.expires_at),
               'inviteBox', i.box, 'invitedBy', i.created_by) order by r.created_at)
      from public.tally_request r join public.tally_invite i on i.family_id = r.family_id and i.id = r.invite_id
      where r.family_id = p_family and r.status = 'pending' and r.expires_at > now()), '[]'::jsonb) end
  );
end;
$$;

-- ---------------------------------------------------------------- records and files

-- Upserts sealed records: rows = [{ "id", "kind", "blob" }, ...] (at most 500, each blob at most 64 KiB). p_epoch is the
-- key epoch the rows were sealed with and must be the family's newest: a phone that has not caught up with a rotation
-- gets TA002 and syncs again, so nothing new is written under a key a removed member held. Returns rows written.
--
-- Ordering: every upsert takes a fresh value of tally_record_seq and readers page with "seq > cursor". Sequence values
-- are taken at nextval() but become visible at COMMIT, so a push that took seq 10 could commit after one that took 11,
-- and a reader that had already moved its cursor to 11 would never see 10. The family lock (taken before any nextval)
-- makes pushes to one family run one at a time, each holding it until it commits, so within a family seq values become
-- visible in the order they were taken. Readers only page within one family.
create function public.tally_push(p_family text, p_member text, p_secret text, p_epoch integer, p_rows jsonb)
returns integer
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  n integer;
  added integer;
  total integer;
  r jsonb;
  f public.tally_family;
begin
  if p_rows is null or jsonb_typeof(p_rows) <> 'array' then
    raise exception 'tally: rows must be an array' using errcode = '22023';
  end if;
  n := jsonb_array_length(p_rows);
  if n > 500 then
    raise exception 'tally: too many rows' using errcode = '54000';
  end if;
  for r in select e.value from jsonb_array_elements(p_rows) as e(value) loop
    if not coalesce(
         jsonb_typeof(r) = 'object'
         and jsonb_typeof(r -> 'id') = 'string' and jsonb_typeof(r -> 'kind') = 'string' and jsonb_typeof(r -> 'blob') = 'string'
         and (r ->> 'id') ~ '^[A-Za-z0-9_-]{1,64}$'
         and (r ->> 'kind') in ('loan', 'payment', 'file', 'member')
         and length(r ->> 'blob') between 1 and 65536,
       false) then
      raise exception 'tally: bad row' using errcode = '22023';
    end if;
  end loop;
  perform tally_private.tally_lock(p_family);
  perform tally_private.tally_member_auth(p_family, p_member, p_secret);
  select * into f from public.tally_family x where x.id = p_family;
  if p_epoch is null or p_epoch <> f.epoch then
    raise exception 'tally: stale epoch' using errcode = 'TA002';
  end if;
  if n = 0 then return 0; end if;

  -- An id may appear twice in one call; the last one wins (ON CONFLICT cannot touch a row twice in one statement).
  with up as (
    insert into public.tally_record as t (family_id, id, kind, blob, seq)
    select p_family, d.rid, d.rkind, d.rblob, nextval('public.tally_record_seq')
    from (
      select distinct on (e.value ->> 'id')
             e.value ->> 'id' as rid, e.value ->> 'kind' as rkind, e.value ->> 'blob' as rblob
      from jsonb_array_elements(p_rows) with ordinality as e(value, ord)
      order by e.value ->> 'id', e.ord desc
    ) d
    on conflict on constraint tally_record_pkey do update
      set kind = excluded.kind, blob = excluded.blob, seq = nextval('public.tally_record_seq')
    returning (t.xmax = 0) as inserted
  )
  select count(*), count(*) filter (where up.inserted) into n, added from up;

  update public.tally_family x set record_count = x.record_count + added where x.id = p_family
    returning x.record_count into total;
  if total > 20000 then
    raise exception 'tally: too many records in this family' using errcode = 'TA003';
  end if;
  return n;
end;
$$;

-- A family's records with seq > p_since, oldest first, at most p_max (clamped to 1..500) per call.
create function public.tally_pull(p_family text, p_member text, p_secret text, p_since bigint, p_max integer)
returns table (id text, kind text, blob text, seq bigint)
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
  perform tally_private.tally_member_auth(p_family, p_member, p_secret);
  return query
    select t.id, t.kind, t.blob, t.seq
    from public.tally_record t
    where t.family_id = p_family and t.seq > coalesce(p_since, 0)
    order by t.seq
    limit greatest(1, least(coalesce(p_max, 500), 500));
end;
$$;

-- Stores a sealed file as base64 text: at most 6 MiB, and at most 100 MiB of files per family. Same epoch rule as tally_push.
create function public.tally_blob_put(p_family text, p_member text, p_secret text, p_epoch integer, p_id text, p_data text)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  f public.tally_family;
  old_len bigint;
  total bigint;
begin
  if p_id is null or p_id !~ '^[A-Za-z0-9_-]{1,64}$' then
    raise exception 'tally: bad id' using errcode = '22023';
  end if;
  if p_data is null or length(p_data) = 0 or length(p_data) > 6 * 1024 * 1024 then
    raise exception 'tally: bad data' using errcode = '54000';
  end if;
  perform tally_private.tally_lock(p_family);
  perform tally_private.tally_member_auth(p_family, p_member, p_secret);
  select * into f from public.tally_family x where x.id = p_family;
  if p_epoch is null or p_epoch <> f.epoch then
    raise exception 'tally: stale epoch' using errcode = 'TA002';
  end if;
  select length(b.data) into old_len from public.tally_blob b where b.family_id = p_family and b.id = p_id;
  update public.tally_family x set blob_bytes = x.blob_bytes - coalesce(old_len, 0) + length(p_data) where x.id = p_family
    returning x.blob_bytes into total;
  if total > 100 * 1024 * 1024 then
    raise exception 'tally: this family has too many files' using errcode = 'TA003';
  end if;
  insert into public.tally_blob as b (family_id, id, data)
    values (p_family, p_id, p_data)
    on conflict on constraint tally_blob_pkey do update set data = excluded.data;
end;
$$;

create function public.tally_blob_get(p_family text, p_member text, p_secret text, p_id text)
returns text
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  d text;
begin
  perform tally_private.tally_member_auth(p_family, p_member, p_secret);
  select b.data into d from public.tally_blob b where b.family_id = p_family and b.id = p_id;
  return d;
end;
$$;

-- ---------------------------------------------------------------- invites and join requests

-- Any member: registers a one-time invite for 24 hours. p_box is the invite's secret sealed under the family key (only
-- members can open it). Returns when it expires (epoch ms).
create function public.tally_invite_create(p_family text, p_member text, p_secret text, p_invite text, p_box text)
returns bigint
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  exp timestamptz := now() + interval '24 hours';
begin
  if p_invite is null or p_invite !~ '^[A-Za-z0-9_-]{22}$' then
    raise exception 'tally: bad invite id' using errcode = '22023';
  end if;
  if p_box is null or length(p_box) not between 1 and 4096 then
    raise exception 'tally: bad invite box' using errcode = '22023';
  end if;
  perform tally_private.tally_lock(p_family);
  perform tally_private.tally_member_auth(p_family, p_member, p_secret);
  perform tally_private.tally_tidy(p_family);
  if (select count(*) from public.tally_invite i where i.family_id = p_family and not i.used and i.expires_at > now()) >= 5 then
    raise exception 'tally: too many open invites' using errcode = 'TA003';
  end if;
  insert into public.tally_invite (family_id, id, created_by, box, expires_at)
    values (p_family, p_invite, p_member, p_box, exp)
    on conflict on constraint tally_invite_pkey do nothing;
  if not found then
    raise exception 'tally: invite id taken' using errcode = 'TA005';
  end if;
  return tally_private.tally_ms(exp);
end;
$$;

-- Whoever made the invite, or an admin: cancels it (and any request made with it). Cancelling one already gone is fine.
create function public.tally_invite_cancel(p_family text, p_member text, p_secret text, p_invite text)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  me public.tally_member;
  maker text;
begin
  perform tally_private.tally_lock(p_family);
  me := tally_private.tally_member_auth(p_family, p_member, p_secret);
  select i.created_by into maker from public.tally_invite i where i.family_id = p_family and i.id = p_invite;
  if maker is null then return; end if;
  if maker <> me.id and me.role <> 'admin' then
    raise exception 'tally: not an admin' using errcode = '42501';
  end if;
  delete from public.tally_request r where r.family_id = p_family and r.invite_id = p_invite;
  delete from public.tally_invite i where i.family_id = p_family and i.id = p_invite;
end;
$$;

-- Someone with an invite (not a member yet) asks to join, which uses the invite up. p_blob is sealed with a key derived
-- from the invite's secret, which the relay never sees, so the relay can neither read nor forge it. The joiner's
-- credentials (hashes only) wait here until an admin decides. Every reason the invite cannot be used is the same TA005.
-- Returns when the request expires (epoch ms).
create function public.tally_join_request(p_family text, p_invite text, p_member text, p_secret_hash text, p_notice_hash text, p_blob text)
returns bigint
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  exp timestamptz := now() + interval '72 hours';
begin
  if p_family is null or p_family !~ '^[A-Za-z0-9_-]{22}$' or p_invite is null or p_invite !~ '^[A-Za-z0-9_-]{22}$' then
    raise exception 'tally: invite not usable' using errcode = 'TA005';
  end if;
  if p_member is null or p_member !~ '^[A-Za-z0-9_-]{1,64}$' then
    raise exception 'tally: bad member id' using errcode = '22023';
  end if;
  if p_secret_hash is null or p_secret_hash !~ '^[0-9a-f]{64}$' or (p_notice_hash is not null and p_notice_hash !~ '^[0-9a-f]{64}$') then
    raise exception 'tally: bad hash' using errcode = '22023';
  end if;
  if p_blob is null or length(p_blob) not between 1 and 8192 then
    raise exception 'tally: bad request' using errcode = '22023';
  end if;
  perform tally_private.tally_lock(p_family);
  perform tally_private.tally_tidy(p_family);
  update public.tally_invite i set used = true
    where i.family_id = p_family and i.id = p_invite and not i.used and i.expires_at > now();
  if not found then
    raise exception 'tally: invite not usable' using errcode = 'TA005';
  end if;
  if exists (select 1 from public.tally_member m where m.family_id = p_family and m.id = p_member and m.status = 'active') then
    raise exception 'tally: already a member' using errcode = 'TA004';
  end if;
  if (select count(*) from public.tally_request r where r.family_id = p_family and r.status = 'pending') >= 5 then
    raise exception 'tally: too many open requests' using errcode = 'TA003';
  end if;
  insert into public.tally_request (family_id, invite_id, member_id, secret_hash, notice_hash, blob, expires_at)
    values (p_family, p_invite, p_member, p_secret_hash, p_notice_hash, p_blob, exp);
  return tally_private.tally_ms(exp);
end;
$$;

-- The joiner, with the secret whose hash it sent: 'approved' (it is an active member now), 'pending', 'denied', or
-- 'removed' (let in and removed since); TA005 when the request is gone (expired, cancelled, or the key was rotated).
create function public.tally_join_status(p_family text, p_invite text, p_member text, p_secret text)
returns text
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  h text;
  st text;
begin
  if p_secret is null or length(p_secret) > 256 then
    raise exception 'tally: invite not usable' using errcode = 'TA005';
  end if;
  h := tally_private.tally_hash(p_secret);
  select m.status into st from public.tally_member m where m.family_id = p_family and m.id = p_member and m.secret_hash = h;
  if st = 'active' then return 'approved'; end if;
  if st = 'removed' then return 'removed'; end if;
  st := null;
  select r.status into st from public.tally_request r
    where r.family_id = p_family and r.invite_id = p_invite and r.member_id = p_member and r.secret_hash = h and r.expires_at > now();
  if st is null then
    raise exception 'tally: invite not usable' using errcode = 'TA005';
  end if;
  return st;
end;
$$;

-- The joiner withdraws its own request (the invite stays used).
create function public.tally_join_cancel(p_family text, p_invite text, p_member text, p_secret text)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
  if p_secret is null or length(p_secret) > 256 then return; end if;
  perform tally_private.tally_lock(p_family);
  delete from public.tally_request r
    where r.family_id = p_family and r.invite_id = p_invite and r.member_id = p_member
      and r.secret_hash = tally_private.tally_hash(p_secret);
end;
$$;

-- An admin decides a request. Allow: the joiner becomes an active member (role member) with the credentials it sent, and
-- p_box (the family keys sealed to the joiner's public key, made at the family's current p_epoch) is stored for it.
-- Don't allow: the request is kept as 'denied' until it expires, so the joiner's phone can say so; nothing is shared.
create function public.tally_request_decide(p_family text, p_member text, p_secret text, p_invite text, p_allow boolean, p_epoch integer, p_box text)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  r public.tally_request;
  f public.tally_family;
  cur text;
begin
  perform tally_private.tally_lock(p_family);
  perform tally_private.tally_admin_auth(p_family, p_member, p_secret);
  select * into r from public.tally_request x
    where x.family_id = p_family and x.invite_id = p_invite and x.status = 'pending' and x.expires_at > now();
  if not found then
    raise exception 'tally: request not open' using errcode = 'TA005';
  end if;
  if not coalesce(p_allow, false) then
    update public.tally_request x set status = 'denied' where x.family_id = p_family and x.invite_id = p_invite;
    return;
  end if;
  select * into f from public.tally_family x where x.id = p_family;
  if p_epoch is null or p_epoch <> f.epoch then
    raise exception 'tally: stale epoch' using errcode = 'TA002';
  end if;
  if p_box is null or length(p_box) not between 1 and 32768 then
    raise exception 'tally: bad box' using errcode = '22023';
  end if;
  if (select count(*) from public.tally_member m where m.family_id = p_family and m.status = 'active') >= 20 then
    raise exception 'tally: the family is full' using errcode = 'TA003';
  end if;
  select m.status into cur from public.tally_member m where m.family_id = p_family and m.id = r.member_id;
  if cur = 'active' then
    raise exception 'tally: already a member' using errcode = 'TA004';
  elsif cur is not null then
    -- someone who was removed or left, let in again: new credentials, a plain member, the old boxes gone
    delete from public.tally_box b where b.family_id = p_family and b.member_id = r.member_id;
    update public.tally_member m set secret_hash = r.secret_hash, notice_hash = r.notice_hash, role = 'member',
      status = 'active', has_epoch = 0, joined_at = now()
      where m.family_id = p_family and m.id = r.member_id;
  else
    insert into public.tally_member (family_id, id, secret_hash, notice_hash, role, status)
      values (p_family, r.member_id, r.secret_hash, r.notice_hash, 'member', 'active');
  end if;
  insert into public.tally_box (family_id, member_id, epoch, box) values (p_family, r.member_id, p_epoch, p_box);
  update public.tally_member m set has_epoch = p_epoch where m.family_id = p_family and m.id = r.member_id;
  delete from public.tally_request x where x.family_id = p_family and x.invite_id = p_invite;
  delete from public.tally_invite i where i.family_id = p_family and i.id = p_invite;
end;
$$;

-- ---------------------------------------------------------------- roles, removing, leaving, rotating

-- An admin makes an active member an admin (p_admin true) or takes the role away (false). Refused (TA004): the target is
-- not an active member; taking it from the last admin; taking it from the family's creator (only the creator can).
create function public.tally_member_role(p_family text, p_member text, p_secret text, p_target text, p_admin boolean)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  t public.tally_member;
  f public.tally_family;
begin
  perform tally_private.tally_lock(p_family);
  perform tally_private.tally_admin_auth(p_family, p_member, p_secret);
  select * into t from public.tally_member m where m.family_id = p_family and m.id = p_target and m.status = 'active';
  if not found or p_admin is null then
    raise exception 'tally: not a member' using errcode = 'TA004';
  end if;
  if p_admin then
    update public.tally_member m set role = 'admin' where m.family_id = p_family and m.id = p_target;
    return;
  end if;
  if t.role <> 'admin' then return; end if;
  select * into f from public.tally_family x where x.id = p_family;
  if f.creator = p_target and p_target <> p_member then
    raise exception 'tally: only the creator can step down' using errcode = 'TA004';
  end if;
  if (select count(*) from public.tally_member m where m.family_id = p_family and m.status = 'active' and m.role = 'admin') <= 1 then
    raise exception 'tally: the last admin' using errcode = 'TA004';
  end if;
  update public.tally_member m set role = 'member' where m.family_id = p_family and m.id = p_target;
end;
$$;

-- An admin removes an active member and rotates the key in the same transaction: from the commit on, every call with the
-- removed member's credentials fails (TA001), and the family's epoch is p_epoch (= current + 1) with p_boxes holding the
-- new key for remaining members (the caller's own box is required; a member left out is given one later by an admin's
-- phone through tally_box_put). Refused (TA004): the creator, the caller (use tally_leave), someone not active. TA002 when
-- the caller's view is out of date (the epoch moved, or a box is for someone no longer active, the removed member included).
create function public.tally_member_remove(p_family text, p_member text, p_secret text, p_target text, p_epoch integer, p_boxes jsonb)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  f public.tally_family;
begin
  perform tally_private.tally_lock(p_family);
  perform tally_private.tally_admin_auth(p_family, p_member, p_secret);
  select * into f from public.tally_family x where x.id = p_family;
  if p_target is null or p_target = p_member
     or not exists (select 1 from public.tally_member m where m.family_id = p_family and m.id = p_target and m.status = 'active') then
    raise exception 'tally: cannot remove that member' using errcode = 'TA004';
  end if;
  if f.creator is not null and f.creator = p_target then
    raise exception 'tally: the creator cannot be removed' using errcode = 'TA004';
  end if;
  update public.tally_member m set status = 'removed' where m.family_id = p_family and m.id = p_target;
  perform tally_private.tally_new_epoch(p_family, p_member, p_epoch, p_boxes);
end;
$$;

-- An admin moves the family to a new key epoch (after someone left, or at any time).
create function public.tally_rotate(p_family text, p_member text, p_secret text, p_epoch integer, p_boxes jsonb)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
  perform tally_private.tally_lock(p_family);
  perform tally_private.tally_admin_auth(p_family, p_member, p_secret);
  perform tally_private.tally_new_epoch(p_family, p_member, p_epoch, p_boxes);
end;
$$;

-- An admin gives members who lack it a box for the current epoch (a member the rotating phone could not seal to, or one
-- let in while a rotation was being made). Existing boxes are kept. Returns how many were stored.
create function public.tally_box_put(p_family text, p_member text, p_secret text, p_epoch integer, p_boxes jsonb)
returns integer
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  f public.tally_family;
begin
  perform tally_private.tally_lock(p_family);
  perform tally_private.tally_admin_auth(p_family, p_member, p_secret);
  select * into f from public.tally_family x where x.id = p_family;
  if p_epoch is null or p_epoch <> f.epoch then
    raise exception 'tally: stale epoch' using errcode = 'TA002';
  end if;
  return tally_private.tally_put_boxes(p_family, p_epoch, p_boxes);
end;
$$;

-- A member leaves. Their invites (and requests made with them) go. If they were the creator, the family no longer has one.
-- If no admin is left, the longest-standing remaining member becomes admin. The key is marked for rotation (an admin's
-- phone does it on its next sync). If nobody is left, the family and everything it holds on the relay is deleted.
-- Returns 'left' or 'deleted'.
create function public.tally_leave(p_family text, p_member text, p_secret text)
returns text
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  heir text;
begin
  perform tally_private.tally_lock(p_family);
  perform tally_private.tally_member_auth(p_family, p_member, p_secret);
  update public.tally_member m set status = 'left', role = 'member' where m.family_id = p_family and m.id = p_member;
  delete from public.tally_request r where r.family_id = p_family
    and r.invite_id in (select i.id from public.tally_invite i where i.family_id = p_family and i.created_by = p_member);
  delete from public.tally_invite i where i.family_id = p_family and i.created_by = p_member;
  update public.tally_family f set creator = null where f.id = p_family and f.creator = p_member;
  if not exists (select 1 from public.tally_member m where m.family_id = p_family and m.status = 'active') then
    delete from public.tally_family f where f.id = p_family;
    return 'deleted';
  end if;
  if not exists (select 1 from public.tally_member m where m.family_id = p_family and m.status = 'active' and m.role = 'admin') then
    select m.id into heir from public.tally_member m where m.family_id = p_family and m.status = 'active'
      order by m.joined_at, m.id limit 1;
    update public.tally_member m set role = 'admin' where m.family_id = p_family and m.id = heir;
  end if;
  update public.tally_family f set rotate = true where f.id = p_family;
  return 'left';
end;
$$;

-- ---------------------------------------------------------------- the background check

-- How many join requests are waiting, for the phone's background check. It takes the notice credential, which can do
-- nothing else; the answer is 0 unless the member is an active admin.
create function public.tally_pending(p_family text, p_member text, p_notice text)
returns integer
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  m public.tally_member;
begin
  if p_notice is null or length(p_notice) > 256 then
    raise exception 'tally: unauthorised' using errcode = '28000';
  end if;
  select * into m from public.tally_member x where x.family_id = p_family and x.id = p_member;
  if not found or m.notice_hash is null or m.notice_hash <> tally_private.tally_hash(p_notice) then
    raise exception 'tally: unauthorised' using errcode = '28000';
  end if;
  if m.status <> 'active' or m.role <> 'admin' then return 0; end if;
  return (select count(*) from public.tally_request r where r.family_id = p_family and r.status = 'pending' and r.expires_at > now());
end;
$$;

-- ---------------------------------------------------------------- keep-awake

-- A daily scheduled job calls this so the free project is never paused. It deletes expired requests and the expired
-- invites nothing needs, touches one row of a table and returns 1; no credentials, nothing revealed.
create or replace function public.tally_ping()
returns integer
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
  delete from public.tally_request r where r.expires_at < now();
  delete from public.tally_invite i where i.expires_at < now()
    and not exists (select 1 from public.tally_request r where r.family_id = i.family_id and r.invite_id = i.id);
  perform 1 from public.tally_family limit 1;
  return 1;
end;
$$;

-- ---------------------------------------------------------------- grants

-- New functions are executable by PUBLIC by default (and Supabase's default privileges add anon/authenticated):
-- reset everything, then grant exactly what the app calls.
revoke all on all functions in schema tally_private from public, anon, authenticated;

revoke all on function public.tally_family_create(text, text, text, text)                     from public, anon, authenticated;
revoke all on function public.tally_state(text, text, text, integer)                          from public, anon, authenticated;
revoke all on function public.tally_push(text, text, text, integer, jsonb)                    from public, anon, authenticated;
revoke all on function public.tally_pull(text, text, text, bigint, integer)                   from public, anon, authenticated;
revoke all on function public.tally_blob_put(text, text, text, integer, text, text)           from public, anon, authenticated;
revoke all on function public.tally_blob_get(text, text, text, text)                          from public, anon, authenticated;
revoke all on function public.tally_invite_create(text, text, text, text, text)               from public, anon, authenticated;
revoke all on function public.tally_invite_cancel(text, text, text, text)                     from public, anon, authenticated;
revoke all on function public.tally_join_request(text, text, text, text, text, text)          from public, anon, authenticated;
revoke all on function public.tally_join_status(text, text, text, text)                       from public, anon, authenticated;
revoke all on function public.tally_join_cancel(text, text, text, text)                       from public, anon, authenticated;
revoke all on function public.tally_request_decide(text, text, text, text, boolean, integer, text) from public, anon, authenticated;
revoke all on function public.tally_member_role(text, text, text, text, boolean)              from public, anon, authenticated;
revoke all on function public.tally_member_remove(text, text, text, text, integer, jsonb)     from public, anon, authenticated;
revoke all on function public.tally_rotate(text, text, text, integer, jsonb)                  from public, anon, authenticated;
revoke all on function public.tally_box_put(text, text, text, integer, jsonb)                 from public, anon, authenticated;
revoke all on function public.tally_leave(text, text, text)                                   from public, anon, authenticated;
revoke all on function public.tally_pending(text, text, text)                                 from public, anon, authenticated;
revoke all on function public.tally_ping()                                                    from public, anon, authenticated;

grant execute on function public.tally_family_create(text, text, text, text)                  to anon, authenticated;
grant execute on function public.tally_state(text, text, text, integer)                       to anon, authenticated;
grant execute on function public.tally_push(text, text, text, integer, jsonb)                 to anon, authenticated;
grant execute on function public.tally_pull(text, text, text, bigint, integer)                to anon, authenticated;
grant execute on function public.tally_blob_put(text, text, text, integer, text, text)        to anon, authenticated;
grant execute on function public.tally_blob_get(text, text, text, text)                       to anon, authenticated;
grant execute on function public.tally_invite_create(text, text, text, text, text)            to anon, authenticated;
grant execute on function public.tally_invite_cancel(text, text, text, text)                  to anon, authenticated;
grant execute on function public.tally_join_request(text, text, text, text, text, text)       to anon, authenticated;
grant execute on function public.tally_join_status(text, text, text, text)                    to anon, authenticated;
grant execute on function public.tally_join_cancel(text, text, text, text)                    to anon, authenticated;
grant execute on function public.tally_request_decide(text, text, text, text, boolean, integer, text) to anon, authenticated;
grant execute on function public.tally_member_role(text, text, text, text, boolean)           to anon, authenticated;
grant execute on function public.tally_member_remove(text, text, text, text, integer, jsonb)  to anon, authenticated;
grant execute on function public.tally_rotate(text, text, text, integer, jsonb)               to anon, authenticated;
grant execute on function public.tally_box_put(text, text, text, integer, jsonb)              to anon, authenticated;
grant execute on function public.tally_leave(text, text, text)                                to anon, authenticated;
grant execute on function public.tally_pending(text, text, text)                              to anon, authenticated;
grant execute on function public.tally_ping()                                                 to anon, authenticated;
