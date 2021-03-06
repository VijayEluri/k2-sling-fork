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
package org.apache.sling.jcr.contentloader.internal;

import static javax.jcr.ImportUUIDBehavior.IMPORT_UUID_CREATE_NEW;

import java.io.IOException;
import java.io.InputStream;
import java.io.UnsupportedEncodingException;
import java.net.URL;
import java.net.URLConnection;
import java.net.URLDecoder;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.StringTokenizer;

import javax.jcr.InvalidSerializedDataException;
import javax.jcr.Item;
import javax.jcr.Node;
import javax.jcr.RepositoryException;
import javax.jcr.Session;

import org.apache.sling.jcr.contentloader.internal.readers.JsonReader;
import org.apache.sling.jcr.contentloader.internal.readers.XmlReader;
import org.apache.sling.jcr.contentloader.internal.readers.ZipReader;
import org.osgi.framework.Bundle;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * The <code>Loader</code> loads initial content from the bundle.
 */
public class Loader {

    public static final String EXT_XML = ".xml";

    public static final String EXT_JCR_XML = ".jcr.xml";

    public static final String EXT_JSON = ".json";

    public static final String EXT_JAR = ".jar";

    public static final String EXT_ZIP = ".zip";

    public static final String ROOT_DESCRIPTOR = "/ROOT";

    /** default log */
    private final Logger log = LoggerFactory.getLogger(Loader.class);

    private ContentLoaderService jcrContentHelper;

    /** All available import providers. */
    private Map<String, ImportProvider> defaultImportProviders;

    private final DefaultContentCreator contentCreator;

    // bundles whose registration failed and should be retried
    private List<Bundle> delayedBundles;

    public Loader(ContentLoaderService jcrContentHelper) {
        this.jcrContentHelper = jcrContentHelper;
        this.contentCreator = new DefaultContentCreator(jcrContentHelper);
        this.delayedBundles = new LinkedList<Bundle>();

        defaultImportProviders = new LinkedHashMap<String, ImportProvider>();
        defaultImportProviders.put(EXT_JCR_XML, null);
        defaultImportProviders.put(EXT_JSON, JsonReader.PROVIDER);
        defaultImportProviders.put(EXT_XML, XmlReader.PROVIDER);
        defaultImportProviders.put(EXT_JAR, ZipReader.JAR_PROVIDER);
        defaultImportProviders.put(EXT_ZIP, ZipReader.ZIP_PROVIDER);
    }

    public void dispose() {
        if (delayedBundles != null) {
            delayedBundles.clear();
            delayedBundles = null;
        }
        jcrContentHelper = null;
        defaultImportProviders = null;
    }

    /**
     * Register a bundle and install its content.
     *
     * @param session
     * @param bundle
     */
    public void registerBundle(final Session session,
                               final Bundle bundle,
                               final boolean isUpdate) {
        // if this is an update, we have to uninstall the old content first
        if ( isUpdate ) {
            this.unregisterBundle(session, bundle);
        }

        log.debug("Registering bundle {} for content loading.",
            bundle.getSymbolicName());

        if (registerBundleInternal(session, bundle, false, isUpdate)) {

            // handle delayed bundles, might help now
            int currentSize = -1;
            for (int i = delayedBundles.size(); i > 0
                && currentSize != delayedBundles.size()
                && !delayedBundles.isEmpty(); i--) {

                for (Iterator<Bundle> di = delayedBundles.iterator(); di.hasNext();) {

                    Bundle delayed = di.next();
                    if (registerBundleInternal(session, delayed, true, false)) {
                        di.remove();
                    }

                }

                currentSize = delayedBundles.size();
            }

        } else if (!isUpdate) {
            // add to delayed bundles - if this is not an update!
            delayedBundles.add(bundle);
        }
    }

