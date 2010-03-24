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
package org.apache.sling.servlets.resolver.internal;

import static org.junit.Assert.*;

import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Workspace;
import javax.servlet.Servlet;
import javax.servlet.http.HttpServlet;

import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceUtil;
import org.apache.sling.api.servlets.OptingServlet;
import org.apache.sling.commons.testing.osgi.MockBundle;
import org.apache.sling.commons.testing.osgi.MockComponentContext;
import org.apache.sling.commons.testing.osgi.MockServiceReference;
import org.apache.sling.commons.testing.sling.MockResource;
import org.apache.sling.commons.testing.sling.MockResourceResolver;
import org.apache.sling.commons.testing.sling.MockSlingHttpServletRequest;
import org.apache.sling.engine.EngineConstants;
import org.apache.sling.jcr.api.SlingRepository;
import org.apache.sling.jcr.resource.JcrResourceResolverFactory;
import org.apache.sling.servlets.resolver.internal.resource.MockServletResource;
import org.jmock.Expectations;
import org.jmock.Mockery;
import org.jmock.integration.junit4.JMock;
import org.jmock.integration.junit4.JUnit4Mockery;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.osgi.framework.Constants;

@RunWith(JMock.class)
public class SlingServletResolverTest {

    protected final Mockery context = new JUnit4Mockery();

    private Servlet servlet;

    private SlingServletResolver servletResolver;

    public static final String SERVLET_PATH = "/mock";

    public static final String SERVLET_NAME = "TestServlet";

    private static final String SERVLET_EXTENSION = "html";

    private MockResourceResolver mockResourceResolver;

    @Before public void setUp() throws Exception {
        mockResourceResolver = new MockResourceResolver();
        mockResourceResolver.setSearchPath("/");

        final JcrResourceResolverFactory factory = new JcrResourceResolverFactory() {

            public ResourceResolver getResourceResolver(Session session) {
                return mockResourceResolver;
            }
        };

        servlet = new MockSlingRequestHandlerServlet();
        servletResolver = new SlingServletResolver() {

            @Override
            String getWorkspaceName(SlingHttpServletRequest request) {
                return getRequestWorkspaceName();
            }

        };

        // set sling repository
        final SlingRepository repository = this.context.mock(SlingRepository.class);
        addExpectations(repository);

        Class<?> resolverClass = servletResolver.getClass().getSuperclass();

        // set resource resolver factory
        final Field resolverField = resolverClass.getDeclaredField("resourceResolverFactory");
        resolverField.setAccessible(true);
        resolverField.set(servletResolver, factory);

        final Field repositoryField = resolverClass.getDeclaredField("repository");
        repositoryField.setAccessible(true);
        repositoryField.set(servletResolver, repository);

        MockBundle bundle = new MockBundle(1L);
        MockComponentContext mockComponentContext = new MockComponentContext(
            bundle, SlingServletResolverTest.this.servlet);
        MockServiceReference serviceReference = new MockServiceReference(bundle);
        serviceReference.setProperty(Constants.SERVICE_ID, 1L);
        serviceReference.setProperty(EngineConstants.SLING_SERLVET_NAME,
            SERVLET_NAME);
        serviceReference.setProperty(
            ServletResolverConstants.SLING_SERVLET_PATHS, SERVLET_PATH);
        serviceReference.setProperty(
            ServletResolverConstants.SLING_SERVLET_EXTENSIONS,
            SERVLET_EXTENSION);
        mockComponentContext.locateService(SERVLET_NAME, serviceReference);

        configureComponentContext(mockComponentContext);

        servletResolver.bindServlet(serviceReference);
        servletResolver.activate(mockComponentContext);

        String path = "/"
            + MockSlingHttpServletRequest.RESOURCE_TYPE
            + "/"
            + ResourceUtil.getName(MockSlingHttpServletRequest.RESOURCE_TYPE)
            + ".servlet";
        MockServletResource res = new MockServletResource(mockResourceResolver,
            servlet, path);
        mockResourceResolver.addResource(res);

        MockResource parent = new MockResource(mockResourceResolver,
            ResourceUtil.getParent(res.getPath()), "nt:folder");
        mockResourceResolver.addResource(parent);

        List<Resource> childRes = new ArrayList<Resource>();
        childRes.add(res);
        mockResourceResolver.addChildren(parent, childRes);
    }

    protected String getRequestWorkspaceName() {
        return "fromRequest";
    }

    protected void configureComponentContext(MockComponentContext mockComponentContext) {
    }

    protected void addExpectations(final SlingRepository repository)
            throws RepositoryException {
        final Session session = this.context.mock(Session.class);
        final Workspace workspace = this.context.mock(Workspace.class);
        this.context.checking(new Expectations() {{
            one(repository).loginAdministrative(with(aNull(String.class)));
            will(returnValue(session));
            one(session).getWorkspace();
            will(returnValue(workspace));
            one(workspace).getName();
            will(returnValue("default"));
        }});
    }

    @Test public void testAcceptsRequest() {
        MockSlingHttpServletRequest secureRequest = new MockSlingHttpServletRequest(
            SERVLET_PATH, null, SERVLET_EXTENSION, null, null);
        secureRequest.setResourceResolver(mockResourceResolver);
        secureRequest.setSecure(true);
        Servlet result = servletResolver.resolveServlet(secureRequest);
        assertEquals("Did not resolve to correct servlet", servlet, result);
    }

    @Test public void testIgnoreRequest() {
        MockSlingHttpServletRequest insecureRequest = new MockSlingHttpServletRequest(
            SERVLET_PATH, null, SERVLET_EXTENSION, null, null);
        insecureRequest.setResourceResolver(mockResourceResolver);
        insecureRequest.setSecure(false);
        Servlet result = servletResolver.resolveServlet(insecureRequest);
        assertTrue("Did not ignore unwanted request",
            result.getClass() != MockSlingRequestHandlerServlet.class);
    }

    /**
     * This sample servlet will only handle secure requests.
     *
     * @see org.apache.sling.api.servlets.OptingServlet#accepts
     */
    @SuppressWarnings("serial")
    private static class MockSlingRequestHandlerServlet extends HttpServlet
            implements OptingServlet {

        public boolean accepts(SlingHttpServletRequest request) {
            return request.isSecure();
        }
    }

}