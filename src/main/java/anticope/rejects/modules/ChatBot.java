package anticope.rejects.modules;

import meteordevelopment.meteorclient.MeteorClient;
import meteordevelopment.meteorclient.events.game.ReceiveMessageEvent;
import meteordevelopment.meteorclient.events.world.TickEvent;
import meteordevelopment.meteorclient.settings.BoolSetting;
import meteordevelopment.meteorclient.settings.Setting;
import meteordevelopment.meteorclient.settings.SettingGroup;
import meteordevelopment.meteorclient.settings.StringSetting;
import meteordevelopment.meteorclient.systems.modules.Category;
import meteordevelopment.meteorclient.systems.modules.Module;
import meteordevelopment.meteorclient.utils.player.ChatUtils;
import meteordevelopment.orbit.EventHandler;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayNetworkHandler;
import net.minecraft.client.network.PlayerListEntry;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.reflect.TypeToken;

import java.io.Reader;
import java.io.Writer;
import java.lang.reflect.Type;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.MatchResult;
import java.util.regex.Pattern;

/**
 * ChatBotPlus
 *
 * - Offline messenger: !leave <player> <message>  (queued & auto-delivered on join)
 * - Inbox trigger:      !inbox
 * - Utilities:          !help, !ping (your own), !info (online count, dimension id, mc time)
 * - DM aware: parses common private-message formats and replies privately when triggered via DM
 * - LeakGuard: strips/blocks Starscript tokens and coord/camera-axis patterns from ALL outputs
 *
 * Notes:
 * - Never prints the bot's own coordinates, yaw, or pitch.
 * - Delivery detection uses the tablist (reliable across many servers).
 */
public class ChatBotPlus extends Module {
    // Category (shows up in Meteor GUI). If your addon already defines categories, reuse that instead.
    private static final Category CAT = new Category("Chat Tools");

    // JSON store
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private static final Type STORE_TYPE = new TypeToken<Map<String, List<OfflineMsg>>>() {}.getType();

    // ---------------- Settings ----------------
    private final SettingGroup gGeneral = settings.getDefaultGroup();
    private final SettingGroup gMessenger = settings.createGroup("Offline Messenger");
    private final SettingGroup gSafety = settings.createGroup("Leak Guard");

    private final Setting<String> prefix = gGeneral.add(new StringSetting.Builder()
        .name("command-prefix")
        .description("Prefix used to invoke commands.")
        .defaultValue("!")
        .build());

