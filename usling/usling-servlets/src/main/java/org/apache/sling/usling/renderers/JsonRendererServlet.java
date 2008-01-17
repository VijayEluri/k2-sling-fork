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
package org.apache.sling.usling.renderers;

import java.io.IOException;

import javax.jcr.Node;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletResponse;

import org.apache.sling.api.HttpStatusCodeException;
import org.apache.sling.api.SlingHttpServletRequest;
import org.apache.sling.api.SlingHttpServletResponse;
import org.apache.sling.api.resource.NonExistingResource;
import org.apache.sling.api.resource.Resource;
import org.apache.sling.api.servlets.SlingSafeMethodsServlet;
import org.apache.sling.commons.json.JSONException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** A SlingSafeMethodsServlet that renders the current Resource
 *  as a JSON data block
 */
public class JsonRendererServlet extends SlingSafeMethodsServlet {

    private static final Logger log = LoggerFactory.getLogger(JsonRendererServlet.class);

    private static final long serialVersionUID = 5577121546674133317L;
    private final String responseContentType;
    private final JsonItemWriter itemWriter;

    /** This optional request parameter sets the recursion level
     *  (into chldren) when dumping a node */
    public static final String PARAM_RECURSION_LEVEL = "maxlevels";

    public JsonRendererServlet(String responseContentTypeHeaderValue) {
        this.responseContentType = responseContentTypeHeaderValue;
        itemWriter = new JsonItemWriter(null);
    }

    @Override
    protected void doGet(SlingHttpServletRequest req,SlingHttpServletResponse resp)
    throws ServletException,IOException
    {
        // Access and check our data
        final Resource  r = req.getResource();
        if(r instanceof NonExistingResource) {
            throw new HttpStatusCodeException(HttpServletResponse.SC_NOT_FOUND, "No data to dump");
        }
        
        /* TODO
        // Do we have a SyntheticResourceData?
        if(r.adaptTo(SyntheticResourceData.class) != null) {
            renderSyntheticResource(req, resp);
            return;
        }
        */
        
        // Do we have a Property?
        final Property p = r.adaptTo(Property.class);
        if(p!=null) {
            try {
                renderProperty(p, resp);
            } catch(JSONException je) {
                reportException(je);
            } catch(RepositoryException re) {
                reportException(re);
            }
            return;
        }
        
        // Do we have a Node?
        final Node n = r.adaptTo(Node.class);
        if(n == null) {
            throw new HttpStatusCodeException(
                HttpServletResponse.SC_NOT_IMPLEMENTED, "Can only dump nodes");
        }

        // how many levels deep?
        int maxRecursionLevels = 0;
        final String depth = req.getParameter(PARAM_RECURSION_LEVEL);
        if (depth != null) {
          try {
            maxRecursionLevels = Integer.parseInt(depth);
          } catch(Exception e) {
            throw new HttpStatusCodeException(HttpServletResponse.SC_BAD_REQUEST,
                    "Invalid value '" + depth + "' for request parameter '" + PARAM_RECURSION_LEVEL + "'"
            );
          }
        }

        // do the dump
        resp.setContentType(responseContentType);
        try {
            itemWriter.dump(n, resp.getWriter(), maxRecursionLevels);
        } catch(JSONException je) {
            reportException(je);
        } catch(RepositoryException re) {
            reportException(re);
        }
    }
    
    /** Render a Property by dumping its String value */ 
    private void renderProperty(Property p, SlingHttpServletResponse resp) throws JSONException, RepositoryException, IOException {
        resp.setContentType(responseContentType);
        new JsonItemWriter(null).dump(p, resp.getWriter());
    }

    /** Render synthetic resources as empty JSON objects */
    private void renderSyntheticResource(SlingHttpServletRequest req,SlingHttpServletResponse resp) throws IOException {
        resp.setContentType(responseContentType);
        resp.getOutputStream().write("{}".getBytes());
    }

    private void reportException(Exception e) throws HttpStatusCodeException {
        log.warn("Error in JsonRendererServlet: " + e.toString(),e);
        throw new HttpStatusCodeException(
                HttpServletResponse.SC_INTERNAL_SERVER_ERROR,
                e.toString(),
                e
        );
    }
}