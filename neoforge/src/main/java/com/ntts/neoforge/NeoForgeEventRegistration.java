package com.ntts.neoforge;

import net.neoforged.neoforge.common.NeoForge;
import net.neoforged.neoforge.event.RegisterCommandsEvent;
import net.neoforged.neoforge.event.ServerChatEvent;
import net.neoforged.neoforge.event.server.ServerStartedEvent;
import net.neoforged.neoforge.event.server.ServerStoppingEvent;
import net.neoforged.bus.api.SubscribeEvent;

final class NeoForgeEventRegistration {
    private final NeoForgeNttsPlugin plugin;

    private NeoForgeEventRegistration(NeoForgeNttsPlugin plugin) {
        this.plugin = plugin;
    }

    static void register(NeoForgeNttsPlugin plugin) {
        NeoForge.EVENT_BUS.register(new NeoForgeEventRegistration(plugin));
    }

    @SubscribeEvent
    public void onServerChat(ServerChatEvent event) {
        plugin.onServerChat(event);
    }

    @SubscribeEvent
    public void onRegisterCommands(RegisterCommandsEvent event) {
        plugin.onRegisterCommands(event);
    }

    @SubscribeEvent
    public void onServerStarted(ServerStartedEvent event) {
        plugin.onServerStarted(event);
    }

    @SubscribeEvent
    public void onServerStopping(ServerStoppingEvent event) {
        plugin.onServerStopping(event);
    }
}


