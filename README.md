# Veloce Backend

Java 21 and Spring Boot API for Veloce's preparation workspaces. PostgreSQL stores accounts, imported roadmaps, timetables, daily work, reports, projects and history.

See the [Frontend README](../Frontend/README.md) for the Flutter application.

## Application overview

Veloce helps people turn a course or career roadmap into a preparation plan they can follow and review each day. A person can maintain separate workspaces for different subjects, such as Python, databases or AI engineering. Each workspace has its own roadmap, preparation dates, study timetable, projects, saved work and progress. The backend supplies the shared API used by the Flutter web and Android applications and stores persistent records in PostgreSQL.

### Workspaces and preparation

A workspace moves through career selection, roadmap selection/import, preparation, timetable preview and final review before activation. Preparation defines the start date, duration, study days, daily study target and timezone. Timetable settings define the available study windows, focus blocks, breaks and reminder preferences. Users can revisit preparation and timetable settings, preview changes and regenerate the remaining plan while retaining completed work. Workspace actions also support pausing, archiving and deletion.

### Roadmap import and timetable planning

Roadmaps can be imported from JSON, XLSX, CSV, Markdown, plain text or a PDF with selectable text, subject to the 10 MB import limit. Users can upload files or paste supported text formats. The importer turns supported content into a topic hierarchy and study activities. Spreadsheet imports support sheet selection, column mapping and structured daily tasks. The review stage exposes missing study estimates and invalid content before confirmation, while saved import choices help with repeated imports.

The planner uses the roadmap effort, preparation period, selected weekdays, study windows and focus/break settings to build a timetable. A preview shows allocated study time, break time and any study effort that cannot fit. It checks the account's other workspace commitments for scheduling conflicts. Users can increase available time or extend preparation before activating a plan that needs more capacity.

### Check-in, focus and daily work

The Today view brings together the selected day's topics, study blocks, breaks, check-in state and work totals. Checking in starts the day's remaining blocks from the check-in time, respects other commitments and preserves completed blocks. If the remaining work cannot fit before midnight, the backend returns an explanation so the user can adjust the timetable.

Attendance, measured focus, reported work and scheduled-session completion are recorded separately. A focus timer measures time spent in a session; a work update records what the person actually learned or produced. Saving reported minutes does not automatically count as measured focus or attendance.

### Daily reports and saved evidence

Structured roadmap days provide six work sections: **Concepts & understanding**, **Basic coding & tests**, **Scenario-based questions**, **Project work**, **Interview practice**, and **Review & next steps**. Tasks come from the imported roadmap. Updates can include a status, actual focused minutes, learning notes, code or solutions, test results, evidence, blockers and next steps, with additional fields where appropriate to the section.

Users can keep drafts before saving work. Daily report submission captures a version of the saved updates and checks that the required work sections have been addressed. Earlier submitted versions remain available when a report is updated. Saving new work after a submission requires another submission for that work to appear in the report. Progress calculations use the latest submitted report for each day without counting earlier versions again.

### Projects, practice and carryover

The project catalog uses projects from the workspace's roadmap. It supports search and status filters and returns totals for all projects, completed projects, pending projects and projects scheduled for today. Each project has separate work sections for requirements and design, implementation, scenarios, testing, delivery and review, so its saved evidence and completion can be reviewed independently.

Practice supports saved sessions, answers and results. Manual practice works with imported material; AI interview practice uses enabled provider/model settings and the latest reported work.

Carryover lets users move unfinished work to a later study date with a reason and track it until completion. Study leave records a period within a scheduled study day and reduces the available focus time. Unfinished tasks can then be carried forward. Leave records and carryover updates retain revision history.

### Progress, history and preparation guides

Progress brings together planned study, measured focus, reported study, attendance, completed days, phase progress, project status and weekly review totals. These figures keep their separate meanings so a submitted report, timer session and check-in do not substitute for one another.

