package info.trizub.clamav.webclient.service;

import info.trizub.clamav.webclient.model.TelegramAction;
import info.trizub.clamav.webclient.model.TelegramActionType;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The buttons under a Telegram alert. Callback data is "<a|y|n>:<action id>":
 * a = ask (first tap), y = confirmed, n = cancelled. It never carries the path
 * or the action itself - those stay on the server, in TelegramAction.
 */
public final class TelegramKeyboard {

    private TelegramKeyboard() {}

    public static Map<String, Object> build(List<TelegramAction> offers, String confirmingId, String alertUrl) {
        List<List<Map<String, String>>> rows = new ArrayList<>();
        List<List<Map<String, String>>> ackRow = new ArrayList<>();
        for (TelegramAction a : offers) {
            if (a.getStatus() != TelegramAction.Status.OFFERED) continue;
            if (a.getType() == TelegramActionType.ACK) {
                // One tap, no confirmation: acknowledging moves nothing on any machine.
                ackRow.add(List.of(button("✔️ Acknowledge alert", "a:" + a.getId())));
                continue;
            }
            String name = shortName(a.getFilePath());
            boolean quarantine = a.getType() == TelegramActionType.QUARANTINE;
            if (a.getId().equals(confirmingId)) {
                rows.add(List.of(
                        button((quarantine ? "✅ Confirm quarantine " : "✅ Confirm restore ") + name, "y:" + a.getId()),
                        button("✖ Cancel", "n:" + a.getId())));
            } else {
                rows.add(List.of(button((quarantine ? "🗄 Quarantine " : "↩️ Restore ") + name, "a:" + a.getId())));
            }
        }
        rows.addAll(ackRow);
        if (alertUrl != null && !alertUrl.isBlank()) {
            rows.add(List.of(Map.of("text", "🔎 Open in console", "url", alertUrl)));
        }
        return Map.of("inline_keyboard", rows);
    }

    public static boolean isEmpty(Map<String, Object> keyboard) {
        Object rows = keyboard.get("inline_keyboard");
        return !(rows instanceof List<?> l) || l.isEmpty();
    }

    /** The file name only, shortened: a button has room for very little. */
    public static String shortName(String path) {
        if (path == null) return "";
        String p = path.replace('\\', '/');
        String name = p.substring(p.lastIndexOf('/') + 1);
        return name.length() > 32 ? name.substring(0, 29) + "..." : name;
    }

    private static Map<String, String> button(String text, String data) {
        return Map.of("text", text, "callback_data", data);
    }
}
