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
package org.apache.sling.scripting.scala.config;

import java.io.File;
import java.net.URISyntaxException;
import java.net.URL;
import java.net.URLClassLoader;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Dictionary;
import java.util.List;

import javax.jcr.Item;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.script.ScriptException;

import org.apache.sling.jcr.api.SlingRepository;
import org.apache.sling.scripting.scala.AbstractSettingsProvider;
import org.apache.sling.scripting.scala.BundleFS;
import org.apache.sling.scripting.scala.JcrFS;
import org.apache.sling.scripting.scala.SettingsProvider;
import org.apache.sling.scripting.scala.interpreter.ScalaSettings;
import org.osgi.framework.Bundle;
import org.osgi.service.component.ComponentContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import scala.tools.nsc.Settings;
import scala.tools.nsc.io.AbstractFile;
import scala.tools.nsc.io.Path;
import scala.tools.nsc.io.PlainFile;

/**
 * This {@link SettingsProvider} exposes the Scala compiler settings and the output
 * directory to the Felix admin console. Furthermore it adds all classes of all bundles
 * to the Scala compiler classpath.
 *
 * @scr.component
 * @scr.service
 */
public class SlingSettingsProvider extends AbstractSettingsProvider {
    private static final Logger log = LoggerFactory.getLogger(SlingSettingsProvider.class);

    private static final String PATH_SEPARATOR = System.getProperty("path.separator");

    /**
     * @scr.property
     *   value=""
     *   label="Scala compiler options"
     *   description="Scala compiler settings as documented by scalac -help"
     */
    public static final String SETTINGS = "scala.compiler.settings";
    private String settings;

    /**
     * @scr.property
     *   value="/var/classes"
     *   label="Compiler output directory"
     *   description="Output directory for files generated by the Scala compiler. Defaults to /var/classes."
     */
    public static final String OUT_DIR = "scala.compiler.outdir";
    private String outDir;

    private ComponentContext context;

    /** @scr.reference */
    private SlingRepository repository;
    private Session session;

    @Override
    public Settings getSettings() throws ScriptException {
        ScalaSettings settings = new ScalaSettings();
        settings.parse(this.settings);
        Bundle[] bundles = context.getBundleContext().getBundles();
        URL[] bootUrls = getBootUrls(bundles[0]);
        StringBuilder bootPath = new StringBuilder(settings.classpath().v());
        for (URL url : bootUrls) {
            // bootUrls are sometimes null, at least when running integration
            // tests with cargo-maven2-plugin
            if(url != null) {
                bootPath.append(PATH_SEPARATOR).append(url.getPath());
            }
        }
        settings.classpath().v_$eq(bootPath.toString());
        settings.outputDirs().setSingleOutput(getOutDir());
        return settings;
    }

    @Override
    public AbstractFile[] getClasspathX() {
        Bundle[] bundles = context.getBundleContext().getBundles();
        List<AbstractFile> bundleFs = new ArrayList<AbstractFile>();
        for (int k = 0; k < bundles.length; k++) {
            URL url = bundles[k].getResource("/");
            if (url == null) {
                url = bundles[k].getResource("");
            }

            if (url != null) {
                if ("file".equals(url.getProtocol())) {
                    try {
                        bundleFs.add(new PlainFile(new Path(new File(url.toURI()))));
                    }
                    catch (URISyntaxException e) {
                        throw (IllegalArgumentException) new IllegalArgumentException(
                                "Can't determine url of bundle " + k).initCause(e);
                    }
                }
                else {
                    bundleFs.add(BundleFS.create(bundles[k]));
                }
            }
            else {
                log.warn("Cannot retrieve resources from Bundle {}. Skipping.", bundles[k].getSymbolicName());
            }
        }
        return bundleFs.toArray(new AbstractFile[bundleFs.size()]);
    }

    // -----------------------------------------------------< SCR integration >---

    protected void activate(ComponentContext context) {
        this.context = context;
        Dictionary<?, ?> properties = context.getProperties();
        outDir = (String) properties.get(OUT_DIR);
        settings = (String) properties.get(SETTINGS);
    }

    protected void deactivate(ComponentContext context) {
        if (session != null) {
            session.logout();
        }
        this.context = null;
    }

    // -----------------------------------------------------< private >---

    // todo use ClassLoaderWriter instead of JcrFs
    private AbstractFile getOutDir() throws ScriptException {
        try {
            if (session == null) {
                session = repository.loginAdministrative(null);
            }
            Node node = deepCreateNode(outDir, session, "sling:Folder");
            if (node == null) {
                throw new ScriptException("Unable to create node " + outDir);
            }
            return JcrFS.create(node);
        }
        catch (RepositoryException e) {
            throw (ScriptException) new ScriptException("Unable to create node " + outDir).initCause(e);
        }
    }

    private static URL[] getBootUrls(Bundle bundle) {
        ArrayList<URL> urls = new ArrayList<URL>();
        ClassLoader classLoader = bundle.getClass().getClassLoader();
        while (classLoader != null) {
            if (classLoader instanceof URLClassLoader) {
                urls.addAll(Arrays.asList(((URLClassLoader) classLoader).getURLs()));
            }
            classLoader = classLoader.getParent();
        }

        return urls.toArray(new URL[urls.size()]);
    }

    private Node deepCreateNode(String path, Session session, String nodeType) throws RepositoryException {
        Node result = null;
        if (session.itemExists(path)) {
            Item it = session.getItem(path);
            if(it.isNode()) {
                result = (Node)it;
            }
        }
        else {
            int slashPos = path.lastIndexOf("/");
            String parentPath = path.substring(0, slashPos);
            String childPath = path.substring(slashPos + 1);
            result = deepCreateNode(parentPath, session, nodeType).addNode(childPath, nodeType);
            session.save();
        }
        return result;
    }

}
