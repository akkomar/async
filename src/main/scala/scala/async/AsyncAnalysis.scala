package scala.async

import scala.reflect.macros.Context
import collection.mutable

private[async] final case class AsyncAnalysis[C <: Context](override val c: C) extends TransformUtils(c) {
  import c.universe._

  /**
   * Analyze the contents of an `async` block in order to:
   * - Report unsupported `await` calls under nested templates, functions, by-name arguments.
   *
   * Must be called on the original tree, not on the ANF transformed tree.
   */
  def reportUnsupportedAwaits(tree: Tree) {
    new UnsupportedAwaitAnalyzer().traverse(tree)
  }

  /**
   * Analyze the contents of an `async` block in order to:
   * - Find which local `ValDef`-s need to be lifted to fields of the state machine, based
   * on whether or not they are accessed only from a single state.
   *
   * Must be called on the ANF transformed tree.
   */
  def valDefsUsedInSubsequentStates(tree: Tree): List[ValDef] = {
    val analyzer = new AsyncDefinitionUseAnalyzer
    analyzer.traverse(tree)
    analyzer.valDefsToLift.toList
  }

  private class UnsupportedAwaitAnalyzer extends super.AsyncTraverser {
    override def nestedClass(classDef: ClassDef) {
      val kind = if (classDef.symbol.asClass.isTrait) "trait" else "class"
      if (!reportUnsupportedAwait(classDef, s"nested $kind")) {
        // do not allow local class definitions, because of SI-5467 (specific to case classes, though)
        c.error(classDef.pos, s"Local class ${classDef.name.decoded} illegal within `async` block")
      }
    }

    override def nestedModule(module: ModuleDef) {
      if (!reportUnsupportedAwait(module, "nested object")) {
        // local object definitions lead to spurious type errors (because of resetAllAttrs?)
        c.error(module.pos, s"Local object ${module.name.decoded} illegal within `async` block")
      }
    }

    override def byNameArgument(arg: Tree) {
      reportUnsupportedAwait(arg, "by-name argument")
    }

    override def function(function: Function) {
      reportUnsupportedAwait(function, "nested function")
    }

    /**
     * @return true, if the tree contained an unsupported await.
     */
    private def reportUnsupportedAwait(tree: Tree, whyUnsupported: String): Boolean = {
      val badAwaits: List[RefTree] = tree collect {
        case rt: RefTree if isAwait(rt) => rt
      }
      badAwaits foreach {
        tree =>
          c.error(tree.pos, s"await must not be used under a $whyUnsupported.")
      }
      badAwaits.nonEmpty
   }
  }

  private class AsyncDefinitionUseAnalyzer extends super.AsyncTraverser {
    private var chunkId = 0

    private def nextChunk() = chunkId += 1

    private var valDefChunkId = Map[Symbol, (ValDef, Int)]()

    val valDefsToLift: mutable.Set[ValDef] = collection.mutable.Set[ValDef]()

    override def traverse(tree: Tree) = {
      tree match {
        case If(cond, thenp, elsep) if tree exists isAwait =>
          traverseChunks(List(cond, thenp, elsep))
        case Match(selector, cases) if tree exists isAwait =>
          traverseChunks(selector :: cases)
        case Apply(fun, args) if isAwait(fun)              =>
          super.traverse(tree)
          nextChunk()
        case vd: ValDef                                    =>
          super.traverse(tree)
          valDefChunkId += (vd.symbol ->(vd, chunkId))
          if (isAwait(vd.rhs)) valDefsToLift += vd
        case as: Assign                                    =>
          if (isAwait(as.rhs)) {
            assert(as.symbol != null, "internal error: null symbol for Assign tree:" + as)

            // TODO test the orElse case, try to remove the restriction.
            val (vd, defBlockId) = valDefChunkId.getOrElse(as.symbol, c.abort(as.pos, "await may only be assigned to a var/val defined in the async block. " + as.symbol))
            valDefsToLift += vd
          }
          super.traverse(tree)
        case rt: RefTree                                   =>
          valDefChunkId.get(rt.symbol) match {
            case Some((vd, defChunkId)) if defChunkId != chunkId =>
              valDefsToLift += vd
            case _                                               =>
          }
          super.traverse(tree)
        case _                                             => super.traverse(tree)
      }
    }

    private def traverseChunks(trees: List[Tree]) {
      trees.foreach {
        t => traverse(t); nextChunk()
      }
    }
  }
}
