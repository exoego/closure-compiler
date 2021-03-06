/*
 * Copyright 2020 The Closure Compiler Authors.
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
package com.google.javascript.jscomp;

import static com.google.common.base.Preconditions.checkArgument;
import static com.google.common.base.Preconditions.checkNotNull;
import static com.google.common.base.Preconditions.checkState;

import com.google.javascript.jscomp.parsing.parser.FeatureSet.Feature;
import com.google.javascript.rhino.IR;
import com.google.javascript.rhino.Node;
import com.google.javascript.rhino.Token;
import java.util.ArrayDeque;

/**
 * Rewrites a single optional chain as one or more nested hook expressions.
 *
 * <p>Optional chains contained in OPTCHAIN_GETELEM indices or OPTCHAIN_CALL arguments are not
 * rewritten.
 *
 * <p>Example:
 *
 * <pre><code>
 *   a?.b[obj?.index]?.c?.(obj?.arg)
 *   // becomes
 *   (tmp0 = a) == null
 *       ? void 0
 *       : (tmp1 = tmp0.b[obj?.index]) == null
 *           ? void 0
 *           : (tmp2 = tmp1.c) == null
 *               ? void 0
 *               : tmp2.call(tmp1, obj?.arg);
 * </code></pre>
 *
 * <p>The unit tests for this class are in RewriteOptionalChainingOperatorTest, because it's most
 * convenient to test this class as part of the transpilation pass that uses it.
 */
class OptionalChainRewriter {
  final AbstractCompiler compiler;
  final AstFactory astFactory;
  final TmpVarNameCreator tmpVarNameCreator;
  final Node chainParent;
  final Node wholeChain;
  final Node enclosingStatement;

  /** Creates unique names to be used for temporary variables. */
  interface TmpVarNameCreator {

    /** Creates a unique temporary variable name each time it is called. */
    String createTmpVarName();
  }

  static class Builder {
    final AbstractCompiler compiler;
    final AstFactory astFactory;
    TmpVarNameCreator tmpVarNameCreator;

    private Builder(AbstractCompiler compiler) {
      this.compiler = checkNotNull(compiler);
      this.astFactory = compiler.createAstFactory();
    }

    Builder setTmpVarNameCreator(TmpVarNameCreator tmpVarNameCreator) {
      this.tmpVarNameCreator = checkNotNull(tmpVarNameCreator);
      return this;
    }

    /** @param wholeChain The last Node in the optional chain. Parent of all the rest. */
    OptionalChainRewriter build(Node wholeChain) {
      return new OptionalChainRewriter(this, wholeChain);
    }
  }

  static Builder builder(AbstractCompiler compiler) {
    return new Builder(compiler);
  }

  private OptionalChainRewriter(Builder builder, Node wholeChain) {
    // This class will only operate on an entire chain.
    checkArgument(NodeUtil.isEndOfFullOptChain(wholeChain), wholeChain);
    this.compiler = builder.compiler;
    this.astFactory = builder.astFactory;
    this.tmpVarNameCreator = checkNotNull(builder.tmpVarNameCreator);
    this.wholeChain = wholeChain;
    this.chainParent = checkNotNull(wholeChain.getParent(), wholeChain);
    this.enclosingStatement = NodeUtil.getEnclosingStatement(wholeChain);
  }

