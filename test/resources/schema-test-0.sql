-- Copyright (c) 2014 KSM Technology Partners
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

-- schema-test-0.sql
--
-- Schema version 0 for test purposes.

CREATE CACHED TABLE test_point (
  x INT NOT NULL,
  y INT NOT NULL
);

INSERT INTO test_point(x, y) values(1, 10);
INSERT INTO test_point(x, y) values(2, 20);
INSERT INTO test_point(x, y) values(3, 30);
INSERT INTO test_point(x, y) values(4, 40);

