-- Files pass through the relay only in transit (docs/DESIGN.md, "Files").
--
-- The relay runs on a free tier with a 500 MB database, and file bytes used to stay in tally_blob for ever. From now on a
-- sealed file is kept only until every member it is owed to has fetched it, and never longer than 30 days:
--   - tally_blob_put records who is owed the file: the members who asked for it (tally_file_ask) when someone did, otherwise
--     every member who is active at that moment except the uploader. Members who join later are not owed old files. A file
--     nobody is owed is not kept at all (a family of one stores nothing).
--   - tally_blob_ack: a member's phone has stored the bytes and checked them against the record's SHA-256; it is no longer
--     owed the file. When nobody is owed it any more, the bytes are deleted.
--   - A member who is removed or leaves is owed nothing any more (a trigger on tally_member). A member who came back on a
--     new phone keeps the same member id and status, so what it was owed is still owed (the new phone fetches it).
--   - Anything not collected within 30 days is deleted anyway (by the family's next file call and by the daily tally_ping).
--   - tally_file_ask: a member whose phone lacks a file's bytes asks for it. If the bytes are still on the relay the member
--     is simply added to those owed ('ready'); otherwise the ask waits (at most 30 days) and tally_files shows it to every
--     other active member, so a phone that holds the bytes uploads them again (owed then to the askers only).
--   - tally_files: for one member, the files it is owed that are here now, and the files other members asked for.
--   - tally_relay_usage: no credentials; the database's total size against the free tier's 500 MB and the total file bytes
--     held, for the daily GitHub check. Nothing about any family.
-- The relay still sees only ids (the file ids were already visible as record ids of kind 'file'), member ids, sizes and
-- times; an ask carries only the file id and the asking member, authenticated by that member's credential like every call.
--
-- Limits: at most 50 MiB of file bytes in flight per family (was 100 MiB held), each file at most 6 MiB as before, at most
-- 500 open asks per family.
--
-- What this migration deletes: every row of tally_blob as it stands (sealed test files; the table has no timestamps, so
-- their age cannot be told, and the owner approved clearing them; on 2026-11-12 there were none). blob_bytes is reset to 0.

-- ---------------------------------------------------------------- clearing the old blobs

delete from public.tally_blob;
update public.tally_family set blob_bytes = 0 where blob_bytes <> 0;

-- ---------------------------------------------------------------- tables

alter table public.tally_blob
  add column uploader   text,
  add column created_at timestamptz not null default now(),
  add column expires_at timestamptz not null default now() + interval '30 days';
create index tally_blob_expires_at on public.tally_blob (expires_at);

-- Who still needs a file: one row per (file, member). Gone with the file, or with the member.
create table public.tally_blob_owed (
  family_id  text not null,
  blob_id    text not null,
  member_id  text not null,
  primary key (family_id, blob_id, member_id),
  foreign key (family_id, blob_id) references public.tally_blob (family_id, id) on delete cascade,
  foreign key (family_id, member_id) references public.tally_member (family_id, id) on delete cascade
);
create index tally_blob_owed_member on public.tally_blob_owed (family_id, member_id);

-- "Ask for this file": a member whose phone lacks the bytes of a file the relay no longer holds.
create table public.tally_file_ask (
  family_id  text not null,
  blob_id    text not null,
  member_id  text not null,
  created_at timestamptz not null default now(),
  primary key (family_id, blob_id, member_id),
  foreign key (family_id, member_id) references public.tally_member (family_id, id) on delete cascade,
  constraint tally_file_ask_id_format check (blob_id ~ '^[A-Za-z0-9_-]{1,64}$')
);
create index tally_file_ask_member on public.tally_file_ask (family_id, member_id);
create index tally_file_ask_created_at on public.tally_file_ask (created_at);

alter table public.tally_blob_owed enable row level security;
alter table public.tally_file_ask  enable row level security;
revoke all on table public.tally_blob_owed, public.tally_file_ask from public, anon, authenticated;

-- ---------------------------------------------------------------- private helpers

-- Deletes a family's files that nobody is owed or that expired, and asks older than 30 days, then recounts blob_bytes.
-- Callers hold the family lock.
create function tally_private.tally_blob_sweep(p_family text)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
  delete from public.tally_blob b
    where b.family_id = p_family
      and (b.expires_at <= now()
           or not exists (select 1 from public.tally_blob_owed o where o.family_id = b.family_id and o.blob_id = b.id));
  delete from public.tally_file_ask a where a.family_id = p_family and a.created_at <= now() - interval '30 days';
  update public.tally_family f
    set blob_bytes = coalesce((select sum(length(b.data)) from public.tally_blob b where b.family_id = p_family), 0)
    where f.id = p_family;
end;
$$;

-- A member who stops being active (removed, left) is owed nothing and asks for nothing any more.
create function tally_private.tally_member_gone()
returns trigger
language plpgsql
volatile
security definer
set search_path = ''
as $$
begin
  if old.status = 'active' and new.status <> 'active' then
    delete from public.tally_blob_owed o where o.family_id = new.family_id and o.member_id = new.id;
    delete from public.tally_file_ask a where a.family_id = new.family_id and a.member_id = new.id;
    perform tally_private.tally_blob_sweep(new.family_id);
  end if;
  return null;
end;
$$;

create trigger tally_member_gone
  after update of status on public.tally_member
  for each row execute function tally_private.tally_member_gone();

revoke all on function tally_private.tally_blob_sweep(text) from public, anon, authenticated;
revoke all on function tally_private.tally_member_gone() from public, anon, authenticated;

-- ---------------------------------------------------------------- files

-- Stores a sealed file (at most 6 MiB of base64) for 30 days and records who is owed it: the members who asked for it,
-- when any did; otherwise, for a file the relay does not hold yet, every other member active now. A file already held
-- keeps its owed list (plus any askers) and gets 30 more days. A file nobody is owed is not kept. At most 50 MiB of files
-- in flight per family (TA003). Same epoch rule as tally_push. Same signature as before, so its grants stay.
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

-- A sealed file that is still here (any active member may fetch it; only those owed it hold it here), or null.
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
  return d;
end;
$$;

-- The caller's phone has stored the file and checked it: it is no longer owed (nor asking for) it. The file is deleted
-- once nobody is owed it. Acknowledging a file one is not owed, or one already gone, is fine.
create function public.tally_blob_ack(p_family text, p_member text, p_secret text, p_id text)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  me public.tally_member;
begin
  if p_id is null or p_id !~ '^[A-Za-z0-9_-]{1,64}$' then
    raise exception 'tally: bad id' using errcode = '22023';
  end if;
  perform tally_private.tally_lock(p_family);
  me := tally_private.tally_member_auth(p_family, p_member, p_secret);
  delete from public.tally_blob_owed o where o.family_id = p_family and o.blob_id = p_id and o.member_id = me.id;
  delete from public.tally_file_ask a where a.family_id = p_family and a.blob_id = p_id and a.member_id = me.id;
  perform tally_private.tally_blob_sweep(p_family);
end;
$$;

-- "Ask for this file". p_id must be a file record of this family (TA004 otherwise). If the bytes are still here the caller
-- is added to those owed them: 'ready' (fetch now). Otherwise the ask is kept (renewed if made again) for holders to see:
-- 'asked'. At most 500 open asks per family (TA003).
create function public.tally_file_ask(p_family text, p_member text, p_secret text, p_id text)
returns text
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  me public.tally_member;
begin
  if p_id is null or p_id !~ '^[A-Za-z0-9_-]{1,64}$' then
    raise exception 'tally: bad id' using errcode = '22023';
  end if;
  perform tally_private.tally_lock(p_family);
  me := tally_private.tally_member_auth(p_family, p_member, p_secret);
  if not exists (select 1 from public.tally_record r where r.family_id = p_family and r.id = p_id and r.kind = 'file') then
    raise exception 'tally: no such file' using errcode = 'TA004';
  end if;
  perform tally_private.tally_blob_sweep(p_family);
  if exists (select 1 from public.tally_blob b where b.family_id = p_family and b.id = p_id) then
    insert into public.tally_blob_owed (family_id, blob_id, member_id) values (p_family, p_id, me.id)
      on conflict on constraint tally_blob_owed_pkey do nothing;
    return 'ready';
  end if;
  if not exists (select 1 from public.tally_file_ask a where a.family_id = p_family and a.blob_id = p_id and a.member_id = me.id)
     and (select count(*) from public.tally_file_ask a where a.family_id = p_family) >= 500 then
    raise exception 'tally: too many files asked for' using errcode = 'TA003';
  end if;
  insert into public.tally_file_ask (family_id, blob_id, member_id) values (p_family, p_id, me.id)
    on conflict on constraint tally_file_ask_pkey do update set created_at = now();
  return 'asked';
end;
$$;

-- For the caller: { owed: [file ids here that it is owed, oldest first], asks: [file ids other active members asked for] },
-- at most 500 of each. Expired files and old asks are swept first.
create function public.tally_files(p_family text, p_member text, p_secret text)
returns jsonb
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  me public.tally_member;
begin
  perform tally_private.tally_lock(p_family);
  me := tally_private.tally_member_auth(p_family, p_member, p_secret);
  perform tally_private.tally_blob_sweep(p_family);
  return jsonb_build_object(
    'owed', coalesce((
      select jsonb_agg(x.id order by x.created_at, x.id) from (
        select b.id, b.created_at from public.tally_blob b
          join public.tally_blob_owed o on o.family_id = b.family_id and o.blob_id = b.id
        where b.family_id = p_family and o.member_id = me.id
        order by b.created_at, b.id limit 500) x), '[]'::jsonb),
    'asks', coalesce((
      select jsonb_agg(x.blob_id order by x.blob_id) from (
        select distinct a.blob_id from public.tally_file_ask a
          join public.tally_member m on m.family_id = a.family_id and m.id = a.member_id
        where a.family_id = p_family and a.member_id <> me.id and m.status = 'active'
        order by a.blob_id limit 500) x), '[]'::jsonb)
  );
end;
$$;

-- ---------------------------------------------------------------- the storage check (no credentials)

-- For the daily GitHub check: the whole database's size against the free tier's 500 MB, and the file bytes held across
-- the relay. Totals only: nothing about any family.
create function public.tally_relay_usage()
returns jsonb
language sql
stable
security definer
set search_path = ''
as $$
  select jsonb_build_object(
    'db_bytes', s.size,
    'limit_bytes', 500 * 1024 * 1024,
    'percent', round(100.0 * s.size / (500 * 1024 * 1024), 1),
    'blob_bytes', (select coalesce(sum(f.blob_bytes), 0) from public.tally_family f)
  )
  from (select pg_catalog.pg_database_size(pg_catalog.current_database()) as size) s;
$$;

-- ---------------------------------------------------------------- keep-awake, now also sweeping files

-- As before (expired requests and invites, returns 1), plus: files past their 30 days and asks older than 30 days go.
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
  delete from public.tally_request r where r.expires_at < now();
  delete from public.tally_invite i where i.expires_at < now()
    and not exists (select 1 from public.tally_request r where r.family_id = i.family_id and r.invite_id = i.id);
  for fam in select distinct b.family_id from public.tally_blob b where b.expires_at <= now() loop
    perform tally_private.tally_lock(fam);
    perform tally_private.tally_blob_sweep(fam);
  end loop;
  delete from public.tally_file_ask a where a.created_at <= now() - interval '30 days';
  perform 1 from public.tally_family limit 1;
  return 1;
end;
$$;

-- ---------------------------------------------------------------- grants

revoke all on function public.tally_blob_ack(text, text, text, text)  from public, anon, authenticated;
revoke all on function public.tally_file_ask(text, text, text, text)  from public, anon, authenticated;
revoke all on function public.tally_files(text, text, text)           from public, anon, authenticated;
revoke all on function public.tally_relay_usage()                     from public, anon, authenticated;
grant execute on function public.tally_blob_ack(text, text, text, text) to anon, authenticated;
grant execute on function public.tally_file_ask(text, text, text, text) to anon, authenticated;
grant execute on function public.tally_files(text, text, text)          to anon, authenticated;
grant execute on function public.tally_relay_usage()                    to anon, authenticated;
