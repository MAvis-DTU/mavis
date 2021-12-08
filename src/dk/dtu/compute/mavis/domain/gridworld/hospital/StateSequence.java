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

import dk.dtu.compute.mavis.domain.ParseException;
import dk.dtu.compute.mavis.server.Server;

import java.awt.*;
import java.io.IOException;
import java.io.LineNumberReader;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.BitSet;

/**
 * A sequence of states, generated from a level and client, or a log file.
 * Stores static elements of the level common to all states.
 * <p>
 * When loading a level, perform sanity checks:
 * 1. Maximum size of level's bounding rectangle must fit in a 16-bit signed integer for each dimension.
 * I.e. maximum size is 32,767 x 32,767 cells, everything inclusive.
 * - Checked during parsing.
 * 2. All relevant areas should be enclosed by walls.
 * - Checked in this.checkObjectsEnclosedInWalls().
 * 3. Each enclosed area must have enough boxes for all goals and at least one agent to manipulate those boxes.
 * - Checked in this.checkObjectsEnclosedInWalls().
 * 4. Box goals that no agent can reach or which boxes' no reaching agents can manipulate,
 * must be solved in the initial state.
 * - Checked in this.checkObjectsEnclosedInWalls().
 * <p>
 * StateSequence objects are thread-safe to write only from a single thread (the protocol thread), but can be read
 * from any number threads (e.g. the GUI threads). The state is up-to-date with calls to getNumStates().
 */
class StateSequence
{
    /**
     * Bookkeeping for writing/reading logs.
     */
    private String levelName = null;
    String clientName = null;

    /**
     * Size of bounding rectangle around level.
     */
    short numRows = 0;
    short numCols = 0;

    /**
     * Walls.
     * Indexed by (row * numCols + col).
     * For a maximum size level (32767 x 32767 cells) this takes ~128 MiB memory.
     */
    private BitSet walls = null;

    /**
     * Boxes.
     * The letters are indexed by box id (0 .. numBoxes-1).
     * The colors are indexed by the box letter (A..Z = 0..25).
     * The locations are stored in the individual states.
     */
    int numBoxes = 0;
    byte[] boxLetters;
    Color[] boxColors = new Color[26];

    /**
     * Agents.
     * The colors are indexed by agent id (0 .. 9).
     * The locations are stored in the individual states.
     */
    byte numAgents = 0;
    Color[] agentColors = new Color[10];

    /**
     * Agent goal cells.
     * Indexed by agent id (0 .. numAgents-1).
     * Values are -1 if agent has no goal cell.
     */
    short[] agentGoalRows = null;
    short[] agentGoalCols = null;

    /**
     * Box goal cells.
     * Indexed by box goal id (0 .. numBoxGoals-1).
     * <p>
     * This collection happens to be lexicographically sorted by (row, col) due to the parsing order.
     * We can therefore do binary search if we want to check if a specific (row, col) coordinate is a box goal cell.
     */
    int numBoxGoals = 0;
    short[] boxGoalRows = null;
    short[] boxGoalCols = null;
    byte[] boxGoalLetters = null;

    /**
     * Sequence of states.
     * states[0] is the initial state.
     * <p>
     * If allowDiscardingPastStates is true, then we will replace states[0] every action, in which case it is not the
     * initial state, but the latest state (and all past states are discarded).
     * <p>
     * IMPORTANT! Note that numStates is used for memory visibility to GUI threads.
     * Take care with order of reading/writing these variables to ensure correct visibility.
     * Only the protocol thread may write these variables (after construction), while any thread may read them.
     * Reading threads is only guaranteed to get an up-to-date snapshot of the variables as of that thread's
     * last read of numStates through .getNumStates().
     */
    private State[] states = new State[64];
    private long[] stateTimes = new long[64];
    private volatile int numStates;

    /**
     * Ids of boxes as they would be in the latest state if the boxes were sorted by (row, col).
     * I.e. sortedBoxIds[i] < sortedBoxIds[i+1] with < defined by lexicographical ordering of (row, col).
     * The value at sortedBoxIds[i] is the box id of the box at that position in the ordering in the latest state.
     * E.g. the sortedBoxIds[11] is the box id of the 12th box in the sorted order, and will index into
     * this.states[this.numStates-1].boxRows/boxCols.
     * Note that during this.apply, this array does NOT index into this.states[this.numStates-1].boxRows/boxCols yet,
     * since the new state is not added to the this.states array yet.
     */
    private int[] sortedBoxIds;

    /**
     * Set if we are allowed to discard past states (i.e. we're not using a GUI).
     */
    private boolean allowDiscardingPastStates = false;

