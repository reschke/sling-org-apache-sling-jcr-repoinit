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
package org.apache.sling.jcr.repoinit;

import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.junit.Assert.fail;

import javax.jcr.AccessDeniedException;
import javax.jcr.Node;
import javax.jcr.PathNotFoundException;
import javax.jcr.RepositoryException;
import javax.jcr.Session;
import javax.jcr.nodetype.NodeTypeManager;
import javax.jcr.nodetype.NodeTypeTemplate;
import javax.jcr.security.Privilege;

import org.apache.jackrabbit.commons.jackrabbit.authorization.AccessControlUtils;
import org.apache.sling.jcr.repoinit.impl.TestUtil;
import org.apache.sling.repoinit.parser.RepoInitParsingException;
import org.apache.sling.testing.mock.sling.ResourceResolverType;
import org.apache.sling.testing.mock.sling.junit.SlingContext;
import org.junit.After;
import org.junit.Before;
import org.junit.Ignore;
import org.junit.Rule;
import org.junit.Test;
import org.junit.Test.None;

/** Various ACL-related tests */
public class GeneralAclTest {

    @Rule
    public final SlingContext context = new SlingContext(ResourceResolverType.JCR_OAK);

    private TestUtil U;
    private String userA;
    private String userB;

    private Session s;

    @Before
    public void setup() throws RepositoryException, RepoInitParsingException {
        U = new TestUtil(context);
        userA = "userA_" + U.id;
        userB = "userB_" + U.id;
        U.parseAndExecute("create service user " + U.username);
        U.parseAndExecute("create service user " + userA);
        U.parseAndExecute("create service user " + userB);

        s = U.loginService(U.username);
    }

    @After
    public void cleanup() throws RepositoryException, RepoInitParsingException {
        U.cleanupUser();
        s.logout();
    }

    @Test(expected=AccessDeniedException.class)
    public void getRootNodeIntiallyFails() throws Exception {
        s.getRootNode();
    }

    @Test
    public void readOnlyThenWriteThenDeny() throws Exception {
        final Node tmp = U.adminSession.getRootNode().addNode("tmp_" + U.id);
        U.adminSession.save();
        final String path = tmp.getPath();

        try {
            s.getNode(path);
            fail("Expected read access to be initially denied:" + path);
        } catch(PathNotFoundException ignore) {
        }

        final String allowRead =
                "set ACL for " + U.username + "\n"
                + "allow jcr:read on " + path + "\n"
                + "end"
                ;
        U.parseAndExecute(allowRead);
        final Node n = s.getNode(path);

        try {
            n.setProperty("U.id", U.id);
            s.save();
            fail("Expected write access to be initially denied:" + path);
        } catch(AccessDeniedException ignore) {
        }
        s.refresh(false);

        final String allowWrite =
                "set ACL for " + U.username + "\n"
                + "allow jcr:write on " + path + "\n"
                + "end"
                ;
        U.parseAndExecute(allowWrite);
        n.setProperty("U.id", U.id);
        s.save();

        final String deny =
                "set ACL for " + U.username + "\n"
                + "deny jcr:all on " + path + "\n"
                + "end"
                ;
        U.parseAndExecute(deny);
        try {
            s.getNode(path);
            fail("Expected access to be denied again:" + path);
        } catch(PathNotFoundException ignore) {
        }
    }

    @Test
    public void addChildAtRoot() throws Exception {
        final String nodename = "test_" + U.id;
        final String path = "/" + nodename;

        final String aclSetup =
            "set ACL for " + U.username + "\n"
            + "allow jcr:all on /\n"
            + "end"
            ;
        U.parseAndExecute(aclSetup);
        try {
            assertFalse(s.itemExists(path));
            s.getRootNode().addNode(nodename);
            s.save();
            assertTrue(s.nodeExists(path));
            s.getNode(path).remove();
            s.save();
            assertFalse(s.itemExists(path));
        } finally {
            s.logout();
        }
    }

