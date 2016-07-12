/*
 *  ____    ____    _____    ____    ___     ____
 * |  _ \  |  _ \  | ____|  / ___|  / _/    / ___|        Precog (R)
 * | |_) | | |_) | |  _|   | |     | |  /| | |  _         Advanced Analytics Engine for NoSQL Data
 * |  __/  |  _ <  | |___  | |___  |/ _| | | |_| |        Copyright (C) 2010 - 2013 SlamData, Inc.
 * |_|     |_| \_\ |_____|  \____|   /__/   \____|        All Rights Reserved.
 *
 * This program is free software: you can redistribute it and/or modify it under the terms of the
 * GNU Affero General Public License as published by the Free Software Foundation, either version
 * 3 of the License, or (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful, but WITHOUT ANY WARRANTY;
 * without even the implied warranty of MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See
 * the GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License along with this
 * program. If not, see <http://www.gnu.org/licenses/>.
 *
 */
package com.precog.yggdrasil
package execution

import com.precog.yggdrasil.vfs._
import blueeyes._
import com.precog.common._

import blueeyes.core.http.MimeType
import blueeyes.core.http.MimeTypes

import scalaz._
import scalaz.NonEmptyList.nels

sealed trait EvaluationError
case class InvalidStateError(message: String)                       extends EvaluationError
case class StorageError(error: ResourceError)                       extends EvaluationError
case class SystemError(error: Throwable)                            extends EvaluationError
case class AccumulatedErrors(errors: NonEmptyList[EvaluationError]) extends EvaluationError

object EvaluationError {
  def invalidState(message: String): EvaluationError              = InvalidStateError(message)
  def storageError(error: ResourceError): EvaluationError         = StorageError(error)
  def systemError(error: Throwable): EvaluationError              = SystemError(error)
  def acc(errors: NonEmptyList[EvaluationError]): EvaluationError = AccumulatedErrors(errors)

  implicit val semigroup: Semigroup[EvaluationError] = new Semigroup[EvaluationError] {
    def append(a: EvaluationError, b: => EvaluationError) = (a, b) match {
      case (AccumulatedErrors(a0), AccumulatedErrors(b0)) => AccumulatedErrors(a0 append b0)
      case (a0, AccumulatedErrors(b0))                    => AccumulatedErrors(a0 <:: b0)
      case (AccumulatedErrors(a0), b0)                    => AccumulatedErrors(b0 <:: a0)
      case (a0, b0)                                       => AccumulatedErrors(nels(a0, b0))
    }
  }
}

case class QueryOptions(
    page: Option[(Long, Long)] = None,
    sortOn: List[CPath] = Nil,
    sortOrder: TableModule.DesiredSortOrder = TableModule.SortAscending,
    timeout: Option[Duration] = None,
    output: MimeType = MimeTypes.application / MimeTypes.json,
    cacheControl: CacheControl = CacheControl.NoCache
)

case class CacheControl(maxAge: Option[Long], recacheAfter: Option[Long], cacheable: Boolean, onlyIfCached: Boolean)

object CacheControl {
  val NoCache = CacheControl(None, None, false, false)
}

trait QueryExecutor[M[+ _], +A] { self =>
  def execute(query: String, context: EvaluationContext, opts: QueryOptions): EitherT[M, EvaluationError, A]

  def map[B](f: A => B)(implicit M: Functor[M]): QueryExecutor[M, B] = new QueryExecutor[M, B] {
    def execute(query: String, context: EvaluationContext, opts: QueryOptions): EitherT[M, EvaluationError, B] = {
      self.execute(query, context, opts) map f
    }
  }
}
