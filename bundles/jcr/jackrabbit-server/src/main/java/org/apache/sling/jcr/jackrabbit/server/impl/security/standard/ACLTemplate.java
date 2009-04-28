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
package org.apache.sling.jcr.jackrabbit.server.impl.security.standard;

import org.apache.commons.collections.map.ListOrderedMap;
import org.apache.jackrabbit.api.jsr283.security.AccessControlEntry;
import org.apache.jackrabbit.api.jsr283.security.AccessControlException;
import org.apache.jackrabbit.api.jsr283.security.AccessControlManager;
import org.apache.jackrabbit.api.jsr283.security.Privilege;
import org.apache.jackrabbit.api.security.principal.PrincipalManager;
import org.apache.jackrabbit.core.NodeImpl;
import org.apache.jackrabbit.core.SessionImpl;
import org.apache.jackrabbit.core.security.authorization.AccessControlConstants;
import org.apache.jackrabbit.core.security.authorization.AccessControlEntryImpl;
import org.apache.jackrabbit.core.security.authorization.JackrabbitAccessControlList;
import org.apache.jackrabbit.core.security.authorization.Permission;
import org.apache.jackrabbit.core.security.authorization.PrivilegeRegistry;

import java.security.Principal;
import java.security.acl.Group;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import javax.jcr.NodeIterator;
import javax.jcr.RepositoryException;
import javax.jcr.Value;

/**
 * <p>
 * Implementation of the {@link JackrabbitAccessControlList} interface that
 * is detached from the effective access control content. Consequently, any
 * modifications applied to this ACL only take effect, if the policy gets
 * {@link org.apache.jackrabbit.api.jsr283.security.AccessControlManager#setPolicy(String, org.apache.jackrabbit.api.jsr283.security.AccessControlPolicy) reapplied}
 * to the <code>AccessControlManager</code> and the changes are saved.
 * </p><p>
 * This is a modified version of the ACLTemplate that has the static collectEntries removed and replaced by a EntryCollector. This is the only change.
 * </p>
 */
@SuppressWarnings("unchecked")
class ACLTemplate implements JackrabbitAccessControlList {

    /**
     * Path of the node this ACL template has been created for.
     */
    private final String path;

    /**
     * Map containing the entries of this ACL Template using the principal
     * name as key. The value represents a List containing maximal one grant
     * and one deny ACE per principal.
     */
    private final Map<String,List<AccessControlEntry>> entries = new ListOrderedMap();

    /**
     * The principal manager used for validation checks
     */
    private final PrincipalManager principalMgr;

    /**
     * The privilege registry
     */
    private final PrivilegeRegistry privilegeRegistry;

    /**
     * Construct a new empty {@link ACLTemplate}.
     *
     * @param path
     * @param principalMgr
     */
    ACLTemplate(String path, PrincipalManager principalMgr, PrivilegeRegistry privilegeRegistry) {
        this.path = path;
        this.principalMgr = principalMgr;
        this.privilegeRegistry = privilegeRegistry;
    }

    /**
     * Create a {@link ACLTemplate} that is used to edit an existing ACL
     * node.
     */
    ACLTemplate(NodeImpl aclNode, PrivilegeRegistry privilegeRegistry) throws RepositoryException {
        if (aclNode == null || !aclNode.isNodeType(AccessControlConstants.NT_REP_ACL)) {
            throw new IllegalArgumentException("Node must be of type: " +
                    AccessControlConstants.NT_REP_ACL);
        }
        SessionImpl sImpl = (SessionImpl) aclNode.getSession();
        path = aclNode.getParent().getPath();
        principalMgr = sImpl.getPrincipalManager();
        this.privilegeRegistry = privilegeRegistry;

        // load the entries:
        AccessControlManager acMgr = sImpl.getAccessControlManager();
        NodeIterator itr = aclNode.getNodes();
        while (itr.hasNext()) {
            NodeImpl aceNode = (NodeImpl) itr.nextNode();

            String principalName = aceNode.getProperty(AccessControlConstants.P_PRINCIPAL_NAME).getString();
            Principal princ = principalMgr.getPrincipal(principalName);

            Value[] privValues = aceNode.getProperty(AccessControlConstants.P_PRIVILEGES).getValues();
            Privilege[] privs = new Privilege[privValues.length];
            for (int i = 0; i < privValues.length; i++) {
                privs[i] = acMgr.privilegeFromName(privValues[i].getString());
            }
            // create a new ACEImpl (omitting validation check)
            Entry ace = new Entry(
                    princ,
                    privs,
                    aceNode.isNodeType(AccessControlConstants.NT_REP_GRANT_ACE));
            // add the entry
            internalAdd(ace);
        }
    }


