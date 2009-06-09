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
package org.apache.sling.engine.impl.request;

import java.io.IOException;
import java.io.PrintWriter;
import java.util.Dictionary;
import java.util.Hashtable;
import java.util.Iterator;

import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.apache.felix.webconsole.AbstractWebConsolePlugin;
import org.apache.felix.webconsole.WebConsoleConstants;
import org.apache.sling.api.SlingHttpServletRequest;
import org.osgi.framework.BundleContext;
import org.osgi.framework.Constants;
import org.osgi.framework.ServiceRegistration;

/**
 * Felix OSGi console plugin that displays info about recent requests processed
 * by Sling. Info about all requests can be found in the logs, but this is
 * useful when testing or explaining things.
 */
@SuppressWarnings("serial")
public class RequestHistoryConsolePlugin extends AbstractWebConsolePlugin {

  public static final String LABEL = "requests";
  public static final String INDEX = "index";
  private static RequestHistoryConsolePlugin instance;
  private ServiceRegistration serviceRegistration;

  public static final int STORED_REQUESTS_COUNT = 20;
  private final SlingHttpServletRequest[] requests = new SlingHttpServletRequest[STORED_REQUESTS_COUNT];
  private int lastRequestIndex = -1;

  private RequestHistoryConsolePlugin() {
  }

  public static void recordRequest(SlingHttpServletRequest r) {
    if (instance == null) {
      return;
    }
    instance.addRequest(r);
  }

  private synchronized void addRequest(SlingHttpServletRequest r) {
    int index = lastRequestIndex + 1;
    if (index >= requests.length) {
      index = 0;
    }
    requests[index] = r;
    lastRequestIndex = index;
  }

  public static void initPlugin(BundleContext context) {
    if (instance == null) {
      RequestHistoryConsolePlugin tmp = new RequestHistoryConsolePlugin();
      tmp.activate(context);
      instance = tmp;
    }
  }

  public static void destroyPlugin() {
    if (instance != null) {
      try {
        instance.deactivate();
      } finally {
        instance = null;
      }
    }
  }

  public void activate(BundleContext context) {
    super.activate(context);

    Dictionary<String, Object> props = new Hashtable<String, Object>();
    props
        .put(Constants.SERVICE_DESCRIPTION,
            "Web Console Plugin to display information about recent Sling requests");
    props.put(Constants.SERVICE_VENDOR, "The Apache Software Foundation");
    props.put(Constants.SERVICE_PID, getClass().getName());
    props.put(WebConsoleConstants.PLUGIN_LABEL, LABEL);

    serviceRegistration = context.registerService(
        WebConsoleConstants.SERVICE_NAME, this, props);
  }

  public void deactivate() {
    if (serviceRegistration != null) {
      serviceRegistration.unregister();
      serviceRegistration = null;
    }
    super.deactivate();
  }

  @Override
  public String getLabel() {
    return LABEL;
  }

  @Override
  public String getTitle() {
    return "Recent requests";
  }

  @Override
  protected void renderContent(HttpServletRequest req, HttpServletResponse res)
      throws ServletException, IOException {

    // Select request to display
    int index = 0;
    final String tmp = req.getParameter(INDEX);
    if (tmp != null) {
      try {
        index = Integer.parseInt(tmp);
      } catch (NumberFormatException ignore) {
        // ignore
      }
    }

    // index is relative to lastRequestIndex
    int arrayIndex = lastRequestIndex - index;
    if (arrayIndex < 0) {
      arrayIndex += requests.length;
    }

    SlingHttpServletRequest r = null;
    try {
      r = requests[arrayIndex];
    } catch (ArrayIndexOutOfBoundsException ignore) {
      // ignore
    }

    final PrintWriter pw = res.getWriter();

    pw.println("<table class='content' cellpadding='0' cellspacing='0' width='100%'>");

    // Links to other requests
    pw.println("<thead>");
    pw.println("<tr class='content'>");
    pw.println("<th colspan='2'class='content container'>Recent Requests</th>");
    pw.println("</thead>");
    pw.println("<tbody>");
    pw.println("<tr class='content'><td>");
    for (int i = 0; i < requests.length; i++) {
      if (requests[i] != null) {
        final String info = (i == 0 ? " (latest)" : "");
        pw.print("<a href='" + LABEL + "?index=" + i + "'>");
        if (i == index) {
          pw.print("<b>");
        }
        pw.print("Request&nbsp;" + i + info);
        if (i == index) {
          pw.print("</b>");
        }
        pw.println("</a> ");
      }
    }
    pw.println("</td></tr>");

    if (r != null) {
      // Request Progress Tracker Info
      pw.println("<tr class='content'>");
      pw.println("<th colspan='2'class='content container'>");
      pw.print("Request " + index + " - RequestProgressTracker Info");
      pw.println("</th></tr>");
      pw.println("<tr><td colspan='2'>");
      final Iterator<String> it = r.getRequestProgressTracker().getMessages();
      pw.print("<pre>");
      while (it.hasNext()) {
        pw.print(it.next());
      }
      pw.println("</pre></td></tr>");
    }
    pw.println("</tbody></table>");
  }
}