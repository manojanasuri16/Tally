-- "Not an admin" gets its own SQLSTATE, TA006, instead of 42501.
--
-- PostgREST answers 42501 (insufficient_privilege) with HTTP 401, and a client posting with a fixed-length body (the app's
-- HttpURLConnection) is then not given the error body, so it could not tell "not an admin" from "unknown credentials".
-- A code of our own comes back as HTTP 400 with its body. Only the two places that raise it change; their privileges stay
-- as the tally_membership migration set them (create or replace keeps them).

create or replace function tally_private.tally_admin_auth(p_family text, p_member text, p_secret text)
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
    raise exception 'tally: not an admin' using errcode = 'TA006';
  end if;
  return v;
end;
$$;

create or replace function public.tally_invite_cancel(p_family text, p_member text, p_secret text, p_invite text)
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
    raise exception 'tally: not an admin' using errcode = 'TA006';
  end if;
  delete from public.tally_request r where r.family_id = p_family and r.invite_id = p_invite;
  delete from public.tally_invite i where i.family_id = p_family and i.id = p_invite;
end;
$$;

revoke all on function tally_private.tally_admin_auth(text, text, text) from public, anon, authenticated;
