(ns sql-file.core-test
  (:use clojure.test
        sql-file.sql-util)
  (:require [sql-file.core :as core]
            [clojure.java.jdbc :as jdbc]))

(def test-db (core/hsqldb-memory-conn "mem-db"))

(defn- with-clean-db [ t ]
  (jdbc/with-db-connection [ conn test-db ]
    (jdbc/db-do-prepared conn "DROP SCHEMA PUBLIC CASCADE"))
  (t))

(use-fixtures :each with-clean-db)

(defn- open-test-db [ schema ]
  (core/open-sql-file test-db schema))

(deftest create-memory-database
  (jdbc/with-db-connection [ conn (open-test-db [ "test" 0 ])]
    (testing "schema versions are reachable via API and correct."
      (is (= 0 (core/get-schema-version conn "sql-file")))
      (is (= 0 (core/get-schema-version conn "test"))))))

(deftest create-and-upgrade-memory-database
  (jdbc/with-db-connection [ conn (open-test-db [ "test" 1 ])]
    (testing "schema versions are reachable via API and correct."
      (is (= 0 (core/get-schema-version conn "sql-file")))
      (is (= 1 (core/get-schema-version conn "test")))))

  (testing "cannot downgrade existing database"
    (is (thrown-with-msg? Exception #"Cannot downgrade schema test from version 1 to 0"
                          (jdbc/with-db-connection [ conn (open-test-db [ "test" 0 ])]
                            )))))

(deftest set-schema-version!
  (jdbc/with-db-connection [ conn (open-test-db [ "test" 0 ]) ]
    (testing "missing schema is missing"
      (is (nil? (core/get-schema-version conn "missing-schema"))))

    (testing "set-schema-version! on missing schema"
      (core/set-schema-version! conn "ssv-test" 0)
      (is (= 0 (core/get-schema-version conn "ssv-test"))))

    (testing "set-schema-version! on present schema"
      (core/set-schema-version! conn "ssv-test-2" 0)
      (core/set-schema-version! conn "ssv-test-2" 2)
      (is (= 2 (core/get-schema-version conn "ssv-test-2"))))))

(deftest failed-schema-execution
  (testing "Upgrade to bad script fails"
    (is (thrown-with-msg? Exception #"Error installing schema: \[\"test\" 2\]"
                          (jdbc/with-db-connection [ conn (open-test-db [ "test" 2 ]) ]
                            ))))

  (testing "Schema versions correct after failure."
    (jdbc/with-db-connection [ conn (open-test-db [ "test" 1 ]) ]
      (is (= 0 (core/get-schema-version conn "sql-file")))
      (is (=  (core/get-schema-version conn "test"))))))

