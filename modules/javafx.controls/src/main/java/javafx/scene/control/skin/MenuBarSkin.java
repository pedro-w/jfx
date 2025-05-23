/*
 * Copyright (c) 2010, 2025, Oracle and/or its affiliates. All rights reserved.
 * DO NOT ALTER OR REMOVE COPYRIGHT NOTICES OR THIS FILE HEADER.
 *
 * This code is free software; you can redistribute it and/or modify it
 * under the terms of the GNU General Public License version 2 only, as
 * published by the Free Software Foundation.  Oracle designates this
 * particular file as subject to the "Classpath" exception as provided
 * by Oracle in the LICENSE file that accompanied this code.
 *
 * This code is distributed in the hope that it will be useful, but WITHOUT
 * ANY WARRANTY; without even the implied warranty of MERCHANTABILITY or
 * FITNESS FOR A PARTICULAR PURPOSE.  See the GNU General Public License
 * version 2 for more details (a copy is included in the LICENSE file that
 * accompanied this code).
 *
 * You should have received a copy of the GNU General Public License version
 * 2 along with this work; if not, write to the Free Software Foundation,
 * Inc., 51 Franklin St, Fifth Floor, Boston, MA 02110-1301 USA.
 *
 * Please contact Oracle, 500 Oracle Parkway, Redwood Shores, CA 94065 USA
 * or visit www.oracle.com if you need additional information or have any
 * questions.
 */

package javafx.scene.control.skin;

import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;
import java.util.stream.Collectors;
import javafx.application.Platform;
import javafx.beans.InvalidationListener;
import javafx.beans.property.DoubleProperty;
import javafx.beans.property.ObjectProperty;
import javafx.beans.property.ReadOnlyProperty;
import javafx.beans.value.ChangeListener;
import javafx.beans.value.WeakChangeListener;
import javafx.collections.ListChangeListener;
import javafx.collections.MapChangeListener;
import javafx.collections.ObservableList;
import javafx.css.CssMetaData;
import javafx.css.Styleable;
import javafx.css.StyleableDoubleProperty;
import javafx.css.StyleableObjectProperty;
import javafx.css.StyleableProperty;
import javafx.css.converter.EnumConverter;
import javafx.css.converter.SizeConverter;
import javafx.event.ActionEvent;
import javafx.event.EventHandler;
import javafx.geometry.Bounds;
import javafx.geometry.NodeOrientation;
import javafx.geometry.Pos;
import javafx.scene.AccessibleAttribute;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Control;
import javafx.scene.control.CustomMenuItem;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuButton;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Skin;
import javafx.scene.control.SkinBase;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyCombination;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseEvent;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import javafx.stage.Window;
import javafx.util.Pair;
import javafx.util.Subscription;
import com.sun.javafx.menu.MenuBase;
import com.sun.javafx.scene.ParentHelper;
import com.sun.javafx.scene.SceneHelper;
import com.sun.javafx.scene.control.GlobalMenuAdapter;
import com.sun.javafx.scene.control.IDisconnectable;
import com.sun.javafx.scene.control.ListenerHelper;
import com.sun.javafx.scene.control.MenuBarButton;
import com.sun.javafx.scene.traversal.Direction;
import com.sun.javafx.scene.traversal.ParentTraversalEngine;
import com.sun.javafx.tk.Toolkit;

/**
 * Default skin implementation for the {@link MenuBar} control. In essence it is
 * a simple toolbar. For the time being there is no overflow behavior and we just
 * hide nodes which fall outside the bounds.
 *
 * @see MenuBar
 * @since 9
 */
public class MenuBarSkin extends SkinBase<MenuBar> {

    private static final ObservableList<Window> stages = Window.getWindows().filtered((w) -> {
        return w instanceof Stage;
    });

    /* *************************************************************************
     *                                                                         *
     * Private fields                                                          *
     *                                                                         *
     **************************************************************************/

    /** contains MenuBarButton's children only */
    private final HBox container;
    /** index of *focused* MenuBarButton */
    private int focusedMenuIndex = -1;

    // represents the currently _open_ menu
    private Menu openMenu;
    private MenuBarButton openMenuButton;

    private static WeakHashMap<Stage, Reference<MenuBarSkin>> systemMenuMap;
    private static List<MenuBase> wrappedDefaultMenus = new ArrayList<>();
    private static Stage currentMenuBarStage;
    private List<MenuBase> wrappedMenus;

    private ChangeListener<Boolean> menuBarFocusedPropertyListener;
    private WeakChangeListener<Boolean> weakMenuBarFocusedPropertyListener;
    private ChangeListener<Scene> sceneChangeListener;
    private ChangeListener<Boolean> menuVisibilityChangeListener;
    private WeakChangeListener<Boolean> weakMenuVisibilityChangeListener;
    private ListenerHelper sceneListenerHelper;
    private IDisconnectable windowFocusHelper;
    private volatile Subscription windowSubscription;
    private boolean pendingDismiss = false;
    private boolean altKeyPressed = false;


    /* *************************************************************************
     *                                                                         *
     * Listeners / Callbacks                                                   *
     *                                                                         *
     **************************************************************************/

