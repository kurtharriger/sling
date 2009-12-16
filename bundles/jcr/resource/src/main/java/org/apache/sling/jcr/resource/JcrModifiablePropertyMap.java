/*
 * Licensed to the Apache Software Foundation (ASF) under one
 * or more contributor license agreements.  See the NOTICE file
 * distributed with this work for additional information
 * regarding copyright ownership.  The ASF licenses this file
 * to you under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance
 * with the License.  You may obtain a copy of the License at
 *
 *   http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing,
 * software distributed under the License is distributed on an
 * "AS IS" BASIS, WITHOUT WARRANTIES OR CONDITIONS OF ANY
 * KIND, either express or implied.  See the License for the
 * specific language governing permissions and limitations
 * under the License.
 */
package org.apache.sling.jcr.resource;

import javax.jcr.Node;

/**
 * This implementation of the value map allows to change
 * the properies and save them later on.
 */
public final class JcrModifiablePropertyMap
    extends org.apache.sling.jcr.resource.internal.JcrModifiablePropertyMap {

    /**
     * Constructor
     * @param node The underlying node.
     */
    public JcrModifiablePropertyMap(final Node node) {
        super(node);
    }

    /**
     * Constructor
     * @param node The underlying node.
     * @param dynamicCL Dynamic class loader for loading serialized objects.
     * @since 2.0.6
     */
    public JcrModifiablePropertyMap(final Node node, final ClassLoader dynamicCL) {
        super(node, dynamicCL);
    }
}