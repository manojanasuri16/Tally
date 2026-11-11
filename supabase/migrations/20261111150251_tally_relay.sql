-- Tally's sync relay (docs/DESIGN.md, "Sync").
--
-- The relay holds only ciphertext. A family is an id plus the SHA-256 of its auth token; the token itself is derived
-- on the phones from the family key (HKDF, info "tally-auth") and never stored here. Records and file blobs are opaque
-- base64 text sealed with AES-256-GCM on the phone.
--
-- Access model: RLS is on for every table with no policies, and anon/authenticated have no table or sequence grants,
-- so the Data API can read or write nothing directly. Everything goes through the security definer functions below,
-- each of which checks the presented token against the family's stored hash before touching that family's rows.
-- They live in public because PostgREST only exposes that schema; the token check is the access control.

-- ---------------------------------------------------------------- tables

create table public.tally_families (
  id          text primary key,
  token_hash  text not null,
  created_at  timestamptz not null default now(),
  constraint tally_families_id_format   check (id ~ '^[A-Za-z0-9_-]{22}$'),
  constraint tally_families_hash_format check (token_hash ~ '^[0-9a-f]{64}$')
);

-- One global sequence for every record upsert. See tally_push for why paging over it never misses a row.
create sequence public.tally_seq as bigint;

create table public.tally_records (
  family_id  text not null references public.tally_families (id) on delete cascade,
  id         text not null,
  kind       text not null,
  blob       text not null,
  seq        bigint not null default nextval('public.tally_seq'),
  primary key (family_id, id),
  constraint tally_records_kind check (kind in ('loan', 'payment', 'file', 'member'))
);
create index tally_records_family_seq on public.tally_records (family_id, seq);

create table public.tally_blobs (
  family_id  text not null references public.tally_families (id) on delete cascade,
  id         text not null,
  data       text not null,
  primary key (family_id, id)
);

alter table public.tally_families enable row level security;
alter table public.tally_records  enable row level security;
alter table public.tally_blobs    enable row level security;

-- Supabase's default privileges grant table access to anon/authenticated in public; take all of it back.
revoke all on table public.tally_families, public.tally_records, public.tally_blobs from public, anon, authenticated;
revoke all on sequence public.tally_seq from public, anon, authenticated;

-- ---------------------------------------------------------------- the token check (not exposed)

create schema if not exists tally_private;
revoke all on schema tally_private from public, anon, authenticated;

-- Raises unless family p_family exists and sha256(p_token) is its stored hash. The same error for an unknown family
-- and a wrong token, so the endpoint does not tell anyone which family ids exist.
create function tally_private.tally_auth(p_family text, p_token text)
returns void
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  h text;
begin
  if p_family is null or p_token is null or length(p_token) > 256 then
    raise exception 'tally: unauthorised' using errcode = '28000';
  end if;
  select f.token_hash into h from public.tally_families f where f.id = p_family;
  if h is null or h <> encode(pg_catalog.sha256(convert_to(p_token, 'UTF8')), 'hex') then
    raise exception 'tally: unauthorised' using errcode = '28000';
  end if;
end;
$$;
revoke all on function tally_private.tally_auth(text, text) from public, anon, authenticated;

-- ---------------------------------------------------------------- public functions

-- Registers a family. Idempotent for its creator (the same hash again succeeds, so a retried create is harmless), but
-- an existing family's hash is never replaced: a different hash for a known id raises.
create function public.tally_family_create(family_id text, token_hash text)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  existing text;
begin
  if family_id is null or family_id !~ '^[A-Za-z0-9_-]{22}$' then
    raise exception 'tally: bad family id' using errcode = '22023';
  end if;
  if token_hash is null or token_hash !~ '^[0-9a-f]{64}$' then
    raise exception 'tally: bad token hash' using errcode = '22023';
  end if;
  insert into public.tally_families (id, token_hash)
    values (tally_family_create.family_id, tally_family_create.token_hash)
    on conflict on constraint tally_families_pkey do nothing;
  select f.token_hash into existing from public.tally_families f where f.id = tally_family_create.family_id;
  if existing is distinct from tally_family_create.token_hash then
    raise exception 'tally: unauthorised' using errcode = '28000';
  end if;
end;
$$;

-- Upserts sealed records: rows = [{ "id": text, "kind": text, "blob": text }, ...] (at most 500, each blob at most
-- 64 KiB of base64). Returns how many rows were written.
--
-- Ordering: every upsert takes a fresh value of the global sequence tally_seq, and readers page with "seq > cursor".
-- Sequence values are handed out at nextval() time but become visible at COMMIT, so without care a push that took
-- seq 10 could commit after another push that took seq 11, and a reader who had already seen 11 (and moved its
-- cursor to 11) would never see 10. We close that by taking a per-family, transaction-scoped advisory lock before any
-- nextval(): pushes to one family run one at a time and each holds the lock until it commits, so within a family seq
-- values become visible in the order they were taken. Readers only ever page within one family, so how other
-- families' seqs interleave does not matter. (Two family ids whose hashes collide merely serialise their pushes.)
-- The alternative, a per-family counter row, would need the same row lock, so the advisory lock costs nothing extra.
create function public.tally_push(family_id text, token text, rows jsonb)
returns integer
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  n integer;
  r jsonb;
