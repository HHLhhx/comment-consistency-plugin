package com.nju.comment.toolwindow;

import com.intellij.ui.JBColor;
import lombok.Getter;

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;

/**
 * 滑动开关组件
 */
@Getter
public class ToggleSwitch extends JComponent {

    private boolean selected;

    private static final int WIDTH = 36;
    private static final int HEIGHT = 20;
    private static final int KNOB = 16;
    private static final int PAD = 2;

    private static final Color ON_TRACK = new JBColor(new Color(52, 199, 89), new Color(48, 209, 88));
    private static final Color OFF_TRACK = new JBColor(new Color(199, 199, 204), new Color(99, 99, 102));

    public ToggleSwitch(boolean selected) {
        this.selected = selected;
        Dimension size = new Dimension(WIDTH, HEIGHT);
        setPreferredSize(size);
        setMinimumSize(size);
        setMaximumSize(size);
        setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
        setOpaque(false);
        addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                setSelected(!ToggleSwitch.this.selected);
            }
        });
    }

    public void setSelected(boolean sel) {
        if (this.selected != sel) {
            boolean old = this.selected;
            this.selected = sel;
            repaint();
            firePropertyChange("selected", old, sel);
            for (ActionListener l : listenerList.getListeners(ActionListener.class)) {
                l.actionPerformed(new ActionEvent(
                        this, ActionEvent.ACTION_PERFORMED, "toggle"));
            }
        }
    }

    public void addActionListener(ActionListener l) {
        listenerList.add(ActionListener.class, l);
    }

    @Override
    protected void paintComponent(Graphics g) {
        Graphics2D g2 = (Graphics2D) g.create();
        g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        g2.setColor(selected ? ON_TRACK : OFF_TRACK);
        g2.fill(new RoundRectangle2D.Float(0, 0, WIDTH, HEIGHT, HEIGHT, HEIGHT));

        int knobX = selected ? WIDTH - KNOB - PAD : PAD;
        g2.setColor(Color.WHITE);
        g2.fillOval(knobX, PAD, KNOB, KNOB);

        g2.dispose();
    }
}
