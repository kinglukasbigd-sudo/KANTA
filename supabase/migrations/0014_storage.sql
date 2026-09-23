-- =============================================================================
-- Kanta — 0014 storage
-- KANTA_SPEC.md §6: bucket `photos`, public read, authenticated write,
-- max 1 MB, image/jpeg only. Paths:
--   reports/{user_id}/{uuid}.jpg
--   containers/{user_id}/{uuid}.jpg          (§4.6)
--   container_requests/{user_id}/{uuid}.jpg  (§4.6)
-- =============================================================================
set search_path to public, extensions;

insert into storage.buckets (id, name, public, file_size_limit, allowed_mime_types)
values ('photos', 'photos', true, 1048576, array['image/jpeg'])
on conflict (id) do update
    set public             = excluded.public,
        file_size_limit    = excluded.file_size_limit,
        allowed_mime_types = excluded.allowed_mime_types;

-- Public read: photos are the evidence the whole app is built on (§4.1 photo
-- timeline). They carry no EXIF and have faces blurred before upload (§2, §8).
drop policy if exists photos_public_read on storage.objects;
create policy photos_public_read on storage.objects
    for select to anon, authenticated
    using (bucket_id = 'photos');

-- Write only into your own folder, under one of the three known prefixes, and
-- only .jpg. The bucket enforces the 1 MB limit and the jpeg mime type; this
-- policy enforces WHERE a user may write.
drop policy if exists photos_insert_own_folder on storage.objects;
create policy photos_insert_own_folder on storage.objects
    for insert to authenticated
    with check (
        bucket_id = 'photos'
        and (storage.foldername(name))[1] in ('reports', 'containers', 'container_requests')
        and (storage.foldername(name))[2] = auth.uid()::text
        and lower(right(name, 4)) = '.jpg'
    );

-- A user may replace or remove their own uploads (retry after a failed send),
-- but never anyone else's.
drop policy if exists photos_update_own on storage.objects;
create policy photos_update_own on storage.objects
    for update to authenticated
    using (
        bucket_id = 'photos'
        and (storage.foldername(name))[2] = auth.uid()::text
    )
    with check (
        bucket_id = 'photos'
        and (storage.foldername(name))[2] = auth.uid()::text
    );

drop policy if exists photos_delete_own on storage.objects;
create policy photos_delete_own on storage.objects
    for delete to authenticated
    using (
        bucket_id = 'photos'
        and (storage.foldername(name))[2] = auth.uid()::text
    );
