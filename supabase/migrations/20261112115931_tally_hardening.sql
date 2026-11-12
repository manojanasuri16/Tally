-- Hardening after the security review of 2026-11-12 (docs/SECURITY_REVIEW.md: H1, M1, L5 and the relay Info items).
--
-- H1, filling or freezing the relay with nothing but the APK's publishable key:
--   - Each family has a byte budget for its records and key boxes: 5 MiB while it has only ever had one member, 20 MiB once
--     someone else has been let in (record_bytes, box_bytes, kept up to date by every call that writes them). A push that
--     would grow a family past it is refused (TA003); one that shrinks it or keeps its size is always taken.
--   - The whole relay has a ceiling: no push, file or new family that adds bytes once the families hold 300 MiB between them,
--     or once the database (read at most every 5 minutes) is above 425 MiB of the free tier's 500. Removals, rotations,
--     approvals and deletions still work at the ceiling, so it never locks a family out of its own security actions, and the
--     database never reaches the size at which Supabase makes the project read-only for everyone.
--   - At most 300 key epochs per family (TA003 after that). Key boxes of members who are no longer active are deleted, and each
--     member keeps only its newest 20 boxes (a phone restored from a backup more than 20 rotations old comes back with "back
--     on a new phone" instead).
--   - Starting a family costs a small proof of work (p_pow: the SHA-256 of "tally-pow|<family>|<member>|<pow>" begins with 20
--     zero bits; about a second on a phone) and is limited per source address: 5 an hour and 20 a day from one address (an
--     IPv6 address counts by its /64), within relay-wide sliding windows of 100 an hour and 500 a day. The address is kept
--     only as a peppered SHA-256 (the pepper never leaves the database), for two days. Without a usable address only the
--     relay-wide windows apply.
--   - A pull page holds at most 4 MiB of rows (always at least one), and a family may download at most 200 MiB a day (pulls
--     and files together; TA003 beyond it), so nobody can burn the project's egress through a family of their own.
--   - A family that never had a second member and has not pulled for 60 days is deleted by tally_ping (its phone is told on
--     its next sync and keeps every entry).
--   - tally_relay_usage also says how much the database grew in the last 24 hours (for an earlier alert) and reads the
--     database size from a copy at most 5 minutes old; tally_ping sweeps at most every 10 minutes (it still answers 1 every
--     time). Neither costs anything when called over and over.
-- M1: tally_member_reclaim hands the creator's place only to the creator's own new phone (the caller is the creator), and an
--     admin's place only when the approving admin did not make the invite the request came with (a second person takes part:
--     someone else made the invite, or another admin approves).
-- L5: tally_state shows a member who is not an admin only the invites they made.
-- Info: the default privileges of objects postgres creates no longer grant anything to anon, authenticated or PUBLIC, so a
--     table, sequence or function added later is reachable only through an explicit grant.
--
-- tally_family_create gains p_pow, so its four-argument form is dropped: Tally 0.5.0 and older can no longer start a family
-- (everything else they do keeps working).

-- ---------------------------------------------------------------- default privileges (Info: future objects)

alter default privileges in schema public revoke all on tables    from anon, authenticated;
alter default privileges in schema public revoke all on sequences from anon, authenticated;
alter default privileges in schema public revoke all on functions from anon, authenticated;
alter default privileges revoke execute on functions from public;

-- ---------------------------------------------------------------- what each family holds and moves

alter table public.tally_family
  add column record_bytes bigint not null default 0,
  add column box_bytes    bigint not null default 0,
  add column last_active  timestamptz not null default now(),
  add column egress_day   date not null default current_date,
  add column egress_bytes bigint not null default 0;
create index tally_family_last_active on public.tally_family (last_active);

update public.tally_family f set
  record_bytes = coalesce((select sum(length(r.blob)) from public.tally_record r where r.family_id = f.id), 0),
  box_bytes    = coalesce((select sum(length(b.box))  from public.tally_box b    where b.family_id = f.id), 0);