    // JDK-8127045 : reset menu selected/focused state
    private EventHandler<ActionEvent> menuActionEventHandler = t -> {
        if (t.getSource() instanceof CustomMenuItem) {
            // JDK-8124120 If CustomMenuItem hideOnClick is false, dont hide
            CustomMenuItem cmi = (CustomMenuItem)t.getSource();
            if (!cmi.isHideOnClick()) return;
        }
        unSelectMenus();
    };

    private ListChangeListener<MenuItem> menuItemListener = (c) -> {
        while (c.next()) {
            for (MenuItem mi : c.getAddedSubList()) {
                updateActionListeners(mi, true);
            }
            for (MenuItem mi: c.getRemoved()) {
                updateActionListeners(mi, false);
            }
        }
    };

    Runnable firstMenuRunnable = new Runnable() {
        @Override
        public void run() {
            /*
            ** check that this menubar's container has contents,
            ** and that the first item is a MenuButton....
            ** otherwise the transfer is off!
            */
            if (container.getChildren().size() > 0) {
                if (container.getChildren().get(0) instanceof MenuButton) {
//                        container.getChildren().get(0).requestFocus();
                    if (focusedMenuIndex != 0) {
                        unSelectMenus();
                        menuModeStart(0);
                        openMenuButton = ((MenuBarButton)container.getChildren().get(0));
//                        openMenu = getSkinnable().getMenus().get(0);
                        openMenuButton.setHover();
                    }
                    else {
                        unSelectMenus();
                    }
                }
            }
        }
    };


    /* *************************************************************************
     *                                                                         *
     * Constructors                                                            *
     *                                                                         *
     **************************************************************************/

