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

(ns sql-file.sql-util-test
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

(deftest test-query-all
  (jdbc/with-db-connection [conn (open-test-db ["test" 0])]
    (testing "query-all returns full result set"
      (is (= (query-all conn "SELECT * FROM test_point ORDER BY x;")
             [{:x 1 :y 10}
              {:x 2 :y 20}
              {:x 3 :y 30}
              {:x 4 :y 40}])))

    (testing "query-all returns empty result set"
      (is (= (query-all conn "SELECT * FROM test_point WHERE x=-1;")
             [])))))

(deftest test-query-column
  (jdbc/with-db-connection [conn (open-test-db ["test" 0])]
    (testing "query-column returns full result set"
      (is (= (query-column conn "SELECT x FROM test_point ORDER BY x;")
             [1 2 3 4])))

    (testing "query-column returns empty result set"
      (is (= (query-column conn "SELECT x FROM test_point WHERE x=-1;")
             [])))

    (testing "query-column reports error on non-columnar result set"
      (is (thrown-with-msg?
           Exception #"Query must have only one column"
           (query-column conn "SELECT x, y FROM test_point;"))))))

(deftest test-query-scalar
  (jdbc/with-db-connection [conn (open-test-db ["test" 0])]
    (testing "query-scalar returns scalar value"
      (is (= (query-scalar conn "SELECT COUNT(*) FROM test_point;")
             4)))

    (testing "query-scalar returns default value for empty result set"
      (is (nil? (query-scalar conn "SELECT x FROM test_point WHERE x=-1;")))

      (is (= (query-scalar conn "SELECT x FROM test_point WHERE x=-1;" :default)
             :default)))

    (testing "query-scalar returns NIL when result set contains null value"
      (is (nil? (query-scalar conn "SELECT NULL FROM test_point WHERE x=1;"))))

    (testing "query-scalar reports error on non-columnar result set"
      (is (thrown-with-msg?
           Exception #"Query must have only one column"
           (query-scalar conn "SELECT x, y FROM test_point WHERE x=1;"))))))

(deftest test-query-scalar-required
  (jdbc/with-db-connection [conn (open-test-db ["test" 0])]
    (testing "query-scalar-required returns scalar value"
      (is (= (query-scalar-required conn "SELECT COUNT(*) FROM test_point;")
             4)))

    (testing "query-scalar-required reports error for empty result set"
      (is (thrown-with-msg?
           Exception #"No value returned when required by query"
           (query-scalar-required conn "SELECT x FROM test_point WHERE x=-1;"))))

    (testing "query-scalar-required returns NIL when result set contains null value"
      (is (nil? (query-scalar-required conn "SELECT NULL FROM test_point WHERE x=1;"))))

    (testing "query-scalar-required reports error on non-columnar result set"
      (is (thrown-with-msg?
           Exception #"Query must have only one column"
           (query-scalar-required conn "SELECT x, y FROM test_point WHERE x=1;"))))))
