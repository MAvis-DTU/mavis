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

package dk.dtu.compute.mavis.gui;

import dk.dtu.compute.mavis.domain.Domain;
import dk.dtu.compute.mavis.server.Server;

import javax.swing.*;
import java.awt.*;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.Locale;

/**
 * Handles synchronization between GUIs for a given set of Domains. If synchronization is not desired,
 * instantiate one PlaybackManager per Domain instead of giving several domains to one manager.
 * <p>
 * The Domain GUIs are displayed on the given GraphicsConfigurations, with the initial settings given to the
 * constructor of the PlaybackManager.
 * <p>
 * Any changes to one GUI on a manager, are reflected on the other GUIs as well.
 * (Except for the specific states rendered by the domains, which will (probably) be different.)
 * <p>
 * IMPORTANT: Unless otherwise specified, all functions on PlaybackManager objects MUST be called from the EDT!
 */
public class PlaybackManager
{
    /**
     * Frames and Domains in a 1-1 correspondence.
     */
    private PlaybackFrame[] frames;
    private Domain[] domains;
    private boolean[] hasClientName;

    /**
     * Signals the state of asynchronous initialization and starting of the GUI.
     * ready is set to true and signaled by the EDT after it has run the constructor function.
     * running is set to true and signaled by the EDT after it has shown the GUI as a result of a call to startGUI().
     * running is set to false and signaled by the EDT after the user closes the GUI.
     * The Main thread can wait for the GUI to close in waitShutdown().
     */
    private volatile boolean ready = false;
    private volatile boolean running = true;

    /**
     * Caching number of states from each domain, for various functions.
     * A 1-1 correspondence with this.domains, containing the latest results from queries to domain.getNumStates().
     */
    private double[] numStates;
    private double maxNumStates;

    /**
     * The timer which triggers tick() at regular intervals for whatever FPS we want.
     * The actual FPS is determined in the constructor, based on the lowest refresh rate among the
     * display devices that we display to.
     * <p>
     * The EDT signals rendering during a tick() and then awaits all rendering threads to finish,
     * before it paints the results to the screen if necessary.
     * <p>
     * The Toolkit is cached for synchronizing buffers to underlying display every tick.
     */
    private Timer tickTimer;
    private final Toolkit toolkit;

    /**
     * Synchronized settings among the GUIs.
     */
    private boolean isPlaying;
    private boolean isFullscreen;
    private boolean hideInterface;
    private long nsPerAction;

    /**
     * Synchronized settings among the GUIs.
     * <p>
     * The last time we started playing (in ns), and the state interpolation last rendered at that time.
     * These are used as offsets to calculate the current state interpolation to render if we are playing.
     */
    private long lastStartTimeNs;
    private double lastStartStateInterpolation;

    /**
     * Synchronized settings among the GUIs.
     * <p>
     * The current state interpolation that we want to render next tick.
     * If this and the last rendered state interpolation are the same for a frame,
     * then we don't have to render unless DomainPanel buffers are invalidated.
     * The DomainPanels themselves take care of this, but the value must be capped to numStates for the DomainPanel
     * before requesting a render.
     */
    private double currentStateInterpolation;

    /**
     * Must not be called from the EDT.
     */
    public static void initializeGUI()
    {
        try {
            SwingUtilities.invokeAndWait(PlaybackManager::initializeSystemLookAndFeel);
        } catch (InterruptedException | InvocationTargetException ignored) {
            Server.printWarning("Could not set system look and feel.");
        }
    }

    /**
     * Attempts to set the system look and feel.
     * Writes a warning to Server.printWarning if it fails.
     */
    private static void initializeSystemLookAndFeel()
    {
        try {
            UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
        } catch (ClassNotFoundException | UnsupportedLookAndFeelException | IllegalAccessException | InstantiationException e) {
            Server.printWarning("Could not set system look and feel.");
            Server.printWarning(e.getMessage());
        }
    }

    /**
     * Assumes a 1-1 correspondence between the given Domains and GraphicsConfigurations.
     * I.e. domains.length == gcs.length, and gcs[i] is the graphics configuration for domains[i].
     * <p>
     * Does not have to be called from the EDT.
     */
    public PlaybackManager(Domain[] domains, GraphicsConfiguration[] gcs)
    {
        this.frames = new PlaybackFrame[domains.length];
        this.domains = Arrays.copyOf(domains, domains.length);
        this.hasClientName = new boolean[domains.length];

        this.numStates = new double[domains.length];
        this.maxNumStates = 0;

        // Default values.
        this.isFullscreen = false;
        this.hideInterface = false;
        this.isPlaying = true;
        this.nsPerAction = -1;

        this.lastStartTimeNs = 0;
        this.lastStartStateInterpolation = 0.0d;
        this.currentStateInterpolation = 0.0d;

        this.toolkit = Toolkit.getDefaultToolkit();

        int tickRate = PlaybackManager.getMinimumSupportedRefreshRate(gcs);
        Server.printDebug("GUI tick rate: " + tickRate + " Hz.");
        // Note that the actual tick rate may/will be a bit higher due to limited resolution of the tick timer.

        SwingUtilities.invokeLater(() -> {
            for (int i = 0; i < this.domains.length; ++i) {
                this.frames[i] = new PlaybackFrame(this, this.domains[i], gcs[i]);
            }
            this.updateNumStates();
            this.tickTimer = new Timer(1000 / tickRate, e -> this.tick());

            synchronized (this) {
                this.ready = true;
                this.notifyAll();
            }
        });
    }

