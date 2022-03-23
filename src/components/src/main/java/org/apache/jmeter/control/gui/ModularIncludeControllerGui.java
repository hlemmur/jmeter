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

import java.awt.Component;
import java.awt.Dimension;
import java.awt.FlowLayout;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.io.File;
import java.util.Collection;
import java.util.Iterator;

import javax.swing.JTree;
import javax.swing.JLabel;
import javax.swing.JButton;
import javax.swing.JPopupMenu;
import javax.swing.JPanel;
import javax.swing.ImageIcon;
import javax.swing.SwingConstants;
import javax.swing.Box;
import javax.swing.BoxLayout;

import javax.swing.tree.DefaultMutableTreeNode;
import javax.swing.tree.DefaultTreeCellRenderer;
import javax.swing.tree.DefaultTreeModel;
import javax.swing.tree.DefaultTreeSelectionModel;
import javax.swing.tree.TreeNode;
import javax.swing.tree.TreePath;
import javax.swing.tree.TreeSelectionModel;

import org.apache.jmeter.control.Controller;
import org.apache.jmeter.control.ModularIncludeController;
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


@GUIMenuSortOrder(MenuInfo.SORT_ORDER_DEFAULT+2)
@TestElementMetadata(labelResource = "modular_include_controller_title")
public class ModularIncludeControllerGui extends AbstractControllerGui implements ActionListener { // NOSONAR Ignore parent warning
    //private static final long serialVersionUID = -4195441608252523573L; // TODO what's that for?

    private final FilePanel includePanel =
            new FilePanel(JMeterUtils.getResString("include_path"), ".jmx"); //$NON-NLS-1$ //$NON-NLS-2$

    private static final String SEPARATOR = " > ";

    private static final TreeNode[] EMPTY_TREE_NODES = new TreeNode[0];

    private JMeterTreeNode selected = null;

    /**
     * Model of a Module to run tree. It is a copy of a test plan tree.
     * User object of each element is set to a correlated JMeterTreeNode element of a test plan tree.
     */
    private final DefaultTreeModel moduleToRunTreeModel;

    /**
     * Module to run tree. Allows to select a module to be executed. Many ModuleControllers can reference
     * the same element.
     */
    private final JTree moduleToRunTreeNodes;

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
     * Initializes the gui panel for the ModularIncludeController instance.
     */
    public ModularIncludeControllerGui() {
        moduleToRunTreeModel = new DefaultTreeModel(new DefaultMutableTreeNode());
        moduleToRunTreeNodes = new JTree(moduleToRunTreeModel);
        moduleToRunTreeNodes.setCellRenderer(new ModularIncludeControllerCellRenderer());

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

            @Override
            public void setSelectionPath(TreePath path) {
                DefaultMutableTreeNode lastSelected = (DefaultMutableTreeNode) path.getLastPathComponent();

                if (isSelectedPathAllowed(lastSelected)) {
                    super.setSelectionPath(path);
                }
            }

            @Override
            public void setSelectionPaths(TreePath[] pPaths) {
                DefaultMutableTreeNode lastSelected = (DefaultMutableTreeNode) pPaths[pPaths.length-1].getLastPathComponent();
                if (isSelectedPathAllowed(lastSelected)) {
                    super.setSelectionPaths(pPaths);
                }
            }

            @Override
            public void addSelectionPath(TreePath path) {
                DefaultMutableTreeNode lastSelected = (DefaultMutableTreeNode) path.getLastPathComponent();
                if (isSelectedPathAllowed(lastSelected)) {
                    super.addSelectionPath(path);
                }
            }

            @Override
            public void addSelectionPaths(TreePath[] paths) {
                DefaultMutableTreeNode lastSelected = (DefaultMutableTreeNode) paths[paths.length-1].getLastPathComponent();
                if (isSelectedPathAllowed(lastSelected)) {
                    super.addSelectionPaths(paths);
                }
            }
        };
        tsm.setSelectionMode(TreeSelectionModel.SINGLE_TREE_SELECTION);
        moduleToRunTreeNodes.setSelectionModel(tsm);


        ImageIcon image = JMeterUtils.getImage("warning.png");
        warningLabel = new JLabel("", image, SwingConstants.LEFT); // $NON-NLS-1$
        JFactory.error(warningLabel);
        warningLabel.setVisible(false);

        init();

