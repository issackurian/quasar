/*
 * Copyright 2014–2018 SlamData Inc.
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

package quasar.contrib.cats

import slamdata.Predef.AnyVal

import cats.effect._
import cats.syntax.functor._

import scala.concurrent.Future

package object effect {

  implicit class toOps[F[_], A](val fa: F[A]) extends AnyVal {
    def to[G[_]](implicit F: Effect[F], G: Async[G]): G[A] =
      Async[G].async { l =>
        Effect[F].runAsync(fa)(c =>
          IO(l(c))
        ).unsafeRunSync
      }
  }

  implicit class IOOps(val self: IO.type) extends AnyVal {
    def fromFutureShift[A](iofa: IO[Future[A]])(implicit T: Timer[IO]): IO[A] =
      IO.fromFuture(iofa).flatMap(a => IO.shift.as(a))
  }
}
