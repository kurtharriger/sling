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
package org.apache.sling.servlets.get.impl;

import java.io.IOException;

import javax.servlet.Servlet;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.SlingConstants;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.request.RequestPathInfo;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.resource.ValueMap;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.apache.sling.servlets.get.impl.helpers.JsonRendererServlet;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The <code>RedirectServlet</code> implements support for GET requests to
 * resources of type <code>sling:redirect</code>. This servlet tries to get the
 * redirect target by
 * <ul>
 * <li>first adapting the resource to a {@link ValueMap} and trying to get the
 * property <code>sling:target</code>.</li>
 * <li>The second attempt is to access the resource <code>sling:target</code>
 * below the requested resource and attapt this to a string.</li>
 * <p>
 * If there is no value found for <code>sling:target</code> a 404 (NOT FOUND)
 * status is sent by this servlet. Otherwise a 302 (FOUND, temporary redirect)
 * status is sent where the target is the relative URL from the current resource
 * to the target resource. Selectors, extension, suffix and query string are
 * also appended to the redirect URL.
 *
 * @scr.component immediate="true" metatype="no"
 * @scr.service interface="javax.servlet.Servlet"
 * @scr.property name="service.description" value="Request Redirect Servlet"
 * @scr.property name="service.vendor" value="The Apache Software Foundation"
 * @scr.property name="sling.servlet.resourceTypes" value="sling:redirect"
 * @scr.property name="sling.servlet.methods" value="GET"
 * @scr.property name="sling.servlet.prefix" value="-1" type="Integer"
 *               private="true"
 */
public class RedirectServlet extends SlingSafeMethodsServlet {

    /** The name of the target property */
    public static final String TARGET_PROP = "sling:target";

    /** default log */
    private final Logger log = LoggerFactory.getLogger(getClass());

    private Servlet jsonRendererServlet;

    @Override
    protected void doGet(SlingHttpServletRequest request,
            SlingHttpServletResponse response) throws ServletException,
            IOException {

        // handle json export of the redirect node
        if (JsonRendererServlet.EXT_JSON.equals(request.getRequestPathInfo().getExtension())) {
            getJsonRendererServlet().service(request, response);
            return;
        }

        // check for redirectability
        if (response.isCommitted()) {
            // committed response cannot be redirected
            log.warn("RedirectServlet: Response is already committed, not redirecting");
            request.getRequestProgressTracker().log(
                "RedirectServlet: Response is already committed, not redirecting");
            return;
        } else if (request.getAttribute(SlingConstants.ATTR_REQUEST_SERVLET) != null) {
            // included request will not redirect
            log.warn("RedirectServlet: Servlet is included, not redirecting");
            request.getRequestProgressTracker().log(
                "RedirectServlet: Servlet is included, not redirecting");
            return;
        }

        String targetPath = null;

        // convert resource to a value map
        final Resource rsrc = request.getResource();
        final ValueMap valueMap = rsrc.adaptTo(ValueMap.class);
        if (valueMap != null) {
            targetPath = valueMap.get(TARGET_PROP, String.class);
        }
        if (targetPath == null) {
            // old behaviour
            final Resource targetResource = request.getResourceResolver().getResource(
                rsrc, TARGET_PROP);
            if (targetResource == null) {
                response.sendError(HttpServletResponse.SC_NOT_FOUND,
                    "Missing target for redirection");
                return;
            }

            // if the target resource is a path (string), redirect there
            targetPath = targetResource.adaptTo(String.class);

        }

        // if we got a target path, make it external and redirect to it
        if (targetPath != null) {
            if (!isUrl(targetPath)) {
                // make path relative and append selectors, extension etc.
                targetPath = toRedirectPath(targetPath, request);
            }

            // and redirect there ...
            response.sendRedirect(targetPath);

            return;
        }

        // no way of finding the target, just fail
        response.sendError(HttpServletResponse.SC_NOT_FOUND,
            "Cannot redirect to target resource " + targetPath);
    }

    /**
     * Create a relative redirect URL for the targetPath relative to the given
     * request. The URL is relative to the request's resource and will include
     * the selectors, extension, suffix and query string of the request.
     */
    protected static String toRedirectPath(String targetPath,
            SlingHttpServletRequest request) {

        // if the target path is an URL, do nothing and return it unmodified
        final RequestPathInfo rpi = request.getRequestPathInfo();

        // make sure the target path is absolute
        final String rawAbsPath;
        if (targetPath.startsWith("/")) {
            rawAbsPath = targetPath;
        } else {
            rawAbsPath = request.getResource().getPath() + "/" + targetPath;
        }

        final StringBuilder target = new StringBuilder();

        // and ensure the path is normalized, us unnormalized if not possible
        final String absPath = ResourceUtil.normalize(rawAbsPath);
        if (absPath == null) {
            target.append(rawAbsPath);
        } else {
            target.append(absPath);
        }

        // append current selectors, extension and suffix
        if (rpi.getExtension() != null) {

            if (rpi.getSelectorString() != null) {
                target.append('.').append(rpi.getSelectorString());
            }

            target.append('.').append(rpi.getExtension());

            if (rpi.getSuffix() != null) {
                target.append(rpi.getSuffix());
            }
        }

        // append current querystring
        if (request.getQueryString() != null) {
            target.append('?').append(request.getQueryString());
        }

        // return the mapped full path
        return request.getResourceResolver().map(request, target.toString());
    }

    private Servlet getJsonRendererServlet() {
        if (jsonRendererServlet == null) {
            Servlet jrs = new JsonRendererServlet();
            try {
                jrs.init(getServletConfig());
            } catch (Exception e) {
                // don't care too much here
            }
            jsonRendererServlet = jrs;
        }
        return jsonRendererServlet;
    }

    /**
     * Returns <code>true</code> if the path is potentially an URL. This
     * simplistic check looks for a ":/" string in the path assuming that this
     * is a separator to separate the scheme from the scheme-specific part. If
     * the separator occurs after a query separator ("?"), though, it is not
     * assumed to be a scheme-separator.
     */
    private static boolean isUrl(final String path) {
        final int protocolIndex = path.indexOf(":/");
        final int queryIndex = path.indexOf('?');
        return protocolIndex > -1
            && (queryIndex == -1 || queryIndex > protocolIndex);
    }
}