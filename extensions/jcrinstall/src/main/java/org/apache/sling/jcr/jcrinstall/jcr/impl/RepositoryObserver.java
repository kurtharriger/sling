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
package org.apache.sling.jcr.jcrinstall.jcr.impl;

import java.io.File;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Properties;
import java.util.Set;
import java.util.SortedSet;
import java.util.TreeSet;

import javax.jcr.Item;
import javax.jcr.Node;
import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.observation.Event;

import org.apache.sling.jcr.api.SlingRepository;
import org.apache.sling.jcr.jcrinstall.jcr.NodeConverter;
import org.apache.sling.jcr.jcrinstall.osgi.OsgiController;
import org.apache.sling.jcr.jcrinstall.osgi.ResourceOverrideRules;
import org.osgi.framework.BundleEvent;
import org.osgi.framework.BundleListener;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Observes a set of folders in the JCR repository, to
 *  detect added or updated resources that might be of
 *  interest to the OsgiController.
 *  
 *  Calls the OsgiController to install/remove resources.
 *   
 *  @scr.component 
 *      immediate="true"
 *      metatype="no"
 *  @scr.property 
 *      name="service.description" 
 *      value="Sling jcrinstall OsgiController Service"
 *  @scr.property 
 *      name="service.vendor" 
 *      value="The Apache Software Foundation"
 */
public class RepositoryObserver implements Runnable, BundleListener {

    protected SortedSet<WatchedFolder> folders;
    private RegexpFilter folderNameFilter;
    private RegexpFilter filenameFilter;
    private ResourceOverrideRules roRules;
    private final PropertiesUtil propertiesUtil = new PropertiesUtil();
    private boolean running;
    
    /** @scr.reference */
    protected OsgiController osgiController;
    
    /** @scr.reference */
    protected SlingRepository repository;
    
    private Session session;
    private File serviceDataFile;
    private long lastBundleEvent;
    
    private final List<NodeConverter> converters = new ArrayList<NodeConverter>();
    
    private List<WatchedFolderCreationListener> listeners = new LinkedList<WatchedFolderCreationListener>();
    
    /** Default set of root folders to watch */
    public static String[] DEFAULT_ROOTS = {"/libs", "/apps"};
    
    /** Default regexp for watched folders */
    public static final String DEFAULT_FOLDER_NAME_REGEXP = ".*/install$";
    
    /** ComponentContext property that overrides the folder name regepx */
    public static final String FOLDER_NAME_REGEXP_PROPERTY = "sling.jcrinstall.folder.name.regexp";
    
    public static final String DATA_FILE = "service.properties";
    public static final String DATA_LAST_FOLDER_REGEXP = "folder.regexp";
    
    /** Scan delay for watched folders */
    protected long scanDelayMsec = 1000L;
    
    private final Logger log = LoggerFactory.getLogger(getClass());
    
    /** Upon activation, find folders to watch under our roots, and observe those
     *  roots to detect new folders to watch.
     */
    protected void activate(ComponentContext context) throws RepositoryException {
    	
        if(context!=null) {
            context.getBundleContext().addBundleListener(this);
        }
        lastBundleEvent = System.currentTimeMillis();
        
    	// TODO make this more configurable (in sync with ResourceOverrideRulesImpl)
    	final String [] roots = DEFAULT_ROOTS;
        final String [] main = { "/libs/" };
        final String [] override = { "/apps/" };
    	
        roRules = new ResourceOverrideRulesImpl(main, override);
    	osgiController.setResourceOverrideRules(roRules);

    	/** NodeConverters setup
         *	Using services and a whiteboard pattern for these would be nice,
         * 	but that could be problematic at startup due to async loading
         */
    	converters.add(new FileNodeConverter());
    	converters.add(new ConfigNodeConverter());
    	
    	String folderNameRegexp = getPropertyValue(context, FOLDER_NAME_REGEXP_PROPERTY);
    	if(folderNameRegexp == null) {
    	    folderNameRegexp = DEFAULT_FOLDER_NAME_REGEXP;
            log.info("Using default folder name regexp '{}'", DEFAULT_FOLDER_NAME_REGEXP);
    	} else {
            log.info("Using folder name regexp '{}' from context property '{}'", folderNameRegexp, FOLDER_NAME_REGEXP_PROPERTY);
    	}
        folderNameFilter = new RegexpFilter(folderNameRegexp);
        serviceDataFile = getServiceDataFile(context);
        
        // Listen for any new WatchedFolders created after activation
        session = repository.loginAdministrative(repository.getDefaultWorkspace());
        final int eventTypes = Event.NODE_ADDED;
        final boolean isDeep = true;
        final boolean noLocal = true;
        for (String path : roots) {
            final WatchedFolderCreationListener w = new WatchedFolderCreationListener(folderNameFilter);
            listeners.add(w);
            session.getWorkspace().getObservationManager().addEventListener(w, eventTypes, path,
                    isDeep, null, null, noLocal);
        }
        
        // Find folders to watch
        folders = new TreeSet<WatchedFolder>();
        for(String root : roots) {
            folders.addAll(findWatchedFolders(root));
        }
        
        // start queue processing
        running = true;
        final Thread t = new Thread(this, getClass().getSimpleName() + "_" + System.currentTimeMillis());
        t.setDaemon(true);
        t.start();
    }
    
