# Plan: schema graph, install tracking, and digests

Status: proposed. This describes the next round of changes to
sql-file. It's meant to be read before the work starts, and kept up to
date as decisions change.

## Goals

1. **String schema ids.** Each schema script is identified by a single
   string rather than a (name, integer version) pair. Any ordering in
   the id (zero-padded numbers, say) is a naming convention for people
   reading a directory listing; sql-file doesn't rely on it.
2. **Declared dependencies.** A script declares the scripts it depends
   on. sql-file reads the scripts it's asked for, follows their
   dependencies, and installs whatever is missing first.
3. **More than one dependency per script.** Installation order is a
   topological sort of the dependency graph.
4. **Install timing.** The start and end time of each script's
   application are recorded.
5. **Content digests.** A digest of each installed script is stored,
   and a changed script is detected on later opens: an error in
   production, a warning in development.
6. **Statement-level tracking** (lower priority). In development, a
   script that fails partway through can be fixed and re-run, with the
   statements that already succeeded skipped.

Out of scope: a real SQL parser (statements are split the way
`sql-file.script/sql-statements` splits them now), and other database
engines.

## Concepts

### Schema ids and script files

A schema id is a string such as `ectcommon/0001-session-key` or
`todo/0003-item-tags`. Its script is the resource `<id>.sql`, looked
up on the connection's `:schema-path`, the same search sql-file does
today. Ids may contain `/` to organize scripts into directories; they
may not contain whitespace. Ids are stored in a `VARCHAR(255)`.

A "chain" is now just a naming convention: a series of scripts sharing
a prefix, each requiring the one before.

### Directives

Directives live in the script's leading comment block, before the
first statement, one per line:

```sql
-- sql-file: requires ectcommon/0001-session-key
-- sql-file: requires todo/0003-item-tags
-- sql-file: replaces toto-16
```

* `requires <id>`: the named schema must be installed before this one.
  A script may have any number of these; their order is significant
  (see Installation order).
* `replaces <id>`: as in 0.5.0, but naming a schema id. The 0.5.0
  two-argument form, `replaces <name> <n>`, is read as
  `replaces <name>-<n>`.

Any other `-- sql-file:` line in the leading block is an error, as
now.

### Legacy (numbered) schemas

Existing scripts named `schema-<name>-<n>.sql` keep working unchanged:

* Their id is `<name>-<n>` (for example `toto-16`).
* An id of the form `<name>-<n>` whose `<id>.sql` isn't found is
  looked up as `schema-<name>-<n>.sql`.
* A legacy script with `n > 0` implicitly requires `<name>-<n-1>`, in
  addition to anything it declares.

A legacy request, the vector `["todo" 3]` in `:schemas`, is read as
the id `"todo-3"`. This is a transition aid for existing applications;
it can be removed in a later release once nothing uses it.

## Database tables

sql-file's own tables replace `sql_file_schema`.

```sql
CREATE CACHED TABLE sql_file_installed (
  schema_id VARCHAR(255) NOT NULL PRIMARY KEY,
  how VARCHAR(16) NOT NULL,        -- 'run', 'recorded', or 'migrated'
  requires VARCHAR(4096) NULL,     -- effective requires, newline separated
  replaces VARCHAR(4096) NULL,     -- newline separated
  digest CHAR(64) NULL,            -- hex SHA-256; NULL until known
  started_on TIMESTAMP NULL,
  completed_on TIMESTAMP NULL      -- NULL while running, or if it failed
);

CREATE CACHED TABLE sql_file_statement (   -- phase 4
  schema_id VARCHAR(255) NOT NULL,
  ordinal INTEGER NOT NULL,
  digest CHAR(64) NOT NULL,
  completed_on TIMESTAMP NOT NULL,
  PRIMARY KEY (schema_id, ordinal)
);
```

* `how` is `run` for a script that was executed, `recorded` for one
  recorded under `replaces` without running, and `migrated` for rows
  carried over from `sql_file_schema` (below).
* Storing each installed schema's `requires` and `replaces` makes the
  installed graph self-describing. sql-file can check it against the
  requested scripts even when old script files have since been deleted.
* For `recorded` and `migrated` rows, `started_on` equals
  `completed_on` (the time they were recorded), or both are NULL for
  migrated rows.

### Bootstrapping sql-file's own tables

sql-file's own schema stays an internal, legacy-style chain, managed
before anything the application asks for:

* `schema-sql-file-0.sql` (existing): creates `sql_file_schema`.
* `schema-sql-file-1.sql` (new): creates `sql_file_installed` and
  `sql_file_statement`.
