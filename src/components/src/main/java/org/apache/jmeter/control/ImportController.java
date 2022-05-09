/*
 * Licensed to the Apache Software Foundation (ASF) under one or more
 * contributor license agreements.  See the NOTICE file distributed with
 * this work for additional information regarding copyright ownership.
 * The ASF licenses this file to you under the Apache License, Version 2.0
 * (the "License"); you may not use this file except in compliance with
 * the License.  You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package org.apache.jmeter.control;

import org.apache.jmeter.exceptions.IllegalUserActionException;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.tree.JMeterTreeModel;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.save.SaveService;
import org.apache.jmeter.services.FileServer;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.testelement.property.CollectionProperty;
import org.apache.jmeter.testelement.property.JMeterProperty;
import org.apache.jmeter.testelement.property.NullProperty;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;
import org.apache.jorphan.collections.ListedHashTree;
import org.apache.jorphan.util.JMeterStopTestException;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.tree.MutableTreeNode;
import javax.swing.tree.TreeNode;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public class ImportController extends GenericController {
    private static int countBuild =0;
    private static int countCached =0;
    private static final Logger log = LoggerFactory.getLogger(ImportController.class);
   // private static final long serialVersionUID = 240L; //TODO what's that for?

    private static final String NODE_PATH = "ImportController.node_path";
    private static final String INCLUDE_PATH = "ImportController.includepath";

    private static  final String PREFIX =
            JMeterUtils.getPropDefault(
                    "includecontroller.prefix",
                    "");

    private HashTree subtree = null;
    private JMeterTreeNode subtreeNode = null;

    /**
     * No-arg constructor
     *
     * @see Object#Object()
     */
    public ImportController() {
        super();
    }

    @Override
    public Object clone() {
        this.resolveReplacementSubTree(null);
        ImportController clone = (ImportController) super.clone();
        clone.setIncludePath(this.getIncludePath());
        if (this.subtree != null) {
            clone.subtree = (HashTree)this.subtree.clone();
            clone.subtreeNode = (JMeterTreeNode) this.getSubtreeNode().clone(); // TODO clone will not have children/parents
        }
        return clone;
    }

    public void reset(){
        this.subtreeNode=null;
        this.subtree=null;
        this.removeProperty(NODE_PATH);
        this.clearTestElementChildren();
    }

    /**
     * In the event an user wants to include an external JMX test plan
     * the GUI would call this.
     * @param jmxfile The path to the JMX test plan to include
     */
    public void setIncludePath(String jmxfile) {
        this.setProperty(INCLUDE_PATH,jmxfile);
    }

    /**
     * return the JMX file path.
     * @return the JMX file path
     */
    public String getIncludePath() {
        return this.getPropertyAsString(INCLUDE_PATH);
    }

    public JMeterTreeNode getSubtreeNode() {
        return subtreeNode;
    }

    /**
     * {@inheritDoc}
     */

    public void resolveReplacementSubTree(JMeterTreeNode context) {
        // prevents from multiple cloning already resolved node
        // TODO probably needs to add check if existing subtree is relevant to selected file - for ex. when the file changed?
        if (subtree==null) {
            this.subtree = this.loadIncludedElements();
        }
        if (subtree != null) { // indicates nested subtree and selected is not resolved
            try {
                if (isGuiMode()) { //!JMeter.isNonGUI()
                    //traverse through the test plan and look up for the external tree was already resolved - optimizes memory usage in GUI mode
                    JMeterTreeNode existingResolvedNode = externalNodeLookup((JMeterTreeNode) GuiPackage.getInstance().getTreeModel().getRoot());
                    this.subtreeNode = existingResolvedNode != null ? existingResolvedNode : buildJMeterTreeNodeFromHashTree(this.subtree);
                    if (existingResolvedNode != null) {
                        log.debug("get external tree node from cache: " + ++countCached);
                    }
                } else {
                    this.subtreeNode = buildJMeterTreeNodeFromHashTree(this.subtree);
                }
            } catch (IllegalUserActionException e) {
                e.printStackTrace();
            }

          //  if (this.subtreeNode!=null)
            //    this.addTestElement((TestElement) this.subtreeNode.getRoot());
        }
    }

    private JMeterTreeNode externalNodeLookup(JMeterTreeNode node){
        if (node.getUserObject() instanceof ImportController) {
            TestElement te = node.getTestElement();
            if (((ImportController) te).getIncludePath().equals(this.getIncludePath())
                    && te.getPropertyAsBoolean("isEventuallyResolved")){
                return ((ImportController) te).subtreeNode;
            }
        }
        Enumeration<?> enumNode = node.children();
        while (enumNode.hasMoreElements()) {
            JMeterTreeNode child = (JMeterTreeNode)enumNode.nextElement();
            JMeterTreeNode result = externalNodeLookup(child);
            if (result != null) {
                return result;
            }
        }
        return null;
    }

    private boolean isGuiMode() {
        return GuiPackage.getInstance() != null;
    }

    public JMeterTreeNode buildJMeterTreeNodeFromHashTree(HashTree tree) throws IllegalUserActionException {
        log.debug("buildJMeterTreeNodeFromHashTree: " + ++countBuild);
        JMeterTreeNode rootNode = new JMeterTreeNode();
        rootNode.setUserObject(new TestPlan());
        //rootNode.setUserObject(this);
        JMeterTreeModel model = new JMeterTreeModel();
        model.setRoot(rootNode);

        model = buildTreeModelFromSubTree(tree, (JMeterTreeNode) model.getRoot(), model);
        /*
        JMeterTreeNode importControllerRoot = new JMeterTreeNode();
        importControllerRoot.setUserObject(this);
        for (Enumeration e = model.getNodesOfType(TestPlan.class).get(0).getFirstChild().children(); e.hasMoreElements();)
            importControllerRoot.add((MutableTreeNode) e.nextElement());

        model.setRoot(importControllerRoot);
*/
        return (JMeterTreeNode) model.getRoot();
    }

    public JMeterTreeModel buildTreeModelFromSubTree(HashTree subTree, JMeterTreeNode current, JMeterTreeModel model) {
        for (Object o : subTree.list()) {
            TestElement item = (TestElement) o;

            if (item instanceof TestPlan) {

                TestPlan tp = (TestPlan) item;
                current = (JMeterTreeNode)model.getRoot();
                TestElement userObj = new TestPlan();
                current.setUserObject(userObj);
                final TestPlan userObject = (TestPlan) current.getUserObject();
                userObject.addTestElement(item);
                userObject.setName(item.getName());
                userObject.setFunctionalMode(tp.isFunctionalMode());
                userObject.setSerialized(tp.isSerialized());

        //        current.setUserObject(item);
                buildTreeModelFromSubTree(subTree.getTree(item), current, model);
            }
            else {
                if (item instanceof ReplaceableController && this.subtreeNode!=null){
                    // adding (external) root Test Plan to match the (external) node path
                    JMeterTreeNode testPlanRootNode = new JMeterTreeNode();
                    testPlanRootNode.setUserObject(new TestPlan());
                    testPlanRootNode.add(cloneTreeNode(this.subtreeNode)); // clone to not affect selection tree

                    if (((ReplaceableController) item).getReplacementSubTree().size()==0) {
                        ((ReplaceableController) item).resolveReplacementSubTree(testPlanRootNode);
                    }
                    //log.info("current replaceable: " + item.getName() + ": " + ((ReplaceableController) item).getReplacementSubTree());

                }
                JMeterTreeNode newNode = new JMeterTreeNode(item, model);
                model.insertNodeInto(newNode, current, current.getChildCount());
                buildTreeModelFromSubTree(subTree.getTree(item), newNode, model);
            }
        }
        return model;
    }


    @SuppressWarnings("JdkObsolete")
    private void createSubTree(HashTree tree, JMeterTreeNode node) {
        Enumeration<?> e = node.children();
        while (e.hasMoreElements()) {
            JMeterTreeNode subNode = (JMeterTreeNode)e.nextElement();
            tree.add(subNode);
            createSubTree(tree.getTree(subNode), subNode);
        }
    }

    private static JMeterTreeNode cloneTreeNode(JMeterTreeNode node) {
        JMeterTreeNode treeNode = (JMeterTreeNode) node.clone();
        treeNode.setUserObject(((TestElement) node.getUserObject()).clone());
        cloneChildren(treeNode, node);
        return treeNode;
    }

    @SuppressWarnings("JdkObsolete")
    private static void cloneChildren(JMeterTreeNode to, JMeterTreeNode from) {
        Enumeration<?> enumr = from.children();
        while (enumr.hasMoreElements()) {
            JMeterTreeNode child = (JMeterTreeNode) enumr.nextElement();
            JMeterTreeNode childClone = (JMeterTreeNode) child.clone();
            childClone.setUserObject(((TestElement) child.getUserObject()).clone());
            to.add(childClone);
            cloneChildren((JMeterTreeNode) to.getLastChild(), child);
        }
    }

    /**
     * load the included elements using SaveService
     *
     * @return tree with loaded elements
     */
    protected HashTree loadIncludedElements() {
        // only try to load the JMX test plan if there is one
        final String includePath = getIncludePath();
        HashTree tree = null;
        if (includePath != null && includePath.length() > 0) {
            String fileName=PREFIX+includePath;
            try {
                File file = new File(FileServer.getFileServer().getBaseDir(), includePath);
                final String absolutePath = file.getAbsolutePath();
                log.info("loadIncludedElements -- try to load included module: {}", absolutePath);
                if(!file.exists() && !file.isAbsolute()){
                    file = new File(fileName.trim());
                    log.info("loadIncludedElements -failed for: {}", absolutePath);
                    if (log.isInfoEnabled()) {
                        log.info("loadIncludedElements -Attempting to read it from: {}", file.getAbsolutePath());
                    }
                    if(!file.canRead() || !file.isFile()){
                        log.error("Import Controller '{}' can't load '{}' - see log for details", this.getName(),
                                fileName);
                        throw new IOException("loadIncludedElements -failed for: " + absolutePath +
                                " and " + file.getAbsolutePath());
                    }
                }

                tree = SaveService.loadTree(file);
                // filter the tree for a TestFragment.
                //tree = getProperBranch(tree);
                //removeDisabledItems(tree);

                return tree;

            } catch (NoClassDefFoundError ex) // Allow for missing optional jars
            {
                String msg = "Including file \""+ fileName
                        + "\" failed for Import Controller \""+ this.getName()
                        +"\", missing jar file";
                log.warn(msg, ex);
                JMeterUtils.reportErrorToUser(msg+" - see log for details");
            } catch (FileNotFoundException ex) {
                String msg = "File \""+ fileName
                        + "\" not found for Import Controller \""+ this.getName()+"\"";
                JMeterUtils.reportErrorToUser(msg+" - see log for details");
                log.warn(msg, ex);
            } catch (Exception ex) {
                String msg = "Including file \"" + fileName
                        + "\" failed for Import Controller \"" + this.getName()
                        +"\", unexpected error";
                JMeterUtils.reportErrorToUser(msg+" - see log for details");
                log.warn(msg, ex);
            }
        }
        return tree;
    }

    private void removeDisabledItems(HashTree tree) {
        for (Object o : new ArrayList<>(tree.list())) {
            TestElement item = (TestElement) o;
            if (!item.isEnabled()) {
                tree.remove(item);
            } else {
                removeDisabledItems(tree.getTree(item));// Recursive call
            }
        }
    }


}