    /**
     * Creates a new MenuBarSkin instance, installing the necessary child
     * nodes into the Control {@link Control#getChildren() children} list, as
     * well as the necessary input mappings for handling key, mouse, etc events.
     *
     * @param control The control that this skin should be installed onto.
     */
    public MenuBarSkin(final MenuBar control) {
        super(control);

        container = new HBox();
        container.getStyleClass().add("container");
        getChildren().add(container);

        menuBarFocusedPropertyListener = (ov, t, t1) -> {
            unSelectMenus();
            if (t1 && !container.getChildren().isEmpty()) {
                // JDK-8126368 when MenuBar's focusTraversable is true the first
                // menu will visually indicate focus
                menuModeStart(0);
                openMenuButton = ((MenuBarButton)container.getChildren().get(0));
                setFocusedMenuIndex(0);
                openMenuButton.setHover();
            }
        };
        weakMenuBarFocusedPropertyListener = new WeakChangeListener<>(menuBarFocusedPropertyListener);

        menuVisibilityChangeListener = (ov, t, t1) -> {
            rebuildUI();
        };
        weakMenuVisibilityChangeListener = new WeakChangeListener<>(menuVisibilityChangeListener);

        ListenerHelper lh = ListenerHelper.get(this);

        if (Platform.isFxApplicationThread()) {
            if (Toolkit.getToolkit().getSystemMenu().isSupported()) {
                lh.addInvalidationListener(control.useSystemMenuBarProperty(), (v) -> {
                    rebuildUI();
                });
            }
        } else {
            // delay rebuildUI() until after MenuBar becomes a part of the scene graph
            // this subscription will be removed by cleanUpListeners()
            windowSubscription = getSkinnable()
                .sceneProperty()
                .flatMap(Scene::windowProperty)
                .subscribe(w -> {
                    if (w != null) {
                        if (Toolkit.getToolkit().getSystemMenu().isSupported()) {
                            lh.addInvalidationListener(control.useSystemMenuBarProperty(), (v) -> {
                                rebuildUI();
                            });
                        }
                        // this method will unsubscribe on first run
                        rebuildUI();
                    }
                });
        }

        rebuildUI();
        lh.addListChangeListener(control.getMenus(), (v) -> {
            rebuildUI();
        });

        // When the mouse leaves the menu, the last hovered item should lose
        // it's focus so that it is no longer selected. This code returns focus
        // to the MenuBar itself, such that keyboard navigation can continue.
          // fix JDK-8112401 : menu bar should not request focus on mouse exit.
//        addEventFilter(MouseEvent.MOUSE_EXITED, new EventHandler<MouseEvent>() {
//            @Override
//            public void handle(MouseEvent event) {
//                requestFocus();
//            }
//        });

        /*
        ** add an accelerator for F10 on windows and ctrl+F10 on mac/linux
        ** pressing f10 will select the first menu button on a menubar
        */
        final KeyCombination acceleratorKeyCombo;
        if (com.sun.javafx.util.Utils.isMac()) {
           acceleratorKeyCombo = KeyCombination.keyCombination("ctrl+F10");
        } else {
           acceleratorKeyCombo = KeyCombination.keyCombination("F10");
        }

        ParentTraversalEngine engine = new ParentTraversalEngine(getSkinnable());
        engine.addTraverseListener((node, bounds) -> {
            if (openMenu != null) openMenu.hide();
            setFocusedMenuIndex(0);
        });
        ParentHelper.setTraversalEngine(getSkinnable(), engine);

        lh.addChangeListener(control.sceneProperty(), true, (scene) -> {
            if (sceneListenerHelper != null) {
                sceneListenerHelper.disconnect();
                sceneListenerHelper = null;
            }

            if (scene != null ) {
                sceneListenerHelper = new ListenerHelper();

                // Key navigation
                sceneListenerHelper.addEventFilter(scene, KeyEvent.KEY_PRESSED, (ev) -> {
                    // process right left and may be tab key events
                    if (focusedMenuIndex >= 0) {
                        switch (ev.getCode()) {
                        case LEFT: {
                            boolean isRTL = control.getEffectiveNodeOrientation() == NodeOrientation.RIGHT_TO_LEFT;
                            if (control.getScene().getWindow().isFocused()) {
                                if (openMenu != null && !openMenu.isShowing()) {
                                    if (isRTL) {
                                        moveToMenu(Direction.NEXT, false); // just move the selection bar
                                    } else {
                                        moveToMenu(Direction.PREVIOUS, false); // just move the selection bar
                                    }
                                    ev.consume();
                                    return;
                                }
                                if (isRTL) {
                                    moveToMenu(Direction.NEXT, true);
                                } else {
                                    moveToMenu(Direction.PREVIOUS, true);
                                }
                            }
                            ev.consume();
                            break;
                        }
                        case RIGHT: {
                            boolean isRTL = control.getEffectiveNodeOrientation() == NodeOrientation.RIGHT_TO_LEFT;
                            if (control.getScene().getWindow().isFocused()) {
                                if (openMenu != null && !openMenu.isShowing()) {
                                    if (isRTL) {
                                        moveToMenu(Direction.PREVIOUS, false); // just move the selection bar
                                    } else {
                                        moveToMenu(Direction.NEXT, false); // just move the selection bar
                                    }
                                    ev.consume();
                                    return;
                                }
                                if (isRTL) {
                                    moveToMenu(Direction.PREVIOUS, true);
                                } else {
                                    moveToMenu(Direction.NEXT, true);
                                }
                            }
                            ev.consume();
                            break;
                        }
                        case DOWN:
                            // case SPACE:
                            // case ENTER:
                            // JDK-8127205: Doing nothing for space and enter
                            if (control.getScene().getWindow().isFocused()) {
                                if (focusedMenuIndex != -1) {
                                    Menu menuToOpen = getSkinnable().getMenus().get(focusedMenuIndex);
                                    showMenu(menuToOpen, true);
                                    ev.consume();
                                }
                            }
                            break;
                        case ESCAPE:
                            unSelectMenus();
                            ev.consume();
                            break;
                        default:
                            break;
                        }
                    }
                });

                // When we click else where in the scene - menu selection should be cleared.
                sceneListenerHelper.addEventFilter(scene, MouseEvent.MOUSE_CLICKED, (ev) -> {
                    Bounds containerScreenBounds = container.localToScreen(container.getLayoutBounds());
                    if ((containerScreenBounds == null) || !containerScreenBounds.contains(ev.getScreenX(), ev.getScreenY())) {
                        unSelectMenus();
                    }
                });

                // When the parent window loses focus - menu selection should be cleared
                sceneListenerHelper.addChangeListener(scene.windowProperty(), true, (w) -> {
                    if (windowFocusHelper != null) {
                        windowFocusHelper.disconnect();
                        windowFocusHelper = null;
                    }

                    if (w != null) {
                        windowFocusHelper = sceneListenerHelper.addChangeListener(w.focusedProperty(), true, (focused) -> {
                            if (!focused) {
                                unSelectMenus();
                            }
                        });
                    }
                });

                sceneListenerHelper.addEventFilter(scene, KeyEvent.ANY, (ev) -> {
                    // Clear menu selection when ALT is pressed by itself
                    if (ev.getEventType() == KeyEvent.KEY_PRESSED) {
                        altKeyPressed = false;
                        if (ev.getCode() == KeyCode.ALT && !ev.isConsumed()) {
                            if (focusedMenuIndex == -1) {
                                altKeyPressed = true;
                            }
                            unSelectMenus();
                        }
                    } else if (ev.getEventType() == KeyEvent.KEY_RELEASED) {
                        // Put focus on the first menu when ALT is released
                        // directly after being pressed by itself
                        if (altKeyPressed && ev.getCode() == KeyCode.ALT && !ev.isConsumed()) {
                            firstMenuRunnable.run();
                        }
                        altKeyPressed = false;
                    }
                });

                // F10 accelerator
                scene.getAccelerators().put(acceleratorKeyCombo, firstMenuRunnable);
                sceneListenerHelper.addDisconnectable(() -> {
                    scene.getAccelerators().remove(acceleratorKeyCombo);
                });
            }
        });
    }

    private void showMenu(Menu menu) {
        showMenu(menu, false);
    }

    private void showMenu(Menu menu, boolean selectFirstItem) {
        // hide the currently visible menu, and move to the next one
        if (openMenu == menu) return;
        if (openMenu != null) {
            openMenu.hide();
        }

        openMenu = menu;
        if (!menu.isShowing() && !isMenuEmpty(menu)) {
            if (selectFirstItem) {
                // put selection / focus on first item in menu
                MenuButton menuButton = menuBarButtonAt(focusedMenuIndex);
                Skin<?> skin = menuButton.getSkin();
                if (skin instanceof MenuButtonSkinBase) {
                    ((MenuButtonSkinBase)skin).requestFocusOnFirstMenuItem();
                }
            }

            openMenu.show();
        }
    }

