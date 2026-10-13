# Large migrations

Status: options 1 and 3 implemented (after 0.6.0); 2 and 4 recorded
for later.

## Problem

sql-file runs each schema script at startup, as a list of statements.
For a small database that's fine. For a large one:

- A single `UPDATE` or `DELETE` over many rows is one large
  transaction: memory, `.log` growth, and locks held throughout.
- If it fails or the process dies partway through, the work is lost,
  and statement tracking can only skip statements that finished.
- `open-local` installs before the application serves anything, so
  migration time is downtime, and long migrations arrive as surprise
  startup delays.
- Nothing reports progress while a long statement runs.

The aim is to handle these without making small-database use any more
complicated: scripts that don't use the new features behave exactly as
before, with the same digests.

## Options

### 1. Batched statements: `-- sql-file: repeat` (implemented)

A statement directive marks a statement to run repeatedly, each pass
its own transaction, until it changes no rows. The statement does its
own batching with `LIMIT`. Scripts containing one are always
statement-tracked and always resumable, including in production. See
the README, "Large Updates in Batches".

HSQLDB 2.7.4 findings: `UPDATE ... LIMIT n` and `DELETE ... LIMIT n`
work; `ORDER BY` isn't allowed directly on `UPDATE` (use an `IN`
subquery or `MERGE` with `ORDER BY ... LIMIT`); `LIMIT 0` means no
limit. Without an index on the `WHERE` condition, each pass rescans
the rows already done, making a large backfill roughly quadratic.

### 2. Clojure migration steps (later)

A directive naming a function, e.g.
`-- sql-file: call my.app.migrate/backfill-priority`, called with the
connection. For work SQL handles badly: stepping through by key range
(which avoids option 1's rescanning), transforming data, calling out to
something else, detailed progress.

Open questions:

- The function must be safe to re-run; sql-file can't check that.
- The digest covers the function's name, not its code, so changes to
  the function aren't detected.
- Lifespan: every fresh database (including every in-memory test
  database) runs every migration, so the function would have to be
  kept as long as its script. `replaces` already addresses this: once
  every deployed database is past the migration, a consolidated
  baseline replaces the chain, and the function can be deleted along
  with the old scripts.

### 3. Installing separately from startup (implemented)

`:on-pending-schema :error` makes `open-local` refuse to open a
database with schemas still to install; `migrate` installs them and
shuts the database down; `:backup-before-install` takes a blocking
backup first. See the README, "Installing Schemas Separately from
Startup".

### 4. Expand/contract with background steps (later)

Split a change across releases: an expand script that's quick at
startup (add a nullable column), a backfill that runs in the background
after the application is up, and a later contract script (add
`NOT NULL`, drop the old column) that requires the backfill to have
finished.

sql-file would need a notion of a deferred schema: run on a background
thread after open, with its own status that application code and later
scripts can check. This is the only option that avoids downtime
entirely, but it's by far the most complex, and the application has to
work correctly in both states. Worth doing only for a real
zero-downtime requirement.