    private List<AccessControlEntry> internalGetEntries() {
        List<AccessControlEntry> l = new ArrayList<AccessControlEntry>();
        for (Iterator<List<AccessControlEntry>> it = entries.values().iterator(); it.hasNext();) {
            l.addAll(it.next());
        }
        return l;
    }

    private List<AccessControlEntry> internalGetEntries(Principal principal) {
        String principalName = principal.getName();
        if (entries.containsKey(principalName)) {
            return entries.get(principalName);
        } else {
            return new ArrayList<AccessControlEntry>(2);
        }
    }

    private synchronized boolean internalAdd(Entry entry) throws AccessControlException {
        Principal principal = entry.getPrincipal();
        List<AccessControlEntry> l = internalGetEntries(principal);
        if (l.isEmpty()) {
            // simple case: just add the new entry
            l.add(entry);
            entries.put(principal.getName(), l);
            return true;
        } else {
            if (l.contains(entry)) {
                // the same entry is already contained -> no modification
                return false;
            }
            // ev. need to adjust existing entries
            Entry complementEntry = null;
            Entry[] entries = (Entry[]) l.toArray(new Entry[l.size()]);
            for (int i = 0; i < entries.length; i++) {
                if (entry.isAllow() == entries[i].isAllow()) {
                    int existingPrivs = entries[i].getPrivilegeBits();
                    if ((existingPrivs | ~entry.getPrivilegeBits()) == -1) {
                        // all privileges to be granted/denied are already present
                        // in the existing entry -> not modified
                        return false;
                    }

                    // remove the existing entry and create a new that includes
                    // both the new privileges and the existing onces.
                    l.remove(i);
                    int mergedBits = entries[i].getPrivilegeBits() | entry.getPrivilegeBits();
                    Privilege[] mergedPrivs = privilegeRegistry.getPrivileges(mergedBits);
                    // omit validation check.
                    entry = new Entry(entry.getPrincipal(), mergedPrivs, entry.isAllow());
                } else {
                    complementEntry = entries[i];
                }
            }

            // make sure, that the complement entry (if existing) does not
            // grant/deny the same privileges -> remove privs that are now
            // denied/granted.
            if (complementEntry != null) {
                int complPrivs = complementEntry.getPrivilegeBits();
                int resultPrivs = Permission.diff(complPrivs, entry.getPrivilegeBits());
                if (resultPrivs == PrivilegeRegistry.NO_PRIVILEGE) {
                    l.remove(complementEntry);
                } else if (resultPrivs != complPrivs) {
                    l.remove(complementEntry);
                    // omit validation check
                    Entry tmpl = new Entry(entry.getPrincipal(),
                            privilegeRegistry.getPrivileges(resultPrivs),
                            !entry.isAllow());
                    l.add(tmpl);
                } /* else: does not need to be modified.*/
            }

            // finally add the new entry at the end.
            l.add(entry);
            return true;
        }
    }

    /**
     *
     * @param principal
     * @param privileges
     * @param isAllow
     * @throws AccessControlException
     */
    private void checkValidEntry(Principal principal, Privilege[] privileges, boolean isAllow) throws AccessControlException {
        // validate principal
        if (!principalMgr.hasPrincipal(principal.getName())) {
            throw new AccessControlException("Principal " + principal.getName() + " does not exist.");
        }
        // additional validation: a group may not have 'denied' permissions
        if (!isAllow && principal instanceof Group) {
            throw new AccessControlException("For group principals permissions can only be added but not denied.");
        }
    }

