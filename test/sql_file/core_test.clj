;; Copyright (c) 2015-2024 Michael Schaeffer
;;
;; Licensed as below.
;;
;; Portions Copyright (c) 2014 KSM Technology Partners
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;       http://www.apache.org/licenses/LICENSE-2.0
;;
;; The license is also includes at the root of the project in the file
;; LICENSE.
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
;;
;; You must not remove this notice, or any other, from this software.


(ns sql-file.core-test
  (:use clojure.test
        sql-file.sql-util)
  (:require [sql-file.core :as core]
            [clojure.java.jdbc :as jdbc]))

(def test-db-name "mem:mem-db")

(def test-db (core/hsqldb-conn {:name test-db-name}))

(defn- with-clean-db [t]
  (jdbc/with-db-connection [conn test-db]
    (jdbc/db-do-prepared conn "DROP SCHEMA PUBLIC CASCADE"))
  (t))

(use-fixtures :each with-clean-db)

(defn- open-test-db [schema]
  (-> (core/open-local {:name test-db-name})
      (core/ensure-schema schema)))

(deftest create-memory-database
  (jdbc/with-db-connection [conn (open-test-db ["test" 0])]
    (testing "schema versions are reachable via API and correct."
      (is (= 0 (core/get-schema-version conn "sql-file")))
      (is (= 0 (core/get-schema-version conn "test"))))))

(deftest create-and-upgrade-memory-database
  (jdbc/with-db-connection [conn (open-test-db ["test" 1])]
    (testing "schema versions are reachable via API and correct."
      (is (= 0 (core/get-schema-version conn "sql-file")))
      (is (= 1 (core/get-schema-version conn "test")))))

  (testing "cannot downgrade existing database"
    (is (thrown-with-msg? Exception #"Cannot downgrade schema test from version 1 to 0"
                          (jdbc/with-db-connection [conn (open-test-db ["test" 0])])))))

(deftest set-schema-version!
  (jdbc/with-db-connection [conn (open-test-db ["test" 0])]
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
                          (jdbc/with-db-connection [conn (open-test-db ["test" 2])]))))

  (testing "Schema versions correct after failure."
    (jdbc/with-db-connection [conn (open-test-db ["test" 1])]
      (is (= 0 (core/get-schema-version conn "sql-file")))
      (is (=  (core/get-schema-version conn "test"))))))


;;; Schema replacement (-- sql-file: replaces <schema> <version>)

(defn- table-exists? [conn table-name]
  (= 1 (query-scalar conn [(str "SELECT COUNT(*) FROM information_schema.tables"
                                " WHERE table_schema = 'PUBLIC' AND table_name = ?")
                           (.toUpperCase ^String table-name)])))

(defn- exception-messages [ex]
  (->> ex
       (iterate #(.getCause ^Throwable %))
       (take-while some?)
       (map #(.getMessage ^Throwable %))))

(defn- thrown-messages [f]
  (try
    (f)
    []
    (catch Exception ex
      (exception-messages ex))))

(deftest script-directive-parsing
  (testing "no directives"
    (is (= {:replaces []} (core/script-directives "-- just a comment\nCREATE TABLE x (y INT);"))))

  (testing "directives are read from the leading comment block"
    (is (= {:replaces [["legacy" 1] ["other" 0]]}
           (core/script-directives (str "-- header\n\n"
                                        "-- sql-file: replaces legacy 1\n"
                                        "--sql-file:replaces   other 0  \n"
                                        "CREATE TABLE x (y INT);")))))

  (testing "directives after the first statement are ignored"
    (is (= {:replaces []}
           (core/script-directives (str "CREATE TABLE x (y INT);\n"
                                        "-- sql-file: replaces legacy 1\n")))))

  (testing "unrecognized directives are an error"
    (is (thrown-with-msg? Exception #"Unrecognized sql-file directive"
                          (core/script-directives "-- sql-file: replace legacy 1\n")))))

(deftest replacing-schema-in-fresh-database
  (jdbc/with-db-connection [conn (open-test-db ["successor" 1])]
    (testing "the replacing script runs normally"
      (is (= 1 (core/get-schema-version conn "successor")))
      (is (table-exists? conn "legacy_a"))
      (is (table-exists? conn "successor_c")))

    (testing "the replaced schema is not installed"
      (is (nil? (core/get-schema-version conn "legacy"))))))

(deftest replacing-schema-already-present
  (open-test-db ["legacy" 1])
  ;; schema-successor-0 creates the legacy tables, so it would fail if run.
  (jdbc/with-db-connection [conn (open-test-db ["successor" 1])]
    (testing "the replacing schema is recorded without running its script"
      (is (= 1 (core/get-schema-version conn "successor")))
      (is (= 1 (core/get-schema-version conn "legacy"))))

    (testing "later versions of the replacing schema run normally"
      (is (table-exists? conn "successor_c")))))

(deftest replacing-schema-at-older-version
  (open-test-db ["legacy" 0])
  (jdbc/with-db-connection [conn (open-test-db ["successor" 0])]
    (testing "the replaced schema is brought up to the declared version first"
      (is (= 1 (core/get-schema-version conn "legacy")))
      (is (table-exists? conn "legacy_b")))

    (testing "and then the replacing schema is recorded"
      (is (= 0 (core/get-schema-version conn "successor"))))))

(deftest replacing-schema-at-newer-version
  (open-test-db ["legacy" 2])
  (is (some #(re-find #"replaces \[\"legacy\" 1\], but the database has legacy at version 2" %)
            (thrown-messages #(open-test-db ["successor" 0]))))
  (jdbc/with-db-connection [conn (open-test-db ["legacy" 2])]
    (is (nil? (core/get-schema-version conn "successor")))))

(deftest replacing-schema-with-missing-scripts
  (open-test-db ["gone" 0])
  (is (some #(re-find #"Cannot find schema script: schema-gone-1.sql" %)
            (thrown-messages #(open-test-db ["heir" 0])))))

(deftest replacing-several-schemas
  (testing "all of the replaced schemas must be present, or none"
    (open-test-db ["legacy" 1])
    (is (some #(re-find #"only \[\[\"legacy\" 1\]\] are present" %)
              (thrown-messages #(open-test-db ["merged" 0])))))

  (testing "when all are present, the replacing schema is recorded"
    (open-test-db ["other" 0])
    (jdbc/with-db-connection [conn (open-test-db ["merged" 0])]
      (is (= 0 (core/get-schema-version conn "merged"))))))

(deftest misspelled-directive
  (is (some #(re-find #"Unrecognized sql-file directive: -- sql-file: replace legacy 1" %)
            (thrown-messages #(open-test-db ["typo" 0])))))
