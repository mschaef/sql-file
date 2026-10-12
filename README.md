# sql-file

A Clojure library designed to automate management of
[HSQLDB](http://hsqldb.org/) databases hosted within an
application. This library provides API's for creating appropriate
[java.jdbc](https://github.com/clojure/java.jdbc) connection maps, as
well as automatically applying schema scripts.

The use case for this is scenarios where the capabilities provided by
a standalone database engine aren't worth the administrative
hassle. With an uberjar build, `sql-file` makes it easier to
consolidate a complete application, database engine and all, into a
single deployable artifact. (And with the presumption of SQL,
`sql-file` makes it possible to switch away to something larger
without rewriting application logic.)

## Adding to Your Project

This project is available on [Clojars](https://clojars.org/)
[here](https://clojars.org/com.mschaef/sql-file). 

It can be added to a [Leiningen](https://leiningen.org/) project with the 
following dependency:

```clojure
[com.mschaef/sql-file "0.6.0"]
```

(0.6.0 is the release described here. Earlier releases use the older
numbered schema scheme only; see Numbered Schemas.)

## Usage

Example usage:

```clojure
(require '[clojure.java.jdbc :as jdbc])
(require '[sql-file.core :as sql-file])

(def conn (sql-file/open-local {:name "test-db"
                                :schemas ["points/0001-add-z"]}))

(jdbc/query conn ["select count(*) from point"])
;; ({:c1 0})
```

This example opens (creating, if necessary) a file-based HSQLDB
database named `test-db`, and installs the schema
`points/0001-add-z` from `resources/points/0001-add-z.sql`, along with
anything it requires. A name starting with `mem:` (`mem:test-db`)
opens an in-memory database instead, which is useful for tests.

### Options

`open-local`, `open-pool` and `with-pool` take a map of options:

| Option | Default | Meaning |
|---|---|---|
| `:name` | (required) | HSQLDB database name: a file path, or `mem:<name>` for an in-memory database |
| `:schemas` | `[]` | Schemas the application needs: ids, or `[name n]` for numbered schemas |
| `:schema-path` | `[]` | Resource directories to search for scripts, before the root of the classpath |
| `:development-mode` | `false` | Sets the defaults of the three options below for development |
| `:on-schema-change` | `:error` (`:warn` in development) | An installed script has changed (see Detecting Changed Scripts) |
| `:on-uncovered-schema` | `:error` (`:warn` in development) | Installed schemas the request doesn't cover (see Schemas Not Covered by the Request) |
| `:track-statements` | `false` (`true` in development) | Record each statement as it's applied, so a failed script can be resumed |
| `:pool-size` | `4` | `open-pool` and `with-pool`: maximum number of pooled connections |
| `:pool` | `{}` | `open-pool` and `with-pool`: additional [HikariCP](https://github.com/tomekw/hikari-cp) settings |

In short, `:development-mode true` turns two errors into warnings (an
edited installed script, and installed schemas the request doesn't
cover) and turns on statement tracking. Each can still be set
individually.

### Schema Scripts and Dependencies

Each schema script is identified by a schema id: its path on the
classpath, without the `.sql` extension. Ids can contain `/`, to
organize scripts into directories, but not whitespace. Any ordering in
the ids (zero-padded numbers, for example) is for the people reading a
directory listing; `sql-file` doesn't rely on it.

A script declares the schemas it depends on in its leading comment
block:

```sql
-- sql-file: requires points/0000-initial

ALTER TABLE point ADD z INT DEFAULT 0 NOT NULL;
```

A script can have any number of `requires` directives. When opening a
database, `sql-file` reads the scripts named in `:schemas`, follows
their `requires`, and installs whatever isn't installed yet, each
script after everything it requires. Scripts that don't depend on each
other are installed in the order they're named in `:schemas`, and in
`requires` directives, so the order is the same every time.

A missing script, a dependency cycle (reported as
`a -> b -> c -> a`), or an invalid id is an error. Schemas that are
already installed aren't read again, so scripts can be deleted once
every database has them.

`:schema-path` lists additional resource directories to search for
scripts, before the root of the classpath.

### Numbered Schemas

Scripts named `schema-<name>-<n>.sql`, the scheme used by earlier
versions of `sql-file`, still work. Their id is `<name>-<n>`, and each
version implicitly requires the one before it. They can be requested by
id or with the older `[name n]` form:

```clojure
(sql-file/open-local {:name "test-db" :schemas [["test" 2]]})
```

In a new database, this installs `schema-test-0.sql`,
`schema-test-1.sql` and `schema-test-2.sql`, in that order. Named and
numbered schemas can depend on each other.

The highest installed version of a numbered schema can be retrieved
using `get-schema-version`:

```clojure
(sql-file/get-schema-version conn "test")
;; 2
```

`set-schema-version!` records versions of a numbered schema as
installed without running their scripts (and removes any later
versions). It's kept for compatibility with earlier releases and is
deprecated, along with `get-schema-version` and the `[name n]` form;
they may be removed in a future release.

### Replacing a Schema

Sometimes a schema needs to be renamed, or a chain of schemas split
into several or merged. A schema script can declare that it reproduces
another schema with a directive in its leading comment block:

```sql
-- sql-file: replaces legacy-16

CREATE CACHED TABLE user ( ... );
```

(For a numbered schema, `replaces legacy 16` means the same thing.)

The replaced schema is present in a database if it's installed, or
part of it is: something it requires is installed, other than what the
replacing script itself requires. For a numbered schema, any installed
version counts. When `sql-file` installs a script carrying this
directive:

* If the replaced schema isn't present (a new database, for example),
  the script runs normally.
* If it's installed, the script is not run. It's recorded as
  installed, because its contents are already in the database.
* If only part of it is present (for a numbered schema, an earlier
  version), the replaced schema is installed first, and then the
  script is recorded as above.
* If an installed schema depends on the replaced one (for a numbered
  schema, a later version is installed), installation fails. The
  database has changes the replacing script doesn't reproduce.

To split a chain, give the first script of each new chain the same
`replaces` directive. To merge chains, list several `replaces`
directives in one script. In that case, either all of the replaced
schemas must be present in the database, or none of them. All of these
checks are made before anything is installed.

The replaced schema's record stays in the database, but nothing
requests it again. Once every database has been opened with the new
scripts, the old scripts can be deleted. A database that still needs
them will then fail with a message naming the missing script, rather
than running the new scripts over existing tables.

Directives must appear before the first statement in the script. Any
other `-- sql-file:` comment there is an error, so a misspelled
directive can't be silently ignored.

### Installation Records

`sql-file` records each installed schema script in the table
`sql_file_installed`, one row per script. Each row records how the
script was installed (`run`, `recorded` under a `replaces` directive,
or `migrated` from an older `sql-file`), what it requires and
replaces, its digest, whether its statements were tracked, and when it
started and finished. `installed-schemas` returns these rows.

`sql-file`'s own tables are recorded the same way, as `sql-file-0` and
`sql-file-1`. They're managed by `sql-file` itself, so they're left out
of the digest check and don't need to be covered by the request.

The row for a script is written before the script runs, and marked
complete when it finishes. If a script fails or is interrupted partway
through, the next attempt to open the database fails with a message
naming it: the database may have been partly changed (HSQLDB doesn't
roll back schema changes), so it needs restoring from a backup, or
repairing by hand and the script's row deleting from
`sql_file_installed`, before continuing. The exception is a script that
ran with statement tracking, which can be fixed and resumed instead
(see the next section).

Databases created by earlier versions of `sql-file` are migrated
automatically the first time they're opened. The older
`sql_file_schema` table is still kept up to date for numbered schemas,
so an application can be rolled back to an earlier `sql-file`.

### Resuming a Failed Script (Development)

With `:track-statements true` (the default when `:development-mode` is
true), `sql-file` also records each statement of a script as it
succeeds, in `sql_file_statement`. HSQLDB applies schema changes
immediately, so these records match what's in the database.

If a script then fails partway through, fix it and open the database
again. `sql-file` checks that the statements already applied are
unchanged (reformatting and comments don't count), skips them, and runs
the rest. The script's record is updated to match the fixed script.
`installed-statements` returns the statements recorded for a schema.

* Changing a statement that was already applied is an error: schema
  changes can't be undone, so restore the database, or make the
  statement match what was applied. Removing one is too.
* An install that ran without statement tracking can't be resumed,
  because there's no record of which statements were applied. It's
  reported as incomplete, as described above.
* Without statement tracking, an install that was tracked is still
  reported as incomplete, with a note that it can be resumed with
  `:track-statements` or `:development-mode`.

Statement records are kept after the script completes, as a record of
what ran and when.

### Detecting Changed Scripts

When a script is installed, `sql-file` records a digest (SHA-256) of
what it means: its `requires` (for a numbered script, including the
implicit previous version), its `replaces`, and its statements. Each
time the database is opened, the digest of every installed script that
still exists is compared with the script as it is now.

Editing an installed script doesn't change databases that already have
it, so a difference usually means a change that should have gone into a
new script. It's an error by default, and a warning with
`:development-mode true`. `:on-schema-change` (`:error` or `:warn`)
overrides either.

The digest covers statements as `sql-file` splits them: runs of
whitespace are collapsed and `--` comments dropped, so re-indenting a
statement, rewording a comment or adding blank lines doesn't count as a
change. Changing a statement does, as does adding whitespace where
there was none (`VALUES(1)` to `VALUES (1)`), and so does adding,
removing or reordering `requires` or `replaces` directives.

Scripts installed before `sql-file` recorded digests get one, from the
script as it is then, the first time the database is opened. Scripts
that no longer exist aren't checked. `script-digest` returns the
digest of a script as it is now.

### Schemas Not Covered by the Request

When opening a database, installed schemas that the requested schemas
don't reach (through `requires` and `replaces`) usually mean older code
running against a newer database, for example after a rollback. This
is an error by default, and a warning with `:development-mode true`.
`:on-uncovered-schema` (`:error` or `:warn`) overrides either.

## Connections and Pools

`open-local` opens the database, installs schemas as described above,
and returns a [java.jdbc](https://github.com/clojure/java.jdbc)
connection map. `hsqldb-conn` returns the same kind of map without
opening the database or installing anything.

Applications serving more than one request at a time will usually want
a connection pool. `open-pool` does everything `open-local` does, then
adds a [HikariCP](https://github.com/tomekw/hikari-cp) pool (see the
`:pool-size` and `:pool` options), and `close-pool` closes it.
`with-pool` and `call-with-pool` open a pool for the duration of a body
or function call:

```clojure
(sql-file/with-pool [db {:name "app-db" :schemas ["app/0003-tags"]}]
  (jdbc/query db ["select count(*) from tag"]))
```

`ensure-schema` installs one more schema (an id or `[name n]`), with
anything it requires, on a database that's already open. Unlike
`open-local`, it doesn't check for schemas the request doesn't cover,
since it only knows about the one schema.

`sql-file.sql-util` has small query helpers built on java.jdbc:
`query-all` (all rows), `query-first` (the first row, or `nil`),
`query-column` (the values of a single-column result), `query-scalar`
(the single value of a single-column result, or a default if there are
no rows) and `query-scalar-required` (the same, but throwing if there
are no rows).

## Maintenance

These functions each take an open connection:

* `backup-to-file-blocking` and `backup-to-file-online` back up the
  database to the given path, with HSQLDB's `BACKUP DATABASE`. The
  blocking form stops other work on the database while it runs; the
  online form doesn't.
* `checkpoint-defragment` runs HSQLDB's `CHECKPOINT DEFRAG`, compacting
  the database files.
* `start-sqltool-shell` starts an interactive HSQLDB SqlTool shell on
  standard input and output, against a connection opened with
  `jdbc/with-db-connection`.

## Diagnostics

`sql-file` logs on the `sql-file.core` log channel.

* At `INFO`, it logs important events: opening a database, installing
  or recording a schema, migrating records from an earlier `sql-file`,
  recording digests for schemas installed before digests existed, and
  resuming a failed script.
* At `WARN`, it logs the conditions that `:development-mode` (or
  `:on-schema-change` / `:on-uncovered-schema` set to `:warn`) turns
  from errors into warnings: edited installed scripts, and installed
  schemas the request doesn't cover.
* At `DEBUG`, it also lists each SQL statement as it's executed.

## Limitations

The database engine provided by `sql-file` is strictly embedded in a
host application, and does not support inbound connections over a
network. Compared to a traditional database design, where the database
sits in a separate process, this can make it more inconvenient to
inspect the state of the database.

`sql-file` needs to break input script files into individual
statements to be able to execute them individually. To do this, it
needs a rudimentary SQL parser. The current version of this parser
only supports `--` style comments.

I would happily accept PRs to fix these or other issues.

## License

Copyright © 2015-2026 [Michael Schaeffer](https://mschaef.com/)

Portions Copyright © 2014 [KSM Technology Partners](https://www.ksmpartners.com/)

All Rights Reserved.

