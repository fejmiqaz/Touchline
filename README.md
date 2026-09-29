# Touchline — football attendance

Spring Boot 4.1.1 / Java 17 application for a coach to manage football generations, players, training sessions, and attendance. The UI uses Thymeleaf and responsive CSS. Google OpenID Connect handles sign-in; logout is a CSRF-protected POST. All signed-in accounts have only `ROLE_COACH`. Players have no accounts or roles. Each coach can access only their own records.

## Run locally

Install Java 17 or later and create a Google OAuth **Web application** client. Configure this authorized redirect URI:

```text
http://localhost:8080/login/oauth2/code/google
```

In PowerShell:

```powershell
$env:GOOGLE_CLIENT_ID = 'your-client-id'
$env:GOOGLE_CLIENT_SECRET = 'your-client-secret'
$env:allowed_coach_emails = 'coach1@gmail.com,coach2@gmail.com'
.\mvnw.cmd spring-boot:run
```

Open <http://localhost:8080>. Without Google credentials, the login page explains the missing configuration. There is no development login bypass. If Google consent is in testing mode, add your coach account to its test users. Google OAuth client setup is external to this repository; real sign-in needs your credentials.

By default, data persists in `data/attendance.mv.db` using H2. Tests use an independent in-memory database. For PostgreSQL set `DATABASE_URL` (a JDBC URL such as `jdbc:postgresql://localhost:5432/attendance`), `DATABASE_USERNAME`, and `DATABASE_PASSWORD`. Flyway applies migrations and Hibernate validates the schema. V1 preserves the original school schema; V2 introduces the football model without deleting any prior data. The old school tables are unused by the football application.

The calendar uses `APP_TIME_ZONE`, defaulting to `Europe/Skopje`. In an HTTPS deployment, configure the production Google redirect URI and set `SERVER_SERVLET_SESSION_COOKIE_SECURE=true`. Keep credentials outside source control.

## Coach workflow

### Restrict coach access

Set the environment variable **`allowed_coach_emails`** to a comma-separated list of approved Google email addresses. In IntelliJ, add it under Run → Edit Configurations → Environment variables. In Render, add the same key under the service's Environment settings. Restart/redeploy after changing it.

Only verified Google emails matching that list can log in. Matching ignores capitalization and surrounding spaces; it does not treat Gmail aliases or different addresses as equivalent. Missing or empty configuration denies every login. List exactly two addresses to allow two accounts; no addresses are hardcoded. Existing data remains linked to Google's stable account subject. Players still have no login access.

Example: `allowed_coach_emails=coach1@gmail.com,coach2@gmail.com`

1. Sign in with Google and create a generation with its name and birth year.
2. Add players with names and unique shirt numbers within that generation.
3. Create tomorrow’s training manually: date, start/end time, pitch, and optional notes. Weekdays appear automatically from the chosen date. Sessions do not recur. Creation is restricted to the day before training, as requested. Overlapping training times are rejected.
4. On the training date, open the session. Click a player’s name/card to select them as present, then save. Unselected players become absent only when attendance is saved. Late recording of past sessions is allowed.
5. Review or correct saved attendance. Changes update existing records. Stale submissions from another tab are rejected. Saved rosters remain fixed when new players join.
6. The notification bar flags players with **more than five absences (six or more)**, counted across **all saved sessions for their generation**. Corrections automatically update notifications. The overview shows total sessions, absences, and rounded absence percentage. There is no season reset.

Future, cancelled, and untaken sessions do not create absences. An unsaved session can be cancelled; recorded sessions retain their attendance history. Empty rosters cannot be submitted. There is no four-year class constraint.

## Editing and deleting records

- **Generations:** Create from the dashboard, open a generation to view its roster, and use **Edit generation** or **Delete generation** below the roster. Deletion also removes its players, sessions, and attendance.
- **Players:** Add from a generation. Click a player's name in any attendance summary to view their individual training history, edit their name/shirt number, or delete them and their attendance records. Their generation and enrollment date stay fixed to preserve the meaning of historical records.
- **Training sessions:** Create from the dashboard and open a session to view, edit, cancel, or delete it. Edits support time, pitch, and notes. Unrecorded sessions can retain their date or move to tomorrow; recorded dates and the generation stay fixed. Deleting a session also deletes its attendance.
- **Attendance sheets:** Save and update presence using the player cards. **Delete attendance sheet** removes the marks and returns the session to pending, allowing attendance to be taken again. It also removes that sheet's contribution to absence totals and alerts.

All delete forms require explicit confirmation. All mutations use CSRF-protected POST requests and check coach ownership. Session edits, attendance changes, and deletions reject stale session revisions. Deletions are permanent once submitted; changes immediately affect totals and notifications. Tests run against a separate in-memory database and do not delete local sample data.

## Package layout

```text
src/main/java/atrck/attendancetracker/
  AttendanceTrackerApplication.java
  model/        Generation, Player, TrainingSession, PlayerAttendance
  repository/   one Spring Data repository per model
  service/      TrackerService: ownership, scheduling, attendance, statistics
  controller/   page/form routes and validation error responses
  security/     Google login, coach authority, application clock
src/main/resources/
  templates/    login, dashboard, generation, training, shared fragments
  static/       responsive styling
  db/migration/ Flyway schema migrations
src/test/       isolated application and workflow tests
```

## Sample data

The local database has been populated with three **Sample Academy** generations for the existing coach account. Sign in with the same Google account to see 36 fictional players, 24 saved training sessions, three sessions for today, three for tomorrow, and three cancelled sessions. Each sample generation includes a player with six absences (an alert), another with five (no alert yet), and players with stronger attendance. Existing records are preserved.

The importer is opt-in and skips sample generations already present, so rerunning it does not duplicate records or reset attendance. For a different coach/database, use that coach's Google OIDC subject (not their email):

```powershell
.\mvnw.cmd spring-boot:run '-Dspring-boot.run.arguments=--app.demo.owner=GOOGLE_SUBJECT --app.demo.exit-after-seeding=true --server.port=0'
```

Stop the regular app first when using the local H2 file. After import, restart normally from IntelliJ with your Google credentials. The importer does not bypass login or run during ordinary startup. Sample dates are relative to the import date and are preserved on subsequent runs.

## Daily email backups

See [database backup setup and restore instructions](docs/database-backups.md) to enable a daily encrypted Neon export emailed through SMTP. The GitHub Actions workflow requires repository secrets and a push to the default branch before it can run. It also retains encrypted artifacts for 30 days. No database credentials are stored in the repository.

## Running tests

```powershell
.\mvnw.cmd test
```

Tests cover the sixth-absence threshold, absence percentages, corrections, roster history, future/cancelled sessions, owner isolation, invalid roster IDs, conflicting schedules, page rendering, CSRF, and logout. Google’s external authorization/consent flow requires a manual check with a configured client.

