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

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.Random;

public class TestClient
{
    final static String[] ACTIONS = {"NoOp",

                                     "Move(N)@Moving North", "Move(S)@Moving South", "Move(E)@Moving East",
                                     "Move(W)@Moving West",

                                     "Push(N,N)", "Push(N,E)", "Push(N,W)", "Push(S,S)", "Push(S,E)", "Push(S,W)",
                                     "Push(E,N)", "Push(E,S)", "Push(E,E)", "Push(W,N)", "Push(W,S)", "Push(W,W)",

                                     "Pull(N,N)", "Pull(N,E)", "Pull(N,W)", "Pull(S,S)", "Pull(S,E)", "Pull(S,W)",
                                     "Pull(E,N)", "Pull(E,S)", "Pull(E,E)", "Pull(W,N)", "Pull(W,S)", "Pull(W,W)"};

    public static void main(String[] args)
    throws IOException
    {
        System.setOut(new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.US_ASCII));
        var stdin = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.US_ASCII));

        // Client name.
        System.out.println("TestClient");
        System.out.flush();

        // Read level.
        String levelLine;
        int numAgents = 0;
        boolean countAgents = false;
        String levelName = "";
        boolean readLevelName = false;
        while (!(levelLine = stdin.readLine()).equals("#end")) {
            if (levelLine.startsWith("#levelname")) {
                readLevelName = true;
                continue;
            }
            if (readLevelName) {
                levelName = levelLine;
                readLevelName = false;
                continue;
            }
            if (levelLine.startsWith("#initial")) {
                countAgents = true;
                continue;
            } else if (levelLine.startsWith("#")) {
                countAgents = false;
                continue;
            }
            if (countAgents) {
                for (int i = 0; i < levelLine.length(); ++i) {
                    char c = levelLine.charAt(i);
                    if ('0' <= c && c <= '9') {
                        ++numAgents;
                    }
                }
            }
        }

        String[] jointAction = new String[numAgents];
        Random random = new Random(0);

        long numMessages = 0;
        final int batchSize = 50;

        messageLoop:
        while (true) {
            for (int i = 0; i < batchSize; ++i) {
                if (++numMessages > 20000) {
                    break messageLoop;
                }
                for (int agent = 0; agent < numAgents; ++agent) {
                    jointAction[agent] = ACTIONS[random.nextInt(ACTIONS.length)];
                }
                System.out.println(String.join("|", jointAction));
                System.out.flush();
            }

            for (int i = 0; i < batchSize; ++i) {
                String serverMsg = stdin.readLine();
                if (serverMsg == null) {
                    break messageLoop;
                }
            }
        }

        System.err.println("Client finished.");
        System.err.flush();
    }
}
