/*
 * Copyright 2017-2021 The Technical University of Denmark
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package dk.dtu.compute.mavis.gui.widgets;

import javax.swing.*;
import java.awt.*;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;

public class SeekBar
        extends JComponent
{
    private int trackWidth = 0;
    private int leftMargin = 0;

    private double maxValue = 1;
    private double value = 0;
    private boolean hasUserChangedValue = false;

    public SeekBar()
    {
        super();
        this.setOpaque(true);

        var handler = this.new SeekBarHandler();
        this.addMouseListener(handler);
        this.addMouseMotionListener(handler);
    }

    @Override
    public void paint(Graphics g)
    {
        int width = this.getWidth();
        int height = this.getHeight();
        int leftMargin = 14;
        int topMargin = (int) (height * 0.20);
        this.leftMargin = 14;
        int trackHeight = 2;
        this.trackWidth = Math.max(width - 2 * leftMargin, 0);

        double valuePercent = Math.min(this.value, this.maxValue) / this.maxValue;
        int sliderWidth = 4;
        int sliderLeft = leftMargin + (int) (this.trackWidth * valuePercent) - sliderWidth / 2;

        // Draw background.
        g.setColor(this.getBackground());
        g.fillRect(0, 0, width, height);

        // Draw track.
        g.setColor(Color.GRAY);
        g.fillRect(leftMargin, height / 2 - trackHeight / 2, this.trackWidth, trackHeight);

        // Draw slider.
        g.setColor(this.getForeground());
        g.fillRect(sliderLeft, topMargin, sliderWidth, height - 2 * topMargin);
    }

    public void setMaxValue(double maxValue)
    {
        if (maxValue != this.maxValue) {
            this.maxValue = maxValue;
            this.paintImmediately(0, 0, this.getWidth(), this.getHeight());
        }
    }

    /**
     * Value can be set higher than maxValue, in which case it draws as if capped at maxValue, but adapts when maxValue
     * is later adjusted.
     */
    public void setValue(double value)
    {
        if (value != this.value) {
            this.value = value;
            this.paintImmediately(0, 0, this.getWidth(), this.getHeight());
        }
    }

    public double getValue()
    {
        return this.value;
    }

    /**
     * Returns whether the value has changed as a result of user interaction, and resets the changed flag to false.
     */
    public boolean hasUserChangedValue()
    {
        boolean temp = this.hasUserChangedValue;
        this.hasUserChangedValue = false;
        return temp;
    }

    private void setValueFromUI(int x)
    {
        this.hasUserChangedValue = true;
        // Only allow user to change value if track is actually at least 1 pixel wide.
        if (this.trackWidth > 0) {
            double newValue = (double) (x - this.leftMargin) / this.trackWidth * this.maxValue;
            newValue = Math.max(0, Math.min(newValue, this.maxValue));
            this.setValue(newValue);
        }
    }

    private class SeekBarHandler
            extends MouseAdapter
    {
        @Override
        public void mousePressed(MouseEvent e)
        {
            SeekBar.this.setValueFromUI(e.getX());
        }

        @Override
        public void mouseDragged(MouseEvent e)
        {
            SeekBar.this.setValueFromUI(e.getX());
        }
    }
}
