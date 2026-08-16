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

package org.geysermc.floodgate.core.util;

import com.google.inject.Inject;
import com.google.inject.Provider;
import com.google.inject.Singleton;
import com.google.inject.name.Named;
import java.io.IOException;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.geysermc.floodgate.api.logger.FloodgateLogger;
import org.geysermc.floodgate.api.player.FloodgatePlayer;
import org.geysermc.floodgate.core.config.FloodgateConfig;
import org.geysermc.floodgate.core.platform.command.CommandUtil;
import org.geysermc.floodgate.util.BedrockData;
import org.yaml.snakeyaml.Yaml;

/**
 * Holds fwhitelist entries for players who could not be resolved to a UUID at command time
 * (education players have no publicly resolvable identity, and Bedrock players can't be resolved
 * when the xuid api is unavailable). When a matching Floodgate player completes the handshake,
 * their now known UUID is written to the server whitelist and the entry is consumed.
 * <p>
 * Entries are stored verbatim as typed (normalized to lower case, spaces as underscores) and are
 * never interpreted against the configured prefixes at add time, because prefixes are operator
 * config that can be empty, multiple characters, or differ between a proxy and its backends.
 * All prefix knowledge is applied at match time instead, by generating candidate names forward
 * from the joining player: their Java name with and without the configured prefix, and their
 * verified raw username in full, prefixed, and truncated to the Java name length budget. Every
 * comparison is an exact one against these forms, so an entry can never be consumed by a player
 * whose raw name merely shares a truncated stem or differs by a literal {@code _N} tail. The
 * {@code _N} collision suffix needs no special handling: it only exists on the Java name, and
 * the raw name candidates match the entry the operator typed without it.
 * <p>
 * The file is only the persistence layer, rewritten on every mutation; lookups run against
 * memory. Nothing is written to disk until the first entry is added, so proxies (where the
 * fwhitelist command doesn't register) never grow the file.
 */
@Singleton
public final class PendingWhitelistManager {
    private static final String FILE_NAME = "pending-whitelist.yml";
    private static final String FILE_HEADER =
            "# Players waiting to be whitelisted by fwhitelist on their next join.\n" +
            "# Managed by Floodgate, edits made while the server is running are overwritten.\n";

    private final Path file;
    private final FloodgateConfig config;
    private final FloodgateLogger logger;
    private final Provider<CommandUtil> commandUtil;

    private final Map<String, PendingEntry> entries = new LinkedHashMap<>();

    @Inject
    public PendingWhitelistManager(
            @Named("dataDirectory") Path dataDirectory,
            FloodgateConfig config,
            FloodgateLogger logger,
            Provider<CommandUtil> commandUtil) {
        this.file = dataDirectory.resolve(FILE_NAME);
        this.config = config;
        this.logger = logger;
        this.commandUtil = commandUtil;
        load();
    }

    /**
     * Adds or updates a pending entry for the given name as typed by the command sender.
     *
     * @return true if the entry is new, false if an existing entry was replaced
     */
    public synchronized boolean add(String name, Population population, String addedBy) {
        String key = normalize(name);
        PendingEntry previous = entries.put(key,
                new PendingEntry(key, population, addedBy, Instant.now().toString()));
        save();
        return previous == null;
    }

    /**
     * Removes the pending entry stored under the given name, if any.
     */
    public synchronized boolean remove(String name) {
        if (entries.remove(normalize(name)) == null) {
            return false;
        }
        save();
        return true;
    }