History covers saved work updates, report versions and other study records from the workspace's preparation start date through today, including records across roadmap versions. Users can filter the history and inspect individual saved revisions. Study exports provide JSON, CSV and XLSX views; supported JSON backups can be restored for review as drafts without replacing submitted history.

Day preparation guides use the selected day's imported topics and study budget. **Build guide** prepares a chart from roadmap material, **AI Enhance** can use enabled eligible free-model providers, and **More concepts** requests another preview. **Keep** stores the selected guide for that day. Provider selection supports fallback when an eligible service or model fails or reaches a limit.

### Accounts, calendar and data ownership

Veloce supports email/password accounts and Google/Facebook sign-in, including linking a provider to an existing account. Workspace access and updates are checked against the signed-in owner. Version checks and mutation identifiers protect saved work against stale edits and repeated requests.

Google Calendar connection requires separate authorization from Google sign-in. Once connected, background workers synchronize Veloce's schedule to its own secondary calendar. Calendar file export is available for users who prefer importing a schedule themselves. Provider credentials saved through workspace settings are encrypted and excluded from study exports.

## Typical study workflow

1. Create a workspace for a subject or career goal.
2. Import and review its roadmap, including study estimates and projects.
3. Choose preparation dates, study days, daily hours and timetable windows.
4. Preview capacity, resolve scheduling conflicts and activate the plan.
5. Check in from Today, follow the study blocks and save work in the daily report sections.
6. Submit the day's report, update project evidence and carry unfinished work to a later day when needed.
7. Review Progress and History, adjust the remaining timetable and use saved preparation guides for upcoming topics.

## Requirements

- **JDK 21**. Local verification used Amazon Corretto 21.
- **PostgreSQL**, with its command-line tools available for database setup. Local verification used PostgreSQL 18.
- Internet access for the Maven wrapper's first dependency download and enabled external integrations.

Keep `mvnw`, `mvnw.cmd` and `.mvn/`. A separate Maven installation or backend startup script is not required.

Run the following commands from the backend folder:

```powershell
Set-Location -LiteralPath 'D:/Project Worksapce/New folder/Veloce App/Backend'
java -version
./mvnw.cmd --version
```

## Start locally

1. Start PostgreSQL.
2. Check the saved database and JWT settings in `src/main/resources/application.properties`.
3. If this is a new database, complete the database setup below.
4. Start the API and leave the terminal open:

```powershell
./mvnw.cmd spring-boot:run
```

In another terminal, check the service:

```powershell
Invoke-RestMethod 'http://127.0.0.1:8090/actuator/health'
```

Expected result: `status: UP`.

| Local address | Purpose |
| --- | --- |
| `http://127.0.0.1:8090/api/v1` | API base |
| `http://127.0.0.1:8090/actuator/health` | Health check |
| `http://127.0.0.1:8090/docs` | API documentation |

Press **Ctrl+C** in the server terminal to stop that process. In IntelliJ, use the shared **Veloce Local** configuration in `.run/Veloce Local.run.xml`. The main class is `com.veloce.Veloce`; use Java 21 and the `local` profile.

## Database setup

### Fresh database

If PostgreSQL commands are not on PATH, use the executables under your PostgreSQL installation's `bin` directory. The following example uses database `veloce` and user `veloce_user`; substitute the names used by your installation:

```powershell
createuser -h 127.0.0.1 -U postgres --pwprompt veloce_user
createdb -h 127.0.0.1 -U postgres --owner=veloce_user veloce
psql -h 127.0.0.1 -U veloce_user -d veloce --single-transaction -v ON_ERROR_STOP=1 -f './src/main/resources/schema.sql'
```

Use the password chosen for `veloce_user` in the private configuration. The current `schema.sql` includes the latest schema; a fresh database does not need older migrations applied afterward.

### Existing database

Back up the database, inspect installed versions, and apply only missing migrations in numeric order:

```powershell
psql -h 127.0.0.1 -U veloce_user -d veloce -c 'SELECT version, installed_at FROM schema_versions ORDER BY version;'
```

