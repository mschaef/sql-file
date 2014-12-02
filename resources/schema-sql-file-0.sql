-- schema-sql-file-0.sql
--
-- Base sql-file schema. This contains administration information used
-- by sql-file to maintain the schemas stored within a database file.

CREATE CACHED TABLE sql_file_schema (
  schema_name VARCHAR(32) NOT NULL PRIMARY KEY,
  schema_version INT NOT NULL
);