    /**
     * Waits for the EDT to initialize the GUI, then shows it with the given parameters.
     * This function should only be called once.
     * The function returns after the GUI is running.
     */
    public void startGUI(boolean fullscreen, boolean hideInterface, int msPerAction, boolean playing)
    {
        synchronized (this) {
            while (!this.ready) {
                try {
                    this.wait();
                } catch (InterruptedException ignored) {
                }
            }
        }
        SwingUtilities.invokeLater(() -> {
            if (hideInterface) {
                this.toggleInterface();
            }
            this.setSpeed(msPerAction);
            if (!playing) {
                this.togglePlayPause();
            }
            for (var frame : this.frames) {
                frame.startRenderingThread();
                if (fullscreen) {
                    frame.showFullscreen();
                } else {
                    frame.showWindowed();
                }
            }
            this.tickTimer.start();

            synchronized (this) {
                this.running = true;
                this.notifyAll();
            }
        });
        synchronized (this) {
            while (!this.running) {
                try {
                    this.wait();
                } catch (InterruptedException ignored) {
                }
            }
        }
    }

    public synchronized void shutdownGUI()
    {
        for (var frame : this.frames) {
            frame.shutdownRenderingThread();
        }
        this.running = false;
        this.notifyAll();
    }

    /**
     * Waits for the GUI to shut down.
     * The function returns after the Rendering threads have joined and the PlaybackFrames have been disposed.
     * Note that Swing itself may not have exited yet, but there is no apparent way to wait for that.
     */
    public synchronized void waitShutdown()
    {
        while (this.running) {
            try {
                this.wait();
            } catch (InterruptedException ignored) {
            }
        }
    }

    /**
     * Does not have to be called from the EDT.
     */
    public void focusPlaybackFrame(int frame)
    {
        SwingUtilities.invokeLater(this.frames[frame]::takeFocus);
    }

    /**
     * Does not have to be called from the EDT.
     */
    private static int getMinimumSupportedRefreshRate(GraphicsConfiguration[] gcs)
    {
        int tickRate = Integer.MAX_VALUE;
        for (var gc : gcs) {
            int refreshRate = gc.getDevice().getDisplayMode().getRefreshRate();
            if (refreshRate != DisplayMode.REFRESH_RATE_UNKNOWN) {
                tickRate = Math.min(tickRate, refreshRate);
            }
        }
        if (tickRate == Integer.MAX_VALUE) {
            tickRate = 60;
        }
        return tickRate;
    }

    void closePlaybackFrames()
    {
        this.tickTimer.stop();
        for (var frame : this.frames) {
            frame.shutdownRenderingThread();
            frame.dispose();
        }
        synchronized (this) {
            this.running = false;
            this.notifyAll();
        }
    }

    void toggleFullscreen()
    {
        this.isFullscreen = !this.isFullscreen;
        var focusedWindow = KeyboardFocusManager.getCurrentKeyboardFocusManager().getFocusedWindow();
        for (var frame : this.frames) {
            if (this.isFullscreen) {
                frame.showFullscreen();
            } else {
                frame.showWindowed();
            }
        }
        if (focusedWindow instanceof PlaybackFrame) {
            ((PlaybackFrame) focusedWindow).takeFocus();
        }
    }

    void toggleInterface()
    {
        this.hideInterface = !this.hideInterface;
        for (var frame : this.frames) {
            if (this.hideInterface) {
                frame.hideInterface();
            } else {
                frame.showInterface();
            }
        }
    }

    void setSpeed(int msPerAction)
    {
        if (this.nsPerAction == msPerAction * 1000000L) {
            return; // Ignore the triggering from setting value in other frames.
        }
        this.nsPerAction = msPerAction * 1000000L;
        for (var frame : this.frames) {
            frame.getSpeedField().setValue(msPerAction);
        }
        this.lastStartTimeNs = System.nanoTime();
        this.lastStartStateInterpolation = this.currentStateInterpolation;
    }

    int getSpeed()
    {
        return (int) (this.nsPerAction / 1000000L);
    }

    void togglePlayPause()
    {
        if (this.isPlaying) {
            this.pause();
        } else {
            this.play();
        }
    }

    private void play()
    {
        this.isPlaying = true;
        this.updatePlayPauseIcons();
        this.lastStartTimeNs = System.nanoTime();
        this.lastStartStateInterpolation = this.currentStateInterpolation;
    }