    /**
     * Parses the given level file to construct a new state sequence.
     */
    StateSequence(Path domainFile, boolean isLogFile)
    throws IOException, ParseException
    {
        var tStart = System.nanoTime();
        try (LineNumberReader levelReader = new LineNumberReader(Files.newBufferedReader(domainFile,
                                                                                         StandardCharsets.US_ASCII))) {
            try {
                // Skip the domain type lines.
                levelReader.readLine();
                levelReader.readLine();

                String line = levelReader.readLine();
                if (line == null || !line.equals("#levelname")) {
                    throw new ParseException("Expected beginning of level name section (#levelname).",
                                             levelReader.getLineNumber());
                }
                line = this.parseNameSection(levelReader);

                if (line == null || !line.equals("#colors")) {
                    throw new ParseException("Expected beginning of color section (#colors).",
                                             levelReader.getLineNumber());
                }
                line = this.parseColorsSection(levelReader);

                if (!line.equals("#initial")) {
                    throw new ParseException("Expected beginning of initial state section (#initial).",
                                             levelReader.getLineNumber());
                }
                line = this.parseInitialSection(levelReader);

                if (!line.equals("#goal")) {
                    throw new ParseException("Expected beginning of goal state section (#goal).",
                                             levelReader.getLineNumber());
                }
                line = this.parseGoalSection(levelReader);

                // Initial and goal states loaded; check that states are legal.
                this.checkObjectsEnclosedInWalls();

                if (!line.stripTrailing().equalsIgnoreCase("#end")) {
                    throw new ParseException("Expected end section (#end).", levelReader.getLineNumber());
                }
                line = parseEndSection(levelReader);

                // If this is a log file, then parse additional sections.
                if (isLogFile) {
                    // Parse client name.
                    if (!line.stripTrailing().equalsIgnoreCase("#clientname")) {
                        throw new ParseException("Expected client name section (#clientname).",
                                                 levelReader.getLineNumber());
                    }
                    line = parseClientNameSection(levelReader);

                    // Parse and simulate actions.
                    if (!line.stripTrailing().equalsIgnoreCase("#actions")) {
                        throw new ParseException("Expected actions section (#actions).", levelReader.getLineNumber());
                    }
                    line = parseActionsSection(levelReader);

                    if (!line.stripTrailing().equalsIgnoreCase("#end")) {
                        throw new ParseException("Expected end section (#end).", levelReader.getLineNumber());
                    }
                    line = parseEndSection(levelReader);

                    // Parse summary to check if it is consistent with simulation.
                    if (!line.stripTrailing().equalsIgnoreCase("#solved")) {
                        throw new ParseException("Expected solved section (#solved).", levelReader.getLineNumber());
                    }
                    line = parseSolvedSection(levelReader);

                    if (!line.stripTrailing().equalsIgnoreCase("#numactions")) {
                        throw new ParseException("Expected numactions section (#numactions).",
                                                 levelReader.getLineNumber());
                    }
                    line = parseNumActionsSection(levelReader);

                    if (!line.stripTrailing().equalsIgnoreCase("#time")) {
                        throw new ParseException("Expected time section (#time).", levelReader.getLineNumber());
                    }
                    line = parseTimeSection(levelReader);

                    if (!line.stripTrailing().equalsIgnoreCase("#end")) {
                        throw new ParseException("Expected end section (#end).", levelReader.getLineNumber());
                    }
                    line = parseEndSection(levelReader);
                }

                if (line != null) {
                    throw new ParseException("Expected no more content after end section.",
                                             levelReader.getLineNumber());
                }
            } catch (MalformedInputException e) {
                throw new ParseException("Level file content not valid ASCII.", levelReader.getLineNumber());
            }
        }

        var tEnd = System.nanoTime();
        Server.printDebug(String.format("Parsing time: %.3f ms.", (tEnd - tStart) / 1_000_000_000.0));

        // IMPORTANT: Done to ensure memory visibility of non-volatile variables on other threads after they've read
        //            numStates.
        this.numStates = this.numStates;
    }

    private static class LocationStack
    {
        private int size;
        private short[] rows;
        private short[] cols;

        LocationStack(int initialCapacity)
        {
            this.size = 0;
            this.rows = new short[initialCapacity];
            this.cols = new short[initialCapacity];
        }

        int size()
        {
            return this.size;
        }

        void clear()
        {
            this.size = 0;
        }

        void push(short row, short col)
        {
            if (this.size == this.rows.length) {
                this.rows = Arrays.copyOf(this.rows, this.rows.length * 2);
                this.cols = Arrays.copyOf(this.cols, this.cols.length * 2);
            }
            this.rows[this.size] = row;
            this.cols[this.size] = col;
            ++this.size;
        }

        short topRow()
        {
            return this.rows[this.size - 1];
        }

        short topCol()
        {
            return this.cols[this.size - 1];
        }

        void pop()
        {
            --this.size;
        }
    }

    /**
     * Verifies that every object (agent, agent goal, box, and box goal) are in areas enclosed by walls.
     */
    private void checkObjectsEnclosedInWalls()
    throws ParseException
    {
        State initialState = this.states[0];
        LocationStack stack = new LocationStack(1024);
        BitSet visitedCells = new BitSet(this.numRows * this.numCols);

        for (byte a = 0; a < this.numAgents; ++a) {
            short agentRow = initialState.agentRows[a];
            short agentCol = initialState.agentCols[a];
            if (!this.isContainedInWalls(agentRow, agentCol, visitedCells, stack)) {
                throw new ParseException(String.format("Agent '%s' is not in an area enclosed by walls.",
                                                       (char) (a + '0')));
            }

            short agentGoalRow = this.agentGoalRows[a];
            short agentGoalCol = this.agentGoalCols[a];
            if (agentGoalRow != -1 && !this.isContainedInWalls(agentGoalRow, agentGoalCol, visitedCells, stack)) {
                throw new ParseException(String.format("Agent '%s's goal cell is not in an area enclosed by walls.",
                                                       (char) (a + '0')));
            }
        }

        for (int b = 0; b < this.numBoxes; ++b) {
            short boxRow = initialState.boxRows[b];
            short boxCol = initialState.boxCols[b];
            if (!this.isContainedInWalls(boxRow, boxCol, visitedCells, stack)) {
                throw new ParseException(String.format("Box '%s' at (%d, %d) is not in an area enclosed by walls.",
                                                       (char) (this.boxLetters[b] + 'A'),
                                                       boxRow,
                                                       boxCol));
            }
        }

        for (int b = 0; b < this.numBoxGoals; ++b) {
            short boxGoalRow = this.boxGoalRows[b];
            short boxGoalCol = this.boxGoalCols[b];
            if (!this.isContainedInWalls(boxGoalRow, boxGoalCol, visitedCells, stack)) {
                throw new ParseException(String.format("Box goal '%s' at (%d, %d) is not in an area enclosed by walls.",
                                                       (char) (this.boxLetters[b] + 'A'),
                                                       boxGoalRow,
                                                       boxGoalCol));
            }
        }

        // Treat unreachable cells as walls so they're not drawn when rendering states.
        visitedCells.flip(0, this.numRows * this.numCols);
        this.walls.or(visitedCells);
    }

