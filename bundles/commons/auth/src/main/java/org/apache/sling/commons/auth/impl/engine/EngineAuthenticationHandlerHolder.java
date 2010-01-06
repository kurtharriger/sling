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
package org.apache.sling.commons.auth.impl.engine;

import java.io.IOException;

import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.commons.auth.impl.AbstractAuthenticationHandlerHolder;
import org.apache.sling.commons.auth.spi.AuthenticationInfo;
import org.apache.sling.engine.auth.AuthenticationHandler;

/**
 * The <code>EngineAuthenticationHandlerHolder</code> class represents an
 * old-style Sling {@link AuthenticationHandler} service in the internal data
 * structure of the
 * {@link org.apache.sling.commons.auth.impl.SlingAuthenticator}.
 */
@SuppressWarnings("deprecation")
public final class EngineAuthenticationHandlerHolder extends
        AbstractAuthenticationHandlerHolder {

    // the actual authentication handler
    private final AuthenticationHandler handler;

    public EngineAuthenticationHandlerHolder(final String fullPath,
            final AuthenticationHandler handler) {
        super(fullPath);
        this.handler = handler;
    }

    public AuthenticationInfo doAuthenticate(HttpServletRequest request,
            HttpServletResponse response) {

        org.apache.sling.engine.auth.AuthenticationInfo engineAuthInfo = handler.authenticate(
            request, response);
        if (engineAuthInfo == null) {
            return null;
        } else if (engineAuthInfo == org.apache.sling.engine.auth.AuthenticationInfo.DOING_AUTH) {
            return AuthenticationInfo.DOING_AUTH;
        }

        return new AuthenticationInfo(engineAuthInfo.getAuthType(),
            engineAuthInfo.getCredentials(), engineAuthInfo.getWorkspaceName());

    }

    public boolean doRequestAuthentication(HttpServletRequest request,
            HttpServletResponse response) throws IOException {
        return handler.requestAuthentication(request, response);
    }

    public void doDropAuthentication(HttpServletRequest request,
            HttpServletResponse response) {
        // Engine AuthenticationHandler does not have this method
    }

    @Override
    public boolean equals(Object obj) {

        // equality is the base class equality (based on the fullpath)
        // and the encapsulated holders being the same.
        if (super.equals(obj)) {
            if (obj.getClass() == getClass()) {
                EngineAuthenticationHandlerHolder other = (EngineAuthenticationHandlerHolder) obj;
                return other.handler == handler;
            }
        }

        // handlers are not the same, so the holders are not the same
        return false;
    }

    @Override
    public String toString() {
        return handler.toString() + " (Legacy API Handler)";
    }

}