package ygg.table

import ygg.common._
import scalaz._, Scalaz._, Ordering._
import ygg.json._

sealed trait CPath {
  def nodes: List[CPathNode]

  def parent: Option[CPath] = if (nodes.isEmpty) None else Some(CPath(nodes.init))

  def ancestors: List[CPath] = {
    def ancestors0(path: CPath, acc: List[CPath]): List[CPath] = path.parent.fold(acc)(p => ancestors0(p, p :: acc))
    ancestors0(this, Nil).reverse
  }

  def combine(paths: Seq[CPath]): Seq[CPath] = (
    if (paths.isEmpty) Seq(this)
    else paths map (p => CPath(nodes ++ p.nodes))
  )

  def \(that: CPath): CPath  = CPath(nodes ++ that.nodes)
  def \(that: String): CPath = CPath(nodes :+ CPathField(that))
  def \(that: Int): CPath    = CPath(nodes :+ CPathIndex(that))

  def \:(that: CPath): CPath  = CPath(that.nodes ++ nodes)
  def \:(that: String): CPath = CPath(CPathField(that) +: nodes)
  def \:(that: Int): CPath    = CPath(CPathIndex(that) +: nodes)

  def hasPrefix(p: CPath): Boolean = nodes.startsWith(p.nodes)
  def hasSuffix(p: CPath): Boolean = nodes.endsWith(p.nodes)

  def take(length: Int): Option[CPath] = {
    (nodes.length >= length).option(CPath(nodes.take(length)))
  }

  def dropPrefix(p: CPath): Option[CPath] = {
    def remainder(nodes: List[CPathNode], toDrop: List[CPathNode]): Option[CPath] = {
      nodes match {
        case x :: xs =>
          toDrop match {
            case `x` :: ys => remainder(xs, ys)
            case Nil       => Some(CPath(nodes))
            case _         => None
          }

        case Nil =>
          if (toDrop.isEmpty) Some(CPath(nodes))
          else None
      }
    }

    remainder(nodes, p.nodes)
  }

  def apply(index: Int): CPathNode = nodes(index)

  def extract(jvalue: JValue): JValue = {
    def extract0(path: List[CPathNode], d: JValue): JValue = path match {
      case Nil                       => d
      case CPathField(name) :: tail  => extract0(tail, d \ name)
      case CPathIndex(index) :: tail => extract0(tail, d(index))
      case head :: _                 => abort("Unexpected CPathNode " + head)
    }
    extract0(nodes, jvalue)
  }

  def head: Option[CPathNode] = nodes.headOption
  def tail: CPath             = CPath(nodes.tail: _*)

  def expand(jvalue: JValue): List[CPath] = {
    def isRegex(s: String) = s.startsWith("(") && s.endsWith(")")

    def expand0(current: List[CPathNode], right: List[CPathNode], d: JValue): List[CPath] = right match {
      case Nil                                              => CPath(current) :: Nil
      case (x @ CPathIndex(index)) :: tail                  => expand0(current :+ x, tail, jvalue(index))
      case (x @ CPathField(name)) :: tail if !isRegex(name) => expand0(current :+ x, tail, jvalue \ name)
      case (x @ CPathField(name)) :: tail =>
        val R = name.r
        val fields = jvalue match {
          case JObject(fs) => fs.toList
          case _           => Nil
        }
        fields flatMap {
          case (R(name), value) => expand0(current :+ CPathField(name), tail, value)
          case _                => Nil
        }
      case head :: _ => abort("Unexpected CPathNode " + head)
    }

    expand0(Nil, nodes, jvalue)
  }

  def path = nodes.mkString("")

  def iterator = nodes.iterator

  def length = nodes.length

  override def toString = if (nodes.isEmpty) "." else path
}

sealed trait CPathNode {
  def \(that: CPath)     = CPath(this :: that.nodes)
  def \(that: CPathNode) = CPath(this :: that :: Nil)
}

object CPathNode {
  implicit def s2PathNode(name: String): CPathNode = CPathField(name)
  implicit def i2PathNode(index: Int): CPathNode   = CPathIndex(index)

  implicit object CPathNodeOrder extends Ord[CPathNode] {
    def order(n1: CPathNode, n2: CPathNode): Cmp = (n1, n2) match {
      case (CPathField(s1), CPathField(s2)) => Cmp(s1 compare s2)
      case (CPathField(_), _)               => GT
      case (_, CPathField(_))               => LT
      case (CPathArray, CPathArray)         => EQ
      case (CPathArray, _)                  => GT
      case (_, CPathArray)                  => LT
      case (CPathIndex(i1), CPathIndex(i2)) => i1 ?|? i2
      case (CPathIndex(_), _)               => GT
      case (_, CPathIndex(_))               => LT
      case (CPathMeta(m1), CPathMeta(m2))   => Cmp(m1 compare m2)
    }
  }

