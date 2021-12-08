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

package dk.dtu.compute.mavis.domain;

import dk.dtu.compute.mavis.client.Timeout;
import dk.dtu.compute.mavis.domain.gridworld.hospital.HospitalDomain;

import java.awt.*;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.charset.MalformedInputException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

public interface Domain
{
    /**
     * Loads a Domain from the given file.
     * The type of domain is parsed from the initial segment of the file interpreted as ASCII text.
     * Throws if the file does not exist, if the file specifies an unsupported domain type, or if the Domain
     * constructor throws an exception during loading (e.g. invalid level specification).
     * <p>
     * Domain types should be lower-case.
     * <p>
     * Make sure the getSupportedDomains() function returns the corresponding domain types that this function supports.
     */
    static Domain loadLevel(Path levelFile)
    throws IOException, ParseException
    {
        String domainType = Domain.getDomainType(levelFile);
        Domain domain;
        //noinspection SwitchStatementWithTooFewBranches
        switch (domainType) {
            case "hospital":
                domain = new HospitalDomain(levelFile, false);
                break;
            default:
                throw new ParseException(String.format("Unsupported domain type: %s.", domainType));
        }
        return domain;
    }

    static Domain loadReplay(Path replayFile)
    throws IOException, ParseException
    {
        String domainType = Domain.getDomainType(replayFile);
        Domain domain;
        //noinspection SwitchStatementWithTooFewBranches
        switch (domainType) {
            case "hospital":
                domain = new HospitalDomain(replayFile, true);
                break;
            default:
                throw new ParseException(String.format("Unsupported domain type: %s.", domainType));
        }
        return domain;
    }

    static String[] getSupportedDomains()
    {
        return new String[]{"hospital"};
    }

    /**
     * Parses the initial segment of a domain file as ASCII and returns the domain type.
     * Throws an exception if the file is not ASCII or does not adhere to the domain file specification.
     */
    private static String getDomainType(Path domainFile)
    throws IOException, ParseException
    {
        try (var domainStream = Files.newInputStream(domainFile)) {
            byte[] buffer = domainStream.readNBytes(128);

            int lf1 = 0;
            while (lf1 < buffer.length && buffer[lf1] != '\n') {
                ++lf1;
            }
            int lf2 = lf1 + 1;
            while (lf2 < buffer.length && buffer[lf2] != '\n') {
                ++lf2;
            }

            if (lf1 == buffer.length || lf2 == buffer.length) {
                throw new ParseException(
                        "Invalid level file header. Can not parse initial segment as two lines of ASCII.");
            }

            var asciiDecoder = StandardCharsets.US_ASCII.newDecoder();
            try {
                int strlen = (lf1 > 0 && buffer[lf1 - 1] == '\r') ? lf1 - 1 : lf1;
                String domainHeader = asciiDecoder.decode(ByteBuffer.wrap(buffer, 0, strlen)).toString();
                if (!domainHeader.equals("#domain")) {
                    throw new ParseException(String.format(
                            "Invalid level file header. Expected '#domain' header, but got '%s'.",
                            domainHeader), 1);
                }

                strlen = (lf2 > 0 && buffer[lf2 - 1] == '\r') ? lf2 - lf1 - 2 : lf2 - lf1 - 1;
                return asciiDecoder.decode(ByteBuffer.wrap(buffer, lf1 + 1, strlen)).toString();
            } catch (MalformedInputException e) {
                throw new ParseException(
                        "Invalid level file header. Can not parse initial segment as two lines of ASCII.");
            }
        }
    }

    /**
     * IMPORTANT: The streams must NOT be closed by the Domain.
     */
    void runProtocol(Timeout timeout,
                     long timeoutNS,
                     BufferedInputStream clientIn,
                     BufferedOutputStream clientOut,
                     OutputStream logOut);

    /**
     * Called after domain instantiation if the domain will be used for GUI output.
     * Allows the domain to initialize graphics resources (fonts, etc.).
     */
    void initializeGraphics();

