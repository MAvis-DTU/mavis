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
import dk.dtu.compute.mavis.gui.widgets.*;

import javax.swing.*;
import javax.swing.text.NumberFormatter;
import java.awt.*;
import java.awt.event.*;
import java.text.NumberFormat;

class PlaybackFrame
        extends JFrame
{
    private Point previousWindowLocation = null;
    private Dimension previousWindowSize = null;
    private int previousWindowExtendedState = -1;

    private JLabel levelNameLabel;
    private JLabel clientNameLabel;
    private JLabel shownStateLabel;
    private JLabel shownStateTimeLabel;

    private DomainPanel domainPanel;
    private JPanel botPanel;

    private JFormattedTextField speedField;
    private SeekBar seekBar;
    private PlayPauseButton playPauseButton;

    PlaybackFrame(PlaybackManager playbackManager, Domain domain, GraphicsConfiguration gc)
    {
        super("MAvis", gc);

        /*
            A LITTLE CUSTOMIZATION
        */
        Color backgroundColor = Color.LIGHT_GRAY;
        Color borderColor = Color.DARK_GRAY;
        Color foregroundColor = Color.BLACK;
        Dimension buttonSize = new Dimension(40, 40);

        /*
            TOP PANEL
         */
        JPanel topPanel = new JPanel();
        topPanel.setLayout(new GridLayout(1, 4));
        topPanel.setBackground(backgroundColor);
        topPanel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(0, 0, 1, 0, borderColor),
                                                              BorderFactory.createEmptyBorder(2, 5, 2, 5)));
        this.add(topPanel, BorderLayout.PAGE_START);

        this.levelNameLabel = new JLabel("Level: " + domain.getLevelName());
        final Font labelFont = this.levelNameLabel.getFont().deriveFont(Font.BOLD, 16);
        this.levelNameLabel.setFont(labelFont);
        topPanel.add(this.levelNameLabel);

        this.clientNameLabel = new JLabel("Client: ");
        this.clientNameLabel.setFont(labelFont);
        topPanel.add(this.clientNameLabel);

        this.shownStateLabel = new JLabel("State: ");
        this.shownStateLabel.setFont(labelFont);
        topPanel.add(this.shownStateLabel);

        this.shownStateTimeLabel = new JLabel("State time: ");
        this.shownStateTimeLabel.setFont(labelFont);
        topPanel.add(this.shownStateTimeLabel);

        /*
            LEVEL PANEL
         */
        this.domainPanel = new DomainPanel(domain);
        this.domainPanel.setFocusable(true);
        this.add(this.domainPanel, BorderLayout.CENTER);

        /*
            BOTTOM PANEL
        */
        this.botPanel = new JPanel();
        this.botPanel.setLayout(new GridBagLayout());
        this.botPanel.setBackground(backgroundColor);
        this.botPanel.setBorder(BorderFactory.createCompoundBorder(BorderFactory.createMatteBorder(1,
                                                                                                   0,
                                                                                                   0,
                                                                                                   0,
                                                                                                   borderColor),
                                                                   BorderFactory.createEmptyBorder(0, 5, 0, 5)));
        this.add(this.botPanel, BorderLayout.PAGE_END);

        JLabel speedLabel = new JLabel("Speed:");
        speedLabel.setFont(labelFont.deriveFont(Font.PLAIN));
        this.botPanel.add(speedLabel);

        var intFormat = NumberFormat.getIntegerInstance();
        intFormat.setGroupingUsed(false);
        NumberFormatter intFormatter = new NumberFormatter(intFormat);
        intFormatter.setMinimum(0);
        intFormatter.setAllowsInvalid(true);
        this.speedField = new JFormattedTextField(intFormatter)
        {
            @Override
            protected void invalidEdit()
            {
                // No beeping....
            }
        };
        this.speedField.setFont(speedLabel.getFont());
        this.speedField.setFocusLostBehavior(JFormattedTextField.COMMIT_OR_REVERT);
        this.speedField.setColumns(5);
        this.speedField.setValue(0);
        this.speedField.setToolTipText("ms/action");
        this.speedField.setHorizontalAlignment(JFormattedTextField.RIGHT);
        this.speedField.setMinimumSize(this.speedField.getPreferredSize());
        this.speedField.addFocusListener(new FocusAdapter()
        {
            @Override
            public void focusGained(FocusEvent e)
            {
                // This is silly, but required because the selection is otherwise immediately reset.
                PlaybackFrame.this.speedField.setText(PlaybackFrame.this.speedField.getText());
                PlaybackFrame.this.speedField.selectAll();
            }
        });
        this.speedField.addPropertyChangeListener("value", e -> playbackManager.setSpeed((int) e.getNewValue()));
        var c = new GridBagConstraints();
        c.insets = new Insets(2, 5, 2, 5);
        this.botPanel.add(this.speedField, c);

        this.seekBar = new SeekBar();
        this.seekBar.setBackground(backgroundColor);
        this.seekBar.setForeground(foregroundColor);
        this.seekBar.setFocusable(false);
        c = new GridBagConstraints();
        c.fill = GridBagConstraints.BOTH;
        c.weightx = 1.0;
        this.botPanel.add(this.seekBar, c);

        SkipBackwardButton skipBackwardButton = new SkipBackwardButton(playbackManager::skipBackward);
        skipBackwardButton.setFocusable(false);
        skipBackwardButton.setMinimumSize(buttonSize);
        skipBackwardButton.setPreferredSize(buttonSize);
        skipBackwardButton.setMaximumSize(buttonSize);
        skipBackwardButton.setBackground(backgroundColor);
        skipBackwardButton.setForeground(foregroundColor);
        this.botPanel.add(skipBackwardButton);

        StepBackwardButton stepBackwardButton = new StepBackwardButton(() -> playbackManager.stepBackward(1));
        stepBackwardButton.setFocusable(false);
        stepBackwardButton.setMinimumSize(buttonSize);
        stepBackwardButton.setPreferredSize(buttonSize);
        stepBackwardButton.setMaximumSize(buttonSize);
        stepBackwardButton.setBackground(backgroundColor);
        stepBackwardButton.setForeground(foregroundColor);
        this.botPanel.add(stepBackwardButton);

        this.playPauseButton = new PlayPauseButton(playbackManager::togglePlayPause);
        this.playPauseButton.setFocusable(false);
        this.playPauseButton.setMinimumSize(buttonSize);
        this.playPauseButton.setPreferredSize(buttonSize);
        this.playPauseButton.setMaximumSize(buttonSize);
        this.playPauseButton.setBackground(backgroundColor);
        this.playPauseButton.setForeground(foregroundColor);
        this.botPanel.add(this.playPauseButton);

        StepForwardButton stepForwardButton = new StepForwardButton(() -> playbackManager.stepForward(1));
        stepForwardButton.setFocusable(false);
        stepForwardButton.setMinimumSize(buttonSize);
        stepForwardButton.setPreferredSize(buttonSize);
        stepForwardButton.setMaximumSize(buttonSize);
        stepForwardButton.setBackground(backgroundColor);
        stepForwardButton.setForeground(foregroundColor);
        this.botPanel.add(stepForwardButton);

        SkipForwardButton skipForwardButton = new SkipForwardButton(playbackManager::skipForward);
        skipForwardButton.setFocusable(false);
        skipForwardButton.setMinimumSize(buttonSize);
        skipForwardButton.setPreferredSize(buttonSize);
        skipForwardButton.setMaximumSize(buttonSize);
        skipForwardButton.setBackground(backgroundColor);
        skipForwardButton.setForeground(foregroundColor);
        this.botPanel.add(skipForwardButton);

        /*
            SET UP HOTKEYS
         */
        int acceleratorKey = Toolkit.getDefaultToolkit().getMenuShortcutKeyMaskEx();

        // Bind inputs.
        var globalInputMap = this.getRootPane().getInputMap(JComponent.WHEN_IN_FOCUSED_WINDOW);
        globalInputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_Q, acceleratorKey), "CloseWindows");
        globalInputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_F, 0), "ToggleFullscreen");
        globalInputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_I, 0), "ToggleInterface");
        globalInputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_SPACE, 0), "TogglePlayPause");
        globalInputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, 0), "StepBackward1");
        globalInputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, 0), "StepForward1");
        globalInputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, InputEvent.SHIFT_DOWN_MASK), "StepBackward10");
        globalInputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, InputEvent.SHIFT_DOWN_MASK), "StepForward10");
        globalInputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_LEFT, acceleratorKey), "SkipBackward");
        globalInputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_RIGHT, acceleratorKey), "SkipForward");
        globalInputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_1, 0), "SetSpeed1");
        globalInputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_2, 0), "SetSpeed2");
        globalInputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_3, 0), "SetSpeed3");
        globalInputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_4, 0), "SetSpeed4");
        globalInputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_5, 0), "SetSpeed5");
        globalInputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_UP, 0), "IncreaseSpeed");
        globalInputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_DOWN, 0), "DecreaseSpeed");

        // Bind actions.
        var globalActionMap = this.getRootPane().getActionMap();
        globalActionMap.put("CloseWindows", new AbstractAction()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                playbackManager.closePlaybackFrames();
            }
        });
        globalActionMap.put("ToggleFullscreen", new AbstractAction()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                playbackManager.toggleFullscreen();
            }
        });
        globalActionMap.put("ToggleInterface", new AbstractAction()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                playbackManager.toggleInterface();
            }
        });
        globalActionMap.put("TogglePlayPause", new AbstractAction()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                playbackManager.togglePlayPause();
            }
        });
        globalActionMap.put("StepBackward1", new AbstractAction()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                playbackManager.stepBackward(1);
            }
        });
        globalActionMap.put("StepForward1", new AbstractAction()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                playbackManager.stepForward(1);
            }
        });
        globalActionMap.put("StepBackward10", new AbstractAction()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                playbackManager.stepBackward(10);
            }
        });
        globalActionMap.put("StepForward10", new AbstractAction()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                playbackManager.stepForward(10);
            }
        });
        globalActionMap.put("SkipBackward", new AbstractAction()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                playbackManager.skipBackward();
            }
        });
        globalActionMap.put("SkipForward", new AbstractAction()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                playbackManager.skipForward();
            }
        });
        globalActionMap.put("SetSpeed1", new AbstractAction()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                playbackManager.setSpeed(50);
            }
        });
        globalActionMap.put("SetSpeed2", new AbstractAction()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                playbackManager.setSpeed(100);
            }
        });
        globalActionMap.put("SetSpeed3", new AbstractAction()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                playbackManager.setSpeed(250);
            }
        });
        globalActionMap.put("SetSpeed4", new AbstractAction()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                playbackManager.setSpeed(500);
            }
        });
        globalActionMap.put("SetSpeed5", new AbstractAction()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                playbackManager.setSpeed(1000);
            }
        });
        globalActionMap.put("IncreaseSpeed", new AbstractAction()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                int speed = playbackManager.getSpeed();
                int newSpeed = Math.max((int) Math.round(speed / 1.50), 1);
                playbackManager.setSpeed(newSpeed);
            }
        });
        globalActionMap.put("DecreaseSpeed", new AbstractAction()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
                int speed = playbackManager.getSpeed();
                int newSpeed = Math.min((int) Math.round(speed * 1.50), 10000);
                playbackManager.setSpeed(newSpeed);
            }
        });

        // When speed text field has focus, ignore the global 1-5 key press events.
        var speedFieldInputMap = speedField.getInputMap(JComponent.WHEN_FOCUSED);
        speedFieldInputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_1, 0), "Ignore");
        speedFieldInputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_2, 0), "Ignore");
        speedFieldInputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_3, 0), "Ignore");
        speedFieldInputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_4, 0), "Ignore");
        speedFieldInputMap.put(KeyStroke.getKeyStroke(KeyEvent.VK_5, 0), "Ignore");
        speedField.getActionMap().put("Ignore", new AbstractAction()
        {
            @Override
            public void actionPerformed(ActionEvent e)
            {
            }
        });

        /*
            FOR CLOSING WINDOWS
         */
        this.setDefaultCloseOperation(WindowConstants.DO_NOTHING_ON_CLOSE);
        this.addWindowListener(new WindowAdapter()
        {
            @Override
            public void windowClosing(WindowEvent e)
            {
                playbackManager.closePlaybackFrames();
            }
        });

        /*
            STORE WINDOW STATE
         */
        this.addComponentListener(new ComponentAdapter()
        {
            @Override
            public void componentResized(ComponentEvent e)
            {
                PlaybackFrame.this.storeFrameSizeLocation();
            }

            @Override
            public void componentMoved(ComponentEvent e)
            {
                PlaybackFrame.this.storeFrameSizeLocation();
            }
        });

        /*
            FOCUS OFF SPEED FIELD BY CLICKING ANYWHERE ELSE
         */
        var giveFocusToDomainPanel = new MouseAdapter()
        {
            @Override
            public void mousePressed(MouseEvent e)
            {
                PlaybackFrame.this.domainPanel.requestFocusInWindow();
            }
        };
        topPanel.addMouseListener(giveFocusToDomainPanel);
        this.domainPanel.addMouseListener(giveFocusToDomainPanel);
        for (var component : this.botPanel.getComponents()) {
            if (component != this.speedField) {
                component.addMouseListener(giveFocusToDomainPanel);
            }
        }
    }

    /**
     * Shows the frame in borderless fullscreen mode.
     */
    void showFullscreen()
    {
        if (this.isDisplayable()) {
            // Store size, location, and state before disposing, so we can restore when we return to windowed mode.
            this.storeFrameSizeLocation();
            this.previousWindowExtendedState = this.getExtendedState();
            this.dispose();
        }
        this.setUndecorated(true);
        this.setResizable(false);
        this.setExtendedState(Frame.MAXIMIZED_BOTH);
        this.setVisible(true);
    }

    /**
     * Shows the frame in windowed mode.
     */
    void showWindowed()
    {
        if (this.isDisplayable()) {
            this.dispose();
        }
        this.setUndecorated(false);
        this.setResizable(true);
        if (this.previousWindowExtendedState != -1) {
            // Frame has been shown in windowed mode before, restore size, location, and state.
            this.setSize(this.previousWindowSize);
            this.setLocation(this.previousWindowLocation);
            this.setExtendedState(this.previousWindowExtendedState);
        } else {
            // First time frame is shown in windowed mode, set default size, location, and state.
            var screenBounds = this.getGraphicsConfiguration().getBounds();
            int windowWidth = (int) (screenBounds.width * 0.6666666666);
            int windowHeight = (int) (screenBounds.height * 0.6666666666);
            this.setSize(windowWidth, windowHeight);
            this.setLocationRelativeTo(this);
            this.setExtendedState(Frame.NORMAL);
        }
        this.setVisible(true);
    }

    /**
     * Called whenever frame is shown, moved, or resized.
     * Called before going borderless fullscreen mode from windowed mode.
     * This maintains the last size and location of the frame when it was in the normal state.
     */
    private void storeFrameSizeLocation()
    {
        if (this.getExtendedState() == Frame.NORMAL) {
            this.previousWindowSize = this.getSize();
            this.previousWindowLocation = this.getLocation();
        }
    }

    void showInterface()
    {
        if (!this.botPanel.isVisible()) {
            this.botPanel.setVisible(true);
        }
        this.repaint();
    }

    void hideInterface()
    {
        if (this.botPanel.isVisible()) {
            this.botPanel.setVisible(false);
        }
        this.repaint();
    }

    void takeFocus()
    {
        this.domainPanel.requestFocus();
    }

    /**
     * Repaints the buffers to the screen if the rendering thread rendered new content in its last rendering pass.
     * IMPORTANT: Must only be called by the EDT, after waiting on waitDomainRender().
     */
    public void repaintDomainIfNewlyRendered()
    {
        this.domainPanel.repaintIfNewlyRendered();
    }

    public void startRenderingThread()
    {
        this.domainPanel.startRenderingThread();
    }

    /**
     * Signals the rendering thread for the DomainPanel to shut down.
     */
    public void shutdownRenderingThread()
    {
        this.domainPanel.shutdownRenderingThread();
    }

    /**
     * Signals the rendering thread for the DomainPanel to render the given state interpolation.
     */
    public void signalDomainRender(double stateInterpolation)
    {
        this.domainPanel.signalRenderBegin(stateInterpolation);
    }

    /**
     * Wait for the DomainPanel's rendering thread to finish rendering after the last call to signalDomainRender().
     */
    public void waitDomainRender()
    {
        this.domainPanel.waitRenderFinish();
    }

    JFormattedTextField getSpeedField()
    {
        return this.speedField;
    }

    SeekBar getSeekBar()
    {
        return this.seekBar;
    }

    void updatePlayPauseButtonIcon(boolean isPlaying)
    {
        this.playPauseButton.setIsPlaying(isPlaying);
    }

    void setLevelName(String levelName)
    {
        this.levelNameLabel.setText("Level: " + levelName);
    }

    void setClientName(String clientName)
    {
        this.clientNameLabel.setText("Client: " + clientName);
    }

    void setShownState(String shownState)
    {
        this.shownStateLabel.setText("State: " + shownState);
    }

    void setShownStateTime(String time)
    {
        this.shownStateTimeLabel.setText("State time: " + time);
    }
}
