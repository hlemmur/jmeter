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
import org.bouncycastle.math.raw.Mod;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.tree.TreeNode;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;

public class ModularIncludeController extends GenericController implements ReplaceableController {
    private static final Logger log = LoggerFactory.getLogger(ModularIncludeController.class);
   // private static final long serialVersionUID = 240L; //TODO what's that for?

    private static final String NODE_PATH = "ModularIncludeController.node_path";
    private static final String INCLUDE_PATH = "ModularIncludeController.includepath";

    private transient JMeterTreeNode selectedNode = null;
    private TreeNode[] selectedPath = null;

    private static  final String PREFIX =
            JMeterUtils.getPropDefault(
                    "includecontroller.prefix",
                    "");

    private HashTree subtree = null;
    private JMeterTreeNode subtreeNode = null;

    /**
     * No-arg constructor
     *
     * @see java.lang.Object#Object()
     */
    public ModularIncludeController() {
        super();
    }

    @Override
    public Object clone() {
        this.resolveReplacementSubTree(null);
        ModularIncludeController clone = (ModularIncludeController) super.clone();
        clone.setIncludePath(this.getIncludePath());
        if (this.subtree != null) {
            clone.subtree = (HashTree)this.subtree.clone();
            clone.subtreeNode = (JMeterTreeNode) this.getSubtreeNode().clone(); // TODO clone will not have children/parents
        }
        if (selectedNode == null) {
            this.restoreSelected();
        }
        // TODO Should we clone instead the selectedNode?
        clone.selectedNode = selectedNode;
        return clone;
    }

