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

