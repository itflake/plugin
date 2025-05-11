package plugin;

import arc.Events;
import arc.graphics.Color;
import arc.util.CommandHandler;
import mindustry.Vars;
import mindustry.core.GameState;
import mindustry.game.EventType.*;
import mindustry.game.Gamemode;
import mindustry.game.Team;
import mindustry.game.Teams;
import mindustry.gen.*;
import mindustry.io.SaveIO;
import mindustry.mod.*;
import mindustry.maps.Map;
import arc.util.Strings;
import arc.func.Cons;
import arc.util.serialization.JsonReader;
import arc.util.Timer;
import arc.util.Log;
import arc.util.Http;
import mindustry.net.Packets;
import mindustry.net.WorldReloader;
import mindustry.net.Administration.Config;
import arc.struct.Seq;
import arc.util.Time;
import mindustry.type.Weather;
import mindustry.ui.Menus;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.content.Weathers;

import java.util.HashMap;
import java.util.*;
import java.util.Arrays;
import arc.files.Fi;
import arc.util.serialization.Json;
import arc.struct.*;
import arc.Core;
import static mindustry.Vars.netServer;
import static plugin.TokenManager.cleanUpExpiredTokens;

public class SimplePlugin extends Plugin {
    MapUploadServer server;
    private static final Fi configFile = Vars.saveDirectory.child("webConfig.json");

    public static class ServerConfig {
        public int port = 52011;
        public String title = "PvP";
        public String url = "ity.moe";
    }

    public static int globalPort;
    public static String globalModeName;
    public static String globalUrl;
    public static ServerConfig config = new ServerConfig();
    public Timer.Task end;
    public static final JsonReader reader = new JsonReader();
    private final HashSet<String> lbEnabled = new HashSet<>();
    private final StringBuilder updatedBuilder = new StringBuilder(); // Store leaderboard data here
    private static final double ratio = 0.6;
    private boolean currentVote = false;
    private Map currentMap = null;
    private static final ObjectMap<String, Timer.Task> confirmTasks = new ObjectMap<>();
    private Gamemode currentMode = null;
    private Player initPlayer = null;
    private long gameStartTime = Time.millis();
    private static boolean snowCommandUsed = false;
    private final HashSet<String> votes = new HashSet<>();
    private static final Set<String> knownIPs = new HashSet<>();
    private static final Fi ipFile = Vars.saveDirectory.child("ip-list.json");
    private static final Json json = new Json();
    private static final Fi rankFile = Vars.saveDirectory.child("rank.json");
    private static final Fi playtimeFile = Vars.saveDirectory.child("playtime.json");
    private static final ObjectMap<String, Float> playtimeData = new ObjectMap<>();
    private static final ObjectMap<String, Integer> rankData = new ObjectMap<>();
    private static final HashMap<String, Team> lastTeams = new HashMap<>();
    private static final HashSet<String> playersReturn = new HashSet<>();
    private static final HashMap<String, Team> playerTeams = new HashMap<>();
    private final HashMap<String, String> playerLang = new HashMap<>();
    private final HashMap<String, Boolean> approvedAdmins = new HashMap<>();
    private final HashMap<String, Boolean> lostTeam = new HashMap<>();
    private static final HashMap<String, String> languageMapping = new HashMap<String, String>() {{
        put("zh", "中文");
        put("en", "English");
        put("es", "Español");
        put("fr", "Français");
        put("de", "Deutsch");
        put("ja", "日本語");
        put("ko", "한국어");
        put("ru", "Русский");
        put("tr", "Türkçe");
    }};
    public void resetGameState() {
        currentVote = false;
        currentMap = null;
        currentMode = null;
        initPlayer = null;
        snowCommandUsed = false;
        votes.clear();
        lastTeams.clear();
        playersReturn.clear();
        playerTeams.clear();
        lostTeam.clear();
        if (end != null) {
            end.cancel();
        }
    }

    public static void loadTime() {
        if (playtimeFile.exists()) {
            try {
                String content = playtimeFile.readString();
                if (!content.trim().isEmpty()) {
                    ObjectMap<String, Float> loadedData = json.fromJson(ObjectMap.class, content);
                    if (loadedData != null) {
                        playtimeData.clear();
                        playtimeData.putAll(loadedData);
                    }
                }
            } catch (Exception e) {
                Log.err("Failed to load playtime data.", e);
            }
        }
    }


    public static void saveTime() {
        try {
            String jsonString = json.toJson(playtimeData);
            playtimeFile.writeString(jsonString);
        } catch (Exception e) {
            Log.err("Failed to save playtime data.", e);
        }
    }

    public static void addTime(String playerUUID, float minutes) {
        float current = playtimeData.containsKey(playerUUID) ? playtimeData.get(playerUUID) : 0f;
        updatePlaytime(playerUUID, current + minutes);
    }

    public static void updatePlaytime(String playerUUID, float mins) {
        playtimeData.put(playerUUID, mins);
        saveTime();
    }

    public static void loadRank() {
        if (rankFile.exists()) {
            try {
                String content = rankFile.readString();
                if (!content.trim().isEmpty()) {
                    ObjectMap<String, Integer> loadedData = json.fromJson(ObjectMap.class, content);
                    if (loadedData != null) {
                        rankData.clear();
                        rankData.putAll(loadedData);
                    }
                }
            } catch (Exception e) {
                Log.err("Failed to load rank data.", e);
            }
        }
    }

    public static void saveRank() {
        try {
            String jsonString = json.toJson(rankData);
            rankFile.writeString(jsonString);
        } catch (Exception e) {
            Log.err("Failed to save rank data.", e);
        }
    }

