/*
 * Copyright (c) 2019-2022 GeyserMC. http://geysermc.org
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in
 * all copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN
 * THE SOFTWARE.
 *
 * @author GeyserMC
 * @link https://github.com/GeyserMC/Floodgate
 */

package org.geysermc.floodgate.util;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.name.Named;
import org.geysermc.floodgate.api.logger.FloodgateLogger;
import org.geysermc.floodgate.api.util.EducationUuidScheme;
import org.yaml.snakeyaml.Yaml;

import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;
import java.util.Map;

/**
 * Provides the {@link EducationUuidScheme} from {@code <dataDirectory>/uuid/scheme.yml}. Bound as an
 * eager singleton so a misconfiguration fails Floodgate at startup rather than on the first
 * education handshake.
 * <p>
 * Because this controls player identity, the loader is deliberately strict: an absent file means
 * "no operator intent", so it is created from the template and defaults to MODERN; but a file that
 * is present yet missing or set to an unrecognized value is a fatal misconfiguration that aborts
 * startup, rather than silently guessing a scheme and re-IDing every education player.
 */
public final class EducationUuidSchemeProvider implements Provider<EducationUuidScheme> {

    private static final String FOLDER_NAME = "uuid";
    private static final String FILE_NAME = "scheme.yml";

    private static final String TEMPLATE =
            "# Education player UUID scheme.\n" +
            "#\n" +
            "# DO NOT change unless you know exactly what you're doing or were instructed to.\n" +
            "# This controls how education players' UUIDs (their identity for permissions,\n" +
            "# data, balances) are derived. It MUST be identical on EduGeyser and EVERY\n" +
            "# EduFloodgate instance on the network, including all backend servers and\n" +
            "# proxies. A mismatch gives a player different UUIDs on different servers and\n" +
            "# corrupts their data. Changing it on a live server re-IDs every education player.\n" +
            "#\n" +
            "#   modern - (default) MESS-verified Entra OID. Recommended for all new servers.\n" +
            "#   legacy - SHA-256(tenantId:username). Only for existing legacy player data.\n" +
            "scheme: modern\n";

    private final Path dataDirectory;
    private final FloodgateLogger logger;

    @Inject
    public EducationUuidSchemeProvider(@Named("dataDirectory") Path dataDirectory, FloodgateLogger logger) {
        this.dataDirectory = dataDirectory;
        this.logger = logger;
    }

    @Override
    public EducationUuidScheme get() {
        Path folder = dataDirectory.resolve(FOLDER_NAME);
        Path file = folder.resolve(FILE_NAME);

        // Absent file: no operator intent yet. Write the template and default to modern.
        if (!Files.exists(file)) {
            try {
                Files.createDirectories(folder);
                Files.write(file, TEMPLATE.getBytes(StandardCharsets.UTF_8));
            } catch (IOException e) {
                logger.warn("[Education UUID] Could not write " + file + ": " + e.getMessage());
            }
            return EducationUuidScheme.MODERN;
        }

        // Present file: operator intent. Never guess; any problem is a hard fail.
        Object loaded;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            loaded = new Yaml().load(reader);
        } catch (Exception e) {
            throw refuseToStart(file, "the file could not be parsed (" + e.getMessage() + ")");
        }

        Object schemeValue = loaded instanceof Map ? ((Map<?, ?>) loaded).get("scheme") : null;
        String value = schemeValue != null ? schemeValue.toString().trim() : null;
        if (value == null || value.isEmpty()) {
            throw refuseToStart(file, "no 'scheme' value is set");
        }
        String normalized = value.toLowerCase(Locale.ROOT);
        if (normalized.equals("modern")) {
            return EducationUuidScheme.MODERN;
        }
        if (normalized.equals("legacy")) {
            return EducationUuidScheme.LEGACY;
        }
        throw refuseToStart(file, "'" + value + "' is not a valid scheme (use modern or legacy)");
    }

    private RuntimeException refuseToStart(Path file, String reason) {
        logger.error("************************************************************************");
        logger.error("  EDUCATION UUID SCHEME IS MISCONFIGURED - REFUSING TO START");
        logger.error("");
        logger.error("  File:    " + file);
        logger.error("  Problem: " + reason + ".");
        logger.error("");
        logger.error("  This setting controls how education players' UUIDs (their identity)");
        logger.error("  are derived. Guessing it could silently change every education");
        logger.error("  player's UUID and disconnect them from their data, so Floodgate will");
        logger.error("  not start until it is fixed.");
        logger.error("");
        logger.error("  Set 'scheme' to exactly 'modern' or 'legacy', or delete the file to");
        logger.error("  regenerate it as 'modern'.");
        logger.error("************************************************************************");
        return new IllegalStateException("Invalid education UUID scheme in " + file + ": " + reason);
    }
}