-- ---------------------------------------------------------------- private bookkeeping (never exposed)

create table tally_private.tally_secret (
  k text primary key,
  v text not null
);
insert into tally_private.tally_secret (k, v)
  values ('ip-pepper', encode(pg_catalog.sha256(convert_to(gen_random_uuid()::text || gen_random_uuid()::text || clock_timestamp()::text, 'UTF8')), 'hex'));

-- One row per family started: when, and from which source (a peppered hash); kept two days.
create table tally_private.tally_create_log (
  at      timestamptz not null default now(),
  source  text not null,
  kind    text not null           -- which request header gave the address: cf | real | xff | none | bad
);
create index tally_create_log_at on tally_private.tally_create_log (at);
create index tally_create_log_source on tally_private.tally_create_log (source, at);

-- The database size as last read, and when tally_ping last swept.
create table tally_private.tally_usage (
  id        integer primary key check (id = 1),
  db_bytes  bigint not null default 0,
  at        timestamptz not null default '-infinity',
  ping_at   timestamptz not null default '-infinity'
);
insert into tally_private.tally_usage (id) values (1);

-- The database size once an hour, kept eight days, for "how much did it grow".
create table tally_private.tally_usage_log (
  at        timestamptz primary key,
  db_bytes  bigint not null
);

alter table tally_private.tally_secret     enable row level security;
alter table tally_private.tally_create_log enable row level security;
alter table tally_private.tally_usage      enable row level security;
alter table tally_private.tally_usage_log  enable row level security;
revoke all on table tally_private.tally_secret, tally_private.tally_create_log, tally_private.tally_usage,
  tally_private.tally_usage_log from public, anon, authenticated;

-- ---------------------------------------------------------------- private helpers

-- Where a request comes from, as a peppered hash of its address (IPv6 by its /64): Cloudflare's cf-connecting-ip, else
-- x-real-ip, else the first x-forwarded-for entry. 'unknown' when no header gives a usable address.
create function tally_private.tally_source(out source text, out kind text)
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  h jsonb;
  ip text;
  a inet;
  net text;
begin
  source := 'unknown'; kind := 'none';
  begin
    h := nullif(current_setting('request.headers', true), '')::jsonb;
  exception when others then
    h := null;
  end;
  if h is null then return; end if;
  ip := nullif(btrim(h ->> 'cf-connecting-ip'), ''); kind := 'cf';
  if ip is null then ip := nullif(btrim(h ->> 'x-real-ip'), ''); kind := 'real'; end if;
  if ip is null then ip := nullif(btrim(split_part(coalesce(h ->> 'x-forwarded-for', ''), ',', 1)), ''); kind := 'xff'; end if;
  if ip is null then kind := 'none'; return; end if;
  begin
    a := ip::inet;
  exception when others then
    kind := 'bad'; return;
  end;
  net := case when pg_catalog.family(a) = 6 then pg_catalog.network(pg_catalog.set_masklen(a, 64))::text else pg_catalog.host(a) end;
  select encode(pg_catalog.sha256(convert_to(s.v || '|' || net, 'UTF8')), 'hex') into source
    from tally_private.tally_secret s where s.k = 'ip-pepper';
  source := coalesce(source, 'unknown');
end;
$$;

-- The database's size, read again when the copy is more than 5 minutes old (and logged once an hour, kept eight days).
create function tally_private.tally_db_bytes()
returns bigint
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  b bigint;
begin
  update tally_private.tally_usage u set db_bytes = pg_catalog.pg_database_size(pg_catalog.current_database()), at = now()
    where u.id = 1 and u.at < now() - interval '5 minutes'
    returning u.db_bytes into b;
  if b is not null then
    insert into tally_private.tally_usage_log (at, db_bytes) values (date_trunc('hour', now()), b)
      on conflict (at) do nothing;
    delete from tally_private.tally_usage_log l where l.at < now() - interval '8 days';
    return b;
  end if;
  select u.db_bytes into b from tally_private.tally_usage u where u.id = 1;
  return coalesce(b, 0);