    public void reset(){
        this.selectedNode=null;
        this.subtreeNode=null;
        this.selectedPath=null;
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
     * Sets the {@link JMeterTreeNode} which represents the controller which
     * this object is pointing to. Used for building the test case upon
     * execution.
     *
     * @param tn
     *            JMeterTreeNode
     * @see org.apache.jmeter.gui.tree.JMeterTreeNode
     */
    public void setSelectedNode(JMeterTreeNode tn) {
        selectedNode = tn;
        setNodePath();
    }

    /**
     * Gets the {@link JMeterTreeNode} for the Controller
     *
     * @return JMeterTreeNode
     */
    public JMeterTreeNode getSelectedNode() {
        if (selectedNode == null){
            restoreSelected();
        }
        return selectedNode;
    }

    private void setNodePath() {
        List<String> nodePath = new ArrayList<>();
        if (selectedNode != null) {
            TreeNode[] path = selectedNode.getPath();
            for (TreeNode node : path) {
                nodePath.add(((JMeterTreeNode) node).getName());
            }
        }
        setProperty(new CollectionProperty(NODE_PATH, nodePath));
    }

    public List<?> getNodePath() {
        JMeterProperty prop = getProperty(NODE_PATH);
        if (!(prop instanceof NullProperty)) {
            return (List<?>) prop.getObjectValue();
        }
        return null;
    }

    private void restoreSelected() {
        GuiPackage gp = GuiPackage.getInstance();
        if (gp != null) {
            JMeterTreeNode root = this.getSubtreeNode();
            resolveReplacementSubTree(root);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void resolveReplacementSubTree(JMeterTreeNode context) {
        this.subtree = this.loadIncludedElements();
        if (subtree!=null) {
            try {
                this.subtreeNode = buildJMeterTreeNodeFromHashTree(this.subtree);
            } catch (IllegalUserActionException e) {
                e.printStackTrace();
            }
        }
        if (selectedNode == null) {
            List<?> nodePathList = getNodePath();
            if (nodePathList != null && !nodePathList.isEmpty()) {
                traverse(context, nodePathList, 1);
            }

            if(hasReplacementOccured() && selectedNode == null) {
                throw new JMeterStopTestException("ModularIncludeController:"
                        + getName()
                        + " has no selected Controller (did you rename some element in the path to target controller?), test was shutdown as a consequence");
            }
        }
    }

    /**
     * In GUI Mode replacement occurs when test start
     * In Non GUI Mode replacement occurs before test runs
     * @return true if replacement occurred at the time method is called
     */
    private boolean hasReplacementOccured() {
        return GuiPackage.getInstance() == null || isRunningVersion();
    }

    private void traverse(JMeterTreeNode node, List<?> nodePath, int level) {
        if (node != null && nodePath.size() > level) {
            for (int i = 0; i < node.getChildCount(); i++) {
                JMeterTreeNode cur = (JMeterTreeNode) node.getChildAt(i);
                // Bug55375 - don't allow selectedNode to be a ModularIncludeController as can cause recursion
                if (!(cur.getTestElement() instanceof ModularIncludeController)) {
                    if (cur.getName().equals(nodePath.get(level).toString())) {
                        if (nodePath.size() == (level + 1)) {
                            selectedNode = cur;
                        }
                        traverse(cur, nodePath, level + 1);
                    }
                }
            }
        }
    }

    /**
     * The way ReplaceableController works is clone is called first,
     * followed by replace(HashTree) and finally getReplacement().
     */
    /**
     * {@inheritDoc}
     */
    // returns replacement node hash tree
    @Override
    public HashTree getReplacementSubTree() {
        HashTree tree = new ListedHashTree();
        if (selectedNode != null) {
            // Use a local variable to avoid replacing reference by modified clone (see Bug 54950)
            JMeterTreeNode nodeToReplace = selectedNode;
            // We clone to avoid enabling existing node
            if (!nodeToReplace.isEnabled()) {
                nodeToReplace = cloneTreeNode(selectedNode);
                nodeToReplace.setEnabled(true);
            }
            HashTree subtree = tree.add(nodeToReplace);
            createSubTree(subtree, nodeToReplace);
        }
        return tree;
    }

    public JMeterTreeNode buildJMeterTreeNodeFromHashTree(HashTree tree) throws IllegalUserActionException {
        JMeterTreeNode rootNode = new JMeterTreeNode();
        rootNode.setUserObject(new TestPlan());
        JMeterTreeModel model = new JMeterTreeModel();
        model.setRoot(rootNode);
        model = buildTreeModelFromSubTree(tree, (JMeterTreeNode) model.getRoot(), model);
        return (JMeterTreeNode) model.getRoot();
    }

    public JMeterTreeModel buildTreeModelFromSubTree(HashTree subTree, JMeterTreeNode current, JMeterTreeModel model) throws IllegalUserActionException {
        for (Object o : subTree.list()) {
            TestElement item = (TestElement) o;
            if (item instanceof TestPlan) {
                TestPlan tp = (TestPlan) item;
                current = (JMeterTreeNode)model.getRoot();
                TestPlan userObj = new TestPlan();
                current.setUserObject(userObj);
                final TestPlan userObject = (TestPlan) current.getUserObject();
                userObject.addTestElement(item);
                userObject.setName(item.getName());
                userObject.setFunctionalMode(tp.isFunctionalMode());
                userObject.setSerialized(tp.isSerialized());
                buildTreeModelFromSubTree(subTree.getTree(item), current, model);
            }
            else {
                if (item instanceof ReplaceableController && this.subtreeNode!=null){
                    // adding root Test Plan to match the node path
                    JMeterTreeNode testPlanRootNode = new JMeterTreeNode();
                    testPlanRootNode.setUserObject(new TestPlan());
                    testPlanRootNode.add(cloneTreeNode(this.subtreeNode)); // clone to not affect selection tree

                    ((ReplaceableController) item).resolveReplacementSubTree(testPlanRootNode);
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
                File file = new File(fileName.trim());
                final String absolutePath = file.getAbsolutePath();
                log.info("loadIncludedElements -- try to load included module: {}", absolutePath);
                if(!file.exists() && !file.isAbsolute()){
                    log.info("loadIncludedElements -failed for: {}", absolutePath);
                    file = new File(FileServer.getFileServer().getBaseDir(), includePath);
                    if (log.isInfoEnabled()) {
                        log.info("loadIncludedElements -Attempting to read it from: {}", file.getAbsolutePath());
                    }
                    if(!file.canRead() || !file.isFile()){
                        log.error("Modular Include Controller '{}' can't load '{}' - see log for details", this.getName(),
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
                        + "\" failed for Modular Include Controller \""+ this.getName()
                        +"\", missing jar file";
                log.warn(msg, ex);
                JMeterUtils.reportErrorToUser(msg+" - see log for details");
            } catch (FileNotFoundException ex) {
                String msg = "File \""+ fileName
                        + "\" not found for Modular Include Controller \""+ this.getName()+"\"";
                JMeterUtils.reportErrorToUser(msg+" - see log for details");
                log.warn(msg, ex);
            } catch (Exception ex) {
                String msg = "Including file \"" + fileName
                        + "\" failed for Modular Include Controller \"" + this.getName()
                        +"\", unexpected error";
                JMeterUtils.reportErrorToUser(msg+" - see log for details");
                log.warn(msg, ex);
            }
        }
        return tree;
    }

    /**
     * Extract from tree (included test plan) all Test Elements located in a Test Fragment
     * @param tree HashTree included Test Plan
     * @return HashTree Subset within Test Fragment or Empty HashTree
     */
    private HashTree getProperBranch(HashTree tree) {
        for (Object o : new ArrayList<>(tree.list())) {
            TestElement item = (TestElement) o;

            //if we found a TestPlan, then we are on our way to the TestFragment
            if (item instanceof TestPlan)
            {
                return getProperBranch(tree.getTree(item));
            }

            if (item instanceof TestFragmentController)
            {
                return tree.getTree(item);
            }
        }
        log.warn("No Test Fragment was found in included Test Plan, returning empty HashTree");
        return new HashTree();
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
