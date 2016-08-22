package ygg.table

import ygg.common._
import scalaz.Ordering._

trait RowComparator { self =>
  def compare(i1: Int, i2: Int): Cmp

  def swap: RowComparator = new RowComparator {
    def compare(i1: Int, i2: Int) = self.compare(i2, i1).complement
  }

  @tailrec
  final def nextLeftIndex(lmin: Int, lmax: Int, ridx: Int): Int = {
    compare(lmax, ridx) match {
      case LT => lmax + 1
      case GT =>
        if (lmax - lmin <= 1) {
          compare(lmin, ridx) match {
            case LT      => lmax
            case GT | EQ => lmin
          }
        } else {
          val lmid = lmin + ((lmax - lmin) / 2)
          compare(lmid, ridx) match {
            case LT      => nextLeftIndex(lmid + 1, lmax, ridx)
            case GT | EQ => nextLeftIndex(lmin, lmid - 1, ridx)
          }
        }

      case EQ =>
        if (lmax - lmin <= 1) {
          compare(lmin, ridx) match {
            case LT      => lmax
            case GT | EQ => lmin
          }
        } else {
          val lmid = lmin + ((lmax - lmin) / 2)
          compare(lmid, ridx) match {
            case LT => nextLeftIndex(lmid + 1, lmax, ridx)
            case GT => abort("inputs on the left not sorted.")
            case EQ => nextLeftIndex(lmin, lmid - 1, ridx)
          }
        }
    }
  }
}
