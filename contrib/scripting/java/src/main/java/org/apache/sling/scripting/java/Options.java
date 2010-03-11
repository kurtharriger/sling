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
package org.apache.sling.scripting.java;

import java.util.Dictionary;
import java.util.Enumeration;
import java.util.Properties;

import org.osgi.service.component.ComponentContext;


/**
 * A class to hold all init parameters specific to the compiler
 */
public class Options {

    private static final String PROPERTY_JAVA_ENCODING = "javaEncoding";

    private static final String PROPERTY_COMPILER_SOURCE_V_M = "compilerSourceVM";

    private static final String PROPERTY_COMPILER_TARGET_V_M = "compilerTargetVM";

    private static final String PROPERTY_CLASSDEBUGINFO = "classdebuginfo";

    /** Default source and target VM version (value is "1.5"). */
    private static final String DEFAULT_VM_VERSION = "1.5";

    /**
     * Do we want to include debugging information in the class file?
     */
    private final boolean classDebugInfo;

    /**
     * Compiler target VM.
     */
    private final String compilerTargetVM;

    /**
     * The compiler source VM.
     */
    private final String compilerSourceVM;

    /**
     * Java platform encoding to generate the servlet.
     */
    private final String javaEncoding;

    /**
     * Classloader
     */
    private final ClassLoader classLoader;

    /**
     * Create an compiler options object using data available from
     * the component configuration.
     */
    public Options(final ComponentContext componentContext,
                   final ClassLoader classLoader) {

        this.classLoader = classLoader;

        // generate properties
        final Properties properties = new Properties();
        // set default values first
        properties.put(PROPERTY_CLASSDEBUGINFO, "true");
        properties.put(PROPERTY_COMPILER_TARGET_V_M, DEFAULT_VM_VERSION);
        properties.put(PROPERTY_COMPILER_SOURCE_V_M, DEFAULT_VM_VERSION);
        properties.put(PROPERTY_JAVA_ENCODING, "UTF-8");

        // now check component properties
        Dictionary<?, ?> config = componentContext.getProperties();
        Enumeration<?> enumeration = config.keys();
        while (enumeration.hasMoreElements()) {
            String key = (String) enumeration.nextElement();
            if (key.startsWith("java.")) {
                Object value = config.get(key);
                if (value != null) {
                    properties.put(key.substring("java.".length()),
                        value.toString());
                }
            }
        }

        this.classDebugInfo = Boolean.valueOf(properties.get(PROPERTY_CLASSDEBUGINFO).toString());
        this.compilerTargetVM = properties.get(PROPERTY_COMPILER_TARGET_V_M).toString();
        this.compilerSourceVM = properties.get(PROPERTY_COMPILER_SOURCE_V_M).toString();
        this.javaEncoding = properties.get(PROPERTY_JAVA_ENCODING).toString();
    }

    /**
     * Return the destination directory.
     */
    public String getDestinationPath() {
        return ":";
    }

    /**
     * Should class files be compiled with debug information?
     */
    public boolean getClassDebugInfo() {
        return this.classDebugInfo;
    }

    public ClassLoader getClassLoader() {
        return this.classLoader;
    }

    /**
     * @see Options#getCompilerTargetVM
     */
    public String getCompilerTargetVM() {
        return this.compilerTargetVM;
    }

    /**
     * @see Options#getCompilerSourceVM
     */
    public String getCompilerSourceVM() {
        return this.compilerSourceVM;
    }

    public String getJavaEncoding() {
        return this.javaEncoding;
    }
}