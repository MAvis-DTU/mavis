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

import dk.dtu.compute.mavis.domain.Domain;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;

public class ArgumentParser
{
    /**
     * Help options.
     */
    public enum PrintHelp
    {
        NONE,
        SHORT,
        LONG
    }

    private PrintHelp printHelp = PrintHelp.NONE;

    /**
     * Input and Output modes.
     * <p>
     * Valid combinations are:
     * CLIENT + FILE + {NONE, GUI, LOG, BOTH}
     * CLIENT + DIRECTORY + {NONE, LOG}
     * REPLAY + {NONE, GUI}
     * In total 8 combinations.
     */
    public enum ServerInputMode
    {
        NONE,
        CLIENT,
        REPLAY
    }

    public enum ClientInputMode
    {
        NONE,
        FILE,
        DIRECTORY
    }

    public enum ServerOutputMode
    {
        NONE,
        GUI,
        LOG,
        BOTH
    }

    private ServerInputMode serverInputMode = ServerInputMode.NONE;
    private ClientInputMode clientInputMode = ClientInputMode.NONE;
    private ServerOutputMode serverOutputMode = ServerOutputMode.NONE;

    /**
     * Client options.
     */
    private String clientCommand = null;
    private Path levelPath = null;
    private int timeoutSeconds = 0;
    private Path logFilePath = null;

    /**
     * Replay options.
     */
    private Path[] replayFilePaths = null;

    /**
     * GUI options.
     */
    private int[] screens = null;
    private int msPerAction = 250;
    private boolean startPlaying = true;
    private boolean startFullscreen = false;
    private boolean startHiddenInterface = false;


