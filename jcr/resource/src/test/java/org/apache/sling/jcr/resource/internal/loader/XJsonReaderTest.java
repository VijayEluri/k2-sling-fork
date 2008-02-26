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
package org.apache.sling.jcr.resource.internal.loader;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import javax.jcr.PropertyType;

import junit.framework.TestCase;

import org.apache.sling.commons.json.JSONArray;
import org.apache.sling.commons.json.JSONException;
import org.apache.sling.commons.json.JSONObject;

public class XJsonReaderTest extends TestCase {

    XJsonReader jsonReader;

    protected void setUp() throws Exception {
        super.setUp();
        this.jsonReader = new XJsonReader();
    }

    protected void tearDown() throws Exception {
        this.jsonReader = null;
        super.tearDown();
    }

    public void testEmptyObject() {
        try {
            this.parse("");
        } catch (IOException ioe) {
            fail("Expected IOException from empty JSON");
        }
    }

    public void testEmpty() throws IOException {
        Node node = this.parse("{}");
        assertNotNull("Expecting node", node);
        assertNull("No name expected", node.getName());
    }

    public void testDefaultPrimaryNodeType() throws IOException {
        String json = "{}";
        Node node = this.parse(json);
        assertNotNull("Expecting node", node);
        assertEquals("nt:unstructured", node.getPrimaryNodeType());
        assertNull("No mixins expected", node.getMixinNodeTypes());
        assertNull("No properties expected", node.getProperties());
        assertNull("No children expected", node.getChildren());
    }

    public void testDefaultPrimaryNodeTypeWithSurroundWhitespace() throws IOException {
        String json = "     {  }     ";
        Node node = this.parse(json);
        assertNotNull("Expecting node", node);
        assertEquals("nt:unstructured", node.getPrimaryNodeType());
        assertNull("No mixins expected", node.getMixinNodeTypes());
        assertNull("No properties expected", node.getProperties());
        assertNull("No children expected", node.getChildren());
    }

    public void testDefaultPrimaryNodeTypeWithoutEnclosingBraces() throws IOException {
        String json = "";
        Node node = this.parse(json);
        assertNotNull("Expecting node", node);
        assertEquals("nt:unstructured", node.getPrimaryNodeType());
        assertNull("No mixins expected", node.getMixinNodeTypes());
        assertNull("No properties expected", node.getProperties());
        assertNull("No children expected", node.getChildren());
    }

    public void testDefaultPrimaryNodeTypeWithoutEnclosingBracesWithSurroundWhitespace() throws IOException {
        String json = "             ";
        Node node = this.parse(json);
        assertNotNull("Expecting node", node);
        assertEquals("nt:unstructured", node.getPrimaryNodeType());
        assertNull("No mixins expected", node.getMixinNodeTypes());
        assertNull("No properties expected", node.getProperties());
        assertNull("No children expected", node.getChildren());
    }

    public void testExplicitePrimaryNodeType() throws IOException {
        String type = "xyz:testType";
        String json = "{ \"jcr:primaryType\": \"" + type + "\" }";

        Node node = this.parse(json);
        assertNotNull("Expecting node", node);
        assertEquals(type, node.getPrimaryNodeType());
    }

    public void testMixinNodeTypes1() throws JSONException, IOException {
        Set<Object> mixins = this.toSet(new Object[]{ "xyz:mix1" });
        String json = "{ \"jcr:mixinTypes\": " + this.toJsonArray(mixins) + "}";

        Node node = this.parse(json);
        assertNotNull("Expecting node", node);
        assertEquals(mixins, node.getMixinNodeTypes());
    }

    public void testMixinNodeTypes2() throws JSONException, IOException {
        Set<Object> mixins = this.toSet(new Object[]{ "xyz:mix1", "abc:mix2" });
        String json = "{ \"jcr:mixinTypes\": " + this.toJsonArray(mixins) + "}";

        Node node = this.parse(json);
        assertNotNull("Expecting node", node);
        assertEquals(mixins, node.getMixinNodeTypes());
    }

    public void testPropertiesNone() throws IOException, JSONException {
        List<Property> properties = null;
        String json = "{ \"properties\": " + this.toJsonObject(properties) + "}";

        Node node = this.parse(json);
        assertNotNull("Expecting node", node);
        assertEquals(properties, node.getProperties());
    }

    public void testPropertiesSingleValue() throws IOException, JSONException {
        List<Property> properties = new ArrayList<Property>();
        Property prop = new Property();
        prop.setName("p1");
        prop.setValue("v1");
        properties.add(prop);
        
        String json = this.toJsonObject(properties).toString();
        
        Node node = this.parse(json);
        assertNotNull("Expecting node", node);
        assertEquals(new HashSet<Property>(properties), new HashSet<Property>(node.getProperties()));
    }
    
    public void testPropertiesTwoSingleValue() throws IOException, JSONException {
        List<Property> properties = new ArrayList<Property>();
        Property prop = new Property();
        prop.setName("p1");
        prop.setValue("v1");
        properties.add(prop);
        prop = new Property();
        prop.setName("p2");
        prop.setValue("v2");
        properties.add(prop);

        String json = this.toJsonObject(properties).toString();

        Node node = this.parse(json);
        assertNotNull("Expecting node", node);
        assertEquals(new HashSet<Property>(properties), new HashSet<Property>(node.getProperties()));
    }

