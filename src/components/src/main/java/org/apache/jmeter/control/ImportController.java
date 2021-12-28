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
import org.apache.jmeter.threads.JMeterContext;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.collections.HashTree;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.swing.tree.TreeNode;

import java.io.File;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Enumeration;

public class ImportController extends GenericController implements ReplaceableController {
    private static final Logger log = LoggerFactory.getLogger(ImportController.class);

    private static final long serialVersionUID = 241L;

    private static final String IMPORT_PATH = "ImportController.importpath"; //$NON-NLS-1$

    private static  final String PREFIX =
        JMeterUtils.getPropDefault(
                "importcontroller.prefix", //$NON-NLS-1$
                ""); //$NON-NLS-1$

    private HashTree subtree = null;
    private TestElement sub = null;
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
        // TODO - fix so that this is only called once per test, instead of at every clone
        // Perhaps save previous filename, and only load if it has changed?
        this.resolveReplacementSubTree(null);
        ImportController clone = (ImportController) super.clone();
        clone.setImportPath(this.getImportPath());
        if (this.subtree != null) {
            if (this.subtree.size() == 1) {
                for (Object o : this.subtree.keySet()) {
                    this.sub = (TestElement) o;
                }
            }
            clone.subtree = (HashTree)this.subtree.clone();
            //clone.subtreeNode = cloneTreeNode(this.getSubtreeNode());
            clone.subtreeNode = (JMeterTreeNode) this.getSubtreeNode().clone();
            clone.sub = this.sub==null ? null : (TestElement) this.sub.clone();
        }
        return clone;
    }

    /**
     * In the event an user wants to include an external JMX test plan
     * the GUI would call this.
     * @param jmxfile The path to the JMX test plan to include
     */
    public void setImportPath(String jmxfile) {
        this.setProperty(IMPORT_PATH,jmxfile);
    }

    /**
     * return the JMX file path.
     * @return the JMX file path
     */
    public String getImportPath() {
        return this.getPropertyAsString(IMPORT_PATH);
    }

    /**
     * The way ReplaceableController works is clone is called first,
     * followed by replace(HashTree) and finally getReplacement().
     */
    @Override
    public HashTree getReplacementSubTree() {
        return subtree;
    }

    public TestElement getReplacementElement() {
        return sub;
    }

    public JMeterTreeNode getSubtreeNode() {
        return subtreeNode;
    }

    @Override
    public void resolveReplacementSubTree(JMeterTreeNode context) {
        this.subtree = this.loadIncludedElements();
        try {
            this.subtreeNode = buildJMeterTreeNodeFromHashTree(this.subtree);
        } catch (IllegalUserActionException e) {
            e.printStackTrace();
        }
    }

    /**
     * load the included elements using SaveService
     *
     * @return tree with loaded elements
     */
    protected HashTree loadIncludedElements() {
        // only try to load the JMX test plan if there is one
        final String importPath = getImportPath();
        HashTree tree = null;
        if (importPath != null && importPath.length() > 0) {
            String fileName=PREFIX+importPath;
            try {
                File file = new File(fileName.trim());
                final String absolutePath = file.getAbsolutePath();
                log.info("loadIncludedElements -- try to load included module: {}", absolutePath);
                if(!file.exists() && !file.isAbsolute()){
                    log.info("loadIncludedElements -failed for: {}", absolutePath);
                    file = new File(FileServer.getFileServer().getBaseDir(), importPath);
                    if (log.isInfoEnabled()) {
                        log.info("loadIncludedElements -Attempting to read it from: {}", file.getAbsolutePath());
                    }
                    if(!file.canRead() || !file.isFile()){
                        log.error("Include Controller '{}' can't load '{}' - see log for details", this.getName(),
                                fileName);
                        throw new IOException("loadIncludedElements -failed for: " + absolutePath +
                                " and " + file.getAbsolutePath());
                    }
                }

                tree = SaveService.loadTree(file);
                // filter the tree for a TestFragment.
                //tree = getProperBranch(tree);
                removeDisabledItems(tree);
                return tree;
            } catch (NoClassDefFoundError ex) // Allow for missing optional jars
            {
                String msg = "Including file \""+ fileName
                            + "\" failed for Include Controller \""+ this.getName()
                            +"\", missing jar file";
                log.warn(msg, ex);
                JMeterUtils.reportErrorToUser(msg+" - see log for details");
            } catch (FileNotFoundException ex) {
                String msg = "File \""+ fileName
                        + "\" not found for Include Controller \""+ this.getName()+"\"";
                JMeterUtils.reportErrorToUser(msg+" - see log for details");
                log.warn(msg, ex);
            } catch (Exception ex) {
                String msg = "Including file \"" + fileName
                            + "\" failed for Include Controller \"" + this.getName()
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
                JMeterTreeNode newNode = new JMeterTreeNode(item, model);
                model.insertNodeInto(newNode, current, current.getChildCount());
                buildTreeModelFromSubTree(subTree.getTree(item), newNode, model);
            }
        }
        return model;
    }

    void convertSubTree(HashTree tree) {
                TestElement item = this;
                HashTree subTree = tree.getTree(item);
                ReplaceableController rc = (ReplaceableController) this.clone();
                if (subTree != null) {
                    HashTree replacementTree = rc.getReplacementSubTree();
                    if (replacementTree != null) {
                        convertSubTree(replacementTree);
                        tree.replaceKey(item, rc);
                        tree.set(rc, replacementTree);
                    }
                }
            }

/*
    void convertSubTree(HashTree tree) {
        for (Object o : new ArrayList<>(tree.list())) {
            if (o instanceof TestElement) {
                TestElement item = (TestElement) o;
                HashTree subTree = tree.getTree(item);
                ReplaceableController rc = (ReplaceableController) item.clone();
                if (subTree != null) {
                    HashTree replacementTree = rc.getReplacementSubTree();
                    if (replacementTree != null) {
                        convertSubTree(replacementTree);
                        tree.replaceKey(item, rc);
                        tree.set(rc, replacementTree);
                    }
                }
            }
        }
    }
*/
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
}