end;
$$;

-- Raises TA003 when the relay as a whole has no room for p_more more bytes (300 MiB across all families, or the database above
-- 425 MiB). Called only by calls that add bytes; never by removals, rotations, approvals or deletions.
create function tally_private.tally_room(p_more bigint)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  held bigint;
begin
  select coalesce(sum(f.record_bytes + f.box_bytes + f.blob_bytes), 0) into held from public.tally_family f;
  if held + greatest(coalesce(p_more, 0), 0) > 300 * 1024 * 1024
     or tally_private.tally_db_bytes() > 425 * 1024 * 1024 then
    raise exception 'tally: the relay is full' using errcode = 'TA003';
  end if;
end;
$$;

-- A family's byte budget for records and key boxes: 5 MiB while it has only ever had one member, else 20 MiB.
create function tally_private.tally_budget(p_family text)
returns bigint
language sql
stable
security definer
set search_path = ''
as $$
  select (case when (select count(*) from public.tally_member m where m.family_id = p_family) <= 1
               then 5 * 1024 * 1024 else 20 * 1024 * 1024 end)::bigint;
$$;

-- Counts p_bytes against the family's daily download allowance (200 MiB), and notes the family as active (at most once a day).
create function tally_private.tally_egress(p_family text, p_bytes bigint)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  used bigint;
begin
  if coalesce(p_bytes, 0) = 0 and exists (select 1 from public.tally_family f
                                          where f.id = p_family and f.last_active >= now() - interval '1 day') then
    return;
  end if;
  update public.tally_family f set
      egress_bytes = (case when f.egress_day = current_date then f.egress_bytes else 0 end) + coalesce(p_bytes, 0),
      egress_day = current_date,
      last_active = case when f.last_active < now() - interval '1 day' then now() else f.last_active end
    where f.id = p_family
    returning f.egress_bytes into used;
  if used > 200 * 1024 * 1024 then
    raise exception 'tally: this family downloaded too much today' using errcode = 'TA003';
  end if;
end;
$$;