    @Test
    public void addPathAclWithRepositoryPath() throws Exception {
        final String aclSetup =
                "set ACL on :repository\n"
                        + "allow jcr:namespaceManagement for "+U.username+"\n"
                        + "end"
                ;

        U.parseAndExecute(aclSetup);
        try {
            s.refresh(false);
            assertTrue(s.getAccessControlManager().hasPrivileges(null, AccessControlUtils.privilegesFromNames(s, "jcr:namespaceManagement")));
        } finally {
            s.logout();
        }
    }

    @Test
    public void addPrincipalAclWithRepositoryPath() throws Exception {
        final String aclSetup =
                "set ACL for " + U.username + "\n"
                        + "allow jcr:all on :repository,/\n"
                        + "end"
                ;

        U.parseAndExecute(aclSetup);
        try {
            s.refresh(false);
            assertTrue(s.getAccessControlManager().hasPrivileges(null, AccessControlUtils.privilegesFromNames(s, Privilege.JCR_ALL)));
            assertTrue(s.getAccessControlManager().hasPrivileges("/", AccessControlUtils.privilegesFromNames(s, Privilege.JCR_ALL)));
        } finally {
            s.logout();
        }
    }

    @Test
    public void addRepositoryAcl() throws Exception {
        final String aclSetup =
                "set repository ACL for " + userA + "," + userB + "\n"
                + "allow jcr:namespaceManagement\n"
                + "allow jcr:nodeTypeDefinitionManagement\n"
                + "end"
                ;

        U.parseAndExecute(aclSetup);
        verifyRegisterNamespace(userA, "a", "http://a", true);
        verifyRegisterNamespace(userB, "b", "http://b", true);
        verifyRegisterNamespace(U.username, "c", "http://c", false);
        verifyRegisterNodeType(userA, "typeA", true);
        verifyRegisterNodeType(userB, "typeB", true);
        verifyRegisterNodeType(U.username, "typeC", false);
    }

    @Test
    public void addRepositoryAclInMultipleBlocks() throws Exception {
        final String aclSetup =
                  "set repository ACL for " + userA + "\n"
                +    "allow jcr:namespaceManagement,jcr:nodeTypeDefinitionManagement\n"
                + "end\n"
                + "set repository ACL for " + userB + "\n"
                +    "allow jcr:namespaceManagement\n"
                + "end"
                ;

        U.parseAndExecute(aclSetup);
        verifyRegisterNamespace(userA, "a", "http://a", true);
        verifyRegisterNamespace(userB, "b", "http://b", true);
        verifyRegisterNodeType(userA, "typeA", true);
        verifyRegisterNodeType(userB, "typeB", false);
    }

    @Test
    public void addRepositoryAclInSequence() throws Exception {
        final String aclSetup =
                  "set repository ACL for " + U.username + "\n"
                +    "deny jcr:namespaceManagement,jcr:nodeTypeDefinitionManagement\n"
                +    "allow jcr:namespaceManagement,jcr:nodeTypeDefinitionManagement\n"
                + "end\n"
                + "set repository ACL for " + U.username + "\n"
                +    "deny jcr:namespaceManagement\n"
                + "end"
                ;

        U.parseAndExecute(aclSetup);
        verifyRegisterNodeType(U.username, "typeC", true);
        verifyRegisterNamespace(U.username, "c", "http://c", false);
    }

    /**
     * Verify the success/failure when registering a node type.
     * Registering a node type requires to be granted the jcr:nodeTypeDefinitionManagement privilege.
     */
    private void verifyRegisterNodeType(String username, String typeName, boolean successExpected) {
        Session userSession = null;
        try {
            userSession = U.loginService(username);
            NodeTypeManager nodeTypeManager = userSession.getWorkspace().getNodeTypeManager();
            NodeTypeTemplate type = nodeTypeManager.createNodeTypeTemplate();
            type.setName(typeName);
            nodeTypeManager.registerNodeType(type, true);
            assertTrue("Register node type succeeded " + typeName, successExpected);
        } catch (RepositoryException e) {
            assertTrue("Error registering node type " + typeName + " " + e.getMessage(), !successExpected);
        } finally {
            if (userSession != null) {
                userSession.logout();
            }
        }
    }


