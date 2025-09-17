@EventHandler
private void onChat(ReceiveMessageEvent e) {
    if (mc() == null || e.getMessage() == null) return;
    String raw = e.getMessage().getString();

    ParsedChat pc = parseIncoming(raw);
    if (pc == null) return;

    String sender = pc.sender;
    String msg = pc.msg;
    boolean cameFromDM = pc.isPrivate;

    if (!msg.startsWith(prefix.get())) return;
    String cmdline = msg.substring(prefix.get().length()).trim();
    if (cmdline.isEmpty()) return;

    if (enableInfo.get()) {
        if (cmdline.equalsIgnoreCase("help")) {
            replySmart(sender, safeOut(
                "Commands: " + prefix.get() + "help, " + prefix.get() + "ping, " +
                prefix.get() + "info, " + prefix.get() + "leave <player> <message>, " + prefix.get() + "inbox"), cameFromDM);
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
            if (inbox.isEmpty()) replySmart(sender, "You have no offline messages.", cameFromDM);
            else {
                replySmart(sender, "You have " + inbox.size() + " offline message(s). They'll arrive shortly.", cameFromDM);
                tryDeliverTo(sender);
            }
            return;
        }
    }
}
