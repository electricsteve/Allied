package com.bba.allied.teamUtils;

import com.bba.allied.data.datManager;
import net.fabricmc.fabric.api.entity.event.v1.ServerLivingEntityEvents;
import net.fabricmc.fabric.api.event.lifecycle.v1.ServerTickEvents;
import net.minecraft.ChatFormatting;
import net.minecraft.nbt.CompoundTag;
import net.minecraft.network.chat.Component;
import net.minecraft.network.protocol.game.ClientboundPlayerInfoUpdatePacket;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.ServerScoreboard;
import net.minecraft.server.level.ServerPlayer;
import net.minecraft.world.damagesource.DamageSource;
import net.minecraft.world.effect.MobEffects;
import net.minecraft.world.entity.LivingEntity;
import net.minecraft.world.scores.PlayerTeam;
import net.fabricmc.fabric.api.message.v1.ServerMessageEvents;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.util.*;

public class teamUtils {
    static CompoundTag data = datManager.get().getData();

    public static final String MOD_ID = "Minecraft";
    public static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    public static void refreshTabForPlayer(ServerPlayer player) {
        MinecraftServer server = player.level().getServer();
        if (server == null) return;

        ClientboundPlayerInfoUpdatePacket packet = new ClientboundPlayerInfoUpdatePacket(
                ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME,
                player
        );
        server.getPlayerList().broadcastAll(packet);
    }

