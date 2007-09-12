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
package org.apache.sling.scripting.jsp;

import java.io.IOException;

import javax.servlet.ServletConfig;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.jasper.Constants;
import org.apache.jasper.JasperException;
import org.apache.jasper.Options;
import org.apache.jasper.compiler.JspRuntimeContext;
import org.apache.jasper.servlet.JspServletWrapper;
import org.apache.sling.scripting.HttpServletAdapter;

/**
 * The <code>JspServletWrapperAdapter</code> TODO
 *
 * @author fmeschbe
 * @version $Rev:23741 $, $Date:2006-12-01 16:24:05 +0100 (Fr, 01 Dez 2006) $
 */
public class JspServletWrapperAdapter extends JspServletWrapper {

    private HttpServletAdapter httpServletAdapter;

    JspServletWrapperAdapter(ServletConfig config, Options options,
        String jspUri, boolean isErrorPage, JspRuntimeContext rctxt)
            throws JasperException {
        super(config, options, jspUri, isErrorPage, rctxt);
    }

    HttpServletAdapter getServletAdapter() {
        if (this.httpServletAdapter == null) {
            this.httpServletAdapter = new JspHttpServletAdapter();
        }

        return this.httpServletAdapter;
    }

    private class JspHttpServletAdapter extends HttpServletAdapter {

        protected void service(HttpServletRequest request,
                HttpServletResponse response) throws IOException,
                ServletException {
            JspServletWrapperAdapter.this.service(request, response,
                this.preCompile(request));
        }

        /**
         * <p>
         * Look for a <em>precompilation request</em> as described in Section
         * 8.4.2 of the JSP 1.2 Specification. <strong>WARNING</strong> - we
         * cannot use <code>request.getParameter()</code> for this, because
         * that will trigger parsing all of the request parameters, and not give
         * a servlet the opportunity to call
         * <code>request.setCharacterEncoding()</code> first.
         * </p>
         *
         * @param request The servlet requset we are processing
         * @exception ServletException if an invalid parameter value for the
         *                <code>jsp_precompile</code> parameter name is
         *                specified
         */
        boolean preCompile(HttpServletRequest request) throws ServletException {

            // assume it is ok to access the parameters here, as we are not a
            // toplevel servlet
            String jspPrecompile = request.getParameter(Constants.PRECOMPILE);
            if (jspPrecompile == null) {
                return false;
            }

            if (jspPrecompile.length() == 0) {
                return true; // ?jsp_precompile
            }

            if (jspPrecompile.equals("true")) {
                return true; // ?jsp_precompile=true
            }

            if (jspPrecompile.equals("false")) {
                // Spec says if jsp_precompile=false, the request should not
                // be delivered to the JSP page; the easiest way to implement
                // this is to set the flag to true, and precompile the page
                // anyway.
                // This still conforms to the spec, since it says the
                // precompilation request can be ignored.
                return true; // ?jsp_precompile=false
            }

            // unexpected value, fail
            throw new ServletException("Cannot have request parameter "
                + Constants.PRECOMPILE + " set to " + jspPrecompile);
        }
    }
}
