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
package org.apache.sling.jcr.webdav.impl.helper;

import java.net.URL;
import java.util.Dictionary;
import java.util.Hashtable;

import javax.jcr.Item;
import javax.jcr.Node;
import javax.jcr.RepositoryException;

import org.apache.jackrabbit.server.io.DefaultHandler;
import org.apache.jackrabbit.server.io.DirListingExportHandler;
import org.apache.jackrabbit.server.io.IOManager;
import org.apache.jackrabbit.server.io.IOManagerImpl;
import org.apache.jackrabbit.server.io.PropertyManager;
import org.apache.jackrabbit.server.io.PropertyManagerImpl;
import org.apache.jackrabbit.webdav.simple.DefaultItemFilter;
import org.apache.jackrabbit.webdav.simple.ItemFilter;
import org.apache.jackrabbit.webdav.simple.ResourceConfig;
import org.apache.jackrabbit.webdav.simple.SimpleWebdavServlet;
import org.apache.sling.commons.mime.MimeTypeService;
import org.apache.sling.commons.osgi.OsgiUtil;
import org.apache.sling.jcr.webdav.impl.servlets.SlingWebDavServlet;

public class SlingResourceConfig extends ResourceConfig {

    private final String[] collectionTypes;

    private final ItemFilter itemFilter;

    private final IOManager ioManager;

    private final PropertyManager propertyManager;

    private final String servletContextPath;

    private final Dictionary<String, String> servletInitParams;

    public SlingResourceConfig(MimeTypeService mimeTypeService,
            Dictionary<?, ?> config) {
        super(new SlingTikaDetector(mimeTypeService));
        collectionTypes = OsgiUtil.toStringArray(
            config.get(SlingWebDavServlet.COLLECTION_TYPES),
            SlingWebDavServlet.COLLECTION_TYPES_DEFAULT);

        String[] filterPrefixes = OsgiUtil.toStringArray(
            config.get(SlingWebDavServlet.FILTER_PREFIXES),
            SlingWebDavServlet.FILTER_PREFIXES_DEFAULT);
        String[] filterNodeTypes = OsgiUtil.toStringArray(
            config.get(SlingWebDavServlet.FILTER_TYPES),
            SlingWebDavServlet.EMPTY_DEFAULT);
        String[] filterURIs = OsgiUtil.toStringArray(
            config.get(SlingWebDavServlet.FILTER_URIS),
            SlingWebDavServlet.EMPTY_DEFAULT);

        itemFilter = new DefaultItemFilter();
        itemFilter.setFilteredPrefixes(filterPrefixes);
        itemFilter.setFilteredURIs(filterURIs);
        itemFilter.setFilteredNodetypes(filterNodeTypes);

        String collectionType = OsgiUtil.toString(
            config.get(SlingWebDavServlet.TYPE_COLLECTIONS),
            SlingWebDavServlet.TYPE_COLLECTIONS_DEFAULT);
        String nonCollectionType = OsgiUtil.toString(
            config.get(SlingWebDavServlet.TYPE_NONCOLLECTIONS),
            SlingWebDavServlet.TYPE_NONCOLLECTIONS_DEFAULT);
        String contentType = OsgiUtil.toString(
            config.get(SlingWebDavServlet.TYPE_CONTENT),
            SlingWebDavServlet.TYPE_CONTENT_DEFAULT);

        // share these handlers between the IOManager and the PropertyManager
        DirListingExportHandler dirHandler = new DirListingExportHandler();
        DefaultHandler defaultHandler = new DefaultHandler(null, collectionType,
            nonCollectionType, contentType);

        ioManager = new IOManagerImpl();
        ioManager.addIOHandler(dirHandler);
        ioManager.addIOHandler(defaultHandler);

        propertyManager = new PropertyManagerImpl();
        propertyManager.addPropertyHandler(dirHandler);
        propertyManager.addPropertyHandler(defaultHandler);

        servletContextPath = OsgiUtil.toString(
            config.get(SlingWebDavServlet.PROP_CONTEXT),
            SlingWebDavServlet.DEFAULT_CONTEXT);

        servletInitParams = new Hashtable<String, String>();
        servletInitParams.put(
            SimpleWebdavServlet.INIT_PARAM_RESOURCE_PATH_PREFIX,
            servletContextPath);
        String value = OsgiUtil.toString(
            config.get(SlingWebDavServlet.PROP_REALM),
            SlingWebDavServlet.DEFAULT_REALM);
        servletInitParams.put(
            SimpleWebdavServlet.INIT_PARAM_AUTHENTICATE_HEADER,
            "Basic Realm=\"" + value + "\"");
    }

    // ---------- ResourceConfig overwrites

    @Override
    public IOManager getIOManager() {
        return ioManager;
    }

    @Override
    public ItemFilter getItemFilter() {
        return itemFilter;
    }

    @Override
    public PropertyManager getPropertyManager() {
        return propertyManager;
    }

    @Override
    public boolean isCollectionResource(Item item) {
        if (item.isNode()) {
            Node node = (Node) item;
            for (String type : collectionTypes) {
                try {
                    if (node.isNodeType(type)) {
                        return false;
                    }
                } catch (RepositoryException re) {
                    // TODO: log and continue
                }
            }
        }

        return true;
    }

    @Override
    public void parse(URL configURL) {
        // we don't parse nothing
    }

    // ---------- SlingResourceConfig additions

    public String getServletContextPath() {
        return servletContextPath;
    }

    public Dictionary<String, String> getServletInitParams() {
        return servletInitParams;
    }
}