    private boolean registerBundleInternal(final Session session,
            final Bundle bundle, final boolean isRetry, final boolean isUpdate) {

        // check if bundle has initial content
        final Iterator<PathEntry> pathIter = PathEntry.getContentPaths(bundle);
        if (pathIter == null) {
            log.debug("Bundle {} has no initial content",
                bundle.getSymbolicName());
            return true;
        }

        try {

            // check if the content has already been loaded
            final Map<String, Object> bundleContentInfo = jcrContentHelper.getBundleContentInfo(
                session, bundle, true);

            // if we don't get an info, someone else is currently loading
            if (bundleContentInfo == null) {
                return false;
            }

            boolean success = false;
            List<String> createdNodes = null;
            try {

                final boolean contentAlreadyLoaded = ((Boolean) bundleContentInfo.get(ContentLoaderService.PROPERTY_CONTENT_LOADED)).booleanValue();

                if (!isUpdate && contentAlreadyLoaded) {

                    log.info("Content of bundle already loaded {}.",
                        bundle.getSymbolicName());

                } else {

                    createdNodes = installContent(session, bundle, pathIter,
                        contentAlreadyLoaded);

                    if (isRetry) {
                        // log success of retry
                        log.info(
                            "Retrytring to load initial content for bundle {} succeeded.",
                            bundle.getSymbolicName());
                    }

                }

                success = true;
                return true;

            } finally {
                jcrContentHelper.unlockBundleContentInfo(session, bundle,
                    success, createdNodes);
            }

        } catch (RepositoryException re) {
            // if we are retrying we already logged this message once, so we
            // won't log it again
            if (!isRetry) {
                log.error("Cannot load initial content for bundle "
                    + bundle.getSymbolicName() + " : " + re.getMessage(), re);
            }
        }

        return false;
    }

    /**
     * Unregister a bundle. Remove installed content.
     *
     * @param bundle The bundle.
     */
    public void unregisterBundle(final Session session, final Bundle bundle) {

        if (delayedBundles.contains(bundle)) {

            delayedBundles.remove(bundle);

        } else {
            try {
                final Map<String, Object> bundleContentInfo = jcrContentHelper.getBundleContentInfo(
                        session, bundle, false);

                // if we don't get an info, someone else is currently loading or unloading
                // or the bundle is already uninstalled
                if (bundleContentInfo == null) {
                    return;
                }

                try {
                    uninstallContent(session, bundle, (String[])bundleContentInfo.get(ContentLoaderService.PROPERTY_UNINSTALL_PATHS));
                    jcrContentHelper.contentIsUninstalled(session, bundle);
                } finally {
                    jcrContentHelper.unlockBundleContentInfo(session, bundle, false, null);

                }
            } catch (RepositoryException re) {
                log.error("Cannot remove initial content for bundle "
                        + bundle.getSymbolicName() + " : " + re.getMessage(), re);
            }
        }
    }

    // ---------- internal -----------------------------------------------------

    /**
     * Install the content from the bundle.
     * @return If the content should be removed on uninstall, a list of top nodes
     */
    private List<String> installContent(final Session session,
                                        final Bundle bundle,
                                        final Iterator<PathEntry> pathIter,
                                        final boolean contentAlreadyLoaded)
    throws RepositoryException {
        final List<String> createdNodes = new ArrayList<String>();

        log.debug("Installing initial content from bundle {}",
            bundle.getSymbolicName());
        try {

            while (pathIter.hasNext()) {
                final PathEntry entry = pathIter.next();
                if (!contentAlreadyLoaded || entry.isOverwrite()) {

                    final Node targetNode = getTargetNode(session, entry.getTarget());

                    if (targetNode != null) {
                        installFromPath(bundle, entry.getPath(), entry, targetNode,
                            entry.isUninstall() ? createdNodes : null);
                    }
                }
            }

            // now optimize created nodes list
            Collections.sort(createdNodes);
            if ( createdNodes.size() > 1) {
                final Iterator<String> i = createdNodes.iterator();
                String previous = i.next() + '/';
                while ( i.hasNext() ) {
                    final String current = i.next();
                    if ( current.startsWith(previous) ) {
                        i.remove();
                    } else {
                        previous = current + '/';
                    }
                }
            }

            // persist modifications now
            session.refresh(true);
            session.save();

            // finally checkin versionable nodes
            for (final Node versionable : this.contentCreator.getVersionables()) {
                versionable.checkin();
            }

        } finally {
            try {
                if (session.hasPendingChanges()) {
                    session.refresh(false);
                }
            } catch (RepositoryException re) {
                log.warn(
                    "Failure to rollback partial initial content for bundle {}",
                    bundle.getSymbolicName(), re);
            }
            this.contentCreator.clear();
        }
        log.debug("Done installing initial content from bundle {}",
            bundle.getSymbolicName());

        return createdNodes;
    }

