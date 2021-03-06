/*
 * Copyright 2015 The Closure Compiler Authors.
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

package com.google.javascript.jscomp.gwt.linker;

import static com.google.common.base.Preconditions.checkArgument;

import com.google.gwt.core.ext.LinkerContext;
import com.google.gwt.core.ext.TreeLogger;
import com.google.gwt.core.ext.UnableToCompleteException;
import com.google.gwt.core.ext.linker.AbstractLinker;
import com.google.gwt.core.ext.linker.ArtifactSet;
import com.google.gwt.core.ext.linker.CompilationResult;
import com.google.gwt.core.ext.linker.LinkerOrder;
import com.google.gwt.core.ext.linker.LinkerOrder.Order;
import com.google.gwt.core.linker.SymbolMapsLinker;

/**
 * Simple single-script linker that doesn't add any dependencies on the browser.
 *
 * This is intended to generate JS for servers, Node, or in a self-contained way inside browsers. It
 * doesn't support permutations, nor does it allow late-loading code.
 */
@LinkerOrder(Order.PRIMARY)
public class MinimalLinker extends AbstractLinker {

  /**
   * Formats the application's JS code for output.
   *
   * @param js Code to format.
   * @return Formatted, linked code.
   */
  private static String formatOutput(String js) {
    StringBuilder output = new StringBuilder();

    // Shadow window so that non-browser environments can pass their own global object here.
    output.append("(function(window){");

    // $wnd is set to this, which should allow JSInterop's normal export to run and
    // pollute the global namespace.
    output.append("var $wnd=this;");

    // Shadow $doc, $moduleName and $moduleBase.
    output.append("var $doc={},$moduleName,$moduleBase;");

    // Append output JS.
    output.append(js);

    // 1. Export $gwtExport, needed for transpile.js
    // 2. Reset $wnd (nb. this occurs after jscompiler's JS has run)
    // 3. Call gwtOnLoad, if defined: this invokes onModuleLoad for all loaded modules.
    output.append("this['$gwtExport']=$wnd;$wnd=this;typeof gwtOnLoad==='function'&&gwtOnLoad()");

    // Overspecify the global object, to capture Node and browser environments.
    String globalObject = "this&&this.self||"
        + "(typeof window!=='undefined'?window:(typeof global!=='undefined'?global:this))";

    // Call the outer function with the global object as this and its first argument, so that we
    // fake window in Node environments, allowing our code to do things like "window.console(...)".
    output.append("}).call(").append(globalObject).append(",").append(globalObject).append(");");

    return output.toString();
  }

  @Override
  public String getDescription() {
    return "Minimal";
  }

  @Override
  public ArtifactSet link(TreeLogger logger, LinkerContext context, ArtifactSet artifacts)
      throws UnableToCompleteException {
    ArtifactSet toReturn = link(logger, context, artifacts, true);
    toReturn = link(logger, context, toReturn, false);
    return toReturn;
  }

  @Override
  public ArtifactSet link(
      TreeLogger logger, LinkerContext context, ArtifactSet artifacts, boolean onePermutation)
      throws UnableToCompleteException {
    ArtifactSet toReturn = new ArtifactSet(artifacts);
    ArtifactSet writableArtifacts = new ArtifactSet(artifacts);

    for (CompilationResult result : toReturn.find(CompilationResult.class)) {
      String[] js = result.getJavaScript();
      checkArgument(js.length == 1, "MinimalLinker doesn't support GWT.runAsync");

      String output = formatOutput(js[0]);
      toReturn.add(emitString(logger, output, context.getModuleName() + ".js"));
    }

    for (SymbolMapsLinker.ScriptFragmentEditsArtifact ea :
        writableArtifacts.find(SymbolMapsLinker.ScriptFragmentEditsArtifact.class)) {
      toReturn.add(ea);
    }
    return toReturn;
  }

  @Override
  public boolean supportsDevModeInJunit(LinkerContext context) {
    return false;
  }
}