* After `sql-file-1` is installed, code (not SQL; it needs to expand
  ranges) copies `sql_file_schema`: a row `(name, n)` becomes rows
  `<name>-0` through `<name>-<n>` with `how = 'migrated'`, no times
  and no digest. `sql-file-0` and `sql-file-1` themselves are recorded
  the same way.
* `sql_file_schema` is kept up to date for numbered schemas (sql-file's
  own row stays at 0), so an application can be rolled back to an
  older sql-file without it re-running scripts. It can be dropped by a
  later `sql-file-2` once rolling back past 0.6 is no longer a concern.
* On a new database, `sql-file-0` is still run (creating
  `sql_file_schema`) for the same reason.
* The `sql-file-1` row is written last, so an interrupted migration is
  simply repeated on the next open.

## Installation

### Resolving the request

`open-local`/`open-pool` take `:schemas`, a sequence of ids (strings)
or legacy `[name n]` vectors. These are the "targets", normally the
newest script of each chain the application uses.

1. For each target, locate and read its script, parse its directives,
   and recursively do the same for each `requires`. Scripts already
   recorded as installed are not read for their requires: the stored
   `requires` column is used instead, so deleted old scripts don't
   break resolution.
2. Errors: a missing script (naming the id and the search path), a
   dependency cycle (reporting the cycle, `a -> b -> c -> a`), an
   invalid id, or a bad directive.

### Installation order

Install in dependency order: a depth-first, post-order walk from the
targets, taking targets in the order given and each script's
`requires` in the order declared. Independent branches therefore
install in a predictable, repeatable order.

### Installing one script

For each schema in that order that isn't installed:

1. If the script has `replaces` directives, apply the 0.5.0 rules,
   adapted to ids (see below). If they say to record rather than run,
   insert a `recorded` row and move on.
2. Insert the row with `how = 'run'`, its `requires`, `replaces` and
   `digest`, `started_on = now` and `completed_on = NULL`.
3. Run the statements.
4. Set `completed_on = now`.

If a statement fails, the row stays with `completed_on` NULL. HSQLDB
commits DDL immediately, so the database really is partly changed;
see Incomplete installs.

### `replaces` with ids

A script with `replaces R` (possibly several):

* R is "present" if R, or anything R requires (transitively), is
  installed, excluding what the replacing script itself requires
  (transitively). This generalizes "the old chain has a row": a
  dependency the replacing script shares with R (a common base
  schema, say) is a prerequisite, not part of R. For a numbered R
  (`<name>-<n>`), any installed `<name>-<k>` counts, so this works
  even after the old chain's scripts are deleted.