    public ArgumentParser(String[] args)
    throws ArgumentException
    {
        if (args.length == 0) {
            this.printHelp = PrintHelp.SHORT;
            return;
        }
        for (String s : args) {
            if (s.equals("-h")) {
                this.printHelp = PrintHelp.LONG;
                return;
            }
        }

        int i = 0;
        while (i < args.length) {
            switch (args[i]) {
                // GUI options.
                case "-g":
                    if (this.serverOutputMode == ServerOutputMode.NONE) {
                        this.serverOutputMode = ServerOutputMode.GUI;
                    } else if (this.serverOutputMode == ServerOutputMode.LOG) {
                        this.serverOutputMode = ServerOutputMode.BOTH;
                    }

                    ArrayList<Integer> screens = new ArrayList<>(8);
                    ++i;
                    while (i < args.length) {
                        if (args[i].charAt(0) == '-') {
                            --i;
                            break;
                        }
                        int screen;
                        try {
                            screen = Integer.parseInt(args[i]);
                        } catch (NumberFormatException e) {
                            --i;
                            break;
                        }
                        screens.add(screen);
                        ++i;
                    }
                    this.screens = screens.stream().mapToInt(s -> s).toArray();
                    break;

                case "-s":
                    ++i;
                    if (i >= args.length) {
                        throw new ArgumentException("Expected another argument after -s.");
                    }
                    try {
                        this.msPerAction = Integer.parseInt(args[i]);
                    } catch (NumberFormatException e) {
                        throw new ArgumentException("The argument after -s must be an integer.");
                    }
                    if (this.msPerAction < 0) {
                        throw new ArgumentException("The argument after -s can not be negative.");
                    }
                    break;

                case "-p":
                    this.startPlaying = false;
                    break;

                case "-f":
                    this.startFullscreen = true;
                    break;

                case "-i":
                    this.startHiddenInterface = true;
                    break;

                // Client options.
                case "-c":
                    if (this.serverInputMode == ServerInputMode.REPLAY) {
                        throw new ArgumentException("Can not use -c argument with -r.");
                    }
                    this.serverInputMode = ServerInputMode.CLIENT;

                    ++i;
                    if (i >= args.length) {
                        throw new ArgumentException("Expected another argument after -c.");
                    }
                    if (args[i].isBlank()) {
                        throw new ArgumentException("The argument after -c can not be blank.");
                    }
                    this.clientCommand = args[i];
                    break;

                case "-l":
                    if (this.serverInputMode == ServerInputMode.REPLAY) {
                        throw new ArgumentException("Can not use -l argument with -r.");
                    }
                    this.serverInputMode = ServerInputMode.CLIENT;

                    ++i;
                    if (i >= args.length) {
                        throw new ArgumentException("Expected another argument after -l.");
                    }
                    this.levelPath = Path.of(args[i]);
                    if (!Files.exists(this.levelPath) || !Files.isReadable(this.levelPath)) {
                        throw new ArgumentException("The level path may not exist, or has insufficient access.");
                    }
                    if (Files.isRegularFile(this.levelPath)) {
                        this.clientInputMode = ClientInputMode.FILE;
                    } else if (Files.isDirectory(this.levelPath)) {
                        this.clientInputMode = ClientInputMode.DIRECTORY;
                    } else {
                        throw new ArgumentException("Unknown level path type.");
                    }
                    break;

                case "-t":
                    if (this.serverInputMode == ServerInputMode.REPLAY) {
                        throw new ArgumentException("Can not use -t argument with -r.");
                    }
                    this.serverInputMode = ServerInputMode.CLIENT;

                    ++i;
                    if (i >= args.length) {
                        throw new ArgumentException("Expected another argument after -t.");
                    }
                    try {
                        this.timeoutSeconds = Integer.parseInt(args[i]);
                    } catch (NumberFormatException e) {
                        throw new ArgumentException("The argument after -t must be an integer.");
                    }
                    if (this.timeoutSeconds <= 0) {
                        throw new ArgumentException("The argument after -t must be positive.");
                    }
                    break;

                case "-o":
                    if (this.serverInputMode == ServerInputMode.REPLAY) {
                        throw new ArgumentException("Can not use -o argument with -r.");
                    }
                    this.serverInputMode = ServerInputMode.CLIENT;

                    if (this.serverOutputMode == ServerOutputMode.NONE) {
                        this.serverOutputMode = ServerOutputMode.LOG;
                    } else if (this.serverOutputMode == ServerOutputMode.GUI) {
                        this.serverOutputMode = ServerOutputMode.BOTH;
                    }

                    ++i;
                    if (i >= args.length) {
                        throw new ArgumentException("Expected another argument after -o.");
                    }
                    this.logFilePath = Path.of(args[i]);
                    if (this.logFilePath.getParent() != null && !Files.exists(this.logFilePath.getParent())) {
                        throw new ArgumentException(
                                "The parent directory of the log file must exist and have sufficient write access.");
                    }
                    if (!Files.notExists(this.logFilePath)) {
                        throw new ArgumentException("The log file may already exist, or has insufficient access.");
                    }
                    break;

                // Replay options.
                case "-r":
                    if (this.serverInputMode == ServerInputMode.CLIENT) {
                        throw new ArgumentException("Can not use -r argument with -c, -l, -t, or -o.");
                    }
                    this.serverInputMode = ServerInputMode.REPLAY;

                    ArrayList<Path> replayFilePaths = new ArrayList<>(8);
                    ++i;
                    while (i < args.length) {
                        if (args[i].charAt(0) == '-') {
                            --i;
                            break;
                        }
                        replayFilePaths.add(Path.of(args[i]));
                        ++i;
                    }
                    this.replayFilePaths = replayFilePaths.toArray(new Path[0]);
                    break;

                // Unknown argument.
                default:
                    throw new ArgumentException("Unknown argument: \"" + args[i] + "\".");
            }
            ++i;
        }

        // No GUI support for client with directory of levels.
        boolean isClientWithDirectory = this.serverInputMode == ServerInputMode.CLIENT &&
                                        this.clientInputMode == ClientInputMode.DIRECTORY;
        boolean hasGuiOutput = this.serverOutputMode == ServerOutputMode.GUI ||
                               this.serverOutputMode == ServerOutputMode.BOTH;
        if (isClientWithDirectory && hasGuiOutput) {
            throw new ArgumentException("GUI is not supported when running client on a directory of levels.");
        }
    }

    /**
     * Server mode.
     */
    public ServerInputMode getServerInputMode()
    {
        return this.serverInputMode;
    }

    public ClientInputMode getClientInputMode()
    {
        return this.clientInputMode;
    }

    public ServerOutputMode getServerOutputMode()
    {
        return this.serverOutputMode;
    }

    public boolean hasGUIOutput()
    {
        return this.serverOutputMode == ServerOutputMode.GUI || this.serverOutputMode == ServerOutputMode.BOTH;
    }

    public boolean hasLogOutput()
    {
        return this.serverOutputMode == ServerOutputMode.LOG || this.serverOutputMode == ServerOutputMode.BOTH;
    }

    /**
     * Client options.
     */
    public String getClientCommand()
    {
        return this.clientCommand;
    }

    public Path getLevelPath()
    {
        return this.levelPath;
    }

    public int getTimeoutSeconds()
    {
        return this.timeoutSeconds;
    }

