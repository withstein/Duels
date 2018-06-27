package me.realized.duels.data;

import com.google.common.collect.Lists;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.stream.Collectors;
import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import lombok.Getter;
import me.realized.duels.DuelsPlugin;
import me.realized.duels.api.event.user.UserCreateEvent;
import me.realized.duels.api.kit.Kit;
import me.realized.duels.api.user.User;
import me.realized.duels.api.util.Pair;
import me.realized.duels.config.Config;
import me.realized.duels.config.Lang;
import me.realized.duels.util.DateUtil;
import me.realized.duels.util.Loadable;
import me.realized.duels.util.Log;
import me.realized.duels.util.UUIDUtil;
import me.realized.duels.util.compat.Players;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

public class UserManager implements Loadable, Listener, me.realized.duels.api.user.UserManager {

    private static final long UPDATE_INTERVAL = 1000 * 60;

    private final DuelsPlugin plugin;
    private final Config config;
    private final Lang lang;
    private final File folder;
    private final Map<UUID, UserData> users = new ConcurrentHashMap<>();
    private final Map<String, UUID> names = new ConcurrentHashMap<>();

    private volatile int defaultRating;
    private volatile int matchesToDisplay;

    @Getter
    private volatile boolean loaded;
    @Getter
    private volatile TopEntry wins;
    @Getter
    private volatile TopEntry losses;
    @Getter
    private final Map<Kit, TopEntry> topRatings = new ConcurrentHashMap<>();

    private int topTask;

    public UserManager(final DuelsPlugin plugin) {
        this.plugin = plugin;
        this.config = plugin.getConfiguration();
        this.lang = plugin.getLang();
        this.folder = new File(plugin.getDataFolder(), "users");

        if (!folder.exists()) {
            folder.mkdir();
        }

        plugin.getServer().getPluginManager().registerEvents(this, plugin);
    }

    @Override
    public void handleLoad() {
        this.defaultRating = config.getDefaultRating();
        this.matchesToDisplay = config.getMatchesToDisplay();

        if (matchesToDisplay < 0) {
            matchesToDisplay = 0;
        }

        plugin.doAsync(() -> {
            final File[] files = folder.listFiles();

            if (files != null && files.length > 0) {
                for (final File file : files) {
                    final String fileName = file.getName();

                    if (!fileName.endsWith(".json")) {
                        continue;
                    }

                    final String name = fileName.substring(0, fileName.length() - 5);

                    final UUID uuid = UUIDUtil.parseUUID(name);

                    if (uuid == null || users.containsKey(uuid)) {
                        continue;
                    }

                    try (Reader reader = new InputStreamReader(new FileInputStream(file))) {
                        final UserData user = plugin.getGson().fromJson(reader, UserData.class);
                        user.defaultRating = defaultRating;
                        user.matchesToDisplay = matchesToDisplay;
                        user.refreshMatches();
                        // Player might have logged in while reading the file
                        names.putIfAbsent(user.getName().toLowerCase(), uuid);
                        users.putIfAbsent(uuid, user);
                    } catch (IOException ex) {
                        Log.error("Failed to load data of " + uuid + ": " + ex.getMessage());
                    }
                }
            }

            loaded = true;
        });

        this.topTask = plugin.doSyncRepeat(() -> {
            final Collection<? extends Kit> kits = plugin.getKitManager().getKits();

            plugin.doAsync(() -> {
                if (!loaded) {
                    return;
                }

                List<Pair<String, Integer>> result;

                if (wins == null || System.currentTimeMillis() - wins.getCreation() >= UPDATE_INTERVAL) {
                    result = sorted(User::getWins);
                    wins = new TopEntry("Wins", "wins", subList(result, 10));
                }

                if (losses == null || System.currentTimeMillis() - losses.getCreation() >= UPDATE_INTERVAL) {
                    result = sorted(User::getLosses);
                    losses = new TopEntry("Losses", "losses", subList(result, 10));
                }

                topRatings.keySet().removeIf(kit -> !kits.contains(kit));

                for (final Kit kit : kits) {
                    final TopEntry entry = topRatings.get(kit);

                    if (entry == null || System.currentTimeMillis() - entry.getCreation() >= UPDATE_INTERVAL) {
                        result = sorted(user -> user.getRating(kit));
                        topRatings.put(kit, new TopEntry(kit.getName(), "rating", subList(result, 10)));
                    }
                }
            });
        }, 20L * 5, 20L);
    }

