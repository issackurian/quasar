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

package quasar.physical.rdbms

import matryoshka.{BirecursiveT, Delay, EqualT, RecursiveT, ShowT}
import quasar.{RenderTree, RenderTreeT}
import slamdata.Predef._
import quasar.connector.BackendModule
import quasar.contrib.pathy.{AFile, APath}
import quasar.contrib.scalaz.{MonadError_, MonadReader_}
import quasar.effect.uuid.GenUUID
import quasar.effect.{KeyValueStore, MonotonicSeq}
import quasar.fp.{:/:, :\:}
import quasar.fp.free._
import quasar.fs.{FileSystemError, FileSystemType}
import quasar.fs.ReadFile.ReadHandle
import quasar.fs.mount.BackendDef.DefErrT
import quasar.fs.mount.ConnectionUri
import quasar.physical.rdbms.fs.postgres.{RdbmsReadFile, SqlReadCursor}
import quasar.qscript.{::\::, ::/::, EquiJoin, ExtractPath, Injectable, QScriptCore, QScriptTotal, ShiftedRead, Unicoalesce, Unirewrite}
import config._

import scalaz._
import Scalaz._
import scala.Predef.implicitly
import scalaz.concurrent.Task

trait Rdbms extends BackendModule with RdbmsReadFile with Interpreter {

  val MR                   = MonadReader_[Backend, Config]
  val ME                   = MonadError_[Backend, FileSystemError]

  implicit val monadMInstance: Monad[M] = MonadM

  type Eff[A] = (
    Task :\:
      MonotonicSeq :\:
      GenUUID :/:
      KeyValueStore[ReadHandle, SqlReadCursor, ?]
  )#M[A]

  type QS[T[_[_]]] = QScriptCore[T, ?] :\: EquiJoin[T, ?] :/: Const[ShiftedRead[AFile], ?]
  type Repr        = String // TODO define best Repr for a SQL query
  type M[A]        = Free[Eff, A]

  type Config = common.Config

  def FunctorQSM[T[_[_]]] = Functor[QSM[T, ?]]
  def DelayRenderTreeQSM[T[_[_]]: BirecursiveT: EqualT: ShowT: RenderTreeT] =
    implicitly[Delay[RenderTree, QSM[T, ?]]]
  def ExtractPathQSM[T[_[_]]: RecursiveT]                               = ExtractPath[QSM[T, ?], APath]
  def QSCoreInject[T[_[_]]]                                             = implicitly[QScriptCore[T, ?] :<: QSM[T, ?]]
  def MonadM                                                            = Monad[M]
  def UnirewriteT[T[_[_]]: BirecursiveT: EqualT: ShowT: RenderTreeT]    = implicitly[Unirewrite[T, QS[T]]]
  def UnicoalesceCap[T[_[_]]: BirecursiveT: EqualT: ShowT: RenderTreeT] = Unicoalesce.Capture[T, QS[T]]

  implicit def qScriptToQScriptTotal[T[_[_]]]: Injectable.Aux[
    QSM[T, ?],
    QScriptTotal[T, ?]] =
    ::\::[QScriptCore[T, ?]](::/::[T, EquiJoin[T, ?], Const[ShiftedRead[AFile], ?]])

  def parseConfig(uri: ConnectionUri): DefErrT[Task, Config] = parseUri(uri)

  def compile(cfg: Config): DefErrT[Task, (M ~> Task, Task[Unit])] =
    (interp ∘ (i => (foldMapNT[Eff, Task](i), Task.delay(())))).liftM[DefErrT]

  def plan[T[_[_]]: BirecursiveT: EqualT: ShowT: RenderTreeT](
      cp: T[QSM[T, ?]]): Backend[Repr] = ??? // TODO

  def QueryFileModule: QueryFileModule = ??? // TODO

  def WriteFileModule: WriteFileModule = ??? // TODO

  def ManageFileModule: ManageFileModule = ??? // TODO

  def includeError[A](b: Backend[FileSystemError \/ A]): Backend[A] =
    EitherT(b.run.map(_.join))
}

object Postgres extends Rdbms {
  override val Type = FileSystemType("postgres")
}