    /**
     * This method is package scoped as it is used in this class as well as for testing
     */
    void setFocusedMenuIndex(int index) {
        focusedMenuIndex = (index >= -1 && index < getSkinnable().getMenus().size()) ? index : -1;

        if (focusedMenuIndex != -1) {
            openMenuButton = (MenuBarButton)container.getChildren().get(focusedMenuIndex);
            openMenuButton.setHover();
        }
    }



    /* *************************************************************************
     *                                                                         *
     * Static methods                                                          *
     *                                                                         *
     **************************************************************************/

    // JDK-8102072: This is intended as private API for SceneBuilder,
    // pending fix for JDK-8090562: Keeping menu in the Mac menu bar when
    // there is no more stage
    /**
     * Set the default system menu bar. This allows an application to keep menu
     * in the system menu bar after the last Window is closed.
     * @param menuBar the menu bar
     */
    public static void setDefaultSystemMenuBar(final MenuBar menuBar) {
        if (Toolkit.getToolkit().getSystemMenu().isSupported()) {
            wrappedDefaultMenus.clear();
            for (Menu menu : menuBar.getMenus()) {
                wrappedDefaultMenus.add(GlobalMenuAdapter.adapt(menu));
            }
            menuBar.getMenus().addListener((ListChangeListener<Menu>) c -> {
                wrappedDefaultMenus.clear();
                for (Menu menu : menuBar.getMenus()) {
                    wrappedDefaultMenus.add(GlobalMenuAdapter.adapt(menu));
                }
            });
        }
    }

    private static MenuBarSkin getMenuBarSkin(Stage stage) {
        if (systemMenuMap == null) return null;
        Reference<MenuBarSkin> skinRef = systemMenuMap.get(stage);
        return skinRef == null ? null : skinRef.get();
    }

    private static void setSystemMenu(Stage stage) {
        if (stage != null && stage.isFocused()) {
            while (stage != null && stage.getOwner() instanceof Stage) {
                MenuBarSkin skin = getMenuBarSkin(stage);
                if (skin != null && skin.wrappedMenus != null) {
                    break;
                } else {
                    // This is a secondary stage (dialog) that doesn't
                    // have own menu bar.
                    //
                    // Continue looking for a menu bar in the parent stage.
                    stage = (Stage)stage.getOwner();
                }
            }
        } else {
            stage = null;
        }

        if (stage != currentMenuBarStage) {
            List<MenuBase> menuList = null;
            if (stage != null) {
                MenuBarSkin skin = getMenuBarSkin(stage);
                if (skin != null) {
                    menuList = skin.wrappedMenus;
                }
            }
            if (menuList == null) {
                menuList = wrappedDefaultMenus;
            }
            Toolkit.getToolkit().getSystemMenu().setMenus(menuList);
            currentMenuBarStage = stage;
        }
    }

    private static void initSystemMenuBar() {
        systemMenuMap = new WeakHashMap<>();

        final InvalidationListener focusedStageListener = ov -> {
            setSystemMenu((Stage)((ReadOnlyProperty<?>)ov).getBean());
        };

        for (Window stage : stages) {
            stage.focusedProperty().addListener(focusedStageListener);
        }
        stages.addListener((ListChangeListener<Window>) c -> {
            while (c.next()) {
                for (Window stage : c.getRemoved()) {
                    stage.focusedProperty().removeListener(focusedStageListener);
                }
                for (Window stage : c.getAddedSubList()) {
                    stage.focusedProperty().addListener(focusedStageListener);
                    setSystemMenu((Stage) stage);
                }
            }
        });
    }



    /* *************************************************************************
     *                                                                         *
     * Properties                                                              *
     *                                                                         *
     **************************************************************************/

    /**
     * Specifies the spacing between menu buttons on the MenuBar.
     */
    // --- spacing
    private DoubleProperty spacing;
    public final void setSpacing(double value) {
        spacingProperty().set(snapSpaceX(value));
    }

    public final double getSpacing() {
        return spacing == null ? 0.0 : snapSpaceX(spacing.get());
    }

    public final DoubleProperty spacingProperty() {
        if (spacing == null) {
            spacing = new StyleableDoubleProperty() {

                @Override
                protected void invalidated() {
                    final double value = get();
                    container.setSpacing(value);
                }

                @Override
                public Object getBean() {
                    return MenuBarSkin.this;
                }

                @Override
                public String getName() {
                    return "spacing";
                }

                @Override
                public CssMetaData<MenuBar,Number> getCssMetaData() {
                    return SPACING;
                }
            };
        }
        return spacing;
    }

    /**
     * Specifies the alignment of the menu buttons inside the MenuBar (by default
     * it is Pos.TOP_LEFT).
     */
    // --- container alignment
    private ObjectProperty<Pos> containerAlignment;
    public final void setContainerAlignment(Pos value) {
        containerAlignmentProperty().set(value);
    }

    public final Pos getContainerAlignment() {
        return containerAlignment == null ? Pos.TOP_LEFT : containerAlignment.get();
    }

    public final ObjectProperty<Pos> containerAlignmentProperty() {
        if (containerAlignment == null) {
            containerAlignment = new StyleableObjectProperty<Pos>(Pos.TOP_LEFT) {

                @Override
                public void invalidated() {
                    final Pos value = get();
                    container.setAlignment(value);
                }

                @Override
                public Object getBean() {
                    return MenuBarSkin.this;
                }

                @Override
                public String getName() {
                    return "containerAlignment";
                }

                @Override
                public CssMetaData<MenuBar,Pos> getCssMetaData() {
                    return ALIGNMENT;
                }
            };
        }
        return containerAlignment;
    }



