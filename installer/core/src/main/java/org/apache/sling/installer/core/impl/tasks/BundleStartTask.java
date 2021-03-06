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

import java.text.DecimalFormat;

import org.apache.sling.installer.api.tasks.InstallationContext;
import org.apache.sling.installer.api.tasks.ResourceState;
import org.apache.sling.installer.api.tasks.TaskResource;
import org.apache.sling.installer.api.tasks.TaskResourceGroup;
import org.apache.sling.installer.core.impl.AbstractInstallTask;
import org.apache.sling.installer.core.impl.OsgiInstallerImpl;
import org.osgi.framework.Bundle;
import org.osgi.framework.BundleException;
import org.osgi.framework.Constants;

/** Start a bundle given its bundle ID
 *  Restarts if the bundle does not start on the first try,
 *  but only after receiving a bundle or framework event,
 *  indicating that it's worth retrying
 */
public class BundleStartTask extends AbstractInstallTask {

    private static final String BUNDLE_START_ORDER = "70-";

    private static final String ATTR_RC = "bst:retryCount";
    private static final String ATTR_EC = "bst:eventsCount";

    private final long bundleId;
	private final String sortKey;
	private long eventsCountForRetrying;
	private int retryCount = 0;

	private final BundleTaskCreator creator;

	public BundleStartTask(final TaskResourceGroup r, final long bundleId, final BundleTaskCreator btc) {
	    super(r);
        this.bundleId = bundleId;
        this.creator = btc;
        this.sortKey = BUNDLE_START_ORDER + new DecimalFormat("00000").format(bundleId);
        final TaskResource rr = this.getResource();
	    if ( rr != null && rr.getTemporaryAttribute(ATTR_RC) != null ) {
	        this.retryCount = (Integer)rr.getTemporaryAttribute(ATTR_RC);
            this.eventsCountForRetrying = (Long)rr.getTemporaryAttribute(ATTR_EC);
	    }
	}

	@Override
	public String getSortKey() {
		return sortKey;
	}

	@Override
	public String toString() {
		return getClass().getSimpleName() + ": bundle " + bundleId;
	}

	/**
	 * Check if the bundle is active.
	 * This is true if the bundle has the active state or of the bundle
	 * is in the starting state and has the lazy activation policy.
	 * Or if the bundle is a fragment, it's considered active as well
	 */
	public static boolean isBundleActive(final Bundle b) {
	    if ( b.getState() == Bundle.ACTIVE ) {
	        return true;
	    }
	    if ( b.getState() == Bundle.STARTING && isLazyActivatian(b) ) {
	        return true;
	    }
	    if ( b.getHeaders().get(Constants.FRAGMENT_HOST) != null ) {
	        return true;
	    }
        return false;
	}
	/**
	 * Check if the bundle has the lazy activation policy
	 */
	private static boolean isLazyActivatian(final Bundle b) {
        return Constants.ACTIVATION_LAZY.equals(b.getHeaders().get(Constants.BUNDLE_ACTIVATIONPOLICY));
	}

	/**
	 * @see org.apache.sling.installer.api.tasks.InstallTask#execute(org.apache.sling.installer.api.tasks.InstallationContext)
	 */
	public void execute(final InstallationContext ctx) {
	    // this is just a sanity check which should never be reached
        if (bundleId == 0) {
            this.getLogger().debug("Bundle 0 is the framework bundle, ignoring request to start it");
            if ( this.getResource() != null ) {
                this.setFinishedState(ResourceState.INSTALLED);
            }
            return;
        }

        // Do not execute this task if waiting for events
        final long eventsCount = OsgiInstallerImpl.getTotalEventsCount();
        if (eventsCount < eventsCountForRetrying) {
            this.getLogger().debug("Task is not executable at this time, counters={}/{}",
                    eventsCountForRetrying, eventsCount);
            if ( this.getResource() == null ) {
                ctx.addTaskToNextCycle(this);
            }
            return;
        }

        final Bundle b = this.creator.getBundleContext().getBundle(bundleId);
		if (b == null) {
		    this.getLogger().info("Cannot start bundle, id not found: {}", bundleId);
			return;
		}

        if (isBundleActive(b) ) {
            this.getLogger().debug("Bundle already started, no action taken: {}/{}", bundleId, b.getSymbolicName());
            if ( this.getResource() != null ) {
                this.setFinishedState(ResourceState.INSTALLED);
            }
            return;
        }
        // Try to start bundle, and if that doesn't work we'll need to retry
        try {
            b.start();
            if ( this.getResource() != null ) {
                this.setFinishedState(ResourceState.INSTALLED);
            }
            this.getLogger().info("Bundle started (retry count={}, bundle ID={}) : {}",
                    new Object[] {retryCount, bundleId, b.getSymbolicName()});
        } catch (final BundleException e) {
            this.getLogger().info("Could not start bundle (retry count={}, bundle ID={}) : {}. Reason: {}. Will retry.",
                    new Object[] {retryCount, bundleId, b.getSymbolicName(), e});

            // Do the first retry immediately (in case "something" happenened right now
            // that warrants a retry), but for the next ones wait for at least one bundle
            // event or framework event
            if (this.retryCount == 0) {
                this.eventsCountForRetrying = OsgiInstallerImpl.getTotalEventsCount();
            } else {
                this.eventsCountForRetrying = OsgiInstallerImpl.getTotalEventsCount() + 1;
            }
            this.retryCount++;
            if ( this.getResource() == null ) {
                ctx.addTaskToNextCycle(this);
            } else {
                this.getResource().setTemporaryAttribute(ATTR_RC, this.retryCount);
                this.getResource().setTemporaryAttribute(ATTR_EC, this.eventsCountForRetrying);
            }
        }
	}
}