    public static void addRank(String playerUUID, int rate) {
        int current = rankData.containsKey(playerUUID) ? rankData.get(playerUUID) : 0;
        updateRankTime(playerUUID, current + rate);
    }

    public static void updateRankTime(String playerUUID, int rate) {
        rankData.put(playerUUID, rate);
        saveRank();
    }

    public static void load() {
        if (ipFile.exists()) {
            try {
                String[] loaded = json.fromJson(String[].class, ipFile.readString());
                knownIPs.clear();
                if (loaded != null) {
                    knownIPs.addAll(Arrays.asList(loaded));
                }
                Log.info("Loaded @ known IPs.", knownIPs.size());
            } catch (Exception e) {
                Log.err("Failed to load known IPs.", e);
            }
        }
    }

    public static void webLoad() {
        if (configFile.exists()) {
            try {
                String content = configFile.readString();

                config = json.fromJson(ServerConfig.class, content);
                Log.info("Loaded config: port=@, title='@', url=@", config.port, config.title, config.port);

                globalPort = config.port;
                globalModeName = config.title;
                globalUrl = config.url;
                return;
            } catch (Exception e) {
                Log.err("Failed to load or parse config.json, rewriting default.", e);
            }
        }

        try {
            String jsonString = "{\n" +
                    "  \"port\": " + config.port + ",\n" +
                    "  \"title\": \"" + config.title + "\"\n" +
                    "  \"url\": \"" + config.url + "\"\n" +
                    "}";
            configFile.writeString(jsonString);
            Log.info("Saved default config to config.json.");
            globalPort = config.port;
            globalModeName = config.title;
            globalUrl = config.url;
        } catch (Exception e) {
            Log.err("Failed to save config.json.", e);
        }
    }

    public static void webInit() {
        webLoad();

        globalPort = config.port;
        globalModeName = config.title;

        Log.info("Web server initialized on port @ with mode '@'", globalPort, globalModeName);
    }

    public static void save() {
        ipFile.writeString(json.toJson(knownIPs.toArray(new String[0])), false);
    }

    public static boolean isNewIP(String ip) {
        return !knownIPs.contains(ip);
    }

    public static void addIP(String ip) {
        if (knownIPs.add(ip)) {
            save();
        }
    }
    public static void assignFallbackTeamIfNoCore(Player player) {
        if (player == null || player.team() == null) {
            Log.warn("Cannot assign fallback team: player or player team is null.");
            return;
        }

        boolean teamHasCore = Vars.state.teams.get(player.team()) != null &&
                Vars.state.teams.get(player.team()).hasCore();

        if (!teamHasCore) {
            Team fallbackTeam = null;
            int minPlayers = Integer.MAX_VALUE;

            for (Team team : Team.all) {
                if (team == Team.derelict) continue;
                var teamData = Vars.state.teams.get(team);
                if (teamData == null || !teamData.hasCore()) continue;

                int playerCount = Groups.player.count(p -> p.team() == team);
                if (playerCount < minPlayers) {
                    fallbackTeam = team;
                    minPlayers = playerCount;
                }
            }

            if (fallbackTeam != null && fallbackTeam != player.team()) {
                player.team(fallbackTeam);
                Log.info("Assigned fallback team " + fallbackTeam.name + " to player " + player.name);
            }
        }
    }

    public void collectLeaderboardData() {
        Seq<Player> onlineWithScore = Groups.player.copy().select(p -> rankData.get(p.uuid(), 0) > 0);
        updatedBuilder.setLength(0);
        updatedBuilder.append("[#FFFFFF]Leaderboard[]\n");

        if (!onlineWithScore.isEmpty()) {
            onlineWithScore.sort((a, b) -> Integer.compare(rankData.get(b.uuid(), 0), rankData.get(a.uuid(), 0)));
            for (int i = 0; i < onlineWithScore.size; i++) {
                Player p = onlineWithScore.get(i);
                int score = rankData.get(p.uuid(), 0);
                updatedBuilder.append("[#D1DBF2FF]").append(i + 1).append(". ")
                        .append(p.name).append(": ")
                        .append(score).append("[]\n");
            }
        }
    }

    public void showLeaderboard() {
        Timer.schedule(() -> {
            collectLeaderboardData();
            Groups.player.each(player -> {
                if (player == null || player.con == null) return;
                if (!lbEnabled.contains(player.uuid())) return;
                if (updatedBuilder.isEmpty()) return;
                Call.infoPopup(player.con, updatedBuilder.toString(), 5f, 8, 0, 2, 50, 0);
            });
        }, 0f, 5f);
    }

    public static void translate(String text, String from, String to, Cons<String> result, Runnable error) {
        Http.post("https://clients5.google.com/translate_a/t?client=dict-chrome-ex&dt=t", "tl=" + to + "&sl=" + from + "&q=" + Strings.encode(text))
                .error(throwable -> error.run())
                .submit(response -> result.get(reader.parse(response.getResultAsString()).get(0).get(0).asString()));
    }

    public static void localeAsync(String message, Player player, Cons<String> onResult, Runnable onError) {
        String playerLocale = player.locale != null ? player.locale : "en";
        translate(message, "auto", playerLocale, onResult, onError);
    }

    public static void localeAsyncAll(String message) {
        Groups.player.each(player -> localeAsync(message, player,
                translated -> player.sendMessage("[#D1DBF2FF]" + translated + "[]"),
                () -> player.sendMessage("[#D1DBF2FF]" + message + "[]")
        ));
    }

    public static void localeAsyncOne(String message, Player player) {
        localeAsync(message, player,
                translated -> player.sendMessage("[#D1DBF2FF]" + translated + "[]"),
                () -> player.sendMessage("[#D1DBF2FF]" + message + "[]")
        );
    }

