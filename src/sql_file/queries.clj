;; Copyright (c) 2015-2026 Michael Schaeffer
;;
;; Licensed as below.
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

(ns sql-file.queries
  "sql-file's queries against its own tables. These take the
  connection explicitly, as a :connection call option, since
  sql-file's functions are given the connection to use."
  (:require [yesql.core :refer [defqueries]]
            [sql-file.query-middleware :as query-middleware]))

(defqueries "sql_file/queries.sql"
  {:middleware (comp query-middleware/query-mode-middleware
                     query-middleware/log-query-middleware)})
