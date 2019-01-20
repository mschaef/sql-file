# sql-file

A Clojure library designed to automate management of
[HSQLDB](http://hsqldb.org/) databases hosted within an
application. This library provides API's for creating appropriate
[java.jdbc](https://github.com/clojure/java.jdbc) connection maps, as
well as automatically applying schema scripts.

The use case for this is scenarios where the capabilities provided by
a standalone database engine aren't worth the administrative
hassle. With an uberjar build, `sql-file` makes ie easier to
consolidate a complete application into a single deployable
artifact. (And because `sql-file` just presumes the use of SQL, you
can easily switch away to something larger if you ever find the need.)

## Adding to Your Project

This project is available on [Clojars](https://clojars.org/)
[here](https://clojars.org/com.mschaef/sql-file). 

It can be added to a [Leiningen](https://leiningen.org/) project with the 
following dependency:

```clojure
[com.mschaef/sql-file 0.3.0]
```

## Usage
                                                      
Example usage:

```clojure
(require '[clojure.java.jdbc :as jdbc])
(require '[sql-file.core :as core])

(jdbc/query (core/open-sql-file (core/hsqldb-file-conn "test-db") ["test" 0])
   ["select count(*) from point]))
;; 0
```

This example creates a file-based HSQLDB database named `test-db`, and
automatically loads version 0 of the `test` schema from
`resources/schema-test-0.sql`.

### Migrations

Using a  schema version higher than  0 causes all of  scripts for that
schema  to  be run  in  ascending  order  of version.  This statement
requests version 2:

```clojure
    (core/open-sql-file (core/hsqldb-file-conn "test-db") ["test" 2])
```

This call will result in three scripts being loaded and applied in
succession: `resources/schema-test-0.sql`,
`resources/schema-test-1.sql`, and finally `resources/schema-test-2.sql`.

The current version of a schema can be retrieved using
`get-schema-version`:

```clojure
(core/get-schema-version conn "test")
;; 2
```

## Diagnostics

`sql-file` attempts to provide useful debug messages on the
`sql-file.core` log channel. At `INFO` level, `sql-file` will log only
important events (opening a database, installation of a schema,
etc.). At `DEBUG` level, it will list each SQL statement as it's
executed.

## Limitations

The database provided by `sql-file` is strictly embedded in a host
application, and does not support inbound connections over a
network. Compared to a traditional database design, where the database
sits in a separate process, this can make it more inconvenient to
inspect the state of the database.

`sql-file` needs to break input script files into individual
statements to be able to execute them individually. To do this, it
needs a rudimentary SQL parser. The current version of this parser
only supports `--` style comments. 

I would happily accept PRs to fix these or other issues.

## License

Copyright © 2015-2019 [Michael Schaeffer](http://www.mschaef.com/)

Portions Copyright © 2014 [KSM Technology Partners](https://www.ksmpartners.com/)

All Rights Reserved.