    private boolean isContainedInWalls(short startRow, short startCol, BitSet visitedCells, LocationStack stack)
    {
        // For adjusting (row, col) to neighbour coordinates by accumulation in DFS.
        final short[] deltaRow = {-1, 2, -1, 0};
        final short[] deltaCol = {0, 0, -1, 2};

        if (visitedCells.get(startRow * this.numCols + startCol)) {
            return true;
        }

        // Reset stack, push this agent's location.
        stack.clear();
        stack.push(startRow, startCol);
        visitedCells.set(startRow * this.numCols + startCol);

        while (stack.size() > 0) {
            // Pop cell.
            short row = stack.topRow();
            short col = stack.topCol();
            stack.pop();

            // If wall cell, do nothing.
            if (this.walls.get(row * this.numCols + col)) {
                continue;
            }

            // If current cell is at level boundary, then agent is not in an area enclosed by walls.
            if (row == 0 || row == this.numRows - 1 || col == 0 || col == this.numCols - 1) {
                return false;
            }

            // Add unvisited neighbour cells to stack.
            for (int i = 0; i < 4; ++i) {
                row += deltaRow[i];
                col += deltaCol[i];
                if (!visitedCells.get(row * this.numCols + col)) {
                    visitedCells.set(row * this.numCols + col);
                    stack.push(row, col);
                }
            }
        }

        return true;
    }

    private String parseNameSection(LineNumberReader levelReader)
    throws IOException, ParseException
    {
        String line = levelReader.readLine();
        if (line == null) {
            throw new ParseException("Expected a level name, but reached end of file.", levelReader.getLineNumber());
        }
        if (line.isBlank()) {
            throw new ParseException("Level name can not be blank.", levelReader.getLineNumber());
        }
        this.levelName = line;
        line = levelReader.readLine();
        return line;
    }

    private String parseColorsSection(LineNumberReader levelReader)
    throws IOException, ParseException
    {
        while (true) {
            String line = levelReader.readLine();
            if (line == null) {
                throw new ParseException("Expected more color lines or end of color section, but reached end of file.",
                                         levelReader.getLineNumber());
            }

            if (line.length() > 0 && line.charAt(0) == '#') {
                return line;
            }

            String[] split = line.split(":");
            if (split.length < 1) {
                throw new ParseException("Invalid color line syntax - missing a colon?", levelReader.getLineNumber());
            }
            if (split.length > 2) {
                throw new ParseException("Invalid color line syntax - too many colons?", levelReader.getLineNumber());
            }

            String colorName = split[0].strip().toLowerCase(java.util.Locale.ROOT);
            Color color = Colors.fromString(colorName);
            if (color == null) {
                throw new ParseException(String.format("Invalid color name: '%s'.", colorName),
                                         levelReader.getLineNumber());
            }

            String[] symbols = split[1].split(",");
            for (String symbol : symbols) {
                symbol = symbol.strip();
                if (symbol.isEmpty()) {
                    throw new ParseException("Missing agent or box specifier between commas.",
                                             levelReader.getLineNumber());
                }
                if (symbol.length() > 1) {
                    throw new ParseException(String.format("Invalid agent or box symbol: '%s'.", symbol),
                                             levelReader.getLineNumber());
                }
                char s = symbol.charAt(0);
                if ('0' <= s && s <= '9') {
                    if (this.agentColors[s - '0'] != null) {
                        throw new ParseException(String.format("Agent '%s' already has a color specified.", s),
                                                 levelReader.getLineNumber());
                    }
                    this.agentColors[s - '0'] = color;
                } else if ('A' <= s && s <= 'Z') {
                    if (this.boxColors[s - 'A'] != null) {
                        throw new ParseException(String.format("Box '%s' already has a color specified.", s),
                                                 levelReader.getLineNumber());
                    }
                    this.boxColors[s - 'A'] = color;
                } else {
                    throw new ParseException(String.format("Invalid agent or box symbol: '%s'.", s),
                                             levelReader.getLineNumber());
                }
            }
        }
    }