    private void startVote(Player initiator, Map map) {
        if (currentVote) return;
        this.currentVote = true;
        this.currentMap = map;
        this.initPlayer = initiator;
        this.votes.add(initiator.uuid());
        int cur = this.votes.size();
        int req = (int) Math.ceil(ratio * Groups.player.size());

        if (req == 1 || initPlayer.admin) {
            Call.sendMessage("[#D1DBF2FF]Vote passed. Map []" + currentMap.name() + " [#D1DBF2FF]will be loaded.[]");
            currentMode = Vars.state.rules.mode();
            java.io.File folder = new java.io.File("config/snapshots");
            if (folder.exists()) {
                for (java.io.File file : Objects.requireNonNull(folder.listFiles((dir, name) -> name.startsWith("autosave-")))) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }

            reloadWorld(() -> {
                Vars.state.map = currentMap;
                Vars.world.loadMap(currentMap);
                Vars.state.rules = currentMap.applyRules(currentMode);
                Vars.logic.play();
                resetGameState();
                gameStartTime = Time.millis();
            });
        } else {
            end = Timer.schedule(() -> {
                if (currentVote) {
                    int curVotes = this.votes.size();
                    if (curVotes < req) {
                        localeAsyncAll("Voted failed.");
                        this.votes.clear();
                        currentVote = false;
                        currentMap = null;
                    }
                }
            }, 30f);
            Call.sendMessage("[#FFFFFFFF]" + initiator.name + "[][#D1DBF2FF] voted to change the map to [][#FFFFFFFF]" + map.name() + "[][#FFFFFFFF]. " + cur + "[][#D1DBF2FF] votes, [][#FFFFFFFF]" + req + "[][#D1DBF2FF] required." + "\n" + "Type[] [#FFFFFFFF]y[] [#D1DBF2FF]to vote.[]");
        }
    }

    public static void reloadWorld(Runnable runnable) {
        try {
            WorldReloader reloader = new WorldReloader();
            reloader.begin();
            runnable.run();
            reloader.end();
        } catch (Exception e) {  // Catching general exception
            Log.err("Error while reloading map: " + e.getMessage());
        }
    }

    public static void welcome(Player player) {
        String titleRaw = "Welcome back";
        String content = "Return to your previous game team?";
        String[] options = new String[]{"Cancel", "Confirm"};

        translate(titleRaw, "auto", player.locale, translatedTitle -> {
            String title = translatedTitle.equals(titleRaw) ? titleRaw : translatedTitle;
            translate(content, "auto", player.locale, translatedContent -> {
                String contentText = translatedContent.equals(content) ? content : translatedContent;
                translate(options[0], "auto", player.locale, translatedOption1 -> {
                    String option1 = translatedOption1.equals(options[0]) ? options[0] : translatedOption1;
                    translate(options[1], "auto", player.locale, translatedOption2 -> {
                        String option2 = translatedOption2.equals(options[1]) ? options[1] : translatedOption2;
                        String[][] translatedOptions = new String[][]{
                                {option1, option2}
                        };
                        Call.menu(player.con, menuId, title, contentText, translatedOptions);
                    }, () -> {
                        String option2 = options[1];
                        String[][] translatedOptions = new String[][]{{option1, option2}};
                        Call.menu(player.con, menuId, title, contentText, translatedOptions);
                    });
                }, () -> {
                    String option1 = options[0];
                    String[][] translatedOptions = new String[][]{{option1, options[1]}};
                    Call.menu(player.con, menuId, title, contentText, translatedOptions);
                });
            }, () -> {
                String[][] translatedOptions = new String[][]{{options[0], options[1]}};
                Call.menu(player.con, menuId, title, content, translatedOptions);
            });
        }, () -> {
            String[][] translatedOptions = new String[][]{{options[0], options[1]}};
            Call.menu(player.con, menuId, titleRaw, content, translatedOptions);
        });
    }

    private static final int playerMenuId = Menus.registerMenu((player, choice) -> {
        if (choice == 1) {
            String playerUUID = player.uuid();
            Team lastTeam = lastTeams.remove(player.uuid());
            boolean validLastTeam = lastTeam != null &&
                    Vars.state.teams.get(lastTeam) != null &&
                    Vars.state.teams.get(lastTeam).hasCore();
            if (validLastTeam) {
                player.team(lastTeam);
            } else {
                assignFallbackTeamIfNoCore(player);
            }
            Timer.Task task = confirmTasks.remove(playerUUID);
            if (task != null) {
                task.cancel();
            }
            addIP(player.con.address);
        } else if (choice == 0) {
            String playerUUID = player.uuid();
            Timer.Task task = confirmTasks.remove(playerUUID);
            if (task != null) {
                task.cancel();
            }
            player.con.kick();
        }
    });


    public static void confirm(Player player) {
        String titleRaw = "Welcome to Snow";
        String content =
                """
Click "Confirm" to start.
                """;

        String[] options = new String[]{"Cancel", "Confirm"};
        translate(titleRaw, "auto", player.locale, translatedTitle -> {
            String title = translatedTitle.equals(titleRaw) ? titleRaw : translatedTitle;
            translate(content, "auto", player.locale, translatedContent -> {
                String contentText = translatedContent.equals(content) ? content : translatedContent;
                translate(options[0], "auto", player.locale, translatedOption1 -> {
                    String option1 = translatedOption1.equals(options[0]) ? options[0] : translatedOption1;

                    translate(options[1], "auto", player.locale, translatedOption2 -> {
                        String option2 = translatedOption2.equals(options[1]) ? options[1] : translatedOption2;
                        String[][] translatedOptions = new String[][]{
                                {option1, option2}
                        };
                        Call.menu(player.con, playerMenuId, title, contentText, translatedOptions);
                    }, () -> {
                        String option2 = options[1];
                        String[][] translatedOptions = new String[][]{{option1, option2}};
                        Call.menu(player.con, playerMenuId, title, contentText, translatedOptions);
                    });
                }, () -> {
                    String option1 = options[0];
                    String[][] translatedOptions = new String[][]{{option1, options[1]}};
                    Call.menu(player.con, playerMenuId, title, contentText, translatedOptions);
                });
            }, () -> {
                String[][] translatedOptions = new String[][]{{options[0], options[1]}};
                Call.menu(player.con, playerMenuId, title, content, translatedOptions);
            });
        }, () -> {
            String[][] translatedOptions = new String[][]{{options[0], options[1]}};
            Call.menu(player.con, playerMenuId, titleRaw, content, translatedOptions);
        });
    }

