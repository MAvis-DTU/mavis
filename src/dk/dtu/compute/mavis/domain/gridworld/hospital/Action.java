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

class Action
{
    enum Type
    {
        NoOp,
        Move,
        Push,
        Pull
    }

    final Type type;
    final short moveDeltaRow;
    final short moveDeltaCol;
    final short boxDeltaRow;
    final short boxDeltaCol;

    private Action(Type type, int moveDeltaRow, int moveDeltaCol, int boxDeltaRow, int boxDeltaCol)
    {
        this.type = type;
        this.moveDeltaRow = (short) moveDeltaRow;
        this.moveDeltaCol = (short) moveDeltaCol;
        this.boxDeltaRow = (short) boxDeltaRow;
        this.boxDeltaCol = (short) boxDeltaCol;
    }

    static final Action NoOp = new Action(Type.NoOp, 0, 0, 0, 0);

    static final Action MoveN = new Action(Type.Move, -1, 0, 0, 0);
    static final Action MoveS = new Action(Type.Move, 1, 0, 0, 0);
    static final Action MoveE = new Action(Type.Move, 0, 1, 0, 0);
    static final Action MoveW = new Action(Type.Move, 0, -1, 0, 0);

    static final Action PushNN = new Action(Type.Push, -1, 0, -1, 0);
    static final Action PushNE = new Action(Type.Push, 0, 1, -1, 0);
    static final Action PushNW = new Action(Type.Push, 0, -1, -1, 0);
    static final Action PushSE = new Action(Type.Push, 0, 1, 1, 0);
    static final Action PushSW = new Action(Type.Push, 0, -1, 1, 0);
    static final Action PushSS = new Action(Type.Push, 1, 0, 1, 0);
    static final Action PushEN = new Action(Type.Push, -1, 0, 0, 1);
    static final Action PushES = new Action(Type.Push, 1, 0, 0, 1);
    static final Action PushEE = new Action(Type.Push, 0, 1, 0, 1);
    static final Action PushWN = new Action(Type.Push, -1, 0, 0, -1);
    static final Action PushWS = new Action(Type.Push, 1, 0, 0, -1);
    static final Action PushWW = new Action(Type.Push, 0, -1, 0, -1);

    static final Action PullNN = new Action(Type.Pull, -1, 0, -1, 0);
    static final Action PullNE = new Action(Type.Pull, -1, 0, 0, 1);
    static final Action PullNW = new Action(Type.Pull, -1, 0, 0, -1);
    static final Action PullSS = new Action(Type.Pull, 1, 0, 1, 0);
    static final Action PullSE = new Action(Type.Pull, 1, 0, 0, 1);
    static final Action PullSW = new Action(Type.Pull, 1, 0, 0, -1);
    static final Action PullEN = new Action(Type.Pull, 0, 1, -1, 0);
    static final Action PullES = new Action(Type.Pull, 0, 1, 1, 0);
    static final Action PullEE = new Action(Type.Pull, 0, 1, 0, 1);
    static final Action PullWN = new Action(Type.Pull, 0, -1, -1, 0);
    static final Action PullWS = new Action(Type.Pull, 0, -1, 1, 0);
    static final Action PullWW = new Action(Type.Pull, 0, -1, 0, -1);

    static Action parse(String action)
    {
        switch (action) {
            case "NoOp":
                return NoOp;

            case "Move(N)":
                return MoveN;
            case "Move(S)":
                return MoveS;
            case "Move(E)":
                return MoveE;
            case "Move(W)":
                return MoveW;

            case "Push(N,N)":
                return PushNN;
            case "Push(N,E)":
                return PushNE;
            case "Push(N,W)":
                return PushNW;
            case "Push(S,S)":
                return PushSS;
            case "Push(S,E)":
                return PushSE;
            case "Push(S,W)":
                return PushSW;
            case "Push(E,N)":
                return PushEN;
            case "Push(E,S)":
                return PushES;
            case "Push(E,E)":
                return PushEE;
            case "Push(W,N)":
                return PushWN;
            case "Push(W,S)":
                return PushWS;
            case "Push(W,W)":
                return PushWW;

            case "Pull(N,N)":
                return PullNN;
            case "Pull(N,E)":
                return PullNE;
            case "Pull(N,W)":
                return PullNW;
            case "Pull(S,S)":
                return PullSS;
            case "Pull(S,E)":
                return PullSE;
            case "Pull(S,W)":
                return PullSW;
            case "Pull(E,N)":
                return PullEN;
            case "Pull(E,S)":
                return PullES;
            case "Pull(E,E)":
                return PullEE;
            case "Pull(W,N)":
                return PullWN;
            case "Pull(W,S)":
                return PullWS;
            case "Pull(W,W)":
                return PullWW;

            default:
                return null;
        }
    }
}