    /**
     * Verify the success/failure when registering a namespace.
     * Registering a namespace successfully requires to be granted the jcr:namespaceManagement privilege.
     */
    private void verifyRegisterNamespace(String username, String prefix, String uri, boolean successExpected) {
        Session userSession = null;
        try {
            userSession = U.loginService(username);
            userSession.getWorkspace().getNamespaceRegistry().registerNamespace(prefix, uri);
            assertTrue("Register namespace succeeded " + prefix + uri, successExpected);
        } catch (RepositoryException e) {
            assertTrue("Error registering namespace " + prefix + uri + " " + e.getMessage(), !successExpected);
        } finally {
            if (userSession != null) {
                userSession.logout();
            }
        }
    }

    /**
     * Verifies success/failure of adding a child
     * @param username
     * @param nodeName
     * @param successExpected
     * @throws RepositoryException
     */
   private void verifyAddChildNode(String username,String nodeName, boolean successExpected) throws RepositoryException {
       Session userSession = U.loginService(username);
       try {
           verifyAddChildNode(userSession,nodeName,successExpected);
       } finally {
           if(userSession != null) {
               userSession.logout();
           }
       }
   }

    /**
     * Verifies success/failure of adding a child
     */
    private void verifyAddChildNode(Session userSession, String nodeName, boolean successExpected) throws RepositoryException{
        Node  rootNode = userSession.getRootNode();
        verifyAddChildNode(rootNode, nodeName, successExpected);
    }

    /**
     * Verifies success/failure of adding a child
     */
    private void verifyAddChildNode(Node node, String nodeName, boolean successExpected) throws RepositoryException{
        try {
            node.addNode(nodeName);
            node.getSession().save();
            assertTrue("Added child node succeeded "+nodeName,successExpected);
        } catch(Exception e) {
            node.getSession().refresh(false);
            assertTrue("Error adding " + nodeName + " to " + node + e.getMessage(), !successExpected);
        }
    }

    /**
     * Verifies success/failure of adding a properties
     * @param username
     * @param nodeName
     * @param propertyName
     * @param successExpected
     * @throws RepositoryException
     */
    private void verifyAddProperty(String username,String nodeName,String propertyName, boolean successExpected) throws RepositoryException {
        Session userSession = U.loginService(username);
        try {
            verifyAddProperty(userSession, nodeName, propertyName, successExpected);
        } finally {
            if(userSession != null) {
                userSession.logout();
            }
        }
    }

    /**
     * Verifies success/failure of adding a properties
     * @param userSession
     * @param relativePath
     * @param propertyName
     * @param successExpected
     * @throws RepositoryException
     */
    private void verifyAddProperty(Session userSession,String relativePath,String propertyName,boolean successExpected) throws RepositoryException{
        try {
            final Node n = userSession.getNode("/" + relativePath);
            n.setProperty(propertyName,"test");
            userSession.save();
            assertTrue("Expected setProperty " + propertyName + " to fail on " + relativePath, successExpected);
        } catch(RepositoryException e) {
            userSession.refresh(false); // remove changes causing failure
            assertFalse(
                "Expected setProperty " + propertyName + " to to succeed on " + relativePath + " but got " + e.getMessage(),
                successExpected);
        }
    }

    /**
     * Verifies that a node is readable
     */
    private void verifyCanRead(Session userSession, String relativePath, boolean successExpected) {
        final String path = "/" + relativePath;
        try {
            userSession.getNode(path);
            assertTrue("Not expecting " + path + " to be readable but it was successfully read", successExpected);
        } catch(RepositoryException notReadable) {
            assertFalse("Expecting " + path + " to be readable but could not read it", successExpected);
        }
    }

