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

package dk.dtu.compute.mavis.client;

import dk.dtu.compute.mavis.domain.Domain;
import dk.dtu.compute.mavis.server.Server;

import java.io.*;
import java.util.Arrays;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

public class Client
{
    private Process clientProcess;
    private Thread clientThread;

    private final Timeout timeout;
    private long timeoutNS;

    private Domain domain;

    private BufferedInputStream clientIn;
    private BufferedOutputStream clientOut;
    private OutputStream logOut;

    private boolean closeLogOnExit;
    private boolean running = false;
    private boolean finished = false;

    /**
     * If the constructor succeeds, then the client process will already be spawned (so can't abort easily).
     * Otherwise, an IOException is thrown and the client process is not spawned.
     * <p>
     * If the constructor succeeds, then Client.startProtocol() has to be called for the Client Thread to terminate.
     * The Client Thread starts the Protocol Thread and has it run the Domain.runProtocol() function.
     * <p>
     * The Protocol Thread should communicate the timeouts to the Client Thread through the Timeout object that
     * it is passed as argument to Domain.runProtocol().
     * <p>
     * If the timeout expires before the Protocol Thread stops or extends the timeout, then the Client thread assumes
     * that the Protocol Thread is indefinitely blocked and proceeds to forcibly terminate the client process.
     * <p>
     * The Client Thread will finally wait for the Protocol Thread to exit and then itself exit.
     */
    public Client(Domain domain,
                  String clientCommand,
                  OutputStream logOut,
                  boolean closeLogOnExit,
                  Timeout timeout,
                  long timeoutNS)
    throws IOException
    {
        this.domain = domain;
        this.logOut = logOut;
        this.closeLogOnExit = closeLogOnExit;
        this.timeout = timeout;
        this.timeoutNS = timeoutNS;

        // Naïvely tokenize client command.
        ProcessBuilder processBuilder = new ProcessBuilder();
        processBuilder.command(Arrays.asList(clientCommand.strip().split("\\s++")));
        processBuilder.redirectError(ProcessBuilder.Redirect.INHERIT);

        this.clientProcess = processBuilder.start();

        InputStream clientIn = this.clientProcess.getInputStream();
        this.clientIn = clientIn instanceof BufferedInputStream ?
                        (BufferedInputStream) clientIn :
                        new BufferedInputStream(clientIn);
        OutputStream clientOut = this.clientProcess.getOutputStream();
        this.clientOut = clientOut instanceof BufferedOutputStream ?
                         (BufferedOutputStream) clientOut :
                         new BufferedOutputStream(clientOut);

        this.clientThread = new Thread(this::runClient, "ClientThread");
        this.clientThread.start();
    }

    public synchronized void startProtocol()
    {
        this.running = true;
        this.notifyAll();
    }

    public void waitShutdown()
    {
        synchronized (this) {
            while (!this.finished) {
                try {
                    this.wait();
                } catch (InterruptedException ignored) {
                }
            }
        }

        while (true) {
            try {
                this.clientThread.join();
                return;
            } catch (InterruptedException ignored) {
            }
        }
    }