| File in `src/main/resources/migrations/` | Adds |
| --- | --- |
| `002-workspace-device-cleanup.sql` | Device cleanup records |
| `003-study-workflow.sql` | Work, projects, practice, drafts and reports |
| `004-calendar-preparation.sql` | Google Calendar connections and saved preparation guides |
| `005-workbook-study-blocks.sql` | Workbook labels for scheduled blocks |
| `006-social-sign-in.sql` | Google/Facebook account connections |
| `007-daily-report-carryover.sql` | Versioned study leave |

Apply an individual missing file with `psql -v ON_ERROR_STOP=1 -f <migration-file>`. Migration 007 also runs once automatically through `StudyMigrations`; earlier migrations remain explicit. The automatic migration expects the earlier schema to exist.

## Authentication and integrations

### Google sign-in

Configure `veloce.auth.google-web-client-id` and `veloce.auth.google-client-ids`, or their mapped `VELOCE_AUTH_GOOGLE_*` values.

Register the frontend origin, normally `http://localhost:8091`, for the web client. Android registration must match application ID **`com.veloce.app`** and the signing certificate used by the build. The backend validates provider tokens before issuing a Veloce session.

### Facebook sign-in

Configure `veloce.auth.facebook-app-id`, `veloce.auth.facebook-app-secret` and `veloce.auth.facebook-graph-version`.

Match the registered web and Android platforms, including the Android key hash. The application requests `public_profile` and `email`. Android also needs its app ID and client token at build time; see the frontend guide. The Facebook app secret stays on the backend.

Use **Profile → Sign-in methods** to link a provider to an existing account and retain its workspaces.

### Google Calendar

Calendar authorization is separate from Google sign-in. Configure:

- `veloce.google.client-id`
- `veloce.google.client-secret`
- `veloce.google.redirect-uri`

The default local callback is:

```text
http://localhost:8090/api/v1/google-calendar/callback
```

Enable the Calendar API for the provider project, register the callback exactly, and connect through workspace Settings. Veloce requests `calendar.app.created` for its own secondary calendar. Synchronization needs enabled workers and the user's consent.

Calendar file export is also available at `/api/v1/workspaces/{workspaceId}/calendar.ics` without Google authorization.

### AI Enhance and practice

Save AI keys through **workspace Settings → AI Enhance providers**. They are encrypted in PostgreSQL per workspace and excluded from study exports.

This implementation supports eligible free-model routes for OpenRouter, NVIDIA, Gemini, Zhipu/Z.ai and Groq. Hugging Face and Mistral slots are present but excluded from the free-only pool because the application has no verified free generation route for them. Providers requiring a free-tier declaration must be confirmed in Settings.

Automatic selection/fallback can try another eligible model after failures or rate limits. OpenRouter requests include zero-price routing limits. Model availability and provider quotas still apply.

**More concepts** creates a preview; **Keep** persists it. **Build guide** and manual practice work from imported material without AI keys. AI interview practice has its own provider/model settings and requires a report covering the latest saved work.

## API conventions

Use `/docs` for complete request/response schemas.

| Area | Route prefix |
| --- | --- |
| Authentication | `/api/v1/auth` |
| Workspace lifecycle and onboarding | `/api/v1/workspaces` |
| Roadmap upload | `/api/v1/workspaces/{w}/roadmap-sources` |
| Initial timetable preview | `/api/v1/workspaces/{w}/plan-previews` |
| Active timetable changes | `/api/v1/workspaces/{w}/reschedule-previews` |
| Timetable activation jobs | `/api/v1/workspaces/{w}/generation-jobs` |
| Focus and completion | `/api/v1/workspaces/{w}/sessions/{s}` |
| Work, reports, projects, carryover, history and AI | `/api/v1/workspaces/{w}/study` |
| Google Calendar | `/api/v1/google-calendar` |

