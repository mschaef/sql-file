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

-- sql-file: replaces legacy 1
-- sql-file: replaces other 0

CREATE CACHED TABLE legacy_a (x INT NOT NULL);
CREATE CACHED TABLE legacy_b (y INT NOT NULL);
CREATE CACHED TABLE other_a (w INT NOT NULL);