    /**
     * Handle content installation for a single path.
     *
     * @param bundle The bundle containing the content.
     * @param path The path
     * @param overwrite Should the content be overwritten.
     * @param parent The parent node.
     * @param createdNodes An optional list to store all new nodes. This list is used for an uninstall
     * @throws RepositoryException
     */
    private void installFromPath(final Bundle bundle,
                                 final String path,
                                 final PathEntry configuration,
                                 final Node parent,
                                 final List<String> createdNodes)
    throws RepositoryException {

        @SuppressWarnings("unchecked")
        Enumeration<String> entries = bundle.getEntryPaths(path);
        if (entries == null) {
            log.info("install: No initial content entries at {}", path);
            return;
        }
        //  init content creator
        this.contentCreator.init(configuration, this.defaultImportProviders, createdNodes);

        final Map<URL, Node> processedEntries = new HashMap<URL, Node>();
        // potential root node import/extension
        URL rootNodeDescriptor = importRootNode(parent.getSession(), bundle, path);
        if (rootNodeDescriptor != null) {
            processedEntries.put(rootNodeDescriptor,
                parent.getSession().getRootNode());
        }

        while (entries.hasMoreElements()) {
            final String entry = entries.nextElement();
            log.debug("Processing initial content entry {}", entry);
            if (entry.endsWith("/")) {

                // dir, check for node descriptor , else create dir
                final String base = entry.substring(0, entry.length() - 1);

                URL nodeDescriptor = null;
                for (String ext : this.contentCreator.getImportProviders().keySet()) {
                    nodeDescriptor = bundle.getEntry(base + ext);
                    if (nodeDescriptor != null) {
                        break;
                    }
                }

                // if we have a descriptor, which has not been processed yet,
                // otherwise call createFolder, which creates an nt:folder or
                // returns an existing node (created by a descriptor)
                final String name = getName(base);
                Node node = null;
                if (nodeDescriptor != null) {
                    node = processedEntries.get(nodeDescriptor);
                    if (node == null) {
                        node = createNode(parent, name, nodeDescriptor,
                                          configuration);
                        processedEntries.put(nodeDescriptor, node);
                    }
                } else {
                    node = createFolder(parent, name, configuration.isOverwrite());
                }

                // walk down the line
                if (node != null) {
                    installFromPath(bundle, entry, configuration, node, createdNodes);
                }

            } else {

                // file => create file
                final URL file = bundle.getEntry(entry);
                if (processedEntries.containsKey(file)) {
                    // this is a consumed node descriptor
                    continue;
                }
                final String name = getName(entry);

                // file, check for node descriptor , else create dir
                URL nodeDescriptor = null;
                for (String ext : this.contentCreator.getImportProviders().keySet()) {
                    nodeDescriptor = bundle.getEntry(entry + ext);
                    if (nodeDescriptor != null) {
                        break;
                    }
                }

                // install if it is a descriptor
                boolean foundProvider = this.contentCreator.getImportProvider(entry) != null;

                Node node = null;
                if (foundProvider) {
                    if ((node = createNode(parent, name, file, configuration)) != null) {
                        processedEntries.put(file, node);
                    }
                }

                // otherwise just place as file
                if ( node == null ) {
                    try {
                        createFile(configuration, parent, file, createdNodes);
                        node = parent.getNode(name);
                    } catch (IOException ioe) {
                        log.warn("Cannot create file node for {}", file, ioe);
                    }
                }
                // if we have a descriptor, which has not been processed yet,
                // process it
                if (nodeDescriptor != null && processedEntries.get(nodeDescriptor) == null ) {
                    try {
                        this.contentCreator.setIgnoreOverwriteFlag(true);
                        node = createNode(parent, name, nodeDescriptor,
                                          configuration);
                        processedEntries.put(nodeDescriptor, node);
                    } finally {
                        this.contentCreator.setIgnoreOverwriteFlag(false);
                    }
                }
            }
        }
    }

    /**
     * Create a new node from a content resource found in the bundle.
     * @param parent The parent node
     * @param name   The name of the new content node
     * @param resourceUrl The resource url.
     * @param overwrite Should the content be overwritten?
     * @param versionables
     * @param checkin
     * @return
     * @throws RepositoryException
     */
    private Node createNode(Node parent,
                            String name,
                            URL resourceUrl,
                            PathEntry configuration)
    throws RepositoryException {
        final String resourcePath = resourceUrl.getPath().toLowerCase();
        InputStream ins = null;
        try {
            // special treatment for system view imports
            if (resourcePath.endsWith(EXT_JCR_XML)) {
                return importSystemView(parent, name, resourceUrl);
            }

            // get the node reader for this resource
            final ImportProvider ip = this.contentCreator.getImportProvider(resourcePath);
            if ( ip == null ) {
                return null;
            }
            final ContentReader nodeReader = ip.getReader();

            // cannot find out the type
            if (nodeReader == null) {
                return null;
            }

            this.contentCreator.prepareParsing(parent, toPlainName(name));
            nodeReader.parse(resourceUrl, this.contentCreator);

            return this.contentCreator.getRootNode();
        } catch (RepositoryException re) {
            throw re;
        } catch (Throwable t) {
            throw new RepositoryException(t.getMessage(), t);
        } finally {
            if (ins != null) {
                try {
                    ins.close();
                } catch (IOException ignore) {
                }
            }
        }
    }

