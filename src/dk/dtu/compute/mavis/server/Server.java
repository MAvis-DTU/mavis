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

package dk.dtu.compute.mavis.server;

import dk.dtu.compute.mavis.client.Client;
import dk.dtu.compute.mavis.client.Timeout;
import dk.dtu.compute.mavis.domain.Domain;
import dk.dtu.compute.mavis.domain.ParseException;
import dk.dtu.compute.mavis.gui.PlaybackManager;

import java.awt.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.function.ToIntFunction;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

public class Server
{
    public static final boolean DEBUG = "true".equalsIgnoreCase(System.getenv("MAVIS_DEBUG"));
    public static final boolean DEBUG_FRAMETIME = "true".equalsIgnoreCase(System.getenv("MAVIS_DEBUG_FRAMETIME"));
    public static final boolean DEBUG_SCREENS = "true".equalsIgnoreCase(System.getenv("MAVIS_DEBUG_SCREENS"));

    public static void main(String[] args)
    {
        Thread.currentThread().setName("MainThread");
        Server.printDebug("Thread started.");

        if (DEBUG_SCREENS) {
            var ge = GraphicsEnvironment.getLocalGraphicsEnvironment();
            var toolkit = java.awt.Toolkit.getDefaultToolkit();
            {
                var defaultScreen = ge.getDefaultScreenDevice();
                printDebug("Default screen: " + defaultScreen);
            }
            for (var gd : ge.getScreenDevices()) {
                var gc = gd.getDefaultConfiguration();
                printDebug("Screen: " + gd);
                printDebug("    Display Mode: " + gd.getDisplayMode()); // In device space.
                printDebug("    Bounds: " + gc.getBounds()); // x/y in device space, w/h in user space.
                printDebug("    Transform: " + gc.getDefaultTransform()); // User -> device space.
                printDebug("    Insets: " + toolkit.getScreenInsets(gc)); // In user space.
            }
        }

        ArgumentParser arguments;
        try {
            arguments = new ArgumentParser(args);
        } catch (ArgumentException e) {
            Server.printError(e.getMessage());
            return;
        }

        switch (arguments.getPrintHelp()) {
            case SHORT:
                System.out.println(ArgumentParser.getShortHelp());
                return;
            case LONG:
                System.out.println(ArgumentParser.getLongHelp());
                return;
        }

        switch (arguments.getServerInputMode()) {
            case NONE:
                Server.printError("No client or replay files specified.");
                return;
            case CLIENT:
                switch (arguments.getClientInputMode()) {
                    case NONE:
                        Server.printError("No level path given.");
                        return;
                    case FILE:
                        Server.runClientOnSingleLevel(arguments);
                        break;
                    case DIRECTORY:
                        Server.runClientOnLevelDirectory(arguments);
                        break;
                }
                break;
            case REPLAY:
                Server.runReplays(arguments);
                break;
        }

        Server.printDebug("Thread shut down.");
    }

    private static void runClientOnSingleLevel(ArgumentParser args)
    {
        Server.printInfo(String.format("Running client on level: %s", args.getLevelPath()));

        // Load domain.
        Domain domain;
        try {
            Server.printDebug("Loading domain.");
            domain = Domain.loadLevel(args.getLevelPath());
        } catch (ParseException e) {
            Server.printError("Could not load domain, failed to parse level file.");
            Server.printError(e.getMessage());
            return;
        } catch (IOException e) {
            Server.printError("IOException while loading domain.");
            Server.printError(e.getMessage());
            return;
        }

        // Load GUI.
        PlaybackManager playbackManager = null;
        if (args.hasGUIOutput()) {
            Server.printDebug("Loading GUI.");
            PlaybackManager.initializeGUI();
            domain.initializeGraphics();
            var gcs = getGraphicsConfigurations(1, args.getScreens());
            var domains = new Domain[]{domain};
            playbackManager = new PlaybackManager(domains, gcs);
        } else {
            // The domain can discard past states if we don't need to be able to seek states in the GUI.
            domain.allowDiscardingPastStates();
        }

        // Open log file.
        OutputStream logFileStream;
        if (args.hasLogOutput()) {
            try {
                logFileStream = Files.newOutputStream(args.getLogFilePath(),
                                                      StandardOpenOption.CREATE_NEW,
                                                      StandardOpenOption.WRITE);
            } catch (IOException e) {
                Server.printError("Could not create log file: " + args.getLogFilePath());
                Server.printError(e.getMessage());
                return;
            }
        } else {
            logFileStream = OutputStream.nullOutputStream();
        }

        // Load and start client.
        Client client;
        Timeout timeout = new Timeout();
        try {
            long timeoutNS = args.getTimeoutSeconds() * 1_000_000_000L;
            client = new Client(domain, args.getClientCommand(), logFileStream, true, timeout, timeoutNS);
        } catch (Exception e) {
            Server.printError("Could not start client process.");
            Server.printError(e.getMessage());
            try {
                logFileStream.close();
            } catch (IOException e1) {
                Client.printError("Could not close log file.");
                Client.printError(e1.getMessage());
            }
            return;
        }

        // Start GUI.
        if (args.hasGUIOutput()) {
            assert playbackManager != null;
            Server.printDebug("Starting GUI.");
            playbackManager.startGUI(args.getStartFullscreen(),
                                     args.getStartHiddenInterface(),
                                     args.getMsPerAction(),
                                     args.getStartPlaying());
            playbackManager.focusPlaybackFrame(0);
        }

        // Start client protocol.
        client.startProtocol();

        // Wait for GUI to shut down.
        if (args.hasGUIOutput()) {
            assert playbackManager != null;
            playbackManager.waitShutdown();
            timeout.expire(); // Does nothing if timeout already expired or stopped.
        }

        // Wait for client to shut down (if it hasn't already while GUI ran).
        client.waitShutdown();
    }

