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
package org.apache.sling.i18n.impl;

import java.util.ArrayList;
import java.util.Dictionary;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Hashtable;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.MissingResourceException;
import java.util.ResourceBundle;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.observation.EventIterator;
import javax.jcr.observation.EventListener;
import javax.jcr.observation.ObservationManager;
import javax.jcr.query.Query;

import org.apache.felix.scr.annotations.Component;
import org.apache.felix.scr.annotations.Property;
import org.apache.felix.scr.annotations.Reference;
import org.apache.felix.scr.annotations.ReferenceCardinality;
import org.apache.felix.scr.annotations.ReferencePolicy;
import org.apache.felix.scr.annotations.Service;
import org.apache.sling.api.resource.LoginException;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ResourceResolverFactory;
import org.apache.sling.commons.osgi.OsgiUtil;
import org.apache.sling.i18n.ResourceBundleProvider;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceRegistration;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.apache.sling.i18n.impl.JcrResourceBundle.*;

/**
 * The <code>JcrResourceBundleProvider</code> implements the
 * <code>ResourceBundleProvider</code> interface creating
 * <code>ResourceBundle</code> instances from resources stored in the
 * repository.
 */
@Component(immediate = true, metatype = true, label = "%provider.name", description = "%provider.description")
@Service(ResourceBundleProvider.class)
public class JcrResourceBundleProvider implements ResourceBundleProvider,
        EventListener {

    private static final boolean DEFAULT_PRELOAD_BUNDLES = false;
    
    @Property(value = "")
    private static final String PROP_USER = "user";

    @Property(value = "")
    private static final String PROP_PASS = "password";

    @Property(value = "en")
    private static final String PROP_DEFAULT_LOCALE = "locale.default";

    @Property(boolValue = DEFAULT_PRELOAD_BUNDLES)
    private static final String PROP_PRELOAD_BUNDLES = "preload.bundles";


    /** default log */
    private final Logger log = LoggerFactory.getLogger(getClass());

    @Reference(cardinality = ReferenceCardinality.OPTIONAL_UNARY, policy = ReferencePolicy.DYNAMIC)
    private ResourceResolverFactory resourceResolverFactory;

    /**
     * The default Locale as configured with the <i>locale.default</i>
     * configuration property. This defaults to <code>Locale.ENGLISH</code> if
     * the configuration property is not set.
     */
    private Locale defaultLocale = Locale.ENGLISH;

    /**
     * The credentials to access the repository or <code>null</code> to use
     * access the repository as the anonymous user, which is the case if the
     * <i>user</i> property is not set in the configuration.
     */
    private Map<String, Object> repoCredentials;

    /**
     * The resource resolver used to access the resource bundles. This object is
     * retrieved from the {@link #resourceResolverFactory} using the anonymous
     * session or the session acquired using the {@link #repoCredentials}.
     */
    private ResourceResolver resourceResolver;

    /**
     * Map of cached resource bundles indexed by a key combined of the pertient
     * base name and <code>Locale</code> used to load and identify the
     * <code>ResourceBundle</code>.
     */
    private final ConcurrentHashMap<Key, ResourceBundle> resourceBundleCache = new ConcurrentHashMap<JcrResourceBundleProvider.Key, ResourceBundle>();

    /**
     * Return root resource bundle as created on-demand by
     * {@link #getRootResourceBundle()}.
     */
    private ResourceBundle rootResourceBundle;
    
    private BundleContext bundleContext;
    
    private List<ServiceRegistration> bundleServiceRegistrations;

    private boolean preloadBundles;

    // ---------- ResourceBundleProvider ---------------------------------------

    /**
     * Returns the configured default <code>Locale</code> which is used as a
     * fallback for {@link #getResourceBundle(Locale)} and also as the basis for
     * any messages requested from resource bundles.
     */
    public Locale getDefaultLocale() {
        return defaultLocale;
    }

    /**
     * Returns the <code>ResourceBundle</code> for the given
     * <code>locale</code>.
     *
     * @param locale The <code>Locale</code> for which to return the resource
     *            bundle. If this is <code>null</code> the configured
     *            {@link #getDefaultLocale() default locale} is assumed.
     * @return The <code>ResourceBundle</code> for the given locale.
     * @throws MissingResourceException If the <code>ResourceResolver</code>
     *             is not available to access the resources.
     */
    public ResourceBundle getResourceBundle(Locale locale) {
        return getResourceBundle(null, locale);
    }

    public ResourceBundle getResourceBundle(String baseName, Locale locale) {
        if (locale == null) {
            locale = defaultLocale;
        }

        return getResourceBundleInternal(baseName, locale);
    }

    // ---------- EventListener ------------------------------------------------

    /**
     * Called whenever something is changed inside of <code>jcr:language</code>
     * or <code>sling:Message</code> nodes. We just removed all cached
     * resource bundles in this case to force reloading them.
     * <p>
     * This is much simpler than analyzing the events and trying to be clever
     * about which exact resource bundles to remove from the cache and at the
     * same time care for any resource bundle dependencies.
     *
     * @param events The actual JCR events are ignored by this implementation.
     */
    public void onEvent(EventIterator events) {
        log.debug("onEvent: Resource changes, removing cached ResourceBundles");
        clearCache();
        preloadBundles();
    }

    // ---------- SCR Integration ----------------------------------------------

    /**
     * Activates and configures this component with the repository access
     * details and the default locale to use
     */
    protected void activate(ComponentContext context) {
        Dictionary<?, ?> props = context.getProperties();

        String user = OsgiUtil.toString(props.get(PROP_USER), null);
        if (user == null || user.length() == 0) {
            repoCredentials = null;
        } else {
            String pass = OsgiUtil.toString(props.get(PROP_PASS), null);
            char[] pwd = (pass == null) ? new char[0] : pass.toCharArray();
            repoCredentials = new HashMap<String, Object>();
            repoCredentials.put(ResourceResolverFactory.USER, user);
            repoCredentials.put(ResourceResolverFactory.PASSWORD, pwd);
        }

        String localeString = OsgiUtil.toString(props.get(PROP_DEFAULT_LOCALE),
            null);
        this.defaultLocale = toLocale(localeString);
        this.preloadBundles = OsgiUtil.toBoolean(props.get(PROP_PRELOAD_BUNDLES), DEFAULT_PRELOAD_BUNDLES);

        this.bundleContext = context.getBundleContext();
        this.bundleServiceRegistrations = new ArrayList<ServiceRegistration>();
        if (this.resourceResolverFactory != null) {
            final Thread t = new Thread() {
                public void run() {
                    preloadBundles();
                }
            };
            t.start();
        }
    }
    
    protected void deactivate() {
        clearCache();
    }

    /**
     * Binds a new <code>ResourceResolverFactory</code>. If we are already
     * bound to another factory, we release that latter one first.
     */
    protected void bindResourceResolverFactory(
            ResourceResolverFactory resourceResolverFactory) {
        if (this.resourceResolverFactory != null) {
            releaseRepository();
        }
        this.resourceResolverFactory = resourceResolverFactory;
        if (this.bundleContext != null) {
            preloadBundles();
        }
    }

    /**
     * Unbinds the <code>ResourceResolverFactory</code>. If we are bound to
     * this factory, we release it.
     */
    protected void unbindResourceResolverFactory(
            ResourceResolverFactory resourceResolverFactory) {
        if (this.resourceResolverFactory == resourceResolverFactory) {
            releaseRepository();
            this.resourceResolverFactory = null;
        }
    }

    // ---------- internal -----------------------------------------------------

    /**
     * Internal implementation of the {@link #getResourceBundle(Locale)} method
     * employing the cache of resource bundles. Creates the bundle if not
     * already cached.
     *
     * @throws MissingResourceException If the resource bundles needs to be
     *             created and the <code>ResourceResolver</code> is not
     *             available to access the resources.
     */
    private ResourceBundle getResourceBundleInternal(String baseName,
            Locale locale) {

        final Key key = new Key(baseName, locale);
        ResourceBundle resourceBundle = resourceBundleCache.get(key);

        if (resourceBundle == null) {
            log.debug(
                "getResourceBundleInternal({}, {}): reading from Repository",
                new Object[] { baseName, locale });
            resourceBundle = createResourceBundle(baseName, locale);
            if (resourceBundleCache.putIfAbsent(key, resourceBundle) != null) {
                resourceBundle = resourceBundleCache.get(key);
                log.debug(
                    "getResourceBundleInternal({}, {}): duplicate creation, using existing ResourceBundle",
                    new Object[] { baseName, locale
                            });
            } else {
                synchronized (this) {
                    Dictionary<Object, Object> serviceProps = new Hashtable<Object, Object>();
                    if (key.baseName != null) {
                        serviceProps.put("baseName", key.baseName);
                    }
                    serviceProps.put("locale", key.locale.toString());
                    ServiceRegistration serviceReg = bundleContext.registerService(ResourceBundle.class.getName(), resourceBundle, serviceProps);
                    bundleServiceRegistrations.add(serviceReg);
                }
            }
        }

        if (log.isDebugEnabled()) {
            log.debug("getResourceBundleInternal({}, {}) ==> {}", new Object[] {
                baseName, locale, resourceBundle });
        }

        return resourceBundle;
    }

    /**
     * Creates the resource bundle for the give locale.
     *
     * @throws MissingResourceException If the <code>ResourceResolver</code>
     *             is not available to access the resources.
     */
    private ResourceBundle createResourceBundle(String baseName, Locale locale) {

        ResourceResolver resolver = getResourceResolver();
        if (resolver == null) {
            log.info("createResourceBundle: Missing Resource Resolver, cannot create Resource Bundle");
            throw new MissingResourceException(
                "ResourceResolver not available", getClass().getName(), "");
        }

        JcrResourceBundle bundle = new JcrResourceBundle(locale, baseName,
            resolver);

        // set parent resource bundle
        Locale parentLocale = getParentLocale(locale);
        if (parentLocale != null) {
            bundle.setParent(getResourceBundleInternal(baseName, parentLocale));
        } else {
            bundle.setParent(getRootResourceBundle());
        }

        return bundle;
    }

    /**
     * Returns the parent locale of the given locale. The parent locale is the
     * locale of a locale is defined as follows:
     * <ol>
     * <li>If the locale has an variant, the parent locale is the locale with
     * the same language and country without the variant.</li>
     * <li>If the locale has no variant but a country, the parent locale is the
     * locale with the same language but neither country nor variant.</li>
     * <li>If the locale has no country and not variant and whose language is
     * different from the language of the the configured default locale, the
     * parent locale is the configured default locale.</li>
     * <li>Otherwise there is no parent locale and <code>null</code> is
     * returned.</li>
     * </ol>
     */
    private Locale getParentLocale(Locale locale) {
        if (locale.getVariant().length() != 0) {
            return new Locale(locale.getLanguage(), locale.getCountry());
        } else if (locale.getCountry().length() != 0) {
            return new Locale(locale.getLanguage());
        } else if (!locale.getLanguage().equals(defaultLocale.getLanguage())) {
            return defaultLocale;
        }

        // no more parents
        return null;
    }

    /**
     * Returns a ResourceBundle which is used as the root resource bundle, that
     * is the ultimate parent:
     * <ul>
     * <li><code>getLocale()</code> returns Locale("", "", "")</li>
     * <li><code>handleGetObject(String key)</code> returns the <code>key</code></li>
     * <li><code>getKeys()</code> returns an empty enumeration.
     * </ul>
     *
     * @return The root resource bundle
     */
    private ResourceBundle getRootResourceBundle() {
        if (rootResourceBundle == null) {
            rootResourceBundle = new RootResourceBundle();
        }
        return rootResourceBundle;
    }

    /**
     * Returns the resource resolver to access messages. This method logs into
     * the repository and registers with the observation manager if not already
     * done so. If unable to connect to the repository, <code>null</code> is
     * returned.
     *
     * @return The <code>ResourceResolver</code> or <code>null</code> if
     *         unable to login to the repository. <code>null</code> is also
     *         returned if no <code>ResourceResolverFactory</code> or no
     *         <code>Repository</code> is available.
     */
    private ResourceResolver getResourceResolver() {
        if (resourceResolver == null) {
            ResourceResolverFactory fac = this.resourceResolverFactory;
            if (fac == null) {

                log.error("getResourceResolver: ResourceResolverFactory is missing. Cannot create ResourceResolver");

            } else {
                ResourceResolver resolver = null;
                try {
                    if (repoCredentials == null) {
                        resolver = fac.getAdministrativeResourceResolver(null);
                    } else {
                        resolver = fac.getResourceResolver(repoCredentials);
                    }

                    final Session s = resolver.adaptTo(Session.class);
                    ObservationManager om = s.getWorkspace().getObservationManager();
                    om.addEventListener(this, 255, "/", true, null,
                        new String[] { "mix:language", "sling:Message" }, true);

                    resourceResolver = resolver;

                } catch (RepositoryException re) {

                    log.error(
                        "getResourceResolver: Problem setting up ResourceResolver with Session",
                        re);

                } catch (LoginException le) {

                    log.error(
                        "getResourceResolver: Problem setting up ResourceResolver with Session",
                        le);

                }

            }
        }

        return resourceResolver;
    }
    
    private void clearCache() {
        resourceBundleCache.clear();
        synchronized (this) {
            for (ServiceRegistration serviceReg : bundleServiceRegistrations) {
                serviceReg.unregister();
            }
            bundleServiceRegistrations.clear();
        }
    }
    
    private void preloadBundles() {
        if (preloadBundles) {
            @SuppressWarnings("deprecation")
            Iterator<Map<String, Object>> bundles = getResourceResolver().queryResources(
                    "//element(*,mix:language)", Query.XPATH);
            Set<Key> usedKeys = new HashSet<Key>();
            while (bundles.hasNext()) {
                Map<String,Object> bundle = bundles.next();
                if (bundle.containsKey(PROP_LANGUAGE)) {
                    Locale locale = toLocale(bundle.get(PROP_LANGUAGE).toString());
                    String baseName = null;
                    if (bundle.containsKey(PROP_BASENAME)) {
                        baseName = bundle.get(PROP_BASENAME).toString();
                    }
                    Key key = new Key(baseName, locale);
                    if (usedKeys.add(key)) {
                        getResourceBundle(baseName, locale);
                    }
                }   
            }
        }
    }

    /**
     * Logs out from the repository and clears the resource bundle cache.
     */
    private void releaseRepository() {
        ResourceResolver resolver = this.resourceResolver;

        this.resourceResolver = null;
        clearCache();

        if (resolver != null) {

            Session s = resolver.adaptTo(Session.class);
            if (s != null) {

                try {
                    ObservationManager om = s.getWorkspace().getObservationManager();
                    om.removeEventListener(this);
                } catch (Throwable t) {
                    log.info(
                        "releaseRepository: Problem unregistering as event listener",
                        t);
                }

            }

            try {
                resolver.close();
            } catch (Throwable t) {
                log.info(
                    "releaseRepository: Unexpected problem closing the ResourceResolver",
                    t);
            }
        }
    }

    /**
     * Converts the given <code>localeString</code> to valid
     * <code>java.util.Locale</code>. If the locale string is
     * <code>null</code> or empty, the platform default locale is assumed. If
     * the localeString matches any locale available per default on the
     * platform, that platform locale is returned. Otherwise the localeString is
     * parsed and the language and country parts are compared against the
     * languages and countries provided by the platform. Any unsupported
     * language or country is replaced by the platform default language and
     * country.
     */
    private Locale toLocale(String localeString) {
        if (localeString == null || localeString.length() == 0) {
            return Locale.getDefault();
        }

        // check language and country
        String[] parts = localeString.split("_");
        if (parts.length == 0) {
            return Locale.getDefault();
        }

        // at least language is available
        String lang = parts[0];
        String[] langs = Locale.getISOLanguages();
        for (int i = 0; i < langs.length; i++) {
            if (langs[i].equals(lang)) {
                lang = null; // signal ok
                break;
            }
        }
        if (lang != null) {
            parts[0] = Locale.getDefault().getLanguage();
        }

        // only language
        if (parts.length == 1) {
            return new Locale(parts[0]);
        }

        // country is also available
        String country = parts[1];
        String[] countries = Locale.getISOCountries();
        for (int i = 0; i < countries.length; i++) {
            if (countries[i].equals(lang)) {
                country = null; // signal ok
                break;
            }
        }
        if (country != null) {
            parts[1] = Locale.getDefault().getCountry();
        }

        // language and country
        if (parts.length == 2) {
            return new Locale(parts[0], parts[1]);
        }

        // language, country and variant
        return new Locale(parts[0], parts[1], parts[2]);
    }

    //---------- internal class

    /**
     * The <code>Key</code> class encapsulates the base name and Locale for the
     * key of the {@link #resourceBundleCache} map.
     */
    private static class Key {

        final String baseName;

        final Locale locale;

        // precomputed hash code, because this will always be used due to
        // this instance being used as a key in a HashMap.
        final int hashCode;

        Key(final String baseName, final Locale locale) {

            int hc = 0;
            if (baseName != null) {
                hc += 17 * baseName.hashCode();
            }
            if (locale != null) {
                hc += 13 * locale.hashCode();
            }

            this.baseName = baseName;
            this.locale = locale;
            this.hashCode = hc;
        }

        @Override
        public int hashCode() {
            return hashCode;
        }

        @Override
        public boolean equals(Object obj) {
            if (obj == this) {
                return true;
            } else if (obj instanceof Key) {
                Key other = (Key) obj;
                return equals(this.baseName, other.baseName)
                    && equals(this.locale, other.locale);
            }

            return false;
        }

        private static boolean equals(Object o1, Object o2) {
            if (o1 == null) {
                if (o2 != null) {
                    return false;
                }
            } else if (!o1.equals(o2)) {
                return false;
            }
            return true;
        }
    }
}