    public Path getLogFilePath()
    {
        return this.logFilePath;
    }

    /**
     * Replay options.
     */
    public Path[] getReplayFilePaths()
    {
        return this.replayFilePaths;
    }

    /**
     * GUI options.
     */
    public int[] getScreens()
    {
        return this.screens;
    }

    public int getMsPerAction()
    {
        return this.msPerAction;
    }

    public boolean getStartPlaying()
    {
        return this.startPlaying;
    }

    public boolean getStartFullscreen()
    {
        return this.startFullscreen;
    }

    public boolean getStartHiddenInterface()
    {
        return this.startHiddenInterface;
    }

    /**
     * Indicates if the server should print a help message and exit.
     */
    public PrintHelp getPrintHelp()
    {
        return this.printHelp;
    }

    private static String getJarName()
    {
        try {
            return new File(Server.class.getProtectionDomain().getCodeSource().getLocation().toURI()).getName();
        } catch (Exception ignored) {
            return "server.jar";
        }
    }

    /**
     * Help messages.
     */
    public static String getShortHelp()
    {
        var shortHelp = """
                Show help message and exit:
                    java -jar %1$s [-h]
                Omitting the -h argument shows this abbreviated description.
                Providing the -h argument shows a detailed description.

                Run a client on a level or a directory of levels, optionally output to GUI and/or log file:
                    java -jar %1$s -c <client-cmd> -l <level-file-or-dir-path> [-t <seconds>]
                              %2$s [-g [<screen>] [-s <ms-per-action>] [-p] [-f] [-i]]
                              %2$s [-o <log-file-path>]

                Replay one or more log files, optionally output to synchronized GUIs:
                    java -jar %1$s -r <log-file-path> [<log-file-path> ...]
                              %2$s [-g [<screen> ...] [-s <ms-per-action>] [-p] [-f] [-i]]""";
        var jarName = getJarName();
        var jarNameSpacePadding = " ".repeat(jarName.length());
        return String.format(shortHelp, jarName, jarNameSpacePadding);
    }

