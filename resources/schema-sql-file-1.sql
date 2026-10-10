-- Copyright (c) 2026 Michael Schaeffer
--
-- Licensed under the Apache License, Version 2.0 (the "License")--
-- you may not use this file except in compliance with the License.
-- You may obtain a copy of the License at
--
--       http://www.apache.org/licenses/LICENSE-2.0
--
-- The license is also includes at the root of the project in the file
-- LICENSE.
--
-- Unless required by applicable law or agreed to in writing, software
-- distributed under the License is distributed on an "AS IS" BASIS,
-- WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
-- See the License for the specific language governing permissions and
-- limitations under the License.
--
-- You must not remove this notice, or any other, from this software.

-- One row per installed schema script. See docs/schema-graph.md.

CREATE CACHED TABLE sql_file_installed (
  schema_id VARCHAR(255) NOT NULL PRIMARY KEY,
  how VARCHAR(16) NOT NULL,
  requires VARCHAR(4096) NULL,
  replaces VARCHAR(4096) NULL,
  digest CHAR(64) NULL,
  statements_tracked BOOLEAN DEFAULT FALSE NOT NULL,
  started_on TIMESTAMP NULL,
  completed_on TIMESTAMP NULL
);

-- One row per successfully applied statement, when statement tracking
-- is enabled.

CREATE CACHED TABLE sql_file_statement (
  schema_id VARCHAR(255) NOT NULL,
  ordinal INTEGER NOT NULL,
  digest CHAR(64) NOT NULL,
  completed_on TIMESTAMP NOT NULL,

  PRIMARY KEY (schema_id, ordinal)
);
