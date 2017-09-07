/*
 * Copyright 2014–2017 SlamData Inc.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package quasar.physical.rdbms.fs.postgres

import quasar.fs.FileSystemErrT
import quasar.physical.rdbms.common._
import slamdata.Predef._
import quasar.fs._
import doobie.imports._
import quasar.physical.rdbms.fs.RdbmsDescribeTable

import scalaz.EitherT
import scalaz.syntax.show._

object PostgresDescribeTable extends RdbmsDescribeTable {

  override def isJson(tablePath: TablePath): FileSystemErrT[ConnectionIO, Boolean] = {
    EitherT(sql"SELECT COLUMN_NAME, COLUMN_TYPE FROM information_schema.COLUMNS WHERE TABLE_NAME = ${tablePath.table.name}"
      .query[(String, String)]
      .list
      .map(_.equals(List(("id", "serial"), ("data", "json"))))
      .attemptSomeSqlState {
        case errorCode =>
          FileSystemError.readFailed(
            tablePath.shows,
            s"Failed to load table description from information_schema $errorCode")
      })
  }
}
