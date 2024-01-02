(ns sql-file.script-test
  (:use clojure.test
        sql-file.util)
  (:require [sql-file.script :as script]
            [clojure.tools.reader.reader-types :as rdr]))

(defn- sql-remaining [ sql ]
  (let [in (rdr/source-logging-push-back-reader sql)
        buf (StringBuffer.)]
    (script/sql-read-string in)
    (while (not (nil? (rdr/peek-char in)))
      (.append buf (rdr/read-char in)))
    (.toString buf)))

(defn- sql-string-literal [ sql ]
  (script/sql-read-string (rdr/source-logging-push-back-reader sql)))

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
    (is (= [{:line 1, :column 1, :statement "1"}]
           (script/sql-statements "1;")))
    (is (= [{:line 1, :column 3, :statement "1"}]
           (script/sql-statements "  1;")))    
    (is (= [{:line 1, :column 1, :statement "1"} {:line 1, :column 3, :statement "2"}]
           (script/sql-statements "1;2")))
    (is (= [{:line 1, :column 1, :statement "1"} {:line 1, :column 3, :statement "2"}]
           (script/sql-statements "1;2;")))
    (is (= [{:line 1, :column 1, :statement "1"}]
           (script/sql-statements "1;--2;")))
    (is (=  [{:line 1, :column 1, :statement "1"} {:line 1, :column 3, :statement "2';'3"}]
           (script/sql-statements "1;2';'3;"))))

  (testing "newline processing"
    (is (= [{:line 3, :column 1, :statement "1"}]
           (script/sql-statements "\n\n1;")))
    (is (= [{:line 3, :column 1, :statement "1"}]
           (script/sql-statements "-- comment \n -- comment \n1;")))
    (is (= [{:line 1, :column 1, :statement "1"}]
           (script/sql-statements "1;\n;")))
    (is (= [{:line 1, :column 1, :statement "1 2"}]
           (script/sql-statements "1\n2;")))
    (is (= [{:line 1, :column 1, :statement "1 2"}]
           (script/sql-statements "1\n\n2;"))))
    (is (= [{:line 1, :column 1, :statement "1 2"} {:line 2, :column 3, :statement "3 4"}]
           (script/sql-statements "1\n2;3\n4;"))))

(defn- test-script-statements [ script-name ]
  (script/sql-statements
   (slurp (clojure.java.io/resource script-name))))

(deftest script-parser
  (testing "empty script"
    (is (= 0 (count (test-script-statements "empty-script.sql")))))

  (testing "single statement script"
    (is (= [{:line 1, :column 1, :statement "CREATE CACHED TABLE sql_file_schema ( schema_name VARCHAR(32) NOT NULL PRIMARY KEY, schema_version INT NOT NULL )"}]
           (test-script-statements "single-statement.sql"))))

  (testing "multiple statement script"
    (is (= [{:line 1, :column 1, :statement "CREATE CACHED TABLE sql_file_schema ( schema_name VARCHAR(32) NOT NULL PRIMARY KEY, )"}
            {:line 5, :column 1, :statement "CREATE CACHED TABLE sql_file_schema ( schema_version INT NOT NULL )"}]
           (test-script-statements "multi-statement.sql"))))

  (testing "multiple statement script w/whitespace"
    (is (= [{:line 3, :column 1, :statement "CREATE CACHED TABLE sql_file_schema ( schema_name VARCHAR(32) NOT NULL PRIMARY KEY, )"}
            {:line 7, :column 1, :statement "CREATE CACHED TABLE sql_file_schema ( schema_version INT NOT NULL )"}]
           (test-script-statements "multi-statement-whitespace.sql"))))

  (testing "multiple statement script w/comments"
    (is (= [{:line 2, :column 1, :statement "CREATE CACHED TABLE sql_file_schema ( schema_name VARCHAR(32) NOT NULL PRIMARY KEY, )"}
            {:line 6, :column 1, :statement "CREATE CACHED TABLE sql_file_schema ( schema_version INT NOT NULL )"}]
           (test-script-statements "multi-statement-comment.sql")))))
