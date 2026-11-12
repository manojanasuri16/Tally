-- Drop the version-1 relay tables (tally_families, tally_records, tally_blobs) and their sequence tally_seq.
-- The membership migration (20261112013706) replaced every function that used them and left them in place, holding
-- only test families made by 0.1.0 and 0.2.0, unreachable by any function. The owner approved deleting that test data.
-- Nothing in the current relay reads or writes these objects.

drop table if exists public.tally_blobs;
drop table if exists public.tally_records;
drop table if exists public.tally_families;
drop sequence if exists public.tally_seq;