    private String parseInitialSection(LineNumberReader levelReader)
    throws IOException, ParseException
    {
        int numWalls = 0;
        short[] wallRows = new short[1024];
        short[] wallCols = new short[1024];

        int numBoxes = 0;
        short[] boxRows = new short[128];
        short[] boxCols = new short[128];
        byte[] boxLetters = new byte[128];

        short[] agentRows = {-1, -1, -1, -1, -1, -1, -1, -1, -1, -1};
        short[] agentCols = {-1, -1, -1, -1, -1, -1, -1, -1, -1, -1};

        // Parse level and accumulate walls, agents, and boxes.
        String line;
        while (true) {
            line = levelReader.readLine();
            if (line == null) {
                throw new ParseException(
                        "Expected more initial state lines or end of initial state section, but reached end of file.",
                        levelReader.getLineNumber());
            }

            if (line.length() > 0 && line.charAt(0) == '#') {
                break;
            }

            line = this.stripTrailingSpaces(line);

            if (line.length() > Short.MAX_VALUE) {
                throw new ParseException(String.format("Initial state too large. Width greater than %s.",
                                                       Short.MAX_VALUE), levelReader.getLineNumber());
            }

            if (line.length() >= this.numCols) {
                this.numCols = (short) line.length();
            }

            for (short col = 0; col < line.length(); ++col) {
                char c = line.charAt(col);
                //noinspection StatementWithEmptyBody
                if (c == ' ') {
                    // Free cell.
                } else if (c == '+') {
                    // Wall.
                    if (numWalls == wallRows.length) {
                        wallRows = Arrays.copyOf(wallRows, wallRows.length * 2);
                        wallCols = Arrays.copyOf(wallCols, wallCols.length * 2);
                    }
                    wallRows[numWalls] = this.numRows;
                    wallCols[numWalls] = col;
                    ++numWalls;
                } else if ('0' <= c && c <= '9') {
                    // Agent.
                    if (agentRows[c - '0'] != -1) {
                        throw new ParseException(String.format("Agent '%s' appears multiple times in initial state.",
                                                               c), levelReader.getLineNumber());
                    }
                    if (this.agentColors[c - '0'] == null) {
                        throw new ParseException(String.format("Agent '%s' has no color specified.", c),
                                                 levelReader.getLineNumber());
                    }
                    agentRows[c - '0'] = this.numRows;
                    agentCols[c - '0'] = col;
                } else if ('A' <= c && c <= 'Z') {
                    // Box.
                    if (this.boxColors[c - 'A'] == null) {
                        throw new ParseException(String.format("Box '%s' has no color specified.", c),
                                                 levelReader.getLineNumber());
                    }
                    if (numBoxes == boxRows.length) {
                        boxRows = Arrays.copyOf(boxRows, boxRows.length * 2);
                        boxCols = Arrays.copyOf(boxCols, boxCols.length * 2);
                        boxLetters = Arrays.copyOf(boxLetters, boxLetters.length * 2);
                    }
                    boxRows[numBoxes] = this.numRows;
                    boxCols[numBoxes] = col;
                    boxLetters[numBoxes] = (byte) (c - 'A');
                    ++numBoxes;
                } else {
                    throw new ParseException(String.format("Invalid character '%s' in column %s.", c, col),
                                             levelReader.getLineNumber());
                }
            }

            ++this.numRows;
            if (this.numRows < 0) {
                throw new ParseException(String.format("Initial state too large. Height greater than %s.",
                                                       Short.MAX_VALUE), levelReader.getLineNumber());
            }
        }

        // Count the agents; ensure that they are numbered consecutively.
        for (byte a = 0; a < 10; ++a) {
            if (agentRows[a] != -1) {
                if (this.numAgents == a) {
                    ++this.numAgents;
                } else {
                    throw new ParseException("Agents must be numbered consecutively.", levelReader.getLineNumber());
                }
            }
        }
        if (this.numAgents == 0) {
            throw new ParseException("Level contains no agents.", levelReader.getLineNumber());
        }

        // Set walls.
        this.walls = new BitSet(this.numRows * this.numCols);
        for (int w = 0; w < numWalls; ++w) {
            this.walls.set(wallRows[w] * this.numCols + wallCols[w]);
        }

        // Set box information.
        this.numBoxes = numBoxes;
        this.boxLetters = Arrays.copyOf(boxLetters, this.numBoxes);
        this.sortedBoxIds = new int[this.numBoxes];
        for (int i = 0; i < this.numBoxes; ++i) {
            this.sortedBoxIds[i] = i;
        }

        // Create initial state.
        State initialState = new State(Arrays.copyOf(boxRows, numBoxes),
                                       Arrays.copyOf(boxCols, numBoxes),
                                       Arrays.copyOf(agentRows, this.numAgents),
                                       Arrays.copyOf(agentCols, this.numAgents));

        this.states[0] = initialState;
        this.stateTimes[0] = 0;
        this.numStates = 1;

        return line;
    }

