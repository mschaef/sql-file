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

(defn- open-test-db
  "Open the test database with the given schemas. Many tests open the
same database several times with different schemas, so installed
schemas the request doesn't cover are only a warning here."
  [& schemas]
  (core/open-local {:name test-db-name
                    :schemas (vec schemas)
                    :on-uncovered-schema :warn}))

(defn- open-test-db-strictly [& schemas]
  (core/open-local {:name test-db-name
                    :schemas (vec schemas)}))

(deftest create-memory-database
  (jdbc/with-db-connection [conn (open-test-db ["test" 0])]
    (testing "schema versions are reachable via API and correct."
      (is (= 1 (core/get-schema-version conn "sql-file")))
      (is (= 0 (core/get-schema-version conn "test"))))))

(deftest create-and-upgrade-memory-database
  (jdbc/with-db-connection [conn (open-test-db ["test" 1])]
    (testing "schema versions are reachable via API and correct."
      (is (= 1 (core/get-schema-version conn "sql-file")))
      (is (= 1 (core/get-schema-version conn "test")))))

  (testing "older code against a newer database is detected"
    (is (thrown-with-msg? Exception #"Installed schemas not covered by the requested schemas: test-1"
                          (open-test-db-strictly ["test" 0])))))

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
    (is (thrown-with-msg? Exception #"Error installing schema: test-2"
                          (jdbc/with-db-connection [conn (open-test-db ["test" 2])]))))

  (testing "The failed script is recorded as started but not completed"
    (let [row (first (filter #(= "test-2" (:schema_id %))
                             (core/installed-schemas test-db)))]
      (is (= "run" (:how row)))
      (is (some? (:started_on row)))
      (is (nil? (:completed_on row)))
      (is (= 1 (core/get-schema-version test-db "test")))))

  (testing "Opening the database again reports the incomplete install"
    (is (thrown-with-msg? Exception #"Schema installation did not complete: test-2"
                          (open-test-db ["test" 1])))))

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
    (is (= {:requires [] :replaces []} (core/script-directives "-- just a comment\nCREATE TABLE x (y INT);"))))

  (testing "directives are read from the leading comment block"
    (is (= {:requires ["base" "dir/lib-1"]
            :replaces ["legacy-1" "other-0" "renamed/thing"]}
           (core/script-directives (str "-- header\n\n"
                                        "-- sql-file: requires base\n"
                                        "-- sql-file: replaces legacy 1\n"
                                        "--sql-file:replaces   other 0  \n"
                                        "-- sql-file: replaces renamed/thing\n"
                                        "-- sql-file: requires dir/lib-1\n"
                                        "CREATE TABLE x (y INT);")))))

  (testing "directives after the first statement are ignored"
    (is (= {:requires [] :replaces []}
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
      (is (= 1 (core/get-schema-version conn "legacy")))
      (let [row (first (filter #(= "successor-0" (:schema_id %)) (core/installed-schemas conn)))]
        (is (= "recorded" (:how row)))
        (is (= "legacy-1" (:replaces row)))))

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
  (is (some #(re-find #"replaces legacy-1, but the database has legacy-2, which depends on it" %)
            (thrown-messages #(open-test-db ["successor" 0]))))
  (jdbc/with-db-connection [conn (open-test-db ["legacy" 2])]
    (is (nil? (core/get-schema-version conn "successor")))))

(deftest replacing-schema-with-missing-scripts
  (open-test-db ["gone" 0])
  (is (some #(re-find #"Cannot find schema script for gone-1 \(gone-1.sql or schema-gone-1.sql\)" %)
            (thrown-messages #(open-test-db ["heir" 0])))))

(deftest replacing-several-schemas
  (testing "all of the replaced schemas must be present, or none"
    (open-test-db ["legacy" 1])
    (is (some #(re-find #"replaces legacy-1, other-0, but only legacy-1 are present" %)
              (thrown-messages #(open-test-db ["merged" 0])))))

  (testing "when all are present, the replacing schema is recorded"
    (open-test-db ["other" 0])
    (jdbc/with-db-connection [conn (open-test-db ["merged" 0])]
      (is (= 0 (core/get-schema-version conn "merged"))))))

(deftest misspelled-directive
  (is (some #(re-find #"Unrecognized sql-file directive: -- sql-file: replace legacy 1" %)
            (thrown-messages #(open-test-db ["typo" 0])))))

(deftest replaced-schemas-are-all-checked-first
  (open-test-db ["legacy" 0])
  (open-test-db ["other" 1])
  (is (some #(re-find #"replaces other-0, but the database has other-1, which depends on it" %)
            (thrown-messages #(open-test-db ["merged" 0]))))
  (testing "no replaced schema was upgraded before the failure"
    (jdbc/with-db-connection [conn (open-test-db ["other" 1])]
      (is (= 0 (core/get-schema-version conn "legacy"))))))

;;; Install tracking (sql_file_installed)

(defn- installed-row [conn schema-id]
  (first (filter #(= schema-id (:schema_id %)) (core/installed-schemas conn))))

(deftest install-tracking-on-new-database
  (jdbc/with-db-connection [conn (open-test-db ["test" 1])]
    (testing "every installed version has a row, with its implicit dependency"
      (is (= ["sql-file-0" "sql-file-1" "test-0" "test-1"]
             (map :schema_id (core/installed-schemas conn))))
      (is (nil? (:requires (installed-row conn "test-0"))))
      (is (= "test-0" (:requires (installed-row conn "test-1")))))

    (testing "run scripts have start and end times"
      (let [row (installed-row conn "test-1")]
        (is (= "run" (:how row)))
        (is (some? (:started_on row)))
        (is (some? (:completed_on row)))
        (is (not (.after (:started_on row) (:completed_on row))))))

    (testing "the older sql_file_schema table is kept up to date"
      (is (= 1 (query-scalar conn ["SELECT schema_version FROM sql_file_schema WHERE schema_name = 'test'"])))
      (is (= 0 (query-scalar conn ["SELECT schema_version FROM sql_file_schema WHERE schema_name = 'sql-file'"]))))))

(defn- create-old-sql-file-database []
  ;; What a database created by sql-file 0.5 and earlier looks like: sql_file_schema
  ;; only, with the test schema at version 1.
  (jdbc/with-db-connection [conn test-db]
    (doseq [script ["schema-sql-file-0.sql" "schema-test-0.sql" "schema-test-1.sql"]]
      (core/do-statements conn (sql-file.script/sql-statements
                                (slurp (clojure.java.io/resource script)))))
    (jdbc/insert! conn :sql_file_schema {:schema_name "sql-file" :schema_version 0})
    (jdbc/insert! conn :sql_file_schema {:schema_name "test" :schema_version 1})))

(deftest migration-from-old-sql-file-database
  (create-old-sql-file-database)
  ;; schema-test-0 and -1 would fail if run again (their tables exist).
  (jdbc/with-db-connection [conn (open-test-db ["test" 1])]
    (testing "existing versions are migrated, not run"
      (is (= 1 (core/get-schema-version conn "test")))
      (is (= {"sql-file-0" "migrated" "sql-file-1" "run" "test-0" "migrated" "test-1" "migrated"}
             (into {} (map (juxt :schema_id :how) (core/installed-schemas conn)))))
      (is (= "test-0" (:requires (installed-row conn "test-1"))))
      (is (nil? (:started_on (installed-row conn "test-1")))))

    (testing "migration is repeated if it was interrupted"
      (jdbc/delete! conn :sql_file_installed ["schema_id IN ('sql-file-1', 'test-1')"])
      (jdbc/with-db-connection [conn (open-test-db ["test" 1])]
        (is (some? (installed-row conn "sql-file-1")))
        (is (= "migrated" (:how (installed-row conn "test-1"))))))))

(deftest set-schema-version-records-and-removes-versions
  (jdbc/with-db-connection [conn (open-test-db ["test" 0])]
    (core/set-schema-version! conn "ssv" 2)
    (is (= ["ssv-0" "ssv-1" "ssv-2"]
           (filter #(.startsWith ^String % "ssv") (map :schema_id (core/installed-schemas conn)))))
    (is (= "recorded" (:how (installed-row conn "ssv-1"))))
    (core/set-schema-version! conn "ssv" 0)
    (is (= ["ssv-0"]
           (filter #(.startsWith ^String % "ssv") (map :schema_id (core/installed-schemas conn)))))
    (is (= 0 (query-scalar conn ["SELECT schema_version FROM sql_file_schema WHERE schema_name = 'ssv'"])))))

;;; Schema ids and the dependency graph

(defn- install-log [conn]
  (query-column conn ["SELECT schema_id FROM install_log ORDER BY seq"]))

(defn- installed-ids [conn prefix]
  (filter #(.startsWith ^String % prefix) (map :schema_id (core/installed-schemas conn))))

(deftest dependency-graph-install
  (jdbc/with-db-connection [conn (open-test-db "graph/top")]
    (testing "dependencies install first, each once, in declared order"
      (is (= ["graph/base" "graph/left" "graph/right" "graph/top"] (install-log conn))))

    (testing "each installed schema records its requires"
      (is (= "graph/left\ngraph/right" (:requires (installed-row conn "graph/top"))))
      (is (= "run" (:how (installed-row conn "graph/top"))))))

  (testing "only what's missing is installed later"
    (jdbc/with-db-connection [conn (open-test-db "graph/top" "graph/top-rl")]
      (is (= ["graph/base" "graph/left" "graph/right" "graph/top" "graph/top-rl"]
             (install-log conn))))))

(deftest independent-branches-install-in-declared-order
  (jdbc/with-db-connection [conn (open-test-db "graph/top-rl")]
    (is (= ["graph/base" "graph/right" "graph/left" "graph/top-rl"] (install-log conn)))))

(deftest numbered-and-named-schemas-together
  (jdbc/with-db-connection [conn (open-test-db "graph/uses-legacy")]
    (testing "a named schema can require a numbered one"
      (is (= 1 (core/get-schema-version conn "test")))
      (is (= ["test-0" "test-1"] (installed-ids conn "test-")))
      (is (= ["graph/base" "graph/uses-legacy"] (install-log conn))))

    (testing "numbered schemas are still mirrored for older sql-file"
      (is (= 1 (query-scalar conn ["SELECT schema_version FROM sql_file_schema WHERE schema_name = 'test'"]))))

    (testing "named schemas aren't"
      (is (= 0 (query-scalar conn ["SELECT COUNT(*) FROM sql_file_schema WHERE schema_name LIKE 'graph%'"]))))))

(deftest installed-schemas-are-not-reread
  (jdbc/with-db-connection [conn (open-test-db)]
    ;; There's no script for ghost/a: it stands in for a script that
    ;; was installed and has since been deleted.
    (jdbc/insert! conn :sql_file_installed {:schema_id "ghost/a" :how "recorded"}))
  (jdbc/with-db-connection [conn (open-test-db "graph/uses-ghost")]
    (is (some? (installed-row conn "graph/uses-ghost")))))

(deftest dependency-cycles-are-reported
  (is (some #(re-find #"Schema dependency cycle: cycle/a -> cycle/b -> cycle/c -> cycle/a" %)
            (thrown-messages #(open-test-db "cycle/a")))))

(deftest missing-dependencies-are-reported
  (is (some #(re-find #"Cannot resolve schema missing/nope, required by missing/a" %)
            (thrown-messages #(open-test-db "missing/a"))))
  (is (some #(re-find #"Cannot find schema script for missing/nope \(missing/nope.sql\)" %)
            (thrown-messages #(open-test-db "missing/a")))))

(deftest invalid-schema-ids-are-reported
  (is (some #(re-find #"Invalid schema id: \"has space\"" %)
            (thrown-messages #(open-test-db "has space")))))

(deftest replacing-by-id-in-fresh-database
  (jdbc/with-db-connection [conn (open-test-db "renamed/left")]
    (testing "what the replacing script itself requires doesn't make the replaced schema present"
      (is (= ["graph/base" "renamed/left"] (install-log conn)))
      (is (nil? (installed-row conn "graph/left"))))))

(deftest replacing-by-id-when-the-replaced-schema-is-present
  (open-test-db "graph/left")
  (jdbc/with-db-connection [conn (open-test-db "renamed/left")]
    (is (= ["graph/base" "graph/left"] (install-log conn)))
    (is (= "recorded" (:how (installed-row conn "renamed/left"))))
    (is (= "graph/left" (:replaces (installed-row conn "renamed/left"))))))

(deftest replacing-by-id-when-part-of-the-replaced-schema-is-present
  ;; graph/left is part of graph/top, and not something renamed/top
  ;; requires itself.
  (open-test-db "graph/left")
  (jdbc/with-db-connection [conn (open-test-db "renamed/top")]
    (testing "the replaced schema is completed, then the replacing one recorded"
      (is (= ["graph/base" "graph/left" "graph/right" "graph/top"] (install-log conn)))
      (is (= "run" (:how (installed-row conn "graph/top"))))
      (is (= "recorded" (:how (installed-row conn "renamed/top"))))))

  (testing "replaced schemas count as covered by the replacing one"
    (is (some? (open-test-db-strictly "renamed/top")))))

(deftest replacing-by-id-when-something-depends-on-the-replaced-schema
  (open-test-db "graph/top")
  (is (some #(re-find #"replaces graph/left, but the database has graph/top, which depends on it" %)
            (thrown-messages #(open-test-db "renamed/left")))))

(deftest uncovered-schemas
  (open-test-db-strictly "graph/top")
  (testing "installed schemas the request doesn't reach are an error by default"
    (is (thrown-with-msg? Exception #"not covered by the requested schemas: graph/right, graph/top"
                          (open-test-db-strictly "graph/left"))))

  (testing "and a warning in development mode, or when asked"
    (is (some? (core/open-local {:name test-db-name :schemas ["graph/left"] :development-mode true})))
    (is (some? (core/open-local {:name test-db-name :schemas ["graph/left"] :on-uncovered-schema :warn}))))

  (testing "an explicit setting overrides development mode"
    (is (thrown? Exception
                 (core/open-local {:name test-db-name :schemas ["graph/left"]
                                   :development-mode true :on-uncovered-schema :error})))))
