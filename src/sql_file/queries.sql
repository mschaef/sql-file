-- name: count-tables-named
-- info-query-mode: :scalar
SELECT COUNT(*)
  FROM information_schema.tables
 WHERE table_name = :table_name

-- name: count-columns-named
-- info-query-mode: :scalar
SELECT COUNT(*)
  FROM information_schema.columns
 WHERE table_name = :table_name
   AND column_name = :column_name

-- name: add-statements-tracked-column!
-- Databases opened with a 0.6.0 development snapshot, before this
-- column was added to sql-file-1.
ALTER TABLE sql_file_installed
  ADD statements_tracked BOOLEAN DEFAULT FALSE NOT NULL
  BEFORE started_on

---- Installed schemas

-- name: get-installed-schemas
SELECT *
  FROM sql_file_installed
 ORDER BY schema_id

-- name: get-all-schema-ids
-- info-query-mode: :column
SELECT schema_id
  FROM sql_file_installed

-- name: get-completed-schema-ids
-- info-query-mode: :column
SELECT schema_id
  FROM sql_file_installed
 WHERE NOT (how = 'run' AND completed_on IS NULL)

-- name: get-incomplete-install
-- info-query-mode: :row
SELECT *
  FROM sql_file_installed
 WHERE schema_id = :schema_id
   AND how = 'run'
   AND completed_on IS NULL

-- name: get-incomplete-installs
SELECT schema_id, started_on, statements_tracked
  FROM sql_file_installed
 WHERE how = 'run'
   AND completed_on IS NULL
 ORDER BY started_on

-- name: insert-installed!
INSERT INTO sql_file_installed (schema_id, how, requires, replaces, digest,
                                statements_tracked, started_on, completed_on)
VALUES (:schema_id, :how, :requires, :replaces, :digest,
        :statements_tracked, :started_on, :completed_on)

-- name: delete-installed!
DELETE FROM sql_file_installed
 WHERE schema_id = :schema_id

-- name: set-installed-completed!
UPDATE sql_file_installed
   SET completed_on = :completed_on
 WHERE schema_id = :schema_id

-- name: set-installed-digest!
UPDATE sql_file_installed
   SET digest = :digest
 WHERE schema_id = :schema_id

-- name: set-installed-resumed!
UPDATE sql_file_installed
   SET requires = :requires,
       replaces = :replaces,
       digest = :digest,
       completed_on = :completed_on
 WHERE schema_id = :schema_id

---- Tracked statements

-- name: insert-statement!
INSERT INTO sql_file_statement (schema_id, ordinal, digest, completed_on)
VALUES (:schema_id, :ordinal, :digest, :completed_on)

-- name: get-installed-statements
SELECT ordinal, digest, completed_on
  FROM sql_file_statement
 WHERE schema_id = :schema_id
 ORDER BY ordinal

---- The older sql_file_schema table, kept for rollback

-- name: get-legacy-schemas
SELECT *
  FROM sql_file_schema

-- name: get-legacy-schema-version
-- info-query-mode: :scalar
SELECT schema_version
  FROM sql_file_schema
 WHERE schema_name = :schema_name

-- name: insert-legacy-schema!
INSERT INTO sql_file_schema (schema_name, schema_version)
VALUES (:schema_name, :schema_version)

-- name: set-legacy-schema-version!
UPDATE sql_file_schema
   SET schema_version = :schema_version
 WHERE schema_name = :schema_name

---- Maintenance

-- name: checkpoint-defragment!
CHECKPOINT DEFRAG

-- name: shutdown!
SHUTDOWN