    /* *************************************************************************
     *                                                                         *
     * Public API                                                              *
     *                                                                         *
     **************************************************************************/

    /** {@inheritDoc} */
    @Override
    public void dispose() {
        if (getSkinnable() == null) {
            return;
        }

        if (sceneListenerHelper != null) {
            sceneListenerHelper.disconnect();
            sceneListenerHelper = null;
        }

        cleanUpListeners();

        if (Platform.isFxApplicationThread()) {
            cleanUpSystemMenu();
        } else {
            Platform.runLater(this::cleanUpSystemMenu);
        }

        getChildren().remove(container);

        // call super.dispose last since it sets control to null
        super.dispose();
    }

    // Return empty insets when "container" is empty, which happens
    // when using the system menu bar.

    /** {@inheritDoc} */
    @Override protected double snappedTopInset() {
        return container.getChildren().isEmpty() ? 0 : super.snappedTopInset();
    }
    /** {@inheritDoc} */
    @Override protected double snappedBottomInset() {
        return container.getChildren().isEmpty() ? 0 : super.snappedBottomInset();
    }
    /** {@inheritDoc} */
    @Override protected double snappedLeftInset() {
        return container.getChildren().isEmpty() ? 0 : super.snappedLeftInset();
    }
    /** {@inheritDoc} */
    @Override protected double snappedRightInset() {
        return container.getChildren().isEmpty() ? 0 : super.snappedRightInset();
    }

    /**
     * Layout the menu bar. This is a simple horizontal layout like an hbox.
     * Any menu items which don't fit into it will simply be made invisible.
     */
    /** {@inheritDoc} */
    @Override protected void layoutChildren(final double x, final double y,
                                            final double w, final double h) {
        // layout the menus one after another
        container.resizeRelocate(x, y, w, h);
    }

    /** {@inheritDoc} */
    @Override protected double computeMinWidth(double height, double topInset, double rightInset, double bottomInset, double leftInset) {
        return container.minWidth(height) + snappedLeftInset() + snappedRightInset();
    }

    /** {@inheritDoc} */
    @Override protected double computePrefWidth(double height, double topInset, double rightInset, double bottomInset, double leftInset) {
        return container.prefWidth(height) + snappedLeftInset() + snappedRightInset();
    }

    /** {@inheritDoc} */
    @Override protected double computeMinHeight(double width, double topInset, double rightInset, double bottomInset, double leftInset) {
        return container.minHeight(width) + snappedTopInset() + snappedBottomInset();
    }

    /** {@inheritDoc} */
    @Override protected double computePrefHeight(double width, double topInset, double rightInset, double bottomInset, double leftInset) {
        return container.prefHeight(width) + snappedTopInset() + snappedBottomInset();
    }

    // grow horizontally, but not vertically
    /** {@inheritDoc} */
    @Override protected double computeMaxHeight(double width, double topInset, double rightInset, double bottomInset, double leftInset) {
        return getSkinnable().prefHeight(-1);
    }



    /* *************************************************************************
     *                                                                         *
     * Private implementation                                                  *
     *                                                                         *
     **************************************************************************/

    // package protected for testing purposes
    MenuBarButton menuBarButtonAt(int i) {
        return (MenuBarButton)container.getChildren().get(i);
    }

    int getFocusedMenuIndex() {
        return focusedMenuIndex;
    }

    private boolean menusContainCustomMenuItem() {
        for (Menu menu : getSkinnable().getMenus()) {
            if (menuContainsCustomMenuItem(menu)) {
                System.err.println("Warning: MenuBar ignored property useSystemMenuBar because menus contain CustomMenuItem");
                return true;
            }
        }
        return false;
    }

    private boolean menuContainsCustomMenuItem(Menu menu) {
        for (MenuItem mi : menu.getItems()) {
            if (mi instanceof CustomMenuItem && !(mi instanceof SeparatorMenuItem)) {
                return true;
            } else if (mi instanceof Menu) {
                if (menuContainsCustomMenuItem((Menu)mi)) {
                    return true;
                }
            }
        }
        return false;
    }

    private int getMenuBarButtonIndex(MenuBarButton m) {
        for (int i= 0; i < container.getChildren().size(); i++) {
            MenuBarButton menuButton = (MenuBarButton)container.getChildren().get(i);
            if (m == menuButton) {
                return i;
            }
        }
        return -1;
    }

    private void updateActionListeners(MenuItem item, boolean add) {
        if (item instanceof Menu) {
            Menu menu = (Menu) item;

            if (add) {
                menu.getItems().addListener(menuItemListener);
            } else {
                menu.getItems().removeListener(menuItemListener);
            }

            for (MenuItem mi : menu.getItems()) {
                updateActionListeners(mi, add);
            }
        } else {
            if (add) {
                item.addEventHandler(ActionEvent.ACTION, menuActionEventHandler);
            } else {
                item.removeEventHandler(ActionEvent.ACTION, menuActionEventHandler);
            }
        }
    }

