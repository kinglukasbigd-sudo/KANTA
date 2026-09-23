-- =============================================================================
-- Kanta — seed: the 10 municipalities of Skopje
-- KANTA_SPEC.md §5.4 (the ranked list) and §7 (boundaries from OSM
-- admin_level=8, assigned with ST_Contains).
--
-- Names only. `geom` stays NULL here and is filled in the next step from the
-- OSM boundary relations — municipality_at() simply returns NULL until then,
-- which leaves reports unassigned rather than mis-assigned.
--
-- Safe to re-run: names are refreshed, geometry already loaded is preserved.
-- =============================================================================
set search_path to public, extensions;

insert into municipalities (id, name_mk, name_sq, name_en) values
    (1,  'Центар',         'Qendër',          'Centar'),
    (2,  'Карпош',         'Karposh',         'Karpoš'),
    (3,  'Аеродром',       'Aerodrom',        'Aerodrom'),
    (4,  'Гази Баба',      'Gazi Baba',       'Gazi Baba'),
    (5,  'Кисела Вода',    'Kisella Vodë',    'Kisela Voda'),
    (6,  'Чаир',           'Çair',            'Čair'),
    (7,  'Бутел',          'Butel',           'Butel'),
    (8,  'Ѓорче Петров',   'Gjorçe Petrov',   'Gjorče Petrov'),
    (9,  'Сарај',          'Saraj',           'Saraj'),
    (10, 'Шуто Оризари',   'Shuto Orizare',   'Šuto Orizari')
on conflict (id) do update
    set name_mk = excluded.name_mk,
        name_sq = excluded.name_sq,
        name_en = excluded.name_en;

-- Keep SK-00001... in step with anything already imported (§7).
select sync_container_code_seq();
