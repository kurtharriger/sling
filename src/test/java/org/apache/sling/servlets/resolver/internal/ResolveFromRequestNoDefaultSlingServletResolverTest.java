/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements. See the NOTICE file distributed with this
 * work for additional information regarding copyright ownership. The ASF
 * licenses this file to You under the Apache License, Version 2.0 (the
 * "License"); you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS, WITHOUT
 * WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied. See the
 * License for the specific language governing permissions and limitations under
 * the License.
 */
package org.apache.sling.servlets.resolver.internal;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.Workspace;

import org.apache.sling.commons.testing.osgi.MockComponentContext;
import org.apache.sling.jcr.api.SlingRepository;
import org.jmock.Expectations;
import org.jmock.Mockery;

/**
 * Testing the scenario where servletresolver.useRequestWorkspace = true and
 * the request workspace is not "default".
 *
 */
public class ResolveFromRequestNoDefaultSlingServletResolverTest extends SlingServletResolverTest {

    // not sure why this is necessary, but it seems to be
    protected final Mockery context = super.context;

    @Override
    protected void configureComponentContext(MockComponentContext mockComponentContext) {
        mockComponentContext.setProperty(SlingServletResolver.PROP_USE_REQUEST_WORKSPACE, "true");
    }

    @Override
    protected void addExpectations(final SlingRepository repository)
            throws RepositoryException {
        final Session defaultSession = this.context.mock(Session.class);
        final Workspace defaultWorkspace = this.context.mock(Workspace.class);

        final Session requestSession = this.context.mock(Session.class);

        this.context.checking(new Expectations() {
            {
                one(repository).loginAdministrative(with(aNull(String.class)));
                will(returnValue(defaultSession));
                one(defaultSession).getWorkspace();
                will(returnValue(defaultWorkspace));
                one(defaultWorkspace).getName();
                will(returnValue("default"));


                one(repository).loginAdministrative(with(equal("fromRequest")));
                will(returnValue(requestSession));
            }
        });
    }

}