    private final Setting<Boolean> respondPublic = gGeneral.add(new BoolSetting.Builder()
        .name("public-replies")
        .description("Reply publicly by default (DM replies remain private).")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> enableInfo = gGeneral.add(new BoolSetting.Builder()
        .name("enable-info-commands")
        .description("Enable help/info/ping commands.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> enableOffline = gMessenger.add(new BoolSetting.Builder()
        .name("enable-offline-messenger")
        .description("Enable !leave and queued delivery on join.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> notifySender = gMessenger.add(new BoolSetting.Builder()
        .name("notify-sender-on-delivery")
        .description("Notify the original sender when their note is delivered.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> leakGuard = gSafety.add(new BoolSetting.Builder()
        .name("leak-guard")
        .description("Harden all outputs against coords/camera/tps leaks via Starscript or raw patterns.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> stripStarscript = gSafety.add(new BoolSetting.Builder()
        .name("strip-starscript-braces")
        .description("Replace any {...} with fullwidth braces so overlays can’t evaluate them.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> blockDangerTokens = gSafety.add(new BoolSetting.Builder()
        .name("block-dangerous-starscript")
        .description("Block replies containing sensitive Starscript vars like player.x, camera.yaw, server.tps.")
        .defaultValue(true)
        .build());

    private final Setting<Boolean> blockRawXYZ = gSafety.add(new BoolSetting.Builder()
        .name("block-raw-xyz-patterns")
        .description("Mask patterns that look like coordinates or labeled axes.")
        .defaultValue(true)
        .build());

    // ---------------- Storage ----------------
    private Path storePath;
    private Map<String, List<OfflineMsg>> store = new HashMap<>(); // key = lowercased target username
    private final Set<UUID> seenTablist = new HashSet<>();
    private int tickCounter = 0;

    // ---------------- Patterns / Guards ----------------
    // Public chat formats like: "<Name> message:" or "Name: message"
    private static final Pattern CHAT_NAME_MSG = Pattern.compile(
        "^\\s*[<\\[]?(?<name>[A-Za-z0-9_]{3,16})[>\\]]?\\s*[:»]\\s*(?<msg>.+)$"
    );

    // Private-message (DM) formats (Essentials-like, vanilla-like, bracketed PMs)
    private static final Pattern DM_ESS_FROM = Pattern.compile(
        "(?i)^\\s*(?:from\\s+)?(?<name>[A-Za-z0-9_]{3,16})\\s*->\\s*(?:me|you)\\s*:\\s*(?<msg>.+)$"
    );
    private static final Pattern DM_VANILLA_FROM = Pattern.compile(
        "(?i)^\\s*(?<name>[A-Za-z0-9_]{3,16})\\s+whispers\\s+to\\s+you:\\s*(?<msg>.+)$"
    );
    private static final Pattern DM_BRACKET = Pattern.compile(
        "^\\s*\\[\\s*PM\\s*]\\s*(?<name>[A-Za-z0-9_]{3,16})\\s*[:»]\\s*(?<msg>.+)$"
    );

    // Raw coord-ish patterns
    private static final Pattern RAW_XYZ_TRIPLE = Pattern.compile("\\b-?\\d{1,7}\\b(?:[ ,]+-?\\d{1,7}\\b){2}");
    private static final Pattern LBL_XYZ = Pattern.compile("(?i)\\b(X|Y|Z|Yaw|Pitch)\\s*[:=]\\s*-?\\d+(?:\\.\\d+)?");

    // Starscript-like braces and sensitive tokens (coords/camera/server performance)
    private static final Pattern CURLY = Pattern.compile("\\{[^}]*}");
    private static final Pattern DANGEROUS_TOKENS = Pattern.compile(
        "(?i)\\{[^}]*\\b(?:" +
            "player\\.(?:x|y|z|pos(?:ition)?|yaw|pitch|rotation|facing)" +
            "|camera\\.(?:yaw|pitch)" +
            "|server\\.(?:tps|mspt)" +
        ")\\b[^}]*}"
    );

    // ---------------- Lifecycle ----------------
    public ChatBotPlus() {
        super(CAT, "ChatBotPlus", "Offline messenger + DM-aware chat tools with strict leak guard.");
    }

    @Override
    public void onActivate() {
        storePath = getDataPath("offline_messages.json");
        loadStore();
        snapshotTablist();
        tickCounter = 0;
    }

    @Override
    public void onDeactivate() {
        saveStore();
        seenTablist.clear();
    }

    // ---------------- Events ----------------
    @EventHandler
    private void onTick(TickEvent.Post e) {
        if (!enableOffline.get()) return;
        if (++tickCounter % 20 != 0) return; // ~1s

        ClientPlayNetworkHandler nh = nh();
        if (nh == null) return;

        for (PlayerListEntry ple : nh.getPlayerList()) {
            if (ple == null) continue;
            UUID id = ple.getProfile().getId();
            if (id == null) continue;
            if (seenTablist.add(id)) {
                String name = ple.getProfile().getName();
                if (name != null) tryDeliverTo(name);
            }
        }
    }

    private static class ParsedChat {
        String sender, msg;
        boolean isPrivate;
        ParsedChat(String s, String m, boolean p) { sender = s; msg = m; isPrivate = p; }
    }

    private ParsedChat parseIncoming(String raw) {
        Matcher m;
        if ((m = CHAT_NAME_MSG.matcher(raw)).find())
            return new ParsedChat(m.group("name"), m.group("msg").trim(), false);
        if ((m = DM_ESS_FROM.matcher(raw)).find())
            return new ParsedChat(m.group("name"), m.group("msg").trim(), true);
        if ((m = DM_VANILLA_FROM.matcher(raw)).find())
            return new ParsedChat(m.group("name"), m.group("msg").trim(), true);
        if ((m = DM_BRACKET.matcher(raw)).find())
            return new ParsedChat(m.group("name"), m.group("msg").trim(), true);
        return null;
    }

    @EventHandler
    private void onChat(ReceiveMessageEvent e) {
        if (mc() == null || e.getMessage() == null) return;

        String raw = e.getMessage().getString();
        ParsedChat pc = parseIncoming(raw);
        if (pc == null) return;

        String sender = pc.sender;
        String msg = pc.msg;
        boolean cameFromDM = pc.isPrivate;

        // Commands start with prefix
        if (!msg.startsWith(prefix.get())) return;
        String cmdline = msg.substring(prefix.get().length()).trim();
        if (cmdline.isEmpty()) return;

        // --- Info / Help / Ping ---
        if (enableInfo.get()) {
            if (cmdline.equalsIgnoreCase("help")) {
                replySmart(sender, safeOut(
                    "Commands: " + prefix.get() + "help, " + prefix.get() + "ping, " +
                    prefix.get() + "info, " + prefix.get() + "leave <player> <message>, " +
                    prefix.get() + "inbox"), cameFromDM);
                return;
            }
            if (cmdline.equalsIgnoreCase("ping")) {
                Integer p = getLatencyFor(sender);
                replySmart(sender, p == null ? "Ping: N/A (not visible in tablist)" : ("Your ping: " + p + " ms"), cameFromDM);
                return;
            }
            if (cmdline.equalsIgnoreCase("info")) {
                replySmart(sender, safeOut(buildInfoLine()), cameFromDM);
                return;
            }
        }

        // --- Offline messenger ---
        if (enableOffline.get()) {
            if (cmdline.toLowerCase(Locale.ROOT).startsWith("leave ")) {
                String[] parts = cmdline.split("\\s+", 3);
                if (parts.length < 3) {
                    replySmart(sender, "Usage: " + prefix.get() + "leave <player> <message>", cameFromDM);
                    return;
                }
                String target = parts[1];
                String message = parts[2];

                enqueueMessage(target, sender, message);
                replySmart(sender, "Saved a note for " + target + ". It will be delivered when they come online.", cameFromDM);

                if (isOnline(target)) tryDeliverTo(target);
                return;
            }

            if (cmdline.equalsIgnoreCase("inbox")) {
                List<OfflineMsg> inbox = store.getOrDefault(sender.toLowerCase(Locale.ROOT), Collections.emptyList());
                if (inbox.isEmpty()) {
                    replySmart(sender, "You have no offline messages.", cameFromDM);
                } else {
                    replySmart(sender, "You have " + inbox.size() + " offline message(s). They'll arrive shortly.", cameFromDM);
                    tryDeliverTo(sender);
                }
                return;
            }
        }
    }

    // ---------------- Core ----------------
    private void replySmart(String to, String text, boolean cameFromDM) {
        text = safeOut(text);
        if (cameFromDM || !respondPublic.get()) {
            if (!sendPrivate(to, text)) sendPublic(to + " " + text);
        } else {
            sendPublic(to + " " + text);
        }
    }

    private void tryDeliverTo(String exactName) {
        String key = exactName.toLowerCase(Locale.ROOT);
        List<OfflineMsg> queue = store.get(key);
        if (queue == null || queue.isEmpty()) return;

        Iterator<OfflineMsg> it = queue.iterator();
        while (it.hasNext()) {
            OfflineMsg om = it.next();

            String payload = "[OFFLINE] From " + om.from + " @ " + fmtTime(om.ts) + ": " + sanitizeInbound(om.body);
            payload = safeOut(payload);

            if (!sendPrivate(exactName, payload)) {
                sendPublic(exactName + " " + payload);
            }

            if (notifySender.get()) {
                String ack = "Delivered your note to " + exactName + ".";
                if (!sendPrivate(om.from, ack)) sendPublic(om.from + " " + ack);
            }

            it.remove();
        }
        if (queue.isEmpty()) store.remove(key);
        saveStore();
    }

    private boolean isOnline(String name) {
        ClientPlayNetworkHandler nh = nh();
        if (nh == null) return false;
        for (PlayerListEntry ple : nh.getPlayerList()) {
            if (ple != null && name.equalsIgnoreCase(ple.getProfile().getName())) return true;
        }
        return false;
    }

    private void enqueueMessage(String target, String from, String body) {
        String k = target.toLowerCase(Locale.ROOT);
        store.computeIfAbsent(k, __ -> new ArrayList<>())
            .add(new OfflineMsg(from, body, System.currentTimeMillis()));
        saveStore();
    }

    // ---------------- Replies ----------------
    private void sendPublic(String text) {
        text = safeOut(text);
        ClientPlayNetworkHandler nh = nh();
        if (nh != null) nh.sendChatMessage(text);
    }

    private boolean sendPrivate(String to, String text) {
        text = safeOut(text);
        ClientPlayNetworkHandler nh = nh();
        if (nh == null) return false;
        nh.sendChatMessage("/msg " + to + " " + text); // works on most servers; change to /tell or /w if needed
        return true;
    }

    // ---------------- Info builders ----------------
    private String buildInfoLine() {
        MinecraftClient mc = mc();
        if (mc == null || mc.world == null) return "Not in world.";
        ClientPlayNetworkHandler nh = nh();
        int online = nh != null ? nh.getPlayerList().size() : -1;

        String dim = mc.world.getRegistryKey().getValue().toString();
        long dayTime = mc.world.getTimeOfDay() % 24000L;
        int hours = (int) ((dayTime / 1000L + 6) % 24); // ~6:00 start
        int minutes = (int) ((dayTime % 1000L) * 60 / 1000L);

        return String.format(Locale.ROOT,
            "Online: %s | Dimension: %s | MC time: %02d:%02d",
            online >= 0 ? online : "N/A", dim, hours, minutes);
        }

    private Integer getLatencyFor(String name) {
        ClientPlayNetworkHandler nh = nh();
        if (nh == null) return null;
        for (PlayerListEntry ple : nh.getPlayerList()) {
            if (ple != null && name.equalsIgnoreCase(ple.getProfile().getName())) {
                return ple.getLatency();
            }
        }
        return null;
    }

    // ---------------- LeakGuard / Sanitizers ----------------
    /** Sanitize inbound (stored) text so we don’t evaluate Starscript when later echoing. */
    private String sanitizeInbound(String s) {
        if (!leakGuard.get() || s == null) return s;
        String out = s;
        if (stripStarscript.get()) {
            out = CURLY.matcher(out).replaceAll((MatchResult m) -> toFullwidth(m.group()));
        }
        return out;
    }

    /** Sanitize any outbound text before sending to chat. */
    private String safeOut(String s) {
        if (!leakGuard.get() || s == null) return s;

        String out = s;

        // 1) Strip dangerous Starscript tokens entirely
        if (blockDangerTokens.get() && DANGEROUS_TOKENS.matcher(out).find()) {
            out = CURLY.matcher(out).replaceAll("[blocked]");
        }

        // 2) Convert remaining {...} to fullwidth braces (prevents evaluation in overlays)
        if (stripStarscript.get()) {
            out = CURLY.matcher(out).replaceAll((MatchResult m) -> toFullwidth(m.group()));
        }

        // 3) Mask coord-like patterns
        if (blockRawXYZ.get()) {
            if (RAW_XYZ_TRIPLE.matcher(out).find() || LBL_XYZ.matcher(out).find()) {
                out = out.replaceAll(RAW_XYZ_TRIPLE.pattern(), "[coords blocked]");
                out = out.replaceAll(LBL_XYZ.pattern(), "[coords blocked]");
            }
        }

        return out;
    }

    private static String toFullwidth(String braces) {
        // Replace { and } with fullwidth variants; keep inner text intact.
        String inner = braces.length() >= 2 ? braces.substring(1, braces.length() - 1) : "";
        return "｛" + inner + "｝";
    }

    // ---------------- Store ----------------
    private void loadStore() {
        try {
            if (Files.exists(storePath)) {
                try (Reader r = Files.newBufferedReader(storePath, StandardCharsets.UTF_8)) {
                    Map<String, List<OfflineMsg>> data = GSON.fromJson(r, STORE_TYPE);
                    if (data != null) store = data;
                }
            }
        } catch (Exception ex) {
            ChatUtils.error("ChatBotPlus: failed to load store: " + ex.getMessage());
        }
    }

    private void saveStore() {
        try {
            Files.createDirectories(storePath.getParent());
            Path tmp = storePath.resolveSibling(storePath.getFileName() + ".tmp");
            try (Writer w = Files.newBufferedWriter(tmp, StandardCharsets.UTF_8)) {
                GSON.toJson(store, STORE_TYPE, w);
            }
            Files.move(tmp, storePath, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (Exception ex) {
            ChatUtils.error("ChatBotPlus: failed to save store: " + ex.getMessage());
        }
    }

    private void snapshotTablist() {
        seenTablist.clear();
        ClientPlayNetworkHandler nh = nh();
        if (nh == null) return;
        for (PlayerListEntry ple : nh.getPlayerList()) {
            if (ple != null && ple.getProfile() != null && ple.getProfile().getId() != null) {
                seenTablist.add(ple.getProfile().getId());
            }
        }
    }

    private Path getDataPath(String file) {
        Path base = MeteorClient.FOLDER; // ".minecraft/meteor-client"
        return base.resolve("chatbotplus").resolve(file);
    }

    private static MinecraftClient mc() {
        return MinecraftClient.getInstance();
    }

    private static ClientPlayNetworkHandler nh() {
        MinecraftClient mc = mc();
        return mc != null ? mc.getNetworkHandler() : null;
    }

    private static String fmtTime(long epochMs) {
        return DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm")
            .withZone(ZoneId.systemDefault())
            .format(Instant.ofEpochMilli(epochMs));
    }

    // ---------------- Types ----------------
    private static class OfflineMsg {
        public String from;
        public String body;
        public long ts;
        public OfflineMsg(String from, String body, long ts) {
            this.from = from; this.body = body; this.ts = ts;
        }
    }
}