    public void testPropertiesMultiValue() throws IOException, JSONException {
        List<Property> properties = new ArrayList<Property>();
        Property prop = new Property();
        prop.setName("p1");
        prop.addValue("v1");
        properties.add(prop);

        String json = this.toJsonObject(properties).toString();

        Node node = this.parse(json);
        assertNotNull("Expecting node", node);
        assertEquals(new HashSet<Property>(properties), new HashSet<Property>(node.getProperties()));
    }

    public void testPropertiesMultiValueEmpty() throws IOException, JSONException {
        List<Property> properties = new ArrayList<Property>();
        Property prop = new Property();
        prop.setName("p1");
        prop.addValue(null); // empty multivalue property
        properties.add(prop);

        String json = this.toJsonObject(properties).toString();

        Node node = this.parse(json);
        assertNotNull("Expecting node", node);
        assertEquals(new HashSet<Property>(properties), new HashSet<Property>(node.getProperties()));
    }

    public void testChildrenNone() throws IOException, JSONException {
        List<Node> nodes = null;
        String json = this.toJsonObject(nodes).toString();

        Node node = this.parse(json);
        assertNotNull("Expecting node", node);
        assertEquals(nodes, node.getChildren());
    }

    public void testChild() throws IOException, JSONException {
        List<Node> nodes = new ArrayList<Node>();
        Node child = new Node();
        child.setName("p1");
        nodes.add(child);

        String json = this.toJsonObject(nodes).toString();

        Node node = this.parse(json);
        assertNotNull("Expecting node", node);
        assertEquals(nodes, node.getChildren());
    }

    public void testChildWithMixin() throws IOException, JSONException {
        List<Node> nodes = new ArrayList<Node>();
        Node child = new Node();
        child.setName("p1");
        child.addMixinNodeType("p1:mix");
        nodes.add(child);

        String json = this.toJsonObject(nodes).toString();

        Node node = this.parse(json);
        assertNotNull("Expecting node", node);
        assertEquals(nodes, node.getChildren());
    }

    public void testTwoChildren() throws IOException, JSONException {
        List<Node> nodes = new ArrayList<Node>();
        Node child = new Node();
        child.setName("p1");
        nodes.add(child);
        child = new Node();
        child.setName("p2");
        nodes.add(child);

        String json = this.toJsonObject(nodes).toString();

        Node node = this.parse(json);
        assertNotNull("Expecting node", node);
        assertEquals(nodes, node.getChildren());
    }

    public void testChildWithProperty() throws IOException, JSONException {
        List<Node> nodes = new ArrayList<Node>();
        Node child = new Node();
        child.setName("c1");
        Property prop = new Property();
        prop.setName("c1p1");
        prop.setValue("c1v1");
        child.addProperty(prop);
        nodes.add(child);

        String json = this.toJsonObject(nodes).toString();

        Node node = this.parse(json);
        assertNotNull("Expecting node", node);
        assertEquals(nodes, node.getChildren());
    }

    //---------- internal helper ----------------------------------------------

    private Node parse(String json) throws IOException {
        String charSet = "ISO-8859-1";
        json = "#" + charSet + "\r\n" + json;
        InputStream ins = new ByteArrayInputStream(json.getBytes(charSet));
        return this.jsonReader.parse(ins);
    }

    private Set<Object> toSet(Object[] content) {
        Set<Object> set = new HashSet<Object>();
        for (int i=0; content != null && i < content.length; i++) {
            set.add(content[i]);
        }

        return set;
    }

    private JSONArray toJsonArray(Collection<?> set) throws JSONException {
        List<Object> list = new ArrayList<Object>();
        for (Object item : set) {
            if (item instanceof Node) {
                list.add(this.toJsonObject((Node) item));
            } else {
                list.add(item);
            }
        }
        return new JSONArray(list);
    }

    private JSONObject toJsonObject(Collection<?> set) throws JSONException {
        JSONObject obj = new JSONObject();
        if (set != null) {
            for (Object next: set) {
                String name = this.getName(next);
                obj.putOpt(name, this.toJsonObject(next));
            }
        }
        return obj;
    }

    private Object toJsonObject(Object object) throws JSONException {
        if (object instanceof Node) {
            return this.toJsonObject((Node) object);
        } else if (object instanceof Property) {
            return this.toJsonObject((Property) object);
        }

        // fall back to string representation
        return String.valueOf(object);
    }

    private JSONObject toJsonObject(Node node) throws JSONException {
        JSONObject obj = new JSONObject();

        obj.putOpt("jcr:primaryType", node.getPrimaryNodeType());

        if (node.getMixinNodeTypes() != null) {
            obj.putOpt("jcr:mixinTypes", this.toJsonArray(node.getMixinNodeTypes()));
        }

        if (node.getProperties() != null) {
            for (Property prop : node.getProperties()) {
                obj.put(prop.getName(), toJsonObject(prop));
            }
        }

        if (node.getChildren() != null) {
            for (Node child : node.getChildren()) {
                obj.put(child.getName(), toJsonObject(child));
            }
        }

        return obj;
    }

    private Object toJsonObject(Property property) throws JSONException {
        if (!property.isMultiValue() && PropertyType.TYPENAME_STRING.equals(property.getType())) {
            return this.toJsonObject(property.getValue());
        }
        Object obj;
        if (property.isMultiValue()) {
            obj = this.toJsonArray(property.getValues());
        } else {
            obj = this.toJsonObject(property.getValue());
        }

        return obj;
    }

    private String getName(Object object) {
        if (object instanceof Node) {
            return ((Node) object).getName();
        } else if (object instanceof Property) {
            return ((Property) object).getName();
        }

        // fall back to string representation
        return String.valueOf(object);
    }
}