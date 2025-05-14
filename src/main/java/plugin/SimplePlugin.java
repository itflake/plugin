package plugin;

import arc.Events;
import arc.math.Mathf;
import arc.net.Server;
import arc.util.*;
import arc.util.Timer;
import mindustry.Vars;
import mindustry.content.UnitTypes;
import mindustry.core.GameState;
import mindustry.core.NetServer;
import mindustry.core.Version;
import mindustry.game.EventType;
import mindustry.game.EventType.*;
import mindustry.game.Gamemode;
import mindustry.game.Team;
import mindustry.game.Teams;
import mindustry.gen.*;
import mindustry.io.SaveIO;
import mindustry.mod.*;
import mindustry.maps.Map;
import arc.func.Cons;
import arc.util.serialization.JsonReader;
import mindustry.net.*;
import mindustry.net.Administration.Config;
import arc.struct.Seq;
import mindustry.type.Weather;
import mindustry.ui.Menus;
import mindustry.world.blocks.storage.CoreBlock;
import mindustry.content.Weathers;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.*;
import java.util.Arrays;
import arc.files.Fi;
import arc.util.serialization.Json;
import arc.struct.*;
import arc.Core;

import static mindustry.Vars.*;
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
    public static final JsonReader reader = new JsonReader();
    private static boolean voteInProgress = false;
    private static boolean kickVote = false;
    private static Player voteInitiator = null;
    private static Map targetMap = null;
    private static Player targetPlayer = null;
    private static final Seq<String> votes = new Seq<>();
    private static Timer.Task voteTimer = null;
    private static final float voteDuration = 30f;
    private final HashSet<String> lbEnabled = new HashSet<>();
    private final StringBuilder updatedBuilder = new StringBuilder();
    private static final double ratio = 0.6;
    private static final ObjectMap<String, Timer.Task> confirmTasks = new ObjectMap<>();
    private long gameStartTime = Time.millis();
    private static final Set<String> knownIPs = new HashSet<>();
    private static final Fi ipFile = Vars.saveDirectory.child("ip-list.json");
    private static final Json json = new Json();
    private static final Fi rankFile = Vars.saveDirectory.child("rank.json");
    private static final Fi playtimeFile = Vars.saveDirectory.child("playtime.json");
    private static final ObjectMap<String, Float> playtimeData = new ObjectMap<>();
    private static final ObjectMap<String, Integer> rankData = new ObjectMap<>();
    private final ObjectMap<String, Long> lastUnloadMap = new ObjectMap<>();
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
        votes.clear();
        playerTeams.clear();
        lostTeam.clear();
        voteInProgress = false;
        kickVote = false;
        voteInitiator = null;
        targetMap = null;
        targetPlayer = null;
        if (voteTimer != null) {
            voteTimer.cancel();
            voteTimer = null;
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
        updateRank(playerUUID, current + rate);
    }

    public static void updateRank(String playerUUID, int rate) {
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
    public void collectLeaderboardData() {
        Seq<Player> onlineWithScore = Groups.player.copy().select(p -> rankData.get(p.uuid(), 0) > 0);
        updatedBuilder.setLength(0);
        updatedBuilder.append("[#FFFFFF]Leaderboard[]\n\n");

        if (!onlineWithScore.isEmpty()) {
            onlineWithScore.sort((a, b) -> Integer.compare(rankData.get(b.uuid(), 0), rankData.get(a.uuid(), 0)));
            for (int i = 0; i < onlineWithScore.size; i++) {
                Player p = onlineWithScore.get(i);
                int score = rankData.get(p.uuid(), 0);
                updatedBuilder.append("[#DEE5F5FF]").append(i + 1).append(". ")
                        .append(p.name).append(": [#DEE5F5FF]")
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

    public static void reloadWorld(Runnable runnable) {
        try {
            WorldReloader reloader = new WorldReloader();
            reloader.begin();
            runnable.run();
            reloader.end();
        } catch (Exception e) {
            Log.err("Error while reloading map: " + e.getMessage());
        }
    }

    private static final int playerMenuId = Menus.registerMenu((player, choice) -> {
        if (choice == 1) {
            String playerUUID = player.uuid();
            Timer.Task task = confirmTasks.remove(playerUUID);
            if (task != null) {
                task.cancel();
            }
            addIP(player.con.address);
            String raw = "Welcome to snow!";
            String lang = player.locale();
            translate(raw, "auto", lang, translated -> Call.announce(player.con, translated), () -> Call.announce(player.con, raw));
            Timer.schedule(() -> showMapLabel(player), 5f);
        } else if (choice == 0) {
            String playerUUID = player.uuid();
            Timer.Task task = confirmTasks.remove(playerUUID);
            if (task != null) {
                task.cancel();
            }
            player.con.kick();
        }
    });

    private static void showMenu(NetConnection con, String[] options) {
        String[][] opts = {{options[0], options[1]}};
        Call.menu(con, SimplePlugin.playerMenuId, "Welcome to snow", "Click \"Confirm\" to start.", opts);
    }

    public NetServer.TeamAssigner assign = (player, players) -> {
        if(state.rules.pvp){
            boolean hasLost = Boolean.TRUE.equals(lostTeam.get(player.uuid()));
            Team previousTeam = playerTeams.get(player.uuid());
            if (hasLost) {
                Call.announce(player.con, "Your team has lost. Please wait for the next round.");
                return Team.derelict;
            } else if(previousTeam != null){
                Teams.TeamData prevData = state.teams.get(previousTeam);
                if(prevData != null && previousTeam != Team.derelict && prevData.hasCore()){
                    int activeTeamCount = state.teams.getActive().count(data ->
                            data.team != Team.derelict && data.hasCore());
                    int totalPlayers = 0;
                    int teamCount = 0;
                    for(Player p : players){
                        if(p != player){
                            totalPlayers++;
                            if(p.team() == previousTeam){
                                teamCount++;
                            }
                        }
                    }
                    int average = (int)Math.ceil((double)totalPlayers / Math.max(activeTeamCount, 1));
                    if(teamCount <= average){
                        return previousTeam;
                    }
                }
            } else  {
                Teams.TeamData re = state.teams.getActive().min(data -> {
                    if ((state.rules.waveTeam == data.team && state.rules.waves) || !data.hasCore() || data.team == Team.derelict)
                        return Integer.MAX_VALUE;

                    int count = 0;
                    for (Player other : players) {
                        if (other.team() == data.team && other != player) {
                            count++;
                        }
                    }
                    return (float) count + Mathf.random(-0.1f, 0.1f);
                });
                return re == null ? null : re.team;
            }
        }
        return state.rules.defaultTeam;
    };



    private static void beginVote(Player initiator, boolean isKick, Map map, Player target, String reason) {
        voteInProgress = true;
        voteInitiator = initiator;
        kickVote = isKick;
        targetMap = map;
        targetPlayer = target;
        votes.clear();
        votes.add(initiator.uuid());
        int req = (int)Math.ceil(Groups.player.size() * ratio);

        StringBuilder message = new StringBuilder();

        if (isKick) {
            message.append("[#F1F1F1FF]").append(initiator.name)
                    .append("[][#DEE5F5FF] started a vote to kick [#F1F1F1FF]")
                    .append(target.name)
                    .append("[][#DEE5F5FF]. [white](1/").append(req).append(")[]");
            if (reason != null && !reason.trim().isEmpty()) {
                message.append("\n[#DEE5F5FF]Reason: [white]").append(reason.trim());
            }
            message.append("\n[#DEE5F5FF]Type [#F1F1F1FF]y[] to vote.[]");
        } else {
            message.append("[#F1F1F1FF]").append(initiator.name)
                    .append("[][#DEE5F5FF] started a vote to change the map to [#F1F1F1FF]")
                    .append(map.name())
                    .append("[][#DEE5F5FF]. [white](1/").append(req).append(")[]\n[#DEE5F5FF]Type [#F1F1F1FF]y[] to vote.[]");
        }

        Call.sendMessage(message.toString());
        voteTimer = Timer.schedule(() -> {
            if (votes.size < req) {
                Call.sendMessage("[#DEE5F5FF]Vote failed.");
            }
            endVote();
        }, voteDuration);
    }


    private static void endVote() {
        voteInProgress = false;
        kickVote = false;
        voteInitiator = null;
        targetMap = null;
        targetPlayer = null;
        votes.clear();
        if (voteTimer != null) {
            voteTimer.cancel();
            voteTimer = null;
        }
    }

    private static void loadMap(Map map) {
        if (map == null) return;
        Gamemode mode = state.rules.mode();
        java.io.File folder = new java.io.File("config/snapshots");
        if (folder.exists()) {
            for (java.io.File file : Objects.requireNonNull(folder.listFiles((dir, name) -> name.startsWith("autosave-")))) {
                //noinspection ResultOfMethodCallIgnored
                file.delete();
            }
        }
        reloadWorld(() -> {
            state.map = map;
            Vars.world.loadMap(map);
            state.rules = map.applyRules(mode);
            Log.info("Using gamemode: @", state.rules.mode().name());
            Vars.logic.play();
        });
    }

    private static final String[] bannedWords = {
            "admin", "fuck", "傻逼", "ばか", "병신", "лох", "nigga", "nigger", "习近平", "金正恩", "卐"
    };

    private boolean containsBannedWord(String name) {
        String lower = name.toLowerCase();
        for (String word : bannedWords) {
            if (lower.contains(word)) return true;
        }
        return false;
    }

    private boolean isYes(String message) {
        return "y".equals(message);
    }

    static String wrapText(String text) {
        StringBuilder wrappedText = new StringBuilder();
        int start = 0;

        while (start < text.length()) {
            int end = Math.min(start + 50, text.length());
            wrappedText.append(text, start, end).append("\n");
            start = end;
        }

        return wrappedText.toString();
    }

    static void showMapLabel(Player player) {
        Building build = Groups.build.find(b -> b instanceof CoreBlock.CoreBuild && b.team == player.team());
        CoreBlock.CoreBuild core = build instanceof CoreBlock.CoreBuild ? (CoreBlock.CoreBuild) build : null;

        String mapName = state.map.name();
        String mapAuthor = state.map.author();
        String mapDesc = state.map.description();

        String text = "[white]" + mapName;
        if (mapAuthor != null && !mapAuthor.trim().isEmpty()) {
            text += "\n\n[#DEE5F5FF]" + mapAuthor.trim();
        }
        if (mapDesc != null && !mapDesc.trim().isEmpty()) {
            text += "\n\n[#DEE5F5FF]" + wrapText(mapDesc.trim());
        }

        float x = core != null ? core.x : Vars.world.unitWidth() / 2f;
        float y = core != null ? core.y : Vars.world.unitHeight() / 2f;

        Call.labelReliable(player.con, text, 10f, x, y);
    }

    public static void writeString(ByteBuffer buffer, String string, int maxlen) {
        if (string == null) {
            throw new IllegalArgumentException("String cannot be null.");
        }

        byte[] bytes = string.getBytes(StandardCharsets.UTF_8);
        if (bytes.length > maxlen) {
            bytes = Arrays.copyOfRange(bytes, 0, maxlen);
        }

        buffer.put((byte) bytes.length);

        buffer.put(bytes);
    }

    public static void applyfly() {
        UnitTypes.risso.flying = true;
        UnitTypes.minke.flying = true;
        UnitTypes.bryde.flying = true;
        UnitTypes.sei.flying = true;
        UnitTypes.omura.flying = true;
        UnitTypes.retusa.flying = true;
        UnitTypes.oxynoe.flying = true;
        UnitTypes.cyerce.flying = true;
        UnitTypes.aegires.flying = true;
        UnitTypes.navanax.flying = true;

        UnitTypes.crawler.flying = true;
        UnitTypes.atrax.flying = true;
        UnitTypes.spiroct.flying = true;
    }

    @Override
    public void init() {
        load();
        webInit();
        loadTime();
        loadRank();
        showLeaderboard();
        ArcNetProvider provider = Reflect.get(Vars.net, "provider");
        Server server = Reflect.get(provider, "server");
        final String[] footer = {""};

        server.setDiscoveryHandler((address, handler) -> {
            String rawName = Administration.Config.serverName.string();
            String rawDesc = Administration.Config.desc.string();
            String mapName = state.map.name();
            String modeName = state.rules.modeName;

            int totalPlayers = Core.settings.getInt("totalPlayers", Groups.player.size());
            int wave = state.wave;
            int build = Version.build;
            int limit = Math.max(netServer.admins.getPlayerLimit(), 0);

            String displayDesc = rawDesc.equals("off") ? footer[0] : rawDesc + footer[0];
            ByteBuffer buffer = ByteBuffer.allocate(500);
            writeString(buffer, rawName, 100);
            writeString(buffer, mapName, 64);
            buffer.putInt(totalPlayers);
            buffer.putInt(wave);
            buffer.putInt(build);
            writeString(buffer, Version.type, 50);
            buffer.put((byte) state.rules.mode().ordinal());
            buffer.putInt(limit);
            writeString(buffer, displayDesc, 200);

            if (modeName != null) {
                writeString(buffer, "[#DEE5F5FF]" + modeName, 50);
            }

            buffer.position(0);
            handler.respond(buffer);
        });

        Vars.net.handleServer(Packets.ConnectPacket.class, (con, packet) -> {
            if (con.kicked) return;
            Events.fire(new EventType.ConnectPacketEvent(con, packet));
            con.connectTime = Time.millis();
            String uuid = packet.uuid;
            String name = packet.name;
            String ip = con.address;
            if (netServer.admins.isIPBanned(con.address) || netServer.admins.isSubnetBanned(con.address) || netServer.admins.isIDBanned(uuid)) {
                con.kick("You have been banned. If you think it is unreasonable, please go to our discord group to unban!\nhttps://discord.gg/6vxqgszCkE", 0);
                return;
            }
            String plainName = name.replaceAll("\\[#(?:[a-fA-F0-9]{6}|[a-fA-F0-9]{8})\\]|\\[\\]", "");
            if (name.trim().isEmpty() || containsBannedWord(plainName) || plainName.length() < 3 || plainName.length() > 10) {
                con.kick("Your name may be illegal, or exceed 10 characters, or be less than 3 characters.", 0);
                return;
            }

            if (con.hasBegunConnecting) {
                con.kick(Packets.KickReason.idInUse, 0);
                return;
            }
            con.hasBegunConnecting = true;
            con.mobile = packet.mobile;
            if (packet.uuid == null || packet.usid == null) {
                con.kick(Packets.KickReason.idInUse, 0);
                return;
            }

            long kickTime = Vars.netServer.admins.getKickTime(uuid, ip);
            if (Time.millis() < kickTime) {
                con.kick("You were recently kicked. Please wait.", 0);
                return;
            }

            Seq<String> extraMods = packet.mods.copy();
            Seq<String> missingMods = mods.getIncompatibility(extraMods);

            if (!extraMods.isEmpty() || !missingMods.isEmpty()) {

                StringBuilder result = new StringBuilder("[accent]Incompatible mods![]\n\n");

                if (!missingMods.isEmpty()) {
                    result.append("Missing:[lightgray]\n").append("> ").append(missingMods.toString("\n> "));
                    result.append("[]\n");
                }

                if (!extraMods.isEmpty()) {
                    result.append("Unnecessary mods:[lightgray]\n").append("> ").append(extraMods.toString("\n> "));
                }

                con.kick(result.toString(), 0);
            }

            if (packet.versionType == null || ((packet.version == -1 || !packet.versionType.equals(Version.type)) && Version.build != -1 && !netServer.admins.allowsCustomClients())) {
                con.kick(!Version.type.equals(packet.versionType) ? Packets.KickReason.typeMismatch : Packets.KickReason.customClient, 0);
                return;
            }

            if (packet.version != Version.build && Version.build != -1 && packet.version != -1) {
                con.kick(packet.version > Version.build ? Packets.KickReason.serverOutdated : Packets.KickReason.clientOutdated, 0);
                return;
            }

            if (Groups.player.contains(p -> p.name.equalsIgnoreCase(packet.name))) {
                con.kick(Packets.KickReason.nameInUse, 0);
                return;
            }

            if (!netServer.admins.isAdmin(uuid, packet.usid) && netServer.admins.getPlayerLimit() > 0 && Groups.player.size() >= netServer.admins.getPlayerLimit()) {
                con.kick(Packets.KickReason.playerLimit);
                return;
            }

            if (Groups.player.contains(p -> p.uuid().equals(uuid) || p.usid().equals(packet.usid))) {
                con.kick(Packets.KickReason.idInUse, 0);
                return;
            }

            for (NetConnection other : Vars.net.getConnections()) {
                if (other != con && uuid.equals(other.uuid)) {
                    con.kick(Packets.KickReason.idInUse, 0);
                    return;
                }
            }

            packet.name = Vars.netServer.fixName(packet.name);
            if (packet.locale == null) packet.locale = "en";
            Vars.netServer.admins.updatePlayerJoined(uuid, ip, packet.name);

            if (packet.version == -1) {
                con.modclient = true;
            }

            if (!Vars.netServer.admins.isWhitelisted(packet.uuid, packet.usid)) {
                con.kick("You are not whitelisted.", 0);
                return;
            }

            Administration.PlayerInfo info = Vars.netServer.admins.getInfo(uuid);

            Player player = Player.create();
            player.admin = Vars.netServer.admins.isAdmin(uuid, packet.usid);
            player.con = con;
            player.con.usid = packet.usid;
            player.con.uuid = uuid;
            player.con.mobile = packet.mobile;
            player.name = packet.name;
            player.locale = packet.locale;
            player.color.set(packet.color).a(1f);
            if (!player.admin && !info.admin) {
                info.adminUsid = packet.usid;
            }
            con.player = player;
            player.team(assign.assign(player, Groups.player));
            Vars.netServer.sendWorldData(player);
            Events.fire(new EventType.PlayerConnect(player));
        });

        Vars.netServer.admins.addActionFilter(action -> {
            if (action.type == Administration.ActionType.depositItem && action.player != null) {
                String uuid = action.player.uuid();
                long now = System.nanoTime();
                long last = lastUnloadMap.get(uuid, 0L);
                if (now - last < 1_500_000_000L) {
                    return false;
                }
                lastUnloadMap.put(uuid, now);
            }
            return true;
        });

        Vars.net.handleServer(Packets.Connect.class, (con, packet) -> {
            Events.fire(new EventType.ConnectionEvent(con));
            var connections = Seq.with(Vars.net.getConnections()).select(c -> c.address.equals(con.address));
            if (connections.size >= 4) {
                Vars.netServer.admins.blacklistDos(con.address);
                connections.each(NetConnection::close);
            }
        });

        netServer.admins.addChatFilter((player, message) -> {
            if (message == null) return null;
            String lowerMessage = message.trim().toLowerCase();
            if (message.startsWith("/") || isYes(lowerMessage)) {
                if (voteInProgress && isYes(lowerMessage) && player != voteInitiator && !votes.contains(player.uuid())) {
                    votes.add(player.uuid());
                    int currentVotes = votes.size;
                    int required = (int) Math.ceil(Groups.player.size() * ratio);
                    if (kickVote && targetPlayer != null) {
                        Call.sendMessage("[#DEE5F5FF]" + player.name + " voted to kick " + targetPlayer.name + ". [white](" + currentVotes + "/" + required + ")[]");
                        if (currentVotes >= required) {
                            Call.sendMessage("[#DEE5F5FF]Vote passed. Kicking " + targetPlayer.name + ".[]");
                            targetPlayer.kick("[#DEE5F5FF]You were kicked by vote.[]");
                            endVote();
                        }
                    } else if (!kickVote && targetMap != null) {
                        Call.sendMessage("[#F1F1F1FF]" + player.name + "[][#DEE5F5FF] voted to change the map to [][#F1F1F1FF]" + targetMap.name() + "[][#F1F1F1FF]. " + currentVotes + "[][#DEE5F5FF] votes, [][#F1F1F1FF]" + required + "[][#DEE5F5FF] required." + "\n" + "Type[] [#F1F1F1FF]y[] [#DEE5F5FF]to vote.");
                        if (currentVotes >= required) {
                            Call.sendMessage("[#DEE5F5FF]Vote passed. Loading map " + targetMap.name() + ".[]");
                            loadMap(targetMap);
                            endVote();
                            resetGameState();
                            gameStartTime = Time.millis();
                        }
                    }
                }
            } else {
                String name = player.name;
                player.sendMessage("[#DEE5F5FF]" + name + "[]: [#EDF4FCCC]" + message + "[]");
                Groups.player.each(receiver -> {
                    if (receiver == player) return;
                    String lang = playerLang.get(receiver.uuid());
                    boolean needTranslate = !receiver.locale.equals(player.locale()) && lang != null && !"off".equals(lang);
                    if (needTranslate) {
                        translate(message, "auto", lang, translated -> {
                            if (!translated.equals(message)) {
                                receiver.sendMessage( "[#DEE5F5FF]" + name + "[]: [#EDF4FCCC]" + message + " [#A9B0B3CC](" + translated + ")[]");
                            } else {
                                receiver.sendMessage("[#DEE5F5FF]" + name + "[]: [#EDF4FCCC]" + message + "[]");
                            }
                        }, () -> receiver.sendMessage("[#DEE5F5FF]" + name + "[]: [#EDF4FCCC]" + message + "[]"));
                    } else {
                        receiver.sendMessage("[#DEE5F5FF]" + name + "[]: [#EDF4FCCC]" + message + "[]");
                    }
                });

            }

            return null;
        });
    }

    public SimplePlugin() {
        Events.on(ServerLoadEvent.class, s -> {
            Timer.schedule(() -> {
                if (!state.isGame()) {
                    Config.desc.set("Welcome to snow!");return;
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

        Events.on(WorldLoadEvent.class, e -> Timer.schedule(() -> Groups.player.each(SimplePlugin::showMapLabel), 2f));

        Events.on(EventType.PlayEvent.class, event -> {
            String mapDescription = Vars.state.map.description();
            Weather.WeatherEntry entry = new Weather.WeatherEntry();
            entry.weather = Weathers.snow;
            entry.intensity = 0.3f;
            entry.always = true;
            state.rules.weather.add(entry);
            if (mapDescription.contains("[@fly]")) {
                applyfly();
            }
        });
        Events.on(PlayerLeave.class, e -> {
            Player player = e.player;
            if (player == null || player.uuid() == null) return;
            String uuid = player.uuid();
            String name = player.name != null ? player.name : "Unknown";

            if (votes.contains(uuid)) {
                votes.remove(uuid);
                int cur = votes.size;
                int req = (int) Math.ceil(Groups.player.size() * ratio);
                Call.sendMessage("[#D1DBF2DD]" + name + " left, " + cur + " votes, " + req + " required.[]");
            }

            if (state.rules != null
                    && state.rules.mode() == Gamemode.pvp
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
                    state.teams.get(e.winner).hasCore() &&
                    Groups.player.count(p -> p.team() == e.winner) > 0) {
                Seq<Player> winners = Groups.player.copy().select(p -> p.team() == e.winner);
                int total = winners.size;
                int score = Math.max(25, 40 - total * 4);

                for (Player p : Groups.player) {
                    if (p.team() == e.winner) {
                        addRank(p.uuid(), score);
                        Call.announce(p.con, "Victory! You got [#D1DBF2DD]" + score + "[] points.");
                    } else {
                        Call.announce(p.con, "Game Over. Team " + e.winner.name + " wins.");
                    }
                }
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
            String ip = player.con.address != null ? player.con.address : "unknown";
            String playerUUID = player.uuid() != null ? player.uuid() : UUID.randomUUID().toString(); // fallback uuid
            if (!playtimeData.containsKey(playerUUID)) {
                updatePlaytime(playerUUID, 0f);
            }
            if (isNewIP(ip)) {
                showMenu(player.con, new String[]{"Cancel", "Confirm"});
                Timer.Task task = Timer.schedule(() -> {
                    Player p = Groups.player.find(pl -> pl.uuid().equals(playerUUID));
                    if (p == null || p.con == null) return;
                    if (playerUUID != null) {
                        p.kick("Did not confirm within 15s");
                    }
                    confirmTasks.remove(playerUUID);
                }, 10f);
                confirmTasks.put(playerUUID, task);
            } else {
                Timer.schedule(() -> showMapLabel(player), 1f);
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
                        if (state.rules.mode() == Gamemode.pvp) {
                            int size = Math.max(1, team.data().players.size);
                            int penalty = Math.max(20, 50 - size * 3);
                            Integer oldValue = rankData.get(p.uuid());
                            int old = oldValue != null ? oldValue : 0;
                            int updated = Math.max(0, old - penalty);
                            updateRank(p.uuid(), updated);
                            lostTeam.put(p.uuid(), true);
                            String message = "Your team has lost. You lost [#D1DBF2DD]" + penalty + " []points.";
                            Call.announce(p.con, message);
                        } else {
                            String message = "You has lost.";
                            Call.announce(p.con, message);
                        }
                    });
                }
            }
        });
    }


    @Override
    public void registerClientCommands(CommandHandler handler) {

        handler.removeCommand("a");
        handler.removeCommand("t");
        handler.removeCommand("vote");
        handler.removeCommand("votekick");
        handler.<Player>register("t", "<message...>", "Send a message only to your teammates.", (args, player) -> {
            if (args.length == 0) {
                player.sendMessage("[#DEE5F5FF]You must enter a message to send to your teammates.[#DEE5F5FF]");
                return;
            }
            String message = String.join(" ", args);
            String formatted = "[#DEE5F5FF]<Team> [#DEE5F5FF]" + player.name + "[]: [#DEE5F5FF]" + message + "[]";
            player.sendMessage(formatted);
            Groups.player.each(receiver -> {
                if (receiver == player || receiver.team() != player.team()) return;
                String lang = playerLang.get(receiver.uuid());
                boolean needsTranslation = !receiver.locale.equals(player.locale()) && lang != null && !lang.equals("off");
                String prefix = "[#DEE5F5FF]<Team> [#DEE5F5FF]" + player.name + "[]: [#DEE5F5FF]";
                if (needsTranslation) {
                    translate(message, "auto", lang,
                            translated -> {
                                if (!translated.equals(message)) {
                                    receiver.sendMessage(prefix + message + " [gray](" + translated + ")[]");
                                } else {
                                    receiver.sendMessage(prefix + message);
                                }
                            },
                            () -> receiver.sendMessage(prefix + message)
                    );
                } else {
                    receiver.sendMessage(prefix + message);
                }
            });
        });


        handler.<Player>register("help", "[page]", "List all available commands.", (args, player) -> {
            int page = 1;
            if (args.length > 0) {
                try {
                    page = Integer.parseInt(args[0]);
                } catch (NumberFormatException e) {
                    player.sendMessage("[#DEE5F5FF]Invalid page number.[]");
                    return;
                }
            }

            Seq<CommandHandler.Command> commandList = handler.getCommandList();
            int commandsPerPage = 15;
            int totalCommands = commandList.size;
            int totalPages = (int) Math.ceil((double) totalCommands / commandsPerPage);

            if (page < 1 || page > totalPages) {
                player.sendMessage("[#DEE5F5FF]Invalid page number. Please enter a valid page number between 1 and " + totalPages + ".[]");
                return;
            }

            int startIndex = (page - 1) * commandsPerPage;
            int endIndex = Math.min(page * commandsPerPage, totalCommands);

            StringBuilder result = new StringBuilder("[#DEE5F5FF]---- Available Commands " + page + "/" + totalPages + " ----[#F1F1F1FF]\n\n");

            for (int i = startIndex; i < endIndex; i++) {
                CommandHandler.Command cmd = commandList.get(i);
                result.append("[#DEE5F5FF]/").append(cmd.text);
                if (!cmd.paramText.isEmpty()) result.append(" [#F1F1F1FF]").append(cmd.paramText).append("[]");
                result.append("[] - [#F1F1F1FF]").append(cmd.description).append("[]\n");
            }

            player.sendMessage(result.toString());
        });

        handler.<Player>register("time", "Show time.", (args, player) -> {
            long duration = Time.timeSinceMillis(gameStartTime); // 毫秒
            int minutes = (int) (duration / 1000 / 60);
            float totalMinutes = playtimeData.get(player.uuid(), 0f);

            player.sendMessage("[#DEE5F5FF]Game played: [] [#DEE5F5FF]" + minutes + " minutes[]");
            player.sendMessage("[#DEE5F5FF]Your total play time: [] [#DEE5F5FF]" + String.format("%.2f", totalMinutes) + " minutes[]");
        });

        handler.<Player>register("rollback", "[name/index]", "Rollback to a snapshot", (args, player) -> {
            java.io.File folder = new java.io.File("config/snapshots");

            java.io.File[] files = folder.listFiles((dir, name) -> name.startsWith("autosave-") && name.endsWith(".msav"));
            if (files == null || files.length == 0) {
                player.sendMessage("[#DEE5F5FF]No snapshots available.[]");
                return;
            }

            Arrays.sort(files, Comparator.comparingLong(java.io.File::lastModified));

            List<String> readableNames = new ArrayList<>();
            for (java.io.File file : files) {
                String name = file.getName().replace("autosave-", "").replace(".msav", "");
                readableNames.add(name);
            }

            if (args.length == 0) {
                StringBuilder sb = new StringBuilder("[#FFFFFF]Available snapshots:[#DEE5F5FF]\n\n");
                for (int i = 0; i < readableNames.size(); i++) {
                    sb.append("[#DEE5F5FF]").append(i).append("[]: ").append(readableNames.get(i)).append("\n");
                }
                player.sendMessage(sb.toString());
                return;
            }

            java.io.File targetFile = null;
            String input = args[0];

            if (!player.admin && args[0] != null) {
                player.sendMessage("[#DEE5F5FF]You are not an admin.[]");
                return;
            }

            assert input != null;
            if (input.matches("\\d+")) {
                int index = Integer.parseInt(input);
                if (index < 0 || index >= files.length) {
                    player.sendMessage("[#DEE5F5FF]Invalid snapshot index.[]");
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
                    player.sendMessage("[#DEE5F5FF]No snapshot matches name '" + input + "'[]");
                    return;
                }
            }

            final java.io.File finalTargetFile = targetFile;

            try {
                if (state.isGame()) {
                    Groups.player.each(eplayer -> eplayer.kick(Packets.KickReason.serverRestarting));
                    state.set(GameState.State.menu);
                    Vars.net.closeServer();
                }

                reloadWorld(() -> {
                    SaveIO.load(new Fi(finalTargetFile));
                    state.set(GameState.State.playing);
                    netServer.openServer();
                    resetGameState();
                    lostTeam.clear();
                });

                String displayName = finalTargetFile.getName().replace("autosave-", "").replace(".msav", "");
                player.sendMessage("[#D1DBF2DD]Rolled back to snapshot: " + displayName + "[]");

            } catch (Exception e) {
                player.sendMessage("[#D1DBF2DD]Failed to load snapshot.[]");
            }
        });

        handler.<Player>register("upload", "Upload maps.", (args, player) -> {
            String playerUUID = player.uuid();
            int current = rankData.containsKey(playerUUID) ? rankData.get(playerUUID) : 0;
            if (current > 50 || player.admin) {
                String token = TokenManager.generateToken();
                int port = config.port;
                String url = "http://" + globalUrl + ":" + port + "/?token=" + token;
                player.sendMessage("[#F1F1F1FF]Upload link (valid 5 min): [][#DEE5F5FF]" + url + "[]");
                Call.openURI(player.con, url);
            } else {
                player.sendMessage("[#DEE5F5FF]You need 50+ points or admin to upload maps. Max size: 200×200. Must fit current mode.");
            }
        });

        handler.<Player>register("discord", "Discord link.", (args, player) -> {
            String url ="https://discord.gg/6vxqgszCkE";
            player.sendMessage("[#F1F1F1FF]Invite link: [][#DEE5F5FF]" + url + "[]");
            Call.openURI(player.con, url);
        });

        handler.<Player>register("toggle", "Toggle admin status.", (args, player) -> {
            String playerUUID = player.uuid();
            int current = rankData.containsKey(playerUUID) ? rankData.get(playerUUID) : 0;
            boolean isApproved = approvedAdmins.getOrDefault(player.uuid(), false);

            if (current > 1000 || isApproved || player.admin) {

                if (!player.admin) {
                    player.admin = true;
                    player.sendMessage("[#DEE5F5FF]You are now an admin.[]");
                } else {
                    player.admin = false;
                    player.sendMessage("[#DEE5F5FF]You are no longer an admin.[]");

                    approvedAdmins.put(player.uuid(), true);
                }

            } else {
                player.sendMessage("[#DEE5F5FF]You need at least 1000 points to become admin. Current: "
                        + String.format("%d", current) + "[]");
            }
        });


        handler.<Player>register("tr", "[language]", "Set your language for automatic translation.", (args, player) -> {
            if (args.length != 1) {
                player.sendMessage("[#DEE5F5FF]Usage: /tr [auto/off/zh/en/es/fr/de/ja/ko/ru/tr][]");
                return;
            }

            String targetLanguage = args[0];

            if (targetLanguage.equals("off")) {
                playerLang.put(player.uuid(), "off");
                player.sendMessage("[#DEE5F5FF]Automatic translation turned off.[]");
                return;
            }
            if (targetLanguage.equals("auto")) {
                playerLang.put(player.uuid(), player.locale());
                player.sendMessage("[#DEE5F5FF]The language is automatically set to " + player.locale + ".[]");
                return;
            }

            if (!languageMapping.containsKey(targetLanguage)) {
                player.sendMessage("[#DEE5F5FF]Invalid language name. Please use a valid language name." + "[]");
                return;
            }

            playerLang.put(player.uuid(), languageMapping.get(targetLanguage));
            player.sendMessage("[#DEE5F5FF]Language set to " + targetLanguage + " for translations.[]");
        });

        handler.<Player>register("maps", "[page]", "List all available maps.", (args, player) -> {
            int page = 1;
            if (args.length > 0) {
                try {
                    page = Integer.parseInt(args[0]);
                } catch (NumberFormatException e) {
                    player.sendMessage("[#DEE5F5FF]Invalid page number. Please enter a valid number.[]");
                    return;
                }
            }

            Seq<Map> availableMaps = Vars.maps.customMaps().isEmpty() ? Vars.maps.defaultMaps() : Vars.maps.customMaps();

            int mapsPerPage = 5;
            int totalMaps = availableMaps.size;
            int totalPages = (int) Math.ceil((double) totalMaps / mapsPerPage);

            if (page < 1 || page > totalPages) {
                player.sendMessage("[#DEE5F5FF]Invalid page number. Please enter a valid page number between 1 and " + totalPages + ".[]");
                return;
            }

            int startIndex = (page - 1) * mapsPerPage;
            int endIndex = Math.min(page * mapsPerPage, totalMaps);

            StringBuilder mapList = new StringBuilder();
            mapList.append("[#DEE5F5FF]---- Available Maps ").append(page).append("/").append(totalPages).append(" ----[#F1F1F1FF]\n\n");

            for (int i = startIndex; i < endIndex; i++) {
                Map map = availableMaps.get(i);
                String author = map.author() != null ? map.author() : "[#DEE5F5FF]Unknown";
                mapList.append("[#DEE5F5FF]").append(i + 1).append(". [#F1F1F1FF]").append(map.name()).append("[] ");
                mapList.append("[#DEE5F5FF]by [#F1F1F1FF]").append(author).append("[]\n");
            }

            player.sendMessage(mapList.toString());
        });


        handler.<Player>register("lb", "Toggle leaderboard display.", (args, player) -> {
            if (lbEnabled.contains(player.uuid())) {
                lbEnabled.remove(player.uuid());
                player.sendMessage("[#DEE5F5FF]Leaderboard display disabled.[]");
            } else {
                lbEnabled.add(player.uuid());
                player.sendMessage("[#DEE5F5FF]Leaderboard display enabled.[]");
            }
        });

        handler.<Player>register("rank", "[page]", "Rankings.", (args, player) -> {
            int page = 1;
            if (args.length > 0) {
                try {
                    page = Integer.parseInt(args[0]);
                } catch (NumberFormatException e) {
                    player.sendMessage("[#DEE5F5FF]Invalid page number.[]");
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
                player.sendMessage("[#DEE5F5FF]No players have any points yet.[]");
                return;
            }

            if (page < 1 || page > totalPages) {
                player.sendMessage("[#DEE5F5FF]Invalid page number. Please enter a valid page number between 1 and " + totalPages + ".[]");
                return;
            }

            StringBuilder message = new StringBuilder();

            if (myScore > 0) {
                int myRank = uuids.indexOf(player.uuid()) + 1;
                message.append("[#F1F1F1FF]Your Rank: [][#DEE5F5FF]").append(myRank).append("/").append(totalPlayers)
                        .append(" | ").append(myScore).append(" []\n");
            } else {
                message.append("[#DEE5F5FF]You have no points yet.[]\n");
            }
            message.append("[#F1F1F1FF]Rankings (Page ").append(page).append("/").append(totalPages).append("):[]\n");

            int startIndex = (page - 1) * playersPerPage;
            int endIndex = Math.min(page * playersPerPage, totalPlayers);

            for (int i = startIndex; i < endIndex; i++) {
                String id = uuids.get(i);
                String name = Vars.netServer.admins.getInfo(id) != null ? Vars.netServer.admins.getInfo(id).lastName : "<unknown>";
                int score = rankData.get(id, 0);
                message.append("[#DEE5F5FF]").append(i + 1).append(". ").append(name).append(": ").append(score).append(" []\n");
            }

            player.sendMessage(message.toString());
        });


        handler.<Player>register("rtv", "[mapId/name]","Vote to change map.", (args, player) -> {
            if (voteInProgress) {
                player.sendMessage("[#DEE5F5FF]A vote is already in progress.[]");
                return;
            }

            Seq<Map> maps = Vars.maps.customMaps().isEmpty() ? Vars.maps.defaultMaps() : Vars.maps.customMaps();
            Map selected = null;

            if (args.length > 0) {
                String input = args[0];
                if (input.matches("\\d+")) {
                    int index = Integer.parseInt(input) - 1;
                    if (index >= 0 && index < maps.size) selected = maps.get(index);
                } else {
                    for (Map map : maps) {
                        if (map.name().toLowerCase().contains(input.toLowerCase())) {
                            selected = map;
                            break;
                        }
                    }
                }
            } else {
                selected = maps.random();
            }

            if (selected == null) {
                player.sendMessage("[#DEE5F5FF]Map not found.[]");
                return;
            }

            if (Groups.player.size() <= 1 || player.admin) {
                Call.sendMessage("[#DEE5F5FF]" + player.name + " changed the map to " + selected.name() + ".[]");
                loadMap(selected);
                resetGameState();
                gameStartTime = Time.millis();
                return;
            }

            beginVote(player, false, selected, null, null);
        });

        handler.<Player>register("votekick", "[player] [reason]", "Start a vote to kick a player", (args, player) -> {
            if (voteInProgress) {
                player.sendMessage("[#DEE5F5FF]A vote is already in progress.[]");
            } else {
                if (args.length == 0) {
                    StringBuilder builder = new StringBuilder();
                    builder.append("[white]Players you can vote to kick:\n");
                    Groups.player.each(p -> p.team() == player.team() && !p.admin && p.con != null && p != player, p -> builder.append("[#DEE5F5FF]").append(p.name).append(" (#").append(p.id()).append(")\n"));
                    player.sendMessage(builder.toString());
                } else {
                    Player target;
                    if (args[0].length() > 1 && args[0].startsWith("#") && Strings.canParseInt(args[0].substring(1))) {
                        int id = Strings.parseInt(args[0].substring(1));
                        target = Groups.player.find(p -> p.id() == id);
                    } else {
                        target = Groups.player.find(p -> p.name().toLowerCase().contains(args[0].toLowerCase()));
                    }
                    String reason = args.length > 1 ? String.join(" ", Arrays.copyOfRange(args, 1, args.length)) : null;
                    if (target !=null) {
                        if (target == player) {
                            player.sendMessage("[#DEE5F5FF]You cannot kick yourself.[]");
                        } else if (target.admin) {
                            player.sendMessage("[#DEE5F5FF]You cannot kick an admin.[]");
                        } else if (player.admin) {
                            Call.sendMessage("[#DEE5F5FF]" + player.name + " kicked " + target.name + " directly. Reason: " + reason);
                            target.kick("[#DEE5F5FF]You were kicked by an admin. Reason: " + reason);
                        } else {
                            beginVote(player, true, null, target, reason);
                        }
                    } else {
                        player.sendMessage("[#DEE5F5FF]Player not found.[]");
                    }
                }
            }
        });

    }
}
