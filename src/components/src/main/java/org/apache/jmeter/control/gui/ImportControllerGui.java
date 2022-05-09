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

package org.apache.jmeter.control.gui;

import org.apache.jmeter.control.Controller;
import org.apache.jmeter.control.ImportController;
import org.apache.jmeter.control.ReplaceableController;
import org.apache.jmeter.control.TestFragmentController;
import org.apache.jmeter.gui.GUIMenuSortOrder;
import org.apache.jmeter.gui.GuiPackage;
import org.apache.jmeter.gui.TestElementMetadata;
import org.apache.jmeter.gui.tree.JMeterTreeNode;
import org.apache.jmeter.gui.util.FilePanel;
import org.apache.jmeter.gui.util.MenuFactory;
import org.apache.jmeter.gui.util.MenuInfo;
import org.apache.jmeter.services.FileServer;
import org.apache.jmeter.testelement.TestElement;
import org.apache.jmeter.testelement.TestPlan;
import org.apache.jmeter.threads.AbstractThreadGroup;
import org.apache.jmeter.util.JMeterUtils;
import org.apache.jorphan.gui.JFactory;
import org.apache.jorphan.gui.layout.VerticalLayout;

import javax.swing.*;
import javax.swing.tree.*;

import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.Collection;
import java.util.Iterator;


@GUIMenuSortOrder(MenuInfo.SORT_ORDER_DEFAULT+2)
@TestElementMetadata(labelResource = "import_controller_title")
public class ImportControllerGui extends AbstractControllerGui implements ActionListener { // NOSONAR Ignore parent warning
    //private static final long serialVersionUID = -4195441608252523573L; // TODO what's that for?

    private final FilePanel includePanel =
            new FilePanel(JMeterUtils.getResString("include_path"), ".jmx"); //$NON-NLS-1$ //$NON-NLS-2$

    private static final String SEPARATOR = " > ";

    private static final TreeNode[] EMPTY_TREE_NODES = new TreeNode[0];


    /**
     * Model of a Module to run tree. It is a copy of a test plan tree.
     * User object of each element is set to a correlated JMeterTreeNode element of a test plan tree.
     */
    private final DefaultTreeModel importedTreeModel;

    /**
     * Module to run tree. Allows to select a module to be executed. Many ModuleControllers can reference
     * the same element.
     */
    private final JTree importedTreeNodes;

    /**
     * Used in case if referenced element does not exist in test plan tree (after removing it for example)
     */
    private final JLabel warningLabel;

    /**
     * Use this to warn about no selectable controller
     */
    private boolean hasAtLeastOneController;

    /**
     * indicates if it's time to reset test element state (on file change for example)
     */
    private boolean doReset = false;

    /**
     * button to reload the tree in case it was not refreshed when for ex. included file has changed
     */
    private JButton reloadTreeButton;

    /**
     * Opens included external *.jmx file in new window
     */
    private JButton openIncludedTestPlanButton;

    /**
     * Initializes the gui panel for the ImportController instance.
     */
    public ImportControllerGui() {
        importedTreeModel = new DefaultTreeModel(new DefaultMutableTreeNode());
        importedTreeNodes = new JTree(importedTreeModel);
        importedTreeNodes.setCellRenderer(new ImportControllerCellRenderer());

        // this custom TreeSelectionModel forbid the selection of some test elements (test plan, thread group, etc..)
        TreeSelectionModel tsm =  new DefaultTreeSelectionModel() {

            //private static final long serialVersionUID = 4062816201792954617L; // TODO what's that for?

            private boolean isSelectedPathAllowed(DefaultMutableTreeNode lastSelected) {
                JMeterTreeNode tn = null;
                if (lastSelected != null && lastSelected.getUserObject() instanceof JMeterTreeNode) {
                    tn = (JMeterTreeNode) lastSelected.getUserObject();
                }
                return tn != null && isTestElementAllowed(tn.getTestElement());
            }

        };
        tsm.setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        importedTreeNodes.setSelectionModel(tsm);

        ImageIcon image = JMeterUtils.getImage("warning.png");
        warningLabel = new JLabel("", image, SwingConstants.LEFT); // $NON-NLS-1$
        JFactory.error(warningLabel);
        warningLabel.setVisible(false);

        init();

        importedTreeNodes.addTreeSelectionListener(
                evt -> {
                    warningLabel.setVisible(false);
                });
        includePanel.addChangeListener(
                evt -> {
                    // reset the test element on external file selection and refresh gui correspondingly
                    // TODO skip on initial creation?
                    importedTreeNodes.clearSelection();
                    doReset = true; // affects this.configure() called by updateCurrentGui()
                    GuiPackage.getInstance().updateCurrentGui();
                });
    }

    /** {@inheritDoc}} */
    @Override
    public String getLabelResource() {
        return "import_controller_title"; // $NON-NLS-1$
    }