    /**
     * Called after domain instantiation if the domain will not be used for any output.
     * This allows the domain to not store the entire sequence of states, but only the latest state.
     * The following functions will not be called on the domain and should not be supported:
     * - getNumStates()
     * - renderDomainBackground(...)
     * - renderStateBackground(...)
     * - renderStateTransition(...)
     * <p>
     * NB! Calling this function after the domain is in use is an error and has undefined behaviour.
     */
    void allowDiscardingPastStates();

    /**
     * Returns the name of the specific level that this domain has loaded.
     * <p>
     * The GUI will call this function once to display the level name.
     */
    String getLevelName();

    /**
     * Returns the name of the client. Must return null until the client name is known.
     * <p>
     * The GUI will poll this every tick until it returns a non-null String which it displays as the client name.
     * <p>
     * IMPORTANT: This function is called from the Swing EDT and must be safe for concurrency with the Client thread.
     */
    String getClientName();

    /**
     * Returns an array of strings which the server will print out after a client/log terminates.
     * The strings should serve as a summary of how the run went, e.g. if level was solved, how many actions were used
     * and how much time the client spent.
     * The strings are printed in the order of the array.
     */
    String[] getStatus();

    /**
     * Return the number of states available.
     * This function will be polled by the GUI at the frequency of the GUI refresh rate.
     * <p>
     * Following concurrent calls to the rendering functions must be successful for values returned by this function.
     * <p>
     * IMPORTANT: This function is called from the Swing EDT and must be safe for concurrency with the Client thread.
     */
    int getNumStates();

    /**
     * Return the time the given state was received from the client.
     * This function will be polled by the GUI at the frequency of the GUI refresh rate.
     * <p>
     * IMPORTANT: This function is called from the Swing EDT and must be safe for concurrency with the Client thread.
     */
    long getStateTime(int stateID);

    /**
     * Render an image of the static background elements of this domain.
     * The image must have the given width and height and must be opaque (i.e. draw every pixel).
     * <p>
     * The GUI calls renderStaticBackground once every time the display area is resized, and otherwise caches the
     * image and reuses that as the background for all states and state transitions.
     * <p>
     * E.g. in a grid world, this could draw all the grid cells, the walls, and the goal cells.
     * <p>
     * IMPORTANT: This function is called from the Swing EDT and must be safe for concurrency with the Client thread.
     */
    void renderDomainBackground(Graphics2D g, int width, int height);

    /**
     * Render an image on top of the domain background which contains the static elements of the state transition from
     * stateID to stateID+1.
     * The given stateID must be in the interval [0; getNumStates()-1].
     * <p>
     * The width and height of the image are the same as the last call to renderStaticBackground on this domain.
     * <p>
     * The GUI calls renderStateBackground once each time it begins rendering a new state transition or when the display
     * area is resized, and otherwise caches and reuses the image drawn for all interpolation frames of the state
     * transition.
     * <p>
     * E.g. in a grid world, this could draw all the agents and boxes that do not move in this state transition.
     * <p>
     * IMPORTANT: This function is called from the Swing EDT and must be safe for concurrency with the Client thread.
     */
    void renderStateBackground(Graphics2D g, int stateID);

    /**
     * Render an interpolation image of all the dynamic elements between stateID and stateID+1 on top of the domain
     * background and the state background.
     * The given stateID must be in the interval [0; getNumStates()-1].
     * The interpolation is a real in the range [0; 1[, where 0 means to draw the state at StateID and
     * above 0 means to draw some interpolation between the stateID and stateID+1 states.
     * If stateID == getNumStates()-1, then the interpolation must be 0, since there is no subsequent state
     * to interpolate to.
     * <p>
     * The width and height of the image are the same as the last call to renderStaticBackground on this domain.
     * <p>
     * The GUI calls renderStateTransition once for each interpolation frame (e.g. at 60 Hz). The GUI caches the result
     * if the playback is paused and recalls this only if the display area is resized.
     * <p>
     * IMPORTANT: This function is called from the Swing EDT and must be safe for concurrency with the Client thread.
     */
    void renderStateTransition(Graphics2D g, int stateID, double interpolation);
}
