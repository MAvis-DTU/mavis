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

/**
 * A one-time Timeout object which the Client Thread uses to wait for the Protocol Thread.
 * The timeout can be manipulated by the Protocol Thread during the protocol.
 * <p>
 * The Protocol Thread can bundle a set of operations to be atomic from the perspective of the Client Thread
 * by synchronizing on this Timeout object.
 * <p>
 * The timeout either expires, or should be stopped by the Protocol Thread when the client has finished the protocol.
 * <p>
 * The Protocol Thread must never call functions that can block indefinitely when:
 * - The timeout is infinite.
 * - The Protocol Thread holds a lock on the Timeout object.
 */
public class Timeout
{
    private volatile boolean expired = false;
    private volatile boolean stopped = false;
    private long startNS;
    private long timeoutNS;

    /**
     * Constructs a Timeout object with an infinite timeout period.
     */
    public Timeout()
    {
        this.startNS = 0;
        this.timeoutNS = 0;
    }

    /**
     * If the timeout has already expired or is stopped, then this function does nothing and returns false.
     * <p>
     * Otherwise, it resets the timeout and returns true.
     * If timeoutNS == 0, then the new timeout period is infinite.
     */
    public synchronized boolean reset(long startNS, long timeoutNS)
    {
        if (this.expired || this.stopped) {
            return false;
        }

        this.startNS = startNS;
        this.timeoutNS = timeoutNS;
        this.notifyAll();

        return true;
    }

    /**
     * If the timeout has already expired or is stopped, then this function does nothing and returns false.
     * <p>
     * Otherwise, it adds incrementNS to the current timeout period and returns true.
     * If the current timeout period is infinite, then this does nothing and returns true.
     */
    public synchronized boolean increment(long incrementNS)
    {
        if (this.expired || this.stopped) {
            return false;
        }

        if (this.timeoutNS != 0) {
            this.timeoutNS += incrementNS;
            this.notifyAll();
        }

        return true;
    }

    /**
     * If the timeout has already expired or is stopped, then this function does nothing and returns false.
     * <p>
     * Otherwise, it subtracts decrementNS from the current timeout period and returns true.
     * If the current timeout period is infinite, then this does nothing and returns true.
     */
    public synchronized boolean decrement(long decrementNS)
    {
        if (this.expired || this.stopped) {
            return false;
        }

        if (this.timeoutNS != 0) {
            this.timeoutNS -= decrementNS;
            this.notifyAll();
        }

        return true;
    }

    /**
     * If the timeout has already expired or is stopped, then this function does nothing and returns false.
     * <p>
     * Otherwise, it stops the timeout and returns true.
     * <p>
     * This function should be called once by the Protocol Thread during Domain.runProtocol if the client has
     * finished the protocol within its time limit. Calling this will wake up the Client Thread and cause it to join
     * on the Protocol Thread to let the protocol finish cleanly.
     * <p>
     * If the timeout expires before this is called, then the Client Thread will assume that the Protocol Thread is
     * blocked indefinitely and proceed to forcibly terminate the client process before joining on the Protocol Thread.
     */
    public synchronized boolean stop()
    {
        if (this.expired || this.stopped) {
            return false;
        }

        this.stopped = true;
        this.notifyAll();

        return true;
    }

    /**
     * If the timeout has already expired or is stopped, then this function does nothing and returns false.
     * <p>
     * Otherwise, it immediately expires the timeout and returns true.
     * <p>
     * This function is called from the Main Thread to signal timeout when the GUI was closed before
     * the normal time out expired and we want to time the client out immediately to shut down.
     */
    public synchronized boolean expire()
    {
        if (this.expired || this.stopped) {
            return false;
        }

        this.expired = true;
        this.notifyAll();

        return true;
    }

    /**
     * Returns true if the timeout was stopped before it expired.
     * Returns false otherwise.
     */
    public boolean isStopped()
    {
        return this.stopped;
    }

    /**
     * Returns true if the timeout expired before it was stopped.
     * Returns false otherwise.
     */
    public boolean isExpired()
    {
        return this.expired;
    }

    /**
     * The Client Thread waits here for the timeout to expire or the timeout to be stopped.
     * This function returns true if the timeout expired, and false if the timeout was stopped before expiration.
     */
    public synchronized boolean waitTimeout()
    {
        if (this.stopped || this.expired) {
            return this.expired;
        }

        long remainingNS = (this.timeoutNS == 0 ? 0 : this.getRemainingNS());
        while (!this.stopped && !this.expired && (this.timeoutNS == 0 || remainingNS > 0)) {
            try {
                this.wait((remainingNS + 999_999L) / 1_000_000L); // Round up to next millisecond.
            } catch (InterruptedException ignored) {
            }
            remainingNS = (this.timeoutNS == 0 ? 0 : this.getRemainingNS());
        }

        if (!this.stopped) {
            this.expired = true;
        }

        return this.expired;
    }

    private synchronized long getRemainingNS()
    {
        return this.timeoutNS - (System.nanoTime() - this.startNS);
    }
}
