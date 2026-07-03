/*
 * Copyright (c) 2019-2024 GeyserMC. http://geysermc.org
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

import io.netty.channel.ChannelHandler;
import io.netty.channel.ChannelPipeline;
import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.annotation.Annotation;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashSet;
import java.util.Locale;
import java.util.Properties;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.regex.Pattern;
import java.util.stream.Collectors;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.player.FloodgatePlayer;
import org.geysermc.floodgate.core.util.Constants;

public class Utils {
    private static final Pattern NON_UNIQUE_PREFIX = Pattern.compile("^\\w{0,16}$");
    private static final Pattern DATABASE_NAME = Pattern.compile(Constants.DATABASE_NAME_FORMAT);
    public static final int MAX_DEBUG_PACKET_COUNT = 25;

    /**
     * This method is used in Addons.<br> Most addons can be removed once the player associated to
     * the channel has been logged in, but they should also be removed once the inject is removed.
     * Because of how Netty works it will throw an exception and we don't want that. This method
     * removes those handlers safely.
     *
     * @param pipeline the pipeline
     * @param handler  the name of the handler to remove
     */
    public static void removeHandler(ChannelPipeline pipeline, String handler) {
        ChannelHandler channelHandler = pipeline.get(handler);
        if (channelHandler != null) {
            pipeline.remove(channelHandler);
        }
    }

    /**
     * Reads a properties resource file
     * @param resourceFile the resource file to read
     * @return the properties file if the resource exists, otherwise null
     * @throws AssertionError if something went wrong while readin the resource file
     */
    public static Properties readProperties(String resourceFile) {
        Properties properties = new Properties();
        try (InputStream is = Utils.class.getClassLoader().getResourceAsStream(resourceFile)) {
            if (is == null) {
                return null;
            }
            properties.load(new InputStreamReader(is, StandardCharsets.UTF_8));
        } catch (IOException e) {
            throw new AssertionError("Failed to read properties file", e);
        }
        return properties;
    }

    public static String getLocale(Locale locale) {
        return locale.getLanguage() + "_" + locale.getCountry();
    }

    public static UUID getJavaUuid(long xuid) {
        return new UUID(0, xuid);
    }

    public static UUID getJavaUuid(String xuid) {
        return getJavaUuid(Long.parseLong(xuid));
    }

    private static final long EDUCATION_UUID_MSB = 0x0000000100000001L;

    /**
     * Generate a stable, unique UUID for education players from their Entra OID.
     * The OID is a UUID v4 assigned by Microsoft to an Entra account. It is
     * cryptographically signed in the MESS token, immutable, and globally unique.
     * A person with multiple Entra accounts has multiple OIDs, the same way a
     * person with multiple Xbox accounts has multiple xuids. LSB is 64 purely
     * random bits extracted from the OID by stripping the 6 fixed UUID v4 bits
     * (version and variant).
     */
    public static UUID getEducationUuid(String oid) {
        UUID parsed = UUID.fromString(oid);
        long msb = parsed.getMostSignificantBits();
        long lsb = parsed.getLeastSignificantBits();

        long upper = ((msb >>> 16) << 12) | (msb & 0xFFF);
        long lower = (lsb << 2) >>> 60;
        return new UUID(EDUCATION_UUID_MSB, (upper << 4) | lower);
    }

    /**
     * Legacy UUID scheme. Derives an education player's Java UUID from the SHA-256 of
     * {@code tenantId + ":" + username}, taking the first 8 hash bytes as the LSB and the same
     * {@link #EDUCATION_UUID_MSB} sentinel as the MSB. Preserved only for deployments with existing
     * player data keyed by this scheme; selected via the configured education UUID scheme.
     */
    public static UUID getLegacyEducationUuid(String tenantId, String username) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest((tenantId + ":" + username).getBytes(StandardCharsets.UTF_8));
            long lsb = 0;
            for (int i = 0; i < 8; i++) {
                lsb = (lsb << 8) | (hash[i] & 0xFF);
            }
            return new UUID(EDUCATION_UUID_MSB, lsb);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError("SHA-256 not available", e);
        }
    }

    public static boolean isEducationId(UUID uuid) {
        return uuid.getMostSignificantBits() == EDUCATION_UUID_MSB;
    }

    /**
     * Maximum Java username length as enforced by Mojang's server.
     */
    private static final int MAX_JAVA_USERNAME_LENGTH = 16;

    /**
     * Find an available Java username for an education player by appending a numeric
     * {@code _N} suffix on collision with an already-online Floodgate player.
     * <p>
     * Education players share an Entra-derived Bedrock username within a tenant (e.g.
     * multiple students named "JohnS"). Rather than always appending a probabilistic
     * hash, we only add a suffix when the base name would collide, keeping single-tenant
     * deployments' usernames clean.
     * <p>
     * The edu prefix (default {@code +}) is not a valid character in standard Java
     * usernames, so we only need to check against other Floodgate players, not against
     * arbitrary Java accounts.
     *
     * @param baseName the desired Java username (already prefix + truncated Bedrock name)
     * @return {@code baseName} if unused, otherwise {@code baseName + "_" + N} for the
     *         smallest {@code N >= 1} that is unused; {@code baseName} is truncated
     *         further if adding the suffix would exceed 16 chars.
     */
    public static String findAvailableEduUsername(String baseName) {
        FloodgateApi api = FloodgateApi.getInstance();
        Set<String> inUse = new HashSet<>();
        for (FloodgatePlayer p : api.getPlayers()) {
            String name = p.getCorrectUsername();
            if (name != null) {
                inUse.add(name.toLowerCase(Locale.ROOT));
            }
        }

        if (!inUse.contains(baseName.toLowerCase(Locale.ROOT))) {
            return baseName;
        }

        int suffix = 1;
        while (true) {
            String suffixStr = "_" + suffix;
            int baseMax = MAX_JAVA_USERNAME_LENGTH - suffixStr.length();
            String base = baseName.length() > baseMax
                    ? baseName.substring(0, baseMax)
                    : baseName;
            String candidate = base + suffixStr;
            if (!inUse.contains(candidate.toLowerCase(Locale.ROOT))) {
                return candidate;
            }
            suffix++;
        }
    }

    public static boolean isUniquePrefix(String prefix) {
        return !NON_UNIQUE_PREFIX.matcher(prefix).matches();
    }

    public static boolean isValidDatabaseName(String databaseName) {
        return DATABASE_NAME.matcher(databaseName).matches();
    }

    public static String getStackTrace(Throwable throwable) {
        StringWriter writer = new StringWriter();
        throwable.printStackTrace(new PrintWriter(writer));
        return writer.toString();
    }

    /**
     * Returns a new CompletableFuture that is already completed exceptionally with the given
     * exception.
     *
     * @param ex  the exception
     * @param <U> the type of the value
     * @return the exceptionally completed CompletableFuture
     */
    public static <U> CompletableFuture<U> failedFuture(Throwable ex) {
        CompletableFuture<U> future = new CompletableFuture<>();
        future.completeExceptionally(ex);
        return future;
    }

    /**
     * Returns a set of all the classes that are annotated by a given annotation.
     * Keep in mind that these are from a set of generated annotations generated
     * at compile time by the annotation processor, meaning that arbitrary annotations
     * cannot be passed into this method and expected to get a set of classes back.
     *
     * @param annotationClass the annotation class
     * @return a set of all the classes annotated by the given annotation
     */
    public static Set<Class<?>> getGeneratedClassesForAnnotation(Class<? extends Annotation> annotationClass) {
        return getGeneratedClassesForAnnotation(annotationClass.getName());
    }

    /**
     * Returns a set of all the classes that are annotated by a given annotation.
     * Keep in mind that these are from a set of generated annotations generated
     * at compile time by the annotation processor, meaning that arbitrary annotations
     * cannot be passed into this method and expected to have a set of classes
     * returned back.
     *
     * @param input the fully qualified name of the annotation
     * @return a set of all the classes annotated by the given annotation
     */
    public static Set<Class<?>> getGeneratedClassesForAnnotation(String input) {
        try (InputStream annotatedClass = Utils.class.getClassLoader().getResourceAsStream(input);
             BufferedReader reader = new BufferedReader(new InputStreamReader(annotatedClass))) {
            return reader.lines().map(className -> {
                try {
                    return Class.forName(className);
                } catch (ClassNotFoundException ex) {
                    throw new RuntimeException("Failed to find class for annotation " + input, ex);
                }
            }).collect(Collectors.toSet());
        } catch (IOException e) {
            throw new RuntimeException(e);
        }
    }
}
