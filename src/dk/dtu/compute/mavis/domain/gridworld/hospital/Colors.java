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

import java.awt.*;

/**
 * Supported colors:
 * blue, red, cyan, purple, green, orange, pink, grey, lightblue, brown
 */
class Colors
{
    private static final Color Blue = new Color(48, 80, 255);
    private static final Color Red = new Color(255, 0, 0);
    private static final Color Cyan = new Color(0, 255, 255);
    private static final Color Purple = new Color(96, 0, 176);
    private static final Color Green = new Color(0, 255, 0); // FIXME: Green not too good with the goal color.
    private static final Color Orange = new Color(255, 128, 0);
    private static final Color Pink = new Color(240, 96, 192);
    private static final Color Grey = new Color(112, 112, 112);
    private static final Color Lightblue = new Color(112, 192, 255);
    private static final Color Brown = new Color(96, 48, 0);

    static final Color UnsolvedGoal = new Color(223, 223, 0);
    static final Color SolvedGoal = new Color(0, 160, 0);

    static Color fromString(String colorName)
    {
        switch (colorName) {
            case "blue":
                return Colors.Blue;
            case "red":
                return Colors.Red;
            case "cyan":
                return Colors.Cyan;
            case "purple":
                return Colors.Purple;
            case "green":
                return Colors.Green;
            case "orange":
                return Colors.Orange;
            case "pink":
                return Colors.Pink;
            case "grey":
                return Colors.Grey;
            case "lightblue":
                return Colors.Lightblue;
            case "brown":
                return Colors.Brown;
            default:
                return null;
        }
    }
}
