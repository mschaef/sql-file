(ns sql-file.core-test
  (:use clojure.test
        sql-file.util)
  (:require [sql-file.core :as core]
            [clojure.java.jdbc :as jdbc]))

(deftest create-memory-database
  (jdbc/with-db-connection [ conn (core/open-hsqldb-memory-conn "test-db" "test" 0)]
    (testing "schema versions are reachable via API and correct."
      (is (= 0 (core/get-schema-version conn "sql-file")))
      (is (= 0 (core/get-schema-version conn "test"))))

    (testing "schema versions are reachable via JDBC and correct."
      (is (= 0 (query-scalar conn (str "SELECT schema_version FROM SQL_FILE_SCHEMA"
                                       "  WHERE schema_name='sql-file'"))))
      (is (= 0 (query-scalar conn (str "SELECT schema_version FROM SQL_FILE_SCHEMA"
                                       "  WHERE schema_name='test'")))))))

(deftest set-schema-version
  (jdbc/with-db-connection [ conn (core/open-hsqldb-memory-conn "test-db" "test" 0)]
    (testing "missing schema is missing"
      (is (nil? (query-scalar conn (str "SELECT schema_version FROM SQL_FILE_SCHEMA"
                                        "  WHERE schema_name='ssv-test'")))))

    (testing "set-schema-version on missing schema"
      (core/set-schema-version conn "ssv-test" 0)
      (is (= 0 (query-scalar conn (str "SELECT schema_version FROM SQL_FILE_SCHEMA"
                                        "  WHERE schema_name='ssv-test'")))))

    (testing "set-schema-version on present schema")
      (core/set-schema-version conn "ssv-test" 1)
      (is (= 1 (query-scalar conn (str "SELECT schema_version FROM SQL_FILE_SCHEMA"
                                        "  WHERE schema_name='ssv-test'"))))))



