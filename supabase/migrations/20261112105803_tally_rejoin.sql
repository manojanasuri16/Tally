-- Coming back on a new phone as the same person (docs/DESIGN.md, "Membership" > "Back on a new phone").
--
-- A member who lost or replaced their phone asks to join with a fresh invite, as anyone does, with a flag inside the sealed
-- request ("I was already in this family"); the relay never sees it. An admin's phone checks the request and picks which
-- member this is. tally_member_reclaim then, in one transaction under the family lock:
--   - gives that member the credentials the new phone sent with its request (member id, role and joined date stay);
--   - remembers the old credential's hash in tally_replaced, so a phone still holding it is told 'replaced' (TA007) on every
--     call from then on, instead of a bare 28000, and can leave the family cleanly with its records kept;
--   - drops the member's old key boxes (sealed to the old phone's key) and stores the admin's grant (sealed to the new
--     phone's key, MAC'd with the invite's secret) for the new epoch;
--   - moves the family to a new epoch with boxes for every other active member (tally_new_epoch, as a removal does), which
--     also deletes every open invite and request, this one included.
-- tally_join_check lets the joining phone learn the outcome: which member id it was given (the relay cannot make it take
-- another one: the grant only opens and checks out for the member id the admin sealed it to).
--
-- New error code: TA007 replaced (these credentials belonged to this member, but a new phone took its place).
-- tally_member_auth and tally_state are replaced to raise it; everything else that authenticates goes through
-- tally_member_auth, so it raises TA007 too. The keep-awake tally_ping is not touched.

create table public.tally_replaced (
  family_id     text not null,
  member_id     text not null,
  secret_hash   text not null,                           -- the credential a new phone replaced
  replaced_at   timestamptz not null default now(),
  primary key (family_id, member_id, secret_hash),
  foreign key (family_id, member_id) references public.tally_member (family_id, id) on delete cascade,
  constraint tally_replaced_hash_format check (secret_hash ~ '^[0-9a-f]{64}$')
);

alter table public.tally_replaced enable row level security;
revoke all on table public.tally_replaced from public, anon, authenticated;

-- ---------------------------------------------------------------- authentication, now telling a replaced phone so

create or replace function tally_private.tally_member_auth(p_family text, p_member text, p_secret text)
returns public.tally_member
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  v public.tally_member;
  h text;
begin
  if p_family is null or p_member is null or p_secret is null or length(p_secret) > 256 then
    raise exception 'tally: unauthorised' using errcode = '28000';
  end if;
  h := tally_private.tally_hash(p_secret);
  select * into v from public.tally_member m where m.family_id = p_family and m.id = p_member;
  if not found then
    raise exception 'tally: unauthorised' using errcode = '28000';
  end if;
  if v.secret_hash <> h then
    if exists (select 1 from public.tally_replaced r
               where r.family_id = p_family and r.member_id = p_member and r.secret_hash = h) then
      raise exception 'tally: replaced by a new phone' using errcode = 'TA007';
    end if;
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

-- tally_state as in tally_family_meta, with the same TA007 check inline (it stays read-only). Same signature: grants stay.
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

-- ---------------------------------------------------------------- an admin lets a member back in on a new phone

-- An admin decides an open request (p_invite) by making the asking phone the active member p_target. p_epoch must be the
-- family's epoch + 1; p_boxes holds the new epoch's boxes: one for p_target (the grant, sealed to the new phone's key by
-- the admin's phone), one for the caller (unless the caller is p_target: an admin moving to a new phone themselves), and
-- one for any other active member the admin's phone can seal to. Refused: not an admin (TA006); the request is not open
-- (TA005); p_target is not an active member (TA004); a stale epoch or a box for someone not active (TA002); no box for
-- p_target (22023). The target keeps its id, role, joined date and status; only its credentials change.
create function public.tally_member_reclaim(p_family text, p_member text, p_secret text, p_invite text, p_target text, p_epoch integer, p_boxes jsonb)
returns void
language plpgsql
volatile
security definer
set search_path = ''
as $$
declare
  r public.tally_request;
  t public.tally_member;
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
  if p_boxes is null or jsonb_typeof(p_boxes) <> 'array'
     or not exists (select 1 from jsonb_array_elements(p_boxes) as e(value) where e.value ->> 'member' = p_target) then
    raise exception 'tally: the member needs a box' using errcode = '22023';
  end if;
  -- the old phone's credential is remembered (to say 'replaced') and stops working with this commit
  insert into public.tally_replaced (family_id, member_id, secret_hash)
    values (p_family, p_target, t.secret_hash)
    on conflict on constraint tally_replaced_pkey do nothing;
  delete from public.tally_box b where b.family_id = p_family and b.member_id = p_target;
  update public.tally_member m set secret_hash = r.secret_hash, notice_hash = r.notice_hash, has_epoch = 0
    where m.family_id = p_family and m.id = p_target;
  -- the new epoch: checks p_epoch, stores the boxes (the grant among them), deletes every open invite and request
  perform tally_private.tally_new_epoch(p_family, p_member, p_epoch, p_boxes);
end;
$$;

-- ---------------------------------------------------------------- the joining phone asks how it went

-- The joiner, with the secret whose hash it sent: { status, member }. 'approved' with the member id it now is (its own
-- request id when let in as someone new, or the member an admin picked when it came back on a new phone), 'removed', or
-- 'pending' / 'denied' with its own request id. TA005 when the request is gone (expired, cancelled, or the key was rotated).
create function public.tally_join_check(p_family text, p_invite text, p_member text, p_secret text)
returns jsonb
language plpgsql
stable
security definer
set search_path = ''
as $$
declare
  h text;
  st text;
  who text;
begin
  if p_family is null or p_secret is null or length(p_secret) > 256 then
    raise exception 'tally: invite not usable' using errcode = 'TA005';
  end if;
  h := tally_private.tally_hash(p_secret);
  select m.status, m.id into st, who from public.tally_member m
    where m.family_id = p_family and m.secret_hash = h and m.status in ('active', 'removed')
    limit 1;
  if st = 'active' then return jsonb_build_object('status', 'approved', 'member', who); end if;
  if st = 'removed' then return jsonb_build_object('status', 'removed', 'member', who); end if;
  st := null;
  select r.status into st from public.tally_request r
    where r.family_id = p_family and r.invite_id = p_invite and r.member_id = p_member and r.secret_hash = h and r.expires_at > now();
  if st is null then
    raise exception 'tally: invite not usable' using errcode = 'TA005';
  end if;
  return jsonb_build_object('status', st, 'member', p_member);
end;
$$;

-- ---------------------------------------------------------------- grants

revoke all on function tally_private.tally_member_auth(text, text, text) from public, anon, authenticated;

revoke all on function public.tally_member_reclaim(text, text, text, text, text, integer, jsonb) from public, anon, authenticated;
revoke all on function public.tally_join_check(text, text, text, text)                         from public, anon, authenticated;
grant execute on function public.tally_member_reclaim(text, text, text, text, text, integer, jsonb) to anon, authenticated;
grant execute on function public.tally_join_check(text, text, text, text)                         to anon, authenticated;
