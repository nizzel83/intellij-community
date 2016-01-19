/*
 * Copyright 2000-2014 JetBrains s.r.o.
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 * http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package com.intellij.ui;

import com.intellij.openapi.Disposable;
import com.intellij.openapi.ui.popup.Balloon;
import com.intellij.openapi.util.Disposer;
import com.intellij.openapi.wm.impl.ToolWindowsPane;
import com.intellij.util.Alarm;
import com.intellij.util.ui.JBInsets;
import com.intellij.util.ui.UIUtil;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ComponentAdapter;
import java.awt.event.ComponentEvent;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

public class BalloonLayoutImpl implements BalloonLayout {

  private final JLayeredPane myLayeredPane;
  private final Insets myInsets;

  private final List<Balloon> myBalloons = new ArrayList<Balloon>();
  private final Map<Balloon, BalloonLayoutData> myLayoutData = new HashMap<Balloon, BalloonLayoutData>();
  private Integer myWidth;

  private final Alarm myRelayoutAlarm = new Alarm();
  private final Runnable myRelayoutRunnable = new Runnable() {
    public void run() {
      relayout();
    }
  };
  private final JRootPane myParent;

  private final Runnable myCloseAll = new Runnable() {
    @Override
    public void run() {
      for (Balloon balloon : new ArrayList<Balloon>(myBalloons)) {
        remove(balloon, true);
      }
    }
  };
  private final Runnable myLayoutRunnable = new Runnable() {
    public void run() {
      calculateSize();
      relayout();
    }
  };

  public BalloonLayoutImpl(@NotNull JRootPane parent, @NotNull Insets insets) {
    myParent = parent;
    myLayeredPane = parent.getLayeredPane();
    myInsets = insets;
    myLayeredPane.addComponentListener(new ComponentAdapter() {
      @Override
      public void componentResized(@NotNull ComponentEvent e) {
        queueRelayout();
      }
    });
  }

  @Override
  public void add(@NotNull Balloon balloon) {
    add(balloon, null);
  }

  @Override
  public void add(@NotNull final Balloon balloon, @Nullable Object layoutData) {
    myBalloons.add(balloon);
    if (layoutData instanceof BalloonLayoutData) {
      BalloonLayoutData balloonLayoutData = (BalloonLayoutData)layoutData;
      balloonLayoutData.closeAll = myCloseAll;
      balloonLayoutData.doLayout = myLayoutRunnable;
      myLayoutData.put(balloon, balloonLayoutData);
    }
    Disposer.register(balloon, new Disposable() {
      public void dispose() {
        remove(balloon, false);
        queueRelayout();
      }
    });

    calculateSize();
    relayout();
    balloon.show(myLayeredPane);
  }

  private void remove(@NotNull Balloon balloon, boolean hide) {
    myBalloons.remove(balloon);
    myLayoutData.remove(balloon);
    if (hide) {
      balloon.hide();
    }
  }

  @NotNull
  private Dimension getSize(@NotNull Balloon balloon) {
    BalloonLayoutData layoutData = myLayoutData.get(balloon);
    if (layoutData == null) {
      Dimension size = balloon.getPreferredSize();
      return myWidth == null ? size : new Dimension(myWidth, size.height);
    }
    return new Dimension(myWidth, layoutData.height);
  }

  public boolean isEmpty() {
    return myBalloons.isEmpty();
  }

  public void queueRelayout() {
    myRelayoutAlarm.cancelAllRequests();
    myRelayoutAlarm.addRequest(myRelayoutRunnable, 200);
  }

  private void calculateSize() {
    myWidth = null;
    if (myLayoutData.isEmpty()) {
      return;
    }

    int width = 0;
    for (Balloon balloon : myBalloons) {
      Dimension size = balloon.getPreferredSize();
      width = Math.max(width, size.width);
      BalloonLayoutData layoutData = myLayoutData.get(balloon);
      if (layoutData != null) {
        layoutData.height = size.height;
      }
    }

    myWidth = width;
  }

  private void relayout() {
    final Dimension size = myLayeredPane.getSize();

    JBInsets.removeFrom(size, myInsets);

    final Rectangle layoutRec = new Rectangle(new Point(myInsets.left, myInsets.top), size);

    List<ArrayList<Balloon>> columns = createColumns(layoutRec);
    while (columns.size() > 1) {
      remove(myBalloons.get(0), true);
      columns = createColumns(layoutRec);
    }
    List<Integer> columnWidths = computeWidths(columns);

    ToolWindowsPane pane = UIUtil.findComponentOfType(myParent, ToolWindowsPane.class);
    JComponent component = pane != null ? pane : myParent;
    int paneOnScreen = component.isShowing() ? component.getLocationOnScreen().y : 0;
    int layerOnScreen = myLayeredPane.isShowing() ? myLayeredPane.getLocationOnScreen().y : 0;
    int toolbarsOffset = paneOnScreen - layerOnScreen;

    JComponent layeredPane = pane != null ? pane.getMyLayeredPane() : null;
    int eachColumnX = (layeredPane == null ? myLayeredPane.getWidth() : layeredPane.getX() + layeredPane.getWidth()) - 4;

    for (int i = 0; i < columns.size(); i++) {
      final ArrayList<Balloon> eachColumn = columns.get(i);
      final Integer eachWidth = columnWidths.get(i);
      eachColumnX -= eachWidth.intValue();
      int eachY = toolbarsOffset + 2;
      for (Balloon eachBalloon : eachColumn) {
        final Rectangle eachRec = new Rectangle();
        eachRec.setSize(getSize(eachBalloon));
        if (((BalloonImpl)eachBalloon).hasShadow()) {
          final Insets shadow = ((BalloonImpl)eachBalloon).getShadowBorderInsets();
          eachRec.width += shadow.left + shadow.right;
          eachRec.height += shadow.top + shadow.bottom;
        }
        eachY += 2; //space between two notifications // TODO: change space for new notifications with shadow
        eachRec.setLocation(eachColumnX + eachWidth.intValue() - eachRec.width, eachY);
        eachBalloon.setBounds(eachRec);
        eachY += eachRec.height;
      }
    }
  }

  private List<Integer> computeWidths(List<ArrayList<Balloon>> columns) {
    List<Integer> columnWidths = new ArrayList<Integer>();
    for (ArrayList<Balloon> eachColumn : columns) {
      int maxWidth = 0;
      for (Balloon each : eachColumn) {
        maxWidth = Math.max(getSize(each).width, maxWidth);
      }
      columnWidths.add(maxWidth);
    }
    return columnWidths;
  }

  private List<ArrayList<Balloon>> createColumns(Rectangle layoutRec) {
    List<ArrayList<Balloon>> columns = new ArrayList<ArrayList<Balloon>>();

    ArrayList<Balloon> eachColumn = new ArrayList<Balloon>();
    columns.add(eachColumn);

    int eachColumnHeight = 0;
    for (Balloon each : myBalloons) {
      final Dimension eachSize = getSize(each);
      if (eachColumnHeight + eachSize.height > layoutRec.getHeight()) {
        eachColumn = new ArrayList<Balloon>();
        columns.add(eachColumn);
        eachColumnHeight = 0;
      }
      eachColumn.add(each);
      eachColumnHeight += eachSize.height;
    }
    return columns;
  }
}