begin
  perform tally_private.tally_auth(tally_push.family_id, tally_push.token);
  if tally_push.rows is null or jsonb_typeof(tally_push.rows) <> 'array' then
    raise exception 'tally: rows must be an array' using errcode = '22023';
  end if;
  n := jsonb_array_length(tally_push.rows);
  if n = 0 then return 0; end if;
  if n > 500 then
    raise exception 'tally: too many rows' using errcode = '54000';
  end if;
  for r in select e.value from jsonb_array_elements(tally_push.rows) as e(value) loop
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

  perform pg_catalog.pg_advisory_xact_lock(pg_catalog.hashtextextended('tally:' || tally_push.family_id, 0));

  -- An id may appear twice in one call; the last one wins (ON CONFLICT cannot touch a row twice in one statement).
  insert into public.tally_records as t (family_id, id, kind, blob, seq)
  select tally_push.family_id, d.rid, d.rkind, d.rblob, nextval('public.tally_seq')
  from (
    select distinct on (e.value ->> 'id')
           e.value ->> 'id' as rid, e.value ->> 'kind' as rkind, e.value ->> 'blob' as rblob
    from jsonb_array_elements(tally_push.rows) with ordinality as e(value, ord)
    order by e.value ->> 'id', e.ord desc
  ) d
  on conflict on constraint tally_records_pkey do update
    set kind = excluded.kind, blob = excluded.blob, seq = nextval('public.tally_seq');

  get diagnostics n = row_count;
  return n;
end;
$$;

-- A family's records with seq > since, oldest first, at most max (clamped to 1..500) per call.
create function public.tally_pull(family_id text, token text, since bigint, max integer)
returns table (id text, kind text, blob text, seq bigint)
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  perform tally_private.tally_auth(tally_pull.family_id, tally_pull.token);
  return query
    select t.id, t.kind, t.blob, t.seq
    from public.tally_records t
    where t.family_id = tally_pull.family_id and t.seq > coalesce(tally_pull.since, 0)
    order by t.seq
    limit greatest(1, least(coalesce(tally_pull.max, 500), 500));
end;
$$;

-- Stores a sealed file as base64 text: at most 6 MiB (a 4 MB file plus base64 and GCM overhead).
create function public.tally_blob_put(family_id text, token text, id text, data text)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
  perform tally_private.tally_auth(tally_blob_put.family_id, tally_blob_put.token);
  if tally_blob_put.id is null or tally_blob_put.id !~ '^[A-Za-z0-9_-]{1,64}$' then
    raise exception 'tally: bad id' using errcode = '22023';
  end if;
  if tally_blob_put.data is null or length(tally_blob_put.data) = 0 or length(tally_blob_put.data) > 6 * 1024 * 1024 then
    raise exception 'tally: bad data' using errcode = '54000';
  end if;
  insert into public.tally_blobs as b (family_id, id, data)
    values (tally_blob_put.family_id, tally_blob_put.id, tally_blob_put.data)
    on conflict on constraint tally_blobs_pkey do update set data = excluded.data;
end;
$$;

-- A sealed file, or null when the family has none under that id.
create function public.tally_blob_get(family_id text, token text, id text)
returns text
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  d text;
begin
  perform tally_private.tally_auth(tally_blob_get.family_id, tally_blob_get.token);
  select b.data into d from public.tally_blobs b
    where b.family_id = tally_blob_get.family_id and b.id = tally_blob_get.id;
  return d;
end;
$$;

-- Keep-awake: a daily scheduled job calls this so the free project is never paused for inactivity. It reads at most
-- one row of a table (so the database is really touched) and returns a constant; no token, nothing revealed.
create function public.tally_ping()
returns integer
language plpgsql
stable
security definer
set search_path = ''
as $$
begin
  perform 1 from public.tally_families limit 1;
  return 1;
end;
$$;

-- New functions are executable by PUBLIC by default (and Supabase's default privileges add anon/authenticated);
-- reset, then grant exactly what the app needs.
revoke all on function public.tally_family_create(text, text)            from public, anon, authenticated;
revoke all on function public.tally_push(text, text, jsonb)              from public, anon, authenticated;
revoke all on function public.tally_pull(text, text, bigint, integer)    from public, anon, authenticated;
revoke all on function public.tally_blob_put(text, text, text, text)     from public, anon, authenticated;
revoke all on function public.tally_blob_get(text, text, text)           from public, anon, authenticated;
revoke all on function public.tally_ping()                               from public, anon, authenticated;
grant execute on function public.tally_family_create(text, text)         to anon, authenticated;
grant execute on function public.tally_push(text, text, jsonb)           to anon, authenticated;
grant execute on function public.tally_pull(text, text, bigint, integer) to anon, authenticated;
grant execute on function public.tally_blob_put(text, text, text, text)  to anon, authenticated;
grant execute on function public.tally_blob_get(text, text, text)        to anon, authenticated;
grant execute on function public.tally_ping()                            to anon, authenticated;
