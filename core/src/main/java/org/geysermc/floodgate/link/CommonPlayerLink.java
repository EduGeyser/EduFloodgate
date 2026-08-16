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

package org.geysermc.floodgate.link;

import com.google.inject.Inject;
import com.google.inject.Key;
import com.google.inject.name.Named;
import com.google.inject.name.Names;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import lombok.AccessLevel;
import lombok.Getter;
import org.geysermc.event.Listener;
import org.geysermc.event.subscribe.Subscribe;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.link.LinkRequest;
import org.geysermc.floodgate.api.link.PlayerLink;
import org.geysermc.floodgate.api.logger.FloodgateLogger;
import org.geysermc.floodgate.core.config.FloodgateConfig;
import org.geysermc.floodgate.database.config.DatabaseConfig;
import org.geysermc.floodgate.database.config.DatabaseConfigLoader;
import org.geysermc.floodgate.core.event.lifecycle.ShutdownEvent;
import org.geysermc.floodgate.core.util.InjectorHolder;

@Listener
public abstract class CommonPlayerLink implements PlayerLink {
    @Getter(AccessLevel.PROTECTED)
    private final ExecutorService executorService = Executors.newCachedThreadPool();

    @Getter private boolean enabled;
    @Getter private boolean allowLinking;
    @Getter private long verifyLinkTimeout;

    @Inject
    @Getter(AccessLevel.PROTECTED)
    private FloodgateLogger logger;

    // injected here in core with guice's own annotations, which every platform honors:
    // core is relocated together with the shaded guice on Spigot, and the jakarta only
    // guice that Velocity 4 provides ignores the javax.inject annotations that database
    // jars used for this, which left the field null there
    @Inject
    @Named("dataDirectory")
    @Getter(AccessLevel.PROTECTED)
    private Path dataDirectory;

    @Inject
    @Getter(AccessLevel.PROTECTED)
    private FloodgateApi api;

    @Inject
    private InjectorHolder injectorHolder;

    @Inject
    private void init(FloodgateConfig config) {
        FloodgateConfig.PlayerLinkConfig linkConfig = config.getPlayerLink();
        enabled = linkConfig.isEnabled();
        allowLinking = linkConfig.isAllowed();
        verifyLinkTimeout = linkConfig.getLinkCodeTimeout();
    }

    private static final SecureRandom CODE_RANDOM = new SecureRandom();

    public String createCode() {
        // together with the attempt limit in LinkAccountCommand this makes the code an actual
        // secret, it can be the only thing gating redemption when the target was offline at
        // request creation
        return String.format("%06d", CODE_RANDOM.nextInt(1_000_000));
    }

    public boolean isRequestedPlayer(LinkRequest request, UUID bedrockId) {
        return request.isRequestedPlayer(api.getPlayer(bedrockId));
    }

    /**
     * Get the config present in init.json and turn it into the given config class. This method will
     * automatically copy and save the default config if the config doesn't exist.
     *
     * @param configClass the class to convert the config to
     * @param <T>         the config type
     * @return the loaded config or null if something went wrong.
     * @see DatabaseConfigLoader#loadAs(Class)
     */
    public <T extends DatabaseConfig> T getConfig(Class<T> configClass) {
        // this method is not intended to be used more than once. It'll make a new instance of
        // DatabaseConfigLoader and DatabaseConfig every time you run this method.
        return injectorHolder.get().getInstance(DatabaseConfigLoader.class).loadAs(configClass);
    }

    @Override
    public String getName() {
        return injectorHolder.get().getInstance(Key.get(String.class, Names.named("databaseName")));
    }

    @Override
    public void stop() {
        executorService.shutdown();
    }

    @Subscribe
    public void onShutdown(ShutdownEvent ignored) {
        stop();
    }
}