    private String parseGoalSection(LineNumberReader levelReader)
    throws IOException, ParseException
    {
        int numBoxGoals = 0;
        short[] boxGoalRows = new short[128];
        short[] boxGoalCols = new short[128];
        byte[] boxGoalLetters = new byte[128];

        this.agentGoalRows = new short[this.numAgents];
        Arrays.fill(this.agentGoalRows, (short) -1);
        this.agentGoalCols = new short[this.numAgents];
        Arrays.fill(this.agentGoalCols, (short) -1);

        short row = 0;

        String line;
        while (true) {
            line = levelReader.readLine();
            if (line == null) {
                throw new ParseException(
                        "Expected more goal state lines or end of goal state section, but reached end of file.",
                        levelReader.getLineNumber());
            }

            if (line.length() > 0 && line.charAt(0) == '#') {
                if (row != this.numRows) {
                    throw new ParseException(
                            "Goal state must have the same number of rows as the initial state, but has too few.",
                            levelReader.getLineNumber());
                }
                break;
            }

            if (row == this.numRows) {
                throw new ParseException(
                        "Goal state must have the same number of rows as the initial state, but has too many.",
                        levelReader.getLineNumber());
            }

            line = this.stripTrailingSpaces(line);

            if (line.length() > Short.MAX_VALUE) {
                throw new ParseException(String.format("Goal state too large. Width greater than %s.", Short.MAX_VALUE),
                                         levelReader.getLineNumber());
            }

            if (line.length() > this.numCols) {
                throw new ParseException("Goal state can not have more columns than the initial state.",
                                         levelReader.getLineNumber());
            }

            short col = 0;
            for (; col < line.length(); ++col) {
                char c = line.charAt(col);
                if (c == '+') {
                    // Wall.
                    if (!this.wallAt(row, col)) {
                        // Which doesn't match a wall in the initial state.
                        throw new ParseException(String.format(
                                "Initial state has no wall at column %d, but goal state does.",
                                col), levelReader.getLineNumber());
                    }
                } else if (this.wallAt(row, col)) // Implicitly c != '+' from first if check.
                {
                    // Missing wall compared to the initial state.
                    throw new ParseException(String.format("Goal state not matching initial state's wall on column %d.",
                                                           col), levelReader.getLineNumber());
                } else if (c == ' ') {
                    // Free cell.
                } else if ('0' <= c && c <= '9') {
                    // Agent.
                    if (c - '0' >= this.numAgents) {
                        throw new ParseException(String.format(
                                "Goal state has agent '%s' who does not appear in the initial state.",
                                c), levelReader.getLineNumber());
                    }
                    if (this.agentGoalRows[c - '0'] != -1) {
                        throw new ParseException(String.format("Agent '%s' appears multiple times in goal state.", c),
                                                 levelReader.getLineNumber());
                    }
                    this.agentGoalRows[c - '0'] = row;
                    this.agentGoalCols[c - '0'] = col;
                } else if ('A' <= c && c <= 'Z') {
                    // Box.
                    if (numBoxGoals == boxGoalRows.length) {
                        boxGoalRows = Arrays.copyOf(boxGoalRows, boxGoalRows.length * 2);
                        boxGoalCols = Arrays.copyOf(boxGoalCols, boxGoalCols.length * 2);
                        boxGoalLetters = Arrays.copyOf(boxGoalLetters, boxGoalLetters.length * 2);
                    }
                    boxGoalRows[numBoxGoals] = row;
                    boxGoalCols[numBoxGoals] = col;
                    boxGoalLetters[numBoxGoals] = (byte) (c - 'A');
                    ++numBoxGoals;
                } else {
                    throw new ParseException(String.format("Invalid character '%s' in column %s.", c, col),
                                             levelReader.getLineNumber());
                }
            }
            // If the goal state line is shorter than the level width, we must check that no walls were omitted.
            for (; col < this.numCols; ++col) {
                if (this.wallAt(row, col)) {
                    throw new ParseException(String.format("Goal state not matching initial state's wall on column %s.",
                                                           col), levelReader.getLineNumber());
                }
            }
            ++row;
        }

        this.numBoxGoals = numBoxGoals;
        this.boxGoalRows = Arrays.copyOf(boxGoalRows, numBoxGoals);
        this.boxGoalCols = Arrays.copyOf(boxGoalCols, numBoxGoals);
        this.boxGoalLetters = Arrays.copyOf(boxGoalLetters, numBoxGoals);

        return line;
    }

    private String parseClientNameSection(LineNumberReader levelReader)
    throws IOException, ParseException
    {
        String line = levelReader.readLine();
        if (line == null) {
            throw new ParseException("Expected a client name, but reached end of file.", levelReader.getLineNumber());
        }
        if (line.isBlank()) {
            throw new ParseException("Client name can not be blank.", levelReader.getLineNumber());
        }
        this.clientName = line;
        line = levelReader.readLine();
        return line;
    }

    private String parseActionsSection(LineNumberReader levelReader)
    throws IOException, ParseException
    {
        Action[] jointAction = new Action[this.numAgents];

        while (true) {
            String line = levelReader.readLine();
            if (line == null) {
                throw new ParseException(
                        "Expected more action lines or end of actions section, but reached end of file.",
                        levelReader.getLineNumber());
            }

            if (line.length() > 0 && line.charAt(0) == '#') {
                return line;
            }

            String[] split = line.split(":");
            if (split.length < 1) {
                throw new ParseException("Invalid action line syntax - timestamp missing?",
                                         levelReader.getLineNumber());
            }
            if (split.length > 2) {
                throw new ParseException("Invalid action line syntax - too many colons?", levelReader.getLineNumber());
            }

            // Parse action timestamp.
            long actionTime;
            try {
                actionTime = Long.parseLong(split[0]);
            } catch (NumberFormatException e) {
                throw new ParseException("Invalid action timestamp.", levelReader.getLineNumber());
            }

            // Parse and execute joint action.
            String[] actionsStr = split[1].split("\\|");
            if (actionsStr.length != this.numAgents) {
                throw new ParseException("Invalid number of agents in joint action.", levelReader.getLineNumber());
            }
            for (int i = 0; i < jointAction.length; ++i) {
                jointAction[i] = Action.parse(actionsStr[i]);
                if (jointAction[i] == null) {
                    throw new ParseException("Invalid joint action.", levelReader.getLineNumber());
                }
            }

            // Execute action.
            this.execute(jointAction, actionTime);
        }
    }

