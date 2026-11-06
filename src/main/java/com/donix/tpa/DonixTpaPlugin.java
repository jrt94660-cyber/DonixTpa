package com.donix.tpa;

import io.papermc.paper.dialog.Dialog;
import io.papermc.paper.registry.data.dialog.ActionButton;
import io.papermc.paper.registry.data.dialog.DialogBase;
import io.papermc.paper.registry.data.dialog.action.DialogAction;
import io.papermc.paper.registry.data.dialog.body.DialogBody;
import io.papermc.paper.registry.data.dialog.type.DialogType;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.event.ClickCallback;
import net.kyori.adventure.text.event.ClickEvent;
import net.kyori.adventure.text.event.HoverEvent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.EntityDamageEvent;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerMoveEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.bukkit.inventory.ItemFlag;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.inventory.meta.SkullMeta;
import org.bukkit.plugin.java.JavaPlugin;
import org.bukkit.scheduler.BukkitRunnable;
import org.bukkit.scheduler.BukkitTask;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

public final class DonixTpaPlugin extends JavaPlugin implements Listener, CommandExecutor, TabCompleter {
    private static final LegacyComponentSerializer LEGACY = LegacyComponentSerializer.legacyAmpersand();

    private final Map<UUID, LinkedHashMap<UUID, TeleportRequest>> incoming = new HashMap<>();
    private final Map<UUID, LinkedHashMap<UUID, TeleportRequest>> outgoing = new HashMap<>();
    private final Map<UUID, BukkitTask> expiryTasks = new HashMap<>();
    private final Map<UUID, Long> lastRequestAt = new HashMap<>();
    private final Map<UUID, TeleportWarmup> warmups = new HashMap<>();

    private File preferencesFile;
    private YamlConfiguration preferences;

    @Override
    public void onEnable() {
        saveDefaultConfig();
        loadPreferences();
        getServer().getPluginManager().registerEvents(this, this);

        String[] commands = {
                "tpa", "tpahere", "tpaccept", "tpdeny", "tpacancel",
                "tpatoggle", "tpaheretoggle", "tpaguitoggle", "tpauto", "dtpa"
        };
        for (String name : commands) {
            var pluginCommand = getCommand(name);
            if (pluginCommand == null) {
                getLogger().severe("Command '" + name + "' is missing from plugin.yml.");
                continue;
            }
            pluginCommand.setExecutor(this);
            pluginCommand.setTabCompleter(this);
        }

        getLogger().info("DonixTpa 1.0.1 enabled. Teleport requests are ready.");
    }

    @Override
    public void onDisable() {
        for (BukkitTask task : expiryTasks.values()) {
            task.cancel();
        }
        for (TeleportWarmup warmup : warmups.values()) {
            if (warmup.task != null) {
                warmup.task.cancel();
            }
        }
        expiryTasks.clear();
        warmups.clear();
        savePreferences();
    }

    private void loadPreferences() {
        if (!getDataFolder().exists() && !getDataFolder().mkdirs()) {
            getLogger().warning("Could not create plugin data folder.");
        }
        preferencesFile = new File(getDataFolder(), "players.yml");
        if (!preferencesFile.exists()) {
            try {
                if (!preferencesFile.createNewFile()) {
                    getLogger().warning("Could not create players.yml.");
                }
            } catch (IOException exception) {
                getLogger().severe("Could not create players.yml: " + exception.getMessage());
            }
        }
        preferences = YamlConfiguration.loadConfiguration(preferencesFile);
    }

    private void savePreferences() {
        if (preferences == null || preferencesFile == null) {
            return;
        }
        try {
            preferences.save(preferencesFile);
        } catch (IOException exception) {
            getLogger().severe("Could not save players.yml: " + exception.getMessage());
        }
    }

    private boolean preference(UUID playerId, String key, boolean fallback) {
        return preferences.getBoolean(playerId + "." + key, fallback);
    }

    private void setPreference(UUID playerId, String key, boolean value) {
        preferences.set(playerId + "." + key, value);
        savePreferences();
    }

    private boolean hasBypass(Player player, String permission) {
        return player.hasPermission("donix.tpa.admin") || player.hasPermission(permission);
    }

    private boolean guiEnabled(Player player) {
        return preference(player.getUniqueId(), "gui",
                getConfig().getBoolean("settings.use-gui-by-default", true));
    }

    private void sendMessage(CommandSender sender, String key, String... replacements) {
        String prefix = getConfig().getString("messages.prefix", "&8[&6Donix&8] &r");
        String message = getConfig().getString("messages." + key, "&cMissing message: " + key);
        sender.sendMessage(color(prefix + apply(message, replacements)));
    }

    private void sendActionBar(Player player, String key, String... replacements) {
        String message = getConfig().getString("messages." + key, "");
        player.sendActionBar(LEGACY.deserialize(apply(message, replacements)));
    }

    private String apply(String input, String... replacements) {
        String output = input;
        for (int i = 0; i + 1 < replacements.length; i += 2) {
            output = output.replace(replacements[i], replacements[i + 1]);
        }
        return output;
    }

    private String color(String input) {
        return ChatColor.translateAlternateColorCodes('&', input);
    }

    private void playSound(Player player, String key) {
        if (!getConfig().getBoolean("sounds.enabled", true)) {
            return;
        }
        String soundName = getConfig().getString("sounds." + key, "");
        if (soundName == null || soundName.isBlank()) {
            return;
        }
        try {
            player.playSound(player.getLocation(), Sound.valueOf(soundName.toUpperCase(Locale.ROOT)), 0.8f, 1.0f);
        } catch (IllegalArgumentException exception) {
            getLogger().warning("Invalid sound configured at sounds." + key + ": " + soundName);
        }
    }

    @Override
    public boolean onCommand(CommandSender sender, Command command, String label, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);

        if (name.equals("dtpa")) {
            if (!sender.hasPermission("donix.tpa.admin")) {
                sendMessage(sender, "no-permission");
                return true;
            }
            if ((args.length == 1 && args[0].equalsIgnoreCase("reload"))
                    || label.equalsIgnoreCase("tpareload")) {
                reloadConfig();
                sendMessage(sender, "reload-success");
            } else {
                sender.sendMessage(color("&eUsage: /dtpa reload"));
            }
            return true;
        }

