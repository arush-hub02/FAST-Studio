-- Playout moves from 3-second to 5-second segments (Times.SEG_MS): 5 s divides every standard spot length.
-- Existing videos are re-packaged and existing schedules move onto the 5 s grid.

-- MediaService resumes PROCESSING assets on start-up and re-packages them at the new cadence (under a new media key).
update asset set status = 'PROCESSING', status_detail = 'Re-packaging into 5-second segments'
where status = 'READY' and media_key is not null;

-- cue points snap to the nearest segment boundary
update asset set cue_points = coalesce((
    select string_agg(c::text, ',' order by c)
    from (select distinct round(trim(x)::bigint / 5000.0)::bigint * 5000 as c
          from unnest(string_to_array(cue_points, ',')) x where trim(x) <> '') s
    where c > 0), '')
where cue_points <> '';

-- Rounding onto the grid is monotonic, so items that touched still touch and nothing new overlaps.
-- Items shorter than half a segment would collapse to zero length: drop those first.
create function pg_temp.snap5(t timestamptz) returns timestamptz language sql immutable as $$
  select timestamptz '2026-01-01 00:00:00+00'
         + round(extract(epoch from t - timestamptz '2026-01-01 00:00:00+00') / 5) * interval '5 seconds' $$;

delete from schedule_entry where pg_temp.snap5(end_at) <= pg_temp.snap5(start_at);
update schedule_entry set start_at = pg_temp.snap5(start_at), end_at = pg_temp.snap5(end_at),
                          asset_offset_ms = round(asset_offset_ms / 5000.0)::bigint * 5000;