    protected File getServiceDataFile(ComponentContext context) {
        return context.getBundleContext().getDataFile(DATA_FILE);
    }
    
    /** Get a property value from the component context or bundle context */
    protected String getPropertyValue(ComponentContext ctx, String name) {
        String result = (String)ctx.getProperties().get(name);
        if(result == null) {
            result = ctx.getBundleContext().getProperty(name);
        }
        return result;
    }
    
    protected void deactivate(ComponentContext context) {
    	
        running = false;
        
        context.getBundleContext().removeBundleListener(this);

    	for(WatchedFolder f : folders) {
    		f.cleanup();
    	}
    	
    	if(session != null) {
	    	for(WatchedFolderCreationListener w : listeners) {
	    		try {
		        	session.getWorkspace().getObservationManager().removeEventListener(w);
	    		} catch(RepositoryException re) {
	    			log.warn("RepositoryException in removeEventListener call", re);
	    		}
	    	}
	    	
	    	session.logout();
	    	session = null;
    	}
    	
    	listeners.clear();
        folders.clear();
    }
    
    /** Add WatchedFolders that have been discovered by our WatchedFolderCreationListeners, if any */
    void addNewWatchedFolders() throws RepositoryException {
    	for(WatchedFolderCreationListener w : listeners) {
    		final Set<String> paths = w.getAndClearPaths();
    		if(paths != null) {
    			for(String path : paths) {
    				folders.add(new WatchedFolder(repository, path, osgiController, filenameFilter, scanDelayMsec, roRules));
    			}
    		}
    	}
    }

    /** Find all folders to watch under rootPath 
     * @throws RepositoryException */
    Set<WatchedFolder> findWatchedFolders(String rootPath) throws RepositoryException {
        final Set<WatchedFolder> result = new HashSet<WatchedFolder>();
        Session s = null;

        try {
            s = repository.loginAdministrative(repository.getDefaultWorkspace());
            if (!s.getRootNode().hasNode(relPath(rootPath))) {
                log.info("Bundles root node {} not found, ignored", rootPath);
            } else {
                log.debug("Bundles root node {} found, looking for bundle folders inside it", rootPath);
                final Node n = s.getRootNode().getNode(relPath(rootPath));
                findWatchedFolders(n, result);
            }
        } finally {
            if (s != null) {
                s.logout();
            }
        }
        
        return result;
    }
    
    /**
     * Add n to setToUpdate if it is a bundle folder, and recurse into its children
     * to do the same.
     */
    void findWatchedFolders(Node n, Set<WatchedFolder> setToUpdate) throws RepositoryException 
    {
        if (folderNameFilter.accept(n.getPath())) {
            setToUpdate.add(new WatchedFolder(repository, n.getPath(), osgiController, filenameFilter, scanDelayMsec, roRules));
        }
        final NodeIterator it = n.getNodes();
        while (it.hasNext()) {
            findWatchedFolders(it.nextNode(), setToUpdate);
        }
    }
    
    /**
     * Return the relative path for supplied path
     */
    static String relPath(String path) {
        if (path.startsWith("/")) {
            return path.substring(1);
        }
        return path;
    }
    
