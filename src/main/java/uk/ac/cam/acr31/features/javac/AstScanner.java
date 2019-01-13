/*
 * Copyright © 2018 Andrew Rice (acr31@cam.ac.uk)
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

package uk.ac.cam.acr31.features.javac;

import com.google.common.base.CaseFormat;
import com.sun.source.tree.CompilationUnitTree;
import com.sun.tools.javac.code.Flags;
import com.sun.tools.javac.code.Symbol;
import com.sun.tools.javac.tree.EndPosTable;
import com.sun.tools.javac.tree.JCTree;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import uk.ac.cam.acr31.features.javac.graph.FeatureGraph;
import uk.ac.cam.acr31.features.javac.proto.GraphProtos;
import uk.ac.cam.acr31.features.javac.proto.GraphProtos.FeatureEdge.EdgeType;
import uk.ac.cam.acr31.features.javac.proto.GraphProtos.FeatureNode.NodeType;

class AstScanner {

  private final FeatureGraph featureGraph;
  private final EndPosTable endPosTable;

  private AstScanner(FeatureGraph featureGraph, EndPosTable endPosTable) {
    this.featureGraph = featureGraph;
    this.endPosTable = endPosTable;
  }

  static void addToGraph(JCTree.JCCompilationUnit compilationUnit, FeatureGraph featureGraph) {

    AstScanner scanner = new AstScanner(featureGraph, compilationUnit.endPositions);
    try {
      scanner.scanOrThrow(compilationUnit, null);
    } catch (InvocationTargetException | IllegalAccessException e) {
      throw new RuntimeException(e);
    }
  }

  private void scanOrThrow(JCTree node, GraphProtos.FeatureNode parent)
      throws InvocationTargetException, IllegalAccessException {

    GraphProtos.FeatureNode newNode =
        featureGraph.createFeatureNode(NodeType.AST_ELEMENT, node.getKind().toString(), node);
    if (parent != null) {
      featureGraph.addEdge(parent, newNode, EdgeType.AST_CHILD);
    } else {
      featureGraph.setAstRoot(newNode);
    }
    // TODO(acr31) check this implements Tree
    Class<?> treeInterface = node.getClass().getInterfaces()[0];

    for (Method m : treeInterface.getDeclaredMethods()) {
      if (m.getParameterCount() != 0) {
        continue;
      }

      // avoid methods which would involve visiting some part of the tree twice
      if (CompilationUnitTree.class.isAssignableFrom(treeInterface)
          && m.getName().equals("getPackageName")) {
        continue;
      }

      Object result = m.invoke(node);

      Deque<JCTree> toProcess = new ArrayDeque<>();
      if (result instanceof JCTree) {
        if (inSource((JCTree) result)) {
          toProcess.add((JCTree) result);
        }
      }

      if (result instanceof List) {
        for (Object o : (List<?>) result) {
          if (o instanceof JCTree && inSource((JCTree) o)) {
            toProcess.add((JCTree) o);
          }
        }
      }

      if (!toProcess.isEmpty()) {
        JCTree firstChild = toProcess.peekFirst();
        JCTree lastChild = toProcess.peekLast();
        GraphProtos.FeatureNode holderNode =
            featureGraph.createFeatureNode(
                NodeType.FAKE_AST,
                methodNameToNodeType(m.getName()),
                firstChild.getStartPosition(),
                lastChild.getEndPosition(endPosTable));
        featureGraph.addEdge(newNode, holderNode, EdgeType.AST_CHILD);
        for (JCTree t : toProcess) {
          scanOrThrow(t, holderNode);
        }
      }
    }
  }

  private static String methodNameToNodeType(String name) {
    return CaseFormat.UPPER_CAMEL.to(CaseFormat.UPPER_UNDERSCORE, name.replaceAll("^get", ""));
  }

  private static boolean inSource(JCTree child) {
    if (child instanceof JCTree.JCMethodDecl) {
      Symbol sym = ((JCTree.JCMethodDecl) child).sym;
      if (sym != null) {
        boolean synthetic = (sym.flags() & Flags.SYNTHETIC) != 0;
        boolean generatedConstructor = (sym.flags() & Flags.GENERATEDCONSTR) != 0;
        return !synthetic && !generatedConstructor;
      }
    }
    return true;
  }
}