  /** Rewrites the optional chain as a hook with temporary variables introduced as needed. */
  void rewrite() {
    checkState(NodeUtil.isOptChainNode(wholeChain), "already rewritten: %s", wholeChain);

    // `first?.start.second?.start`
    // We search from the end of the chain and push the start nodes onto the stack, so the first
    // one ends up on top.
    ArrayDeque<Node> startNodeStack = new ArrayDeque<>();
    Node subchainEnd = wholeChain;
    while (NodeUtil.isOptChainNode(subchainEnd)) {
      final Node subchainStart = NodeUtil.getStartOfOptChainSegment(subchainEnd);
      startNodeStack.push(subchainStart);
      subchainEnd = subchainStart.getFirstChild();
    }

    checkState(!startNodeStack.isEmpty());
    // Each time we rewrite the initial segment of the chain, the remaining chain gets wrapped
    // in a hook statement like `(tmp0 = a.b) == null ? void 0 : tmp0.rest?.of.chain?.()`,
    // So wholeChain ends up more deeply nested on each rewrite.
    // We only care about the top-most replacement here.
    final Node optChainReplacement = rewriteInitialSegment(startNodeStack.pop(), wholeChain);
    while (!startNodeStack.isEmpty()) {
      rewriteInitialSegment(startNodeStack.pop(), wholeChain);
    }

    // Handle a non-optional call to an optional chain that ends in an element or property
    // access.
    // `(a?.optional.chain)(arg1)`
    // Writing JavaScript code like this is a bad idea, but it might get automatically
    // generated, so we must handle it.
    // The optional chain could evaluate to `undefined`, which we then try to call as a
    // function. However, if it isn't undefined, we have to preserve the correct `this` value
    // for the call.
    if (chainParent.isCall()
        // The chain will have been replaced by optChainReplacement during the rewriting above.
        && optChainReplacement.isFirstChildOf(chainParent)
        // The wholeChain variable will still point to the rewritten final Node of the
        // chain. It will no longer be optional.
        && NodeUtil.isNormalGet(wholeChain)) {
      final Node thisValue = wholeChain.getFirstChild();
      final Node tmpThisNode = getSubExprNameNode(thisValue);
      optChainReplacement.detach();
      chainParent.addChildToFront(tmpThisNode);
      final Node dotCallNode =
          astFactory
              .createGetProp(optChainReplacement, "call")
              .useSourceInfoIfMissingFromForTree(optChainReplacement);
      chainParent.addChildToFront(dotCallNode);
    }

    // Transpilation of the optional chain adds `let` declarations for temporary variables.
    // NOTE: If this class is being used before transpilation, it's OK to use `let`, since it will
    // be transpiled away, if necessary. If it is being used after transpilation, then using `let`
    // must be OK, because optional chains weren't transpiled away and `let` existed before they
    // did.
    final Node enclosingScript = NodeUtil.getEnclosingScript(enclosingStatement);
    NodeUtil.addFeatureToScript(enclosingScript, Feature.LET_DECLARATIONS, compiler);

    compiler.reportChangeToEnclosingScope(chainParent);
  }

  /**
   * Rewrites the first part of a possibly-multi-part optional chain.
   *
   * <p>e.g.
   *
   * <pre>{@code
   * a()?.b.c?.d;
   * // becomes
   * let tmp0;
   * (tmp0 = a()) == null
   *     ? void 0
   *     : tmp0.b.c?d;
   * }</pre>
   *
   * @param fullChainStart The very first `?.` node
   * @param fullChainEnd The very last optional chain node.
   * @return The hook expression that replaced the chain.
   */
  private Node rewriteInitialSegment(final Node fullChainStart, final Node fullChainEnd) {
    // `receiverNode?.restOfChain`
    Node receiverNode = fullChainStart.getFirstChild();
    // for `a?.b.c?.d`, this will be `a?.b.c`, because the NodeUtil method finds the end
    // of the sub-chain, not the full chain.
    final Node initialChainEnd = NodeUtil.getEndOfOptChainSegment(fullChainStart);

    // If the receiver is an optional chain, we weren't really given the start of a full
    // chain.
    checkArgument(!NodeUtil.isOptChainNode(receiverNode), receiverNode);

    // change the initial chain's nodes to be non-optional
    convertToNonOptionalChainSegment(initialChainEnd);

    final Node placeholder = IR.empty();
    fullChainEnd.replaceWith(placeholder);
    // NOTE: convertToNonOptionalChain() above will have made the chain start
    // and all the other nodes in the first segment of the chain non-optional,
    // so fullChainStart.isCall() is the right test here.
    if (NodeUtil.isNormalGet(receiverNode) && fullChainStart.isCall()) {
      // `expr.prop?.(x).y`
      // Needs to become
      // `(t1 = (t0 = expr).prop) == null ? void 0 : t1.call(t0, x).y`
      final Node thisValue = receiverNode.getFirstChild();
      final Node tmpThisNode = getSubExprNameNode(thisValue);
      final Node tmpReceiverNode = getSubExprNameNode(receiverNode);
      receiverNode = fullChainStart.getFirstChild().detach();
      fullChainStart.addChildToFront(tmpThisNode);
      fullChainStart.addChildToFront(
          astFactory
              .createGetProp(tmpReceiverNode, "call")
              .useSourceInfoIfMissingFromForTree(receiverNode));
    } else {
      // `expr?.x.y`
      // needs to become
      // `((t0 = expr) == null) ? void 0 : t0.x.y`
      final Node tmpReceiverNode = getSubExprNameNode(receiverNode);
      receiverNode = fullChainStart.getFirstChild();
      receiverNode.replaceWith(tmpReceiverNode);
    }
    final Node optChainReplacement =
        astFactory
            .createHook(
                astFactory.createEq(receiverNode, astFactory.createNull()),
                astFactory.createUndefinedValue(),
                fullChainEnd)
            .useSourceInfoIfMissingFromForTree(fullChainEnd);
    placeholder.replaceWith(optChainReplacement);

    return optChainReplacement;
  }

