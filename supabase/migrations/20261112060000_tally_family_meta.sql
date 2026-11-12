-- The family's name can be changed by an admin after the family is made.
--
-- Until now each phone kept the name it was given at create or in the invite. The name now also lives on the relay as
-- tally_family.meta: a blob sealed by the admin's phone under the family's newest key (AAD "tally-meta:<family>", the
-- same AES-GCM epoch format as records), so the relay never sees the name. Only an admin can set it (TA006 otherwise),
-- only under the newest epoch (TA002), and tally_state hands it to every active member, who keeps it when its version
-- counter is not older than the one it holds (a replayed older blob is ignored on the phone).

alter table public.tally_family add column meta text;
alter table public.tally_family add constraint tally_family_meta_size check (meta is null or length(meta) between 1 and 4096);

-- An admin stores the family's sealed name. p_epoch is the epoch it was sealed with and must be the family's newest.
create function public.tally_family_meta_set(p_family text, p_member text, p_secret text, p_epoch integer, p_meta text)
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
  if p_epoch is null or p_epoch <> f.epoch then
    raise exception 'tally: stale epoch' using errcode = 'TA002';
  end if;
  if p_meta is null or length(p_meta) not between 1 and 4096 then
    raise exception 'tally: bad meta' using errcode = '22023';
  end if;
  update public.tally_family x set meta = p_meta where x.id = p_family;
end;
$$;

-- tally_state as before, plus 'meta' (null until an admin renames the family). Same signature, so its grants stay.
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

revoke all on function public.tally_family_meta_set(text, text, text, integer, text) from public, anon, authenticated;
grant execute on function public.tally_family_meta_set(text, text, text, integer, text) to anon, authenticated;
