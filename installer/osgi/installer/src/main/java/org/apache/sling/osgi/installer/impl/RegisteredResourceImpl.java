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
package org.apache.sling.osgi.installer.impl;

import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Dictionary;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Hashtable;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.jar.JarInputStream;
import java.util.jar.Manifest;

import org.apache.sling.osgi.installer.InstallableResource;
import org.apache.sling.osgi.installer.impl.propertyconverter.PropertyConverter;
import org.apache.sling.osgi.installer.impl.propertyconverter.PropertyValue;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;

/** A resource that's been registered in the OSGi controller.
 * 	Data can be either an InputStream or a Dictionary, and we store
 *  it locally to avoid holding up to classes or data from our 
 *  clients, in case those disappear while we're installing stuff. 
 */
public class RegisteredResourceImpl implements RegisteredResource, Serializable { 
    private static final long serialVersionUID = 2L;
	private final String url;
	private final String urlScheme;
	private final String digest;
	private final String entity;
	private final Dictionary<String, Object> dictionary;
	private final Map<String, Object> attributes = new HashMap<String, Object>();
	private boolean installable = true;
	private final boolean hasDataFile;
	private final int priority;
    private final long serialNumber;
    private static long serialNumberCounter = System.currentTimeMillis();
	
    static enum ResourceType {
        BUNDLE,
        CONFIG
    }
    
    private final RegisteredResource.ResourceType resourceType;
	
    public static final String ENTITY_JAR_PREFIX = "jar:";
	public static final String ENTITY_BUNDLE_PREFIX = "bundle:";
	public static final String ENTITY_CONFIG_PREFIX = "config:";
	
	/** Create a RegisteredResource from given data. If the data's extension
	 *  maps to a configuration and the data provides an input stream, it is
	 *  converted to a Dictionary 
	 */
	public RegisteredResourceImpl(BundleContext ctx, InstallableResource input) throws IOException {
	    
	    try {
    		url = input.getUrl();
    		urlScheme = getUrlScheme(url);
    		resourceType = computeResourceType(input.getExtension());
    		priority = input.getPriority();
    		serialNumber = getNextSerialNumber();
    		
            if(input.getDigest() == null || input.getDigest().length() == 0) {
                throw new IllegalArgumentException("Missing digest: " + input);
            }
            
    		if(resourceType == RegisteredResource.ResourceType.BUNDLE) {
                if(input.getInputStream() == null) {
                    throw new IllegalArgumentException("InputStream is required for BUNDLE resource type: " + input);
                }
                dictionary = null;
                copyToLocalStorage(input.getInputStream(), getDataFile(ctx));
                hasDataFile = true;
                digest = input.getDigest();
                setAttributesFromManifest(ctx);
                final String name = (String)attributes.get(Constants.BUNDLE_SYMBOLICNAME); 
                if(name == null) {
                    // not a bundle - use "jar" entity to make it easier to find out
                    entity = ENTITY_JAR_PREFIX + input.getUrl();
                } else {
                    entity = ENTITY_BUNDLE_PREFIX + name;
                }
    		} else {
                hasDataFile = false;
                final ConfigurationPid pid = new ConfigurationPid(input.getUrl());
                entity = ENTITY_CONFIG_PREFIX + pid.getCompositePid();
                attributes.put(CONFIG_PID_ATTRIBUTE, pid);
                if(input.getInputStream() == null) {
                    // config provided as a Dictionary
                    dictionary = copy(input.getDictionary());
                } else {
                    dictionary = readDictionary(input.getInputStream()); 
                }
                digest = input.getDigest();
    		}
    	} finally {
    		if(input.getInputStream() != null) {
    			input.getInputStream().close();
    		}
    	}
	}
	
    private static long getNextSerialNumber() {
        synchronized (RegisteredResourceImpl.class) {
            return serialNumberCounter++; 
        }
    }

	@Override
	public String toString() {
	    return getClass().getSimpleName() + " " + url + ", digest=" + digest + ", serialNumber=" + serialNumber;
	}
	
	protected File getDataFile(BundleContext ctx) {
		final String filename = getClass().getSimpleName() + "." + serialNumber;
		return ctx.getDataFile(filename);
	}
	
	public void cleanup(BundleContext bc) {
	    final File dataFile = getDataFile(bc);
		if(dataFile.exists()) {
			dataFile.delete();
		}
	}
	
	public String getURL() {
		return url;
	}
	
	public InputStream getInputStream(BundleContext bc) throws IOException {
	    if(hasDataFile) {
	        final File dataFile = getDataFile(bc);
	        if(dataFile.exists()) {
	            return new BufferedInputStream(new FileInputStream(dataFile));
	        }
	    }
        return  null;
	}
	
