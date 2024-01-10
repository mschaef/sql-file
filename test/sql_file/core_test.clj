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

(defn- with-clean-db [ t ]
  (jdbc/with-db-connection [ conn test-db ]
    (jdbc/db-do-prepared conn "DROP SCHEMA PUBLIC CASCADE"))
  (t))

(use-fixtures :each with-clean-db)

(defn- open-test-db [ schema ]
  (-> (core/open-local {:name test-db-name } )
      (core/ensure-schema schema)))

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

