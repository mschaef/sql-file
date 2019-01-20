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

(ns sql-file.main
  (:require [sql-file.core :as core]
            [clojure.java.jdbc :as jdbc]
            [clojure.tools.logging :as log]))

(def memory-db? false)

(defn -main []
  (jdbc/with-db-connection [ conn (-> (core/open-local {:name (if memory-db? "mem:test-db" "test-db")})
                                      (core/ensure-schema ["test" 1]))]
    (log/info "Conn: " conn))
  (log/info "end run."))