    @SuppressWarnings("LongLine")
    public static String getLongHelp()
    {
        var longHelp = """
                The server modes are listed below.
                The order of arguments is irrelevant.
                Example invocations are given in the end.

                Show help message and exit:
                    java -jar %1$s [-h]
                Omitting the -h argument shows an abbreviated description.
                Providing the -h argument shows this detailed description.

                Run a client on a level or a directory of levels, optionally output to GUI and/or log file:
                    java -jar %1$s -c <client-cmd> -l <level-file-or-dir-path> [-t <seconds>]
                              %2$s [-g [<screen>] [-s <ms-per-action>] [-p] [-f] [-i]]
                              %2$s [-o <log-file-path>]
                Where the arguments are as follows:
                    -c <client-cmd>
                        Specifies the command the server will use to start the client process, including all client arguments.
                        The <client-cmd> string will be naïvely tokenized by splitting on whitespace, and
                        then passed to your OS native process API through Java's ProcessBuilder.
                    -l <level-file-or-dir-path>
                        Specifies the path to either a single level file, or to a directory containing one or more level files.
                    -t <seconds>
                        Optional. Specifies a timeout in seconds for the client.
                        The server will terminate the client after this timeout.
                        If this argument is not given, the server will never time the client out.
                    -g [<screen>]
                        Optional. Enables GUI output.
                        The optional <screen> argument specifies which screen to start the GUI on.
                        See notes on <screen> below for an explanation of the valid values.
                    -s <ms-per-action>
                        Optional. Set the GUI playback speed in milliseconds per action.
                        By default the speed is 250 ms per action.
                    -p
                        Optional. Start the GUI paused.
                        By default the GUI starts playing immediately.
                    -f
                        Optional. Start the GUI in fullscreen mode.
                        By default the GUI starts in windowed mode.
                    -i
                        Optional. Start the GUI with interface hidden.
                        By default the GUI shows interface elements for navigating playback.
                    -o <log-file-path>
                        Optional. Writes log file(s) of the client run.
                        If the -l argument is a level file path, then a single log file is written to the given log file path.
                        If the -l argument is a level directory path, then logs for the client run on all levels in the
                        level directory are compressed as a zip file written to the given log file path.
                        NB: The log file may *not* already exist (the server does not allow overwriting files).

                Replay one or more log files, optionally output to synchronized GUIs:
                    java -jar %1$s -r <log-file-path> [<log-file-path> ...]
                              %2$s [-g [<screen> ...] [-s <ms-per-action>] [-p] [-f] [-i]]
                Where the arguments are as follows:
                    -r <log-file-path> [<log-file-path> ...]
                        Specifies one or more log files to replay.
                    -g [<screen> ...]
                        Optional. Enables GUI output. The playback of the replays are synchronized.
                        The optional <screen> arguments specify which screen to start the GUI on for each log file.
                        See notes on <screen> below for an explanation of the valid values.
                    -s <ms-per-action>
                        Optional. Set the GUI playback speed in milliseconds per action.
                        By default the speed is 250 ms per action.
                    -p
                        Optional. Start the GUI paused.
                        By default the GUI starts playing immediately.
                    -f
                        Optional. Start the GUI in fullscreen mode.
                        By default the GUI starts in windowed mode.
                    -i
                        Optional. Start the GUI with interface hidden.
                        By default the GUI shows interface elements for navigating playback.

                Notes on the <screen> arguments:
                    Values for the <screen> arguments are integers in the range 0..(<num-screens> - 1).
                    The server attemps to enumerate screens from left-to-right, breaking ties with top-to-bottom.
                    The real underlying screen ordering is system-defined, and the server may fail at enumerating in the above order.
                    If no <screen> argument is given, then the 'default' screen is used, which is a system-defined screen.

                    E.g. in a grid aligned 2x2 screen setup, the server will attempt to enumerate the screens as:
                    0: Top-left screen.
                    1: Bottom-left screen.
                    2: Top-right screen.
                    3: Bottom-right screen.

                    E.g. in a 1x3 horizontally aligned screen setup, the server will attempt to enumerate the screens as:
                    0: The left-most screen.
                    1: The middle screen.
                    2: The right-most screen.

                You can use the following hotkeys when the server runs with GUI output:
                    <space>      : Toggle play/pause.
                    <left>       : Step back one state. Pauses.
                    <shift+left> : Step back ten states. Pauses.
                    <right>      : Step forward one state. Pauses.
                    <shift+right>: Step forward ten states. Pauses.
                    <ctrl+left>  : Jump to initial state.
                    <ctrl+right> : Jump to last state.
                    1-5          : Set playback speed to a predefined value (50, 100, 250, 500, 1000).
                    <up>         : Increase playback speed.
                    <down>       : Decrease playback speed.
                    <tab>        : Toggle focus for the playback speed text box.
                    F            : Toggle between windowed/fullscreen.
                    I            : Toggle interface elements that may spoil results on/off.
                    <ctrl>+Q     : Quit.
                If you are running on MacOS, then <ctrl> is your command key.

                Supported domains (case-sensitive):
                    %3$s

                Client example invocations:
                    # Client on single level, no output.
                    java -jar %1$s -c "java ExampleClient" -l "levels/example.lvl"

                    # Client on single level, output to GUI on default screen.
                    java -jar %1$s -c "java ExampleClient" -l "levels/example.lvl" -g

                    # Client on single level, output to GUI on screen 0.
                    java -jar %1$s -c "java ExampleClient" -l "levels/example.lvl" -g 0

                    # Client on single level, output to log file.
                    java -jar %1$s -c "java ExampleClient" -l "levels/example.lvl" -o "logs/example.log"

                    # Client on single level, output to GUI on default screen and to log file.
                    java -jar %1$s -c "java ExampleClient" -l "levels/example.lvl" -g -o "logs/example.log"

                    # Client on a directory of levels, no output.
                    java -jar %1$s -c "java ExampleClient" -l "levels"

                    # Client on a directory of levels, output to log archive.
                    java -jar %1$s -c "java ExampleClient" -l "levels" -o "logs.zip"

                Replay example invocations:
                    # Replay a single log file, no output.
                    java -jar %1$s -r "logs/example.log"

                    # Replay a single log file, output to GUI on default screen.
                    java -jar %1$s -r "logs/example.log" -g

                    # Replay two log files, output to synchronized GUIs on screen 0 and 1.
                    # Start the GUIs paused, in fullscreen mode and with hidden interface elements to avoid spoilers.
                    # Play back actions at a speed of one action every 500 milliseconds.
                    java -jar %1$s -r "logs/example1.log" "logs/example2.log" -g 0 1 -p -f -i -s 500""";
        var jarName = getJarName();
        var jarNameSpacePadding = " ".repeat(jarName.length());
        var supportedDomains = String.join("\n    ", Domain.getSupportedDomains());
        return String.format(longHelp, jarName, jarNameSpacePadding, supportedDomains);
    }
}