    private void cleanUpListeners() {
        Subscription sub = windowSubscription;
        if (sub != null) {
            sub.unsubscribe();
            windowSubscription = null;
        }

        getSkinnable().focusedProperty().removeListener(weakMenuBarFocusedPropertyListener);

        for (Menu m : getSkinnable().getMenus()) {
            // remove action listeners
            updateActionListeners(m, false);

            m.visibleProperty().removeListener(weakMenuVisibilityChangeListener);
        }

        for (Node n : container.getChildren()) {
            // Stop observing menu's showing & disable property for changes.
            // Need to unbind before clearing container's children.
            MenuBarButton menuButton = (MenuBarButton)n;
            menuButton.hide();
            menuButton.menu.showingProperty().removeListener(menuButton.menuListener);
            menuButton.disableProperty().unbind();
            menuButton.textProperty().unbind();
            menuButton.graphicProperty().unbind();
            menuButton.styleProperty().unbind();

            menuButton.dispose();

            // JDK-8118781 : old instance of context menu window/popup for this MenuButton needs
            // to be cleaned up. Setting the skin to null - results in a call to dispose()
            // on the skin which in this case MenuButtonSkinBase - does the subsequent
            // clean up to ContextMenu/popup window.
            menuButton.setSkin(null);
            menuButton = null;
        }

        container.getChildren().clear();
    }

    private void rebuildUI() {
        if (!Platform.isFxApplicationThread()) {
            return;
        }
        cleanUpListeners();

        if (Toolkit.getToolkit().getSystemMenu().isSupported()) {
            final Scene scene = getSkinnable().getScene();
            if (scene != null) {
                // JDK-8094110 - make sure system menu is updated when this MenuBar's scene changes.
                if (sceneChangeListener == null) {
                    sceneChangeListener = (observable, oldValue, newValue) -> {

                        if (oldValue != null) {
                            if (oldValue.getWindow() instanceof Stage) {
                                final Stage stage = (Stage) oldValue.getWindow();
                                final MenuBarSkin curMBSkin = getMenuBarSkin(stage);
                                if (curMBSkin == MenuBarSkin.this) {
                                    curMBSkin.wrappedMenus = null;
                                    systemMenuMap.remove(stage);
                                    if (currentMenuBarStage == stage) {
                                        currentMenuBarStage = null;
                                        setSystemMenu(stage);
                                    }
                                } else {
                                    if (getSkinnable().isUseSystemMenuBar() &&
                                            curMBSkin != null && curMBSkin.getSkinnable() != null &&
                                            curMBSkin.getSkinnable().isUseSystemMenuBar()) {
                                        curMBSkin.getSkinnable().setUseSystemMenuBar(false);
                                    }
                                }
                            }
                        }

                        if (newValue != null) {
                            if (getSkinnable().isUseSystemMenuBar() && !menusContainCustomMenuItem()) {
                                if (newValue.getWindow() instanceof Stage) {
                                    final Stage stage = (Stage) newValue.getWindow();
                                    if (systemMenuMap == null) {
                                        initSystemMenuBar();
                                    }
                                    wrappedMenus = new ArrayList<>();
                                    systemMenuMap.put(stage, new WeakReference<>(this));
                                    for (Menu menu : getSkinnable().getMenus()) {
                                        wrappedMenus.add(GlobalMenuAdapter.adapt(menu));
                                    }
                                    currentMenuBarStage = null;
                                    setSystemMenu(stage);

                                    // TODO: Why two request layout calls here?
                                    getSkinnable().requestLayout();
                                    javafx.application.Platform.runLater(() -> getSkinnable().requestLayout());
                                }
                            }
                        }
                    };
                    getSkinnable().sceneProperty().addListener(sceneChangeListener);
                }

                // Fake a change event to trigger an update to the system menu.
                sceneChangeListener.changed(getSkinnable().sceneProperty(), scene, scene);

                // If the system menu references this MenuBarSkin, then we're done with rebuilding the UI.
                // If the system menu does not reference this MenuBarSkin, then the MenuBar is a child of the scene
                // and we continue with the update.
                // If there is no system menu but this skinnable uses the system menu bar, then the
                // stage just isn't focused yet (see setSystemMenu) and we're done rebuilding the UI.
                if (currentMenuBarStage != null ? getMenuBarSkin(currentMenuBarStage) == MenuBarSkin.this : getSkinnable().isUseSystemMenuBar()) {
                    return;
                }

            } else {
                // if scene is null, make sure this MenuBarSkin isn't left behind as the system menu
                if (currentMenuBarStage != null) {
                    final MenuBarSkin curMBSkin = getMenuBarSkin(currentMenuBarStage);
                    if (curMBSkin == MenuBarSkin.this) {
                        setSystemMenu(null);
                    }
                }
            }
        }

        getSkinnable().focusedProperty().addListener(weakMenuBarFocusedPropertyListener);

        for (final Menu menu : getSkinnable().getMenus()) {

            menu.visibleProperty().addListener(weakMenuVisibilityChangeListener);

            if (!menu.isVisible()) continue;
            final MenuBarButton menuButton = new MenuBarButton(this, menu);
            menuButton.setFocusTraversable(false);
            menuButton.getStyleClass().add("menu");
            menuButton.setStyle(menu.getStyle()); // copy style

            menuButton.getItems().setAll(menu.getItems());
            container.getChildren().add(menuButton);

            menuButton.menuListener = (observable, oldValue, newValue) -> {
                if (menu.isShowing()) {
                    menuButton.show();
                    menuModeStart(container.getChildren().indexOf(menuButton));
                } else {
                    menuButton.hide();
                }
            };
            menuButton.menu = menu;
            menu.showingProperty().addListener(menuButton.menuListener);
            menuButton.disableProperty().bindBidirectional(menu.disableProperty());
            menuButton.textProperty().bind(menu.textProperty());
            menuButton.graphicProperty().bind(menu.graphicProperty());
            menuButton.styleProperty().bind(menu.styleProperty());
            menuButton.getProperties().addListener((MapChangeListener<Object, Object>) c -> {
                 if (c.wasAdded() && MenuButtonSkin.AUTOHIDE.equals(c.getKey())) {
                    menuButton.getProperties().remove(MenuButtonSkin.AUTOHIDE);
                    menu.hide();
                }
            });
            menuButton.showingProperty().addListener((observable, oldValue, isShowing) -> {
                if (isShowing) {
                    if(openMenuButton == null && focusedMenuIndex != -1)
                        openMenuButton = (MenuBarButton)container.getChildren().get(focusedMenuIndex);

                    if (openMenuButton != null && openMenuButton != menuButton) {
                        openMenuButton.clearHover();
                    }
                    openMenuButton = menuButton;
                    showMenu(menu);
                } else {
                    // Fix for JDK-8167138 - we need to clear out the openMenu / openMenuButton
                    // when the menu is hidden (e.g. via autoHide), so that we can open it again
                    // the next time (if it is the first menu requested to show)
                    openMenu = null;
                    openMenuButton = null;
                }
            });

            menuButton.setOnMousePressed(event -> {
                pendingDismiss = menuButton.isShowing();

                // check if the owner window has focus
                if (menuButton.getScene().getWindow().isFocused()) {
                    showMenu(menu);
                    // update FocusedIndex
                    menuModeStart(getMenuBarButtonIndex(menuButton));
                }
            });

            menuButton.setOnMouseReleased(event -> {
                // check if the owner window has focus
                if (menuButton.getScene().getWindow().isFocused()) {
                    if (pendingDismiss) {
                        resetOpenMenu();
                    }
                }
                pendingDismiss = false;
            });

            menuButton.setOnMouseEntered(event -> {
                // check if the owner window has focus
                if (menuButton.getScene() != null && menuButton.getScene().getWindow() != null &&
                        menuButton.getScene().getWindow().isFocused()) {
                    if (openMenuButton != null && openMenuButton != menuButton) {
                            openMenuButton.clearHover();
                            openMenuButton = null;
                            openMenuButton = menuButton;
                    }
                    updateFocusedIndex();
                    if (openMenu != null && openMenu != menu) {
                        showMenu(menu);
                    }
                }
            });
            updateActionListeners(menu, true);
        }
        getSkinnable().requestLayout();
    }