        moduleToRunTreeNodes.addTreeSelectionListener(
                evt -> {
                    warningLabel.setVisible(false);
                    //reloadTreeButton.setEnabled(true);
                });
        includePanel.addChangeListener(
                evt -> {
                    // reset the test element on external file selection and refresh gui correspondingly
                    // TODO skip on initial creation?
                    selected = null;
                    moduleToRunTreeNodes.clearSelection();
                    doReset = true; // affects this.configure() called by updateCurrentGui()
                    GuiPackage.getInstance().updateCurrentGui();
                });
    }

    /** {@inheritDoc}} */
    @Override
    public String getLabelResource() {
        return "modular_include_controller_title"; // $NON-NLS-1$
    }

    /** {@inheritDoc}} */
    @Override
    public void configure(TestElement el) {
        super.configure(el);
        hasAtLeastOneController = false;
        ModularIncludeController controller = (ModularIncludeController) el;
        this.includePanel.setFilename(controller.getIncludePath());

        if (doReset){
            controller.reset();
            doReset =false;
        }

        this.selected = controller.getSelectedNode();
        if (this.includePanel.getFilename().equals("") || this.includePanel.getFilename()==null){
            moduleToRunTreeNodes.setVisible(false);
            warningLabel.setVisible(false);
            reloadTreeButton.setEnabled(false);
            openIncludedTestPlanButton.setEnabled(false);
        }
        else if (selected == null && controller.getNodePath() != null) {
            warningLabel.setText(JMeterUtils.getResString("modular_include_controller_warning") // $NON-NLS-1$
                    + renderPath(controller.getNodePath()));
            warningLabel.setVisible(true);
        }
        else {
            warningLabel.setVisible(false);
            moduleToRunTreeNodes.setVisible(true);
            reloadTreeButton.setEnabled(true);
            openIncludedTestPlanButton.setEnabled(true);
        }
        reinitialize(controller);
    }

    private String renderPath(Collection<?> path) {
        Iterator<?> iter = path.iterator();
        StringBuilder buf = new StringBuilder();
        boolean first = true;
        while (iter.hasNext()) {
            if (first) {
                first = false;
                iter.next();
                continue;
            }
            buf.append(iter.next());
            if (iter.hasNext()) {
                buf.append(SEPARATOR); // $NON-NLS-1$
            }
        }
        return buf.toString();
    }

    /** {@inheritDoc}} */
    @Override
    public TestElement createTestElement() {
        ModularIncludeController mc = new ModularIncludeController();
        configureTestElement(mc);
        if (selected != null) {
            mc.setSelectedNode(selected);
        }
        return mc;
    }

    /** {@inheritDoc}} */
    @Override
    public void modifyTestElement(TestElement element) {
        configureTestElement(element);
        ModularIncludeController controller = (ModularIncludeController)element;

        // save the relative external filename instead of absolute one
        // current limitation - included files must be under the same dir as the parent test plan
        // TODO what if there is a need to import files from another dir? maybe to use approach of jmeter property lookup lib dir
        // or leave the absolute path but parametrize with file path variable
        controller.setIncludePath(getRelativeFilePath(this.includePanel.getFilename()));

        JMeterTreeNode tn = null;
        DefaultMutableTreeNode lastSelected =
                (DefaultMutableTreeNode) this.moduleToRunTreeNodes.getLastSelectedPathComponent();
        // TODO check if last selected corresponds the current include file - or clear it
        if (lastSelected != null && lastSelected.getUserObject() instanceof JMeterTreeNode) {
            tn = (JMeterTreeNode) lastSelected.getUserObject();
        }
        if (tn != null) {
            selected = tn;
            // prevent from selecting thread group or test plan elements
            if (isTestElementAllowed(selected.getTestElement())) {
                controller.setSelectedNode(selected);
            }
        }
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
        selected = null;
        hasAtLeastOneController = false;
        if(moduleToRunTreeModel != null) {
            ((DefaultMutableTreeNode) moduleToRunTreeModel.getRoot()).removeAllChildren();
        }
        if(moduleToRunTreeNodes != null) {
            moduleToRunTreeNodes.clearSelection();
            moduleToRunTreeNodes.setVisible(false);
            //reloadTreeButton.setEnabled(false);
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
        reloadTreeButton = new JButton(JMeterUtils.getResString("modular_include_controller_refresh_tree")); //$NON-NLS-1$
        reloadTreeButton.addActionListener(this);
        modulesPanel.add(reloadTreeButton);

        openIncludedTestPlanButton = new JButton(JMeterUtils.getResString("modular_include_controller_open_included_testplan")); //$NON-NLS-1$
        openIncludedTestPlanButton.addActionListener(this);
        modulesPanel.add(openIncludedTestPlanButton);

        modulesPanel.setLayout(new BoxLayout(modulesPanel, BoxLayout.Y_AXIS));
        modulesPanel.add(Box.createRigidArea(new Dimension(0,5)));

        JLabel nodesLabel = new JLabel(JMeterUtils.getResString("modular_include_controller_module_to_run")); // $NON-NLS-1$
        modulesPanel.add(nodesLabel);
        modulesPanel.add(warningLabel);
        add(modulesPanel);

        JPanel treePanel = new JPanel();
        treePanel.setLayout(new FlowLayout(FlowLayout.LEFT));
        treePanel.add(moduleToRunTreeNodes);
        add(treePanel);
    }

    /**
     * Recursively traverse module to run tree in order to find JMeterTreeNode element given by testPlanPath
     * in a DefaultMutableTreeNode tree
     *
     * @param level - current search level
     * @param testPlanPath - path of a test plan tree element
     * @param parent - module to run tree parent element
     *
     * @return path of a found element
     */
    private TreeNode[] findPathInTreeModel(
            int level, TreeNode[] testPlanPath, DefaultMutableTreeNode parent)
    {
        if (level >= testPlanPath.length) {
            return EMPTY_TREE_NODES;
        }
        int childCount = parent.getChildCount();
        JMeterTreeNode searchedTreeNode =
                (JMeterTreeNode) testPlanPath[level];

        for (int i = 0; i < childCount; i++) {
            DefaultMutableTreeNode child = (DefaultMutableTreeNode) parent.getChildAt(i);
            JMeterTreeNode childUserObj = (JMeterTreeNode) child.getUserObject();

            // TODO temporary solution! doesn't guarantee the path is correct - names are not unique
            if (childUserObj.getName().equals(searchedTreeNode.getName())){
            // TODO objects are not equal eventually even if they are identical - why?
            //if (childUserObj.equals(searchedTreeNode)) {
                if (level == (testPlanPath.length - 1)) {
                    return child.getPath();
                } else {
                    return findPathInTreeModel(level + 1, testPlanPath, child);
                }
            }
        }
        return EMPTY_TREE_NODES;
    }

    /**
     * Expand module to run tree to selected JMeterTreeNode and set selection path to it
     * @param selected - referenced module to run
     */
    private void focusSelectedOnTree(JMeterTreeNode selected)
    {
        TreeNode[] path = selected.getPath();
        DefaultMutableTreeNode root = (DefaultMutableTreeNode) moduleToRunTreeNodes.getModel().getRoot();
        //treepath of test plan tree and module to run tree cannot be compared directly - moduleToRunTreeModel.getPathToRoot()
        //custom method for finding an JMeterTreeNode element in DefaultMutableTreeNode have to be used
        TreeNode[] dmtnPath = this.findPathInTreeModel(1, path, root);
        if (dmtnPath.length > 0) {
            TreePath treePath = new TreePath(dmtnPath);
            moduleToRunTreeNodes.setSelectionPath(treePath);
            moduleToRunTreeNodes.scrollPathToVisible(treePath);
        }
    }

    /**
     * Implementation of Refresh Tree button: <br>
     * reloads the external tree visual representation
     */
    @Override
    public void actionPerformed(ActionEvent e) {
        if (e.getSource() == reloadTreeButton) {
            selected = null;
            moduleToRunTreeNodes.clearSelection();
            doReset = true; // affects this.configure() called by updateCurrentGui()
            GuiPackage.getInstance().updateCurrentGui();
        }
        if (e.getSource() == openIncludedTestPlanButton) {
            /*
            ProcessBuilder pb = new ProcessBuilder("myshellScript.sh", "myArg1", "myArg2");
            Map<String, String> env = pb.environment();
            env.put("VAR1", "myValue");
            env.remove("OTHERVAR");
            env.put("VAR2", env.get("VAR1") + "suffix");
            pb.directory(new File("myDir"));
            Process p = pb.start();*/
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
     *  <li>selects the node referenced by ModularIncludeController</li>
     *  <li>Updates warning label</li>
     * </ol>
     */
    private void reinitialize(ModularIncludeController controller) {
        ((DefaultMutableTreeNode) moduleToRunTreeModel.getRoot()).removeAllChildren();

        ((DefaultMutableTreeNode) moduleToRunTreeModel.getRoot())
                .setUserObject(controller.getSubtreeNode());

        buildTreeNodeModel(controller.getSubtreeNode(), 0, (DefaultMutableTreeNode) moduleToRunTreeModel.getRoot());
        moduleToRunTreeModel.nodeStructureChanged((TreeNode) moduleToRunTreeModel.getRoot());

        if (selected != null) {
            //expand Module to run tree to selected node and set selection path to it
            this.focusSelectedOnTree(selected);
        }
        if (!hasAtLeastOneController && moduleToRunTreeNodes.isVisible()) {
            warningLabel.setText(JMeterUtils.getResString("modular_include_controller_warning_no_controller"));
            warningLabel.setVisible(true);
        }
    }
    /**
     * Recursively build module to run tree. <br/>
     * Only 4 types of elements are allowed to be added:
     * <ul>
     *  <li>All controllers except ModularIncludeController</li>
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
                    ((DefaultMutableTreeNode) moduleToRunTreeModel.getRoot())
                            .setUserObject(cur);
                    buildTreeNodeModel(cur, level,
                            (DefaultMutableTreeNode) moduleToRunTreeModel.getRoot());
                }
            }
        }
    }

    /**
     * Renderer class for printing "module to run" tree
     */
    private static class ModularIncludeControllerCellRenderer extends DefaultTreeCellRenderer { // NOSONAR Ignore parent warning

        //private static final long serialVersionUID = 1129098620102526299L; // TODO what's that for?

        /**
         * @see javax.swing.tree.DefaultTreeCellRenderer#getTreeCellRendererComponent(javax.swing.JTree,
         *      java.lang.Object, boolean, boolean, boolean, int, boolean)
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