    private void pause()
    {
        this.isPlaying = false;
        this.updatePlayPauseIcons();
    }

    private void updatePlayPauseIcons()
    {
        for (var frame : this.frames) {
            frame.updatePlayPauseButtonIcon(this.isPlaying);
        }
    }

    void stepBackward(int step)
    {
        this.seek(Math.max(Math.ceil(this.currentStateInterpolation - step), 0), true);
    }

    void stepForward(int step)
    {
        this.seek(Math.min(Math.floor(this.currentStateInterpolation + step), this.maxNumStates), true);
    }

    void skipBackward()
    {
        this.seek(0, false);
    }

    void skipForward()
    {
        this.seek(this.maxNumStates, false);
    }

    private void seek(double stateInterpolation, boolean pause)
    {
        if (pause) {
            this.pause();
        } else {
            this.lastStartTimeNs = System.nanoTime();
            this.lastStartStateInterpolation = stateInterpolation;
        }
        this.currentStateInterpolation = stateInterpolation;
    }

    private void updateNumStates()
    {
        for (int i = 0; i < this.domains.length; ++i) {
            this.numStates[i] = (double) this.domains[i].getNumStates() - 1;
            this.maxNumStates = Math.max(this.maxNumStates, numStates[i]);
        }
    }

    /**
     * Triggers at each frame.
     * <p>
     * Render each DomainPanel if either of:
     * 1. The DomainPanel has been resized.
     * 2. The associated Domain has new states and we are rendering past the end.
     * 3. We changed the current target state interpolation.
     */
    private void tick()
    {
        long start = System.nanoTime();

        // Poll the domains for their client names as necessary.
        for (int i = 0; i < this.frames.length; ++i) {
            if (!this.hasClientName[i]) {
                String clientName = this.domains[i].getClientName();
                if (clientName != null) {
                    this.frames[i].setClientName(clientName);
                    this.hasClientName[i] = true;
                }
            }
        }

        // If user interacted with seek bar, pause and seek to appropriate state.
        for (var frame : this.frames) {
            if (frame.getSeekBar().hasUserChangedValue()) {
                this.seek(frame.getSeekBar().getValue(), true);
            }
        }

        // Update the number of states available from each domain.
        this.updateNumStates();

        // If we are playing, advance current target state interpolation.
        if (this.isPlaying) {
            if (this.nsPerAction == 0) {
                this.currentStateInterpolation = this.maxNumStates;
            } else {
                long timeSinceLastStartNs = System.nanoTime() - this.lastStartTimeNs;
                this.currentStateInterpolation = this.lastStartStateInterpolation +
                                                 (double) timeSinceLastStartNs / this.nsPerAction;
                // Cap to the maximum number of states available amongst all domains.
                // Do not advance target state interpolation again until some domain exceeds the current maximum.
                if (this.currentStateInterpolation > this.maxNumStates) {
                    this.currentStateInterpolation = this.maxNumStates;
                    this.lastStartTimeNs = System.nanoTime();
                    this.lastStartStateInterpolation = this.currentStateInterpolation;
                }
            }
        }

        // Update seek bar values.
        for (int i = 0; i < this.frames.length; ++i) {
            this.frames[i].getSeekBar().setMaxValue(this.numStates[i]);
            this.frames[i].getSeekBar().setValue(this.currentStateInterpolation);
        }

        // Update state labels.
        // Begin rendering, wait to finish, then write buffers to UI if they were updated.
        for (int i = 0; i < this.frames.length; ++i) {
            // Cap to the maximum number of states for this particular domain.
            double cappedStateInterpolation = Math.min(this.currentStateInterpolation, this.numStates[i]);
            String shownState = this.hideInterface ?
                                String.format(Locale.ROOT, "%.3f", cappedStateInterpolation) :
                                String.format(Locale.ROOT,
                                              "%.3f of %d",
                                              cappedStateInterpolation,
                                              (int) this.numStates[i]);
            this.frames[i].setShownState(shownState);
            String shownStateTime = this.hideInterface ?
                                    "" :
                                    String.format(Locale.ROOT,
                                                  "%.3f s",
                                                  this.domains[i].getStateTime((int) cappedStateInterpolation) /
                                                  1_000_000_000.0);
            this.frames[i].setShownStateTime(shownStateTime);
            this.frames[i].signalDomainRender(cappedStateInterpolation);
        }
        for (var frame : this.frames) {
            frame.waitDomainRender();
        }
        for (var frame : this.frames) {
            frame.repaintDomainIfNewlyRendered();
        }
        this.toolkit.sync();

        long elapsed = System.nanoTime() - start;
        if (Server.DEBUG_FRAMETIME && elapsed / 1_000_000L > this.tickTimer.getDelay()) {
            Server.printDebug(String.format(Locale.ROOT,
                                            "Tick time (%d ms) exceeded frame time (%d ms).",
                                            elapsed / 1_000_000L,
                                            this.tickTimer.getDelay()));
        }
    }
}