    private String parseSolvedSection(LineNumberReader levelReader)
    throws IOException, ParseException
    {
        String line = levelReader.readLine();
        if (line == null) {
            throw new ParseException("Expected a solved value, but reached end of file.", levelReader.getLineNumber());
        }

        if (!line.equals("true") && !line.equals("false")) {
            throw new ParseException("Invalid solved value.", levelReader.getLineNumber());
        }

        boolean logSolved = line.equals("true");
        boolean actuallySolved = true;
        for (int boxGoalId = 0; boxGoalId < this.numBoxGoals; ++boxGoalId) {
            short boxGoalRow = this.boxGoalRows[boxGoalId];
            short boxGoalCol = this.boxGoalCols[boxGoalId];
            byte boxGoalLetter = this.boxGoalLetters[boxGoalId];

            if (this.boxAt(boxGoalRow, boxGoalCol) != boxGoalLetter) {
                actuallySolved = false;
                break;
            }
        }
        State lastState = this.getState(this.numStates - 1);
        for (int agent = 0; agent < this.numAgents; ++agent) {
            short agentGoalRow = this.agentGoalRows[agent];
            short agentGoalCol = this.agentGoalCols[agent];
            short agentRow = lastState.agentRows[agent];
            short agentCol = lastState.agentCols[agent];

            if (agentGoalRow != -1 && (agentRow != agentGoalRow || agentCol != agentGoalCol)) {
                actuallySolved = false;
                break;
            }
        }

        if (logSolved && !actuallySolved) {
            throw new ParseException("Log summary claims level is solved, but the actions don't solve the level.",
                                     levelReader.getLineNumber());
        } else if (!logSolved && actuallySolved) {
            throw new ParseException("Log summary claims level is not solved, but the actions solve the level.",
                                     levelReader.getLineNumber());
        }

        line = levelReader.readLine();
        return line;
    }

    private String parseNumActionsSection(LineNumberReader levelReader)
    throws IOException, ParseException
    {
        String line = levelReader.readLine();
        if (line == null) {
            throw new ParseException("Expected a solved value, but reached end of file.", levelReader.getLineNumber());
        }

        long numActions;
        try {
            numActions = Long.parseLong(line);
        } catch (NumberFormatException e) {
            throw new ParseException("Invalid number of actions.", levelReader.getLineNumber());
        }

        if (numActions != this.numStates - 1) {
            throw new ParseException("Number of action does not conform to the number of actions in the sequence.",
                                     levelReader.getLineNumber());
        }

        line = levelReader.readLine();
        return line;
    }

    private String parseTimeSection(LineNumberReader levelReader)
    throws IOException, ParseException
    {
        String line = levelReader.readLine();
        if (line == null) {
            throw new ParseException("Expected a solved value, but reached end of file.", levelReader.getLineNumber());
        }

        long lastStateTime;
        try {
            lastStateTime = Long.parseLong(line);
        } catch (NumberFormatException e) {
            throw new ParseException("Invalid time of last action.", levelReader.getLineNumber());
        }

        if (lastStateTime != this.getStateTime(this.numStates - 1)) {
            throw new ParseException("Last state time does not conform to the timestamp of the last action.",
                                     levelReader.getLineNumber());
        }

        line = levelReader.readLine();
        return line;
    }

    private String parseEndSection(LineNumberReader levelReader)
    throws IOException, ParseException
    {
        return levelReader.readLine();
    }

    private String stripTrailingSpaces(String s)
    {
        int endIndex = s.length();
        while (endIndex > 0 && s.charAt(endIndex - 1) == ' ') {
            --endIndex;
        }
        return s.substring(0, endIndex);
    }

    void allowDiscardingPastStates()
    {
        this.allowDiscardingPastStates = true;
    }

    String getLevelName()
    {
        return this.levelName;
    }

    /**
     * Gets the number of available states.
     * NB! This is a volatile read, so any subsequent read of this.states is "up-to-date" as of this call.
     */
    int getNumStates()
    {
        return this.numStates;
    }

    /**
     * Gets the time in nanoseconds for when the given state was generated.
     */
    long getStateTime(int state)
    {
        return this.stateTimes[state];
    }

    State getState(int state)
    {
        return this.states[state];
    }

    /**
     * Returns whether there is a wall at the given (row, col).
     * Complexity: O(1).
     */
    boolean wallAt(short row, short col)
    {
        return this.walls.get(row * this.numCols + col);
    }

    /**
     * Binary searches for a box goal cell at the given (row, col) and returns the index if found, and -1 otherwise.
     * Complexity: O(log(numBoxGoals)).
     */
    int findBoxGoal(short row, short col)
    {
        int lowIdx = 0;
        int highIdx = this.numBoxGoals - 1;

        while (lowIdx <= highIdx) {
            int midIdx = lowIdx + (highIdx - lowIdx) / 2;
            short midRow = this.boxGoalRows[midIdx];
            short midCol = this.boxGoalCols[midIdx];

            if (midRow < row) {
                lowIdx = midIdx + 1;
            } else if (midRow > row) {
                highIdx = midIdx - 1;
            } else if (midCol < col) {
                lowIdx = midIdx + 1;
            } else if (midCol > col) {
                highIdx = midIdx - 1;
            } else {
                return midIdx;
            }
        }

        return -1;
    }

    /**
     * Search for box goal at the given (row, col).
     * Returns box goal letter (A..Z = 0..25) if found, and -1 otherwise.
     * Complexity: O(log(numBoxGoals)).
     */
    byte boxGoalAt(short row, short col)
    {
        int boxGoal = this.findBoxGoal(row, col);
        if (boxGoal != -1) {
            return this.boxGoalLetters[boxGoal];
        }
        return -1;
    }

    /**
     * Does a binary search over the sorted boxes in the latest state, and returns the index in the sorted order
     * where the box with the given (row, col) should be.
     * Complexity: O(log(numBoxes)).
     */
    private int findBox(State currentState, short row, short col)
    {
        int lowIdx = 0;
        int highIdx = this.numBoxes - 1;
        int midIdx = lowIdx + (highIdx - lowIdx) / 2;

        while (lowIdx <= highIdx) {
            midIdx = lowIdx + (highIdx - lowIdx) / 2;
            short midRow = currentState.boxRows[this.sortedBoxIds[midIdx]];
            short midCol = currentState.boxCols[this.sortedBoxIds[midIdx]];

            if (midRow < row) {
                lowIdx = midIdx + 1;
            } else if (midRow > row) {
                highIdx = midIdx - 1;
            } else if (midCol < col) {
                lowIdx = midIdx + 1;
            } else if (midCol > col) {
                highIdx = midIdx - 1;
            } else {
                // Found.
                break;
            }
        }

        return midIdx;
    }