    /**
     * Tries to consume a pending entry for the given player and, on a match, whitelists their
     * verified UUID. Called from the handshake path, which runs well before the server's own
     * whitelist check, so a matched player passes that check on the same join. The entry is only
     * consumed after the whitelist write is handed off, so if the write ever loses the race with
     * the login the next join still succeeds.
     */
    public synchronized void fulfill(FloodgatePlayer player, BedrockData data) {
        if (entries.isEmpty() || config.isProxy()) {
            return;
        }

        boolean education = data.isEducation();
        String javaName = normalize(player.getCorrectUsername());
        String raw = normalize(data.getUsername());
        String prefix = normalize(education
                ? config.getEducationPrefix()
                : config.getUsernamePrefix());
        int budget = 16 - prefix.length();
        String truncatedRaw = raw.length() > budget ? raw.substring(0, budget) : raw;

        // every comparison is exact against forms computed forward from the verified names, so
        // an entry can only be consumed by a player it actually denotes: no truncation stem or
        // literal _N tail can cross match. A collision suffix needs no handling of its own, it
        // only exists on the Java name and the raw forms match the entry typed without it.
        // Ordered most specific first, the rendered in game name before derived forms
        Set<String> candidates = new LinkedHashSet<>();
        candidates.add(javaName);
        if (!prefix.isEmpty() && javaName.startsWith(prefix)) {
            candidates.add(javaName.substring(prefix.length()));
        }
        candidates.add(raw);
        candidates.add(prefix + raw);
        candidates.add(truncatedRaw);
        candidates.add(prefix + truncatedRaw);

        PendingEntry match = null;
        for (String candidate : candidates) {
            PendingEntry entry = entries.get(candidate);
            if (entry != null && entry.population.includes(education)) {
                match = entry;
                break;
            }
        }

        if (match == null) {
            return;
        }

        UUID uuid = player.getCorrectUniqueId();
        String username = player.getCorrectUsername();
        boolean added = commandUtil.get().whitelistPlayer(uuid, username);

        entries.remove(match.name);
        save();

        logger.info(
                "Whitelisted {} ({}, {}) from pending whitelist entry '{}' ({}, added by {} at {}){}",
                username,
                uuid,
                education ? "education player, tenant " + data.getTenantId() : "bedrock player",
                match.name,
                match.population.name().toLowerCase(Locale.ROOT),
                match.addedBy,
                match.addedAt,
                added ? "" : ", they were already whitelisted");
    }

    private static String normalize(String name) {
        return name.toLowerCase(Locale.ROOT).replace(' ', '_');
    }

    private void load() {
        if (!Files.exists(file)) {
            return;
        }

        Object loaded;
        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            loaded = new Yaml().load(reader);
        } catch (Exception exception) {
            logger.error(
                    "Could not read " + file + ", the pending whitelist starts empty and the "
                    + "file is left untouched until the next fwhitelist change", exception);
            return;
        }

        Object list = loaded instanceof Map ? ((Map<?, ?>) loaded).get("entries") : null;
        if (!(list instanceof List)) {
            return;
        }

        for (Object item : (List<?>) list) {
            if (!(item instanceof Map)) {
                continue;
            }
            Map<?, ?> entry = (Map<?, ?>) item;
            Object name = entry.get("name");
            if (name == null || name.toString().isEmpty()) {
                logger.warn("Skipping a pending whitelist entry without a name in {}", file);
                continue;
            }
            String key = normalize(name.toString());
            Object type = entry.get("type");
            Object addedBy = entry.get("added-by");
            Object addedAt = entry.get("added-at");
            entries.put(key, new PendingEntry(
                    key,
                    Population.fromInput(type != null ? type.toString() : null),
                    addedBy != null ? addedBy.toString() : "unknown",
                    addedAt != null ? addedAt.toString() : "unknown"));
        }
    }

    private void save() {
        List<Map<String, String>> list = new ArrayList<>();
        for (PendingEntry entry : entries.values()) {
            Map<String, String> item = new LinkedHashMap<>();
            item.put("name", entry.name);
            item.put("type", entry.population.name().toLowerCase(Locale.ROOT));
            item.put("added-by", entry.addedBy);
            item.put("added-at", entry.addedAt);
            list.add(item);
        }
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("entries", list);

        try {
            Path temp = file.resolveSibling(FILE_NAME + ".tmp");
            Files.write(temp, (FILE_HEADER + new Yaml().dump(root))
                    .getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temp, file,
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (IOException ignored) {
                // some file systems don't support atomic moves
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException exception) {
            logger.error("Could not save " + file, exception);
        }
    }

    /**
     * Which players an entry may match. Bare fwhitelist adds use BOTH, since prefixes are
     * operator config and can't reliably tell the populations apart from a typed name.
     */
    public enum Population {
        BOTH,
        BEDROCK,
        EDU;

        boolean includes(boolean education) {
            return this == BOTH || this == (education ? EDU : BEDROCK);
        }

        /**
         * Returns the population for the given user input, BOTH when absent, null when invalid.
         */
        public static Population fromInput(String input) {
            if (input == null || input.isEmpty()) {
                return BOTH;
            }
            String normalized = input.toLowerCase(Locale.ROOT);
            if (normalized.equals("bedrock")) {
                return BEDROCK;
            }
            if (normalized.equals("edu") || normalized.equals("education")) {
                return EDU;
            }
            return null;
        }
    }

    private static final class PendingEntry {
        final String name;
        final Population population;
        final String addedBy;
        final String addedAt;

        PendingEntry(String name, Population population, String addedBy, String addedAt) {
            this.name = name;
            this.population = population == null ? Population.BOTH : population;
            this.addedBy = addedBy;
            this.addedAt = addedAt;
        }
    }
}
