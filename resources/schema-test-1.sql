-- schema-test-1.sql
--
-- Schema version 1 for test purposes.

CREATE CACHED TABLE test_point (
  x INT NOT NULL,
  y INT NOT NULL
);

CREATE CACHED TABLE test_3point (
  x INT NOT NULL,
  y INT NOT NULL,
  z INT NOT NULL
);