    // always called in the fx application thread
    private void cleanUpSystemMenu() {
        if (sceneChangeListener != null && getSkinnable() != null) {
            getSkinnable().sceneProperty().removeListener(sceneChangeListener);
            // rebuildUI creates sceneChangeListener and adds sceneChangeListener to sceneProperty,
            // so sceneChangeListener needs to be reset to null in the off chance that this
            // skin instance is reused.
            sceneChangeListener = null;
        }

        if (currentMenuBarStage != null && getMenuBarSkin(currentMenuBarStage) == MenuBarSkin.this) {
            setSystemMenu(null);
        }

        if (systemMenuMap != null) {
            Iterator<Map.Entry<Stage,Reference<MenuBarSkin>>> iterator = systemMenuMap.entrySet().iterator();
            while (iterator.hasNext()) {
                Map.Entry<Stage,Reference<MenuBarSkin>> entry = iterator.next();
                Reference<MenuBarSkin> ref = entry.getValue();
                MenuBarSkin skin = ref != null ? ref.get() : null;
                if (skin == null || skin == MenuBarSkin.this) {
                    iterator.remove();
                }
            }
        }
    }

    private boolean isMenuEmpty(Menu menu) {
        boolean retVal = true;
        if (menu != null) {
            for (MenuItem m : menu.getItems()) {
                if (m != null && m.isVisible()) retVal = false;
            }
        }
        return retVal;
    }

    private void resetOpenMenu() {
        if (openMenu != null) {
            openMenu.hide();
            openMenu = null;
        }
    }

    private void unSelectMenus() {
        clearMenuButtonHover();
        if (focusedMenuIndex == -1) return;
        if (openMenu != null) {
            openMenu.hide();
            openMenu = null;
        }
        if (openMenuButton != null) {
            openMenuButton.clearHover();
            openMenuButton = null;
        }
        menuModeEnd();
    }

    private void menuModeStart(int newIndex) {
        if (focusedMenuIndex == -1) {
            SceneHelper.getSceneAccessor().setTransientFocusContainer(getSkinnable().getScene(), getSkinnable());
        }
        setFocusedMenuIndex(newIndex);
    }

    private void menuModeEnd() {
        if (focusedMenuIndex != -1) {
            SceneHelper.getSceneAccessor().setTransientFocusContainer(getSkinnable().getScene(), null);

            /* Return the a11y focus to a control in the scene. */
            getSkinnable().notifyAccessibleAttributeChanged(AccessibleAttribute.FOCUS_NODE);
        }
        setFocusedMenuIndex(-1);
    }

