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
package org.apache.sling.junit.annotations;

import org.apache.sling.junit.Activator;
import org.apache.sling.junit.TestObjectProcessor;
import org.junit.runners.BlockJUnit4ClassRunner;
import org.junit.runners.model.InitializationError;
import org.osgi.framework.BundleContext;
import org.osgi.framework.ServiceReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** TestRunner which uses a TestObjectProcessor to 
 *  handle annotations in test classes.
 *  A test that has RunWith=SlingAnnotationsTestRunner can
 *  use @TestReference, for example, to access OSGi services.
 */
public class SlingAnnotationsTestRunner extends BlockJUnit4ClassRunner {
    private static final Logger log = LoggerFactory.getLogger(SlingAnnotationsTestRunner.class);

    private static TestObjectProcessor testObjectProcessor;  
    
    public SlingAnnotationsTestRunner(Class<?> clazz) throws InitializationError {
        super(clazz);
    }
    
    @Override
    protected Object createTest() throws Exception {
        final BundleContext ctx = Activator.getBundleContext();
        if(testObjectProcessor == null && ctx != null) {
            final ServiceReference ref = ctx.getServiceReference(TestObjectProcessor.class.getName());
            if(ref != null) {
                testObjectProcessor = (TestObjectProcessor)ctx.getService(ref);
            }
            log.info("Got TestObjectProcessor {}", testObjectProcessor);
        }

        if(testObjectProcessor == null) {
            log.info("No TestObjectProcessor service available, annotations will not be processed");
            return super.createTest();
        } else { 
            return testObjectProcessor.process(super.createTest());
        }
    }
}
