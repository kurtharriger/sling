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
import scala.collection._

package org.apache.sling.scripting.scala.interpreter {

/**
 * Bindings of names to Values
 */
trait Bindings extends Map[String, AnyRef] {

  /**
   * Associate a value with a name
   * @param name
   * @param value
   * @returns  The value which was previously associated with the
   *   given name or null if none.
   */
  def putValue(name: String, value: AnyRef): AnyRef

  /**
   * @returns  the value associated with the given name
   * @param name
   */
  def getValue(name: String): AnyRef =
    get(name) match {
      case Some(a) => a
      case None => null
    }
  
  def getViews(clazz: Class[_]) = {
    def findLeastAccessibleClass(clazz: Class[_]): Class[_] = {
      if   (accessible(clazz)) clazz
      else findLeastAccessibleClass(clazz.getSuperclass)
    }
    
    def getInterfacesUpTo(clazz: Class[_], bound: Class[_]) = {
      def getInterfacesUpTo(intfs: mutable.Set[Class[_]], clazz: Class[_], bound: Class[_]): mutable.Set[Class[_]] = 
        if (clazz == bound) intfs
        else getInterfacesUpTo(intfs ++ clazz.getInterfaces, clazz.getSuperclass, bound)
      
      getInterfacesUpTo(mutable.Set.empty, clazz, bound)
    }

    def accessible(clazz: Class[_]) = {
      try {
        Class.forName(clazz.getName)
        true
      } 
      catch { case _ => false }
    }
    
    val l = findLeastAccessibleClass(clazz) 
    var o = getInterfacesUpTo(clazz, l) 
    var v = Set.empty ++ o
    
    while (!v.isEmpty) {
      val w = v.find(_ => true).get
      val p = w.getInterfaces.filter(accessible)
      o = o -- p
      v = v - w ++ p
    }
    
    l::o.toList
  } 
}

/**
 * Default implementation of {@link Bindings} backed by a mutable Map
 */
private class BindingsWrapper(map: mutable.Map[String, AnyRef]) extends Bindings {
  def size: Int = map.size
  def get(name: String) = map.get(name)
  def elements: Iterator[(String, AnyRef)] = map.elements
  
  def putValue(name: String, value: AnyRef) = 
    map.put(name, value) match {
      case Some(a) => a
      case None => null
    }
  
}

object Bindings {
  import _root_.scala.collection.jcl.Conversions.convertMap
  
  def apply(): Bindings = new BindingsWrapper(new mutable.HashMap)
  def apply(map: mutable.Map[String, AnyRef]): Bindings = new BindingsWrapper(map)
  def apply(map: java.util.Map[String, AnyRef]): Bindings = new BindingsWrapper(map)
}

}


