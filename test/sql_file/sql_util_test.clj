(ns sql-file.sql-util-test
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

(deftest test-query-all
  (jdbc/with-db-connection [ conn (open-test-db [ "test" 0 ])]
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
  (jdbc/with-db-connection [ conn (open-test-db [ "test" 0 ])]
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
  (jdbc/with-db-connection [ conn (open-test-db [ "test" 0 ])]
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
  (jdbc/with-db-connection [ conn (open-test-db [ "test" 0 ])]
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


