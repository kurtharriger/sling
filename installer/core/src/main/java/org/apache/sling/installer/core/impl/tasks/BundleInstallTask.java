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

import org.apache.sling.installer.api.InstallableResource;
import org.apache.sling.installer.api.tasks.InstallationContext;
import org.apache.sling.installer.api.tasks.TaskResourceGroup;
import org.apache.sling.installer.core.impl.AbstractInstallTask;
import org.osgi.framework.Bundle;
import org.osgi.service.startlevel.StartLevel;

/** Install a bundle supplied as a RegisteredResource.
 *  Creates a BundleStartTask to start the bundle */
public class BundleInstallTask extends AbstractInstallTask {

    private static final String BUNDLE_INSTALL_ORDER = "50-";

    private final BundleTaskCreator creator;

    public BundleInstallTask(final TaskResourceGroup r,
            final BundleTaskCreator creator) {
        super(r);
        this.creator = creator;
    }

    /**
     * @see org.apache.sling.installer.api.tasks.InstallTask#execute(org.apache.sling.installer.api.tasks.InstallationContext)
     */
    public void execute(final InstallationContext ctx) {
        int startLevel = 0;
        final Object providedLevel;

        if (this.getResource().getDictionary() != null) {
            if ( this.getResource().getDictionary().get(InstallableResource.BUNDLE_START_LEVEL) != null ) {
                providedLevel = this.getResource().getDictionary().get(InstallableResource.BUNDLE_START_LEVEL);
            } else {
                providedLevel = this.getResource().getDictionary().get(InstallableResource.INSTALLATION_HINT);
            }
        } else {
            providedLevel = null;
        }
        if ( providedLevel != null ) {
            if ( providedLevel instanceof Number ) {
                startLevel = ((Number)providedLevel).intValue();
            } else {
                try {
                    startLevel = Integer.valueOf(providedLevel.toString());
                } catch (final NumberFormatException nfe) {
                    // ignore this
                }
            }
        }
        // get the start level service (if possible) so we can set the initial start level
        final StartLevel startLevelService = this.creator.getStartLevel();
        try {
            final Bundle b = this.creator.getBundleContext().installBundle(getResource().getURL(), getResource().getInputStream());
            ctx.log("Installed bundle {} from resource {}", b, getResource());
            // optionally set the start level
            if ( startLevel > 0 ) {
                if (startLevelService != null) {
                    startLevelService.setBundleStartLevel(b, startLevel);
                } else {
                    this.getLogger().warn("Ignoring start level {} for bundle {} - start level service not available.",
                            startLevel, b);
                }
            }

            // mark this resource as installed and to be started
            this.getResource().setAttribute(BundleTaskCreator.ATTR_START, "true");
            ctx.addTaskToCurrentCycle(new BundleStartTask(getResourceGroup(), b.getBundleId(), this.creator));
            ctx.addTaskToNextCycle(new RefreshOptionalPackagesTask(this.creator));
        } catch (Exception ex) {
            // if something goes wrong we simply try it again
            this.getLogger().debug("Exception during install of bundle " + this.getResource() + " : " + ex.getMessage() + ". Retrying later.", ex);
        }
    }

    @Override
    public String getSortKey() {
        return BUNDLE_INSTALL_ORDER + getResource().getURL();
    }
}
