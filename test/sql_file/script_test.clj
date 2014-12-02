(ns sql-file.script-test
  (:use clojure.test
        sql-file.util)
  (:require [sql-file.script :as script]))

(deftest at-sql-comment?
  (testing "Can identify comment"
    (is (script/at-sql-comment? "--foo"))
    (is (script/at-sql-comment? "-- foo \n")))

  (testing "Can identify non comment"
    (is (not (script/at-sql-comment? " --foo")))
    (is (not (script/at-sql-comment? "foo")))
    (is (not (script/at-sql-comment? "\"foo\"")))))

(deftest sql-skip-comment
  (testing "Can skip comment"
    (is (empty? (script/sql-skip-comment "-- no-newline")))
    (is (= \* (first (script/sql-skip-comment "-- comment\n*"))))))

(defn- sql-remaining [ sql ]
  (apply str (first (script/sql-read-string sql))))

(defn- sql-string-literal [ sql ]
  (second (script/sql-read-string sql)))

(deftest sql-read-string
  (testing "Can read SQL strings and produce correct literal tokens"
    (is (= "''" (sql-string-literal "''*")))
    (is (= "'test'" (sql-string-literal "'test'*")))
    (is (= "'-- embedded comment'" (sql-string-literal "'-- embedded comment'*")))
    (is (= "'partial" (sql-string-literal "'partial")))
    (is (= "'quot''d'" (sql-string-literal "'quot''d'*")))
    (is (= "''''''" (sql-string-literal "''''''*"))))

  (testing "Can read SQL strings and produce correct remaining text"
    (is (= "*" (sql-remaining "''*")))
    (is (= "*" (sql-remaining "'test'*")))
    (is (= "*" (sql-remaining "'-- embedded comment'*")))
    (is (= "" (sql-remaining "'partial")))

    (is (= "*" (sql-remaining "'quot''d'*")))

    (is (= "*" (sql-remaining "''''''*")))))

(deftest sql-statements 
  (testing "Empty script"
    (is (empty? (script/sql-statements "")))
    (is (empty? (script/sql-statements "   ")))
    (is (empty? (script/sql-statements " \n \n")))
    (is (empty? (script/sql-statements "--comments only\n\n--foo\n"))))

  (testing "Statement delimiter processing"
    (is (= ["1"]
           (script/sql-statements "1;")))
    (is (= ["1" "2"]
           (script/sql-statements "1;2")))
    (is (= ["1" "2"]
           (script/sql-statements "1;2;")))
    (is (= ["1"]
           (script/sql-statements "1;--2;")))
    (is (= ["1" "2';'3"]
           (script/sql-statements "1;2';'3;"))))

  (testing "newline processing"
    (is (= ["1"]
           (script/sql-statements "1;\n;")))
    (is (= ["1 2"]
           (script/sql-statements "1\n2;")))
    (is (= ["1  2"]
           (script/sql-statements "1\n\n2;"))))
    (is (= ["1 2" "3 4"]
           (script/sql-statements "1\n2;3\n4;"))))
