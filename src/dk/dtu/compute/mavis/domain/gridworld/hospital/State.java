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

import java.util.Arrays;

/**
 * Compact data class for storing non-static state information.
 * Every instance belongs to a StateSequence, which tracks the static state information.
 */
class State
{
    /**
     * Box locations.
     * Indexed by box id (0 .. numBoxes-1).
     */
    short[] boxRows;
    short[] boxCols;

    /**
     * Agent locations.
     * Indexed by agent id (0 .. numAgents-1).
     */
    short[] agentRows;
    short[] agentCols;

    State(short[] boxRows, short[] boxCols, short[] agentRows, short[] agentCols)
    {
        this.boxRows = boxRows;
        this.boxCols = boxCols;
        this.agentRows = agentRows;
        this.agentCols = agentCols;
    }

    State(State copy)
    {
        this.boxRows = Arrays.copyOf(copy.boxRows, copy.boxRows.length);
        this.boxCols = Arrays.copyOf(copy.boxCols, copy.boxCols.length);
        this.agentRows = Arrays.copyOf(copy.agentRows, copy.agentRows.length);
        this.agentCols = Arrays.copyOf(copy.agentCols, copy.agentCols.length);
    }
}