* None present: run the script normally.
* All present: install R (with whatever it requires that's missing),
  then record the script without running it.
* Some present, some not: error.
* Something installed requires R, directly or transitively (it comes
  after R, so the database has changes the replacing script doesn't
  reproduce): error. With the stored `requires` column this check
  doesn't depend on old script files still existing.
* All of these checks happen before anything is run, which also fixes
  a 0.5.0 rough edge where one replaced chain could be upgraded before
  another failed the "later version" check.

### Incomplete installs

On open, any row with `completed_on` NULL means a script started and
didn't finish. Without statement tracking (phase 4), this is an error
naming the schema: the database is partly changed and needs restoring
(or fixing by hand) before continuing.

### Installed schemas the request doesn't cover

This replaces the "Cannot downgrade" check. Installed schemas that
aren't reachable from the targets (through `requires` and `replaces`)
usually mean old code running against a newer database, for example
after a rollback. That's an error in production and a warning in
development. sql-file's own schemas (`sql-file-*`) are exempt. The
check is made by `open-local` (which knows the whole request) before
anything is installed; `ensure-schema`, which installs a single schema,
doesn't make it.

## Digests

### What's digested

The digest is a SHA-256 over a canonical encoding of what the script
means:

1. each effective `requires`, in order (for a legacy script, including
   the implicit `<name>-<n-1>`),
2. each `replaces`,
3. each statement as `sql-statements` produces it, in order.

Each element is written as a tag (`R`, `P`, or `S`), its length in
UTF-8 bytes, and its bytes, so no two different scripts encode the
same way.

Because `sql-statements` collapses whitespace runs to a single space,
drops `--` comments, and keeps string literals exactly, the digest
ignores comments, blank lines, and re-indentation or re-flowing of a
statement, but not other changes to it. Changing a dependency or a
replacement does change the digest.

### Checking

On each open, for every installed schema with a digest whose script
can still be found, recompute and compare:

* Match: nothing.
* Mismatch: error in production, warning in development, naming the
  schema and the script URL.
* No stored digest (`migrated` rows): store the current digest, logged
  at INFO. The first open after upgrading sql-file adopts whatever the
  scripts say at that point.
* Script no longer found: skipped. Deleting retired scripts is fine.

## Production and development behavior

A new open option, `:development-mode` (default false), sets the
defaults for the behaviors that differ:

| | production | development |
|---|---|---|
| Changed installed script (digest mismatch) | error | warning |
| Installed schema not covered by the request | error | warning |
| Incomplete install | error | resume (phase 4), else error |

Each can be overridden individually
(`:on-schema-change`, `:on-uncovered-schema`, each `:error` or
`:warn`). ectcommon's `start-app` would pass its own
`:development-mode` setting through.

## Statement-level tracking (phase 4)

Enabled with `:track-statements true`, which defaults to the value of
`:development-mode`.

* When enabled, each statement's row in `sql_file_statement` is
  written after it succeeds (digest of the statement text, as above).
  HSQLDB commits DDL immediately, so the rows match the database.
* On open, an incomplete install with statement rows resumes:
  * walking the statements from the start, those whose ordinal and
    digest match a completed row are skipped;
  * execution resumes at the first statement that wasn't completed;
  * a completed statement whose text has since changed is an error
    ("statement 4 of todo/0003 changed after it was applied"): DDL
    can't be undone, so the fix is to restore the database or make
    the script match what was applied.
* When the script finishes, its row is completed as usual. Statement
  rows are kept as a record of what ran.
* With tracking disabled, an incomplete install is an error, as above.

## Public API

* `open-local`, `open-pool`, `with-pool`: `:schemas` accepts ids and
  legacy vectors; new options as above.
* New: `installed-schemas` (the rows of `sql_file_installed`),
  `schema-installed?`.
* `ensure-schema` takes an id (or legacy vector).
* `get-schema-version` / `set-schema-version!`: kept for legacy
  vectors during the transition (reading `sql_file_installed`),
  deprecated, removed with legacy requests.
* `script-directives`: returns `{:requires [...] :replaces [...]}`.
* New: `script-digest`, for tests and tooling.

## Phases and releases

All of the phases below go into a single 0.6.0 release; they're
separate steps of the work, each with tests in the existing style
(small fixture scripts under `test/resources`, in-memory databases).
1.0.0 is a later decision.

1. **Step 1: tracking table.** (Done.) `sql-file-1`, migration of
   `sql_file_schema`, start/finish times, incomplete-install detection.
   Existing numbered chains behave as before; `replaces` checks all
   replaced schemas before running anything.
2. **Step 2: ids and the dependency graph.** (Done.) String ids, `requires`,
   resolution and topological install, legacy naming, `replaces` by
   id, the uncovered-schema check, `:development-mode`.
3. **Step 3: digests.** Computation, storage, checking and adoption for
   migrated rows.
4. **Step 4: statement-level tracking and resume.**
5. **Later (possibly 1.0.0):** remove legacy `[name n]` requests and
   `get/set-schema-version`; drop `sql_file_schema`. Possibly 1.0.0.

Test coverage to add along the way: diamond dependencies (installed
once, in the right order), cycles, missing dependencies, deterministic
order of independent branches, legacy and new ids mixed, migration of
an existing `sql_file_schema`, `replaces` across ids including the
"later" check without old script files, incomplete installs, digest
match/mismatch/adoption/missing script, and statement resume including
an edited applied statement.

## Effect on toto, stuffastocking and ectcommon

Nothing has to change when they move to 0.6.0. Their
existing scripts (`ectcommon-0`, `todo-0`, `stocking-5`, the frozen
`toto-*`) keep their legacy ids. New scripts can use the new style,
for example `ectcommon/0001-session-key` with
`requires ectcommon-0`, and the applications switch their `:schemas`
from `[["todo" 0]]` to the newest id when they do.

The digest adoption step means the first open after upgrading records
digests for scripts as they stand; edits to those scripts after that
are detected.

## Open questions

* Should a target's `requires` closure be allowed to name schemas that
  are only satisfied by `replaces` (a script required under its old
  id, after a rename)? Current plan: no; require the new id.
* Maximum id length (255) and allowed characters (no whitespace;
  anything else?).
* Whether `recorded` rows should store the digest of the replacing
  script (current plan: yes, so later edits to a baseline are noticed).