   /**
    * Verifies that ACEs for existing principal are replaced
    */
   @Test
   @Ignore("SLING-6423 - ACLOptions processing is not implemented yet")
   public void mergeMode_ReplaceExistingPrincipalTest() throws Exception {
      final String initialAclSetup =
                     " set ACL for " + userA + "\n"
                     + "allow jcr:read,jcr:addChildNodes on / \n"
                     + "end"
                     ;

      final String aclsToBeMerged =
                     " set ACL for " + userA + " (ACLOptions=merge)\n"
                     + "allow jcr:read on / \n"
                     + "allow jcr:modifyProperties on / \n"
                     + "end"
                     ;

      U.parseAndExecute(initialAclSetup);
      // verify that setup is correct
      verifyAddChildNode(userA, "A1_" + U.id, true); // add node should succeed
      verifyAddProperty(userA,"A1_"+U.id,"Prop1",false); // add property should fail

      //now merge acls
      U.parseAndExecute(aclsToBeMerged);

      //verify merged ACLs
      verifyAddChildNode(userA, "A2_" + U.id, false); // add node should fail
      verifyAddProperty(userA,"A1_"+U.id,"prop2",true);// add property should succeed

   }


    /**
     * Verify that ACLs for new principal are added
     * @throws Exception
     */
    @Test
    @Ignore("SLING-6423 - ACLOptions processing is not implemented yet")
    public void mergeMode_AddAceTest() throws Exception {
        final String initialAclSetup =
                "set ACL for " + userA + "\n"
                + "allow jcr:read,jcr:write on /\n"
                + "end \n"
                ;

        // userA,jcr:write ACE will be removed,
        // userB ACE will be added
        final String aclsToBeMerged =
                    "set ACL on / (ACLOptions=merge) \n"
                    + "allow jcr:read for " + userA + "\n"
                    + "allow jcr:read,jcr:write for " + userB + "\n"
                    + "end \n"
                ;

        U.parseAndExecute(initialAclSetup);
        // verify that setup is correct
        verifyAddChildNode(userA, "A1_" + U.id, true);
        verifyAddChildNode(userB, "B1_" + U.id, false);
        //now merge acls
        U.parseAndExecute(aclsToBeMerged);

        //verify merged ACLs
        verifyAddChildNode(userA, "A2_" + U.id, false);
        verifyAddChildNode(userB, "B2_" + U.id, true);

    }

    /**
     * Verify that ACEs for unspecified principal are preserved
     * @throws Exception
     */
    @Test
    @Ignore("SLING-6423 - ACLOptions processing is not implemented yet")
    public void mergeMode_PreserveAceTest() throws Exception {
        final String initialAclSetup =
                        "set ACL on / \n"
                        + "allow jcr:read,jcr:write for " + userA + "\n"
                        + "allow jcr:read,jcr:write for " + userB + "\n"
                        + "end \n"
                        ;

        // userB ACE will be preserved
        final String aclsToBeMerged =
                        "set ACL on / (ACLOptions=merge) \n"
                        + "allow jcr:read for " + userA + "\n"
                        + "end \n"
                        ;

        U.parseAndExecute(initialAclSetup);
        // verify that setup is correct
        verifyAddChildNode(userA, "A1_" + U.id, true);
        verifyAddChildNode(userB, "B1_" + U.id, true);

        //now merge acls
        U.parseAndExecute(aclsToBeMerged);

        //verify merged ACLs
        verifyAddChildNode(userA, "A2_" + U.id, false);
        verifyAddChildNode(userB, "B2_" + U.id, true);

    }


    /**
     * Verifiy that ACE for non-existing principal are added
     * @throws Exception
     */
    @Test
    @Ignore("SLING-6423 - ACLOptions processing is not implemented yet")
    public void mergePreserveMode_AddAceTest() throws Exception{
        final String initialAclSetup =
                " set ACL for " + userB + "\n"
                + "allow jcr:read,jcr:write on /\n"
                + "end \n"
                ;

        final String aclsToBeMerged =
                "set ACL for " + userA + " (ACLOptions=mergePreserve)\n"
                + "allow jcr:read,jcr:write on / \n"
                + "end \n"
                ;

        U.parseAndExecute(initialAclSetup);
        // verify that setup is correct
        verifyAddChildNode(userA, "A1_" + U.id, false);
        verifyAddChildNode(userB, "B1_" + U.id, true);

        //now merge acls
        U.parseAndExecute(aclsToBeMerged);

        //verify merged ACLs
        verifyAddChildNode(userA, "A2_" + U.id, true);
        verifyAddChildNode(userB, "B2_" + U.id, true);
    }


