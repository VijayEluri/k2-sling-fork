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
package org.apache.jackrabbit.core;

import org.apache.jackrabbit.core.nodetype.NodeDefinitionImpl;
import org.apache.jackrabbit.core.nodetype.NodeTypeImpl;
import org.apache.jackrabbit.core.state.ChildNodeEntry;
import org.apache.jackrabbit.core.state.NodeState;
import org.apache.jackrabbit.core.value.InternalValue;
import org.apache.jackrabbit.spi.Name;

import javax.jcr.AccessDeniedException;
import javax.jcr.ItemExistsException;
import javax.jcr.ItemNotFoundException;
import javax.jcr.PathNotFoundException;
import javax.jcr.Property;
import javax.jcr.RepositoryException;
import javax.jcr.Value;
import javax.jcr.lock.LockException;
import javax.jcr.nodetype.ConstraintViolationException;
import javax.jcr.version.VersionException;

/**
 * <code>DynamicSecurityItemModifier</code>: An abstract helper class to allow classes
 * of the security API residing outside of the core package to modify and remove
 * protected items for security. The protected item definitions are required in
 * order not to have security relevant content being changed through common
 * item operations but forcing the usage of the security API. The latter asserts
 * that implementation specific constraints are not violated.
 */
public abstract class DynamicSecurityItemModifier {

    protected DynamicSecurityItemModifier() {
        Class<?> cl = getClass();
        if (!(cl.equals(org.apache.sling.jcr.jackrabbit.server.impl.security.standard.ACLEditor.class))) {
            throw new IllegalArgumentException("Only ACLEditor may extend from the DynamicSecurityItemModifier");
        }
    }

    protected NodeImpl addSecurityNode(NodeImpl parentImpl, Name name, Name ntName) throws RepositoryException, PathNotFoundException, LockException, ConstraintViolationException, ItemExistsException, VersionException {
        synchronized (parentImpl) {
            // validation: make sure Node is not locked or checked-in.
            parentImpl.checkSetProperty();

            NodeTypeImpl nodeType = parentImpl.session.getNodeTypeManager().getNodeType(ntName);
            NodeDefinitionImpl def = parentImpl.getApplicableChildNodeDefinition(name, ntName);

            // check for name collisions
            // TODO: improve. copied from NodeImpl
            NodeState thisState = (NodeState) parentImpl.getItemState();
            ChildNodeEntry cne = thisState.getChildNodeEntry(name, 1);
            if (cne != null) {
                // there's already a child node entry with that name;
                // check same-name sibling setting of new node
                if (!def.allowsSameNameSiblings()) {
                    throw new ItemExistsException();
                }
                // check same-name sibling setting of existing node
                NodeId newId = cne.getId();
                NodeImpl n = (NodeImpl) parentImpl.session.getItemManager().getItem(newId);
                if (!n.getDefinition().allowsSameNameSiblings()) {
                    throw new ItemExistsException();
                }
            }

            return parentImpl.createChildNode(name, def, nodeType, null);
        }
    }

    protected Property setSecurityProperty(NodeImpl parentImpl, Name name, Value value) throws RepositoryException, PathNotFoundException, LockException, ConstraintViolationException, ItemExistsException, VersionException {
        synchronized (parentImpl) {
            // validation: make sure Node is not locked or checked-in.
            parentImpl.checkSetProperty();
            InternalValue intVs = InternalValue.create(value, parentImpl.session);
            return parentImpl.internalSetProperty(name, intVs);
        }
    }

    protected Property setSecurityProperty(NodeImpl parentImpl, Name name, Value[] values) throws RepositoryException, PathNotFoundException, LockException, ConstraintViolationException, ItemExistsException, VersionException {
        synchronized (parentImpl) {
            // validation: make sure Node is not locked or checked-in.
            parentImpl.checkSetProperty();
            InternalValue[] intVs = new InternalValue[values.length];
            for (int i = 0; i < values.length; i++) {
                intVs[i] = InternalValue.create(values[i], parentImpl.session);
            }
            return parentImpl.internalSetProperty(name, intVs);
        }
    }

    protected void removeSecurityItem(ItemImpl itemImpl) throws LockException, VersionException, AccessDeniedException, ItemNotFoundException, RepositoryException {
        NodeImpl n = (itemImpl.isNode()) ? (NodeImpl) itemImpl : (NodeImpl) itemImpl.getParent();
        synchronized (n) {
            // validation: make sure Node is not locked or checked-in.
            n.checkSetProperty();
            itemImpl.internalRemove(true);
        }
    }
}