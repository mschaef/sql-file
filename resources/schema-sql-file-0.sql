-- Copyright (c) KSM Technology Partners.
-- All rights reserved.
-- 
-- The use and distribution terms for this software are covered by the
-- Apache License, version 2.0 (http://opensource.org/licenses/Apache-2.0)
-- which can be found in the file LICENSE at the root of this distribution.
-- 
-- By using this software in any fashion, you are agreeing to be bound by
-- the terms of this license.
-- 
-- You must not remove this notice, or any other, from this software.


-- schema-sql-file-0.sql
--
-- Base sql-file schema. This contains administration information used
-- by sql-file to maintain the schemas stored within a database file.

CREATE CACHED TABLE sql_file_schema (
  schema_name VARCHAR(32) NOT NULL PRIMARY KEY,
  schema_version INT NOT NULL
);