    /**
     * Search for box at the given (row, col) in the latest state.
     * Returns box letter (A..Z = 0..25) if found, and -1 otherwise.
     * Complexity: O(log(numBoxes)).
     */
    byte boxAt(short row, short col)
    {
        if (this.numBoxes == 0) {
            return -1;
        }
        State currentState = this.states[this.numStates - 1];
        int sortedBoxIdx = this.findBox(currentState, row, col);
        int boxId = this.sortedBoxIds[sortedBoxIdx];
        if (currentState.boxRows[boxId] == row && currentState.boxCols[boxId] == col) {
            return this.boxLetters[boxId];
        }
        return -1;
    }

    /**
     * Moves a box in newState from the given (fromRow, fromCol) to (toRow, toCol) and maintains this.sortedBoxIds.
     */
    void moveBox(State newState, short fromRow, short fromCol, short toRow, short toCol)
    {
        int sortedBoxIdx = this.findBox(newState, fromRow, fromCol);
        int boxId = this.sortedBoxIds[sortedBoxIdx];

        short[] boxRows = newState.boxRows;
        short[] boxCols = newState.boxCols;

        // Shift sortedBoxIds until sorted order is restored.
        if (toRow > fromRow || (toRow == fromRow && toCol > fromCol)) {
            while (sortedBoxIdx + 1 < this.numBoxes &&
                   (boxRows[this.sortedBoxIds[sortedBoxIdx + 1]] < toRow ||
                    (boxRows[this.sortedBoxIds[sortedBoxIdx + 1]] == toRow &&
                     boxCols[this.sortedBoxIds[sortedBoxIdx + 1]] < toCol))) {
                this.sortedBoxIds[sortedBoxIdx] = this.sortedBoxIds[sortedBoxIdx + 1];
                ++sortedBoxIdx;
            }
        } else {
            while (0 < sortedBoxIdx &&
                   (boxRows[this.sortedBoxIds[sortedBoxIdx - 1]] > toRow ||
                    (boxRows[this.sortedBoxIds[sortedBoxIdx - 1]] == toRow &&
                     boxCols[this.sortedBoxIds[sortedBoxIdx - 1]] > toCol))) {
                this.sortedBoxIds[sortedBoxIdx] = this.sortedBoxIds[sortedBoxIdx - 1];
                --sortedBoxIdx;
            }
        }

        // Move box.
        boxRows[boxId] = toRow;
        boxCols[boxId] = toCol;
        this.sortedBoxIds[sortedBoxIdx] = boxId;
    }

    /**
     * Search for an agent at the given (row, col) in the latest state.
     * Returns agent ID (0..9) if found, and -1 otherwise.
     * Complexity: O(numAgents).
     */
    byte agentAt(short row, short col)
    {
        State currentState = this.states[this.numStates - 1];
        for (byte a = 0; a < this.numAgents; ++a) {
            if (currentState.agentRows[a] == row && currentState.agentCols[a] == col) {
                return a;
            }
        }
        return -1;
    }

    /**
     * Moves the given agent to the given (row, col).
     * Complexity: O(1).
     */
    void moveAgent(State newState, byte agent, short row, short col)
    {
        newState.agentRows[agent] = row;
        newState.agentCols[agent] = col;
    }

    /**
     * Search for an agent goal at the given (row, col).
     * Returns agent ID (0..9) if found, and -1 otherwise.
     * Complexity: O(numAgents).
     */
    byte agentGoalAt(short row, short col)
    {
        for (byte a = 0; a < this.numAgents; ++a) {
            if (this.agentGoalRows[a] == row && this.agentGoalCols[a] == col) {
                return a;
            }
        }
        return -1;
    }

    /**
     * Checks if the given cell is free (no wall, box, or agent occupies it).
     * Complexity: O(log(numBoxes) + numAgents).
     */
    boolean freeAt(short row, short col)
    {
        return !this.wallAt(row, col) && this.boxAt(row, col) == -1 && this.agentAt(row, col) == -1;
    }

