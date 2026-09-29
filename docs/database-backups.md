# Daily encrypted email backups

The `Daily database backup` GitHub Actions workflow exports the Neon database, checks its archive structure, encrypts it with GPG AES-256, keeps the encrypted file as a GitHub artifact for 30 days, and sends it as an email attachment. It runs independently of Render. No backup or email is sent merely by adding these files locally.

## Activate

1. Push `.github/workflows/database-backup.yml`, `scripts/database_backup.py`, and `scripts/test_database_backup.py` to your repository's **default branch**.
2. In GitHub, open **Settings → Secrets and variables → Actions → Secrets → New repository secret**. Add:

| Secret | Value |
| --- | --- |
| `BACKUP_DATABASE_HOST` | Neon **direct** hostname only (no `jdbc:`, path, username or password). Turn pooling off in Neon's Connect panel. |
| `DATABASE_USERNAME` | Neon role, for example `neondb_owner`. |
| `DATABASE_PASSWORD` | Neon role password. |
| `BACKUP_PASSPHRASE` | A unique, randomly generated password of at least 20 characters, on one line. Save a separate copy in your password manager: losing it makes every encrypted backup unusable. |
| `SMTP_USERNAME` | Sending email address. |
| `SMTP_PASSWORD` | SMTP credential, such as a Gmail **App Password**, not your regular Google password. |
| `BACKUP_EMAIL_TO` | Recipient email address. |

These are GitHub secrets, separate from Render's environment variables. Never put secrets in a workflow file or a Git commit. Do not email the encryption passphrase with the backup. Keep old passphrases if you rotate them while older backups are retained.

3. Defaults are Gmail SMTP (`smtp.gmail.com`, TLS port `465`) and database `neondb`. For another provider/database, add repository **Variables** named `SMTP_HOST`, `SMTP_PORT` (`465` or `587`), and `BACKUP_DATABASE_NAME`. The SMTP provider must permit the sender address and authenticated SMTP. Gmail App Passwords require two-step verification and may be unavailable for some account policies; use your provider's supported SMTP credentials in that case.
4. Open **Actions → Daily database backup → Run workflow**. This sends a real backup email. Check that the run succeeds, the attachment arrives, and the encrypted artifact is downloadable.
5. Enable GitHub Actions failure notifications in your GitHub notification settings. The workflow also attempts a failure email, but a broken email credential/provider prevents that notification too.

Default schedule: **03:17 UTC daily**, which is **04:17 in Skopje in winter / 05:17 in summer**. Change `cron: '17 3 * * *'` to adjust it. GitHub schedules can be delayed and public repositories can have schedules disabled after 60 days without repository activity. Check run history and missing emails; this is not an exact-time or guaranteed-delivery scheduler.

## What is backed up

The export includes database schema and data: generations, players, training sessions, attendance, and Flyway migration history. It excludes ownership/grants, cluster roles, application environment variables, Google credentials, and source code. Keep the code in GitHub and credentials separately in your password manager.

`postgres:18` supplies the PostgreSQL tools. Check your Neon major version: pg_dump must be at least as new as the server. If Neon uses a newer major, update `POSTGRES_IMAGE` in the workflow. Restore to the same or a compatible newer PostgreSQL version. The workflow does not touch or overwrite production data.

The attachment must be **18 MiB or smaller** to leave room for email encoding. Oversized exports fail the delivery step and attempt a warning email; the encrypted artifact remains downloadable. Smaller attachments can still be rejected by provider limits or spam policies. Switch to private object storage with emailed links if the database outgrows attachments.

## Restore safely

Do a restore drill before relying on these backups. The automated `pg_restore --list` check verifies the archive's table of contents, not a complete restore.

1. Download a `.dump.gpg` attachment/artifact. If downloaded from GitHub, extract the artifact ZIP first.
2. Install GnuPG and PostgreSQL client tools locally. Decrypt; GPG asks for your backup passphrase:

```powershell
gpg --output restored.dump --decrypt .\touchline-TIMESTAMP.dump.gpg
```

3. Create a **new empty test database** in Neon. Set the following locally using that test database's connection details (these `PG*` variables are for PostgreSQL tools, not Spring Boot):

```powershell
$env:PGHOST = 'YOUR_TEST_NEON_HOST'
$env:PGDATABASE = 'restore_test'
$env:PGUSER = 'YOUR_TEST_ROLE'
$env:PGPASSWORD = 'YOUR_TEST_PASSWORD'
$env:PGSSLMODE = 'require'
pg_restore --no-owner --no-acl --exit-on-error --single-transaction --dbname=$env:PGDATABASE .\restored.dump
```

4. Verify table counts and sample player/attendance records. Point a local app instance at this restored database and test Google login, generations, attendance, and absence alerts. Do not seed sample data into the restore test.
5. During a real recovery, pause writes to the app, validate the recovered database, and only then change Render's database environment variables and redeploy. Keep the previous database until recovery has been verified. A restore loses changes made after the exported snapshot unless you reconcile them separately.
6. Remove the decrypted dump when finished and keep the encrypted originals according to your retention policy. Test restoring periodically, especially after schema changes.

An application crash alone does not normally erase Neon data. These backups protect against deletion/data loss, with up to roughly a day's data loss between successful daily exports. Combine with Neon's point-in-time recovery for recovery between exports.

References: [GitHub schedule limitations](https://docs.github.com/en/actions/reference/workflows-and-actions/events-that-trigger-workflows#schedule), [PostgreSQL pg_dump](https://www.postgresql.org/docs/current/app-pgdump.html), [Gmail App Passwords](https://support.google.com/accounts/answer/185833).