-- Deletes the key boxes nobody needs (members no longer active; boxes beyond each member's newest 20), then recounts them.
create function tally_private.tally_box_tidy(p_family text)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
  delete from public.tally_box b using public.tally_member m
    where b.family_id = p_family and m.family_id = b.family_id and m.id = b.member_id and m.status <> 'active';
  delete from public.tally_box b
    where b.family_id = p_family
      and b.epoch not in (select x.epoch from public.tally_box x
                          where x.family_id = b.family_id and x.member_id = b.member_id
                          order by x.epoch desc limit 20);
  update public.tally_family f
    set box_bytes = coalesce((select sum(length(b.box)) from public.tally_box b where b.family_id = p_family), 0)
    where f.id = p_family;
end;
$$;

-- As before (every recipient active, at most once), then the family's boxes are tidied and recounted.
create or replace function tally_private.tally_put_boxes(p_family text, p_epoch integer, p_boxes jsonb)
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
  perform tally_private.tally_box_tidy(p_family);
  return k;
end;
$$;

-- As before, plus: at most 300 epochs per family.
create or replace function tally_private.tally_new_epoch(p_family text, p_caller text, p_epoch integer, p_boxes jsonb)
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
  if p_epoch > 300 then
    raise exception 'tally: too many keys in this family' using errcode = 'TA003';
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

-- ---------------------------------------------------------------- starting a family (H1)

drop function public.tally_family_create(text, text, text, text);

-- As before (idempotent for the creator), plus: the proof of work, the per-source and relay-wide limits, and room on the relay.
create function public.tally_family_create(p_family text, p_member text, p_secret_hash text, p_notice_hash text, p_pow text)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  src_source text;
  src_kind text;
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
  if p_pow is null or p_pow !~ '^[0-9]{1,20}$'
     or left(encode(pg_catalog.sha256(convert_to('tally-pow|' || p_family || '|' || p_member || '|' || p_pow, 'UTF8')), 'hex'), 5) <> '00000' then
    raise exception 'tally: bad proof of work' using errcode = '22023';
  end if;
  perform tally_private.tally_lock(p_family);
  if exists (select 1 from public.tally_family f where f.id = p_family) then
    if exists (select 1 from public.tally_family f join public.tally_member m on m.family_id = f.id
               where f.id = p_family and f.creator = p_member and m.id = p_member and m.secret_hash = p_secret_hash and m.status = 'active') then
      return;
    end if;
    raise exception 'tally: unauthorised' using errcode = '28000';
  end if;
  -- the limits, counted under one lock so two creates cannot both slip under them
  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('tally:create', 0));
  select s.source, s.kind into src_source, src_kind from tally_private.tally_source() s;
  if src_source <> 'unknown' and (
       (select count(*) from tally_private.tally_create_log l where l.source = src_source and l.at > now() - interval '1 hour') >= 5
    or (select count(*) from tally_private.tally_create_log l where l.source = src_source and l.at > now() - interval '24 hours') >= 20) then
    raise exception 'tally: too many new families from here, try later' using errcode = 'TA003';
  end if;
  if (select count(*) from tally_private.tally_create_log l where l.at > now() - interval '1 hour') >= 100
     or (select count(*) from tally_private.tally_create_log l where l.at > now() - interval '24 hours') >= 500 then
    raise exception 'tally: too many new families, try later' using errcode = 'TA003';
  end if;
  perform tally_private.tally_room(0);
  insert into tally_private.tally_create_log (source, kind) values (src_source, src_kind);
  insert into public.tally_family (id, creator, epoch) values (p_family, p_member, 1);
  insert into public.tally_member (family_id, id, secret_hash, notice_hash, role, status, has_epoch)
    values (p_family, p_member, p_secret_hash, p_notice_hash, 'admin', 'active', 1);
end;
$$;

-- ---------------------------------------------------------------- records (H1)

-- As before, plus the family's byte budget and the relay's room for whatever a push adds.
create or replace function public.tally_push(p_family text, p_member text, p_secret text, p_epoch integer, p_rows jsonb)
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
  delta bigint;
  held bigint;
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

  -- how many bytes this push adds (the last copy of an id wins, as below)
  select coalesce(sum(length(d.rblob)), 0) - coalesce(sum(length(t.blob)), 0) into delta
  from (
    select distinct on (e.value ->> 'id') e.value ->> 'id' as rid, e.value ->> 'blob' as rblob
    from jsonb_array_elements(p_rows) with ordinality as e(value, ord)
    order by e.value ->> 'id', e.ord desc
  ) d
  left join public.tally_record t on t.family_id = p_family and t.id = d.rid;

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

  update public.tally_family x set record_count = x.record_count + added, record_bytes = x.record_bytes + delta
    where x.id = p_family
    returning x.record_count, x.record_bytes + x.box_bytes into total, held;
  if total > 20000 then
    raise exception 'tally: too many records in this family' using errcode = 'TA003';
  end if;
  if delta > 0 then
    if held > tally_private.tally_budget(p_family) then
      raise exception 'tally: this family is full' using errcode = 'TA003';
    end if;
    perform tally_private.tally_room(0);
  end if;
  return n;
end;
$$;

-- A family's records with seq > p_since, oldest first: at most p_max (clamped to 1..500) and at most 4 MiB of blobs per call
-- (always at least one row), counted against the family's daily download allowance. A short page is not the end: callers
-- page until a call returns nothing.
create or replace function public.tally_pull(p_family text, p_member text, p_secret text, p_since bigint, p_max integer)
returns table (id text, kind text, blob text, seq bigint)
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  lim integer := greatest(1, least(coalesce(p_max, 500), 500));
  bytes bigint;