    private void moveToMenu(Direction dir, boolean doShow) {
        Menu focusedMenu = menuBarButtonAt(focusedMenuIndex).menu;
        boolean showNextMenu = doShow && focusedMenu.isShowing();
        findSibling(dir, focusedMenuIndex).ifPresent(p -> {
            setFocusedMenuIndex(p.getValue());
            if (showNextMenu) {
                // we explicitly do *not* allow selection - we are moving
                // to a sibling menu, and therefore selection should be reset
                showMenu(p.getKey(), false);
            }
        });
    }

    private Optional<Pair<Menu,Integer>> findSibling(Direction dir, int startIndex) {
        if (startIndex == -1) {
            return Optional.empty();
        }

        List<Menu> visibleMenus = getSkinnable().getMenus().stream().filter(Menu::isVisible)
                .collect(Collectors.toList());
        final int totalMenus = visibleMenus.size();
        int i = 0;
        int nextIndex = 0;

        // Traverse all visible menus in menubar to find nextIndex
        while (i < totalMenus) {
            i++;

            nextIndex = (startIndex + (dir.isForward() ? 1 : -1)) % totalMenus;

            if (nextIndex == -1) {
                // loop backwards to end
                nextIndex = totalMenus - 1;
            }

            // if menu at nextIndex is disabled, skip it
            if (visibleMenus.get(nextIndex).isDisable()) {
                // Calculate new nextIndex by continuing loop
                startIndex = nextIndex;
            } else {
                // nextIndex is to be highlighted
                break;
            }
        }

        clearMenuButtonHover();
        return Optional.of(new Pair<>(visibleMenus.get(nextIndex), nextIndex));
    }

    private void updateFocusedIndex() {
        int index = 0;
        for(Node n : container.getChildren()) {
            if (n.isHover()) {
                setFocusedMenuIndex(index);
                return;
            }
            index++;
        }
        menuModeEnd();
    }

    private void clearMenuButtonHover() {
         for(Node n : container.getChildren()) {
            if (n.isHover()) {
                ((MenuBarButton)n).clearHover();
                ((MenuBarButton)n).disarm();
                return;
            }
        }
    }



    /* *************************************************************************
     *                                                                         *
     * CSS                                                                     *
     *                                                                         *
     **************************************************************************/

    private static final CssMetaData<MenuBar,Number> SPACING =
            new CssMetaData<>("-fx-spacing", SizeConverter.getInstance(), 0.0) {
                @Override
                public boolean isSettable(MenuBar n) {
                    final MenuBarSkin skin = (MenuBarSkin) n.getSkin();
                    return skin.spacing == null || !skin.spacing.isBound();
                }

                @Override
                public StyleableProperty<Number> getStyleableProperty(MenuBar n) {
                    final MenuBarSkin skin = (MenuBarSkin) n.getSkin();
                    return (StyleableProperty<Number>)skin.spacingProperty();
                }
            };

    private static final CssMetaData<MenuBar,Pos> ALIGNMENT =
            new CssMetaData<>("-fx-alignment", new EnumConverter<>(Pos.class), Pos.TOP_LEFT ) {
                @Override
                public boolean isSettable(MenuBar n) {
                    final MenuBarSkin skin = (MenuBarSkin) n.getSkin();
                    return skin.containerAlignment == null || !skin.containerAlignment.isBound();
                }

                @Override
                public StyleableProperty<Pos> getStyleableProperty(MenuBar n) {
                    final MenuBarSkin skin = (MenuBarSkin) n.getSkin();
                    return (StyleableProperty<Pos>)skin.containerAlignmentProperty();
                }
            };

    private static final List<CssMetaData<? extends Styleable, ?>> STYLEABLES;
    static {
        final List<CssMetaData<? extends Styleable, ?>> styleables = new ArrayList<>(SkinBase.getClassCssMetaData());

        // StackPane also has -fx-alignment. Replace it with
        // MenuBarSkin's.
        // TODO: Really should be able to reference StackPane.StyleableProperties.ALIGNMENT
        final String alignmentProperty = ALIGNMENT.getProperty();
        for (int n=0, nMax=styleables.size(); n<nMax; n++) {
            final CssMetaData<?,?> prop = styleables.get(n);
            if (alignmentProperty.equals(prop.getProperty())) styleables.remove(prop);
        }

        styleables.add(SPACING);
        styleables.add(ALIGNMENT);
        STYLEABLES = Collections.unmodifiableList(styleables);
    }

    /**
     * Returns the CssMetaData associated with this class, which may include the
     * CssMetaData of its superclasses.
     * @return the CssMetaData associated with this class, which may include the
     * CssMetaData of its superclasses
     */
    public static List<CssMetaData<? extends Styleable, ?>> getClassCssMetaData() {
        return STYLEABLES;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public List<CssMetaData<? extends Styleable, ?>> getCssMetaData() {
        return getClassCssMetaData();
    }

    /* *************************************************************************
     *                                                                         *
     * Accessibility handling                                                  *
     *                                                                         *
     **************************************************************************/

    /** {@inheritDoc} */
    @Override protected Object queryAccessibleAttribute(AccessibleAttribute attribute, Object... parameters) {
        switch (attribute) {
            case FOCUS_NODE: return openMenuButton;
            default: return super.queryAccessibleAttribute(attribute, parameters);
        }
    }
}