- Native clients send `Authorization: Bearer <access-token>` and `X-Veloce-Client: native`.
- Browser requests use the web flow with credentials and HTTP-only refresh cookies.
- Versioned saves require the latest `expectedRevision` or `expectedVersion`; mutation IDs/idempotency keys prevent duplicate actions.
- Study dates use `YYYY-MM-DD` in the workspace's IANA timezone. Schedule/export ranges include `from` and exclude `to`.
- Check-in starts today's remaining blocks immediately. Completed blocks are preserved and count toward the day's study budget.
- Reported work, measured focus, attendance and scheduled-session completion are separate measurements.

## Build and test

```powershell
./mvnw.cmd test
./mvnw.cmd package
```

The package command runs tests and creates **`target/veloce-api-0.1.0.jar`**. Stop any process using that JAR before replacing it on Windows.

Run the packaged backend with the project's local memory limits:

```powershell
java -Xms32m -Xmx384m -XX:+UseSerialGC -XX:ReservedCodeCacheSize=64m -XX:TieredStopAtLevel=1 -XX:CICompilerCount=2 -jar 'target/veloce-api-0.1.0.jar'
```

Append a Spring argument to change a setting, for example `--server.port=18090`. Update the frontend API address if the backend port changes.

### PostgreSQL integration tests

The normal command skips database tests unless their fixture environment is configured. Most database tests require the exact disposable database name **`veloce_history_verify_261115`**. Initialize it separately with `schema.sql`, then set its credentials in the test terminal:

```powershell
$env:VELOCE_UI_TEST_DB_URL = 'jdbc:postgresql://127.0.0.1:5432/veloce_history_verify_261115'
$env:VELOCE_UI_TEST_DB_USER = '<test-database-user>'
$env:VELOCE_UI_TEST_DB_PASSWORD = '<test-database-password>'
./mvnw.cmd test
```

Never point fixture tests at the application database. `StudyViewPostgresTest` requires a separate `veloce_design_verify_261115` database with specifically seeded rows matching its assertions; changing the URL alone does not create the fixture.

On **21 November 2026**, all 89 distinct backend tests were exercised across their required fixtures. Live Google/Facebook consent, actual Calendar synchronization and real cloud AI calls were outside that isolated verification.

## Project structure

| Location | Responsibility |
| --- | --- |
| `src/main/java/com/veloce/auth/` | Accounts, JWT sessions and social token verification |
| `src/main/java/com/veloce/workspace/` | Ownership, settings and lifecycle |
| `src/main/java/com/veloce/roadmap/` | Parsing, validation, import reuse and workbook timetables |
| `src/main/java/com/veloce/planning/` | Capacity, conflicts, previews and generation |
| `src/main/java/com/veloce/learning/` | Sessions, focus and Calendar export |
| `src/main/java/com/veloce/study/` | Reports, projects, carryover, leave, history and AI |
| `src/main/java/com/veloce/integration/` | Calendar and device integrations |
| `src/main/resources/schema.sql` | Fresh schema |
| `src/main/resources/contracts/roadmap-v1.schema.json` | Required runtime roadmap schema |
| `src/test/` | Unit tests, database checks and fixtures |

## Troubleshooting

| Symptom | Check |
| --- | --- |
| Database connection/authentication failure | Start PostgreSQL and verify JDBC URL, user and password. |
| Missing table / startup migration failure | Initialize a fresh schema or apply missing earlier migrations. |
| Unresolved `VELOCE_JWT_SECRET` | Restore the saved secret/configuration and rebuild; preserve the existing key. |
| Import or generation remains queued | Confirm `veloce.jobs-enabled=true` and inspect server logs. |
| Browser CORS or refresh-cookie failure | Match origin, loopback profile and cookie mode; local HTTP needs `veloce.secure-cookie=false`. |
| Windows error 1455 / insufficient JVM memory | Use Java 21 and the existing heap limits; close unused applications and check Windows virtual memory. |
| JAR rename failure during package | Stop the process using that JAR and build again. |
| Provider/Calendar connection unavailable | Check provider registration and the matching configuration/callback. |

Maven has a 256 MB heap limit in `.mvn/jvm.config`; the Spring Boot runner uses 384 MB. Raising heaps while Windows lacks committed memory can worsen startup failures.