begin
  perform tally_private.tally_member_auth(p_family, p_member, p_secret);
  select coalesce(sum(x.len), 0) into bytes from (
    select length(t.blob) as len, sum(length(t.blob)) over (order by t.seq) as cum, row_number() over (order by t.seq) as rn
    from public.tally_record t
    where t.family_id = p_family and t.seq > coalesce(p_since, 0)
    order by t.seq limit lim) x
  where x.rn = 1 or x.cum <= 4 * 1024 * 1024;
  perform tally_private.tally_egress(p_family, bytes);
  return query
    select x.id, x.kind, x.blob, x.seq from (
      select t.id, t.kind, t.blob, t.seq, sum(length(t.blob)) over (order by t.seq) as cum, row_number() over (order by t.seq) as rn
      from public.tally_record t
      where t.family_id = p_family and t.seq > coalesce(p_since, 0)
      order by t.seq limit lim) x
    where x.rn = 1 or x.cum <= 4 * 1024 * 1024
    order by x.seq;
end;
$$;

-- ---------------------------------------------------------------- files (H1: room, downloads)

-- As in tally_files_in_transit, plus room on the relay for the file.
create or replace function public.tally_blob_put(p_family text, p_member text, p_secret text, p_epoch integer, p_id text, p_data text)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  me public.tally_member;
  f public.tally_family;
  existed boolean;
  asked boolean;
begin
  if p_id is null or p_id !~ '^[A-Za-z0-9_-]{1,64}$' then
    raise exception 'tally: bad id' using errcode = '22023';
  end if;
  if p_data is null or length(p_data) = 0 or length(p_data) > 6 * 1024 * 1024 then
    raise exception 'tally: bad data' using errcode = '54000';
  end if;
  perform tally_private.tally_lock(p_family);
  me := tally_private.tally_member_auth(p_family, p_member, p_secret);
  select * into f from public.tally_family x where x.id = p_family;
  if p_epoch is null or p_epoch <> f.epoch then
    raise exception 'tally: stale epoch' using errcode = 'TA002';
  end if;
  perform tally_private.tally_blob_sweep(p_family);
  perform tally_private.tally_room(length(p_data));
  existed := exists (select 1 from public.tally_blob b where b.family_id = p_family and b.id = p_id);
  asked := exists (select 1 from public.tally_file_ask a join public.tally_member m on m.family_id = a.family_id and m.id = a.member_id
                   where a.family_id = p_family and a.blob_id = p_id and a.member_id <> me.id and m.status = 'active');
  insert into public.tally_blob as b (family_id, id, data, uploader, created_at, expires_at)
    values (p_family, p_id, p_data, me.id, now(), now() + interval '30 days')
    on conflict on constraint tally_blob_pkey do update
      set data = excluded.data, uploader = excluded.uploader, expires_at = excluded.expires_at;
  if asked then
    insert into public.tally_blob_owed (family_id, blob_id, member_id)
      select p_family, p_id, a.member_id
      from public.tally_file_ask a join public.tally_member m on m.family_id = a.family_id and m.id = a.member_id
      where a.family_id = p_family and a.blob_id = p_id and a.member_id <> me.id and m.status = 'active'
      on conflict on constraint tally_blob_owed_pkey do nothing;
  elsif not existed then
    insert into public.tally_blob_owed (family_id, blob_id, member_id)
      select p_family, p_id, m.id from public.tally_member m
      where m.family_id = p_family and m.status = 'active' and m.id <> me.id;
  end if;
  delete from public.tally_file_ask a where a.family_id = p_family and a.blob_id = p_id;
  perform tally_private.tally_blob_sweep(p_family);           -- drops it again if nobody is owed it; recounts the bytes
  if (select x.blob_bytes from public.tally_family x where x.id = p_family) > 50 * 1024 * 1024 then
    raise exception 'tally: too many files waiting in this family' using errcode = 'TA003';
  end if;
end;
$$;