    /**
     * Create a folder
     *
     * @param parent The parent node.
     * @param name The name of the folder
     * @param overwrite If set to true, an existing folder is removed first.
     * @return The node pointing to the folder.
     * @throws RepositoryException
     */
    private Node createFolder(Node parent, String name, final boolean overwrite)
            throws RepositoryException {
        if (parent.hasNode(name)) {
            if (overwrite) {
                parent.getNode(name).remove();
            } else {
                return parent.getNode(name);
            }
        }

        return parent.addNode(name, "sling:Folder");
    }

    /**
     * Create a file from the given url.
     *
     * @param parent
     * @param source
     * @throws IOException
     * @throws RepositoryException
     */
    private void createFile(PathEntry configuration, Node parent, URL source, List<String> createdNodes)
    throws IOException, RepositoryException {
        final String srcPath = source.getPath();
        int pos = srcPath.lastIndexOf("/");
        final String name = getName(source.getPath());
        final String path;
        if ( pos == -1 ) {
            path = name;
        } else {
            path = srcPath.substring(0, pos + 1) + name;
        }

        this.contentCreator.init(configuration, defaultImportProviders, createdNodes);
        this.contentCreator.prepareParsing(parent, name);
        final URLConnection conn = source.openConnection();
        final long lastModified = conn.getLastModified();
        final String type = conn.getContentType();
        final InputStream data = conn.getInputStream();
        this.contentCreator.createFileAndResourceNode(path, data, type, lastModified);
        this.contentCreator.finishNode();
        this.contentCreator.finishNode();
    }

    /**
     * Gets and decods the name part of the <code>path</code>. The name is
     * the part of the path after the last slash (or the complete path if no
     * slash is contained). To support names containing unsupported characters
     * such as colon (<code>:</code>), names may be URL encoded (see
     * <code>java.net.URLEncoder</code>) using the <i>UTF-8</i> character
     * encoding. In this case, this method decodes the name using the
     * <code>java.netURLDecoder</code> class with the <i>UTF-8</i> character
     * encoding.
     *
     * @param path The path from which to extract the name part.
     * @return The URL decoded name part.
     */
    private String getName(String path) {
        int lastSlash = path.lastIndexOf('/');
        String name = (lastSlash < 0) ? path : path.substring(lastSlash + 1);

        // check for encoded characters (%xx)
        // has encoded characters, need to decode
        if (name.indexOf('%') >= 0) {
            try {
                return URLDecoder.decode(name, "UTF-8");
            } catch (UnsupportedEncodingException uee) {
                // actually unexpected because UTF-8 is required by the spec
                log.error("Cannot decode "
                    + name
                    + " beause the platform has no support for UTF-8, using undecoded");
            } catch (Exception e) {
                // IllegalArgumentException or failure to decode
                log.error("Cannot decode " + name + ", using undecoded", e);
            }
        }

        // not encoded or problems decoding, return the name unmodified
        return name;
    }

    private Node getTargetNode(Session session, String path)
            throws RepositoryException {

        // not specyfied path directive
        if (path == null) return session.getRootNode();

        int firstSlash = path.indexOf("/");

        // it's a relative path
        if (firstSlash != 0) path = "/" + path;

        if ( !session.itemExists(path) ) {
            Node currentNode = session.getRootNode();
            final StringTokenizer st = new StringTokenizer(path.substring(1), "/");
            while ( st.hasMoreTokens() ) {
                final String name = st.nextToken();
                if ( !currentNode.hasNode(name) ) {
                    currentNode.addNode(name, "sling:Folder");
                }
                currentNode = currentNode.getNode(name);
            }
            return currentNode;
        }
        Item item = session.getItem(path);
        return (item.isNode()) ? (Node) item : null;
    }