	public Dictionary<String, Object> getDictionary() {
		return dictionary;
	}
	
	public String getDigest() {
		return digest;
	}
	
    /** Copy data to local storage */
	private void copyToLocalStorage(InputStream data, File f) throws IOException {
		final OutputStream os = new BufferedOutputStream(new FileOutputStream(f));
		try {
			final byte[] buffer = new byte[16384];
			int count = 0;
			while( (count = data.read(buffer, 0, buffer.length)) > 0) {
				os.write(buffer, 0, count);
			}
			os.flush();
		} finally {
			if(os != null) {
				os.close();
			}
		}
	}
	
	/** Convert InputStream to Dictionary using our extended properties format,
	 * 	which supports multi-value properties 
	 */
	static Dictionary<String, Object> readDictionary(InputStream is) throws IOException {
		final Dictionary<String, Object> result = new Hashtable<String, Object>();
		final PropertyConverter converter = new PropertyConverter();
		final Properties p = new Properties();
        p.load(is);
        for(Map.Entry<Object, Object> e : p.entrySet()) {
            final PropertyValue v = converter.convert((String)e.getKey(), (String)e.getValue());
            result.put(v.getKey(), v.getValue());
        }
        return result;
	}
	
	/** Copy given Dictionary, sorting keys */
	static Dictionary<String, Object> copy(Dictionary<String, Object> d) {
	    final Dictionary<String, Object> result = new Hashtable<String, Object>();
	    final List<String> keys = new ArrayList<String>();
	    final Enumeration<String> e = d.keys();
	    while(e.hasMoreElements()) {
	        keys.add(e.nextElement());
	    }
	    Collections.sort(keys);
	    for(String key : keys) {
	        result.put(key, d.get(key));
	    }
	    return result;
	}
	
	public String getUrl() {
	    return url;
	}

    public RegisteredResource.ResourceType getResourceType() {
        return resourceType;
    }
    
    static RegisteredResource.ResourceType computeResourceType(String extension) {
        if(extension.equals("jar")) {
            return RegisteredResource.ResourceType.BUNDLE;
        } else {
            return RegisteredResource.ResourceType.CONFIG;
        }
    }
    
    /** Return the identifier of the OSGi "entity" that this resource
     *  represents, for example "bundle:SID" where SID is the bundle's
     *  symbolic ID, or "config:PID" where PID is config's PID. 
     */
    public String getEntityId() {
        return entity;
    }
    
    public Map<String, Object> getAttributes() {
		return attributes;
	}
    
	public boolean isInstallable() {
        return installable;
	}

    public void setInstallable(boolean installable) {
        this.installable = installable;
    }

    /** Read the manifest from supplied input stream, which is closed before return */
    static Manifest getManifest(InputStream ins) throws IOException {
        Manifest result = null;

        JarInputStream jis = null;
        try {
            jis = new JarInputStream(ins);
            result= jis.getManifest();

        } finally {

            // close the jar stream or the inputstream, if the jar
            // stream is set, we don't need to close the input stream
            // since closing the jar stream closes the input stream
            if (jis != null) {
                try {
                    jis.close();
                } catch (IOException ignore) {
                }
            } else {
                try {
                    ins.close();
                } catch (IOException ignore) {
                }
            }
        }

        return result;
    }
    
    private void setAttributesFromManifest(BundleContext bc) throws IOException {
    	final Manifest m = getManifest(getInputStream(bc));
    	if(m == null) {
            throw new IOException("Cannot get manifest of bundle resource");
    	}
    	
    	final String sn = m.getMainAttributes().getValue(Constants.BUNDLE_SYMBOLICNAME);
        if(sn == null) {
            throw new IOException("Manifest does not supply " + Constants.BUNDLE_SYMBOLICNAME);
        }
    	
    	final String v = m.getMainAttributes().getValue(Constants.BUNDLE_VERSION);
        if(v == null) {
            throw new IOException("Manifest does not supply " + Constants.BUNDLE_VERSION);
        }
    	
        if(m != null) {
            attributes.put(Constants.BUNDLE_SYMBOLICNAME, sn);
            attributes.put(Constants.BUNDLE_VERSION, v.toString());
        }
    }
    
    static String getUrlScheme(String url) {
        final int pos = url.indexOf(':');
        if(pos <= 0) {
            throw new IllegalArgumentException("URL does not contain (or starts with) scheme separator ':': " + url);
        }
        return url.substring(0, pos);
    }
    
    public String getUrlScheme() {
        return urlScheme;
    }
    
    public int getPriority() {
        return priority;
    }
    
    public long getSerialNumber() {
        return serialNumber;
    }
}