-- As before, counted against the family's daily download allowance.
create or replace function public.tally_blob_get(p_family text, p_member text, p_secret text, p_id text)
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
  select b.data into d from public.tally_blob b where b.family_id = p_family and b.id = p_id and b.expires_at > now();
  if d is not null then
    perform tally_private.tally_egress(p_family, length(d));
  end if;
  return d;
end;
$$;

-- ---------------------------------------------------------------- approving (boxes counted)

-- As in tally_membership, with the family's boxes tidied and recounted after the grant is stored.
create or replace function public.tally_request_decide(p_family text, p_member text, p_secret text, p_invite text, p_allow boolean, p_epoch integer, p_box text)
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
  perform tally_private.tally_box_tidy(p_family);
end;
$$;

-- ---------------------------------------------------------------- back on a new phone (M1)

-- As in tally_rejoin, plus two refusals (TA004):
--   - the creator's place goes only to the creator's own new phone (p_member = p_target: the creator approves it on the old one);
--   - another admin's place only when the caller did not make the invite this request came with, so a second person takes
--     part (someone else made the invite, or another admin approves). A request whose invite is gone counts as the caller's.
create or replace function public.tally_member_reclaim(p_family text, p_member text, p_secret text, p_invite text, p_target text, p_epoch integer, p_boxes jsonb)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  r public.tally_request;
  t public.tally_member;
  f public.tally_family;
  maker text;
begin
  perform tally_private.tally_lock(p_family);
  perform tally_private.tally_admin_auth(p_family, p_member, p_secret);
  select * into r from public.tally_request x
    where x.family_id = p_family and x.invite_id = p_invite and x.status = 'pending' and x.expires_at > now();
  if not found then
    raise exception 'tally: request not open' using errcode = 'TA005';
  end if;
  select * into t from public.tally_member m where m.family_id = p_family and m.id = p_target and m.status = 'active';
  if not found then
    raise exception 'tally: not a member' using errcode = 'TA004';
  end if;
  select * into f from public.tally_family x where x.id = p_family;
  if f.creator is not null and f.creator = p_target and p_target <> p_member then
    raise exception 'tally: only the creator can move the creator''s place' using errcode = 'TA004';
  end if;
  if t.role = 'admin' and p_target <> p_member then
    select i.created_by into maker from public.tally_invite i where i.family_id = p_family and i.id = p_invite;
    if maker is null or maker = p_member then
      raise exception 'tally: another person must take part' using errcode = 'TA004';
    end if;
  end if;
  if p_boxes is null or jsonb_typeof(p_boxes) <> 'array'
     or not exists (select 1 from jsonb_array_elements(p_boxes) as e(value) where e.value ->> 'member' = p_target) then
    raise exception 'tally: the member needs a box' using errcode = '22023';
  end if;
  insert into public.tally_replaced (family_id, member_id, secret_hash)
    values (p_family, p_target, t.secret_hash)
    on conflict on constraint tally_replaced_pkey do nothing;
  delete from public.tally_box b where b.family_id = p_family and b.member_id = p_target;
  update public.tally_member m set secret_hash = r.secret_hash, notice_hash = r.notice_hash, has_epoch = 0
    where m.family_id = p_family and m.id = p_target;
  perform tally_private.tally_new_epoch(p_family, p_member, p_epoch, p_boxes);
end;
$$;

-- ---------------------------------------------------------------- the family as one member sees it (L5)

-- As in tally_rejoin, except that a member who is not an admin sees only the invites they made.
create or replace function public.tally_state(p_family text, p_member text, p_secret text, p_after integer)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  me public.tally_member;
  f public.tally_family;
  h text;
