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
package org.apache.sling.configresource.impl;

import java.util.Map;

import org.apache.sling.api.resource.PersistableValueMap;
import org.apache.sling.api.resource.ResourceResolver;
import org.apache.sling.api.resource.ValueMap;
import org.osgi.service.cm.Configuration;

public class ConfigurationResource extends ConfigResource {

    private final Configuration configuration;

    public ConfigurationResource(String path, ResourceResolver resolver,
            Configuration config) {
        super(path, resolver);
        this.configuration = config;
    }

    protected Configuration getConfiguration() {
        return this.configuration;
    }

    @Override
    public String getResourceType() {
        return ConfigAdminProviderConstants.RESOURCE_TYPE_CONFIGURATION + "/" + this.configuration.getPid();
    }

    @Override
    public String getResourceSuperType() {
        return ConfigAdminProviderConstants.RESOURCE_TYPE_CONFIGURATION;
    }

    @SuppressWarnings("unchecked")
    @Override
    public <AdapterType> AdapterType adaptTo(Class<AdapterType> type) {
        if ( type == Map.class || type == ValueMap.class || type == PersistableValueMap.class) {
            return (AdapterType) new ConfigurationMap(this.configuration);
        }
        return super.adaptTo(type);
    }
}