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

package dk.dtu.compute.mavis.domain.gridworld.hospital;

import dk.dtu.compute.mavis.Fonts;
import dk.dtu.compute.mavis.client.Client;
import dk.dtu.compute.mavis.client.Timeout;
import dk.dtu.compute.mavis.domain.Domain;
import dk.dtu.compute.mavis.domain.ParseException;
import dk.dtu.compute.mavis.server.Server;

import java.awt.*;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.AffineTransform;
import java.awt.geom.Point2D;
import java.io.*;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;
import java.util.HashMap;
import java.util.concurrent.TimeUnit;

public final class HospitalDomain
        implements Domain
{
    private Path levelFile;
    private StateSequence stateSequence;

    private volatile String clientName = null;

    private long numActions = 0;

    /**
     * Rendering constants.
     */
    private static final Color LETTERBOX_COLOR = Color.BLACK;
    private static final Color GRID_COLOR = Color.DARK_GRAY;
    private static final Color CELL_COLOR = Color.LIGHT_GRAY;
    private static final Color WALL_COLOR = Color.BLACK;
    private static final Color BOX_AGENT_FONT_COLOR = Color.BLACK;
    private static final Color GOAL_COLOR = Colors.UnsolvedGoal;
    private static final Color GOAL_FONT_COLOR = blendColors(GOAL_COLOR, Color.BLACK, 0.7);
    private static final Color GOAL_SOLVED_COLOR = Colors.SolvedGoal;

    private static final double BOX_MARGIN_PERCENT = 0.1;
    private static final double TEXT_MARGIN_PERCENT = 0.2;

    private static final Stroke OUTLINE_STROKE = new BasicStroke(0.0f);

    @SuppressWarnings("SameParameterValue")
    private static Color blendColors(Color c1, Color c2, double ratio)
    {
        int r = (int) ((1.0 - ratio) * c1.getRed() + ratio * c2.getRed());
        int g = (int) ((1.0 - ratio) * c1.getGreen() + ratio * c2.getGreen());
        int b = (int) ((1.0 - ratio) * c1.getBlue() + ratio * c2.getBlue());
        return new Color(r, g, b);
    }

    /**
     * Rendering context.
     */
    private int originLeft, originTop;
    private int width, height;
    private int cellSize;
    private int cellBoxMargin;
    // Font information to render fitted text in cells.
    private Font baseFont;
    private final int[][] agentGlyphCodes = new int[10][1];
    private final int[][] boxGlyphCodes = new int[26][1];
    private final HashMap<FontRenderContext, Float> pointToPixelRatios = new HashMap<>();
    // Agent and box letters.
    private final GlyphVector[] agentGlyphVectors = new GlyphVector[10];
    private final GlyphVector[] boxGlyphVectors = new GlyphVector[26];
    // Agent colors.
    private final Color[] agentOutlineColor = new Color[10];
    private final Color[] agentArmColor = new Color[10];
    // For drawing arms on agents.
    private final Polygon agentArmMove = new Polygon();
    private final Polygon agentArmPushPull = new Polygon();
    private final AffineTransform agentArmTransform = new AffineTransform();
    // In an interpolation between two states, we track the static and dynamic elements when possible to draw less.
    private boolean staticElementsRendered = false;
    private int numDynamicBoxes;
    private final int[] dynamicBoxes = new int[10];
    private int numDynamicAgents;
    private final byte[] dynamicAgents = new byte[10];
    private final int[] dynamicAgentsBox = new int[10];
    // For each state we cache the box goals which are solved in that state. The state data structures are not designed
    // for that to be efficiently computable.
    private BitSet[] stateSolvedBoxGoals = new BitSet[1024];

    public HospitalDomain(Path domainFile, boolean isLogFile)
    throws IOException, ParseException
    {
        this.levelFile = domainFile;
        this.stateSequence = new StateSequence(domainFile, isLogFile);

        if (isLogFile) {
            this.clientName = this.stateSequence.clientName;
            this.numActions = this.stateSequence.getNumStates() - 1;
        }
    }

    @Override
    public void runProtocol(Timeout timeout,
                            long timeoutNS,
                            BufferedInputStream clientIn,
                            BufferedOutputStream clientOut,
                            OutputStream logOut)
    {
        Client.printDebug("Protocol begun.");

        var clientReader = new BufferedReader(new InputStreamReader(clientIn, StandardCharsets.US_ASCII.newDecoder()));
        var clientWriter = new BufferedWriter(new OutputStreamWriter(clientOut,
                                                                     StandardCharsets.US_ASCII.newEncoder()));
        var logWriter = new BufferedWriter(new OutputStreamWriter(logOut, StandardCharsets.US_ASCII.newEncoder()));

        // Read client name. 10 seconds timeout.
        timeout.reset(System.nanoTime(), TimeUnit.SECONDS.toNanos(10));
        String clientMsg;
        try {
            Client.printDebug("Waiting for client name.");
            clientMsg = clientReader.readLine();
        } catch (CharacterCodingException e) {
            Client.printError("Client message not valid ASCII.");
            return;
        } catch (IOException e) {
            Client.printError("Unexpected exception while reading client name:");
            e.printStackTrace(System.out);
            return;
        }

        // Check and reset timeout.
        long startNS = System.nanoTime();
        if (!timeout.reset(startNS, timeoutNS)) {
            Client.printError("Timed out while waiting for client name.");
            return;
        }

        // Store client name.
        if (clientMsg != null) {
            Client.printDebug("Received client name: " + clientMsg);
            this.clientName = clientMsg;
        } else {
            Client.printError("Client closed its output stream before sending its name.");
            return;
        }

        // Send level to client and log.
        Client.printDebug("Opening level file: " + this.levelFile);
        try (InputStream levelStream = Files.newInputStream(this.levelFile)) {
            Client.printDebug("Writing level to client and log.");
            try {
                byte[] buffer = new byte[4096];
                int len;
                boolean endNewline = false;
                while ((len = levelStream.readNBytes(buffer, 0, buffer.length)) != 0) {
                    clientOut.write(buffer, 0, len);
                    logOut.write(buffer, 0, len);
                    endNewline = buffer[len - 1] == '\n';
                }
                clientOut.flush();
                logOut.flush();
                if (!endNewline) {
                    clientWriter.newLine();
                    clientWriter.flush();
                    logWriter.newLine();
                    logWriter.flush();
                }
            } catch (IOException e) {
                if (timeout.isExpired()) {
                    Client.printError("Timeout expired while sending level to client.");
                } else {
                    Client.printError("Could not send level to client and/or log.");
                    Client.printError(e.getMessage());
                }
                return;
            }
        } catch (IOException e) {
            Client.printError("Could not open level file.");
            Client.printError(e.getMessage());
            return;
        }

        // Log client name.
        // Has to be logged after level file contents have been logged (first log lines must be domain).
        try {
            Client.printDebug("Logging client name.");
            logWriter.write("#clientname");
            logWriter.newLine();
            logWriter.write(this.clientName);
            logWriter.newLine();
            logWriter.flush();
        } catch (IOException e) {
            Client.printError("Could not write client name to log file.");
            Client.printError(e.getMessage());
            return;
        }

        Client.printDebug("Beginning action/comment message exchanges.");
        long numMessages = 0;
        Action[] jointAction = new Action[this.stateSequence.numAgents];

        try {
            logWriter.write("#actions");
            logWriter.newLine();
            logWriter.flush();
        } catch (IOException e) {
            Client.printError("Could not write to log file.");
            Client.printError(e.getMessage());
            return;
        }

        protocolLoop:
        while (true) {
            if (timeout.isExpired()) {
                Client.printDebug("Client timed out in protocol loop.");
                break;
            }

            // Read client message.
            try {
                clientMsg = clientReader.readLine();
            } catch (CharacterCodingException e) {
                Client.printError("Client message not valid ASCII.");
                return;
            } catch (IOException e) {
                Client.printError("Unexpected exception while reading from client.");
                Client.printError(e.getMessage());
                e.printStackTrace();
                return;
            }
            if (clientMsg == null) {
                if (timeout.isExpired()) {
                    Client.printDebug("Client stream closed after timeout.");
                } else {
                    Client.printDebug("Client closed its output stream.");
                }
                break;
            }

            if (timeout.isExpired()) {
                Client.printDebug("Client timed out in protocol loop.");
                break;
            }

            // Process message.
            ++numMessages;
            if (clientMsg.startsWith("#")) {
                Client.printMessage(clientMsg.substring(1));
            } else {
                // Parse action string.
                String[] actionMsg = clientMsg.split("\\|");
                if (actionMsg.length != this.stateSequence.numAgents) {
                    Client.printError("Invalid number of agents in joint action:");
                    Client.printError(clientMsg);
                    continue;
                }
                for (int i = 0; i < jointAction.length; ++i) {
                    jointAction[i] = Action.parse(actionMsg[i]);
                    if (jointAction[i] == null) {
                        Client.printError("Invalid joint action:");
                        Client.printError(clientMsg);
                        continue protocolLoop;
                    }
                }

                // Execute action.
                long actionTime = System.nanoTime() - startNS;
                boolean[] result = this.stateSequence.execute(jointAction, actionTime);
                ++this.numActions;

                // Write response.
                try {
                    clientWriter.write(result[0] ? "true" : "false");
                    for (int i = 1; i < result.length; ++i) {
                        clientWriter.write("|");
                        clientWriter.write(result[i] ? "true" : "false");
                    }
                    clientWriter.newLine();
                    clientWriter.flush();
                } catch (IOException e) {
                    // Happens when client closes before reading responses, then server can't write to the client's
                    // input stream.
                    Client.printError("Could not write response to client.");
                    Client.printError(e.getMessage());
                    return;
                }

                // Log action.
                try {
                    logWriter.write(Long.toString(actionTime));
                    logWriter.write(":");
                    logWriter.write(clientMsg);
                    logWriter.newLine();
                    logWriter.flush();
                } catch (IOException e) {
                    Client.printError("Could not write to log file.");
                    Client.printError(e.getMessage());
                    return;
                }
            }
        }
        Client.printDebug("Messages exchanged: " + numMessages + ".");

        // Log summary.
        try {
            logWriter.write("#end");
            logWriter.newLine();

            logWriter.write("#solved");
            logWriter.newLine();
            logWriter.write(this.isGoalState(this.getNumStates() - 1) ? "true" : "false");
            logWriter.newLine();

            logWriter.write("#numactions");
            logWriter.newLine();
            logWriter.write(Long.toString(this.numActions));
            logWriter.newLine();

            logWriter.write("#time");
            logWriter.newLine();
            logWriter.write(Long.toString(this.getStateTime(this.getNumStates() - 1)));
            logWriter.newLine();

            logWriter.write("#end");
            logWriter.newLine();
            logWriter.flush();
        } catch (IOException e) {
            Client.printError("Could not write to log file.");
            Client.printError(e.getMessage());
            return;
        }

        Client.printDebug("Protocol finished.");
    }

    @Override
    public void allowDiscardingPastStates()
    {
        this.stateSequence.allowDiscardingPastStates();
    }

    @Override
    public String getLevelName()
    {
        return this.stateSequence.getLevelName();
    }

    @Override
    public String getClientName()
    {
        return this.clientName;
    }

    @Override
    public String[] getStatus()
    {
        int lastStateID = this.getNumStates() - 1;
        boolean isSolved = this.isGoalState(lastStateID);

        String[] status = new String[3];
        status[0] = String.format("Level solved: %s.", isSolved ? "Yes" : "No");
        status[1] = String.format("Actions used: %,d.", this.numActions);
        status[2] = String.format("Time to solve: %.3f seconds.", this.getStateTime(lastStateID) / 1_000_000_000d);

        return status;
    }

    private boolean isGoalState(int stateID)
    {
        boolean isSolved = true;
        State state = this.stateSequence.getState(stateID);
        var solvedBoxGoals = this.getSolvedBoxGoals(stateID);
        if (solvedBoxGoals.nextClearBit(0) != this.stateSequence.numBoxGoals) {
            isSolved = false;
        } else {
            for (byte agent = 0; agent < this.stateSequence.numAgents; ++agent) {
                boolean agentHasGoal = this.stateSequence.agentGoalRows[agent] != -1;
                boolean agentNotInGoal = this.stateSequence.agentGoalRows[agent] != state.agentRows[agent] ||
                                         this.stateSequence.agentGoalCols[agent] != state.agentCols[agent];
                if (agentHasGoal && agentNotInGoal) {
                    isSolved = false;
                    break;
                }
            }
        }
        return isSolved;
    }

    @Override
    public int getNumStates()
    {
        return this.stateSequence.getNumStates();
    }

    @Override
    public long getStateTime(int stateID)
    {
        return this.stateSequence.getStateTime(stateID);
    }

    @Override
    public void renderDomainBackground(Graphics2D g, int width, int height)
    {
        int numRows = this.stateSequence.numRows;
        int numCols = this.stateSequence.numCols;

        // Determine sizes.
        this.calculateRenderSizes(g, width, height, numRows, numCols);

        // Letterbox.
        g.setColor(LETTERBOX_COLOR);
        g.fillRect(0, 0, width, height);

        // Cell background.
        g.setColor(CELL_COLOR);
        g.fillRect(this.originLeft, this.originTop, this.width, this.height);

        // Grid and walls.
        for (short row = 0; row < numRows; ++row) {
            int top = this.originTop + row * this.cellSize;
            for (short col = 0; col < numCols; ++col) {
                int left = this.originLeft + col * this.cellSize;
                if (this.stateSequence.wallAt(row, col)) {
                    g.setColor(WALL_COLOR);
                    g.fillRect(left, top, this.cellSize, this.cellSize);
                } else {
                    g.setColor(GRID_COLOR);
                    g.drawRect(left, top, this.cellSize - 1, this.cellSize - 1);
                }
            }
        }

        // Goal cells.
        for (int boxGoal = 0; boxGoal < this.stateSequence.numBoxGoals; ++boxGoal) {
            short row = this.stateSequence.boxGoalRows[boxGoal];
            short col = this.stateSequence.boxGoalCols[boxGoal];
            byte boxGoalLetter = this.stateSequence.boxGoalLetters[boxGoal];
            this.drawBoxGoalCell(g, row, col, (char) ('A' + boxGoalLetter), false);
        }
        for (byte agent = 0; agent < this.stateSequence.numAgents; ++agent) {
            short row = this.stateSequence.agentGoalRows[agent];
            short col = this.stateSequence.agentGoalCols[agent];
            if (row != -1) {
                this.drawAgentGoalCell(g, row, col, (char) ('0' + agent), false);
            }
        }
    }

    @Override
    public void renderStateBackground(Graphics2D g, int stateID)
    {
        State currentState = this.stateSequence.getState(stateID);

        // Highlight solved goal cells.
        BitSet solvedBoxGoals = this.getSolvedBoxGoals(stateID);
        for (int boxGoal = 0; boxGoal < this.stateSequence.numBoxGoals; ++boxGoal) {
            short row = this.stateSequence.boxGoalRows[boxGoal];
            short col = this.stateSequence.boxGoalCols[boxGoal];
            byte boxGoalLetter = this.stateSequence.boxGoalLetters[boxGoal];
            boolean currentSolved = solvedBoxGoals.get(boxGoal);
            if (currentSolved) {
                this.drawBoxGoalCell(g, row, col, (char) ('A' + boxGoalLetter), true);
            }
        }
        for (byte agent = 0; agent < this.stateSequence.numAgents; ++agent) {
            short row = this.stateSequence.agentGoalRows[agent];
            short col = this.stateSequence.agentGoalCols[agent];
            boolean currentSolved = currentState.agentRows[agent] == row && currentState.agentCols[agent] == col;
            if (currentSolved) {
                this.drawAgentGoalCell(g, row, col, (char) ('0' + agent), true);
            }
        }

        // Can we determine static elements? Is the next state known already?
        if (stateID < this.stateSequence.getNumStates() - 1) {
            State nextState = this.stateSequence.getState(stateID + 1);

            this.numDynamicBoxes = 0;
            this.numDynamicAgents = 0;

            // Find dynamic boxes and draw static ones.
            for (int box = 0; box < this.stateSequence.numBoxes; ++box) {
                boolean isBoxStatic = currentState.boxRows[box] == nextState.boxRows[box] &&
                                      currentState.boxCols[box] == nextState.boxCols[box];
                if (isBoxStatic) {
                    byte letter = this.stateSequence.boxLetters[box];
                    int top = this.originTop + currentState.boxRows[box] * this.cellSize;
                    int left = this.originLeft + currentState.boxCols[box] * this.cellSize;
                    this.drawBox(g, top, left, (char) ('A' + letter), this.stateSequence.boxColors[letter]);
                } else {
                    this.dynamicBoxes[this.numDynamicBoxes] = box;
                    ++this.numDynamicBoxes;
                }
            }

            // Find dynamic agents and draw static ones.
            for (byte agent = 0; agent < this.stateSequence.numAgents; ++agent) {
                boolean isAgentStatic = currentState.agentRows[agent] == nextState.agentRows[agent] &&
                                        currentState.agentCols[agent] == nextState.agentCols[agent];
                if (isAgentStatic) {
                    int top = this.originTop + currentState.agentRows[agent] * this.cellSize;
                    int left = this.originLeft + currentState.agentCols[agent] * this.cellSize;
                    this.drawAgent(g, top, left, (char) ('0' + agent), agent);
                } else {
                    this.dynamicAgents[this.numDynamicAgents] = agent;

                    // Determine if the agent is moving a box.
                    this.dynamicAgentsBox[this.numDynamicAgents] = -1; // Assume not.
                    for (int dynamicBox = 0; dynamicBox < this.numDynamicBoxes; ++dynamicBox) {
                        int box = this.dynamicBoxes[dynamicBox];
                        boolean isAgentPushingBox = nextState.agentRows[agent] == currentState.boxRows[box] &&
                                                    nextState.agentCols[agent] == currentState.boxCols[box];
                        boolean isAgentPullingBox = currentState.agentRows[agent] == nextState.boxRows[box] &&
                                                    currentState.agentCols[agent] == nextState.boxCols[box];
                        if (isAgentPushingBox || isAgentPullingBox) {
                            this.dynamicAgentsBox[this.numDynamicAgents] = box;
                            break;
                        }
                    }

                    ++this.numDynamicAgents;
                }
            }

            this.staticElementsRendered = true;
        } else {
            // We can't draw anything, because we don't know which elements will have to move in the state transition.
            this.staticElementsRendered = false;
            this.numDynamicBoxes = -1;
            this.numDynamicAgents = -1;
        }
    }

    @Override
    public void renderStateTransition(Graphics2D g, int stateID, double interpolation)
    {
        if (interpolation < 0.0 || interpolation >= 1.0) {
            Server.printError("Bad interpolation: " + interpolation);
            return;
        }

        State currentState = this.stateSequence.getState(stateID);
        State nextState = interpolation == 0.0 ? currentState : this.stateSequence.getState(stateID + 1);
        BitSet currentSolvedBoxGoals = this.getSolvedBoxGoals(stateID);
        BitSet nextSolvedBoxGoals = interpolation == 0.0 ? currentSolvedBoxGoals : this.getSolvedBoxGoals(stateID + 1);

        // Un-highlight goal cells that were solved, but are unsolved in this transition.
        for (int boxGoal = 0; boxGoal < this.stateSequence.numBoxGoals; ++boxGoal) {
            short row = this.stateSequence.boxGoalRows[boxGoal];
            short col = this.stateSequence.boxGoalCols[boxGoal];
            byte boxGoalLetter = this.stateSequence.boxGoalLetters[boxGoal];
            boolean currentSolved = currentSolvedBoxGoals.get(boxGoal);
            boolean nextSolved = nextSolvedBoxGoals.get(boxGoal);
            if (currentSolved && !nextSolved) {
                this.drawBoxGoalCell(g, row, col, (char) ('A' + boxGoalLetter), false);
            }
        }
        for (byte agent = 0; agent < this.stateSequence.numAgents; ++agent) {
            short row = this.stateSequence.agentGoalRows[agent];
            short col = this.stateSequence.agentGoalCols[agent];
            boolean currentSolved = currentState.agentRows[agent] == row && currentState.agentCols[agent] == col;
            boolean nextSolved = nextState.agentRows[agent] == row && nextState.agentCols[agent] == col;
            if (currentSolved && !nextSolved) {
                this.drawAgentGoalCell(g, row, col, (char) ('0' + agent), false);
            }
        }

        // Have we determined the dynamic entities in the transition? Otherwise do it now.
        if (interpolation != 0.0 && this.numDynamicAgents == -1) {
            this.numDynamicBoxes = 0;
            this.numDynamicAgents = 0;

            // Find dynamic boxes.
            for (int box = 0; box < this.stateSequence.numBoxes; ++box) {
                boolean isBoxDynamic = currentState.boxRows[box] != nextState.boxRows[box] ||
                                       currentState.boxCols[box] != nextState.boxCols[box];
                if (isBoxDynamic) {
                    this.dynamicBoxes[this.numDynamicBoxes] = box;
                    ++this.numDynamicBoxes;
                }
            }

            // Find dynamic agents.
            for (byte agent = 0; agent < this.stateSequence.numAgents; ++agent) {
                boolean isAgentDynamic = currentState.agentRows[agent] != nextState.agentRows[agent] ||
                                         currentState.agentCols[agent] != nextState.agentCols[agent];
                if (isAgentDynamic) {
                    this.dynamicAgents[this.numDynamicAgents] = agent;

                    // Determine if the agent is moving a box.
                    this.dynamicAgentsBox[this.numDynamicAgents] = -1; // Assume not.
                    for (int dynamicBox = 0; dynamicBox < this.numDynamicBoxes; ++dynamicBox) {
                        int box = this.dynamicBoxes[dynamicBox];
                        boolean isAgentPushingBox = nextState.agentRows[agent] == currentState.boxRows[box] &&
                                                    nextState.agentCols[agent] == currentState.boxCols[box];
                        boolean isAgentPullingBox = currentState.agentRows[agent] == nextState.boxRows[box] &&
                                                    currentState.agentCols[agent] == nextState.boxCols[box];
                        if (isAgentPushingBox || isAgentPullingBox) {
                            this.dynamicAgentsBox[this.numDynamicAgents] = box;
                            break;
                        }
                    }

                    ++this.numDynamicAgents;
                }
            }
        }

        // Draw arms on agents (under agent and box, so we can rely on overlap to form desired shapes).
        if (interpolation != 0.0) {
            for (byte dynamicAgent = 0; dynamicAgent < this.numDynamicAgents; ++dynamicAgent) {
                byte agent = this.dynamicAgents[dynamicAgent];
                int box = this.dynamicAgentsBox[dynamicAgent];
                if (box != -1) {
                    // Push/Pull.
                    // Agent position.
                    int cTop = this.originTop + currentState.agentRows[agent] * this.cellSize;
                    int cLeft = this.originLeft + currentState.agentCols[agent] * this.cellSize;
                    int nTop = this.originTop + nextState.agentRows[agent] * this.cellSize;
                    int nLeft = this.originLeft + nextState.agentCols[agent] * this.cellSize;
                    int iTop = (int) (cTop + (nTop - cTop) * interpolation);
                    int iLeft = (int) (cLeft + (nLeft - cLeft) * interpolation);
                    // Box position.
                    int bcTop = this.originTop + currentState.boxRows[box] * this.cellSize;
                    int bcLeft = this.originLeft + currentState.boxCols[box] * this.cellSize;
                    int bnTop = this.originTop + nextState.boxRows[box] * this.cellSize;
                    int bnLeft = this.originLeft + nextState.boxCols[box] * this.cellSize;
                    int biTop = (int) (bcTop + (bnTop - bcTop) * interpolation);
                    int biLeft = (int) (bcLeft + (bnLeft - bcLeft) * interpolation);

                    double direction = Math.atan2(biTop - iTop, biLeft - iLeft);
                    this.drawAgentArm(g, this.agentArmPushPull, iTop, iLeft, direction, agent);
                } else {
                    // Move.
                    int cTop = this.originTop + currentState.agentRows[agent] * this.cellSize;
                    int cLeft = this.originLeft + currentState.agentCols[agent] * this.cellSize;
                    int nTop = this.originTop + nextState.agentRows[agent] * this.cellSize;
                    int nLeft = this.originLeft + nextState.agentCols[agent] * this.cellSize;
                    int iTop = (int) (cTop + (nTop - cTop) * interpolation);
                    int iLeft = (int) (cLeft + (nLeft - cLeft) * interpolation);
                    double direction = Math.atan2(nTop - cTop, nLeft - cLeft);
                    this.drawAgentArm(g, this.agentArmMove, iTop, iLeft, direction, agent);
                }
            }
        }

        if (this.staticElementsRendered) {
            // Draw dynamic boxes.
            for (int dynamicBox = 0; dynamicBox < this.numDynamicBoxes; ++dynamicBox) {
                int box = this.dynamicBoxes[dynamicBox];
                byte letter = this.stateSequence.boxLetters[box];
                int cTop = this.originTop + currentState.boxRows[box] * this.cellSize;
                int cLeft = this.originLeft + currentState.boxCols[box] * this.cellSize;
                int nTop = this.originTop + nextState.boxRows[box] * this.cellSize;
                int nLeft = this.originLeft + nextState.boxCols[box] * this.cellSize;
                int iTop = (int) (cTop + (nTop - cTop) * interpolation);
                int iLeft = (int) (cLeft + (nLeft - cLeft) * interpolation);
                this.drawBox(g, iTop, iLeft, (char) ('A' + letter), this.stateSequence.boxColors[letter]);
            }

            // Draw dynamic agents.
            for (byte dynamicAgent = 0; dynamicAgent < this.numDynamicAgents; ++dynamicAgent) {
                byte agent = this.dynamicAgents[dynamicAgent];
                int cTop = this.originTop + currentState.agentRows[agent] * this.cellSize;
                int cLeft = this.originLeft + currentState.agentCols[agent] * this.cellSize;
                int nTop = this.originTop + nextState.agentRows[agent] * this.cellSize;
                int nLeft = this.originLeft + nextState.agentCols[agent] * this.cellSize;
                int iTop = (int) (cTop + (nTop - cTop) * interpolation);
                int iLeft = (int) (cLeft + (nLeft - cLeft) * interpolation);
                this.drawAgent(g, iTop, iLeft, (char) ('0' + agent), agent);
            }
        } else {
            // Draw all boxes.
            for (int box = 0; box < this.stateSequence.numBoxes; ++box) {
                byte letter = this.stateSequence.boxLetters[box];
                int cTop = this.originTop + currentState.boxRows[box] * this.cellSize;
                int cLeft = this.originLeft + currentState.boxCols[box] * this.cellSize;
                int nTop = this.originTop + nextState.boxRows[box] * this.cellSize;
                int nLeft = this.originLeft + nextState.boxCols[box] * this.cellSize;
                int iTop = (int) (cTop + (nTop - cTop) * interpolation);
                int iLeft = (int) (cLeft + (nLeft - cLeft) * interpolation);
                this.drawBox(g, iTop, iLeft, (char) ('A' + letter), this.stateSequence.boxColors[letter]);
            }

            // Draw all agents.
            for (byte agent = 0; agent < this.stateSequence.numAgents; ++agent) {
                int cTop = this.originTop + currentState.agentRows[agent] * this.cellSize;
                int cLeft = this.originLeft + currentState.agentCols[agent] * this.cellSize;
                int nTop = this.originTop + nextState.agentRows[agent] * this.cellSize;
                int nLeft = this.originLeft + nextState.agentCols[agent] * this.cellSize;
                int iTop = (int) (cTop + (nTop - cTop) * interpolation);
                int iLeft = (int) (cLeft + (nLeft - cLeft) * interpolation);
                this.drawAgent(g, iTop, iLeft, (char) ('0' + agent), agent);
            }
        }
    }

    public void initializeGraphics()
    {
        for (byte agent = 0; agent < this.stateSequence.numAgents; ++agent) {
            this.agentArmColor[agent] = this.stateSequence.agentColors[agent].darker();
            this.agentOutlineColor[agent] = this.stateSequence.agentColors[agent].darker().darker();
        }

        baseFont = Fonts.getDejaVuSansMono();
        // We should always be able to load our font, but if something somehow fails, fall back on some default font.
        if (baseFont == null) {
            baseFont = new Font(Font.SANS_SERIF, Font.PLAIN, 1);
        }

        // The FRC doesn't have to be correct to extract glyph codes from the font.
        var frc = new FontRenderContext(null, false, false);
        var agentGlyphVector = baseFont.createGlyphVector(frc, "0123456789");
        for (int agent = 0; agent < 10; agent++) {
            agentGlyphCodes[agent][0] = agentGlyphVector.getGlyphCode(agent);
        }
        var boxGlyphVector = baseFont.createGlyphVector(frc, "ABCDEFGHIJKLMNOPQRSTUVWXYZ");
        for (int box = 0; box < 26; box++) {
            boxGlyphCodes[box][0] = boxGlyphVector.getGlyphCode(box);
        }
    }

    private void calculateRenderSizes(Graphics2D g, int width, int height, int numRows, int numCols)
    {
        this.cellSize = Math.min(width / numCols, height / numRows);

        int excessWidth = width - numCols * this.cellSize;
        int excessHeight = height - numRows * this.cellSize;
        this.originLeft = excessWidth / 2;
        this.originTop = excessHeight / 2;
        this.width = width - excessWidth;
        this.height = height - excessHeight;

        this.cellBoxMargin = (int) (this.cellSize * BOX_MARGIN_PERCENT);
        int cellTextMargin = (int) (this.cellSize * TEXT_MARGIN_PERCENT);

        // Estimate point to pixel ratios for this font render context if we haven't before.
        FontRenderContext fontRenderContext = g.getFontRenderContext();
        float pointToPixelRatio = this.pointToPixelRatios.computeIfAbsent(fontRenderContext, frc -> {
            Font font = this.baseFont.deriveFont(Font.BOLD, 100.0f);
            GlyphVector glyphVector = font.createGlyphVector(frc, this.boxGlyphCodes['W' - 'A']);
            var glyphPixelBounds = glyphVector.getGlyphPixelBounds(0, null, 0, 0);
            return font.getSize2D() / (float) Math.max(glyphPixelBounds.getHeight(), glyphPixelBounds.getWidth());
        });

        // Determine font size required to fit text in cells.
        // The estimate will be off by a few pixels sometimes, but should be fine. It's too expensive to instantiate
        // many font sizes, render text and measure sizes.
        var cellTextSize = this.cellSize - 2 * cellTextMargin;
        float estimatedPointSize = cellTextSize * pointToPixelRatio;
        Font currentFont = this.baseFont.deriveFont(Font.BOLD, estimatedPointSize);

        // Layout box and agent letters.
        for (int box = 0; box < 26; ++box) {
            this.boxGlyphVectors[box] = currentFont.createGlyphVector(fontRenderContext, this.boxGlyphCodes[box]);
            // Position glyph so it will render in center of cell if the glyph vector is drawn in the cell corner.
            var glyphBounds = this.boxGlyphVectors[box].getGlyphPixelBounds(0, null, 0, 0);
            int glyphLeft = cellTextMargin + (cellTextSize - glyphBounds.width) / 2 - glyphBounds.x;
            int glyphTop = cellTextMargin + cellTextSize - (cellTextSize - glyphBounds.height) / 2;
            this.boxGlyphVectors[box].setGlyphPosition(0, new Point2D.Float(glyphLeft, glyphTop));
        }

        for (int agent = 0; agent < 10; ++agent) {
            this.agentGlyphVectors[agent] = currentFont.createGlyphVector(fontRenderContext,
                                                                          this.agentGlyphCodes[agent]);
            // Position glyph so it will render in center of cell if the glyph vector is drawn in the cell corner.
            var glyphBounds = this.agentGlyphVectors[agent].getGlyphPixelBounds(0, null, 0, 0);
            int glyphLeft = cellTextMargin + (cellTextSize - glyphBounds.width) / 2 - glyphBounds.x;
            int glyphTop = cellTextMargin + cellTextSize - (cellTextSize - glyphBounds.height) / 2;
            this.agentGlyphVectors[agent].setGlyphPosition(0, new Point2D.Float(glyphLeft, glyphTop));
        }

        // Agent move arm shape.
        // A triangle "pointing" left and with one point on (0,0) and two other points on either side of the x-axis.
        int armLength = this.cellSize / 2 - 1;
        int armHeight = (int) (this.cellSize * 0.60);
        this.agentArmMove.reset();
        this.agentArmMove.addPoint(0, 0);
        this.agentArmMove.addPoint(armLength, -armHeight / 2);
        this.agentArmMove.addPoint(armLength, armHeight / 2);
        this.agentArmMove.addPoint(0, 0);

        // Agent push/pull arm shape.
        // A triangle "pointing" left and with one point on (0,0) and two other points on either side of the x-axis.
        armLength = (int) (this.cellSize / Math.sqrt(2.0));
        armHeight = (int) ((this.cellSize - 2 * this.cellBoxMargin) / Math.sqrt(2.0));
        this.agentArmPushPull.reset();
        this.agentArmPushPull.addPoint(0, 0);
        this.agentArmPushPull.addPoint(armLength, -armHeight / 2);
        this.agentArmPushPull.addPoint(armLength, armHeight / 2);
        this.agentArmPushPull.addPoint(0, 0);
    }

    private BitSet getSolvedBoxGoals(int stateID)
    {
        if (stateID >= this.stateSolvedBoxGoals.length) {
            int newSize = Math.max(this.stateSolvedBoxGoals.length * 2, stateID + 1);
            this.stateSolvedBoxGoals = Arrays.copyOf(this.stateSolvedBoxGoals, newSize);
        }
        BitSet solvedBoxGoals = this.stateSolvedBoxGoals[stateID];
        if (solvedBoxGoals == null) {
            State state = this.stateSequence.getState(stateID);
            solvedBoxGoals = new BitSet(this.stateSequence.numBoxGoals);

            for (int box = 0; box < this.stateSequence.numBoxes; ++box) {
                short boxRow = state.boxRows[box];
                short boxCol = state.boxCols[box];
                byte boxLetter = this.stateSequence.boxLetters[box];
                int boxGoal = this.stateSequence.findBoxGoal(boxRow, boxCol);
                if (boxGoal != -1 && boxLetter == this.stateSequence.boxGoalLetters[boxGoal]) {
                    solvedBoxGoals.set(boxGoal);
                }
            }

            this.stateSolvedBoxGoals[stateID] = solvedBoxGoals;
        }
        return solvedBoxGoals;
    }

    private void drawBoxGoalCell(Graphics2D g, short row, short col, char letter, boolean solved)
    {
        int top = this.originTop + row * this.cellSize;
        int left = this.originLeft + col * this.cellSize;
        int size = this.cellSize - 2;
        g.setColor(solved ? GOAL_SOLVED_COLOR : GOAL_COLOR);
        g.fillRect(left + 1, top + 1, size, size);

        // No need to draw text if cell is solved, since box will be drawn on top of text anyway.
        if (!solved) {
            g.setColor(GOAL_FONT_COLOR);
            g.drawGlyphVector(this.boxGlyphVectors[letter - 'A'], left, top);
        }
    }

    private void drawAgentGoalCell(Graphics2D g, short row, short col, char letter, boolean solved)
    {
        int top = this.originTop + row * this.cellSize;
        int left = this.originLeft + col * this.cellSize;
        int size = this.cellSize - 2;
        g.setColor(solved ? GOAL_SOLVED_COLOR : GOAL_COLOR);
        g.fillOval(left + 1, top + 1, size, size);

        // No need to draw text if cell is solved, since agent will be drawn on top of text anyway.
        if (!solved) {
            g.setColor(GOAL_FONT_COLOR);
            g.drawGlyphVector(this.agentGlyphVectors[letter - '0'], left, top);
        }
    }

    private void drawBox(Graphics2D g, int top, int left, char letter, Color color)
    {
        int size = this.cellSize - 2 * this.cellBoxMargin;

        // Box fill.
        g.setColor(color);
        g.fillRect(left + this.cellBoxMargin, top + this.cellBoxMargin, size, size);

        // box outline.
        g.setColor(color.darker().darker());
        Stroke stroke = g.getStroke();
        g.setStroke(OUTLINE_STROKE);
        g.drawRect(left + this.cellBoxMargin, top + this.cellBoxMargin, size, size);
        g.setStroke(stroke);

        g.setColor(BOX_AGENT_FONT_COLOR);
        g.drawGlyphVector(this.boxGlyphVectors[letter - 'A'], left, top);
    }

    private void drawAgent(Graphics2D g, int top, int left, char letter, byte agent)
    {
        int size = this.cellSize - 2 * this.cellBoxMargin;

        // Agent fill.
        g.setColor(this.stateSequence.agentColors[agent]);
        g.fillOval(left + this.cellBoxMargin, top + this.cellBoxMargin, size, size);

        // Agent outline.
        g.setColor(this.agentOutlineColor[agent]);
        Stroke stroke = g.getStroke();
        g.setStroke(OUTLINE_STROKE);
        g.drawOval(left + this.cellBoxMargin, top + this.cellBoxMargin, size, size);
        g.setStroke(stroke);

        // Agent letter.
        g.setColor(BOX_AGENT_FONT_COLOR);
        g.drawGlyphVector(this.agentGlyphVectors[letter - '0'], left, top);
    }

    private void drawAgentArm(Graphics2D g, Polygon armShape, int top, int left, double rotation, byte agent)
    {
        int armTop = top + this.cellSize / 2;
        int armLeft = left + this.cellSize / 2;
        this.setArmTransform(armTop, armLeft, rotation);
        var transform = g.getTransform();
        g.setTransform(this.agentArmTransform);

        // Arm fill.
        g.setColor(this.agentArmColor[agent]);
        g.fillPolygon(armShape);

        // Arm outline.
        g.setColor(this.agentOutlineColor[agent]);
        Stroke stroke = g.getStroke();
        g.setStroke(OUTLINE_STROKE);
        g.drawPolygon(armShape);
        g.setStroke(stroke);

        g.setTransform(transform);
    }

    private void setArmTransform(int top, int left, double rotation)
    {
        double cos = Math.cos(rotation);
        double sin = Math.sin(rotation);
        this.agentArmTransform.setTransform(cos, sin, -sin, cos, left, top);
    }
}