    /**
     * Verify that ACE for existing principal are ignored
     * @throws Exception
     */
    @Test
    @Ignore("SLING-6423 - ACLOptions processing is not implemented yet")
    public void mergePreserveMode_IgnoreAceTest() throws Exception {
        final String initialAclSetup =
                "set ACL for " + userA + "\n"
                + "allow jcr:read,jcr:addChildNodes on /\n"
                + "end"
                ;

        final String aclsToBeMerged =
                " set ACL for " + userA + " (ACLOptions=mergePreserve) \n"
                + "allow jcr:read,jcr:modifyProperties on / \n"
                + "end \n"
                ;

        U.parseAndExecute(initialAclSetup);
        // verify that setup is correct
        verifyAddChildNode(userA, "A1_" + U.id, true); // add node should succeed
        verifyAddProperty(userA, "A1_" + U.id, "Prop1", false); // add property should fail

        //now merge acls
        U.parseAndExecute(aclsToBeMerged);

        //verify merged ACLs
        verifyAddChildNode(userA, "A2_" + U.id, true); // add node should succeed
        verifyAddProperty(userA, "A2_" + U.id, "Prop1", false); // add property should fail
    }


    /**
     * Verify that ACE for unspecified principal are preserved
     * @throws Exception
     */
    @Test
    @Ignore("SLING-6423 - ACLOptions processing is not implemented yet")
    public void mergePreserveMode_PreserveAceTest() throws Exception {
        final String initialAclSetup =
                " set ACL on /\n"
                + "allow jcr:read, jcr:write for " + userA + " , " + userB + "\n"
                + "end \n"
                ;

        // ACL for userA will be ignored but added for userB
        final String aclsToBeMerged =
                " set ACL for " + userA + " (ACLOptions=mergePreserve) \n"
                + "allow jcr:read on / \n"
                + "end \n"
                ;

        U.parseAndExecute(initialAclSetup);
        // verify that setup is correct
        verifyAddChildNode(userA, "A1_" + U.id, true);
        verifyAddChildNode(userB, "B1_" + U.id, true);

        //now merge acls
        U.parseAndExecute(aclsToBeMerged);

        //verify merged ACLs
        verifyAddChildNode(userA, "A2_" + U.id, true);
        verifyAddChildNode(userB, "B2_" + U.id, true);
    }

    /**
     * Tests use of glob restriction in set ACL
     * @throws Exception
     */
    @Test
    public void globRestrictionTest() throws Exception {

        final String allowedNodeName = "testxyz_" + U.id;
        final String notAllowedNodeName = "testabc_" + U.id;

        U.adminSession.getRootNode().addNode(allowedNodeName);
        U.adminSession.getRootNode().addNode(notAllowedNodeName);
        U.adminSession.save();


        final String nodeName = "test_" + U.id;

        final String aclSetup =
                "set ACL for " + U.username + "\n"
                        + "allow jcr:all on / \n"
                        + "deny jcr:addChildNodes on / restriction(rep:glob,*abc*)\n"
                        + "end"
                ;

        U.parseAndExecute(aclSetup);

        try {
            verifyAddChildNode(s.getRootNode().getNode(allowedNodeName), nodeName, true);
            verifyAddChildNode(s.getRootNode().getNode(notAllowedNodeName), nodeName, false);

        } finally {
            s.logout();
        }
    }


