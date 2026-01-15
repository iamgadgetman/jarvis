package com.yourname.jarvis.quests;

import com.yourname.jarvis.Jarvis;
import org.bukkit.entity.Player;

/**
 * QuestSystem - Stub implementation for v0.0.5
 * 
 * This is a minimal stub to allow compilation.
 * Full implementation can be added in future versions.
 */
public class QuestSystem {
    
    private final Jarvis plugin;
    
    public QuestSystem(Jarvis plugin) {
        this.plugin = plugin;
    }
    
    public void generateAndAssignQuest(Player player) {
        player.sendMessage("§cQuest system not yet implemented in v0.0.5");
        player.sendMessage("§7This feature is planned for a future release");
    }
    
    public void showQuestStatus(Player player) {
        player.sendMessage("§cQuest system not yet implemented in v0.0.5");
    }
    
    public void clearQuests(Player player) {
        player.sendMessage("§cQuest system not yet implemented in v0.0.5");
    }
}
