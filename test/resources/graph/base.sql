-- Copyright (c) 2026 Michael Schaeffer

CREATE CACHED TABLE install_log (seq INT IDENTITY, schema_id VARCHAR(64) NOT NULL);
INSERT INTO install_log(schema_id) VALUES('graph/base');
