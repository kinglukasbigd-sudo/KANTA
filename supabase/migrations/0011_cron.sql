-- =============================================================================
-- Kanta — 0011 scheduled jobs
-- KANTA_SPEC.md §5.1: "A scheduled function (pg_cron) expires old reports every hour."
-- =============================================================================
set search_path to public, extensions;

-- Re-running this migration must not stack duplicate jobs.
select cron.unschedule(jobid)
  from cron.job
 where jobname = 'kanta-expire-reports';

select cron.schedule(
    'kanta-expire-reports',
    '0 * * * *',                       -- top of every hour
    $$select public.expire_old_reports()$$
);

-- Inspect with:  select * from cron.job;
--                select * from cron.job_run_details order by start_time desc limit 20;