    private void uninstallContent(final Session session, final Bundle bundle,
            final String[] uninstallPaths) {
        try {
            log.debug("Uninstalling initial content from bundle {}",
                bundle.getSymbolicName());
            if ( uninstallPaths != null && uninstallPaths.length > 0 ) {
                for(final String path : uninstallPaths) {
                    if ( session.itemExists(path) ) {
                        session.getItem(path).remove();
                    }
                }
                // persist modifications now
                session.save();
            }

            log.debug("Done uninstalling initial content from bundle {}",
                bundle.getSymbolicName());
        } catch (RepositoryException re) {
            log.error("Unable to uninstall initial content from bundle "
                + bundle.getSymbolicName(), re);
        } finally {
            try {
                if (session.hasPendingChanges()) {
                    session.refresh(false);
                }
            } catch (RepositoryException re) {
                log.warn(
                    "Failure to rollback uninstaling initial content for bundle {}",
                    bundle.getSymbolicName(), re);
            }
        }
    }

    /**
     * Import the XML file as JCR system or document view import. If the XML
     * file is not a valid system or document view export/import file,
     * <code>false</code> is returned.
     *
     * @param parent The parent node below which to import
     * @param nodeXML The URL to the XML file to import
     * @return <code>true</code> if the import succeeds, <code>false</code>
     *         if the import fails due to XML format errors.
     * @throws IOException If an IO error occurrs reading the XML file.
     */
    private Node importSystemView(Node parent, String name, URL nodeXML)
    throws IOException {

        InputStream ins = null;
        try {

            // check whether we have the content already, nothing to do then
            if ( name.endsWith(EXT_JCR_XML) ) {
                name = name.substring(0, name.length() - EXT_JCR_XML.length());
            }
            if (parent.hasNode(name)) {
                log.debug(
                    "importSystemView: Node {} for XML {} already exists, nothing to to",
                    name, nodeXML);
                return parent.getNode(name);
            }

            ins = nodeXML.openStream();
            Session session = parent.getSession();
            session.importXML(parent.getPath(), ins, IMPORT_UUID_CREATE_NEW);

            // additionally check whether the expected child node exists
            return (parent.hasNode(name)) ? parent.getNode(name) : null;

        } catch (InvalidSerializedDataException isde) {

            // the xml might not be System or Document View export, fall back
            // to old-style XML reading
            log.info(
                "importSystemView: XML {} does not seem to be system view export, trying old style; cause: {}",
                nodeXML, isde.toString());
            return null;

        } catch (RepositoryException re) {

            // any other repository related issue...
            log.info(
                "importSystemView: Repository issue loading XML {}, trying old style; cause: {}",
                nodeXML, re.toString());
            return null;

        } finally {
            if (ins != null) {
                try {
                    ins.close();
                } catch (IOException ignore) {
                    // ignore
                }
            }
        }

    }

    protected static final class Descriptor {
        public URL rootNodeDescriptor;

        public ContentReader nodeReader;
    }

    /**
     * Return the root node descriptor.
     */
    private Descriptor getRootNodeDescriptor(final Bundle bundle,
                                             final String path) {
        URL rootNodeDescriptor = null;

        for (Map.Entry<String, ImportProvider> e : this.contentCreator.getImportProviders().entrySet()) {
            if (e.getValue() != null) {
                rootNodeDescriptor = bundle.getEntry(path + ROOT_DESCRIPTOR
                    + e.getKey());
                if (rootNodeDescriptor != null) {
                    try {
                        final Descriptor d = new Descriptor();
                        d.rootNodeDescriptor = rootNodeDescriptor;
                        d.nodeReader = e.getValue().getReader();
                        return d;
                    } catch (IOException ioe) {
                        log.error("Unable to setup node reader for "
                            + e.getKey(), ioe);
                        return null;
                    }
                }
            }
        }
        return null;
    }

    /**
     * Imports mixin nodes and properties (and optionally child nodes) of the
     * root node.
     */
    private URL importRootNode(Session session, Bundle bundle, String path)
    throws RepositoryException {
        final Descriptor descriptor = getRootNodeDescriptor(bundle, path);
        // no root descriptor found
        if (descriptor == null) {
            return null;
        }

        try {
            this.contentCreator.prepareParsing(session.getRootNode(), null);
            descriptor.nodeReader.parse(descriptor.rootNodeDescriptor, this.contentCreator);

            return descriptor.rootNodeDescriptor;
        } catch (RepositoryException re) {
            throw re;
        } catch (Throwable t) {
            throw new RepositoryException(t.getMessage(), t);
        }

    }

    private String toPlainName(String name) {
        final String providerExt = this.contentCreator.getImportProviderExtension(name);
        if (providerExt != null) {
            return name.substring(0, name.length() - providerExt.length());
        }
        return name;

    }
}
