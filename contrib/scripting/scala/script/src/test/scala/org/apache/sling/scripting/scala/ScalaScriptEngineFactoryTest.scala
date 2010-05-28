/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to You under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
import javax.script.{
  ScriptEngine,
  ScriptEngineFactory}

import junit.framework.TestCase
import junit.framework.Assert._

package org.apache.sling.scripting.scala {

class ScalaScriptEngineFactoryTest extends TestCase {

  def testScriptEngineFactoryInit() {
    val scalaEngineFactory = new ScalaScriptEngineFactory
    assertNotNull(scalaEngineFactory)
  }
  
  def testScriptEngineFactoryEngine() {
    try {
      val scriptEngine = (new ScalaScriptEngineFactory).getScriptEngine
      assertNotNull(scriptEngine)
    }
    catch {
      case e: IllegalStateException => // expected
    }
  }

  def testScriptEngineFactoryLanguage() {
    val language = (new ScalaScriptEngineFactory).getLanguageName
      assertEquals("Scala", language)
  }

  def testScriptEngineFactoryLanguageVersion() {
    val version = (new ScalaScriptEngineFactory).getLanguageVersion()
    assertEquals("2.7.7", version)
  }
  
}

}