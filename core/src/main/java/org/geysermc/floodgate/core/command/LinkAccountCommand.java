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

package org.geysermc.floodgate.core.command;

import static org.geysermc.floodgate.core.command.CommonCommandMessage.CHECK_CONSOLE;
import static org.incendo.cloud.parser.standard.StringParser.stringParser;

import com.google.inject.Inject;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import lombok.Getter;
import lombok.NoArgsConstructor;
import org.geysermc.floodgate.api.FloodgateApi;
import org.geysermc.floodgate.api.link.LinkRequestResult;
import org.geysermc.floodgate.api.link.PlayerLink;
import org.geysermc.floodgate.api.logger.FloodgateLogger;
import org.geysermc.floodgate.core.command.util.Permission;
import org.geysermc.floodgate.core.config.FloodgateConfig;
import org.geysermc.floodgate.link.GlobalPlayerLinking;
import org.geysermc.floodgate.core.platform.command.FloodgateCommand;
import org.geysermc.floodgate.core.platform.command.TranslatableMessage;
import org.geysermc.floodgate.core.player.UserAudience;
import org.geysermc.floodgate.core.player.UserAudience.PlayerAudience;
import org.geysermc.floodgate.core.player.audience.PlayerAudienceArgument;
import org.geysermc.floodgate.core.player.audience.ProfileAudience;
import org.geysermc.floodgate.core.util.Constants;
import org.geysermc.floodgate.core.util.Utils;
import org.incendo.cloud.Command;
import org.incendo.cloud.CommandManager;
import org.incendo.cloud.context.CommandContext;
import org.incendo.cloud.description.Description;

@NoArgsConstructor
public final class LinkAccountCommand implements FloodgateCommand {
    private static final int MAX_FAILED_VERIFY_ATTEMPTS = 5;
    private static final long VERIFY_ATTEMPT_WINDOW_MILLIS = TimeUnit.MINUTES.toMillis(10);

    @Inject private FloodgateApi api;
    @Inject private FloodgateLogger logger;

    // failed redemption attempts per Bedrock sender. The link code is a guessable size, so
    // without a limit the 1 in a million odds can be brute forced by command spam
    private final Map<UUID, FailedAttempts> verifyFailures = new ConcurrentHashMap<>();

    @Override
    public Command<PlayerAudience> buildCommand(CommandManager<UserAudience> commandManager) {
        return commandManager.commandBuilder("linkaccount",
                Description.of("Link your Java account with your Bedrock account"))
                .senderType(PlayerAudience.class)
                .permission(Permission.COMMAND_LINK.get())
                .argument(PlayerAudienceArgument.ofAnyUsernameBoth("player"))
                .optional("code", stringParser())
                .handler(this::execute)
                .build();
    }

