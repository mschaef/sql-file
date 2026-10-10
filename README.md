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
anything it requires.

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
replaces, and when it started and finished. `installed-schemas`
returns these rows.

The row for a script is written before the script runs, and marked
complete when it finishes. If a script fails or is interrupted partway
through, the next attempt to open the database fails with a message
naming it: the database may have been partly changed (HSQLDB doesn't
roll back schema changes), so it needs restoring from a backup, or
repairing by hand and the script's row deleting from
`sql_file_installed`, before continuing.

Databases created by earlier versions of `sql-file` are migrated
automatically the first time they're opened. The older
`sql_file_schema` table is still kept up to date for numbered schemas,
so an application can be rolled back to an earlier `sql-file`.

### Schemas Not Covered by the Request

When opening a database, installed schemas that the requested schemas
don't reach (through `requires` and `replaces`) usually mean older code
running against a newer database, for example after a rollback. This
is an error by default, and a warning with `:development-mode true`.
`:on-uncovered-schema` (`:error` or `:warn`) overrides either.

## Diagnostics

`sql-file` attempts to provide useful debug messages on the
`sql-file.core` log channel. At `INFO` level, `sql-file` will log only
important events (opening a database, installation of a schema,
etc.). At `DEBUG` level, it will list each SQL statement as it's
executed.

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

Copyright © 2015-2024 [Michael Schaeffer](http://www.mschaef.com/)

Portions Copyright © 2014 [KSM Technology Partners](https://www.ksmpartners.com/)

All Rights Reserved.

