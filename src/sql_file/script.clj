;; Copyright (c) 2015-2019 Michael Schaeffer
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

(ns sql-file.script
  "Utility functions for processing SQL scripts.")

(defn at-sql-comment? [ sql ]
  "Determine if a sequence of characters is located at a SQL
comment (identified by two leading hypens')"
  (and (= \- (first sql)) (= \- (second sql))))

(defn sql-skip-comment [ sql ]
  "Skip a SQL comment, returning a the sequence of characters
following the comment text."
  (rest
   (drop-while #(not (= \newline %)) sql)))

(defn sql-read-string [ sql ]
  "Read a literal string from a SQL script, returning a two element
vector composed of the remaining SQL text and the text of the
literal. If the SQL script ends within a literal, there's no guarantee
that the returned literal is well-formed."
  (loop [lit-buf (.append (StringBuffer.) (first sql))
         sql (rest sql)]
    (cond
     (empty? sql)
     [ sql (.toString lit-buf)]

     (and (= \' (first sql)) (= \' (second sql)))
     (recur (.append lit-buf "''")  (rest (rest sql)))

     (= \' (first sql)) 
     [ (rest sql ) (.toString (.append lit-buf (first sql)))]

     :else
     (recur (.append lit-buf (first sql))  (rest sql)))))

(defn sql-statements [ sql ]
  "Given a sequence of characters corresponding to a SQL script,
return a sequence of the SQL statements contained in that script."
  (remove #(= 0 (.length %))
          (loop [sql sql
                 stmt-buf (StringBuffer.)
                 stmts []]
            (cond
             (empty? sql)
             (conj stmts (.trim (.toString stmt-buf)))
             
             (at-sql-comment? sql)
             (recur (sql-skip-comment sql) stmt-buf stmts)
             
             (= \' (first sql))
             (let [ [ sql literal ] (sql-read-string sql) ]
               (recur sql (.append stmt-buf literal) stmts))
             
             (= \; (first sql))
             (recur (rest sql) (StringBuffer.) (conj stmts (.trim (.toString stmt-buf))))
                      
             (= \newline (first sql))
             (recur (rest sql) (.append stmt-buf \space) stmts)

             :else
             (recur (rest sql) (.append stmt-buf (first sql)) stmts)))))

