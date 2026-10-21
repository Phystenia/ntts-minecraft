package com.ntts.neoforge;

import net.minecraft.server.level.ServerPlayer;

import java.util.List;

final class NeoForgePlayerList {
    private NeoForgePlayerList() {
    }

    static List<ServerPlayer> get(ServerPlayer player) {
        return player.getServer().getPlayerList().getPlayers();
    }
}