        if (!(sender instanceof Player player)) {
            sendMessage(sender, "only-players");
            return true;
        }
        if (!player.hasPermission("donix.tpa.use")) {
            sendMessage(player, "no-permission");
            return true;
        }

        switch (name) {
            case "tpa" -> handleRequestCommand(player, args, RequestType.TO);
            case "tpahere" -> handleRequestCommand(player, args, RequestType.HERE);
            case "tpaccept" -> handleAcceptCommand(player, args);
            case "tpdeny" -> handleDenyCommand(player, args);
            case "tpacancel" -> handleCancelCommand(player, args);
            case "tpatoggle" -> togglePreference(player, "tpa", "toggle-tpa-enabled", "toggle-tpa-disabled", true);
            case "tpaheretoggle" -> togglePreference(player, "tpahere", "toggle-tpahere-enabled", "toggle-tpahere-disabled", true);
            case "tpaguitoggle" -> togglePreference(player, "gui",
                    "toggle-gui-enabled", "toggle-gui-disabled",
                    getConfig().getBoolean("settings.use-gui-by-default", true));
            case "tpauto" -> togglePreference(player, "auto", "toggle-auto-enabled", "toggle-auto-disabled", false);
            default -> {
                return false;
            }
        }
        return true;
    }

    private void togglePreference(Player player, String key, String enabledMessage,
                                  String disabledMessage, boolean defaultValue) {
        boolean enabled = !preference(player.getUniqueId(), key, defaultValue);
        setPreference(player.getUniqueId(), key, enabled);
        sendMessage(player, enabled ? enabledMessage : disabledMessage);
        playSound(player, "gui-click");
    }

    private void handleRequestCommand(Player sender, String[] args, RequestType type) {
        if (type == RequestType.TO && args.length == 0) {
            if (isBedrockPlayer(sender)) {
                openBedrockPlayerSelector(sender);
            } else {
                openNativePlayerSelector(sender);
            }
            return;
        }

        if (args.length != 1) {
            sendMessage(sender, type == RequestType.TO ? "usage-tpa" : "usage-tpahere");
            return;
        }

        Player target = Bukkit.getPlayerExact(args[0]);
        if (target == null) {
            sendMessage(sender, "player-not-found", "%player%", args[0]);
            return;
        }
        if (target.getUniqueId().equals(sender.getUniqueId())) {
            sendMessage(sender, "cannot-tpa-self");
            return;
        }

        if (isBedrockPlayer(sender)) {
            if (type == RequestType.TO) {
                openBedrockActionSelector(sender, target);
            } else {
                openRequestConfirmation(sender, target, type);
            }
        } else if (type == RequestType.TO) {
            openNativeTypeSelector(sender, target);
        } else {
            openNativeConfirmDialog(sender, target.getUniqueId(), target.getName(), type);
        }
    }

    private void createRequest(Player sender, Player target, RequestType type) {
        boolean limitBypass = hasBypass(sender, "donix.tpa.limit.bypass");
        boolean targetLimitBypass = hasBypass(target, "donix.tpa.limit.bypass");

        long cooldownMillis = getConfig().getLong("settings.request-cooldown-seconds", 10L) * 1000L;
        long lastRequest = lastRequestAt.getOrDefault(sender.getUniqueId(), 0L);
        if (!hasBypass(sender, "donix.tpa.cooldown.bypass") && cooldownMillis > 0L
                && System.currentTimeMillis() - lastRequest < cooldownMillis) {
            long remaining = Math.max(1L, (cooldownMillis - (System.currentTimeMillis() - lastRequest) + 999L) / 1000L);
            sendMessage(sender, "cooldown-active", "%time%", String.valueOf(remaining));
            return;
        }

        if (type == RequestType.TO && !preference(target.getUniqueId(), "tpa", true) && !limitBypass) {
            sendMessage(sender, "target-tpa-disabled");
            return;
        }
        if (type == RequestType.HERE && !preference(target.getUniqueId(), "tpahere", true) && !limitBypass) {
            sendMessage(sender, "target-tpahere-disabled");
            return;
        }

        Map<UUID, TeleportRequest> currentOutgoing = outgoing.get(sender.getUniqueId());
        Map<UUID, TeleportRequest> currentIncoming = incoming.get(target.getUniqueId());

        if (currentOutgoing != null && currentOutgoing.containsKey(target.getUniqueId())) {
            sendMessage(sender, "already-pending", "%player%", target.getName());
            return;
        }

        int outgoingLimit = Math.max(1, getConfig().getInt("settings.max-outgoing-requests", 3));
        int incomingLimit = Math.max(1, getConfig().getInt("settings.max-incoming-requests", 5));
        int outgoingCount = currentOutgoing == null ? 0 : currentOutgoing.size();
        int incomingCount = currentIncoming == null ? 0 : currentIncoming.size();
        if (!limitBypass && outgoingCount >= outgoingLimit) {
            sendMessage(sender, "outgoing-limit");
            return;
        }
        if (!targetLimitBypass && incomingCount >= incomingLimit) {
            sendMessage(sender, "incoming-limit");
            return;
        }

        LinkedHashMap<UUID, TeleportRequest> senderRequests =
                outgoing.computeIfAbsent(sender.getUniqueId(), ignored -> new LinkedHashMap<>());
        LinkedHashMap<UUID, TeleportRequest> targetRequests =
                incoming.computeIfAbsent(target.getUniqueId(), ignored -> new LinkedHashMap<>());

        int expiration = Math.max(1, getConfig().getInt("settings.request-expire-seconds", 60));
        TeleportRequest request = new TeleportRequest(
                UUID.randomUUID(), sender.getUniqueId(), target.getUniqueId(), type,
                System.currentTimeMillis() + expiration * 1000L
        );

        senderRequests.put(target.getUniqueId(), request);
        targetRequests.put(sender.getUniqueId(), request);
        lastRequestAt.put(sender.getUniqueId(), System.currentTimeMillis());

        BukkitTask expiry = Bukkit.getScheduler().runTaskLater(this,
                () -> expireRequest(request.id), expiration * 20L);
        expiryTasks.put(request.id, expiry);

        sendMessage(sender, type == RequestType.TO ? "request-sent" : "request-sent-here",
                "%target%", target.getName());
        playSound(sender, "request-sent");

        if (preference(target.getUniqueId(), "auto", false)) {
            sendMessage(target, "auto-accept-notify", "%sender%", sender.getName());
            acceptRequest(target, request);
            return;
        }

        sendMessage(target, type == RequestType.TO ? "request-received-tpa" : "request-received-tpahere",
                "%sender%", sender.getName());
        sendRequestButtons(target, sender.getName());
        playSound(target, "request-received");
    }

    private void sendRequestButtons(Player target, String senderName) {
        Component accept = Component.text("[✔ Accept]", NamedTextColor.GREEN)
                .clickEvent(ClickEvent.runCommand("/tpaccept " + senderName + " confirm"))
                .hoverEvent(HoverEvent.showText(Component.text("Accept " + senderName + "'s request")));
        Component deny = Component.text("[✖ Deny]", NamedTextColor.RED)
                .clickEvent(ClickEvent.runCommand("/tpdeny " + senderName))
                .hoverEvent(HoverEvent.showText(Component.text("Deny " + senderName + "'s request")));
        target.sendMessage(LEGACY.deserialize(getConfig().getString("messages.prefix", "&8[&6Donix&8] &r"))
                .append(Component.text("Click an option: ", NamedTextColor.GRAY))
                .append(accept)
                .append(Component.text("  "))
                .append(deny));
    }

    private void handleAcceptCommand(Player receiver, String[] args) {
        boolean quick = args.length > 1 && args[1].equalsIgnoreCase("confirm");
        if (args.length > 1 && !quick) {
            sendMessage(receiver, "no-pending-requests");
            return;
        }

        TeleportRequest request = args.length == 0
                ? newestIncoming(receiver.getUniqueId())
                : findIncomingByName(receiver.getUniqueId(), args[0]);

        if (request == null) {
            sendMessage(receiver, "no-pending-requests");
            return;
        }

        if (!quick && guiEnabled(receiver)) {
            if (args.length == 0 && incoming.getOrDefault(receiver.getUniqueId(), new LinkedHashMap<>()).size() > 1) {
                openIncomingList(receiver);
            } else {
                Player requester = Bukkit.getPlayer(request.senderId);
                if (requester != null) {
                    openRequestAcceptance(receiver, requester, request);
                } else {
                    removeRequest(request);
                    sendMessage(receiver, "gui-no-request");
                }
            }
            return;
        }
        acceptRequest(receiver, request);
    }

    private void handleDenyCommand(Player receiver, String[] args) {
        if (args.length > 1) {
            sendMessage(receiver, "no-pending-requests");
            return;
        }
        TeleportRequest request = args.length == 0
                ? newestIncoming(receiver.getUniqueId())
                : findIncomingByName(receiver.getUniqueId(), args[0]);
        if (request == null) {
            sendMessage(receiver, "no-pending-requests");
            return;
        }
        denyRequest(receiver, request);
    }

    private void handleCancelCommand(Player sender, String[] args) {
        if (args.length > 1) {
            sendMessage(sender, "no-outgoing-requests");
            return;
        }
        TeleportRequest request = args.length == 0
                ? newestOutgoing(sender.getUniqueId())
                : findOutgoingByName(sender.getUniqueId(), args[0]);
        if (request == null) {
            sendMessage(sender, "no-outgoing-requests");
            return;
        }

        Player target = Bukkit.getPlayer(request.receiverId);
        removeRequest(request);
        sendMessage(sender, "request-cancelled", "%target%", target == null ? "player" : target.getName());
        if (target != null) {
            sendMessage(target, "request-cancelled-by-sender", "%sender%", sender.getName());
        }
    }

    private TeleportRequest newestIncoming(UUID receiverId) {
        Map<UUID, TeleportRequest> requests = incoming.get(receiverId);
        if (requests == null || requests.isEmpty()) {
            return null;
        }
        return requests.values().stream().max(Comparator.comparingLong(request -> request.expiresAt)).orElse(null);
    }

    private TeleportRequest newestOutgoing(UUID senderId) {
        Map<UUID, TeleportRequest> requests = outgoing.get(senderId);
        if (requests == null || requests.isEmpty()) {
            return null;
        }
        return requests.values().stream().max(Comparator.comparingLong(request -> request.expiresAt)).orElse(null);
    }

    private TeleportRequest findIncomingByName(UUID receiverId, String senderName) {
        Map<UUID, TeleportRequest> requests = incoming.get(receiverId);
        if (requests == null) {
            return null;
        }
        for (TeleportRequest request : requests.values()) {
            Player sender = Bukkit.getPlayer(request.senderId);
            if (sender != null && sender.getName().equalsIgnoreCase(senderName)) {
                return request;
            }
        }
        return null;
    }

    private TeleportRequest findOutgoingByName(UUID senderId, String targetName) {
        Map<UUID, TeleportRequest> requests = outgoing.get(senderId);
        if (requests == null) {
            return null;
        }
        for (TeleportRequest request : requests.values()) {
            Player target = Bukkit.getPlayer(request.receiverId);
            if (target != null && target.getName().equalsIgnoreCase(targetName)) {
                return request;
            }
        }
        return null;
    }

    private TeleportRequest findRequest(UUID requestId) {
        for (Map<UUID, TeleportRequest> requests : incoming.values()) {
            for (TeleportRequest request : requests.values()) {
                if (request.id.equals(requestId)) {
                    return request;
                }
            }
        }
        return null;
    }

    private void acceptRequest(Player receiver, TeleportRequest request) {
        TeleportRequest current = findRequest(request.id);
        if (current == null || !current.receiverId.equals(receiver.getUniqueId())) {
            sendMessage(receiver, "gui-no-request");
            return;
        }

        Player sender = Bukkit.getPlayer(current.senderId);
        if (sender == null) {
            removeRequest(current);
            sendMessage(receiver, "request-cancelled-offline");
            return;
        }

        removeRequest(current);
        sendMessage(receiver, "request-accepted-target", "%sender%", sender.getName());
        sendMessage(sender, "request-accepted-sender", "%target%", receiver.getName());
        playSound(receiver, "request-accepted");
        playSound(sender, "request-accepted");

        Player movingPlayer = current.type == RequestType.TO ? sender : receiver;
        Player destinationPlayer = current.type == RequestType.TO ? receiver : sender;
        startTeleportWarmup(movingPlayer, destinationPlayer);
    }

    private void denyRequest(Player receiver, TeleportRequest request) {
        TeleportRequest current = findRequest(request.id);
        if (current == null || !current.receiverId.equals(receiver.getUniqueId())) {
            sendMessage(receiver, "gui-no-request");
            return;
        }
        Player sender = Bukkit.getPlayer(current.senderId);
        removeRequest(current);
        if (sender != null) {
            sendMessage(sender, "request-denied-sender", "%target%", receiver.getName());
        }
        sendMessage(receiver, "request-denied-target", "%sender%", sender == null ? "player" : sender.getName());
        playSound(receiver, "request-denied");
        if (sender != null) {
            playSound(sender, "request-denied");
        }
    }

    private void removeRequest(TeleportRequest request) {
        LinkedHashMap<UUID, TeleportRequest> sent = outgoing.get(request.senderId);
        if (sent != null) {
            sent.remove(request.receiverId);
            if (sent.isEmpty()) {
                outgoing.remove(request.senderId);
            }
        }

        LinkedHashMap<UUID, TeleportRequest> received = incoming.get(request.receiverId);
        if (received != null) {
            received.remove(request.senderId);
            if (received.isEmpty()) {
                incoming.remove(request.receiverId);
            }
        }

        BukkitTask task = expiryTasks.remove(request.id);
        if (task != null) {
            task.cancel();
        }
    }

    private void expireRequest(UUID requestId) {
        TeleportRequest request = findRequest(requestId);
        if (request == null) {
            return;
        }
        Player sender = Bukkit.getPlayer(request.senderId);
        Player receiver = Bukkit.getPlayer(request.receiverId);
        removeRequest(request);
        if (sender != null && receiver != null) {
            sendMessage(sender, "request-expired-sender", "%target%", receiver.getName());
            sendMessage(receiver, "request-expired-target", "%sender%", sender.getName());
        } else if (sender != null) {
            sendMessage(sender, "request-expired-sender", "%target%", "player");
        } else if (receiver != null) {
            sendMessage(receiver, "request-expired-target", "%sender%", "player");
        }
    }

    private void startTeleportWarmup(Player movingPlayer, Player destinationPlayer) {
        if (warmups.containsKey(movingPlayer.getUniqueId())) {
            sendMessage(movingPlayer, "teleport-already-active");
            return;
        }

        int delay = Math.max(0, getConfig().getInt("settings.teleport-delay-seconds", 5));
        if (delay == 0 || hasBypass(movingPlayer, "donix.tpa.delay.bypass")) {
            performTeleport(movingPlayer, destinationPlayer);
            return;
        }

        sendMessage(destinationPlayer, "teleport-started-other", "%player%", movingPlayer.getName());
        final int[] remaining = {delay};

        BukkitRunnable countdown = new BukkitRunnable() {
            @Override
            public void run() {
                if (!movingPlayer.isOnline() || !destinationPlayer.isOnline()) {
                    cancelWarmup(movingPlayer.getUniqueId(), "teleport-cancelled-offline");
                    return;
                }
                if (remaining[0] <= 0) {
                    warmups.remove(movingPlayer.getUniqueId());
                    cancel();
                    performTeleport(movingPlayer, destinationPlayer);
                    return;
                }

                sendActionBar(movingPlayer, "teleport-countdown", "%time%", String.valueOf(remaining[0]));
                playSound(movingPlayer, "countdown-tick");
                remaining[0]--;
            }
        };

        BukkitTask task = countdown.runTaskTimer(this, 0L, 20L);
        warmups.put(movingPlayer.getUniqueId(),
                new TeleportWarmup(destinationPlayer.getUniqueId(), movingPlayer.getLocation().clone(), task));
    }

    private void performTeleport(Player movingPlayer, Player destinationPlayer) {
        if (!movingPlayer.isOnline() || !destinationPlayer.isOnline()) {
            if (movingPlayer.isOnline()) {
                sendMessage(movingPlayer, "teleport-cancelled-offline");
            }
            return;
        }

        movingPlayer.closeInventory();
        destinationPlayer.closeInventory();
        movingPlayer.teleportAsync(destinationPlayer.getLocation().clone()).whenComplete((success, throwable) -> {
            if (!movingPlayer.isOnline()) {
                return;
            }
            if (throwable == null && Boolean.TRUE.equals(success)) {
                sendMessage(movingPlayer, "teleport-success");
                playSound(movingPlayer, "teleport-success");
            } else {
                sendMessage(movingPlayer, "teleport-failed");
            }
        });
    }

    private void cancelWarmup(UUID movingPlayerId, String messageKey) {
        TeleportWarmup warmup = warmups.remove(movingPlayerId);
        if (warmup == null) {
            return;
        }
        if (warmup.task != null) {
            warmup.task.cancel();
        }

        Player movingPlayer = Bukkit.getPlayer(movingPlayerId);
        Player destination = Bukkit.getPlayer(warmup.destinationId);
        if (movingPlayer != null) {
            sendMessage(movingPlayer, messageKey);
            playSound(movingPlayer, "teleport-cancelled");
        }
        if (destination != null && movingPlayer != null) {
            sendMessage(destination, "teleport-cancelled-other-moved", "%player%", movingPlayer.getName());
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerMove(PlayerMoveEvent event) {
        if (!getConfig().getBoolean("settings.cancel-on-move", true) || event.getTo() == null) {
            return;
        }
        if (event.getFrom().getWorld() != event.getTo().getWorld()
                || Double.compare(event.getFrom().getX(), event.getTo().getX()) != 0
                || Double.compare(event.getFrom().getY(), event.getTo().getY()) != 0
                || Double.compare(event.getFrom().getZ(), event.getTo().getZ()) != 0) {
            cancelWarmup(event.getPlayer().getUniqueId(), "teleport-cancelled-moved");
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPlayerDamage(EntityDamageEvent event) {
        if (!getConfig().getBoolean("settings.cancel-on-damage", true)
                || !(event.getEntity() instanceof Player player)) {
            return;
        }
        cancelWarmup(player.getUniqueId(), "teleport-cancelled-damage");
    }

    @EventHandler
    public void onPlayerChangedWorld(PlayerChangedWorldEvent event) {
        cancelWarmup(event.getPlayer().getUniqueId(), "teleport-cancelled-moved");
    }

    @EventHandler
    public void onPlayerQuit(PlayerQuitEvent event) {
        UUID playerId = event.getPlayer().getUniqueId();

        Set<UUID> toCancel = new HashSet<>();
        for (Map.Entry<UUID, TeleportWarmup> entry : warmups.entrySet()) {
            if (entry.getKey().equals(playerId) || entry.getValue().destinationId.equals(playerId)) {
                toCancel.add(entry.getKey());
            }
        }
        for (UUID movingId : toCancel) {
            cancelWarmup(movingId, "teleport-cancelled-offline");
        }

        List<TeleportRequest> affected = new ArrayList<>();
        for (Map<UUID, TeleportRequest> requests : incoming.values()) {
            for (TeleportRequest request : requests.values()) {
                if (request.senderId.equals(playerId) || request.receiverId.equals(playerId)) {
                    affected.add(request);
                }
            }
        }
        for (TeleportRequest request : affected) {
            UUID otherId = request.senderId.equals(playerId) ? request.receiverId : request.senderId;
            removeRequest(request);
            Player other = Bukkit.getPlayer(otherId);
            if (other != null) {
                sendMessage(other, "request-cancelled-offline");
            }
        }
    }

    private boolean isBedrockPlayer(Player player) {
        if (Bukkit.getPluginManager().getPlugin("floodgate") != null) {
            try {
                Class<?> apiClass = Class.forName("org.geysermc.floodgate.api.FloodgateApi");
                Object api = apiClass.getMethod("getInstance").invoke(null);
                Object result = apiClass.getMethod("isFloodgatePlayer", UUID.class)
                        .invoke(api, player.getUniqueId());
                if (Boolean.TRUE.equals(result)) {
                    return true;
                }
            } catch (ReflectiveOperationException | LinkageError exception) {
                getLogger().fine("Floodgate API lookup was unavailable; using configured Bedrock name prefix.");
            }
        }

        String prefix = getConfig().getString("settings.bedrock-name-prefix", ".");
        return prefix != null && !prefix.isEmpty() && player.getName().startsWith(prefix);
    }

    private Component dialogText(String text) {
        return LEGACY.deserialize(text == null ? "" : text);
    }

    private ActionButton dialogButton(String label, String tooltip, int width, Consumer<Player> callback) {
        DialogAction action = callback == null ? null : DialogAction.customClick((response, audience) -> {
            if (audience instanceof Player clicker) {
                Bukkit.getScheduler().runTask(this, () -> {
                    if (clicker.isOnline()) {
                        callback.accept(clicker);
                    }
                });
            }
        }, ClickCallback.Options.builder()
                .uses(1)
                .lifetime(ClickCallback.DEFAULT_LIFETIME)
                .build());

        return ActionButton.create(dialogText(label),
                tooltip == null ? null : dialogText(tooltip), width, action);
    }

    private Dialog createNativeDialog(String title, List<String> lines, DialogType type) {
        List<DialogBody> body = lines.stream()
                .map(line -> (DialogBody) DialogBody.plainMessage(dialogText(line)))
                .toList();

        return Dialog.create(builder -> builder.empty()
                .base(DialogBase.builder(dialogText(title))
                        .canCloseWithEscape(true)
                        .body(body)
                        .build())
                .type(type));
    }

    private void openNativePlayerSelector(Player viewer) {
        List<Player> online = Bukkit.getOnlinePlayers().stream()
                .filter(target -> !target.getUniqueId().equals(viewer.getUniqueId()))
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();

        if (online.isEmpty()) {
            sendMessage(viewer, "no-online-players");
            return;
        }

        List<ActionButton> buttons = new ArrayList<>();
        int width = getConfig().getInt("dialogs.player-button-width", 120);
        for (Player target : online) {
            UUID targetId = target.getUniqueId();
            String targetName = target.getName();
            buttons.add(dialogButton("&b" + targetName,
                    "&7Select " + targetName, width, clicker -> {
                        Player currentTarget = Bukkit.getPlayer(targetId);
                        if (currentTarget == null) {
                            sendMessage(clicker, "player-not-found", "%player%", targetName);
                            openNativePlayerSelector(clicker);
                            return;
                        }
                        openNativeTypeSelector(clicker, currentTarget);
                    }));
        }

        ActionButton cancel = dialogButton("&cCancel", "&7Close this menu.", 90, null);
        Dialog dialog = createNativeDialog(
                getConfig().getString("dialogs.player-list-title", "&8ᴛᴇʟᴇᴘᴏʀᴛ"),
                List.of(getConfig().getString("dialogs.player-list-description", "&7Select an online player.")),
                DialogType.multiAction(buttons, cancel, 2));
        viewer.showDialog(dialog);
    }

    private void openNativeTypeSelector(Player viewer, Player target) {
        UUID targetId = target.getUniqueId();
        String targetName = target.getName();
        int width = getConfig().getInt("dialogs.action-button-width", 104);

        ActionButton tpa = dialogButton("&bTPA", "&7Request to teleport to " + targetName + ".", width, clicker -> {
            Player currentTarget = Bukkit.getPlayer(targetId);
            if (currentTarget == null) {
                sendMessage(clicker, "player-not-found", "%player%", targetName);
                openNativePlayerSelector(clicker);
                return;
            }
            openNativeConfirmDialog(clicker, targetId, targetName, RequestType.TO);
        });

        ActionButton tpahere = dialogButton("&eTPAHERE", "&7Ask " + targetName + " to teleport to you.", width, clicker -> {
            Player currentTarget = Bukkit.getPlayer(targetId);
            if (currentTarget == null) {
                sendMessage(clicker, "player-not-found", "%player%", targetName);
                openNativePlayerSelector(clicker);
                return;
            }
            openNativeConfirmDialog(clicker, targetId, targetName, RequestType.HERE);
        });

        ActionButton cancel = dialogButton("&cCancel", "&7Return to the player list.", 90,
                this::openNativePlayerSelector);
        Dialog dialog = createNativeDialog(
                getConfig().getString("dialogs.action-title", "&8ᴛᴇʟᴇᴘᴏʀᴛ ᴏᴘᴛɪᴏɴs"),
                List.of("&7Selected player: &f" + targetName,
                        "&7Choose which teleport request to send."),
                DialogType.multiAction(List.of(tpa, tpahere), cancel, 2));
        viewer.showDialog(dialog);
    }

    private void openNativeConfirmDialog(Player viewer, UUID targetId, String targetName, RequestType type) {
        String label = type == RequestType.TO ? "TPA" : "TPAHERE";
        String description = type == RequestType.TO
                ? "&7Send a request to teleport to &f" + targetName + "&7?"
                : "&7Ask &f" + targetName + " &7to teleport to you?";

        int width = getConfig().getInt("dialogs.confirm-button-width", 92);
        ActionButton confirm = dialogButton("&aConfirm", "&7Send this request.", width, clicker -> {
            Player target = Bukkit.getPlayer(targetId);
            if (target == null) {
                sendMessage(clicker, "player-not-found", "%player%", targetName);
                openNativePlayerSelector(clicker);
                return;
            }
            createRequest(clicker, target, type);
        });
        ActionButton cancel = dialogButton("&cCancel", "&7Cancel this request.", width, clicker -> {
            Player currentTarget = Bukkit.getPlayer(targetId);
            if (currentTarget == null) {
                openNativePlayerSelector(clicker);
            } else {
                openNativeTypeSelector(clicker, currentTarget);
            }
        });

        Dialog dialog = createNativeDialog(
                getConfig().getString("dialogs.confirm-title", "&8ᴄᴏɴғɪʀᴍ ᴛᴇʟᴇᴘᴏʀᴛ"),
                List.of("&7Player: &f" + targetName,
                        "&7Request: &f" + label,
                        description),
                DialogType.confirmation(confirm, cancel));
        viewer.showDialog(dialog);
    }

    private void openBedrockPlayerSelector(Player viewer) {
        List<Player> online = Bukkit.getOnlinePlayers().stream()
                .filter(target -> !target.getUniqueId().equals(viewer.getUniqueId()))
                .sorted(Comparator.comparing(Player::getName, String.CASE_INSENSITIVE_ORDER))
                .toList();
        if (online.isEmpty()) {
            sendMessage(viewer, "no-online-players");
            return;
        }

        MenuData data = new MenuData(MenuType.PLAYER_SELECTION, viewer.getUniqueId(), null, null, null);
        MenuHolder holder = new MenuHolder(data);
        Inventory inventory = Bukkit.createInventory(holder, 54, color("&8ᴛᴇʟᴇᴘᴏʀᴛ"));
        holder.inventory = inventory;
        fillInventory(inventory);

        int slot = 9;
        for (Player target : online) {
            if (slot > 44) {
                break;
            }
            inventory.setItem(slot, createItem(Material.PLAYER_HEAD, "&b" + target.getName(),
                    List.of("&7Click to select this player."), target.getUniqueId()));
            data.entries.put(slot, target.getUniqueId());
            slot++;
        }

        inventory.setItem(49, createItem(Material.BARRIER, "&c&lCLOSE",
                List.of("&7Close this menu."), null));
        viewer.openInventory(inventory);
    }

    private void openBedrockActionSelector(Player viewer, Player target) {
        MenuData data = new MenuData(MenuType.ACTION_SELECTION, viewer.getUniqueId(),
                target.getUniqueId(), null, null);
        MenuHolder holder = new MenuHolder(data);
        Inventory inventory = Bukkit.createInventory(holder, 27, color("&8ᴛᴇʟᴇᴘᴏʀᴛ ᴏᴘᴛɪᴏɴs"));
        holder.inventory = inventory;
        fillInventory(inventory);

        inventory.setItem(11, createItem(Material.ENDER_PEARL, "&b&lTPA",
                List.of("&7Request to teleport to &f" + target.getName()), null));
        inventory.setItem(15, createItem(Material.FEATHER, "&e&lTPAHERE",
                List.of("&7Ask &f" + target.getName() + " &7to teleport to you."), null));
        inventory.setItem(22, createItem(Material.BARRIER, "&c&lCANCEL",
                List.of("&7Return to the player list."), null));
        viewer.openInventory(inventory);
    }

    private void openRequestConfirmation(Player viewer, Player target, RequestType type) {
        MenuData data = new MenuData(MenuType.SEND_CONFIRMATION, viewer.getUniqueId(),
                target.getUniqueId(), null, type);
        Inventory inventory = createDetailInventory(data, target, type, false);
        viewer.openInventory(inventory);
    }

    private void openRequestAcceptance(Player viewer, Player requester, TeleportRequest request) {
        MenuData data = new MenuData(MenuType.ACCEPT_CONFIRMATION, viewer.getUniqueId(),
                requester.getUniqueId(), request.id, request.type);
        Inventory inventory = createDetailInventory(data, requester, request.type, true);
        viewer.openInventory(inventory);
    }

    private Inventory createDetailInventory(MenuData data, Player otherPlayer, RequestType type, boolean accepting) {
        int size = Math.max(27, Math.min(54, getConfig().getInt("gui.size", 27)));
        size = size - (size % 9);
        MenuHolder holder = new MenuHolder(data);
        String title = getConfig().getString(accepting ? "gui.accept-title" : "gui.confirm-title",
                accepting ? "&8ACCEPT REQUEST" : "&8CONFIRM REQUEST");
        Inventory inventory = Bukkit.createInventory(holder, size, color(title));
        holder.inventory = inventory;

        fillInventory(inventory);
        int cancelSlot = configuredSlot("gui.cancel-slot", 10, size);
        int playerSlot = configuredSlot("gui.player-slot", 12, size);
        int typeSlot = configuredSlot("gui.request-type-slot", 13, size);
        int dimensionSlot = configuredSlot("gui.dimension-slot", 14, size);
        int confirmSlot = configuredSlot("gui.confirm-slot", 16, size);

        inventory.setItem(cancelSlot, createItem(Material.RED_STAINED_GLASS_PANE,
                getConfig().getString(accepting ? "items.deny-name" : "items.cancel-name",
                        accepting ? "&c&lDENY REQUEST" : "&c&lCANCEL"),
                getConfig().getStringList(accepting ? "items.deny-lore" : "items.cancel-lore"), null));

        ItemStack head = createItem(Material.PLAYER_HEAD,
                "&b&l" + otherPlayer.getName(),
                List.of("&7" + (accepting ? "Request from " : "Request to ") + otherPlayer.getName()),
                otherPlayer.getUniqueId());
        inventory.setItem(playerSlot, head);

        Material typeMaterial = type == RequestType.TO ? Material.ENDER_PEARL : Material.FEATHER;
        String typeName = getConfig().getString(type == RequestType.TO
                ? "items.type-tpa-name" : "items.type-here-name", type.label);
        inventory.setItem(typeSlot, createItem(typeMaterial, typeName,
                List.of("&7" + type.description), null));

        inventory.setItem(dimensionSlot, createDimensionItem(otherPlayer));

        inventory.setItem(confirmSlot, createItem(Material.LIME_STAINED_GLASS_PANE,
                getConfig().getString(accepting ? "items.accept-name" : "items.confirm-name",
                        accepting ? "&a&lACCEPT REQUEST" : "&a&lCONFIRM"),
                getConfig().getStringList(accepting ? "items.accept-lore" : "items.confirm-lore"), null));

        return inventory;
    }

    private void openIncomingList(Player viewer) {
        List<TeleportRequest> requests = new ArrayList<>(
                incoming.getOrDefault(viewer.getUniqueId(), new LinkedHashMap<>()).values());
        if (requests.isEmpty()) {
            sendMessage(viewer, "request-list-empty");
            return;
        }

        MenuData data = new MenuData(MenuType.INCOMING_LIST, viewer.getUniqueId(), null, null, null);
        MenuHolder holder = new MenuHolder(data);
        int size = Math.max(27, Math.min(54, getConfig().getInt("gui.list-size", 54)));
        size = size - (size % 9);
        Inventory inventory = Bukkit.createInventory(holder, size,
                color(getConfig().getString("gui.list-title", "&8INCOMING REQUESTS")));
        holder.inventory = inventory;
        fillInventory(inventory);

        int[] slots = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25,
                28, 29, 30, 31, 32, 33, 34, 37, 38, 39, 40, 41, 42, 43};
        int index = 0;
        for (TeleportRequest request : requests) {
            if (index >= slots.length || slots[index] >= size) {
                break;
            }
            Player requester = Bukkit.getPlayer(request.senderId);
            if (requester == null) {
                continue;
            }
            long seconds = Math.max(0L, (request.expiresAt - System.currentTimeMillis() + 999L) / 1000L);
            String type = request.type == RequestType.TO ? "TPA" : "TPAHERE";
            List<String> lore = getConfig().getStringList("items.request-entry-lore").stream()
                    .map(line -> apply(line, "%type%", type, "%time%", String.valueOf(seconds)))
                    .map(line -> apply(line, "%player%", requester.getName()))
                    .toList();
            String displayName = apply(getConfig().getString("items.request-entry-name", "&b%player%"),
                    "%player%", requester.getName());
            inventory.setItem(slots[index], createItem(Material.PLAYER_HEAD, displayName, lore, requester.getUniqueId()));
            data.entries.put(slots[index], request.id);
            index++;
        }

        int closeSlot = configuredSlot("gui.close-slot", 49, size);
        inventory.setItem(closeSlot, createItem(Material.BARRIER,
                getConfig().getString("items.close-name", "&c&lCLOSE"),
                getConfig().getStringList("items.close-lore"), null));
        viewer.openInventory(inventory);
    }

    private void fillInventory(Inventory inventory) {
        if (!getConfig().getBoolean("gui.filler-enabled", true)) {
            return;
        }
        Material filler;
        try {
            filler = Material.valueOf(getConfig().getString("gui.filler-material", "GRAY_STAINED_GLASS_PANE")
                    .toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException exception) {
            filler = Material.GRAY_STAINED_GLASS_PANE;
        }
        ItemStack item = createItem(filler, getConfig().getString("gui.filler-name", " "), List.of(), null);
        for (int slot = 0; slot < inventory.getSize(); slot++) {
            inventory.setItem(slot, item);
        }
    }

    private ItemStack createDimensionItem(Player player) {
        World.Environment environment = player.getWorld().getEnvironment();
        Material material;
        String name;
        if (environment == World.Environment.NETHER) {
            material = Material.NETHERRACK;
            name = getConfig().getString("items.dimension-nether", "&cNether");
        } else if (environment == World.Environment.THE_END) {
            material = Material.END_STONE;
            name = getConfig().getString("items.dimension-end", "&dThe End");
        } else {
            material = Material.GRASS_BLOCK;
            name = getConfig().getString("items.dimension-overworld", "&aOverworld");
        }
        return createItem(material, name, List.of("&7Destination dimension"), null);
    }

    private ItemStack createItem(Material material, String displayName, List<String> lore, UUID skullOwner) {
        ItemStack item = new ItemStack(material);
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return item;
        }
        meta.setDisplayName(color(displayName));
        if (lore != null && !lore.isEmpty()) {
            meta.setLore(lore.stream().map(this::color).toList());
        }
        meta.addItemFlags(ItemFlag.HIDE_ATTRIBUTES);
        if (meta instanceof SkullMeta skullMeta && skullOwner != null) {
            OfflinePlayer owner = Bukkit.getOfflinePlayer(skullOwner);
            skullMeta.setOwningPlayer(owner);
        }
        item.setItemMeta(meta);
        return item;
    }

    private int configuredSlot(String path, int fallback, int size) {
        int slot = getConfig().getInt(path, fallback);
        return Math.max(0, Math.min(size - 1, slot));
    }

    @EventHandler
    public void onInventoryClick(InventoryClickEvent event) {
        Inventory top = event.getView().getTopInventory();
        if (!(top.getHolder() instanceof MenuHolder holder)) {
            return;
        }
        event.setCancelled(true);
        if (!(event.getWhoClicked() instanceof Player player)
                || !holder.data.owner.equals(player.getUniqueId())
                || event.getClickedInventory() != top) {
            return;
        }

        int slot = event.getRawSlot();
        playSound(player, "gui-click");

        if (holder.data.type == MenuType.PLAYER_SELECTION) {
            UUID targetId = holder.data.entries.get(slot);
            if (targetId == null) {
                if (slot == 49) {
                    player.closeInventory();
                }
                return;
            }
            Player target = Bukkit.getPlayer(targetId);
            if (target == null) {
                player.closeInventory();
                sendMessage(player, "player-not-found", "%player%", "player");
                openBedrockPlayerSelector(player);
                return;
            }
            openBedrockActionSelector(player, target);
            return;
        }

        if (holder.data.type == MenuType.ACTION_SELECTION) {
            if (slot == 22) {
                openBedrockPlayerSelector(player);
                return;
            }
            RequestType selectedType = slot == 11 ? RequestType.TO
                    : slot == 15 ? RequestType.HERE : null;
            if (selectedType == null) {
                return;
            }
            Player target = Bukkit.getPlayer(holder.data.target);
            if (target == null) {
                player.closeInventory();
                sendMessage(player, "player-not-found", "%player%", "player");
                openBedrockPlayerSelector(player);
                return;
            }
            openRequestConfirmation(player, target, selectedType);
            return;
        }

        if (holder.data.type == MenuType.INCOMING_LIST) {
            UUID requestId = holder.data.entries.get(slot);
            if (requestId == null) {
                if (slot == configuredSlot("gui.close-slot", 49, top.getSize())) {
                    player.closeInventory();
                }
                return;
            }
            TeleportRequest request = findRequest(requestId);
            if (request == null) {
                player.closeInventory();
                sendMessage(player, "gui-no-request");
                return;
            }
            Player requester = Bukkit.getPlayer(request.senderId);
            if (requester == null) {
                removeRequest(request);
                player.closeInventory();
                sendMessage(player, "gui-no-request");
                return;
            }
            openRequestAcceptance(player, requester, request);
            return;
        }

        int cancelSlot = configuredSlot("gui.cancel-slot", 10, top.getSize());
        int confirmSlot = configuredSlot("gui.confirm-slot", 16, top.getSize());

        if (slot == cancelSlot) {
            if (holder.data.type == MenuType.ACCEPT_CONFIRMATION) {
                TeleportRequest request = holder.data.requestId == null ? null : findRequest(holder.data.requestId);
                if (request != null) {
                    denyRequest(player, request);
                }
            }
            player.closeInventory();
            return;
        }

        if (slot != confirmSlot) {
            return;
        }

        if (holder.data.type == MenuType.SEND_CONFIRMATION) {
            Player target = Bukkit.getPlayer(holder.data.target);
            player.closeInventory();
            if (target == null) {
                sendMessage(player, "player-not-found", "%player%", "player");
                return;
            }
            createRequest(player, target, holder.data.requestType);
        } else if (holder.data.type == MenuType.ACCEPT_CONFIRMATION) {
            TeleportRequest request = holder.data.requestId == null ? null : findRequest(holder.data.requestId);
            player.closeInventory();
            if (request == null) {
                sendMessage(player, "gui-no-request");
                return;
            }
            acceptRequest(player, request);
        }
    }

    @Override
    public List<String> onTabComplete(CommandSender sender, Command command, String alias, String[] args) {
        String name = command.getName().toLowerCase(Locale.ROOT);
        if (name.equals("dtpa")) {
            return args.length == 1 && sender.hasPermission("donix.tpa.admin")
                    ? List.of("reload") : List.of();
        }
        if (args.length != 1 || !sender.hasPermission("donix.tpa.use")) {
            return List.of();
        }

        String partial = args[0].toLowerCase(Locale.ROOT);
        List<String> suggestions = new ArrayList<>();
        if (name.equals("tpa") || name.equals("tpahere")) {
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (sender instanceof Player self && self.getUniqueId().equals(online.getUniqueId())) {
                    continue;
                }
                if (online.getName().toLowerCase(Locale.ROOT).startsWith(partial)) {
                    suggestions.add(online.getName());
                }
            }
        } else if (sender instanceof Player player
                && (name.equals("tpaccept") || name.equals("tpdeny"))) {
            Map<UUID, TeleportRequest> requests = incoming.get(player.getUniqueId());
            if (requests != null) {
                for (TeleportRequest request : requests.values()) {
                    Player requester = Bukkit.getPlayer(request.senderId);
                    if (requester != null && requester.getName().toLowerCase(Locale.ROOT).startsWith(partial)) {
                        suggestions.add(requester.getName());
                    }
                }
            }
        } else if (sender instanceof Player player && name.equals("tpacancel")) {
            Map<UUID, TeleportRequest> requests = outgoing.get(player.getUniqueId());
            if (requests != null) {
                for (TeleportRequest request : requests.values()) {
                    Player target = Bukkit.getPlayer(request.receiverId);
                    if (target != null && target.getName().toLowerCase(Locale.ROOT).startsWith(partial)) {
                        suggestions.add(target.getName());
                    }
                }
            }
        }
        return suggestions;
    }

    private enum RequestType {
        TO("TPA", "Teleport to the player"),
        HERE("TPAHERE", "The player teleports to you");

        private final String label;
        private final String description;

        RequestType(String label, String description) {
            this.label = label;
            this.description = description;
        }
    }

    private enum MenuType {
        SEND_CONFIRMATION,
        ACCEPT_CONFIRMATION,
        INCOMING_LIST,
        PLAYER_SELECTION,
        ACTION_SELECTION
    }

    private static final class TeleportRequest {
        private final UUID id;
        private final UUID senderId;
        private final UUID receiverId;
        private final RequestType type;
        private final long expiresAt;

        private TeleportRequest(UUID id, UUID senderId, UUID receiverId, RequestType type, long expiresAt) {
            this.id = id;
            this.senderId = senderId;
            this.receiverId = receiverId;
            this.type = type;
            this.expiresAt = expiresAt;
        }
    }

    private static final class TeleportWarmup {
        private final UUID destinationId;
        private final org.bukkit.Location startLocation;
        private final BukkitTask task;

        private TeleportWarmup(UUID destinationId, org.bukkit.Location startLocation, BukkitTask task) {
            this.destinationId = destinationId;
            this.startLocation = startLocation;
            this.task = task;
        }
    }

    private static final class MenuData {
        private final MenuType type;
        private final UUID owner;
        private final UUID target;
        private final UUID requestId;
        private final RequestType requestType;
        private final Map<Integer, UUID> entries = new HashMap<>();

        private MenuData(MenuType type, UUID owner, UUID target, UUID requestId, RequestType requestType) {
            this.type = type;
            this.owner = owner;
            this.target = target;
            this.requestId = requestId;
            this.requestType = requestType;
        }
    }

    private static final class MenuHolder implements InventoryHolder {
        private final MenuData data;
        private Inventory inventory;

        private MenuHolder(MenuData data) {
            this.data = data;
        }

        @Override
        public Inventory getInventory() {
            return inventory;
        }
    }
}
