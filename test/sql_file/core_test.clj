(ns sql-file.core-test
  (:use clojure.test
        sql-file.sql-util)
  (:require [sql-file.core :as core]
            [clojure.java.jdbc :as jdbc]))

(deftest create-memory-database
  (jdbc/with-db-connection [ conn (core/open-sql-file (core/hsqldb-memory-conn "test-db") [ "test" 0 ])]
    (testing "schema versions are reachable via API and correct."
      (is (= 0 (core/get-schema-version conn "sql-file")))
      (is (= 0 (core/get-schema-version conn "test"))))

    (testing "schema versions are reachable via JDBC and correct."
      (is (= 0 (query-scalar conn (str "SELECT schema_version FROM SQL_FILE_SCHEMA"
                                       "  WHERE schema_name='sql-file'"))))
      (is (= 0 (query-scalar conn (str "SELECT schema_version FROM SQL_FILE_SCHEMA"
                                       "  WHERE schema_name='test'")))))))

(deftest create-and-upgrade-memory-database
  (jdbc/with-db-connection [ conn (core/open-sql-file (core/hsqldb-memory-conn "test-db") [ "test" 1 ])]
    (testing "schema versions are reachable via API and correct."
      (is (= 0 (core/get-schema-version conn "sql-file")))
      (is (= 1 (core/get-schema-version conn "test"))))

    (testing "schema versions are reachable via JDBC and correct."
      (is (= 0 (query-scalar conn (str "SELECT schema_version FROM SQL_FILE_SCHEMA"
                                       "  WHERE schema_name='sql-file'"))))
      (is (= 1 (query-scalar conn (str "SELECT schema_version FROM SQL_FILE_SCHEMA"
                                       "  WHERE schema_name='test'")))))))

(deftest set-schema-version
  (jdbc/with-db-connection [ conn (core/open-sql-file (core/hsqldb-memory-conn "test-db") [ "test" 0 ]) ]
    (testing "missing schema is missing"
      (is (nil? (query-scalar conn (str "SELECT schema_version FROM SQL_FILE_SCHEMA"
                                        "  WHERE schema_name='ssv-test'")))))

    (testing "set-schema-version! on missing schema"
      (core/set-schema-version! conn "ssv-test" 0)
      (is (= 0 (query-scalar conn (str "SELECT schema_version FROM SQL_FILE_SCHEMA"
                                        "  WHERE schema_name='ssv-test'")))))

    (testing "set-schema-version! on present schema")
    (core/set-schema-version! conn "ssv-test-2" 2)
    (is (= 2 (query-scalar conn (str "SELECT schema_version FROM SQL_FILE_SCHEMA"
                                     "  WHERE schema_name='ssv-test-2'"))))))