    /** {@inheritDoc}} */
    @Override
    public void configure(TestElement el) {
        super.configure(el);
        hasAtLeastOneController = false;
        ImportController controller = (ImportController) el;
        this.includePanel.setFilename(controller.getIncludePath());

        if (doReset){
            controller.reset();
            doReset =false;
        }

        if (this.includePanel.getFilename().equals("") || this.includePanel.getFilename()==null){
            importedTreeNodes.setVisible(false);
            warningLabel.setVisible(false);
            reloadTreeButton.setEnabled(false);
            openIncludedTestPlanButton.setEnabled(false);
        }
        else {
            warningLabel.setVisible(false);
            importedTreeNodes.setVisible(true);
            reloadTreeButton.setEnabled(true);
            openIncludedTestPlanButton.setEnabled(true);
        }
        reinitialize(controller);
    }

    /** {@inheritDoc}} */
    @Override
    public TestElement createTestElement() {
        ImportController ic = new ImportController();
        configureTestElement(ic);
        return ic;
    }

    /** {@inheritDoc}} */
    @Override
    public void modifyTestElement(TestElement element) {
        configureTestElement(element);
        ImportController controller = (ImportController)element;

        // save the relative external filename instead of absolute one
        // current limitation - included files must be under the same dir as the parent test plan
        // TODO what if there is a need to import files from another dir? maybe to use approach of jmeter property lookup lib dir
        // or leave the absolute path but parametrize with file path variable
        controller.setIncludePath(getRelativeFilePath(this.includePanel.getFilename()));
    }

    // check if a given test element can be selected as the target of a module controller
    private static boolean isTestElementAllowed(TestElement testElement) {
        return testElement != null
                && !(testElement instanceof AbstractThreadGroup)
                && !(testElement instanceof TestPlan);

    }

    /** {@inheritDoc}} */
    @Override
    public void clearGui() {
        super.clearGui();
        hasAtLeastOneController = false;
        if(importedTreeModel != null) {
            ((DefaultMutableTreeNode) importedTreeModel.getRoot()).removeAllChildren();
        }
        if(importedTreeNodes != null) {
            importedTreeNodes.setVisible(false);
        }
        includePanel.clearGui();
    }

    /** {@inheritDoc}} */
    @Override
    public JPopupMenu createPopupMenu() {
        JPopupMenu menu = new JPopupMenu();
        MenuFactory.addEditMenu(menu, true);
        MenuFactory.addFileMenu(menu);
        return menu;
    }

    private void init() { // WARNING: called from ctor so must not be overridden (i.e. must be private or final)
        setLayout(new VerticalLayout(5, VerticalLayout.BOTH, VerticalLayout.TOP));
        setBorder(makeBorder());
        add(makeTitlePanel());

        add(includePanel);

        JPanel modulesPanel = new JPanel();

        // TODO button to open imported jmx file in new jmeter gui instance?

        // TODO add tree refresh button?
        reloadTreeButton = new JButton(JMeterUtils.getResString("import_controller_refresh_tree")); //$NON-NLS-1$
        reloadTreeButton.addActionListener(this);
        modulesPanel.add(reloadTreeButton);

        openIncludedTestPlanButton = new JButton(JMeterUtils.getResString("import_controller_open_included_testplan")); //$NON-NLS-1$
        openIncludedTestPlanButton.addActionListener(this);
        modulesPanel.add(openIncludedTestPlanButton);

        modulesPanel.setLayout(new BoxLayout(modulesPanel, BoxLayout.Y_AXIS));
        modulesPanel.add(Box.createRigidArea(new Dimension(0,5)));

        JLabel nodesLabel = new JLabel(JMeterUtils.getResString("import_controller_imported_tree")); // $NON-NLS-1$
        modulesPanel.add(nodesLabel);
        modulesPanel.add(warningLabel);
        add(modulesPanel);

        JPanel treePanel = new JPanel();
        treePanel.setLayout(new FlowLayout(FlowLayout.LEFT));
        treePanel.add(importedTreeNodes);
        add(treePanel);
    }

    /**
     * Implementation of Refresh Tree button: <br>
     * reloads the external tree visual representation
     */
    @Override
    public void actionPerformed(ActionEvent e) {
        if (e.getSource() == reloadTreeButton) {
            importedTreeNodes.clearSelection();
            doReset = true; // affects this.configure() called by updateCurrentGui()
            GuiPackage.getInstance().updateCurrentGui();
        }
        if (e.getSource() == openIncludedTestPlanButton) {
            org.apache.jmeter.NewDriver.main(new String[] {"-t", getAbsoluteFilePath(this.includePanel.getFilename())});
        }

    }

    private String getAbsoluteFilePath(String filename){
        File file = new File(filename);
        String currentBaseDir = FileServer.getFileServer().getBaseDir();
        if (!file.isAbsolute()) {
            return (new File(currentBaseDir)).toPath().resolve(file.toPath()).toString();
        }
        else {
            return filename;
        }
    };

    private String getRelativeFilePath(String filename){
        File file = new File(filename);
        String currentBaseDir = FileServer.getFileServer().getBaseDir();
        if (file.isAbsolute()) {
            return (new File(currentBaseDir)).toPath().relativize(file.toPath()).toString();
        }
        else {
            return filename;
        }
    };

