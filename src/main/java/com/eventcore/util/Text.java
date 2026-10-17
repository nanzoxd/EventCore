package com.eventcore.util;

import com.eventcore.EventCorePlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Text pipeline: {placeholders}, the &lt;g&gt; palette-gradient tag, small caps and bold stripping.
 * Placeholder values are never small-capsed, only the template text is.
 * A placeholder whose key ends in _raw is pasted into the template before parsing (e.g. "&lt;{color_raw}&gt;").
 */
public final class Text {
    private static final MiniMessage MM = MiniMessage.miniMessage();
    private static final String SC = "ᴀʙᴄᴅᴇꜰɢʜɪᴊᴋʟᴍɴᴏᴘǫʀꜱᴛᴜᴠᴡxʏᴢ";
    private final EventCorePlugin pl;

    public Text(EventCorePlugin pl) { this.pl = pl; }

    public static Map<String, Object> ph(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) m.put(String.valueOf(kv[i]), kv[i + 1]);
        return m;
    }

    public List<String> palette() {
        List<String> l = pl.cfg.config().getStringList("palette");
        return l.isEmpty() ? List.of("#ffffff") : l;
    }

    private boolean smallCapsOn() { return pl.cfg.config().getBoolean("style.small-caps", true); }

    public static String smallCaps(String s) {
        StringBuilder b = new StringBuilder(s.length());
        for (char c : s.toCharArray()) b.append(sc(c));
        return b.toString();
    }

    private static char sc(char c) {
        char l = Character.toLowerCase(c);
        return (l >= 'a' && l <= 'z') ? SC.charAt(l - 'a') : c;
    }

    /** Small caps respecting the style.small-caps switch (for placeholder values built by code). */
    public String sc(String s) { return smallCapsOn() ? smallCaps(s) : s; }

    /** Converts template text to small caps while leaving MiniMessage tags and {placeholders} untouched. */
    private static String smallCapsTemplate(String s) {
        StringBuilder b = new StringBuilder(s.length());
        int i = 0;
        boolean off = false; // inside <bb>..</bb> or <n>..</n>: keep normal letters
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '<') {
                int e = s.indexOf('>', i);
                if (e > 0) {
                    String tag = s.substring(i, e + 1).toLowerCase(Locale.ROOT);
                    if (tag.equals("<bb>") || tag.equals("<n>")) off = true;
                    else if (tag.equals("</bb>") || tag.equals("</n>")) off = false;
                    b.append(s, i, e + 1); i = e + 1; continue;
                }
            } else if (c == '{') {
                int e = s.indexOf('}', i);
                if (e > 0 && s.substring(i + 1, e).matches("[a-zA-Z_0-9]+")) { b.append(s, i, e + 1); i = e + 1; continue; }
            }
            b.append(off ? c : sc(c));
            i++;
        }
        return b.toString();
    }

    private String gradientOpen(double phase) {
        List<String> p = palette();
        if (p.size() == 1) return "<color:" + p.get(0) + ">";
        String ph = phase != 0 ? ":" + String.format(Locale.ROOT, "%.3f", phase) : "";
        return "<gradient:" + String.join(":", p) + ph + ">";
    }

    public List<String> bossbarColors() {
        List<String> l = pl.cfg.config().getStringList("bossbar-gradient");
        return l.isEmpty() ? palette() : l;
    }

    private String bbOpen() {
        List<String> p = bossbarColors();
        return p.size() == 1 ? "<color:" + p.get(0) + ">" : "<gradient:" + String.join(":", p) + ">";
    }

    private String bbClose() { return bossbarColors().size() == 1 ? "</color>" : "</gradient>"; }

    /** Bossbar gradient (config bossbar-gradient) over an already expanded plain string. */
    public Component bossbar(String plain) { return gradient(plain, bossbarColors(), 0); }

    /** Like expand() but keeps normal letters (no small caps). */
    public String expandPlain(String tpl, Map<String, Object> ph) {
        if (tpl == null) return "";
        tpl = tpl.replace("<g>", "").replace("</g>", "").replace("<bb>", "").replace("</bb>", "").replace("<n>", "").replace("</n>", "");
        if (ph != null)
            for (Map.Entry<String, Object> e : ph.entrySet()) {
                Object v = e.getValue();
                tpl = tpl.replace("{" + e.getKey() + "}", v instanceof Component c ? plain(c) : String.valueOf(v));
            }
        return MM.stripTags(tpl);
    }

    private String gradientClose() { return palette().size() == 1 ? "</color>" : "</gradient>"; }

    public Component render(String tpl, Map<String, Object> ph) {
        if (tpl == null) tpl = "";
        if (ph == null) ph = Map.of();
        for (Map.Entry<String, Object> e : ph.entrySet())
            if (e.getKey().endsWith("_raw")) tpl = tpl.replace("{" + e.getKey() + "}", String.valueOf(e.getValue()));
        if (smallCapsOn()) tpl = smallCapsTemplate(tpl);
        boolean strip = pl.cfg.config().getBoolean("style.strip-bold", true);
        if (strip) tpl = tpl.replaceAll("(?i)</?(b|bold)>", "");
        tpl = tpl.replace("<g>", gradientOpen(0)).replace("</g>", gradientClose());
        tpl = tpl.replace("<bb>", bbOpen()).replace("</bb>", bbClose()).replace("<n>", "").replace("</n>", "");
        TagResolver.Builder tr = TagResolver.builder();
        for (Map.Entry<String, Object> e : ph.entrySet()) {
            String k = e.getKey();
            if (k.endsWith("_raw") || !tpl.contains("{" + k + "}")) continue;
            tpl = tpl.replace("{" + k + "}", "<" + k + ">");
            Object v = e.getValue();
            if (v instanceof Component c) tr.resolver(Placeholder.component(k, c));
            else tr.resolver(Placeholder.unparsed(k, String.valueOf(v)));
        }
        Component c = MM.deserialize(tpl, tr.build());
        Component root = Component.empty();
        if (strip) root = root.decoration(TextDecoration.BOLD, false);
        return root.append(c);
    }

    /** Item names / lore: like render() but never italic. */
    public Component item(String tpl, Map<String, Object> ph) {
        return render(tpl, ph).decoration(TextDecoration.ITALIC, false);
    }

    /** Fully expanded plain string (small caps applied, placeholders substituted, tags stripped). */
    public String expand(String tpl, Map<String, Object> ph) {
        if (tpl == null) return "";
        if (smallCapsOn()) tpl = smallCapsTemplate(tpl);
        tpl = tpl.replace("<g>", "").replace("</g>", "");
        if (ph != null)
            for (Map.Entry<String, Object> e : ph.entrySet()) {
                Object v = e.getValue();
                String s = v instanceof Component c ? plain(c) : String.valueOf(v);
                tpl = tpl.replace("{" + e.getKey() + "}", s);
            }
        return MM.stripTags(tpl);
    }

    public static String plain(Component c) { return PlainTextComponentSerializer.plainText().serialize(c); }

    public Component gradient(String text, List<String> hexes, double phase) {
        if (hexes == null || hexes.isEmpty()) return Component.text(text);
        TextDecoration bold = TextDecoration.BOLD;
        if (hexes.size() == 1) {
            TextColor col = TextColor.fromHexString(hexes.get(0));
            return Component.text(text, col == null ? TextColor.color(0xffffff) : col).decoration(bold, false);
        }
        String ph = phase != 0 ? ":" + String.format(Locale.ROOT, "%.3f", Math.max(-1, Math.min(1, phase))) : "";
        return MM.deserialize("<gradient:" + String.join(":", hexes) + ph + ">" + MM.escapeTags(text) + "</gradient>")
                .decoration(bold, false);
    }

    public Component paletteGradient(String text, double phase) { return gradient(text, palette(), phase); }
}