    private static void runClientOnLevelDirectory(ArgumentParser args)
    {
        var levelFileFilter = new DirectoryStream.Filter<Path>()
        {
            @Override
            public boolean accept(Path entry)
            {
                return Files.isReadable(entry) &&
                       Files.isRegularFile(entry) &&
                       entry.getFileName().toString().endsWith(".lvl") &&
                       entry.getFileName().toString().length() > 4;
            }
        };

        try (var levelDirectory = Files.newDirectoryStream(args.getLevelPath(), levelFileFilter)) {
            // Open log file.
            OutputStream logFileStream;
            ZipOutputStream logZipStream;
            if (args.hasLogOutput()) {
                try {
                    logZipStream = new ZipOutputStream(new BufferedOutputStream(Files.newOutputStream(args.getLogFilePath(),
                                                                                                      StandardOpenOption.CREATE_NEW,
                                                                                                      StandardOpenOption.WRITE)));
                    logFileStream = logZipStream;
                } catch (IOException e) {
                    Server.printError("Could not create log file: " + args.getLogFilePath());
                    Server.printError(e.getMessage());
                    return;
                }
            } else {
                logFileStream = OutputStream.nullOutputStream();
                logZipStream = new ZipOutputStream(logFileStream);
            }

            ArrayList<String> levelNames = new ArrayList<>();
            ArrayList<String[]> levelStatus = new ArrayList<>();

            for (Path levelPath : levelDirectory) {
                Server.printInfo(String.format("Running client on level file: %s", levelPath.toString()));

                // Load domain.
                Domain domain;
                try {
                    Server.printDebug("Loading domain.");
                    domain = Domain.loadLevel(levelPath);
                } catch (ParseException e) {
                    Server.printError("Could not load domain, failed to parse level file.");
                    Server.printError(e.getMessage());
                    continue;
                } catch (IOException e) {
                    Server.printError("IOException while loading domain.");
                    e.printStackTrace();
                    continue;
                }

                // Never run with GUI, always discard states.
                domain.allowDiscardingPastStates();

                // Prepare next log entry.
                String levelFileName = levelPath.getFileName().toString();
                String logEntryName = levelFileName.substring(0, levelFileName.length() - 4) + ".log";
                try {
                    logZipStream.putNextEntry(new ZipEntry(logEntryName));
                } catch (IOException e) {
                    Server.printError("Could not create log file entry for level.");
                    Server.printError(e.getMessage());
                    continue;
                }

                // Load and start client.
                Client client;
                Timeout timeout = new Timeout();
                try {
                    long timeoutNS = args.getTimeoutSeconds() * 1_000_000_000L;
                    client = new Client(domain, args.getClientCommand(), logFileStream, false, timeout, timeoutNS);
                } catch (Exception e) {
                    Server.printError("Could not start client process.");
                    Server.printError(e.getMessage());
                    continue;
                }

                // Start client protocol.
                client.startProtocol();

                // Wait for client to shut down.
                client.waitShutdown();

                // Aggregate level summaries.
                levelNames.add(domain.getLevelName());
                levelStatus.add(domain.getStatus());

                // Attempt to clear up resources before we proceed to next level.
                domain = null;
                timeout = null;
                client = null;
                System.gc();
            }

            // Write summary to log file.
            try {
                logZipStream.putNextEntry(new ZipEntry("summary.txt"));
                BufferedWriter logWriter = new BufferedWriter(new OutputStreamWriter(logFileStream,
                                                                                     StandardCharsets.US_ASCII.newEncoder()));
                for (int i = 0; i < levelNames.size(); ++i) {
                    logWriter.write("Level name: ");
                    logWriter.write(levelNames.get(i));
                    logWriter.newLine();
                    for (String statusLine : levelStatus.get(i)) {
                        logWriter.write(statusLine);
                        logWriter.newLine();
                    }
                    logWriter.newLine();
                    logWriter.flush();
                }
            } catch (IOException e) {
                Server.printError("Could not write summary to log file.");
                Server.printError(e.getMessage());
            }

            // Close log file.
            try {
                logZipStream.close();
            } catch (IOException e) {
                Server.printError("Could not close log file.");
                Server.printError(e.getMessage());
                return;
            }
        } catch (IOException e) {
            Server.printError("Could not open levels directory.");
            Server.printError(e.getMessage());
            return;
        }
    }