    private static final String[] bannedWords = {
            "admin", "gm", "moderator", "fuck", "傻逼", "操", "ばか", "병신", "лох", "nigga", "nigger", "习近平", "金正恩", "卐"
    };

    private boolean containsBannedWord(String name) {
        String lower = name.toLowerCase();
        for (String word : bannedWords) {
            if (lower.contains(word)) return true;
        }
        return false;
    }

    private boolean isValidName(String name) {
        if (containsBannedWord(name)) return false;
        if (name.codePointCount(0, name.length()) > 40) return false;

        for (int i = 0; i < name.length(); ) {
            int codePoint = name.codePointAt(i);
            i += Character.charCount(codePoint);

            if (isAllowedUnicode(codePoint)) continue;

            return false;
        }
        return true;
    }

    private boolean isAllowedUnicode(int codePoint) {
        Character.UnicodeBlock block = Character.UnicodeBlock.of(codePoint);
        return
                (codePoint >= 32 && codePoint <= 126) ||
                        // Chinese
                        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS ||
                        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_A ||
                        block == Character.UnicodeBlock.CJK_UNIFIED_IDEOGRAPHS_EXTENSION_B ||
                        block == Character.UnicodeBlock.CJK_SYMBOLS_AND_PUNCTUATION ||
                        block == Character.UnicodeBlock.GENERAL_PUNCTUATION ||
                        block == Character.UnicodeBlock.BASIC_LATIN ||
                        // Japanese
                        block == Character.UnicodeBlock.HIRAGANA ||
                        block == Character.UnicodeBlock.KATAKANA ||
                        block == Character.UnicodeBlock.KATAKANA_PHONETIC_EXTENSIONS ||
                        // Korean
                        block == Character.UnicodeBlock.HANGUL_SYLLABLES ||
                        block == Character.UnicodeBlock.HANGUL_JAMO ||
                        block == Character.UnicodeBlock.HANGUL_COMPATIBILITY_JAMO ||
                        // Russian
                        block == Character.UnicodeBlock.CYRILLIC ||
                        block == Character.UnicodeBlock.CYRILLIC_SUPPLEMENTARY ||
                        block == Character.UnicodeBlock.CYRILLIC_EXTENDED_A ||
                        block == Character.UnicodeBlock.CYRILLIC_EXTENDED_B;
    }

    private static final List<String> commands = Arrays.asList(

            "[#FFFFFFFF]/lb[]",
            "[#FFFFFFFF]/maps[] [page]",
            "[#FFFFFFFF]/rank[] [page]",
            "[#FFFFFFFF]/rollback[] [name/index]",
            "[#FFFFFFFF]/rtv[] [map]",
            "[#FFFFFFFF]/snow[]",
            "[#FFFFFFFF]/t[]",
            "[#FFFFFFFF]/time[]",
            "[#FFFFFFFF]/toggle[]",
            "[#FFFFFFFF]/tr[] [language]/auto/off",
            "[#FFFFFFFF]/upload[]",
            "[#FFFFFFFF]/vote[] y/n",
            "[#FFFFFFFF]/votekick[] [player]"
    );