begin
  if p_family is null or p_member is null or p_secret is null or length(p_secret) > 256 then
    raise exception 'tally: unauthorised' using errcode = '28000';
  end if;
  h := tally_private.tally_hash(p_secret);
  select * into me from public.tally_member m where m.family_id = p_family and m.id = p_member;
  if not found then
    raise exception 'tally: unauthorised' using errcode = '28000';
  end if;
  if me.secret_hash <> h then
    if exists (select 1 from public.tally_replaced r
               where r.family_id = p_family and r.member_id = p_member and r.secret_hash = h) then
      raise exception 'tally: replaced by a new phone' using errcode = 'TA007';
    end if;
    raise exception 'tally: unauthorised' using errcode = '28000';
  end if;
  if me.status = 'removed' then raise exception 'tally: removed' using errcode = 'TA001'; end if;
  if me.status <> 'active' then raise exception 'tally: unauthorised' using errcode = '28000'; end if;
  select * into f from public.tally_family x where x.id = p_family;
  return jsonb_build_object(
    'epoch', f.epoch,
    'creator', f.creator,
    'rotate', f.rotate,
    'meta', f.meta,
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
      from public.tally_invite i where i.family_id = p_family and not i.used and i.expires_at > now()
        and (me.role = 'admin' or i.created_by = p_member)), '[]'::jsonb),
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

-- ---------------------------------------------------------------- keep-awake and the storage check (H1, Info)

-- Answers 1 on every call. Its sweeps run at most every 10 minutes: expired requests and invites, files past 30 days, old asks,
-- the create log, and families that never had a second member and have not pulled for 60 days.
create or replace function public.tally_ping()
returns integer
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  fam text;
begin
  update tally_private.tally_usage u set ping_at = now() where u.id = 1 and u.ping_at < now() - interval '10 minutes';
  if not found then return 1; end if;
  delete from public.tally_request r where r.expires_at < now();
  delete from public.tally_invite i where i.expires_at < now()
    and not exists (select 1 from public.tally_request r where r.family_id = i.family_id and r.invite_id = i.id);
  for fam in select distinct b.family_id from public.tally_blob b where b.expires_at <= now() loop
    perform tally_private.tally_lock(fam);
    perform tally_private.tally_blob_sweep(fam);
  end loop;
  delete from public.tally_file_ask a where a.created_at <= now() - interval '30 days';
  for fam in select f.id from public.tally_family f
             where f.last_active < now() - interval '60 days'
               and (select count(*) from public.tally_member m where m.family_id = f.id) <= 1 loop
    perform tally_private.tally_lock(fam);
    delete from public.tally_family f where f.id = fam and f.last_active < now() - interval '60 days'
      and (select count(*) from public.tally_member m where m.family_id = f.id) <= 1;
  end loop;
  delete from tally_private.tally_create_log l where l.at < now() - interval '2 days';
  perform tally_private.tally_db_bytes();
  return 1;
end;
$$;

-- For the scheduled GitHub check: relay-wide totals only, nothing about any family. grew_24h_bytes is how much the database
-- grew since about 24 hours ago (since the oldest reading while the log is younger than that). Same signature: grants stay.
create or replace function public.tally_relay_usage()
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  db bigint := tally_private.tally_db_bytes();
  before bigint;
begin
  select l.db_bytes into before from tally_private.tally_usage_log l
    where l.at <= now() - interval '24 hours' order by l.at desc limit 1;
  if before is null then
    select l.db_bytes into before from tally_private.tally_usage_log l order by l.at limit 1;
  end if;
  return jsonb_build_object(
    'db_bytes', db,
    'limit_bytes', 500 * 1024 * 1024,
    'percent', round(100.0 * db / (500 * 1024 * 1024), 1),
    'blob_bytes', (select coalesce(sum(f.blob_bytes), 0) from public.tally_family f),
    'record_bytes', (select coalesce(sum(f.record_bytes + f.box_bytes), 0) from public.tally_family f),
    'grew_24h_bytes', greatest(db - coalesce(before, db), 0)
  );
end;
$$;

-- ---------------------------------------------------------------- grants

revoke all on all functions in schema tally_private from public, anon, authenticated;

revoke all on function public.tally_family_create(text, text, text, text, text) from public, anon, authenticated;
grant execute on function public.tally_family_create(text, text, text, text, text) to anon, authenticated;
-- every other function here was replaced with create or replace and keeps the grants of the migration that made it