    public void execute(CommandContext<PlayerAudience> context) {
        UserAudience sender = context.sender();

        PlayerLink link = api.getPlayerLink();

        //todo make this less hacky
        if (link instanceof GlobalPlayerLinking) {
            if (((GlobalPlayerLinking) link).getDatabaseImpl() != null) {
                sender.sendMessage(CommonCommandMessage.LOCAL_LINKING_NOTICE,
                        Constants.LINK_INFO_URL);
            } else {
                if (Utils.isEducationId(sender.uuid())) {
                    // the global linking api is keyed by xuid and education accounts have
                    // none, so pointing them at the global linking website would dead end
                    sender.sendMessage("Education accounts can't use global linking."
                            + " This server would need its own linking database.");
                    return;
                }
                sender.sendMessage(CommonCommandMessage.GLOBAL_LINKING_NOTICE,
                        Constants.LINK_INFO_URL);
                return;
            }
        }

        if (!link.isEnabledAndAllowed()) {
            sender.sendMessage(CommonCommandMessage.LINKING_DISABLED);
            return;
        }

        ProfileAudience targetUser = context.get("player");
        // allowUuid is false so username cannot be null
        String targetName = targetUser.username();

        // when the player is a Bedrock player
        if (api.isFloodgatePlayer(sender.uuid())) {
            if (!context.contains("code")) {
                sender.sendMessage(Message.BEDROCK_USAGE);
                return;
            }

            String code = context.get("code");

            if (isVerifyThrottled(sender.uuid())) {
                sender.sendMessage("Too many failed link attempts, try again later.");
                return;
            }

            link.verifyLinkRequest(sender.uuid(), targetName, sender.username(), code)
                    .whenComplete((result, throwable) -> {
                        if (throwable != null || result == LinkRequestResult.UNKNOWN_ERROR) {
                            sender.sendMessage(Message.LINK_REQUEST_ERROR);
                            return;
                        }

                        switch (result) {
                            case ALREADY_LINKED:
                                sender.sendMessage(Message.ALREADY_LINKED);
                                break;
                            case NO_LINK_REQUESTED:
                                recordVerifyFailure(sender.uuid());
                                sender.sendMessage(Message.NO_LINK_REQUESTED);
                                break;
                            case INVALID_CODE:
                                recordVerifyFailure(sender.uuid());
                                sender.sendMessage(Message.INVALID_CODE);
                                break;
                            case REQUEST_EXPIRED:
                                recordVerifyFailure(sender.uuid());
                                sender.sendMessage(Message.LINK_REQUEST_EXPIRED);
                                break;
                            case LINK_COMPLETED:
                                verifyFailures.remove(sender.uuid());
                                sender.disconnect(Message.LINK_REQUEST_COMPLETED, targetName);
                                break;
                            default:
                                sender.disconnect("Invalid account linking result");
                                break;
                        }
                    });
            return;
        }

        if (context.contains("code")) {
            sender.sendMessage(Message.JAVA_USAGE);
            return;
        }

        // when the target is online we can bind the request to their exact account, so that
        // no other same named account can ever redeem it. When they're offline the code is
        // the secret, handed to the intended player out of band
        UUID targetBedrockId = targetUser.uuid();
        if (targetBedrockId != null && !api.isFloodgateId(targetBedrockId)) {
            targetBedrockId = null;
        }

        link.createLinkRequest(sender.uuid(), sender.username(), targetName, targetBedrockId)
                .whenComplete((result, throwable) -> {
                    if (throwable != null || result == LinkRequestResult.UNKNOWN_ERROR) {
                        sender.sendMessage(Message.LINK_REQUEST_ERROR);
                        return;
                    }

                    if (!(result instanceof String)) {
                        logger.error("Expected string code, got {}", result);
                        sender.sendMessage(Message.LINK_REQUEST_ERROR);
                        return;
                    }

                    sender.sendMessage(Message.LINK_REQUEST_CREATED,
                            targetName, sender.username(), result);
                });
    }

    private boolean isVerifyThrottled(UUID senderId) {
        FailedAttempts failures = verifyFailures.get(senderId);
        if (failures == null) {
            return false;
        }
        synchronized (failures) {
            if (System.currentTimeMillis() - failures.windowStart > VERIFY_ATTEMPT_WINDOW_MILLIS) {
                return false;
            }
            return failures.count >= MAX_FAILED_VERIFY_ATTEMPTS;
        }
    }

    private void recordVerifyFailure(UUID senderId) {
        FailedAttempts failures = verifyFailures.computeIfAbsent(
                senderId, id -> new FailedAttempts());
        synchronized (failures) {
            long now = System.currentTimeMillis();
            if (now - failures.windowStart > VERIFY_ATTEMPT_WINDOW_MILLIS) {
                failures.windowStart = now;
                failures.count = 0;
            }
            failures.count++;
        }
    }

    private static final class FailedAttempts {
        long windowStart = System.currentTimeMillis();
        int count;
    }

    @Override
    public boolean shouldRegister(FloodgateConfig config) {
        FloodgateConfig.PlayerLinkConfig linkConfig = config.getPlayerLink();
        return linkConfig.isEnabled() &&
                (linkConfig.isEnableOwnLinking() || linkConfig.isEnableGlobalLinking());
    }

    @Getter
    public enum Message implements TranslatableMessage {
        ALREADY_LINKED("floodgate.command.link_account.already_linked"),
        JAVA_USAGE("floodgate.command.link_account.java_usage"),
        LINK_REQUEST_CREATED("floodgate.command.link_account.link_request_created"),
        BEDROCK_USAGE("floodgate.command.link_account.bedrock_usage"),
        LINK_REQUEST_EXPIRED("floodgate.command.link_account.link_request_expired"),
        LINK_REQUEST_COMPLETED("floodgate.command.link_account.link_request_completed"),
        LINK_REQUEST_ERROR("floodgate.command.link_request.error " + CHECK_CONSOLE),
        INVALID_CODE("floodgate.command.link_account.invalid_code"),
        NO_LINK_REQUESTED("floodgate.command.link_account.no_link_requested");

        private final String rawMessage;
        private final String[] translateParts;

        Message(String rawMessage) {
            this.rawMessage = rawMessage;
            this.translateParts = rawMessage.split(" ");
        }
    }
}