    //--------------------------------------------------< AccessControlList >---
    /**
     * @see org.apache.jackrabbit.api.jsr283.security.AccessControlList#getAccessControlEntries()
     */
    public AccessControlEntry[] getAccessControlEntries() throws RepositoryException {
        List<AccessControlEntry> l = internalGetEntries();
        return (AccessControlEntry[]) l.toArray(new AccessControlEntry[l.size()]);
    }

    /**
     * @see org.apache.jackrabbit.api.jsr283.security.AccessControlList#addAccessControlEntry(Principal, Privilege[])
     */
    public boolean addAccessControlEntry(Principal principal, Privilege[] privileges)
            throws AccessControlException, RepositoryException {
        return addEntry(principal, privileges, true, Collections.EMPTY_MAP);
    }

    /**
     * @see org.apache.jackrabbit.api.jsr283.security.AccessControlList#removeAccessControlEntry(AccessControlEntry)
     */
    public synchronized void removeAccessControlEntry(AccessControlEntry ace)
            throws AccessControlException, RepositoryException {
        if (!(ace instanceof Entry)) {
            throw new AccessControlException("Invalid AccessControlEntry implementation " + ace.getClass().getName() + ".");
        }
        List<AccessControlEntry> l = internalGetEntries(ace.getPrincipal());
        if (l.remove(ace)) {
            if (l.isEmpty()) {
                entries.remove(ace.getPrincipal().getName());
            }
        } else {
            throw new AccessControlException("AccessControlEntry " + ace + " cannot be removed from ACL defined at " + getPath());
        }
    }

    //-----------------------------------------------------< JackrabbitAccessControlList >---
    /**
     * @see JackrabbitAccessControlList#getPath()
     */
    public String getPath() {
        return path;
    }

    /**
     * @see JackrabbitAccessControlList#isEmpty()
     */
    public boolean isEmpty() {
        return entries.isEmpty();
    }

    /**
     * @see JackrabbitAccessControlList#size()
     */
    public int size() {
        return internalGetEntries().size();
    }

    /**
     * @see JackrabbitAccessControlList#addEntry(Principal, Privilege[], boolean)
     */
    public boolean addEntry(Principal principal, Privilege[] privileges, boolean isAllow)
            throws AccessControlException, RepositoryException {
        return addEntry(principal, privileges, isAllow, null);
    }

    /**
     * @see JackrabbitAccessControlList#addEntry(Principal, Privilege[], boolean, Map)
     */
    public boolean addEntry(Principal principal, Privilege[] privileges,
                            boolean isAllow, Map restrictions)
            throws AccessControlException, RepositoryException {
        if (restrictions != null && !restrictions.isEmpty()) {
            throw new AccessControlException("This AccessControlList does not allow for additional restrictions.");
        }

        checkValidEntry(principal, privileges, isAllow);
        Entry ace = new Entry(principal, privileges, isAllow);
        return internalAdd(ace);
    }

    //-------------------------------------------------------------< Object >---
    /**
     * Returns zero to satisfy the Object equals/hashCode contract.
     * This class is mutable and not meant to be used as a hash key.
     *
     * @return always zero
     * @see Object#hashCode()
     */
    public int hashCode() {
        return 0;
    }

    /**
     * Returns true if the path and the entries are equal; false otherwise.
     *
     * @param obj
     * @return true if the path and the entries are equal; false otherwise.
     * @see Object#equals(Object)
     */
    public boolean equals(Object obj) {
        if (obj == this) {
            return true;
        }

        if (obj instanceof ACLTemplate) {
          ACLTemplate acl = (ACLTemplate) obj;
            return path.equals(acl.path) && entries.equals(acl.entries);
        }
        return false;
    }

    //--------------------------------------------------------------------------
    /**
     *
     */
    static class Entry extends AccessControlEntryImpl {

        Entry(Principal principal, Privilege[] privileges, boolean allow) throws AccessControlException {
            super(principal, privileges, allow, Collections.EMPTY_MAP);
        }
    }
}
