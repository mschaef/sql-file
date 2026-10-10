;; Copyright (c) 2015-2024 Michael Schaeffer
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

(ns sql-file.core
  (:use sql-file.sql-util)
  (:require [clojure.tools.logging :as log]
            [clojure.java.jdbc :as jdbc]
            [sql-file.script :as script]
            [hikari-cp.core :as hikari-cp]))

(defn- schema-path [conn]
  (conj (get conn :schema-path []) ""))

(def ^:private legacy-id-regex #"(.+)-(\d+)")

(defn- valid-schema-id? [schema-id]
  (and (string? schema-id)
       (<= 1 (count schema-id) 255)
       (not (re-find #"\s" schema-id))))

(defn- locate-schema-script [conn schema-id]
  "Locate the script for a schema id: <id>.sql on the schema path, or
for an id of the form <name>-<n>, the numbered script
schema-<name>-<n>.sql. Returns a map with :url, and :legacy [name n]
for a numbered script. Throws if there's no such script."
  (let [basename (str schema-id ".sql")
        legacy (when-let [[_ schema-name version] (re-matches legacy-id-regex schema-id)]
                 [schema-name (Integer/parseInt version)])
        legacy-basename (str "schema-" schema-id ".sql")
        find (fn [name]
               (some #(clojure.java.io/resource (str % name)) (schema-path conn)))]
    (if-let [url (find basename)]
      {:url url}
      (if-let [url (and legacy (find legacy-basename))]
        {:url url :legacy legacy}
        (throw (Exception. (str "Cannot find schema script for " schema-id
                                " (" (if legacy
                                       (str basename " or " legacy-basename)
                                       basename)
                                ") in search path " (schema-path conn))))))))

(defn do-statements [conn stmts]
  "Execute a sequence of statements against the given DB connection."
  (jdbc/with-db-connection [cdb conn]
    (doseq [stmt stmts]
      (log/debug "Executing SQL:" (str (:url stmt) "(" (:line stmt) ":" (:column stmt) ")") (:statement stmt))
      (try
        (jdbc/db-do-prepared cdb (:statement stmt))
        (catch Exception ex
          (throw (Exception. (str "Error running statement: " stmt) ex)))))))

(defn- run-script [conn script-url script-text]
  "Run the database script with the given text (read from the given
URL) against a specific database connection."
  (log/info "Run DB script:" (str script-url))
  (do-statements conn (map #(assoc % :url script-url)
                           (script/sql-statements script-text))))

;;; Script directives
;;;
;;; A schema script can carry directives to sql-file in its leading
;;; comment block, one per line, of the form:
;;;
;;;   -- sql-file: <directive> <arguments...>
;;;
;;; The directives are:
;;;
;;;   -- sql-file: requires <schema-id>
;;;
;;; The named schema must be installed before this one. A script can
;;; have any number of these; they're installed in the order given.
;;;
;;;   -- sql-file: replaces <schema-id>
;;;   -- sql-file: replaces <schema-name> <schema-version>
;;;
;;; This declares that the script reproduces another schema (the
;;; second form names a numbered schema, and is the same as
;;; "replaces <schema-name>-<schema-version>"). In a database that
;;; already has that other schema, the script is not run; it's
;;; recorded as installed instead. This is how a schema is renamed,
;;; or a chain of schemas split into several or merged.

(def ^:private directive-regex #"^--\s*sql-file:\s*(.*?)\s*$")

(def ^:private requires-regex #"requires\s+(\S+)")

(def ^:private replaces-regex #"replaces\s+(\S+)(?:\s+(\d+))?")

(defn- leading-comment-lines [script-text]
  (->> (clojure.string/split-lines script-text)
       (map clojure.string/trim)
       (take-while #(or (= "" %) (.startsWith ^String % "--")))))

(defn script-directives [script-text]
  "Parse the sql-file directives in the leading comment block of a
schema script, returning a map with :requires and :replaces, each a
vector of schema ids. Unrecognized directives are an error, so that a
misspelled directive can't be silently ignored."
  (reduce (fn [directives line]
            (if-let [[_ directive] (re-matches directive-regex line)]
              (if-let [[_ schema-id] (re-matches requires-regex directive)]
                (update directives :requires conj schema-id)
                (if-let [[_ schema-id version] (re-matches replaces-regex directive)]
                  (update directives :replaces conj (if version
                                                      (str schema-id "-" version)
                                                      schema-id))
                  (throw (Exception. (str "Unrecognized sql-file directive: " line)))))
              directives))
          {:requires [] :replaces []}
          (leading-comment-lines script-text)))

;;; Install tracking
;;;
;;; sql-file records each installed schema script as a row in
;;; sql_file_installed, keyed by schema id. A numbered ("legacy")
;;; schema [name n] has the id "name-n", and implicitly requires
;;; "name-(n-1)".
;;;
;;; how is 'run' (the script was executed), 'recorded' (recorded under
;;; a replaces directive, or by set-schema-version!, without running),
;;; or 'migrated' (carried over from the older sql_file_schema table).
;;; A 'run' row is written before its script starts, and completed_on
;;; set when it finishes, so a script that failed or was interrupted
;;; partway through can be detected later.
;;;
;;; The older sql_file_schema table (one row per numbered schema, with
;;; its version) is still kept up to date, so that an application can
;;; be rolled back to an older sql-file without re-running scripts.

(defn- now []
  (java.sql.Timestamp. (System/currentTimeMillis)))

(defn- table-exists? [conn table-name]
  (pos? (query-scalar conn [(str "SELECT COUNT(*) FROM information_schema.tables"
                                 " WHERE table_name = ?")
                            (.toUpperCase ^String table-name)])))

(defn legacy-schema-id [schema-name schema-version]
  "The schema id of version schema-version of the numbered schema
schema-name."
  (str schema-name "-" schema-version))

(defn- legacy-schema-version [schema-name schema-id]
  "If schema-id is a version of the numbered schema schema-name, return
the version number, otherwise nil."
  (when-let [[_ version] (re-matches (re-pattern (str (java.util.regex.Pattern/quote schema-name)
                                                      "-(\\d+)"))
                                     schema-id)]
    (Integer/parseInt version)))

(defn- legacy-requires [schema-name schema-version]
  (when (pos? schema-version)
    (legacy-schema-id schema-name (dec schema-version))))

(defn- all-schema-ids [conn]
  (query-column conn ["SELECT schema_id FROM sql_file_installed"]))

(defn- completed-schema-ids [conn]
  (query-column conn [(str "SELECT schema_id FROM sql_file_installed"
                           " WHERE NOT (how = 'run' AND completed_on IS NULL)")]))

(defn installed-schemas [conn]
  "Return the rows of sql_file_installed: one map per installed schema,
with :schema_id, :how, :requires, :replaces, :digest, :started_on and
:completed_on."
  (query-all conn ["SELECT * FROM sql_file_installed ORDER BY schema_id"]))

(defn- insert-installed! [conn row]
  (jdbc/insert! conn :sql_file_installed row))

(defn- mirror-legacy-version! [conn schema-name schema-version]
  "Keep the older sql_file_schema table in step, for rollback to an
older sql-file. sql-file's own schema stays at version 0 there, which
is all an older sql-file knows how to handle."
  (when (and (not= schema-name "sql-file")
             (table-exists? conn "sql_file_schema"))
    (if (some? (query-scalar conn ["SELECT schema_version FROM sql_file_schema WHERE schema_name = ?"
                                   schema-name]))
      (jdbc/update! conn :sql_file_schema
                    {:schema_version schema-version}
                    ["schema_name = ?" schema-name])
      (jdbc/insert! conn :sql_file_schema
                    {:schema_name schema-name
                     :schema_version schema-version}))))

(defn get-schema-version [conn schema-name]
  "Retrieves the current version of a numbered schema within a database
managed by sql-file: the highest version recorded as installed. If
there is no such schema, this function returns nil. If the version
cannot be identified due to an exception an error message is logged
with the stack trace and the function returns nil."
  (try
    (let [versions (keep #(legacy-schema-version schema-name %)
                         (completed-schema-ids conn))]
      (when (seq versions)
        (apply max versions)))
    (catch Exception ex
      (when (log/enabled? :debug)
        (log/error ex "Error while attempting to identify version of schema:" schema-name))
      nil)))

(defn set-schema-version! [conn schema-name req-schema-version]
  "Sets the version of a numbered schema within a database managed by
sql-file, without running any scripts: versions up to and including
req-schema-version are recorded as installed, and any later versions
are removed."
  (let [ids (all-schema-ids conn)
        installed (set ids)
        timestamp (now)]
    (doseq [id ids]
      (when-let [version (legacy-schema-version schema-name id)]
        (when (> version req-schema-version)
          (jdbc/delete! conn :sql_file_installed ["schema_id = ?" id]))))
    (doseq [version (range (inc req-schema-version))]
      (let [id (legacy-schema-id schema-name version)]
        (when-not (installed id)
          (insert-installed! conn {:schema_id id
                                   :how "recorded"
                                   :requires (legacy-requires schema-name version)
                                   :started_on timestamp
                                   :completed_on timestamp}))))
    (mirror-legacy-version! conn schema-name req-schema-version)))

;;; Schema scripts and the dependency graph

(defn- split-ids [s]
  (if (clojure.string/blank? s)
    []
    (clojure.string/split s #"\n")))

(defn- join-ids [ids]
  (when (seq ids)
    (clojure.string/join "\n" ids)))

(defn- read-script [conn schema-id]
  "Locate and read the script for a schema id, returning a map with
:id, :url, :text, :requires (the effective requires: for a numbered
script, the previous version first, then any declared), :replaces, and
:legacy ([name n] for a numbered script)."
  (when-not (valid-schema-id? schema-id)
    (throw (Exception. (str "Invalid schema id: " (pr-str schema-id)))))
  (let [{:keys [url legacy]} (locate-schema-script conn schema-id)
        text (slurp url)
        {:keys [requires replaces]} (script-directives text)
        implicit (when legacy (apply legacy-requires legacy))]
    {:id schema-id
     :url url
     :text text
     :requires (vec (distinct (concat (when implicit [implicit]) requires)))
     :replaces replaces
     :legacy legacy}))

(defn- installed-graph [conn]
  "The installed schemas, as a map from id to {:requires [...]
:replaces [...]}, from what was recorded at install time."
  (into {} (map (fn [row]
                  [(:schema_id row) {:requires (split-ids (:requires row))
                                     :replaces (split-ids (:replaces row))}])
                (installed-schemas conn))))

(defn- schema-target-id [schema]
  "Schemas are requested by id, or by the older [name n] vector."
  (if (vector? schema)
    (apply legacy-schema-id schema)
    schema))

(defn- install-order [conn targets]
  "The schemas that need installing to provide the given targets, in
dependency order: a depth-first, post-order walk taking targets in the
order given and each script's requires in the order declared.
Installed schemas aren't read or followed: they, and what they
required, are already present. Returns a sequence of scripts (see
read-script)."
  (let [installed (installed-graph conn)
        order (atom [])
        done (atom #{})]
    (letfn [(visit [schema-id path]
              (when (some #{schema-id} path)
                (throw (Exception. (str "Schema dependency cycle: "
                                        (clojure.string/join " -> " (concat (drop-while #(not= schema-id %) path)
                                                                            [schema-id]))))))
              (when-not (or (installed schema-id) (@done schema-id))
                (let [script (try
                               (read-script conn schema-id)
                               (catch Exception ex
                                 (throw (Exception. (str "Cannot resolve schema " schema-id
                                                         (when (seq path)
                                                           (str ", required by " (last path))))
                                                    ex))))
                      path (conj path schema-id)]
                  (doseq [required (:requires script)]
                    (visit required path))
                  (swap! done conj schema-id)
                  (swap! order conj script))))]
      (doseq [target targets]
        (visit target [])))
    @order))

(defn- requires-closure [graph schema-id]
  "schema-id and everything it requires, transitively, according to
graph (a map from id to {:requires [...]})."
  (loop [pending [schema-id]
         seen #{}]
    (if-let [[id & more] (seq pending)]
      (if (seen id)
        (recur more seen)
        (recur (concat more (get-in graph [id :requires])) (conj seen id)))
      seen)))

(defn- script-requires-closure [conn installed schema-id]
  "schema-id and everything it requires, transitively, reading scripts
for schemas that aren't installed and using the installed graph for
those that are. Scripts that can't be found are skipped."
  (loop [pending [schema-id]
         seen #{}]
    (if-let [[id & more] (seq pending)]
      (if (seen id)
        (recur more seen)
        (let [requires (if-let [node (installed id)]
                         (:requires node)
                         (try
                           (:requires (read-script conn id))
                           (catch Exception _ [])))]
          (recur (concat more requires) (conj seen id))))
      seen)))

(declare ensure-schema)

(defn- replaced-schema-present? [conn installed own replaced-id]
  "A replaced schema is present if it's installed, or if part of it is:
something it requires (transitively) is installed, other than what the
replacing script itself requires (own, the closure of those). For a
numbered schema, any installed version counts, even if the older
scripts have since been deleted. (A later version than the replaced
one is then caught as depending on it.)"
  (let [partly-present? #(and (contains? installed %) (not (own %)))]
    (or (contains? installed replaced-id)
        (if-let [[_ schema-name] (re-matches legacy-id-regex replaced-id)]
          (some #(and (legacy-schema-version schema-name %) (partly-present? %))
                (keys installed))
          (some partly-present? (script-requires-closure conn installed replaced-id))))))

(defn- replaced-schemas-present [conn script]
  "Given the schemas a script declares it replaces, determine whether
they're already present in the database. Returns true if all of them
are present (after installing whatever they need to be up to date),
false if none are, and throws if only some are, or if an installed
schema depends on one of them. (Then the database has changes the
replacing script doesn't reproduce.) All of the checks are made before
anything is installed."
  (let [replaces (:replaces script)
        installed (installed-graph conn)
        own (set (mapcat #(script-requires-closure conn installed %) (:requires script)))
        present (filter #(replaced-schema-present? conn installed own %) replaces)]
    (cond
      (empty? present)
      false

      (not= (count present) (count replaces))
      (throw (Exception. (str "Schema " (:id script) " replaces " (clojure.string/join ", " replaces)
                              ", but only " (clojure.string/join ", " present)
                              " are present in the database.")))

      :else
      (do
        (doseq [replaced-id replaces
                [installed-id _] installed
                :when (and (not= installed-id replaced-id)
                           (contains? (requires-closure installed installed-id) replaced-id))]
          (throw (Exception. (str "Schema " (:id script) " replaces " replaced-id
                                  ", but the database has " installed-id ", which depends on it."))))
        (doseq [replaced-id replaces]
          (ensure-schema conn replaced-id))
        true))))

(defn- install-script [conn script]
  "Install a single schema script whose requirements are already
installed. If the script declares that it replaces schemas already
present in the database, the script is not run and the schema is just
recorded as installed."
  (let [{:keys [id url text requires replaces legacy]} script]
    (log/info "Installing schema:" id)
    (try
      (if (replaced-schemas-present conn script)
        (let [timestamp (now)]
          (log/info "Schema" id "replaces" (clojure.string/join ", " replaces)
                    "which are already present. Recording it without running" (str url))
          (insert-installed! conn {:schema_id id
                                   :how "recorded"
                                   :requires (join-ids requires)
                                   :replaces (join-ids replaces)
                                   :started_on timestamp
                                   :completed_on timestamp}))
        (do
          (insert-installed! conn {:schema_id id
                                   :how "run"
                                   :requires (join-ids requires)
                                   :replaces (join-ids replaces)
                                   :started_on (now)})
          (run-script conn url text)
          (jdbc/update! conn :sql_file_installed
                        {:completed_on (now)}
                        ["schema_id = ?" id])))
      (catch Exception ex
        (throw (Exception. (str "Error installing schema: " id) ex))))
    (when legacy
      (apply mirror-legacy-version! conn legacy))))

(defn- install-targets [conn targets]
  (doseq [script (install-order conn (map schema-target-id targets))]
    ;; Installing a replacing script can install other schemas first
    ;; (the ones it replaces), so check again.
    (when-not (contains? (set (all-schema-ids conn)) (:id script))
      (install-script conn script))))

(defn- uncovered-schemas [conn targets]
  "Installed schemas not reachable from the targets through requires
and replaces. These usually mean older code running against a newer
database. sql-file's own schemas don't count."
  (let [installed (installed-graph conn)
        reachable (loop [pending (map schema-target-id targets)
                         seen #{}]
                    (if-let [[id & more] (seq pending)]
                      (if (seen id)
                        (recur more seen)
                        (let [{:keys [requires replaces]}
                              (or (installed id)
                                  (try
                                    (read-script conn id)
                                    (catch Exception _ {})))]
                          (recur (concat more requires replaces) (conj seen id))))
                      seen))]
    (sort (remove #(or (reachable %) (.startsWith ^String % "sql-file-"))
                  (keys installed)))))

(defn- policy [desc option]
  "How to handle a condition that's an error in production and a
warning in development: :error or :warn."
  (get desc option (if (:development-mode desc) :warn :error)))

(defn- check-uncovered-schemas [conn desc targets]
  (when-let [uncovered (seq (uncovered-schemas conn targets))]
    (let [message (str "Installed schemas not covered by the requested schemas: "
                       (clojure.string/join ", " uncovered)
                       ". Is this older code running against a newer database?")]
      (if (= :warn (policy desc :on-uncovered-schema))
        (log/warn message)
        (throw (Exception. message))))))

;;; sql-file's own tables

(defn- run-internal-script [conn version]
  (let [script-url (clojure.java.io/resource (format "schema-sql-file-%s.sql" version))]
    (run-script conn script-url (slurp script-url))))

(defn- migrate-legacy-schema-rows! [conn]
  "Copy the older sql_file_schema table into sql_file_installed: a row
(name, n) becomes rows name-0 through name-n. Rows already present are
left alone, so this can safely be repeated."
  (let [installed (set (all-schema-ids conn))]
    (doseq [{schema-name :schema_name
             schema-version :schema_version} (query-all conn ["SELECT * FROM sql_file_schema"])]
      (log/info "Migrating installed schema records:" schema-name "versions 0 to" schema-version)
      (doseq [version (range (inc schema-version))]
        (let [id (legacy-schema-id schema-name version)]
          (when-not (installed id)
            (insert-installed! conn {:schema_id id
                                     :how "migrated"
                                     :requires (legacy-requires schema-name version)})))))))

(defn- ensure-sql-file-tables [conn]
  "Install or upgrade sql-file's own tables. A database from an older
sql-file has only sql_file_schema; its rows are copied into
sql_file_installed. The sql-file-1 row is written last, so if this is
interrupted it's simply repeated on the next open."
  (when-not (table-exists? conn "sql_file_schema")
    (log/info "Installing schema: [sql-file 0]")
    (run-internal-script conn 0)
    (jdbc/insert! conn :sql_file_schema {:schema_name "sql-file"
                                         :schema_version 0}))
  (when-not (and (table-exists? conn "sql_file_installed")
                 (some #{"sql-file-1"} (all-schema-ids conn)))
    (let [started-on (now)]
      (when-not (table-exists? conn "sql_file_installed")
        (log/info "Installing schema: [sql-file 1]")
        (run-internal-script conn 1))
      (migrate-legacy-schema-rows! conn)
      (insert-installed! conn {:schema_id "sql-file-1"
                               :how "run"
                               :requires "sql-file-0"
                               :started_on started-on
                               :completed_on (now)}))))

(defn- check-incomplete-installs [conn]
  "Fail if any schema script started but didn't finish. The database
may have been partly changed by it, and running it again could fail
or do damage."
  (when-let [incomplete (seq (query-all conn [(str "SELECT schema_id, started_on FROM sql_file_installed"
                                                   " WHERE how = 'run' AND completed_on IS NULL"
                                                   " ORDER BY started_on")]))]
    (throw (Exception. (str "Schema installation did not complete: "
                            (clojure.string/join ", " (map #(str (:schema_id %) " (started " (:started_on %) ")")
                                                           incomplete))
                            ". The database may have been partly changed. Restore it from a backup,"
                            " or repair it by hand and delete the schema's row from sql_file_installed.")))))

;; Public Entry points

(defn hsqldb-conn [desc]
  "Construct a connection map for an HSQLDB database with the given
filename and schema. A name with a \"mem:\" prefix may be used to
request a memory database."
  (cond-> {:classname "org.hsqldb.jdbc.JDBCDriver"
           :subprotocol  "hsqldb"
           :subname (:name desc)}
    (:schema-path desc) (assoc :schema-path (:schema-path desc))))

(defn ensure-schema [conn schema]
  "Install the given schema, and anything it requires, if they aren't
already installed. The schema is an id, or a numbered schema as
[name n]."
  (log/debug "Ensuring schema:" schema)
  (install-targets conn [schema])
  conn)

(defn checkpoint-defragment [conn]
  (jdbc/db-do-prepared conn "CHECKPOINT DEFRAG"))

(defn backup-to-file-blocking [conn output-path]
  (jdbc/db-do-prepared conn (str "BACKUP DATABASE TO '" output-path "' BLOCKING")))

(defn backup-to-file-online [conn output-path]
  (jdbc/db-do-prepared conn (str "BACKUP DATABASE TO '" output-path "' NOT BLOCKING")))

(defn start-sqltool-shell [conn]
  (.flush *out*)
  (doto (org.hsqldb.cmdline.SqlFile. *in* "stdin" System/out
                                     "UTF-8" true
                                     (java.net.URL. "file:."))
    (.setConnection (:connection conn))
    (.execute)))

(defn open-local [desc]
  "Open the database described by desc, installing sql-file's own
tables and the schemas listed in :schemas (ids, or [name n] for
numbered schemas), along with everything they require. Options:

  :name - the HSQLDB database name (a file path, or mem:<name>)
  :schema-path - directories (resource path prefixes) to search for
     schema scripts, in addition to the root
  :schemas - the schemas the application needs
  :development-mode - when true, conditions that are errors in
     production are warnings instead
  :on-uncovered-schema - :error or :warn, for installed schemas the
     requested schemas don't cover (default from :development-mode)"
  (log/info "Opening sql-file:" desc)
  (let [conn (hsqldb-conn desc)]
    (ensure-sql-file-tables conn)
    (check-incomplete-installs conn)
    (let [targets (get desc :schemas [])]
      (check-uncovered-schemas conn desc targets)
      (install-targets conn targets))
    conn))

(defn open-pool [desc]
  (log/info "Opening sql-file (pooled):" desc)
  (let [conn (open-local desc)
        datasource (hikari-cp/make-datasource (merge {:driver-class-name (:classname conn)
                                                      :jdbc-url (str "jdbc:hsqldb:" (:subname conn))
                                                      :maximum-pool-size (get desc :pool-size 4)}
                                                     (get desc :pool {})))]
    (assoc conn :datasource datasource)))

(defn close-pool [pool]
  (hikari-cp/close-datasource (:datasource pool)))

(defn call-with-pool [f desc]
  (let [pool (open-pool desc)]
    (try
      (f pool)
      (finally
        (close-pool pool)))))

(defmacro with-pool [[var desc] & body]
  `(call-with-pool (fn [~var] ~@body) ~desc))