    private void runClient()
    {
        Client.printDebug("Thread started.");

        // Wait until startProtocol() called by Main Thread.
        synchronized (this) {
            while (!this.running) {
                try {
                    this.wait();
                } catch (InterruptedException ignored) {
                }
            }
        }

        Client.printDebug(String.format("Client process supports normal termination: %s.",
                                        this.clientProcess.supportsNormalTermination()));

        // Start Protocol Thread.
        Thread protocolThread = new Thread(this::runProtocol, "ProtocolThread");
        protocolThread.start();

        // Wait for timeout to stop or expire. Handle accordingly.
        boolean timeoutExpired = this.timeout.waitTimeout();

        if (!timeoutExpired) {
            Client.printDebug("ProtocolThread stopped timeout, waiting for client to terminate.");
            while (true) {
                try {
                    protocolThread.join();
                    break;
                } catch (InterruptedException ignored) {
                }
            }
            Client.printInfo("Waiting for client process to terminate by itself.");
            try {
                this.clientProcess.waitFor(500, TimeUnit.MILLISECONDS);
            } catch (InterruptedException ignored) {
            }
            this.terminateClient();
            this.closeClientStreams();
        } else {
            Client.printInfo("Client timed out.");
            this.terminateClient();
            this.closeClientStreams();
            while (true) {
                try {
                    protocolThread.join();
                    break;
                } catch (InterruptedException ignored) {
                }
            }
        }

        if (this.closeLogOnExit) {
            try {
                this.logOut.flush();
                this.logOut.close();
                Client.printDebug("Closed log stream.");
            } catch (IOException e) {
                Client.printError("Could not flush and close log file.");
                Client.printError(e.getMessage());
            }
        }

        // Print status of run.
        for (String s : domain.getStatus()) {
            Server.printInfo(s);
        }

        synchronized (this) {
            this.finished = true;
            this.notifyAll();
        }

        Client.printDebug("Thread shut down.");
    }

    private void runProtocol()
    {
        Client.printDebug("Thread started.");

        this.domain.runProtocol(this.timeout, this.timeoutNS, this.clientIn, this.clientOut, this.logOut);

        // If Domain.runProtocol() forgot to call Timeout.stop(), we call it here (does nothing if already stopped or
        // expired).
        this.timeout.stop();

        Client.printDebug("Thread shut down.");
    }

    private void terminateClient()
    {
        if (this.clientProcess.isAlive() && this.clientProcess.supportsNormalTermination()) {
            Client.printInfo("Sending termination signal to client process (PID = " + this.clientProcess.pid() + ").");
            this.clientProcess.destroy();
            try {
                boolean terminated = this.clientProcess.waitFor(1000, TimeUnit.MILLISECONDS);
                if (terminated) {
                    return;
                }
            } catch (InterruptedException ignored) {
            }
        }

        if (this.clientProcess.isAlive()) {
            Client.printInfo("Forcibly terminating client process.");
            this.clientProcess.destroyForcibly();
            try {
                boolean terminated = this.clientProcess.waitFor(200, TimeUnit.MILLISECONDS);
                if (terminated) {
                    return;
                }
            } catch (InterruptedException ignored) {
            }
        }

        var clientChildProcesses = this.clientProcess.descendants()
                                                     .filter(ProcessHandle::isAlive)
                                                     .collect(Collectors.toSet());
        if (this.clientProcess.isAlive()) {
            Client.printWarning("Client process not terminated. PID = " + this.clientProcess.pid() + ".");
        } else if (!clientChildProcesses.isEmpty()) {
            Client.printWarning("Client spawned subprocesses which haven't terminated.");
            String leakedPIDs = clientChildProcesses.stream()
                                                    .map(ph -> Long.toString(ph.pid()))
                                                    .collect(Collectors.joining(", "));
            Client.printWarning("PIDs: " + leakedPIDs + ".");
        } else {
            Client.printInfo("Client terminated.");
        }
    }

    private void closeClientStreams()
    {
        try {
            this.clientIn.close();
        } catch (IOException ignored) {
        }
        try {
            this.clientOut.close();
        } catch (IOException ignored) {
        }
        try {
            this.clientProcess.getErrorStream().close();
        } catch (IOException ignored) {
        }
    }

    public static void printMessage(String msg)
    {
        System.out.format("[client][message] %s\n", msg);
    }

    public static void printDebug(String msg)
    {
        if (!Server.DEBUG) {
            return;
        }
        System.out.format("[client][debug][%s] %s\n", Thread.currentThread().getName(), msg);
    }

    public static void printInfo(String msg)
    {
        System.out.format("[client][info] %s\n", msg);
    }

    public static void printWarning(String msg)
    {
        System.out.format("[client][warning] %s\n", msg);
    }

    public static void printError(String msg)
    {
        System.out.format("[client][error] %s\n", msg);
    }
}