    public SimplePlugin() {
        Events.on(ServerLoadEvent.class, s -> {
            load();
            webInit();
            loadTime();
            loadRank();
            showLeaderboard();
            Timer.schedule(() -> {
                if (!Vars.state.isGame()) {
                    Config.desc.set("Welcome to Snow!");return;
                }
                Groups.player.each(p -> addTime(p.uuid(), 0.06f));
                long duration = Time.timeSinceMillis(gameStartTime);
                int seconds = (int) (duration / 1000);
                int minutes = seconds / 60;
                Config.desc.set("[#EDF4FCBB]Game started [#EDF4FCFF]" + minutes + "[] minutes ago.[]");
            }, 0f, 5f);
            try {
                server = new MapUploadServer(globalModeName, globalPort);
            } catch (Exception e) {
                Log.err("Web server failed to start:", e);
            }
            Timer.schedule(() -> {
                cleanUpExpiredTokens();
                long duration = Time.timeSinceMillis(gameStartTime);
                int seconds = (int) (duration / 1000);
                int minutes = seconds / 60;
                Fi file = Core.files.local("config/snapshots/autosave-" + minutes + "m.msav");
                SaveIO.save(file);
            }, 70f, 70f);

            java.io.File folder = new java.io.File("config/snapshots");
            if (folder.exists()) {
                for (java.io.File file : Objects.requireNonNull(folder.listFiles((dir, name) -> name.startsWith("autosave-")))) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
        });

        Events.on(PlayerChatEvent.class, e -> {
            Player sender = e.player;
            String message = e.message;
            if (currentMap != null && currentVote && (message.equals("y") || message.equals("yes")) && sender != initPlayer && !this.votes.contains(sender.uuid())) {
                this.votes.add(sender.uuid());
                int cur = this.votes.size();
                int reqVotes = (int) Math.ceil(ratio * Groups.player.size());
                Call.sendMessage("[#FFFFFFFF]" + sender.name + "[][#D1DBF2FF] voted to change the map. []" + "[#FFFFFFFF]" + cur + "[][#D1DBF2FF] votes, [][#FFFFFFFF]" + reqVotes + "[][#D1DBF2FF] required.[]");
                int curVotes = this.votes.size();
                if (curVotes >= reqVotes) {
                    Call.sendMessage("[#D1DBF2FF]Vote passed! Map []" + currentMap.name() + " [#D1DBF2FF]will be loaded in 5 seconds...");
                    Timer.schedule(() -> {
                        currentMode = Vars.state.rules.mode();
                        java.io.File folder = new java.io.File("config/snapshots");
                        if (folder.exists()) {
                            for (java.io.File file : Objects.requireNonNull(folder.listFiles((dir, name) -> name.startsWith("autosave-")))) {
                                //noinspection ResultOfMethodCallIgnored
                                file.delete();
                            }
                        }
                        if (currentMap != null) {
                            reloadWorld(() -> {
                                Vars.state.map = currentMap;
                                Vars.world.loadMap(currentMap);
                                Vars.state.rules = currentMap.applyRules(currentMode);
                                Vars.logic.play();
                                currentVote = false;
                                currentMap = null;
                                resetGameState();
                                gameStartTime = Time.millis();
                            });
                            if (end != null) {
                                end.cancel();  // Only cancel if the task is non-null
                            }
                        }
                    }, 5f);
                }
            }
            Groups.player.each(receiver -> {
                if (receiver == sender) return;
                String messageLower = message.trim().toLowerCase();
                String lang = playerLang.get(receiver.uuid());
                if (receiver.locale.equals(sender.locale())) {
                    return;
                }
                if (messageLower.startsWith("/") || messageLower.equals("back") || messageLower.equals("y") || messageLower.equals("yes")) {
                    return;
                }
                if (lang == null || lang.equals("off")) return;
                translate(message, "auto", lang, translated -> {
                    if (!translated.equals(message)) {
                        receiver.sendMessage("[][gray]" + sender.name + ": " + translated + "[]");
                    }
                }, () -> localeAsyncOne("Translation failed!", receiver));
            });

        });

        Events.on(PlayerLeave.class, e -> {
            Player player = e.player;
            if (player == null || player.uuid() == null) return;

            String uuid = player.uuid();
            String name = player.name != null ? player.name : "Unknown";

            if (votes.contains(uuid)) {
                votes.remove(uuid);
                int cur = votes.size();
                int req = (int) Math.ceil(ratio * Groups.player.size());
                Call.sendMessage("[#D1DBF2DD]" + name + " left, " + cur + " votes, " + req + " required.[]");
            }

            if (Vars.state.rules != null
                    && Vars.state.rules.mode() == Gamemode.pvp
                    && player.team() != null
                    && player.team() != Team.derelict) {
                playerTeams.put(uuid, player.team());
                Timer.schedule(() -> playerTeams.remove(uuid), 60f * 5, 0, 0);
            }
        });

        Events.on(GameOverEvent.class, e -> {
            resetGameState();
            gameStartTime = Time.millis();
            if (e.winner != null &&
                    e.winner != Team.derelict &&
                    Vars.state.teams.get(e.winner).hasCore() &&
                    Groups.player.count(p -> p.team() == e.winner) > 0) {
                Seq<Player> winners = Groups.player.copy().select(p -> p.team() == e.winner);
                int total = winners.size;
                int score = Math.max(25, 40 - total / 4);
                for (Player p : winners) {
                    addRank(p.uuid(), score);
                    p.sendMessage("[#D1DBF2DD]You got " + score + " points![]");
                }
                Call.sendMessage("[#D1DBF2DD][#" + e.winner.color.toString() + "]" + e.winner.name + "[] team wins![]");
            }
            java.io.File folder = new java.io.File("config/snapshots");
            if (folder.exists()) {
                for (java.io.File file : Objects.requireNonNull(folder.listFiles((dir, name) -> name.startsWith("autosave-")))) {
                    //noinspection ResultOfMethodCallIgnored
                    file.delete();
                }
            }
        });

        Events.on(PlayerJoin.class, e -> {
            Player player = e.player;
            if (player == null || player.con == null) return;
            String name = player.name != null ? player.name : "";
            String ip = player.con.address != null ? player.con.address : "unknown";
            String playerUUID = player.uuid() != null ? player.uuid() : UUID.randomUUID().toString(); // fallback uuid

            if (!isValidName(name)) {
                String raw = "Your name is illegal or contains special characters or is longer than 40 characters.";
                localeAsync(raw, player,
                        translated -> {
                            if (player.con != null) player.con.kick(translated);
                        },
                        () -> {
                            if (player.con != null) player.con.kick(raw);
                        }
                );
                return;
            }

            if (!playtimeData.containsKey(playerUUID)) {
                updatePlaytime(playerUUID, 0f);
            }

            if (isNewIP(ip)) {
                if (player.team() == null) {
                    Log.warn("Cannot move player to derelict: player or team is null.");
                    return;
                }
                lastTeams.put(playerUUID, player.team());
                player.team(Team.derelict);
                confirm(player);
                Timer.Task task = Timer.schedule(() -> {
                    Player p = Groups.player.find(pl -> pl.uuid().equals(playerUUID));
                    if (p == null || p.con == null) return;
                    if (playerUUID != null) {
                        p.kick("Did not confirm within 1 minute.");
                    }
                    confirmTasks.remove(playerUUID);
                }, 60f);
                confirmTasks.put(player.uuid(), task);
            } else {
                String motdRaw = "Welcome to Snow!";
                localeAsync(motdRaw, player, player::sendMessage, () -> player.sendMessage(motdRaw));
                Team newTeam = playerTeams.get(playerUUID);
                boolean isPVP = Vars.state.rules != null && Vars.state.rules.mode() == Gamemode.pvp;
                Team playerTeam = player.team();
                Teams.TeamData teamData = (Vars.state != null && Vars.state.teams != null) ? Vars.state.teams.get(playerTeam) : null;
                Teams.TeamData newTeamData = (newTeam != null && Vars.state != null && Vars.state.teams != null)
                        ? Vars.state.teams.get(newTeam)
                        : null;
                boolean teamHasCore = teamData != null && teamData.hasCore();
                boolean hasLost = Boolean.TRUE.equals(lostTeam.get(playerUUID)); // null 或 false 均视为未败队
                if (isPVP){
                    if (hasLost) {
                        localeAsyncOne("Your team has lost. Please wait for the next round.", player);
                        player.team(Team.derelict);
                    } else {
                        if (newTeam != null && playerTeam != newTeam && newTeamData != null && newTeamData.hasCore()) {
                            lastTeams.put(playerUUID, player.team());
                            player.team(Team.derelict);
                            playersReturn.add(playerUUID);
                            welcome(player);
                        } else if (!teamHasCore) {
                            assignFallbackTeamIfNoCore(player);
                        }
                    }
                }
            }

            if (!playerLang.containsKey(playerUUID)) {
                String lang = player.locale;
                String detectedLang = (lang == null || lang.isEmpty()) ? "en" : lang;
                playerLang.put(playerUUID, detectedLang);
            }

            lbEnabled.add(player.uuid());
        });

        Events.on(BlockDestroyEvent.class, event -> {
            var team = event.tile.team();

            if (event.tile.block() instanceof CoreBlock) {
                if (team != Team.derelict && team.cores().size <= 1) {
                    team.data().players.each(p -> {
                        if (Vars.state.rules.mode() == Gamemode.pvp) {
                            localeAsyncOne("Your team has lost. Please wait for the next round.", p);
                            int size = Math.max(1, team.data().players.size);
                            int penalty = Math.max(20, 40 - size / 3);
                            Integer oldValue = rankData.get(p.uuid());
                            int old = oldValue != null ? oldValue : 0;
                            int updated = Math.max(0, old - penalty);
                            updateRankTime(p.uuid(), updated);
                            p.sendMessage("[#D1DBF2FF]You lost " + penalty + " points[]!");
                            lostTeam.put(p.uuid(), true);
                            p.team(Team.derelict);
                            p.clearUnit();
                        } else {
                            localeAsyncOne("You lost this game.", p);
                        }
                    });
                }
            }
        });
    }

    private static final int menuId = Menus.registerMenu((player, choice) -> {
        if (choice == 1) {
            Team newTeam = playerTeams.get(player.uuid());
            if (newTeam != null) player.team(newTeam);
            lastTeams.remove(player.uuid());
            playersReturn.remove(player.uuid());
            playerTeams.remove(player.uuid());
        } else if (choice == 0) {
            if (!playersReturn.contains(player.uuid())) return;
            playersReturn.remove(player.uuid());
            playerTeams.remove(player.uuid());

            Team lastTeam = lastTeams.remove(player.uuid());
            boolean validLastTeam = lastTeam != null &&
                    Vars.state.teams.get(lastTeam) != null &&
                    Vars.state.teams.get(lastTeam).hasCore();

            if (validLastTeam) {
                player.team(lastTeam);
            } else {
                assignFallbackTeamIfNoCore(player);
            }
        }
    });

    @Override
    public void registerClientCommands(CommandHandler handler) {

        handler.removeCommand("a");
        handler.<Player>register("help", "[page]", (args, player) -> {
            StringBuilder helpMessage = new StringBuilder("Available Commands:\n");

            for (String command : commands) {
                helpMessage.append("[#D1DBF2FF]").append(command).append("\n");
            }
            player.sendMessage(helpMessage.append("[]").toString());
        });

        handler.<Player>register("snow", "snow", (args, player) -> {
            if (snowCommandUsed) {
                player.sendMessage("[#D1DBF2FF]The snow effect has already been applied in this game.");
                return;
            }
            String playerUUID = player.uuid();
            int current = rankData.containsKey(playerUUID) ? rankData.get(playerUUID) : 0;
            if (player.admin || current > 50) {
                Vars.state.rules.ambientLight.set(new Color(0.1f, 0.1f, 0.2f, 0.8f));
                Vars.state.rules.lighting = true;
                Weather.WeatherEntry entry = new Weather.WeatherEntry();
                entry.weather = Weathers.snow;
                entry.intensity = 1f;
                entry.always = true;
                Vars.state.rules.weather.add(entry);

                for (Player p : Groups.player) {
                    Call.worldDataBegin(p.con);
                    netServer.sendWorldData(p);
                }
                snowCommandUsed = true;
            } else {
                player.sendMessage("[#D1DBF2FF]You must be an admin or has 50+ points.");
            }
        });

        handler.<Player>register("time", "show time", (args, player) -> {
            long duration = Time.timeSinceMillis(gameStartTime); // 毫秒
            int minutes = (int) (duration / 1000 / 60);
            float totalMinutes = playtimeData.get(player.uuid(), 0f);

            player.sendMessage("[#D1DBF2FF]Game played: [] " + minutes + " minutes[]");
            player.sendMessage("[#D1DBF2FF]Your total play time: [] " + String.format("%.2f", totalMinutes) + " minutes[]");
        });

        handler.<Player>register("rollback", "[name/index]", "Rollback to a snapshot", (args, player) -> {
            java.io.File folder = new java.io.File("config/snapshots");

            java.io.File[] files = folder.listFiles((dir, name) -> name.startsWith("autosave-") && name.endsWith(".msav"));
            if (files == null || files.length == 0) {
                player.sendMessage("[#D1DBF2FF]No snapshots available.[]");
                return;
            }

            Arrays.sort(files, Comparator.comparingLong(java.io.File::lastModified));

            List<String> readableNames = new ArrayList<>();
            for (java.io.File file : files) {
                String name = file.getName().replace("autosave-", "").replace(".msav", "");
                readableNames.add(name);
            }

            if (args.length == 0) {
                StringBuilder sb = new StringBuilder("[#FFFFFF]Available snapshots:\n");
                for (int i = 0; i < readableNames.size(); i++) {
                    sb.append("[#D1DBF2FF]").append(i).append("[]: ").append(readableNames.get(i)).append("\n");
                }
                player.sendMessage(sb.toString());
                return;
            }
            java.io.File targetFile = null;
            String input = args[0];

            if (!player.admin && args[0] != null) {
                player.sendMessage("[#D1DBF2FF]You are not an admin.[]");
                return;
            }

            assert input != null;
            if (input.matches("\\d+")) {
                int index = Integer.parseInt(input);
                if (index < 0 || index >= files.length) {
                    player.sendMessage("[#D1DBF2FF]Invalid snapshot index.[]");
                    return;
                }
                targetFile = files[index];
            } else {
                boolean matched = false;
                for (int i = 0; i < readableNames.size(); i++) {
                    if (readableNames.get(i).equalsIgnoreCase(input)) {
                        targetFile = files[i];
                        matched = true;
                        break;
                    }
                }
                if (!matched) {
                    player.sendMessage("[#D1DBF2FF]No snapshot matches name '" + input + "'[]");
                    return;
                }
            }

            final java.io.File finalTargetFile = targetFile;

            try {

                if (Vars.state.isGame()) {
                    Groups.player.each(eplayer -> eplayer.kick(Packets.KickReason.serverRestarting));
                    Vars.state.set(GameState.State.menu);
                    Vars.net.closeServer();
                }
                reloadWorld(() -> {
                    SaveIO.load(new Fi(finalTargetFile));
                    Vars.state.set(GameState.State.playing);
                    netServer.openServer();
                    resetGameState();
                });

                String displayName = finalTargetFile.getName().replace("autosave-", "").replace(".msav", "");
                player.sendMessage("[#D1DBF2DD]Rolled back to snapshot: " + displayName + "[]");

            } catch (Exception e) {
                player.sendMessage("[#D1DBF2DD]Failed to load snapshot.[]");
            }
        });

        handler.<Player>register("upload", "", (args, player) -> {
            String playerUUID = player.uuid();
            int current = rankData.containsKey(playerUUID) ? rankData.get(playerUUID) : 0;
            if (current > 300 || player.admin) {
                String token = TokenManager.generateToken();
                int port = config.port;
                String url = "http://" + globalUrl + ":" + port + "/?token=" + token;
                player.sendMessage("[#FFFFFFFF]Upload link (valid 5 min): [][#D1DBF2FF]" + url + "[]");
                Call.openURI(player.con, url);
            } else {
                player.sendMessage("[#D1DBF2FF]You need 300+ points or admin to upload maps. Max size: 200×200. Must fit current mode.");
            }
        });

        handler.<Player>register("discord", "", (args, player) -> {
            String url ="https://discord.gg/ajvwQMx8";
            player.sendMessage("[#FFFFFFFF]Invite link: [][#D1DBF2FF]" + url + "[]");
            Call.openURI(player.con, url);
        });

        handler.<Player>register("toggle", "Admin status toggle", (args, player) -> {
            String playerUUID = player.uuid();
            int current = rankData.containsKey(playerUUID) ? rankData.get(playerUUID) : 0;
            boolean isApproved = approvedAdmins.getOrDefault(player.uuid(), false);

            if (current > 5000 || isApproved || player.admin) {

                if (!player.admin) {
                    player.admin = true;
                    player.sendMessage("[#D1DBF2FF]You are now an admin.[]");
                } else {
                    player.admin = false;
                    player.sendMessage("[#D1DBF2FF]You are no longer an admin.[]");

                    approvedAdmins.put(player.uuid(), true);
                }

            } else {
                player.sendMessage("[#D1DBF2FF]You need at least 5000 points to become admin. Current: "
                        + String.format("%d", current) + "[]");
            }
        });


        handler.<Player>register("tr", "[language]", "Set your language for automatic translation", (args, player) -> {
            if (args.length != 1) {
                player.sendMessage("[#D1DBF2FF]Usage: /tr [auto/off/zh/en/es/fr/de/ja/ko/ru/tr][]");
                return;
            }

            String targetLanguage = args[0];

            if (targetLanguage.equals("off")) {
                playerLang.put(player.uuid(), "off");
                player.sendMessage("[#D1DBF2FF]Automatic translation turned off.[]");
                return;
            }
            if (targetLanguage.equals("auto")) {
                playerLang.put(player.uuid(), player.locale());
                player.sendMessage("[#D1DBF2FF]The language is automatically set to " + player.locale + ".[]");
                return;
            }

            if (!languageMapping.containsKey(targetLanguage)) {
                player.sendMessage("[#D1DBF2FF]Invalid language name. Please use a valid language name." + "[]");
                return;
            }

            playerLang.put(player.uuid(), languageMapping.get(targetLanguage));
            player.sendMessage("[#D1DBF2FF]Language set to " + targetLanguage + " for translations.[]");
        });

        handler.<Player>register("maps", "[page]", "List all available maps with pagination", (args, player) -> {
            int page = 1;
            if (args.length > 0) {
                try {
                    page = Integer.parseInt(args[0]);
                } catch (NumberFormatException e) {
                    player.sendMessage("[#D1DBF2FF]Invalid page number.[]");
                    return;
                }
            }

            Seq<Map> availableMaps = Vars.maps.customMaps().isEmpty() ? Vars.maps.defaultMaps() : Vars.maps.customMaps();

            int mapsPerPage = 5;
            int totalMaps = availableMaps.size;
            int totalPages = (int) Math.ceil((double) totalMaps / mapsPerPage);

            if (page < 1 || page > totalPages) {
                player.sendMessage("[#D1DBF2FF]Invalid page number. Please enter a valid page number between 1 and " + totalPages + ".[]");
                return;
            }

            int startIndex = (page - 1) * mapsPerPage;
            int endIndex = Math.min(page * mapsPerPage, totalMaps);

            StringBuilder mapList = new StringBuilder("[#FFFFFFFF]Current Map: [][#D1DBF2FF]" + Vars.state.map.name() + "[]\n");
            mapList.append("[#FFFFFFFF]Available Maps (Page ").append(page).append("/").append(totalPages).append("):[]\n");

            for (int i = startIndex; i < endIndex; i++) {
                mapList.append("[#D1DBF2FF]").append(i + 1).append(". ").append(availableMaps.get(i).name()).append("[]\n");
            }

            player.sendMessage(mapList.toString());
        });

        handler.<Player>register("lb", "Toggle leaderboard display.", (args, player) -> {
            if (lbEnabled.contains(player.uuid())) {
                lbEnabled.remove(player.uuid());
                player.sendMessage("[#D1DBF2FF]Leaderboard display disabled.[]");
            } else {
                lbEnabled.add(player.uuid());
                player.sendMessage("[#D1DBF2FF]Leaderboard display enabled.[]");
            }
        });

        handler.<Player>register("rank", "[page]", "rankings", (args, player) -> {
            int page = 1;
            if (args.length > 0) {
                try {
                    page = Integer.parseInt(args[0]);
                } catch (NumberFormatException e) {
                    player.sendMessage("[#D1DBF2FF]Invalid page number.[]");
                    return;
                }
            }

            int myScore = rankData.get(player.uuid(), 0);

            Seq<String> uuids = rankData.keys().toSeq().select(id -> rankData.get(id, 0) > 0);
            uuids.sort((a, b) -> Integer.compare(rankData.get(b, 0), rankData.get(a, 0)));

            int playersPerPage = 10;
            int totalPlayers = uuids.size;
            int totalPages = (int) Math.ceil((double) totalPlayers / playersPerPage);

            if (totalPlayers == 0) {
                player.sendMessage("[#D1DBF2FF]No players have any points yet.[]");
                return;
            }

            if (page < 1 || page > totalPages) {
                player.sendMessage("[#D1DBF2FF]Invalid page number. Please enter a valid page number between 1 and " + totalPages + ".[]");
                return;
            }

            StringBuilder message = new StringBuilder();

            if (myScore > 0) {
                int myRank = uuids.indexOf(player.uuid()) + 1;
                message.append("[#FFFFFFFF]Your Rank: [][#D1DBF2FF]").append(myRank).append("/").append(totalPlayers)
                        .append(" | ").append(myScore).append(" []\n");
            } else {
                message.append("[#D1DBF2FF]You have no points yet.[]\n");
            }
            message.append("[#FFFFFFFF]Rankings (Page ").append(page).append("/").append(totalPages).append("):[]\n");

            int startIndex = (page - 1) * playersPerPage;
            int endIndex = Math.min(page * playersPerPage, totalPlayers);

            for (int i = startIndex; i < endIndex; i++) {
                String id = uuids.get(i);
                String name = Vars.netServer.admins.getInfo(id) != null ? Vars.netServer.admins.getInfo(id).lastName : "<unknown>";
                int score = rankData.get(id, 0);
                message.append("[#D1DBF2FF]").append(i + 1).append(". ").append(name).append(": ").append(score).append(" []\n");
            }

            player.sendMessage(message.toString());
        });

        handler.<Player>register("rtv", "[mapId/partialName]", "Vote to change map", (args, player) -> {
            if (currentVote) {
                player.sendMessage("[#D1DBF2DD]There is already a voting process.[]");
                return;
            }
            if (args.length == 0) {
                Seq<Map> availableMaps = Vars.maps.customMaps().isEmpty() ? Vars.maps.defaultMaps() : Vars.maps.customMaps();
                Map randomMap = availableMaps.get(new Random().nextInt(availableMaps.size));
                startVote(player, randomMap);
            } else {
                String input = args[0];
                Seq<Map> availableMaps = Vars.maps.customMaps().isEmpty() ? Vars.maps.defaultMaps() : Vars.maps.customMaps();

                if (input.matches("\\d+")) {
                    int mapIndex = Integer.parseInt(input) - 1;
                    if (mapIndex >= 0 && mapIndex < availableMaps.size) {
                        startVote(player, availableMaps.get(mapIndex));
                    } else {
                        player.sendMessage("[#D1DBF2DD]Invalid map number.[]");
                    }
                } else {
                    Map matchedMap = null;
                    for (Map map : availableMaps) {
                        if (map.name().toLowerCase().contains(input.toLowerCase())) {
                            matchedMap = map;
                            break;
                        }
                    }

                    if (matchedMap == null) {
                        player.sendMessage("[#D1DBF2DD]No map found.[]");
                        return;
                    }
                    startVote(player, matchedMap);
                }
            }
        });
    }
}
