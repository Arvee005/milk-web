# Free Hosting Setup (Render + Neon + uptime checker)

Owner decision (2026-10-15): keep hosting free. This guide is the checklist for running Milk Web at no cost
without the two free-tier problems found during the audit.

| Problem | Cause | Fix |
|---|---|---|
| First visit after a break waits about a minute | Render free web services sleep after 15 minutes without traffic | A free uptime checker calls the liveness probe every 5 minutes; faster Java startup for the remaining wake-ups |
| Data would be deleted | Render free PostgreSQL expires 30 days after creation, then is deleted after a 14-day grace period | Move the database to Neon's free plan, which does not expire |

**Never paste database passwords or connection strings into chats, issues, or commits.**

## 1. Keep the web service awake (after the free-hosting code is deployed)

1. Create a free account at UptimeRobot (or cron-job.org).
2. Add an **HTTP(s)** monitor:
   - URL: `https://milk-web.onrender.com/actuator/health/liveness`
   - Interval: **5 minutes**
3. Optional: turn on email alerts; you will hear about real outages too.

Why liveness: it only reports whether the app is running and never touches the database (tested in
`HealthProbeTest`). Calling `/actuator/health` instead would wake the database every few minutes and use up
Neon's free compute hours. One always-on free service uses about 744 of Render's 750 free hours per month, so
keep only one free Render service running.

## 2. Move the database to Neon (before the Render database expires)

Do this at a quiet time when nobody is recording collections or payments.

1. **Create the database.** Sign up at neon.com, create a project (for example `radhika-farms`) in the region
   closest to the Render service's region (Render → service → Settings → Region).
2. **Copy the direct connection details** from Neon (choose the direct connection, not the pooled one): host,
   database name, user, password.
3. **Install the PostgreSQL tools on the Mac** (once): `brew install libpq && brew link --force libpq`
4. **Back up the Render database** (Render → database → Connections → External Database URL):
   ```
   pg_dump --no-owner --no-acl --format=custom "<Render external URL>" -f milkweb-2026.dump
   ```
   Keep this file; it is your backup.
5. **Restore into Neon:**
   ```
   pg_restore --no-owner --no-acl --dbname "<Neon direct connection string>" milkweb-2026.dump
   ```
6. **Check the copy:**
   ```
   psql "<Neon direct connection string>" -c "select count(*) from customers" -c "select string_agg(version, ',' order by installed_rank) from flyway_schema_history"
   ```
   The customer count should match the app, and migrations should read `1,2,3,4`.
7. **Point Render at Neon** (Render → web service → Environment), then save; Render redeploys:
   - `MILKWEB_DATABASE_URL` = `jdbc:postgresql://<neon host>/<database>?sslmode=require`
   - `MILKWEB_DATABASE_USERNAME` = Neon user
   - `MILKWEB_DATABASE_PASSWORD` = Neon password
8. **Check the deploy:** the logs should show `Schema ... is up to date. No migration necessary.`, then sign in
   and open a bill with payments.
9. **Keep the Render database** for a few days as a fallback, then delete it. If anything goes wrong, set the three
   variables back to the Render values.

## What it costs

Nothing, within these free limits: Render 750 instance hours per month for one web service; Neon 0.5 GB storage
and a monthly compute allowance, with the database sleeping when unused. The farm's data is far below 0.5 GB.
Free plans and their limits can change; check Render's and Neon's pricing pages occasionally.
