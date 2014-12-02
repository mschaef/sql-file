(ns sql-file.main
  (:use sql-file.util)
  (:require [sql-file.core :as core]
            [clojure.java.jdbc :as jdbc]
            [clojure.tools.logging :as log]))

(def memory-db? false)

(defn -main []
  (jdbc/with-db-connection [ conn (if memory-db?
                                    (core/open-hsqldb-memory-conn "test-db" "test" 0)
                                    (core/open-hsqldb-file-conn "test-db" "test" 0))]
    (log/info "Conn: " conn))
  (log/info "end run."))
