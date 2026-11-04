/*
 * Copyright (C) 2026 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.android.tools.idea.room.migrations.json

import com.android.tools.idea.room.generators.TestUtils.createDatabaseBundle
import com.android.tools.idea.room.generators.TestUtils.createEntityBundle
import com.android.tools.idea.room.generators.TestUtils.createFieldBundle
import com.google.common.truth.Truth.assertThat
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4

@RunWith(JUnit4::class)
class DatabaseBundleTest {
  @Test
  fun isSchemaEqual_sameEntities_returnsTrue() {
    val db1 =
      createDatabaseBundle(
        1,
        createEntityBundle("table1", createFieldBundle("column1"), createFieldBundle("column2")),
        createEntityBundle("table2", createFieldBundle("column1")),
      )
    // Same schema, different version and entity order.
    val db2 =
      createDatabaseBundle(
        2,
        createEntityBundle("table2", createFieldBundle("column1")),
        createEntityBundle("table1", createFieldBundle("column1"), createFieldBundle("column2")),
      )

    assertThat(db1.isSchemaEqual(db2)).isTrue()
    assertThat(db2.isSchemaEqual(db1)).isTrue()
  }

  @Test
  fun isSchemaEqual_differentFields_returnsFalse() {
    val db1 = createDatabaseBundle(1, createEntityBundle("table1", createFieldBundle("column1")))
    val db2 =
      createDatabaseBundle(
        2,
        createEntityBundle("table1", createFieldBundle("column1"), createFieldBundle("column2")),
      )

    assertThat(db1.isSchemaEqual(db2)).isFalse()
    assertThat(db2.isSchemaEqual(db1)).isFalse()
  }

  @Test
  fun isSchemaEqual_differentTables_returnsFalse() {
    val db1 = createDatabaseBundle(1, createEntityBundle("table1", createFieldBundle("column1")))
    val db2 = createDatabaseBundle(1, createEntityBundle("table2", createFieldBundle("column1")))

    assertThat(db1.isSchemaEqual(db2)).isFalse()
  }
}