    /** Uninstall resources as needed when starting up */ 
    void handleInitialUninstalls() throws Exception {
        // If regexp has changed, uninstall resources left in folders 
        // that don't match the new regexp
        // TODO this happens right after activate() is called on this service,
        // might conflict with ongoing SCR activities?
        final Properties props = propertiesUtil.loadProperties(serviceDataFile);
        final String oldRegexp = props.getProperty(DATA_LAST_FOLDER_REGEXP);
        if(oldRegexp != null && !oldRegexp.equals(folderNameFilter.getRegexp())) {
            log.info("Folder name regexp has changed, uninstalling non-applicable resources ( {} -> {} )", 
                    oldRegexp, folderNameFilter.getRegexp());
            for(String uri : osgiController.getInstalledUris()) {
                try {
                    if(session.itemExists(uri)) {
                        final Item i = session.getItem(uri);
                        if(i.isNode()) {
                            final Node n = (Node)i;
                            final Node parent = n.getParent();
                            if(!folderNameFilter.accept(parent.getPath())) {
                                log.info("Uninstalling resource {}, folder name not accepted by current filter", uri);
                                osgiController.scheduleUninstall(uri);
                            }
                        }
                    }
                } catch (Exception e) {
                    log.warn("Exception during 'uninstall all'", e);
                }
            }
        }
        props.setProperty(DATA_LAST_FOLDER_REGEXP, folderNameFilter.getRegexp());
        propertiesUtil.saveProperties(props, serviceDataFile);
        
        // Check if any deletions happened while this
        // service was inactive: create a fake WatchFolder 
        // on / and use it to check for any deletions, even 
        // if the corresponding WatchFolders are gone
        try {
            final WatchedFolder rootWf = new WatchedFolder(repository, "/", osgiController, filenameFilter, 0L, null);
            rootWf.checkDeletions(osgiController.getInstalledUris());
        } catch(Exception e) {
            log.warn("Exception in root WatchFolder.checkDeletions call", e);
        }
        
        // Let the OSGi controller execute the uninstalls
        osgiController.executeScheduledOperations();
    }
    
    /**
     * Scan WatchFolders once their timer expires
     */
    public void run() {
        log.info("{} thread {} starts", getClass().getSimpleName(), Thread.currentThread().getName());
        
        // Wait some time after receiving our last bundle event, to
        // avoid uninstalling stuff while components might be starting
        final long bundleEventDelayMsec = 1000;
        boolean logged = false;
        while(System.currentTimeMillis() - lastBundleEvent < bundleEventDelayMsec) {
            if(!logged) {
                log.info("Bundle events were detected in the last {} msec, waiting...", bundleEventDelayMsec);
            }
            logged = true;
            
            try {
                Thread.sleep(100);
            } catch(InterruptedException ignore) {
                // ignore
            }
        }
        log.info("No bundle events in the last {} msec, starting processing", bundleEventDelayMsec);

        
        // We could use the scheduler service but that makes things harder to test
        boolean firstCycle = true;
        while (running) {
            try {
            	if(firstCycle) {
            		handleInitialUninstalls();
            		firstCycle = false;
            	}
                runOneCycle();
            } catch (IllegalArgumentException ie) {
                log.warn("IllegalArgumentException  in " + getClass().getSimpleName(), ie);
            } catch (RepositoryException re) {
                log.warn("RepositoryException in " + getClass().getSimpleName(), re);
            } catch (Exception e) {
                log.error("Unhandled Exception in runOneCycle()", e);
            } finally {
                try {
                    Thread.sleep(1000L);
                } catch (InterruptedException ignore) {
                    // ignore
                }
            }
        }

        log.info("{} thread {} ends", getClass().getSimpleName(), Thread.currentThread().getName());
    }
    
    /** Let our WatchedFolders run their scanning cycles */ 
    void runOneCycle() throws Exception {
    	
    	// Add any new watched folders, and scan those who need it 
        addNewWatchedFolders();
    	for(WatchedFolder wf : folders) {
    		wf.scanIfNeeded(converters);
        }
    	
    	// And then let the OsgiController install/remove
    	// resources that we detected
    	osgiController.executeScheduledOperations();
    }
    
    public void bundleChanged(BundleEvent event) {
        lastBundleEvent = System.currentTimeMillis();
    }
}