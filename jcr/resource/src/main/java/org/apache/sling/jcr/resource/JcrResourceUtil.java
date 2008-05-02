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

import javax.jcr.Property;
import javax.jcr.PropertyType;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Value;
import javax.jcr.query.Query;
import javax.jcr.query.QueryManager;
import javax.jcr.query.QueryResult;

import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.jcr.resource.internal.helper.LazyInputStream;

/**
 * The <code>JcrResourceUtil</code> class provides helper methods used
 * throughout this bundle.
 */
public class JcrResourceUtil {

    /** Helper method to execute a JCR query */
    public static QueryResult query(Session session, String query,
            String language) throws RepositoryException {
        QueryManager qManager = session.getWorkspace().getQueryManager();
        Query q = qManager.createQuery(query, language);
        return q.execute();
    }

    /** Converts a JCR Value to a corresponding Java Object */
    public static Object toJavaObject(Value value) throws RepositoryException {
        switch (value.getType()) {
            case PropertyType.BINARY:
                return new LazyInputStream(value);
            case PropertyType.BOOLEAN:
                return value.getBoolean();
            case PropertyType.DATE:
                return value.getDate();
            case PropertyType.DOUBLE:
                return value.getDouble();
            case PropertyType.LONG:
                return value.getLong();
            case PropertyType.NAME: // fall through
            case PropertyType.PATH: // fall through
            case PropertyType.REFERENCE: // fall through
            case PropertyType.STRING: // fall through
            case PropertyType.UNDEFINED: // not actually expected
            default: // not actually expected
                return value.getString();
        }
    }

    /**
     * Converts the value(s) of a JCR Property to a corresponding Java Object.
     * If the property has multiple values the result is an array of Java
     * Objects representing the converted values of the property.
     */
    public static Object toJavaObject(Property property)
            throws RepositoryException {
        // multi-value property: return an array of values
        if (property.getDefinition().isMultiple()) {
            Value[] values = property.getValues();
            Object[] result = new Object[values.length];
            for (int i = 0; i < values.length; i++) {
                Value value = values[i];
                if (value != null) {
                    result[i] = toJavaObject(value);
                }
            }
            return result;
        }

        // single value property
        return toJavaObject(property.getValue());
    }

    /**
     * Helper method, which returns the given resource type as returned from the
     * {@link org.apache.sling.api.resource.Resource#getResourceType()} as a
     * relative path.
     *
     * @param type The resource type to be converted into a path
     * @return The resource type as a path.
     */
    public static String resourceTypeToPath(String type) {
        return type.replaceAll("\\:", "/");
    }

    /**
     * Returns the super type of the given resource type. This is the result of
     * calling the <code>getResourceSuperType()</code> method on the
     * <code>Resource</code> addressed by the <code>resourceType</code>. If
     * the resource type does not address a resource or if the addressed
     * resource has no resource super type, this method returns
     * <code>null</code>.
     *
     * @param resourceResolver The <code>ResourceResolver</code> used to
     *            access the resource whose path (relative or absolute) is given
     *            by the <code>resourceType</code> parameter.
     * @param resourceType The resource type whose super type is to be returned.
     *            This type is turned into a path by calling the
     *            {@link #resourceTypeToPath(String)} method before trying to
     *            get the resource through the <code>resourceResolver</code>.
     * @return the super type of the <code>resourceType</code> or
     *         <code>null</code> if the resource type does not address a
     *         resource or if the addressed resource has no resource super type.
     */
    public static String getResourceSuperType(
            ResourceResolver resourceResolver, String resourceType) {
        // normalize resource type to a path string
        String rtPath = resourceTypeToPath(resourceType);

        // get a resource for the resource type
        Resource rtResource = resourceResolver.getResource(rtPath);

        // get the resource super type from the resource
        return (rtResource != null) ? rtResource.getResourceSuperType() : null;
    }
}
