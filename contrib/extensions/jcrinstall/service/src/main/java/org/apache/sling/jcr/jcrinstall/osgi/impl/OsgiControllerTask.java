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
package org.apache.sling.jcr.jcrinstall.osgi.impl;

import static org.apache.sling.jcr.jcrinstall.osgi.InstallResultCode.IGNORED;

import java.io.IOException;
import java.util.Collection;
import java.util.Map;

import org.apache.sling.jcr.jcrinstall.osgi.InstallableData;
import org.apache.sling.jcr.jcrinstall.osgi.JcrInstallException;
import org.apache.sling.jcr.jcrinstall.osgi.OsgiResourceProcessor;
import org.apache.sling.jcr.jcrinstall.osgi.ResourceOverrideRules;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** An install/upgrade/uninistall task, meant to be executed
 * 	by the OsgiController worker thread.
 */
class OsgiControllerTask {
	
	private final String uri;
	private final InstallableData data;
	private final Storage storage;
	private final OsgiResourceProcessorList processors;
	private final ResourceOverrideRules roRules;
	
    private static final Logger log = LoggerFactory.getLogger(OsgiControllerTask.class);

    /** Create a task that will install, update or uninstall a resource.
     * @param data if not null, operation is install or update, else uninstall
     */
	OsgiControllerTask(
			Storage storage, 
			OsgiResourceProcessorList processors, 
			ResourceOverrideRules roRules, 
			String uri, 
			InstallableData data)
	{
		this.storage = storage;
		this.processors = processors;
		this.roRules = roRules;
		this.uri = uri;
		this.data = data;
	}
	
	void execute() throws JcrInstallException, IOException {
		if(data != null) {
			executeInstallOrUpdate();
		} else {
			executeUninstall();
		}
	}

	private void executeUninstall() throws JcrInstallException {
        // If a corresponding higher priority resource is installed, ignore this request
        if(roRules != null) {
            for(String r : roRules.getHigherPriorityResources(uri)) {
                if(storage.contains(r)) {
                    log.info("Resource {} won't be uninstalled, overridden by {} which has higher priority",
                            uri, r);
                    return;
                }
            }
        }
        
        try {
	        // let each processor try to uninstall, one of them
        	// should know how that handle uri
	    	for(OsgiResourceProcessor p : this.processors) {
	                p.uninstall(uri, storage.getMap(uri));
	    	}
	    	
	        storage.remove(uri);
	        storage.saveToFile();
	        
        } catch(Exception e) {
            throw new JcrInstallException("Exception in uninstall (" + uri + ")", e);
        }
	}

	private void executeInstallOrUpdate() throws JcrInstallException , IOException {
        // If a corresponding higher priority resource is already installed, ignore this one
        if(roRules != null) {
            for(String r : roRules.getHigherPriorityResources(uri)) {
                if(storage.contains(r)) {
                    log.info("Resource {} ignored, overridden by {} which has higher priority",
                            uri, r);
                    return;
                }
            }
        }
        
        // If a corresponding lower priority resource is installed, uninstall it first
        if(roRules != null) {
            for(String r : roRules.getLowerPriorityResources(uri)) {
                if(storage.contains(r)) {
                    log.info("Resource {} overrides {}, uninstalling the latter",
                            uri, r);
                    executeUninstall();
                }
            }
        }
        
        // let suitable OsgiResourceProcessor process install
        final OsgiResourceProcessor p = processors.getProcessor(uri, data);
        if (p != null) {
            try {
                final Map<String, Object> map = storage.getMap(uri);
                if(p.installOrUpdate(uri, map, data) != IGNORED) {
                    map.put(OsgiControllerImpl.KEY_DIGEST, data.getDigest());
                }
                storage.saveToFile();
            } catch(IOException ioe) {
                throw ioe;
            } catch(Exception e) {
                throw new JcrInstallException("Exception in installOrUpdate (" + uri + ")", e);
            }
        }
        return;
		
	}
}
