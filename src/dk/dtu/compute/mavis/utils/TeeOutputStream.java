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

package dk.dtu.compute.mavis.utils;

import java.io.IOException;
import java.io.OutputStream;

public class TeeOutputStream
        extends OutputStream
{
    protected final OutputStream out1;
    protected final OutputStream out2;


    public TeeOutputStream(OutputStream out1, OutputStream out2)
    {
        this.out1 = out1;
        this.out2 = out2;
    }

    @Override
    public synchronized void write(int b)
    throws IOException
    {
        this.out1.write(b);
        this.out2.write(b);
    }

    @Override
    public synchronized void write(byte[] b)
    throws IOException
    {
        this.out1.write(b);
        this.out2.write(b);
    }

    @Override
    public synchronized void write(byte[] b, int off, int len)
    throws IOException
    {
        this.out1.write(b, off, len);
        this.out2.write(b, off, len);
    }

    @Override
    public synchronized void flush()
    throws IOException
    {
        try {
            this.out1.flush();
        } finally {
            this.out2.flush();
        }
    }

    @Override
    public synchronized void close()
    throws IOException
    {
        try {
            this.out1.close();
        } finally {
            this.out2.close();
        }
    }
}
