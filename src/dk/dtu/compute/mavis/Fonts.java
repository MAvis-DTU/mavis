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

package dk.dtu.compute.mavis;

import dk.dtu.compute.mavis.server.Server;

import java.awt.*;
import java.io.IOException;
import java.io.InputStream;

public class Fonts
{
    private static final String dejaVuSansMonoResourcePath = "/fonts/dejavu/DejaVuSansMono-Bold.ttf";
    private static Font dejaVuSansMono = null;

    /**
     * Returns a global instance of the DejaVu Sans Mono (Bold) font, loaded on the first call.
     * Returns null and writes an error to Server.printError if the font can't be loaded.
     */
    public synchronized static Font getDejaVuSansMono()
    {
        if (dejaVuSansMono == null) {
            try {
                var fontLoadStart = System.nanoTime();
                dejaVuSansMono = loadFontResource(dejaVuSansMonoResourcePath, Font.TRUETYPE_FONT);
                var fontLoadElapsed = System.nanoTime() - fontLoadStart;
                Server.printDebug("Loaded DejaVu Sans Mono font in: " + fontLoadElapsed / 1_000_000L + " ms.");
            } catch (FontFormatException | IOException e) {
                Server.printError("Could not load DejaVu Sans Mono font:");
                Server.printError(e.getMessage());
            }
        }
        return dejaVuSansMono;
    }

    private static Font loadFontResource(String fontPath, int fontFormat)
    throws IOException, FontFormatException
    {
        try (InputStream fontStream = Fonts.class.getResourceAsStream(fontPath)) {
            if (fontStream == null) {
                throw new IOException("Could not get resource stream: " + fontPath);
            }
            return Font.createFont(fontFormat, fontStream);
        }
    }
}
