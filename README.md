# sql-file

A Clojure library designed to automate management of
[HSQLDB](http://hsqldb.org/) databases hosted within an
application. This library provides API's for creating appropriate
[java.jdbc](https://github.com/clojure/java.jdbc) connection maps, as
well as automatically applying schema creation and migration scripts.

## Usage

Example usage:

```clojure
(require '[clojure.java.jdbc :as jdbc])
(require '[sql-file.core :as core])

(jdbc/query (core/open-hsqldb-file-conn "test-db" "test" 0)
   ["select count(*) from point]))
;; 0
```

This example creates a file-based HSQLDB database named `test-db`, and
automatically loads version 0 of the `test` schema from
`resources/schema-test-0.sql`.

## License

Copyright © 2014 KSM Technology Partners

All Rights Reserved.

