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
(require '[sql-file.core :as core])

(jdbc/query (sql-file/open-local {:name "test-db" :schemas [ [ "test" 0 ] ]})
   ["select count(*) from point]))
;; 0
```

This example creates a file-based HSQLDB database named `test-db`, and
automatically loads version 0 of the `test` schema from
`resources/schema-test-0.sql`.

### Migrations

`sql-file` supports automatic forward migrations of database schemas
through the use of sequential version numbers.  To illustrate, this
`open-local` call requests version 2 of the `test` schema.

```clojure
(sql-file/open-local {:name "test-db" :schemas [ [ "test" 2 ] ]})
```

When opening a database, `sql-file` will compare the requested schema
version with the version already loaded in the database. It will then
run any necessary schema scripts in numerical order to ensure the
requested schema is present in the database. In a new database, this
call will result in three schema creation scripts being loaded and
applied in succession: `resources/schema-test-0.sql`,
`resources/schema-test-1.sql`, and finally
`resources/schema-test-2.sql`. If the database already contains schema
version `0`, then just `resources/schema-test-1.sql` will be run
`resources/schema-test-2.sql`.

The current version of a schema can be retrieved using
`get-schema-version`:

```clojure
(core/get-schema-version conn "test")
;; 2
```

### Replacing a Schema

Sometimes a schema chain needs to be renamed, split into several
chains, or merged with another. A schema script can declare that it
reproduces a version of another schema with a directive in its leading
comment block:

```sql
-- sql-file: replaces legacy 16

CREATE CACHED TABLE user ( ... );
```

When `sql-file` installs a script carrying this directive:

* If the database doesn't have the `legacy` schema at all (a new
  database, for example), the script runs normally.
* If the database has `legacy` at version 16, the script is not run.
  Its schema version is recorded as present, because its contents are
  already in the database.
* If the database has `legacy` at an earlier version, the `legacy`
  scripts are run to bring it up to version 16 first, and then the
  script's schema version is recorded as above.
* If the database has `legacy` at a later version, installation fails.
  The later version has changes the replacing script doesn't
  reproduce.

To split a chain, give the first script of each new chain the same
`replaces` directive. To merge chains, list several `replaces`
directives in one script. In that case, either all of the replaced
schemas must be present in the database, or none of them.

The replaced schema's row stays in the database, but nothing requests
it again. Once every database has been opened with the new chains, the
old chain's scripts can be deleted. A database still below the
declared version will then fail with a message naming the missing
script, rather than running the new chain's scripts over existing
tables.

Directives must appear before the first statement in the script. Any
other `-- sql-file:` comment there is an error, so a misspelled
directive can't be silently ignored.

### Installation Records

`sql-file` records each installed schema script in the table
`sql_file_installed`, one row per script. Version `n` of a numbered
schema `name` has the id `name-n`. Each row records how the script was
installed (`run`, `recorded` under a `replaces` directive, or
`migrated` from an older `sql-file`), the script it depends on, and
when it started and finished. `installed-schemas` returns these rows.

The row for a script is written before the script runs, and marked
complete when it finishes. If a script fails or is interrupted partway
through, the next attempt to open the database fails with a message
naming it: the database may have been partly changed (HSQLDB doesn't
roll back schema changes), so it needs restoring from a backup, or
repairing by hand and the script's row deleting from
`sql_file_installed`, before continuing.

Databases created by earlier versions of `sql-file` are migrated
automatically the first time they're opened. The older
`sql_file_schema` table is still kept up to date, so an application
can be rolled back to an earlier `sql-file`.

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