    /**
     * Determines which actions are applicable and non-conflicting.
     * Returns an array with true for each action which was applicable and non-conflicting, and false otherwise.
     */
    private boolean[] isApplicable(Action[] jointAction)
    {
        State currentState = this.states[this.numStates - 1];

        boolean[] applicable = new boolean[this.numAgents];
        short[] destRows = new short[this.numAgents];
        short[] destCols = new short[this.numAgents];
        short[] boxRows = new short[this.numAgents];
        short[] boxCols = new short[this.numAgents];

        // Test applicability.
        for (byte agent = 0; agent < this.numAgents; ++agent) {
            Action action = jointAction[agent];
            short agentRow = currentState.agentRows[agent];
            short agentCol = currentState.agentCols[agent];
            boxRows[agent] = (short) (agentRow + action.boxDeltaRow);
            boxCols[agent] = (short) (agentCol + action.boxDeltaCol);
            byte boxLetter;

            // Test for applicability.
            switch (action.type) {
                case NoOp:
                    applicable[agent] = true;
                    break;

                case Move:
                    destRows[agent] = (short) (agentRow + action.moveDeltaRow);
                    destCols[agent] = (short) (agentCol + action.moveDeltaCol);
                    applicable[agent] = this.freeAt(destRows[agent], destCols[agent]);
                    break;

                case Push:
                    boxLetter = this.boxAt(boxRows[agent], boxCols[agent]);
                    destRows[agent] = (short) (boxRows[agent] + action.moveDeltaRow);
                    destCols[agent] = (short) (boxCols[agent] + action.moveDeltaCol);
                    applicable[agent] = boxLetter != -1 &&
                                        this.agentColors[agent] == this.boxColors[boxLetter] &&
                                        this.freeAt(destRows[agent], destCols[agent]);
                    break;

                case Pull:
                    boxRows[agent] = (short) (agentRow - action.boxDeltaRow);
                    boxCols[agent] = (short) (agentCol - action.boxDeltaCol);
                    boxLetter = this.boxAt(boxRows[agent], boxCols[agent]);
                    destRows[agent] = (short) (agentRow + action.moveDeltaRow);
                    destCols[agent] = (short) (agentCol + action.moveDeltaCol);
                    applicable[agent] = boxLetter != -1 &&
                                        this.agentColors[agent] == this.boxColors[boxLetter] &&
                                        this.freeAt(destRows[agent], destCols[agent]);
                    break;
            }
        }

        // Test conflicts.
        boolean[] conflicting = new boolean[this.numAgents];
        for (byte a1 = 0; a1 < this.numAgents; ++a1) {
            if (!applicable[a1] || jointAction[a1] == Action.NoOp) {
                continue;
            }
            for (byte a2 = 0; a2 < a1; ++a2) {
                if (!applicable[a2] || jointAction[a2] == Action.NoOp) {
                    continue;
                }

                // Objects moving into same cell?
                if (destRows[a1] == destRows[a2] && destCols[a1] == destCols[a2]) {
                    conflicting[a1] = true;
                    conflicting[a2] = true;
                }

                // Moving same box?
                if (boxRows[a1] == boxRows[a2] && boxCols[a1] == boxCols[a2]) {
                    conflicting[a1] = true;
                    conflicting[a2] = true;
                }
            }
        }

        for (byte agent = 0; agent < this.numAgents; ++agent) {
            applicable[agent] &= !conflicting[agent];
        }

        return applicable;
    }

    /**
     * Applies the actions in jointAction which are applicable to the latest state and returns the resulting state.
     */
    private State apply(Action[] jointAction, boolean[] applicable)
    {
        State currentState = this.states[this.numStates - 1];
        State newState = new State(currentState);

        for (byte agent = 0; agent < jointAction.length; ++agent) {
            if (!applicable[agent]) {
                // Inapplicable or conflicting action - do nothing instead.
                continue;
            }

            Action action = jointAction[agent];
            short newAgentRow;
            short newAgentCol;
            short oldBoxRow;
            short oldBoxCol;
            short newBoxRow;
            short newBoxCol;

            switch (action.type) {
                case NoOp:
                    // Do nothing.
                    break;

                case Move:
                    newAgentRow = (short) (currentState.agentRows[agent] + action.moveDeltaRow);
                    newAgentCol = (short) (currentState.agentCols[agent] + action.moveDeltaCol);
                    this.moveAgent(newState, agent, newAgentRow, newAgentCol);
                    break;

                case Push:
                    newAgentRow = (short) (currentState.agentRows[agent] + action.boxDeltaRow);
                    newAgentCol = (short) (currentState.agentCols[agent] + action.boxDeltaCol);
                    oldBoxRow = newAgentRow;
                    oldBoxCol = newAgentCol;
                    newBoxRow = (short) (oldBoxRow + action.moveDeltaRow);
                    newBoxCol = (short) (oldBoxCol + action.moveDeltaCol);
                    this.moveBox(newState, oldBoxRow, oldBoxCol, newBoxRow, newBoxCol);
                    this.moveAgent(newState, agent, newAgentRow, newAgentCol);
                    break;

                case Pull:
                    newAgentRow = (short) (currentState.agentRows[agent] + action.moveDeltaRow);
                    newAgentCol = (short) (currentState.agentCols[agent] + action.moveDeltaCol);
                    oldBoxRow = (short) (currentState.agentRows[agent] - action.boxDeltaRow);
                    oldBoxCol = (short) (currentState.agentCols[agent] - action.boxDeltaCol);
                    newBoxRow = currentState.agentRows[agent];
                    newBoxCol = currentState.agentCols[agent];
                    this.moveAgent(newState, agent, newAgentRow, newAgentCol);
                    this.moveBox(newState, oldBoxRow, oldBoxCol, newBoxRow, newBoxCol);
                    break;
            }
        }

        return newState;
    }

    /**
     * Execute a joint action.
     * Returns a boolean array with success for each agent.
     */
    boolean[] execute(Action[] jointAction, long actionTime)
    {
        // Determine applicable and non-conflicting actions.
        boolean[] applicable = this.isApplicable(jointAction);

        // Create new state with applicable and non-conflicting actions.
        State newState = this.apply(jointAction, applicable);

        // Update this.states and this.numStates. Grow as necessary.
        if (this.allowDiscardingPastStates) {
            this.states[0] = newState;
            this.stateTimes[0] = actionTime;
            // NB. This change will not be visible to other threads; if we needed that we could set numStates = 1.
        } else {
            if (this.states.length == this.numStates) {
                this.states = Arrays.copyOf(this.states, this.states.length * 2);
                this.stateTimes = Arrays.copyOf(this.stateTimes, this.stateTimes.length * 2);
            }
            this.states[this.numStates] = newState;
            this.stateTimes[this.numStates] = actionTime;

            // Non-atomic increment OK, only the protocol thread may write to numStates.
            // NB. This causes visibility of the new state to other threads.
            //noinspection NonAtomicOperationOnVolatileField
            ++this.numStates;
        }

        return applicable;
    }
}
