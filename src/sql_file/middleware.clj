;; Copyright (c) 2015-2022 Michael Schaeffer
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

(ns sql-file.middleware
  (:require [clojure.tools.logging :as log]
            [clojure.java.jdbc :as jdbc]))

(def ^:dynamic *db* nil)

(defn call-with-db-connection [ fn db-connection ]
  (jdbc/with-db-connection [ conn db-connection ]
    (binding [ *db* conn ]
      (fn))))

(defmacro with-db-connection [ db-connection & body ]
  `(call-with-db-connection (fn [] ~@body) ~db-connection))

(defn current-db-connection []
  (when (not *db*)
    (throw (RuntimeException. "No current database connection for query.")))
  *db*)

(defn wrap-db-connection [ app db-connection ]
  (fn [ req ]
    (call-with-db-connection (fn [] (app req)) db-connection)))