    @Override
    public void handleUnload() {
        plugin.cancelTask(topTask);
        loaded = false;
        saveUsers(Players.getOnlinePlayers());
        users.clear();
    }

    @Nullable
    @Override
    public UserData get(@Nonnull final String name) {
        Objects.requireNonNull(name, "name");
        final UUID uuid = names.get(name.toLowerCase());
        return uuid != null ? get(uuid) : null;
    }

    @Nullable
    @Override
    public UserData get(@Nonnull final UUID uuid) {
        Objects.requireNonNull(uuid, "uuid");
        return users.get(uuid);
    }

    @Nullable
    @Override
    public UserData get(@Nonnull final Player player) {
        Objects.requireNonNull(player, "player");
        return get(player.getUniqueId());
    }

    @Override
    public TopEntry getTopWins() {
        return wins;
    }

    @Override
    public TopEntry getTopLosses() {
        return losses;
    }

    @Nullable
    @Override
    public TopEntry getTopRatings(@Nonnull final Kit kit) {
        Objects.requireNonNull(kit, "kit");
        return topRatings.get(kit);
    }

    private List<Pair<String, Integer>> subList(final List<Pair<String, Integer>> list, final int max) {
        return Collections.unmodifiableList(Lists.newArrayList(list.size() > 10 ? list.subList(0, max) : list));
    }

    private <V extends Comparable<V>> List<Pair<String, V>> sorted(final Function<User, V> function) {
        return users.values().stream()
            .map(data -> new Pair<>(data.getName(), function.apply(data)))
            .sorted(Comparator.reverseOrder())
            .collect(Collectors.toList());
    }

    private void saveUsers(final Collection<? extends Player> players) {
        for (final Player player : players) {
            final UserData user = users.remove(player.getUniqueId());

            if (user != null) {
                trySave(player, user);
            }
        }
    }

    private UserData tryLoad(final Player player) {
        final File file = new File(folder, player.getUniqueId() + ".json");

        if (!file.exists()) {
            final UserData user = new UserData(player);
            user.defaultRating = defaultRating;
            user.matchesToDisplay = matchesToDisplay;
            plugin.doSync(() -> plugin.getServer().getPluginManager().callEvent(new UserCreateEvent(user)));
            return user;
        }

        try (Reader reader = new InputStreamReader(new FileInputStream(file))) {
            final UserData user = plugin.getGson().fromJson(reader, UserData.class);
            user.defaultRating = defaultRating;
            user.matchesToDisplay = matchesToDisplay;
            user.refreshMatches();

            if (!player.getName().equals(user.getName())) {
                user.setName(player.getName());
            }

            return user;
        } catch (IOException ex) {
            ex.printStackTrace();
            Log.error("An error occured while loading userdata of " + player.getName() + ": " + ex.getMessage());
            return null;
        }
    }

    private void trySave(final Player player, final UserData data) {
        final File file = new File(folder, player.getUniqueId() + ".json");

        try {
            if (!file.exists()) {
                file.createNewFile();
            }

            try (Writer writer = new OutputStreamWriter(new FileOutputStream(file))) {
                plugin.getGson().toJson(data, writer);
                writer.flush();
            }
        } catch (IOException ex) {
            ex.printStackTrace();
            Log.error("An error occured while saving userdata of " + player.getName() + ": " + ex.getMessage());
        }
    }

    @EventHandler
    public void on(final PlayerJoinEvent event) {
        final Player player = event.getPlayer();
        final UserData user = users.get(player.getUniqueId());

        if (user != null) {
            if (!player.getName().equals(user.getName())) {
                user.setName(player.getName());
                names.put(player.getName().toLowerCase(), player.getUniqueId());
            }

            return;
        }

        plugin.doAsync(() -> {
            final UserData data = tryLoad(player);

            if (data == null) {
                lang.sendMessage(player, "ERROR.data.load-failure");
                return;
            }

            names.put(player.getName().toLowerCase(), player.getUniqueId());
            users.put(player.getUniqueId(), data);
        });
    }

    @EventHandler
    public void on(final PlayerQuitEvent event) {
        final UserData user = users.remove(event.getPlayer().getUniqueId());

        if (user != null) {
            plugin.doAsync(() -> trySave(event.getPlayer(), user));
        }
    }

    public String getNextUpdate(final long creation) {
        return DateUtil.format((creation + UPDATE_INTERVAL - System.currentTimeMillis()) / 1000L);
    }
}
