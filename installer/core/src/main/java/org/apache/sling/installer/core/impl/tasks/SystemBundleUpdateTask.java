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
package org.apache.sling.installer.core.impl.tasks;

import java.io.IOException;
import java.io.InputStream;

import org.apache.sling.installer.api.tasks.InstallationContext;
import org.apache.sling.installer.api.tasks.TaskResourceGroup;
import org.apache.sling.installer.core.impl.AbstractInstallTask;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;

/**
 * Update the system bundle from a RegisteredResource.
 */
public class SystemBundleUpdateTask extends AbstractInstallTask {

    private static final String BUNDLE_UPDATE_ORDER = "99-";

    private final BundleTaskCreator creator;

    public SystemBundleUpdateTask(final TaskResourceGroup r,
            final BundleTaskCreator creator) {
        super(r);
        this.creator = creator;
    }

    @Override
    public void execute(final InstallationContext ctx) {
        // restart system bundle
        if ( this.getResource() == null ) {
            this.restartSystemBundleDelayed();
            return;
        }
        final String symbolicName = (String)getResource().getAttribute(Constants.BUNDLE_SYMBOLICNAME);
        final Bundle b = this.creator.getMatchingBundle(symbolicName, null);
        if (b == null) {
            throw new IllegalStateException("Bundle to update (" + symbolicName + ") not found");
        }

        InputStream is = null;
        try {
            is = getResource().getInputStream();
            if (is == null) {
                throw new IllegalStateException(
                        "RegisteredResource provides null InputStream, cannot update bundle: "
                        + getResource());
            }
            // delayed system bundle update
            final InputStream backgroundIS = is;
            is = null;
            final Thread t = new Thread(new Runnable() {

                /**
                 * @see java.lang.Runnable#run()
                 */
                public void run() {
                    try {
                        Thread.sleep(800);
                    } catch(InterruptedException ignored) {
                    }
                    try {
                        b.update(backgroundIS);
                    } catch (final BundleException be) {
                        getLogger().warn("Unable to update system bundle", be);
                    } finally {
                        try {
                            backgroundIS.close();
                        } catch (IOException ignore) {}
                    }
                }
            });
            t.setDaemon(true);
            t.start();
        } catch (final IOException e) {
            this.getLogger().warn("Removing failing tasks - unable to retry: " + this, e);
        } finally {
            if ( is != null ) {
                try {
                    is.close();
                } catch (IOException ignore) {}
            }
        }
    }

    @Override
    public String getSortKey() {
        return BUNDLE_UPDATE_ORDER + getResource().getURL();
    }

    private void restartSystemBundleDelayed() {
        final Bundle systemBundle = this.creator.getBundleContext().getBundle(0);
        // sanity check
        if ( systemBundle == null ) {
            return;
        }
        final Thread t = new Thread(new Runnable() {

            /**
             * @see java.lang.Runnable#run()
             */
            public void run() {
                try {
                    Thread.sleep(800);
                } catch(InterruptedException ignored) {
                }
                try {
                    systemBundle.update();
                } catch (final BundleException be) {
                    getLogger().warn("Unable to refresh system bundle", be);
                }
            }
        });
        t.setDaemon(true);
        t.start();
    }
}