  implicit val CPathNodeOrdering = CPathNodeOrder.toScalaOrdering
}

sealed case class CPathField(name: String) extends CPathNode {
  override def toString = "." + name
}

sealed case class CPathMeta(name: String) extends CPathNode {
  override def toString = "@" + name
}

sealed case class CPathIndex(index: Int) extends CPathNode {
  override def toString = "[" + index + "]"
}

case object CPathArray extends CPathNode {
  override def toString = "[*]"
}

object CPath {
  private val PathPattern  = """\.|(?=\[\d+\])|(?=\[\*\])""".r
  private val IndexPattern = """^\[(\d+)\]$""".r

  val Identity = CPath()

  type AndValue = CPath -> CValue

  private[this] case class CompositeCPath(nodes: List[CPathNode]) extends CPath

  def apply(n: CPathNode*): CPath = CompositeCPath(n.toList)

  def apply(l: List[CPathNode]): CPath = apply(l: _*)

  def apply(path: JPath): CPath = CPath(
    path.nodes map {
      case JPathField(name) => CPathField(name)
      case JPathIndex(idx)  => CPathIndex(idx)
    }: _*
  )

  def unapplySeq(path: CPath): Option[List[CPathNode]]  = Some(path.nodes)
  def unapplySeq(path: String): Option[List[CPathNode]] = Some(apply(path).nodes)

  implicit def apply(path: String): CPath = {
    def parse0(segments: List[String], acc: List[CPathNode]): List[CPathNode] = segments match {
      case Nil                               => acc
      case head :: tail if head.trim.isEmpty => parse0(tail, acc)
      case "[*]" :: tail                     => parse0(tail, CPathArray :: acc)
      case IndexPattern(index) :: tail       => parse0(tail, CPathIndex(index.toInt) :: acc)
      case name :: tail                      => parse0(tail, CPathField(name) :: acc)
    }

    val properPath = if (path.startsWith(".")) path else "." + path
    apply(parse0(PathPattern.split(properPath).toList, Nil).reverse: _*)
  }

  trait CPathTree[A]
  case class RootNode[A](children: Seq[CPathTree[A]])                     extends CPathTree[A]
  case class FieldNode[A](field: CPathField, children: Seq[CPathTree[A]]) extends CPathTree[A]
  case class IndexNode[A](index: CPathIndex, children: Seq[CPathTree[A]]) extends CPathTree[A]
  case class LeafNode[A](value: A)                                        extends CPathTree[A]

  case class PathWithLeaf[A](path: Seq[CPathNode], value: A) {
    val size: Int = path.length
  }

  def makeStructuredTree[A](pathsAndValues: Seq[CPath -> A]) = {
    def inner[A](paths: Seq[PathWithLeaf[A]]): Seq[CPathTree[A]] = {
      if (paths.size == 1 && paths.head.size == 0) {
        List(LeafNode(paths.head.value))
      } else {
        val filtered = paths filterNot { case PathWithLeaf(path, _)  => path.isEmpty }
        val grouped  = filtered groupBy { case PathWithLeaf(path, _) => path.head }

        def recurse[A](paths: Seq[PathWithLeaf[A]]) =
          inner(paths map { case PathWithLeaf(path, v) => PathWithLeaf(path.tail, v) })

        val result = grouped.toSeq.sortBy(_._1) map {
          case (node, paths) =>
            node match {
              case (field: CPathField) => FieldNode(field, recurse(paths))
              case (index: CPathIndex) => IndexNode(index, recurse(paths))
              case _                   => abort("CPathArray and CPathMeta not supported")
            }
        }
        result
      }
    }

    val leaves = pathsAndValues.sortBy(_._1) map {
      case (path, value) =>
        PathWithLeaf[A](path.nodes, value)
    }

    RootNode(inner(leaves))
  }

  def makeTree[A](cpaths0: Seq[CPath], values: Seq[A]): CPathTree[A] = {
    if (cpaths0.isEmpty && values.length == 1)
      RootNode(Seq(LeafNode(values.head)))
    else if (cpaths0.length == values.length)
      makeStructuredTree(cpaths0.sorted zip values)
    else
      RootNode(Seq.empty[CPathTree[A]])
  }

  implicit def singleNodePath(node: CPathNode): CPath = CPath(node)

  implicit val CPathOrder: Ord[CPath] = new Ord[CPath] {
    def order(v1: CPath, v2: CPath): Cmp = {
      def compare0(n1: List[CPathNode], n2: List[CPathNode]): Cmp = (n1, n2) match {
        case (Nil, Nil) => EQ
        case (Nil, _)   => LT
        case (_, Nil)   => GT
        case (n1 :: ns1, n2 :: ns2) =>
          val ncomp = Ord[CPathNode].order(n1, n2)
          if (ncomp != EQ) ncomp else compare0(ns1, ns2)
      }

      compare0(v1.nodes, v2.nodes)
    }
  }
}
