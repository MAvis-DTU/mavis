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

import dk.dtu.compute.mavis.domain.Domain;
import dk.dtu.compute.mavis.server.Server;

import javax.swing.*;
import java.awt.*;
import java.awt.geom.AffineTransform;
import java.awt.image.VolatileImage;

import static java.awt.RenderingHints.*;

public class DomainPanel
        extends JPanel
{
    private VolatileImage domainBackgroundBuffer;
    private VolatileImage stateBackgroundBuffer;
    private VolatileImage stateTransitionBuffer;
    private Graphics2D domainBackgroundGraphics;
    private Graphics2D stateBackgroundGraphics;
    private Graphics2D stateTransitionGraphics;

    private Domain domain;
    private Thread domainRenderingThread;
    private static int renderingThreadCount = 0;

    private double lastStateInterpolation = 0;
    private double currentStateInterpolation;
    private boolean requireFullRender = false;
    private boolean isNewlyRendered = false;

    private int lastWidth = 0;
    private int lastHeight = 0;

    private boolean shutdown = false;
    private boolean signal = false;

    public DomainPanel(Domain domain)
    {
        super();
        this.setOpaque(true);

        this.domain = domain;

        /*
         * Thread which renders the buffers off the EDT. The rendering begins on the signal, and the EDT
         * can wait for rendering to finish before painting the buffers to screen if newly rendered.
         */
        this.domainRenderingThread = new Thread(this::renderLoop,
                                                "DomainRenderingThread-" + DomainPanel.renderingThreadCount++);
    }

    @Override
    public void paint(Graphics g)
    {
        g.drawImage(this.stateTransitionBuffer, 0, 0, null);
    }

    /**
     * Repaints the buffers to the screen if the rendering thread rendered new content in its last rendering pass.
     * <p>
     * IMPORTANT: Must only be called by the EDT, after waiting on waitRenderFinish().
     */
    public void repaintIfNewlyRendered()
    {
        if (this.isNewlyRendered) {
            this.paintImmediately(0, 0, this.getWidth(), this.getHeight());
        }
    }

    public void startRenderingThread()
    {
        this.domainRenderingThread.start();
    }

    /**
     * Signals the rendering thread for this DomainPanel to shut down, and waits for it to join.
     */
    public void shutdownRenderingThread()
    {
        synchronized (this) {
            this.shutdown = true;
            this.signal = true;
            this.notifyAll();
        }
        while (true) {
            try {
                this.domainRenderingThread.join();
                return;
            } catch (InterruptedException ignored) {
            }
        }
    }

    /**
     * Signals the rendering thread for this DomainPanel to render the given state interpolation.
     */
    public synchronized void signalRenderBegin(double stateInterpolation)
    {
        this.validateBuffers();
        this.currentStateInterpolation = stateInterpolation;
        this.signal = true;
        this.notifyAll();
    }

    /**
     * Wait for this DomainPanel's rendering thread to finish rendering after the last call to signalRenderBegin().
     */
    public synchronized void waitRenderFinish()
    {
        while (this.signal) {
            try {
                this.wait();
            } catch (InterruptedException ignored) {
            }
        }
    }

    private void createBuffers()
    {
        var gc = this.getGraphicsConfiguration();
        var scalingFactor = gc.getDefaultTransform().getScaleX();
        // Round up to ensure we're not missing a row/column of pixels at the bottom/right in case of odd scaling
        // factors (e.g. 125%, 175%, etc.).
        int deviceWidth = (int) Math.ceil(this.getWidth() * scalingFactor);
        int deviceHeight = (int) Math.ceil(this.getHeight() * scalingFactor);

        this.domainBackgroundBuffer = gc.createCompatibleVolatileImage(deviceWidth, deviceHeight);
        this.domainBackgroundGraphics = this.domainBackgroundBuffer.createGraphics();
        this.domainBackgroundGraphics.setTransform(new AffineTransform());
        this.domainBackgroundGraphics.setRenderingHint(KEY_TEXT_ANTIALIASING, VALUE_TEXT_ANTIALIAS_ON);
        this.domainBackgroundGraphics.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);

        this.stateBackgroundBuffer = gc.createCompatibleVolatileImage(deviceWidth, deviceHeight);
        this.stateBackgroundGraphics = this.stateBackgroundBuffer.createGraphics();
        this.stateBackgroundGraphics.setTransform(new AffineTransform());
        this.stateBackgroundGraphics.setRenderingHint(KEY_TEXT_ANTIALIASING, VALUE_TEXT_ANTIALIAS_ON);
        this.stateBackgroundGraphics.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);

        this.stateTransitionBuffer = gc.createCompatibleVolatileImage(deviceWidth, deviceHeight);
        this.stateTransitionGraphics = this.stateTransitionBuffer.createGraphics();
        this.stateTransitionGraphics.setTransform(new AffineTransform());
        this.stateTransitionGraphics.setRenderingHint(KEY_TEXT_ANTIALIASING, VALUE_TEXT_ANTIALIAS_ON);
        this.stateTransitionGraphics.setRenderingHint(KEY_ANTIALIASING, VALUE_ANTIALIAS_ON);
    }

    /**
     * Reallocates the buffers to match the current display device and size of the panel, if necessary.
     */
    private void validateBuffers()
    {
        // Don't reallocate if size is 0 or less in any dimension, or we are not visible.
        if (this.getWidth() <= 0 || this.getHeight() <= 0 || !this.isVisible()) {
            return;
        }

        // Allocate if this is the first render (buffers are null).
        if (this.domainBackgroundBuffer == null) {
            this.createBuffers();
            this.lastWidth = this.getWidth();
            this.lastHeight = this.getHeight();
            this.requireFullRender = true;
        }

        // Validate buffers.
        int status1 = this.domainBackgroundBuffer.validate(this.getGraphicsConfiguration());
        int status2 = this.stateBackgroundBuffer.validate(this.getGraphicsConfiguration());
        int status3 = this.stateTransitionBuffer.validate(this.getGraphicsConfiguration());
        boolean revalidate = false;

        // Reallocate if any buffers were incompatible with their graphics configuration (e.g. window moved to new
        // display device).
        // Reallocate if the panel has changed size.
        boolean isBuffersIncompatible = status1 == VolatileImage.IMAGE_INCOMPATIBLE ||
                                        status2 == VolatileImage.IMAGE_INCOMPATIBLE ||
                                        status3 == VolatileImage.IMAGE_INCOMPATIBLE;
        boolean isSizeChanged = this.getWidth() != this.lastWidth || this.getHeight() != this.lastHeight;
        if (isBuffersIncompatible || isSizeChanged) {
            this.domainBackgroundGraphics.dispose();
            this.stateBackgroundGraphics.dispose();
            this.stateTransitionGraphics.dispose();
            this.domainBackgroundBuffer.flush();
            this.stateBackgroundBuffer.flush();
            this.stateTransitionBuffer.flush();
            this.createBuffers();
            this.lastWidth = this.getWidth();
            this.lastHeight = this.getHeight();
            this.requireFullRender = true;
            revalidate = true;
        }

        // We have to revalidate if we just reallocated the buffers, otherwise we will miss a frame.
        // (Because .validate() does not restore an image if it returns IMAGE_INCOMPATIBLE.)
        // Since we have just checked compatibility with the graphics configuration, we will skip that check here.
        if (revalidate) {
            status1 = this.domainBackgroundBuffer.validate(null);
            status2 = this.stateBackgroundBuffer.validate(null);
            status3 = this.stateTransitionBuffer.validate(null);
        }

        // If buffers were restored, require a full render.
        isBuffersIncompatible = status1 == VolatileImage.IMAGE_INCOMPATIBLE ||
                                status2 == VolatileImage.IMAGE_INCOMPATIBLE ||
                                status3 == VolatileImage.IMAGE_INCOMPATIBLE;
        if (isBuffersIncompatible) {
            this.requireFullRender = true;
        }
    }

    /**
     * Assumes the buffers are valid and of appropriate sizes.
     * Call validateBuffers() first to validate and restore/reallocate buffers as necessary.
     */
    private void renderDomainBackground()
    {
        this.domain.renderDomainBackground(this.domainBackgroundGraphics,
                                           this.domainBackgroundBuffer.getWidth(),
                                           this.domainBackgroundBuffer.getHeight());
    }

    /**
     * Assumes the domainBackgroundBuffer is up-to-date. Call renderDomainBackground() first if not.
     */
    private void renderStateBackground(int stateID)
    {
        var tx = this.stateBackgroundGraphics.getTransform();
        this.stateBackgroundGraphics.setTransform(this.getGraphicsConfiguration().getDefaultTransform());
        this.stateBackgroundGraphics.drawImage(this.domainBackgroundBuffer, 0, 0, null);
        this.stateBackgroundGraphics.setTransform(tx);
        this.domain.renderStateBackground(this.stateBackgroundGraphics, stateID);
    }

    /**
     * Assumes the stateBackgroundBuffer is up-to-date. Call renderStateBackground() first if not.
     */
    private void renderStateTransition(int stateID, double interpolation)
    {
        var tx = this.stateTransitionGraphics.getTransform();
        this.stateTransitionGraphics.setTransform(this.getGraphicsConfiguration().getDefaultTransform());
        this.stateTransitionGraphics.drawImage(this.stateBackgroundBuffer, 0, 0, null);
        this.stateTransitionGraphics.setTransform(tx);
        this.domain.renderStateTransition(this.stateTransitionGraphics, stateID, interpolation);
    }

    /**
     * The rendering thread waits here until signalRenderBegin() is called.
     */
    private synchronized void waitRenderBegin()
    {
        while (!this.signal) {
            try {
                this.wait();
            } catch (InterruptedException ignored) {
            }
        }
    }

    /**
     * The rendering thread calls this to release the EDT waiting on waitRenderFinish().
     */
    private synchronized void signalRenderFinish()
    {
        this.signal = false;
        this.notifyAll();
    }

    /**
     * The rendering loop for this DomainPanel's rendering thread.
     * The thread is started in the constructur, and runs until shutdown is signaled.
     */
    private void renderLoop()
    {
        Server.printDebug("Thread started.");

        while (true) {
            // The EDT's call to signalDomainRender() has a happens-before relationship with this thread's call below.
            this.waitRenderBegin();

            if (this.shutdown) {
                break;
            }

            // Render only as much as is necessary.
            this.isNewlyRendered = true;
            int curState = (int) this.currentStateInterpolation;
            if (this.requireFullRender) {
                this.renderDomainBackground();
                this.renderStateBackground(curState);
                this.renderStateTransition(curState, this.currentStateInterpolation - curState);
                this.requireFullRender = false;
            } else if ((int) this.lastStateInterpolation != curState) {
                this.renderStateBackground(curState);
                this.renderStateTransition(curState, this.currentStateInterpolation - curState);
            } else if (this.lastStateInterpolation != this.currentStateInterpolation) {
                this.renderStateTransition(curState, this.currentStateInterpolation - curState);
            } else {
                this.isNewlyRendered = false;
            }

            this.isNewlyRendered &= !this.stateTransitionBuffer.contentsLost();
            this.lastStateInterpolation = this.currentStateInterpolation;

            // This thread's call below has a happens-before relationship to the EDT's call to waitForFinishRender().
            this.signalRenderFinish();
        }

        Server.printDebug("Thread shut down.");
    }
}