  /**
   * Given an expression node, declare a temporary variable to hold that expression and replace the
   * expression with `(tmp = expr)`.
   *
   * <p>e.g. `subExpr.moreExpr` becomes `(tmp = subExpr).moreExpr`, and `let tmp;` gets inserted
   * before the enclosing statement of this optional chain.
   *
   * @param subExpr The sub expression Node
   * @return A detached NAME node for the temporary variable name and with source info and type
   *     matching `subExpr`, that may be inserted where needed.
   */
  Node getSubExprNameNode(Node subExpr) {
    String tempVarName = declareTempVarName(subExpr);
    Node placeholder = IR.empty();
    subExpr.replaceWith(placeholder);
    Node replacement =
        astFactory.createAssign(tempVarName, subExpr).useSourceInfoIfMissingFromForTree(subExpr);
    placeholder.replaceWith(replacement);
    return replacement.getFirstChild().cloneNode();
  }

  /**
   * Declare a temporary variable name that will be used to hold the given value.
   *
   * <p>The generated declaration has no assignment, it's just `let tmp;`.
   *
   * @param valueNode A node from which to copy the source info and type to be used for the new
   *     variable.
   * @return the name used for the new temporary variable.
   */
  String declareTempVarName(Node valueNode) {
    String tempVarName = tmpVarNameCreator.createTmpVarName();
    Node declarationStatement =
        astFactory.createSingleLetNameDeclaration(tempVarName).srcrefTree(valueNode);
    enclosingStatement.getParent().addChildBefore(declarationStatement, enclosingStatement);
    return tempVarName;
  }

  /**
   * Given the end of an optional chain segment. Change all nodes from the end down to the start
   * into non-optional nodes.
   */
  private static void convertToNonOptionalChainSegment(Node endOfOptChainSegment) {
    // Since part of changing the nodes removes the isOptionalChainStart() marker we look for to
    // know we're done, this logic is easier to read if we just find all the nodes first, then
    // change them.
    final ArrayDeque<Node> segmentNodes = new ArrayDeque<>();
    Node segmentNode = endOfOptChainSegment;
    while (true) {
      checkState(NodeUtil.isOptChainNode(segmentNode), segmentNode);
      segmentNodes.add(segmentNode);
      if (segmentNode.isOptionalChainStart()) {
        break;
      } else {
        segmentNode = segmentNode.getFirstChild();
      }
    }
    for (Node n : segmentNodes) {
      n.setIsOptionalChainStart(false);
      n.setToken(getNonOptChainToken(n.getToken()));
    }
  }

  private static Token getNonOptChainToken(Token optChainToken) {
    switch (optChainToken) {
      case OPTCHAIN_CALL:
        return Token.CALL;
      case OPTCHAIN_GETELEM:
        return Token.GETELEM;
      case OPTCHAIN_GETPROP:
        return Token.GETPROP;
      default:
        throw new IllegalStateException("Should be an OPTCHAIN token: " + optChainToken);
    }
  }
}