    /**
     * Reinitializes the visual representation of JMeter Tree keeping only the Controllers, Test Fragment, Thread Groups, Test Plan
     * <ol>
     *  <li>Rebuilds moduleToRunTreeModel</li>
     *  <li>selects the node referenced by ImportController</li>
     *  <li>Updates warning label</li>
     * </ol>
     */
    private void reinitialize(ImportController controller) {
        ((DefaultMutableTreeNode) importedTreeModel.getRoot()).removeAllChildren();

        GuiPackage gp = GuiPackage.getInstance();
        JMeterTreeNode root;
        if (gp != null) {
            /*if (controller.getSubtreeNode()!=null) {
                gp.getCurrentNode().add(controller.getSubtreeNode());
            }*/
            root = (JMeterTreeNode) GuiPackage.getInstance().getTreeModel().getRoot();
            buildTreeNodeModel(root, 0, null);
            importedTreeModel.nodeStructureChanged((TreeNode) importedTreeModel.getRoot());
        }
        /*

        ((DefaultMutableTreeNode) importedTreeModel.getRoot())
                .setUserObject(controller.getSubtreeNode());

        buildTreeNodeModel(controller.getSubtreeNode(), 0, (DefaultMutableTreeNode) importedTreeModel.getRoot());
        importedTreeModel.nodeStructureChanged((TreeNode) importedTreeModel.getRoot());

        if (!hasAtLeastOneController && importedTreeNodes.isVisible()) {
            warningLabel.setText(JMeterUtils.getResString("import_controller_warning_no_controller"));
            warningLabel.setVisible(true);
        }

         */
    }
    /**
     * Recursively build module to run tree. <br/>
     * Only 4 types of elements are allowed to be added:
     * <ul>
     *  <li>All controllers except ImportController</li>
     *  <li>TestPlan</li>
     *  <li>TestFragmentController</li>
     *  <li>AbstractThreadGroup</li>
     * </ul>
     * @param node - element of test plan tree
     * @param level - level of element in a tree
     * @param parent
     */
    private void buildTreeNodeModel(JMeterTreeNode node, int level,
                                    DefaultMutableTreeNode parent) {
        if (node != null) {
            for (int i = 0; i < node.getChildCount(); i++) {
                JMeterTreeNode cur = (JMeterTreeNode) node.getChildAt(i);
                TestElement te = cur.getTestElement();
                // TODO maybe it's better to include samplers (non-selectable) just to better understand the external components structure?
                if ( te instanceof TestFragmentController
                        || te instanceof AbstractThreadGroup
                        || (te instanceof Controller
                        && !(te instanceof ReplaceableController)
                        && level > 0)) {
                    DefaultMutableTreeNode newNode = new DefaultMutableTreeNode(cur);
                    parent.add(newNode);
                    buildTreeNodeModel(cur, level + 1, newNode);
                    final boolean isController = te instanceof Controller
                            && !(te instanceof ReplaceableController
                            || te instanceof AbstractThreadGroup);
                    hasAtLeastOneController =
                            hasAtLeastOneController || isController;
                }
                else if (te instanceof TestPlan) {
                    ((DefaultMutableTreeNode) importedTreeModel.getRoot())
                            .setUserObject(cur);
                    buildTreeNodeModel(cur, level,
                            (DefaultMutableTreeNode) importedTreeModel.getRoot());
                }
            }
        }
    }

    /**
     * Renderer class for printing "module to run" tree
     */
    private static class ImportControllerCellRenderer extends DefaultTreeCellRenderer { // NOSONAR Ignore parent warning

        //private static final long serialVersionUID = 1129098620102526299L; // TODO what's that for?

        /**
         * @see DefaultTreeCellRenderer#getTreeCellRendererComponent(JTree,
         *      Object, boolean, boolean, boolean, int, boolean)
         */
        @Override
        public Component getTreeCellRendererComponent(JTree tree, Object value, boolean selected, boolean expanded,
                                                      boolean leaf, int row, boolean hasFocus) {
            JMeterTreeNode node = (JMeterTreeNode)((DefaultMutableTreeNode) value).getUserObject();
            if (node != null){
                super.getTreeCellRendererComponent(tree, node.getName(), selected, expanded, leaf, row,
                        hasFocus);
                //print same icon as in test plan tree
                boolean enabled = node.isEnabled();
                // TODO ? if icon not found (no test plan in the file - exception should be handled) java.lang.ClassNotFoundException
                ImageIcon icon = node.getIcon(enabled);
                if (icon != null) {
                    if (enabled) {
                        setIcon(icon);
                    } else {
                        setDisabledIcon(icon);
                    }
                }
                else if (!enabled) { // i.e. no disabled icon found
                    // Must therefore set the enabled icon so there is at least some  icon
                    icon = node.getIcon();
                    if (icon != null) {
                        setIcon(icon);
                    }
                }
            }
            return this;
        }
    }
}
