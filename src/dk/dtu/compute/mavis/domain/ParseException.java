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

public class ParseException
        extends Exception
{
    public final int lineNumber;

    public ParseException(String message)
    {
        super(message);
        this.lineNumber = -1;
    }

    public ParseException(String message, int lineNumber)
    {
        super(message);
        this.lineNumber = lineNumber;
    }

    @Override
    public String getMessage()
    {
        if (this.lineNumber == -1) {
            return super.getMessage();
        } else {
            return "On line " + this.lineNumber + ": " + super.getMessage();
        }
    }
}
