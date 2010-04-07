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
package org.apache.sling.osgi.installer.it;

import java.util.Dictionary;
import java.util.Hashtable;

import static org.junit.Assert.fail;
import org.apache.sling.osgi.installer.InstallableResource;
import org.junit.After;
import org.junit.Before;
import org.junit.runner.RunWith;
import org.ops4j.pax.exam.Option;
import org.ops4j.pax.exam.junit.JUnit4TestRunner;
import org.osgi.service.cm.Configuration;

@RunWith(JUnit4TestRunner.class)
public class ConfigPrioritiesTest extends OsgiInstallerTestBase {

    private final static long TIMEOUT = 5000L;
    
    @org.ops4j.pax.exam.junit.Configuration
    public static Option[] configuration() {
        return defaultConfiguration();
    }
    
    @Before
    public void setUp() {
        setupInstaller();
    }
    
    @After
    public void tearDown() {
        super.tearDown();
    }
    
    void assertConfigValue(String pid, String key, String value, long timeoutMsec) throws Exception {
        boolean found = false;
        final String info = pid + ": waiting for " + key + "=" + value;
        final long end = System.currentTimeMillis() + timeoutMsec;
        do {
            final Configuration cfg = waitForConfiguration(info, pid, timeoutMsec, true);
            if(value.equals(cfg.getProperties().get(key))) {
                found = true;
                break;
            }
        } while(System.currentTimeMillis() < end);
        
        if(!found) {
            fail("Did not get expected value: " + info);
        }
    }
    
    public void testOverrideConfig() throws Exception {
        final String pid = getClass().getSimpleName() + "." + System.currentTimeMillis();
        final Dictionary<String, Object> data = new Hashtable<String, Object>();
        
        data.put("foo", "a");
        final InstallableResource a = getInstallableResource(pid, data, InstallableResource.DEFAULT_PRIORITY - 1);
        data.put("foo", "b");
        final InstallableResource b = getInstallableResource(pid, data, InstallableResource.DEFAULT_PRIORITY);
        data.put("foo", "c");
        final InstallableResource c = getInstallableResource(pid, data, InstallableResource.DEFAULT_PRIORITY + 1);
        
        installer.addResource(b);
        assertConfigValue(pid, "foo", "b", TIMEOUT);
        installer.addResource(c);
        assertConfigValue(pid, "foo", "c", TIMEOUT);
        installer.addResource(a);
        installer.removeResource(new InstallableResource(c.getUrl()));
        assertConfigValue(pid, "foo", "b", TIMEOUT);
        installer.removeResource(new InstallableResource(b.getUrl()));
        assertConfigValue(pid, "foo", "a", TIMEOUT);
        installer.removeResource(new InstallableResource(a.getUrl()));
        waitForConfiguration("After removing all resources", pid, TIMEOUT, false);
    }
}