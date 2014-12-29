; Copyright (c) KSM Technology Partners.
; All rights reserved.
;
; The use and distribution terms for this software are covered by the
; Apache License, version 2.0 (http://opensource.org/licenses/Apache-2.0)
; which can be found in the file LICENSE at the root of this distribution.
;
; By using this software in any fashion, you are agreeing to be bound by
; the terms of this license.
;
; You must not remove this notice, or any other, from this software.

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