    private static void runReplays(ArgumentParser args)
    {
        // Load domains.
        Path[] replayFilePaths = args.getReplayFilePaths();
        Domain[] domains = new Domain[replayFilePaths.length];
        for (int i = 0; i < replayFilePaths.length; i++) {
            try {
                Server.printInfo(String.format("Loading log file: %s", replayFilePaths[i]));
                domains[i] = Domain.loadReplay(replayFilePaths[i]);
            } catch (ParseException e) {
                Server.printError("Could not load domain, failed to parse log file.");
                Server.printError(e.getMessage());
                return;
            } catch (IOException e) {
                Server.printError("IOException while loading domain.");
                Server.printError(e.getMessage());
                return;
            }
        }

        PlaybackManager playbackManager = null;
        if (args.hasGUIOutput()) {
            Server.printDebug("Loading GUI.");
            PlaybackManager.initializeGUI();
            for (var domain : domains) {
                domain.initializeGraphics();
            }
            var gcs = getGraphicsConfigurations(domains.length, args.getScreens());
            playbackManager = new PlaybackManager(domains, gcs);

            Server.printDebug("Starting GUI.");
            playbackManager.startGUI(args.getStartFullscreen(),
                                     args.getStartHiddenInterface(),
                                     args.getMsPerAction(),
                                     args.getStartPlaying());
            playbackManager.focusPlaybackFrame(0);

            playbackManager.waitShutdown();
        }
    }

    /**
     * Returns a GraphicsConfiguration array of size numScreens, attempting to map the user-specified
     * screen numbers (if provided) to the GraphicsDevices corresponding to a logical
     * left-to-right, top-to-bottom enumeration. See the screen notes in ArgumentParser.
     */
    private static GraphicsConfiguration[] getGraphicsConfigurations(int numScreens, int[] screens)
    {
        final GraphicsConfiguration defaultScreen = GraphicsEnvironment.getLocalGraphicsEnvironment()
                                                                       .getDefaultScreenDevice()
                                                                       .getDefaultConfiguration();

        // Attempt sorting for the logical enumeration. May not work, e.g. if bounds are not relative to each other.
        ToIntFunction<GraphicsConfiguration> getScreenX = gc -> gc.getBounds().x;
        ToIntFunction<GraphicsConfiguration> getScreenY = gc -> gc.getBounds().y;
        var screenConfigs = Arrays.stream(GraphicsEnvironment.getLocalGraphicsEnvironment().getScreenDevices())
                                  .map(GraphicsDevice::getDefaultConfiguration)
                                  .sorted(Comparator.comparingInt(getScreenX).thenComparingInt(getScreenY))
                                  .toArray(GraphicsConfiguration[]::new);

        var gcs = new GraphicsConfiguration[numScreens];
        for (int i = 0; i < numScreens; ++i) {
            if (i >= screens.length) {
                // Unspecified.
                gcs[i] = defaultScreen;
            } else if (screens[i] < 0 || screens[i] >= screenConfigs.length) {
                // Out of range.
                gcs[i] = defaultScreen;
                Server.printWarning("No screen #" + screens[i] + "; using default screen.");
            } else {
                // User-specified.
                gcs[i] = screenConfigs[screens[i]];
            }
        }

        return gcs;
    }

    public static void printDebug(String msg)
    {
        if (!Server.DEBUG) {
            return;
        }
        System.out.format("[server][debug][%s] %s\n", Thread.currentThread().getName(), msg);
    }

    public static void printInfo(String msg)
    {
        System.out.format("[server][info] %s\n", msg);
    }

    public static void printWarning(String msg)
    {
        System.out.format("[server][warning] %s\n", msg);
    }

    public static void printError(String msg)
    {
        System.out.format("[server][error] %s\n", msg);
    }
}