    /**
     * Tests use of rep:itemNames restriction in set ACL
     * @throws Exception
     */
    @Test
    public void itemNamesRestrictionTest() throws Exception {
        final String nodename = "test_" + U.id;
        final String propName = "test2_" + U.id;

        final String aclSetup =
                "set ACL for " + U.username + "\n"
                        + "allow jcr:all on / \n"
                        + "deny jcr:modifyProperties on / restriction(rep:itemNames,"+propName+")\n"
                        + "end"
                ;

        U.parseAndExecute(aclSetup);

        try {

            verifyAddChildNode(s, nodename, true);
            verifyAddProperty(s, nodename, "someotherproperty", true);
            verifyAddProperty(s, nodename, propName, false); // adding property propName should fail

        } finally {
            s.logout();
        }
    }

    /**
     * Tests default merging of acls with restrictions
     * @throws Exception
     */
    @Test
    public void multiRestrictionMergeTest() throws Exception {
        final String nodeName = "test_" + U.id;
        final String nodeName2 = "test2_" + U.id;
        final String nodeName3 = "test3_" + U.id;
        final String propName  = "propName"  + U.id;


        U.adminSession.getRootNode().addNode(nodeName);
        U.adminSession.getRootNode().addNode(nodeName2);
        U.adminSession.getRootNode().addNode(nodeName3);
        U.adminSession.save();


        final String aclSetup =
                "set ACL for " + U.username + "\n"
                        + "allow jcr:all on / \n"
                        + "deny jcr:addChildNodes on / restriction(rep:glob,"+nodeName2+")\n"
                        + "end"
                ;

        U.parseAndExecute(aclSetup);

       final String aclSetup2 =
                "set ACL for " + U.username + "\n"
                        + "deny jcr:addChildNodes on / restriction(rep:glob,"+nodeName3+")\n"
                        + "end"
                ;

        U.parseAndExecute(aclSetup2);

        final String aclSetup3 =
                "set ACL for " + U.username + "\n"
                        + "deny jcr:modifyProperties on / restriction(rep:itemNames,"+propName+")\n"
                        + "end"
                ;

        U.parseAndExecute(aclSetup3);

        try {

            // now verify add child nodes perm

            verifyAddChildNode(s.getRootNode().getNode(nodeName), nodeName, true);

            verifyAddChildNode(s.getRootNode().getNode(nodeName2), nodeName, false);
            verifyAddChildNode(s.getRootNode().getNode(nodeName3), nodeName, false);

            // verify property restriction
            verifyAddProperty(s, nodeName, "someotherproperty", true);
            verifyAddProperty(s, nodeName, propName, false); // adding property propName should fail

        } finally {
            s.logout();
        }
    }


    @Test(expected = None.class)
    public void emptyRestrictionTest() throws Exception {
        final String aclSetup =
                "set ACL for " + U.username + "\n"
                        + "allow jcr:all on / \n"
                        + "deny jcr:modifyProperties on / restriction(rep:itemNames)\n"
                        + "end"
                ;
        U.parseAndExecute(aclSetup);
    }

    /**
     * Tests empty rep:glob restriction which actually does something useful
     * @throws Exception
     */
    @Test
    public void emptyGlobRestrictionTest() throws Exception {
        final String parentName = "empty_glob_" + U.id;
        final String childName = "child";
        final String childPath = parentName + "/" + childName;

        try {
            U.adminSession.getRootNode().addNode(parentName).addNode(childName);
            U.adminSession.save();

            // Allow for reading the parent node but not its children with an empty glob restriction
            // as per https://jackrabbit.apache.org/oak/docs/security/authorization/restriction.html
            final String aclSetup =
                    "set ACL for " + U.username + "\n"
                            + "deny jcr:read on /\n"
                            + "allow jcr:read on /" + parentName + " restriction (rep:glob)\n"
                            + "end"
                    ;

            U.parseAndExecute(aclSetup);

            // Verify read access with a non-admin session opened with U.username
            verifyCanRead(s, parentName, true);
            verifyCanRead(s, childPath, false);

        } finally {
            s.logout();
        }
    }
}
