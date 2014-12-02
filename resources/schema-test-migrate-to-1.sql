-- schema-test-migrate-to-1.sql
--
-- Migration script to upgrade test schema to version 1

CREATE CACHED TABLE test_3point (
  x INT NOT NULL,
  y INT NOT NULL,
  z INT NOT NULL
);
