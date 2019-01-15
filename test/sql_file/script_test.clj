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
    (is (= [{:line 1, :column 1, :statement "1"} {:line 1, :column 3, :statement "2"}]
           (script/sql-statements "1;2")))
    (is (= [{:line 1, :column 1, :statement "1"} {:line 1, :column 3, :statement "2"}]
           (script/sql-statements "1;2;")))
    (is (= [{:line 1, :column 1, :statement "1"}]
           (script/sql-statements "1;--2;")))
    (is (=  [{:line 1, :column 1, :statement "1"} {:line 1, :column 3, :statement "2';'3"}]
           (script/sql-statements "1;2';'3;"))))

  (testing "newline processing"
    (is (= [{:line 1, :column 1, :statement "1"}]
           (script/sql-statements "1;\n;")))
    (is (= [{:line 1, :column 1, :statement "1 2"}]
           (script/sql-statements "1\n2;")))
    (is (= [{:line 1, :column 1, :statement "1 2"}]
           (script/sql-statements "1\n\n2;"))))
    (is (= [{:line 1, :column 1, :statement "1 2"} {:line 2, :column 3, :statement "3 4"}]
           (script/sql-statements "1\n2;3\n4;"))))