    public static void refreshAllTablist(MinecraftServer server) {
        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            ClientboundPlayerInfoUpdatePacket packet = new ClientboundPlayerInfoUpdatePacket(
                    ClientboundPlayerInfoUpdatePacket.Action.UPDATE_DISPLAY_NAME,
                    player
            );
            server.getPlayerList().broadcastAll(packet);
        }
    }

    public static void register() {
        ServerMessageEvents.ALLOW_CHAT_MESSAGE.register((signedMessage, player, params) -> {
            String rawText = signedMessage.decoratedContent().getString();
            Component formatted = formatTeamChat(player, rawText);

            UUID uuid = player.getUUID();

            if (teamChatManager.isEnabled(uuid)) {
                String teamName = datManager.get().getTeam(uuid);

                if (teamName != null) {
                    CompoundTag teamData = datManager.get().getData()
                            .getCompoundOrEmpty("teams")
                            .getCompoundOrEmpty(teamName);

                    teamData.getString("owner").ifPresent(ownerStr -> {
                        try {
                            ServerPlayer owner = player.level().getServer().getPlayerList()
                                    .getPlayer(UUID.fromString(ownerStr));
                            if (owner != null) owner.sendSystemMessage(formatted);
                        } catch (Exception ignored) {}
                    });

                    var members = teamData.getListOrEmpty("members");
                    for (int i = 0; i < members.size(); i++) {
                        members.getString(i).ifPresent(memberStr -> {
                            try {
                                ServerPlayer member = player.level().getServer().getPlayerList()
                                        .getPlayer(UUID.fromString(memberStr));
                                if (member != null) member.sendSystemMessage(formatted);
                            } catch (Exception ignored) {}
                        });
                    }
                }
            } else {
                MinecraftServer server = player.level().getServer();
                if (server != null) {
                    server.getPlayerList().getPlayers().forEach(p -> p.sendSystemMessage(formatted));
                }
                LOGGER.info("{}", formatted.getString());
            }

            return false;
        });

        ServerLivingEntityEvents.ALLOW_DAMAGE.register((entity, source, amount) -> teamUtils.handleFriendlyFire(entity, source));
        ServerTickEvents.END_LEVEL_TICK.register(world -> updateHighlight(world.getServer()));
    }

    private static Component formatTeamChat(ServerPlayer player, String originalMessage) {
        CompoundTag teams = datManager.get().getData().getCompoundOrEmpty("teams");
        String playerUuid = player.getUUID().toString();

        for (String teamName : teams.keySet()) {
            CompoundTag team = teams.getCompoundOrEmpty(teamName);

            if (team.getString("owner").orElse("").equals(playerUuid)) {
                return buildChatMessage(player, originalMessage, team, teamName);
            }

            var members = team.getListOrEmpty("members");
            for (int i = 0; i < members.size(); i++) {
                if (members.getString(i).orElse("").equals(playerUuid)) {
                    return buildChatMessage(player, originalMessage, team, teamName);
                }
            }
        }
        return Component.literal("<")
                .append(player.getDisplayName())
                .append("> ")
                .append(originalMessage).withStyle(ChatFormatting.WHITE);
    }

    private static Component buildChatMessage(
            ServerPlayer player,
            String message,
            CompoundTag team,
            String internalTeamName
    ) {
        boolean useTag = team
                .getCompoundOrEmpty("settings")
                .getBoolean("chatUseTag")
                .orElse(false);

        String colorStr = team
                .getString("tagColor")
                .orElse("WHITE");

        ChatFormatting color;
        try {
            color = ChatFormatting.valueOf(colorStr.toUpperCase());
        } catch (Exception e) {
            color = ChatFormatting.WHITE;
        }

        PlayerTeam scoreboardTeam = player.getTeam();

        Component prefix = Component.empty();
        Component playerName = Component.literal(player.getName().getString()).withStyle(ChatFormatting.WHITE);

        if (scoreboardTeam != null) {
            prefix = scoreboardTeam.getPlayerPrefix();
        }
        Component teamName = Component.literal("[").withStyle(ChatFormatting.WHITE)
                .append(Component.literal(internalTeamName).withStyle(color))
                .append(Component.literal("] ")).withStyle(ChatFormatting.WHITE);

        prefix = useTag ? prefix : teamName;

        UUID uuid = player.getUUID();
        boolean teamChatEnabled = teamChatManager.isEnabled(uuid);

        if (teamChatEnabled) {
            prefix = Component.literal("[").withStyle(ChatFormatting.WHITE)
                    .append(Component.literal("TEAM").withStyle(ChatFormatting.AQUA))
                    .append(Component.literal("] ")).withStyle(ChatFormatting.WHITE);
        }

        return Component.empty()
                .append(prefix)
                .append(Component.literal("<"))
                .append(playerName)
                .append(Component.literal("> "))
                .append(Component.literal(message));
    }

    public static void rebuildTeams(
            MinecraftServer server
    ) {
        removeAllTeams(server);

        CompoundTag teamsNBT = data.getCompoundOrEmpty("teams");

        for (String internalTeamName : teamsNBT.keySet()) {
            CompoundTag teamData = teamsNBT.getCompoundOrEmpty(internalTeamName);
            if (teamData.isEmpty()) continue;

            String colorStr = teamData
                    .getString("tagColor")
                    .orElse("WHITE");

            ChatFormatting teamColor;
            try {
                teamColor = ChatFormatting.valueOf(colorStr.toUpperCase());
            } catch (IllegalArgumentException e) {
                teamColor = ChatFormatting.WHITE;
            }

            PlayerTeam scoreboardTeam = addTeam(server, internalTeamName, teamColor);

            teamData.getString("owner").ifPresent(ownerUuidStr -> {
                try {
                    UUID uuid = UUID.fromString(ownerUuidStr);
                    ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                    if (player != null) {
                        addPlayerToTeam(server, player, scoreboardTeam);
                    }
                } catch (IllegalArgumentException ignored) {}
            });

            var members = teamData.getListOrEmpty("members");
            for (int i = 0; i < members.size(); i++) {
                members.getString(i).ifPresent(memberUuidStr -> {
                    try {
                        UUID uuid = UUID.fromString(memberUuidStr);
                        ServerPlayer player = server.getPlayerList().getPlayer(uuid);
                        if (player != null) {
                            addPlayerToTeam(server, player, scoreboardTeam);
                        }
                    } catch (IllegalArgumentException ignored) {}
                });
            }
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {
            updateOverheadName(server, player);
        }

        refreshAllTablist(server);
    }

    public static void updateOverheadName(MinecraftServer server, ServerPlayer player) {
        CompoundTag teams = datManager.get().getData().getCompoundOrEmpty("teams");
        String uuid = player.getUUID().toString();

        for (String internalTeamName : teams.keySet()) {
            CompoundTag teamData = teams.getCompoundOrEmpty(internalTeamName);

            boolean isOwner = teamData.getString("owner").orElse("").equals(uuid);
            boolean isMember = teamData.getListOrEmpty("members").stream()
                    .anyMatch(e -> e.asString().orElse("").equals(uuid));

            if (isOwner || isMember) {
                String tag = teamData.getString("teamTag").orElse(internalTeamName).toUpperCase();
                String colorStr = teamData.getString("tagColor").orElse("WHITE");
                ChatFormatting tagColor;

                try {
                    tagColor = ChatFormatting.valueOf(colorStr.toUpperCase());
                } catch (IllegalArgumentException e) {
                    tagColor = ChatFormatting.WHITE;
                }

                Component prefix = Component.literal("[")
                        .withStyle(ChatFormatting.WHITE)
                        .append(Component.literal(tag).withStyle(tagColor))
                        .append(Component.literal("] ").withStyle(ChatFormatting.WHITE));

                ServerScoreboard scoreboard = server.getScoreboard();
                String teamId = toTeamId(internalTeamName);
                PlayerTeam team = scoreboard.getPlayerTeam(teamId);
                if (team == null) {
                    team = scoreboard.addPlayerTeam(teamId);
                }

                team.setPlayerPrefix(prefix);
                team.setPlayerSuffix(Component.empty());
                team.setColor(ChatFormatting.WHITE);
                scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
                return;
            }
        }

        ServerScoreboard scoreboard = server.getScoreboard();
        for (PlayerTeam team : scoreboard.getPlayerTeams()) {
            if (team.getPlayers().contains(player.getScoreboardName())) {
                scoreboard.removePlayerFromTeam(player.getScoreboardName(), team);
            }
        }
    }

    public static void removeAllTeams(MinecraftServer server) {
        ServerScoreboard scoreboard = server.getScoreboard();

        for (PlayerTeam team : scoreboard.getPlayerTeams().toArray(PlayerTeam[]::new)) {
            scoreboard.removePlayerTeam(team);
        }
    }

    public static String toTeamId(String name) {
        return name.toLowerCase().replaceAll("\\s+", "");
    }

    public static PlayerTeam addTeam(
            MinecraftServer server,
            String fullName,
            ChatFormatting color
    ) {
        ServerScoreboard scoreboard = server.getScoreboard();
        String teamId = toTeamId(fullName);

        PlayerTeam team = scoreboard.getPlayerTeam(teamId);
        if (team == null) {
            team = scoreboard.addPlayerTeam(teamId);
        }

        team.setDisplayName(Component.literal(fullName));
        team.setColor(color);

        return team;
    }

    public static void addPlayerToTeam(
            MinecraftServer server,
            ServerPlayer player,
            PlayerTeam team
    ) {
        ServerScoreboard scoreboard = server.getScoreboard();

        scoreboard.addPlayerToTeam(player.getScoreboardName(), team);
    }

    public static boolean handleFriendlyFire(LivingEntity victim, DamageSource source) {
        if (!(source.getEntity() instanceof ServerPlayer attacker)) {
            return true;
        }

        if (!(victim instanceof ServerPlayer victimPlayer)) {
            return true;
        }

        CompoundTag teams = datManager.get().getData().getCompoundOrEmpty("teams");

        String attackerUuid = attacker.getUUID().toString();
        String victimUuid = victimPlayer.getUUID().toString();

        String attackerTeam = findPlayersTeam(teams, attackerUuid);
        String victimTeam   = findPlayersTeam(teams, victimUuid);

        if (attackerTeam == null || !attackerTeam.equals(victimTeam)) {
            return true;
        }

        CompoundTag teamData = teams.getCompoundOrEmpty(attackerTeam);

        return teamData
                .getCompoundOrEmpty("settings")
                .getBoolean("friendlyFire")
                .orElse(false);
    }

    private static String findPlayersTeam(CompoundTag teams, String uuid) {
        for (String teamName : teams.keySet()) {
            CompoundTag team = teams.getCompoundOrEmpty(teamName);

            if (team.getString("owner").orElse("").equals(uuid)) {
                return teamName;
            }

            var members = team.getListOrEmpty("members");
            for (int i = 0; i < members.size(); i++) {
                if (members.getString(i).orElse("").equals(uuid)) {
                    return teamName;
                }
            }
        }
        return null;
    }

    public static void updateHighlight(MinecraftServer server) {
        CompoundTag teams = datManager.get().getData().getCompoundOrEmpty("teams");

        Map<String, CompoundTag> uuidToTeam = new HashMap<>();
        for (String teamName : teams.keySet()) {
            CompoundTag team = teams.getCompoundOrEmpty(teamName);
            team.getString("owner").ifPresent(owner -> uuidToTeam.put(owner, team));

            var members = team.getListOrEmpty("members");
            for (int i = 0; i < members.size(); i++) {
                members.getString(i).ifPresent(member -> uuidToTeam.put(member, team));
            }
        }

        for (ServerPlayer player : server.getPlayerList().getPlayers()) {

            CompoundTag playerTeam = uuidToTeam.get(player.getUUID().toString());

            boolean highlightEnabled = false;
            if (playerTeam != null) {
                highlightEnabled = playerTeam
                        .getCompoundOrEmpty("settings")
                        .getBoolean("highlight")
                        .orElse(false);
            }

            for (ServerPlayer teammate : server.getPlayerList().getPlayers()) {
                if (teammate == player) continue;

                CompoundTag teammateTeam = uuidToTeam.get(teammate.getUUID().toString());

                boolean shouldGlow = highlightEnabled
                        && teammateTeam != null
                        && teammateTeam == playerTeam
                        && teammate.hasEffect(MobEffects.INVISIBILITY);

                teammate.setGlowingTag(shouldGlow);
            }
        }
    }
}
