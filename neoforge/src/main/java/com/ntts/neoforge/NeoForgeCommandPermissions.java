package com.ntts.neoforge;

import net.minecraft.commands.CommandSourceStack;

final class NeoForgeCommandPermissions {
    private NeoForgeCommandPermissions() {
    }

    static boolean isOperator(CommandSourceStack source) {
        return source.hasPermission(2);
    }
}


