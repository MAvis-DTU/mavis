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
import java.awt.geom.Path2D;

public class StepBackwardButton
        extends JComponent
{
    private final Path2D.Float triangle = new Path2D.Float(Path2D.WIND_EVEN_ODD, 3);

    public StepBackwardButton(Runnable action)
    {
        super();
        this.setOpaque(true);

        this.addMouseListener(new MouseAdapter()
        {
            @Override
            public void mouseReleased(MouseEvent e)
            {
                if (e.getButton() != MouseEvent.BUTTON1) {
                    return;
                }
                JComponent source = (JComponent) e.getSource();
                if (0 <= e.getX() && e.getX() <= source.getWidth() && 0 <= e.getY() && e.getY() <= source.getHeight()) {
                    action.run();
                }
            }
        });
    }

    @Override
    public void paint(Graphics g)
    {
        /*
         * A bar and one left-pointing triangle (slightly overlapped).
         */
        Graphics2D g2d = (Graphics2D) g;

        int width = this.getWidth();
        int height = this.getHeight();
        int margin = (int) (width * 0.15);
        int internalWidth = width - 2 * margin;
        int internalHeight = height - 2 * margin;
        int barWidth = (int) (internalWidth * 0.20);

        this.triangle.reset();
        this.triangle.moveTo(margin + internalWidth, margin);
        this.triangle.lineTo(margin, height / 2f);
        this.triangle.lineTo(margin + internalWidth, margin + internalHeight);
        this.triangle.closePath();

        g2d.setColor(this.getBackground());
        g2d.fillRect(0, 0, width, height);

        g2d.setColor(this.getForeground());
        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        g2d.fillRect(margin, margin, barWidth, internalHeight);
        g2d.fill(this.triangle);
    }
}
