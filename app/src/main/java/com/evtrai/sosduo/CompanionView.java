package com.evtrai.sosduo;

import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.Canvas;
import android.graphics.Color;
import android.graphics.LinearGradient;
import android.graphics.Paint;
import android.graphics.Path;
import android.graphics.RectF;
import android.graphics.Shader;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
import android.text.TextUtils;
import android.util.TypedValue;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.HorizontalScrollView;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/** The bottom-screen companion: header, tabs (Battle / Party / Items) and custom-drawn widgets. */
public class CompanionView extends FrameLayout {

    // ---- palette (taken from the game's own HUD colours) ----
    static final int BG = 0xFF070B1C, PANEL = 0xFF111936, PANEL_HI = 0xFF1A2550, BORDER = 0xFF2B3970,
            TEAL = 0xFF5FE3E0, TEXT = 0xFFEDE6D6, MUTED = 0xFF8A93B8, GOLD = 0xFFF2C46B,
            HP_A = 0xFFB9824F, HP_B = 0xFFF3CFA2, MP_A = 0xFF8C68D6, MP_B = 0xFFEE8FC6,
            DANGER = 0xFFE5585C, ORANGE = 0xFFE8713A, TRACK = 0xFF0A1028;

    static final Typeface FACE = Typeface.create("sans-serif-condensed", Typeface.NORMAL);
    static final Typeface FACE_B = Typeface.create("sans-serif-condensed", Typeface.BOLD);

    static final int TAB_BATTLE = 0, TAB_PARTY = 1, TAB_ITEMS = 2;
    static final String[] TAB_NAMES = {"BATTLE", "PARTY", "ITEMS"};

    final float dp;
    private final TextView areaTv, goldTv, timeTv;
    private final TextView[] tabViews = new TextView[3];
    private final LinearLayout header, tabBar;
    private final ScrollView scroll;
    private final LinearLayout body;
    private final FrameLayout splash;
    private final TextView splashMsg;
    private final LinearLayout splashContent;
    private boolean splashShown = true;

    private int tab = TAB_PARTY, userTab = TAB_PARTY;
    private boolean inBattle, autoSwitched;
    private String renderedKey = "";
    private JSONObject snap;
    private String itemFilter = "All";
    private final Set<String> expandedItems = new HashSet<>();
    private final Map<String, Bitmap> portraits = new HashMap<>();
    private int portraitsKnown = 0;

    public CompanionView(Context c) {
        super(c);
        dp = getResources().getDisplayMetrics().density;
        setBackgroundColor(BG);

        LinearLayout root = new LinearLayout(c);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setPadding(px(12), px(10), px(12), px(8));
        addView(root, new LayoutParams(-1, -1));

        // header: area | gold · time
        header = new LinearLayout(c);
        header.setGravity(Gravity.CENTER_VERTICAL);
        areaTv = text(c, "", 17, TEAL, true);
        areaTv.setLetterSpacing(0.06f);
        areaTv.setSingleLine(true);
        areaTv.setEllipsize(TextUtils.TruncateAt.END);
        header.addView(areaTv, new LinearLayout.LayoutParams(0, -2, 1));
        goldTv = text(c, "", 14, GOLD, true);
        header.addView(new CoinView(c), new LinearLayout.LayoutParams(px(14), px(14)));
        LinearLayout.LayoutParams glp = new LinearLayout.LayoutParams(-2, -2);
        glp.leftMargin = px(5);
        header.addView(goldTv, glp);
        timeTv = text(c, "", 14, MUTED, false);
        LinearLayout.LayoutParams tlp = new LinearLayout.LayoutParams(-2, -2);
        tlp.leftMargin = px(14);
        header.addView(timeTv, tlp);
        root.addView(header, new LinearLayout.LayoutParams(-1, -2));

        // tabs
        tabBar = new LinearLayout(c);
        LinearLayout.LayoutParams tbl = new LinearLayout.LayoutParams(-1, px(34));
        tbl.topMargin = px(8);
        tbl.bottomMargin = px(8);
        for (int i = 0; i < 3; i++) {
            final int idx = i;
            TextView t = text(c, TAB_NAMES[i], 13, MUTED, true);
            t.setGravity(Gravity.CENTER);
            t.setLetterSpacing(0.15f);
            t.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                userTab = idx;
                autoSwitched = false;
                selectTab(idx);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -1, 1);
            if (i > 0) lp.leftMargin = px(6);
            tabBar.addView(t, lp);
            tabViews[i] = t;
        }
        root.addView(tabBar, tbl);

        scroll = new ScrollView(c);
        scroll.setVerticalScrollBarEnabled(false);
        scroll.setOverScrollMode(OVER_SCROLL_NEVER);
        body = new LinearLayout(c);
        body.setOrientation(LinearLayout.VERTICAL);
        scroll.addView(body, new ScrollView.LayoutParams(-1, -2));
        root.addView(scroll, new LinearLayout.LayoutParams(-1, 0, 1));

        // splash (boot / title)
        splash = new FrameLayout(c);
        splash.setBackgroundColor(BG);
        LinearLayout sl = splashContent = new LinearLayout(c);
        sl.setOrientation(LinearLayout.VERTICAL);
        sl.setGravity(Gravity.CENTER);
        TextView t1 = text(c, "SEA OF STARS", 34, TEAL, true);
        t1.setLetterSpacing(0.12f);
        TextView t2 = text(c, "D U O", 16, GOLD, true);
        t2.setLetterSpacing(0.4f);
        splashMsg = text(c, "", 14, MUTED, false);
        splashMsg.setPadding(0, px(18), 0, 0);
        sl.addView(t1); sl.addView(t2); sl.addView(splashMsg);
        for (int i = 0; i < sl.getChildCount(); i++) ((TextView) sl.getChildAt(i)).setGravity(Gravity.CENTER);
        splash.addView(sl, new LayoutParams(-1, -1, Gravity.CENTER));
        addView(splash, new LayoutParams(-1, -1));

        selectTab(tab);
    }

    // ------------------------------------------------------------------ update

    public void update(String json) {
        JSONObject s;
        try { s = new JSONObject(json); } catch (Exception e) { return; }
        String state = s.optString("state");
        if (!state.equals("battle") && !state.equals("field")) {
            // title / loading / cutscene / boot: a dim, quiet screen (OLED-friendly), faded in
            boolean dim = !state.equals("boot") && !state.equals("error");
            splashMsg.setText(dim ? "" : s.optString("msg", ""));
            splashContent.setAlpha(dim ? 0.35f : 1f);
            showSplash(true);
            inBattle = false;
            autoSwitched = false;
            return;
        }
        showSplash(false);
        snap = s;

        areaTv.setText(s.optString("area").toUpperCase(Locale.ROOT));
        long gold = s.optLong("gold", -1);
        goldTv.setText(String.format(Locale.US, "%,d", Math.max(0, gold)));
        long pt = s.optLong("playtime");
        timeTv.setText(String.format(Locale.US, "%d:%02d", pt / 3600, (pt / 60) % 60));

        fetchPortraits(s.optJSONArray("party"));
        boolean battle = state.equals("battle");
        if (battle && !inBattle && tab != TAB_BATTLE) { autoSwitched = true; selectTab(TAB_BATTLE); }
        if (!battle && inBattle && autoSwitched) { autoSwitched = false; selectTab(userTab); }
        inBattle = battle;
        render(false);
    }

    private void showSplash(boolean show) {
        if (show == splashShown) return;
        splashShown = show;
        splash.animate().cancel();
        if (show) {
            splash.setVisibility(VISIBLE);
            splash.animate().alpha(1f).setDuration(350).start();
        } else {
            splash.animate().alpha(0f).setDuration(350).withEndAction(() -> splash.setVisibility(GONE)).start();
        }
    }

    private void selectTab(int t) {
        tab = t;
        for (int i = 0; i < 3; i++) {
            boolean sel = i == t;
            tabViews[i].setTextColor(sel ? BG : MUTED);
            tabViews[i].setBackground(round(sel ? TEAL : PANEL, sel ? TEAL : BORDER, 17));
        }
        render(true);
        scroll.scrollTo(0, 0);
    }

    private void render(boolean force) {
        if (snap == null) return;
        String key;
        switch (tab) {
            case TAB_BATTLE: key = "b" + snap.optString("state") + snap.optJSONArray("enemies") + snap.optJSONArray("party") + snap.optInt("combo") + snap.optInt("ult") + snap.opt("menu") + portraitsKnown; break;
            case TAB_PARTY: key = "p" + snap.optJSONArray("party") + snap.optInt("combo") + snap.optInt("ult") + portraitsKnown; break;
            default: key = "i" + snap.optJSONArray("items") + itemFilter + expandedItems; break;
        }
        if (!force && key.equals(renderedKey)) return;
        renderedKey = key;
        int y = scroll.getScrollY();
        body.removeAllViews();
        switch (tab) {
            case TAB_BATTLE: renderBattle(); break;
            case TAB_PARTY: renderParty(); break;
            default: renderItems(); break;
        }
        if (!force) scroll.post(() -> scroll.scrollTo(0, y));
    }

    // ------------------------------------------------------------------ BATTLE

    private void renderBattle() {
        Context c = getContext();
        JSONArray enemies = snap.optJSONArray("enemies");
        boolean battle = "battle".equals(snap.optString("state"));
        if (!battle || enemies == null || enemies.length() == 0) {
            LinearLayout p = panel(c);
            p.setGravity(Gravity.CENTER);
            TextView t = text(c, "No battle", 18, TEXT, true);
            t.setGravity(Gravity.CENTER);
            TextView t2 = text(c, "Enemy timers, locks and HP appear here automatically when a fight starts.", 13, MUTED, false);
            t2.setGravity(Gravity.CENTER);
            p.addView(t); p.addView(t2);
            p.setPadding(px(16), px(26), px(16), px(26));
            body.addView(p, mlp(-1, -2, 0, 0, 0, px(10)));
            body.addView(comboRow(c), mlp(-1, -2, 0, 0, 0, px(10)));
            body.addView(partyMini(c));
            return;
        }
        if (snap.optBoolean("boss")) {
            TextView b = text(c, "BOSS", 12, DANGER, true);
            b.setLetterSpacing(0.3f);
            body.addView(b, mlp(-2, -2, px(2), 0, 0, px(4)));
        }
        LinearLayout row = null;
        for (int i = 0; i < enemies.length(); i++) {
            if (i % 2 == 0) {
                row = new LinearLayout(c);
                body.addView(row, mlp(-1, -2, 0, 0, 0, px(8)));
            }
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i % 2 == 1) lp.leftMargin = px(8);
            row.addView(enemyCard(c, enemies.optJSONObject(i)), lp);
        }
        if (enemies.length() % 2 == 1) row.addView(new View(c), new LinearLayout.LayoutParams(0, 1, 1));
        JSONObject menu = snap.optJSONObject("menu");
        if (menu != null) body.addView(commandPanel(c, menu), mlp(-1, -2, 0, px(2), 0, px(8)));
        body.addView(comboRow(c), mlp(-1, -2, 0, px(2), 0, px(8)));
        body.addView(partyMini(c));
    }

    private View enemyCard(Context c, JSONObject e) {
        boolean dead = e.optBoolean("dead");
        LinearLayout card = panel(c);
        card.setAlpha(dead ? 0.35f : 1f);
        card.setPadding(px(10), px(8), px(10), px(10));

        LinearLayout top = new LinearLayout(c);
        top.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(c, e.optString("name"), 15, TEXT, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        top.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        int turns = e.optInt("turns", -1);
        if (!dead && turns >= 0) top.addView(new CountdownView(c, turns), new LinearLayout.LayoutParams(px(38), px(38)));
        card.addView(top);

        JSONArray locks = e.optJSONArray("locks");
        if (locks != null && locks.length() > 0) {
            LinearLayout lr = new LinearLayout(c);
            for (int i = 0; i < locks.length(); i++) {
                JSONObject l = locks.optJSONObject(i);
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(px(26), px(26));
                lp.rightMargin = px(4);
                lr.addView(new LockIconView(c, l.optString("t"), l.optBoolean("r")), lp);
            }
            card.addView(lr, mlp(-1, -2, 0, px(6), 0, 0));
        }

        int hp = e.optInt("hp"), max = e.optInt("hpMax");
        boolean hidden = e.optBoolean("hideHP");
        String val = hidden ? "???" : hp + " / " + max;
        card.addView(new BarView(c, "HP", val, hidden ? 1f : frac(hp, max), DANGER, 0xFFF08A7E), mlp(-1, px(28), 0, px(6), 0, 0));
        return card;
    }

    private View partyMini(Context c) {
        LinearLayout p = panel(c);
        p.setPadding(px(10), px(6), px(10), px(8));
        JSONArray party = snap.optJSONArray("party");
        if (party == null) return p;
        for (int i = 0; i < party.length(); i++) {
            JSONObject m = party.optJSONObject(i);
            LinearLayout r = new LinearLayout(c);
            r.setGravity(Gravity.CENTER_VERTICAL);
            r.addView(new AvatarView(c, m.optString("id"), parseColor(m.optString("color"))), new LinearLayout.LayoutParams(px(30), px(30)));
            TextView n = text(c, m.optString("name"), 14, TEXT, true);
            n.setSingleLine(true);
            n.setEllipsize(TextUtils.TruncateAt.END);
            r.addView(n, mlp(px(78), -2, px(8), 0, 0, 0));
            r.addView(new BarView(c, "HP", m.optInt("hp") + "", frac(m.optInt("hp"), m.optInt("hpMax")), HP_A, HP_B), new LinearLayout.LayoutParams(0, px(28), 1.3f));
            r.addView(new BarView(c, "MP", m.optInt("mp") + "", frac(m.optInt("mp"), m.optInt("mpMax")), MP_A, MP_B), mlp(0, px(28), px(8), 0, 0, 0, 1f));
            int mana = m.optInt("mana", -1);
            if (mana >= 0) r.addView(new ManaView(c, mana), mlp(px(42), px(16), px(8), 0, 0, 0));
            p.addView(r, mlp(-1, -2, 0, px(4), 0, 0));
        }
        return p;
    }

    private View comboRow(Context c) {
        LinearLayout p = panel(c);
        p.setOrientation(LinearLayout.HORIZONTAL);
        p.setGravity(Gravity.CENTER_VERTICAL);
        p.setPadding(px(12), px(6), px(12), px(6));
        TextView l = text(c, "COMBO", 12, MUTED, true);
        l.setLetterSpacing(0.15f);
        p.addView(l);
        int unit = Math.max(1, snap.optInt("comboUnit", 1000));
        p.addView(new ComboView(c, snap.optInt("combo") / (float) unit, Math.max(1, snap.optInt("comboMax", 3))),
                mlp(0, px(26), px(10), 0, 0, 0, 1f));
        TextView u = text(c, "ULT  " + snap.optInt("ult"), 13, GOLD, true);
        u.setLetterSpacing(0.1f);
        p.addView(u);
        return p;
    }

    // ------------------------------------------------------------------ PARTY

    private void renderParty() {
        Context c = getContext();
        JSONArray party = snap.optJSONArray("party");
        if (party == null) return;
        LinearLayout row = new LinearLayout(c);
        for (int i = 0; i < party.length(); i++) {
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i > 0) lp.leftMargin = px(8);
            row.addView(memberCard(c, party.optJSONObject(i)), lp);
        }
        body.addView(row, mlp(-1, -2, 0, 0, 0, px(8)));
        body.addView(comboRow(c));
    }

    private View memberCard(Context c, JSONObject m) {
        int color = parseColor(m.optString("color"));
        LinearLayout card = panel(c);
        card.setPadding(0, 0, 0, px(10));

        // coloured banner
        LinearLayout bannerRow = new LinearLayout(c);
        bannerRow.setGravity(Gravity.CENTER_VERTICAL);
        bannerRow.setPadding(px(10), px(8), px(10), px(8));
        bannerRow.addView(new AvatarView(c, m.optString("id"), color), new LinearLayout.LayoutParams(px(46), px(46)));
        LinearLayout banner = new LinearLayout(c);
        banner.setOrientation(LinearLayout.VERTICAL);
        banner.setPadding(px(10), 0, 0, 0);
        GradientDrawable g = new GradientDrawable(GradientDrawable.Orientation.LEFT_RIGHT,
                new int[]{withAlpha(color, 0xC0), withAlpha(color, 0x30)});
        float r = px(10);
        g.setCornerRadii(new float[]{r, r, r, r, 0, 0, 0, 0});
        bannerRow.setBackground(g);
        LinearLayout nl = new LinearLayout(c);
        nl.setGravity(Gravity.CENTER_VERTICAL);
        TextView name = text(c, m.optString("name"), 19, Color.WHITE, true);
        name.setSingleLine(true);
        name.setEllipsize(TextUtils.TruncateAt.END);
        nl.addView(name, new LinearLayout.LayoutParams(0, -2, 1));
        TextView lv = text(c, "LV " + m.optInt("lv"), 13, BG, true);
        lv.setPadding(px(7), px(1), px(7), px(1));
        lv.setBackground(round(GOLD, 0, 8));
        nl.addView(lv);
        banner.addView(nl);
        String title = m.optString("title");
        if (!title.isEmpty()) {
            TextView t = text(c, title, 12, 0xDDFFFFFF, false);
            t.setSingleLine(true);
            t.setEllipsize(TextUtils.TruncateAt.END);
            banner.addView(t);
        }
        bannerRow.addView(banner, new LinearLayout.LayoutParams(0, -2, 1));
        card.addView(bannerRow, new LinearLayout.LayoutParams(-1, -2));

        LinearLayout in = new LinearLayout(c);
        in.setOrientation(LinearLayout.VERTICAL);
        in.setPadding(px(10), px(6), px(10), 0);
        in.addView(new BarView(c, "HP", m.optInt("hp") + " / " + m.optInt("hpMax"), frac(m.optInt("hp"), m.optInt("hpMax")), HP_A, HP_B), new LinearLayout.LayoutParams(-1, px(30)));
        in.addView(new BarView(c, "MP", m.optInt("mp") + " / " + m.optInt("mpMax"), frac(m.optInt("mp"), m.optInt("mpMax")), MP_A, MP_B), mlp(-1, px(30), 0, px(2), 0, 0));

        // stats 2x2
        String[][] st = {{"ATK", "atk"}, {"DEF", "def"}, {"M.ATK", "matk"}, {"M.DEF", "mdef"}};
        for (int i = 0; i < 4; i += 2) {
            LinearLayout sr = new LinearLayout(c);
            for (int j = i; j < i + 2; j++) {
                LinearLayout cell = new LinearLayout(c);
                cell.setGravity(Gravity.CENTER_VERTICAL);
                cell.setPadding(px(8), px(4), px(8), px(4));
                cell.setBackground(round(TRACK, 0, 6));
                TextView k = text(c, st[j][0], 11, MUTED, true);
                cell.addView(k, new LinearLayout.LayoutParams(0, -2, 1));
                cell.addView(text(c, String.valueOf(m.optInt(st[j][1])), 14, TEXT, true));
                LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
                if (j % 2 == 1) lp.leftMargin = px(6);
                sr.addView(cell, lp);
            }
            in.addView(sr, mlp(-1, -2, 0, px(6), 0, 0));
        }

        // equipment
        TextView eqh = text(c, "EQUIPMENT", 11, MUTED, true);
        eqh.setLetterSpacing(0.15f);
        in.addView(eqh, mlp(-2, -2, 0, px(10), 0, px(2)));
        in.addView(equipLine(c, "Weapon", m.optString("weapon")));
        in.addView(equipLine(c, "Armor", m.optString("armor")));
        JSONArray tr = m.optJSONArray("trinkets");
        if (tr != null) for (int i = 0; i < tr.length(); i++) in.addView(equipLine(c, "Trinket", tr.optString(i)));
        card.addView(in);
        return card;
    }

    private View equipLine(Context c, String slot, String item) {
        LinearLayout l = new LinearLayout(c);
        l.setPadding(0, px(2), 0, px(2));
        TextView s = text(c, slot, 12, MUTED, false);
        l.addView(s, new LinearLayout.LayoutParams(px(58), -2));
        TextView v = text(c, item.isEmpty() ? "—" : item, 13, item.isEmpty() ? MUTED : TEXT, false);
        v.setSingleLine(true);
        v.setEllipsize(TextUtils.TruncateAt.END);
        l.addView(v, new LinearLayout.LayoutParams(0, -2, 1));
        return l;
    }

    // ------------------------------------------------------------------ ITEMS

    static String categoryLabel(String cls) {
        if (cls == null || cls.isEmpty()) return "Other";
        String c = cls.toLowerCase(Locale.ROOT);
        if (c.contains("relic")) return "Relics";
        if (c.contains("key")) return "Key Items";
        if (c.contains("consum") || c.contains("food") || c.contains("meal")) return "Consumables";
        if (c.contains("ingredient")) return "Ingredients";
        if (c.contains("weapon")) return "Weapons";
        if (c.contains("armor")) return "Armor";
        if (c.contains("trinket")) return "Trinkets";
        if (c.contains("recipe")) return "Recipes";
        if (c.contains("valuable") || c.contains("treasure")) return "Valuables";
        return cls.replace("Item", "").replace("Inventory", "");
    }

    private void renderItems() {
        Context c = getContext();
        JSONArray items = snap.optJSONArray("items");
        if (items == null) items = new JSONArray();
        Map<String, List<JSONObject>> byCat = new LinkedHashMap<>();
        for (int i = 0; i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            byCat.computeIfAbsent(categoryLabel(it.optString("cat")), k -> new ArrayList<>()).add(it);
        }
        if (!itemFilter.equals("All") && !byCat.containsKey(itemFilter)) itemFilter = "All";

        // filter chips
        HorizontalScrollView hs = new HorizontalScrollView(c);
        hs.setHorizontalScrollBarEnabled(false);
        LinearLayout chips = new LinearLayout(c);
        List<String> cats = new ArrayList<>();
        cats.add("All");
        cats.addAll(byCat.keySet());
        for (String cat : cats) {
            boolean sel = cat.equals(itemFilter);
            int n = cat.equals("All") ? items.length() : byCat.get(cat).size();
            TextView chip = text(c, cat + "  " + n, 13, sel ? BG : TEXT, true);
            chip.setPadding(px(12), px(5), px(12), px(5));
            chip.setBackground(round(sel ? GOLD : PANEL, sel ? GOLD : BORDER, 14));
            chip.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                itemFilter = cat;
                render(true);
            });
            chips.addView(chip, mlp(-2, -2, 0, 0, px(6), 0));
        }
        hs.addView(chips);
        body.addView(hs, mlp(-1, -2, 0, 0, 0, px(8)));

        if (items.length() == 0) {
            body.addView(text(c, "Your bag is empty.", 14, MUTED, false));
            return;
        }
        for (Map.Entry<String, List<JSONObject>> e : byCat.entrySet()) {
            if (!itemFilter.equals("All") && !itemFilter.equals(e.getKey())) continue;
            TextView h = text(c, e.getKey().toUpperCase(Locale.ROOT), 12, TEAL, true);
            h.setLetterSpacing(0.15f);
            body.addView(h, mlp(-2, -2, px(2), px(4), 0, px(4)));
            LinearLayout list = panel(c);
            list.setPadding(0, px(2), 0, px(2));
            List<JSONObject> rows = e.getValue();
            for (int i = 0; i < rows.size(); i++) list.addView(itemRow(c, rows.get(i), i < rows.size() - 1));
            body.addView(list, mlp(-1, -2, 0, 0, 0, px(8)));
        }
    }

    private View itemRow(Context c, JSONObject it, boolean divider) {
        String name = it.optString("name");
        String key = name + "|" + it.optString("cat");
        boolean open = expandedItems.contains(key);
        LinearLayout w = new LinearLayout(c);
        w.setOrientation(LinearLayout.VERTICAL);
        w.setPadding(px(12), px(8), px(12), px(8));
        LinearLayout r = new LinearLayout(c);
        r.setGravity(Gravity.CENTER_VERTICAL);
        TextView n = text(c, name, 15, TEXT, false);
        r.addView(n, new LinearLayout.LayoutParams(0, -2, 1));
        int qty = it.optInt("qty");
        if (qty > 1) {
            TextView q = text(c, "×" + qty, 13, GOLD, true);
            r.addView(q);
        }
        w.addView(r);
        String desc = it.optString("desc");
        if (open && !desc.isEmpty()) {
            TextView d = text(c, desc, 13, MUTED, false);
            d.setPadding(0, px(4), 0, 0);
            w.addView(d);
        }
        if (open) w.setBackgroundColor(PANEL_HI);
        w.setOnClickListener(v -> {
            v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
            if (!expandedItems.remove(key)) expandedItems.add(key);
            render(false);
        });
        if (!divider) return w;
        LinearLayout box = new LinearLayout(c);
        box.setOrientation(LinearLayout.VERTICAL);
        box.addView(w);
        View line = new View(c);
        line.setBackgroundColor(BORDER);
        box.addView(line, mlp(-1, 1, px(12), 0, px(12), 0));
        return box;
    }

    // ------------------------------------------------------------------ touch commands

    private View commandPanel(Context c, JSONObject menu) {
        LinearLayout p = panel(c);
        p.setPadding(px(10), px(8), px(10), px(10));
        p.setBackground(round(PANEL, TEAL, 10));
        String kind = menu.optString("kind");
        String actorId = menu.optString("actor");
        String actorName = actorId;
        JSONArray party = snap.optJSONArray("party");
        if (party != null) for (int i = 0; i < party.length(); i++)
            if (actorId.equals(party.optJSONObject(i).optString("id"))) actorName = party.optJSONObject(i).optString("name");

        LinearLayout head = new LinearLayout(c);
        head.setGravity(Gravity.CENTER_VERTICAL);
        if (!actorId.isEmpty()) head.addView(new AvatarView(c, actorId, TEAL), new LinearLayout.LayoutParams(px(26), px(26)));
        String title = kind.equals("skills") ? "SKILLS" : kind.equals("items") ? "ITEMS" : "COMMAND";
        TextView h = text(c, (actorName.isEmpty() ? "" : actorName.toUpperCase(Locale.ROOT) + "  ·  ") + title, 13, TEAL, true);
        h.setLetterSpacing(0.12f);
        head.addView(h, mlp(0, -2, px(8), 0, 0, 0, 1f));
        TextView back = text(c, "BACK", 12, TEXT, true);
        back.setLetterSpacing(0.15f);
        back.setPadding(px(12), px(5), px(12), px(5));
        back.setBackground(round(PANEL_HI, BORDER, 12));
        back.setOnClickListener(v -> { v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY); HostActivity.nativeAction("back"); });
        head.addView(back);
        p.addView(head, mlp(-1, -2, 0, 0, 0, px(8)));

        JSONArray items = menu.optJSONArray("items");
        int sel = menu.optInt("sel", -1);
        int cols = kind.equals("commands") ? 2 : 2;
        LinearLayout row = null;
        for (int i = 0; items != null && i < items.length(); i++) {
            JSONObject it = items.optJSONObject(i);
            if (i % cols == 0) {
                row = new LinearLayout(c);
                p.addView(row, mlp(-1, -2, 0, i == 0 ? 0 : px(6), 0, 0));
            }
            final int idx = i;
            boolean ok = it.optBoolean("ok", true);
            LinearLayout b = new LinearLayout(c);
            b.setGravity(Gravity.CENTER_VERTICAL);
            b.setPadding(px(12), px(10), px(12), px(10));
            b.setBackground(round(i == sel ? PANEL_HI : TRACK, i == sel ? TEAL : BORDER, 10));
            b.setAlpha(ok ? 1f : 0.4f);
            TextView l = text(c, it.optString("label"), 16, TEXT, true);
            l.setSingleLine(true);
            l.setEllipsize(TextUtils.TruncateAt.END);
            b.addView(l, new LinearLayout.LayoutParams(0, -2, 1));
            String sub = it.optString("sub");
            if (!sub.isEmpty()) b.addView(text(c, sub, 13, kind.equals("skills") ? MP_B : GOLD, true));
            b.setOnClickListener(v -> {
                v.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY);
                HostActivity.nativeAction("pick:" + idx);
            });
            LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(0, -2, 1);
            if (i % cols > 0) lp.leftMargin = px(6);
            row.addView(b, lp);
        }
        if (items != null && items.length() % cols != 0) row.addView(new View(c), new LinearLayout.LayoutParams(0, 1, cols - items.length() % cols));
        return p;
    }

    // ------------------------------------------------------------------ portraits

    private void fetchPortraits(JSONArray party) {
        if (party == null) return;
        for (int i = 0; i < party.length(); i++) {
            String id = party.optJSONObject(i).optString("id");
            if (id.isEmpty() || portraits.containsKey(id)) continue;
            byte[] raw;
            try { raw = HostActivity.nativePortrait(id); } catch (Throwable t) { raw = null; }
            if (raw == null || raw.length < 8) continue;
            ByteBuffer bb = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN);
            int w = bb.getInt(0), h = bb.getInt(4);
            if (w <= 0 || h <= 0 || raw.length < 8 + w * h * 4) continue;
            int[] argb = new int[w * h];
            for (int y = 0; y < h; y++) {
                int src = 8 + (h - 1 - y) * w * 4;   // rows arrive bottom-up
                for (int x = 0; x < w; x++, src += 4)
                    argb[y * w + x] = ((raw[src + 3] & 0xFF) << 24) | ((raw[src] & 0xFF) << 16) | ((raw[src + 1] & 0xFF) << 8) | (raw[src + 2] & 0xFF);
            }
            portraits.put(id, Bitmap.createBitmap(argb, w, h, Bitmap.Config.ARGB_8888));
            portraitsKnown++;
        }
    }

    /** Character portrait (pixel art, scaled with nearest-neighbour) on a tinted rounded tile. */
    class AvatarView extends View {
        final String id; final int tint;
        final Paint p = new Paint();
        AvatarView(Context c, String id, int tint) { super(c); this.id = id; this.tint = tint; }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight(), r = Math.min(w, h) * 0.22f;
            p.setAntiAlias(true);
            p.setColor(withAlpha(tint, 0x55));
            cv.drawRoundRect(new RectF(0, 0, w, h), r, r, p);
            Bitmap b = portraits.get(id);
            if (b != null) {
                p.setFilterBitmap(false);
                p.setAntiAlias(false);
                float s = Math.min(w / b.getWidth(), h / b.getHeight());
                float bw = b.getWidth() * s, bh = b.getHeight() * s;
                cv.drawBitmap(b, null, new RectF((w - bw) / 2, (h - bh) / 2, (w + bw) / 2, (h + bh) / 2), p);
            } else {
                p.setColor(tint);
                p.setTextAlign(Paint.Align.CENTER);
                p.setTypeface(FACE_B);
                p.setTextSize(h * 0.5f);
                cv.drawText(id.isEmpty() ? "?" : id.substring(0, 1), w / 2, h / 2 - (p.descent() + p.ascent()) / 2, p);
            }
        }
    }

    // ------------------------------------------------------------------ helpers

    int px(float v) { return Math.round(v * dp); }

    static float frac(int a, int b) { return b <= 0 ? 0f : Math.max(0f, Math.min(1f, a / (float) b)); }

    static int parseColor(String s) { try { return Color.parseColor(s); } catch (Exception e) { return TEAL; } }

    static int withAlpha(int c, int a) { return (c & 0x00FFFFFF) | (a << 24); }

    TextView text(Context c, String s, float sp, int color, boolean bold) {
        TextView t = new TextView(c);
        t.setText(s);
        t.setTextSize(TypedValue.COMPLEX_UNIT_SP, sp);
        t.setTextColor(color);
        t.setTypeface(bold ? FACE_B : FACE);
        t.setIncludeFontPadding(false);
        return t;
    }

    GradientDrawable round(int fill, int stroke, float radiusDp) {
        GradientDrawable g = new GradientDrawable();
        g.setColor(fill);
        g.setCornerRadius(px(radiusDp));
        if (stroke != 0) g.setStroke(Math.max(1, px(1)), stroke);
        return g;
    }

    LinearLayout panel(Context c) {
        LinearLayout p = new LinearLayout(c);
        p.setOrientation(LinearLayout.VERTICAL);
        p.setBackground(round(PANEL, BORDER, 10));
        return p;
    }

    LinearLayout.LayoutParams mlp(int w, int h, int l, int t, int r, int b) { return mlp(w, h, l, t, r, b, 0); }

    LinearLayout.LayoutParams mlp(int w, int h, int l, int t, int r, int b, float weight) {
        LinearLayout.LayoutParams lp = new LinearLayout.LayoutParams(w, h, weight);
        lp.setMargins(l, t, r, b);
        return lp;
    }

    // ------------------------------------------------------------------ custom widgets

    /** Label + value on one line, gradient bar below (styled like the game's HP/MP bars). */
    class BarView extends View {
        final String label, value; final float f; final int a, b;
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        BarView(Context c, String label, String value, float f, int a, int b) { super(c); this.label = label; this.value = value; this.f = f; this.a = a; this.b = b; }
        @Override protected void onDraw(Canvas cv) {
            float w = getWidth(), h = getHeight();
            float barH = px(7), barY = h - barH - px(1);
            p.setShader(null);
            p.setTypeface(FACE_B);
            p.setTextSize(px(11));
            p.setColor(MUTED);
            cv.drawText(label, 0, barY - px(4), p);
            p.setTextSize(px(13));
            p.setColor(TEXT);
            float tw = p.measureText(value);
            cv.drawText(value, w - tw, barY - px(4), p);
            RectF track = new RectF(0, barY, w, barY + barH);
            p.setColor(TRACK);
            cv.drawRoundRect(track, barH / 2, barH / 2, p);
            if (f > 0) {
                RectF fill = new RectF(0, barY, Math.max(barH, w * f), barY + barH);
                p.setShader(new LinearGradient(0, 0, w, 0, a, b, Shader.TileMode.CLAMP));
                cv.drawRoundRect(fill, barH / 2, barH / 2, p);
                p.setShader(null);
            }
        }
    }

    /** Enemy cast countdown, an octagon medallion like the game's timer icon. */
    class CountdownView extends View {
        final int n; final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        CountdownView(Context c, int n) { super(c); this.n = n; }
        @Override protected void onDraw(Canvas cv) {
            float s = Math.min(getWidth(), getHeight()), cx = getWidth() / 2f, cy = getHeight() / 2f, r = s / 2f - px(2);
            Path oct = new Path();
            for (int i = 0; i < 8; i++) {
                double a = Math.PI / 8 + i * Math.PI / 4;
                float x = cx + (float) (r * Math.cos(a)), y = cy + (float) (r * Math.sin(a));
                if (i == 0) oct.moveTo(x, y); else oct.lineTo(x, y);
            }
            oct.close();
            int col = n <= 1 ? DANGER : ORANGE;
            p.setStyle(Paint.Style.FILL);
            p.setColor(0xFF2A1410);
            cv.drawPath(oct, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(px(2.2f));
            p.setColor(col);
            cv.drawPath(oct, p);
            p.setStyle(Paint.Style.FILL);
            p.setColor(Color.WHITE);
            p.setTypeface(FACE_B);
            p.setTextSize(s * 0.5f);
            p.setTextAlign(Paint.Align.CENTER);
            cv.drawText(String.valueOf(n), cx, cy - (p.descent() + p.ascent()) / 2, p);
        }
    }

    static int lockColor(String t) {
        switch (t) {
            case "Sword": return 0xFFC9CFDC;
            case "Blunt": return 0xFFB98A5A;
            case "Sun": return 0xFFFCA044;
            case "Moon": return 0xFF7DDBFF;
            case "Eclipse": return 0xFF9A6BE0;
            case "Arcane": return 0xFFE07BE8;
            case "Poison": return 0xFF7CC75B;
            case "Stun": return 0xFFF3DD62;
            default: return 0xFF9AA0B5;
        }
    }

    /** One spell lock: damage-type glyph on a coloured disc, or "?" while unrevealed. */
    class LockIconView extends View {
        final String t; final boolean revealed; final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        LockIconView(Context c, String t, boolean revealed) { super(c); this.t = t; this.revealed = revealed; }
        @Override protected void onDraw(Canvas cv) {
            float s = Math.min(getWidth(), getHeight()), cx = s / 2, cy = s / 2, r = s / 2 - px(1);
            int col = revealed ? lockColor(t) : 0xFF3A4470;
            p.setStyle(Paint.Style.FILL);
            p.setColor(withAlpha(col, 0x40));
            cv.drawCircle(cx, cy, r, p);
            p.setStyle(Paint.Style.STROKE);
            p.setStrokeWidth(px(1.6f));
            p.setColor(col);
            cv.drawCircle(cx, cy, r, p);
            if (!revealed) {
                p.setStyle(Paint.Style.FILL);
                p.setColor(MUTED);
                p.setTypeface(FACE_B);
                p.setTextSize(s * 0.55f);
                p.setTextAlign(Paint.Align.CENTER);
                cv.drawText("?", cx, cy - (p.descent() + p.ascent()) / 2, p);
                return;
            }
            float g = r * 0.55f;
            p.setColor(col);
            p.setStrokeWidth(px(2f));
            p.setStrokeCap(Paint.Cap.ROUND);
            switch (t) {
                case "Sword":
                    cv.drawLine(cx - g, cy + g, cx + g, cy - g, p);
                    cv.drawLine(cx - g * 0.9f, cy + g * 0.2f, cx - g * 0.2f, cy + g * 0.9f, p);
                    break;
                case "Blunt":
                    p.setStyle(Paint.Style.FILL);
                    cv.drawRect(cx - g, cy - g * 0.8f, cx + g, cy - g * 0.1f, p);
                    p.setStyle(Paint.Style.STROKE);
                    cv.drawLine(cx, cy - g * 0.1f, cx, cy + g, p);
                    break;
                case "Sun":
                    p.setStyle(Paint.Style.FILL);
                    cv.drawCircle(cx, cy, g * 0.5f, p);
                    p.setStyle(Paint.Style.STROKE);
                    for (int i = 0; i < 8; i++) {
                        double a = i * Math.PI / 4;
                        cv.drawLine(cx + (float) (Math.cos(a) * g * 0.75f), cy + (float) (Math.sin(a) * g * 0.75f),
                                cx + (float) (Math.cos(a) * g * 1.05f), cy + (float) (Math.sin(a) * g * 1.05f), p);
                    }
                    break;
                case "Moon": {
                    p.setStyle(Paint.Style.FILL);
                    Path m = new Path();
                    m.addCircle(cx, cy, g, Path.Direction.CW);
                    Path cut = new Path();
                    cut.addCircle(cx + g * 0.55f, cy - g * 0.3f, g * 0.85f, Path.Direction.CW);
                    m.op(cut, Path.Op.DIFFERENCE);
                    cv.drawPath(m, p);
                    break;
                }
                case "Eclipse":
                    p.setStyle(Paint.Style.FILL);
                    cv.drawCircle(cx, cy, g, p);
                    p.setColor(0xFF120A24);
                    cv.drawCircle(cx + g * 0.35f, cy, g * 0.8f, p);
                    break;
                case "Arcane": {
                    p.setStyle(Paint.Style.FILL);
                    Path st = new Path();
                    st.moveTo(cx, cy - g); st.lineTo(cx + g * 0.28f, cy - g * 0.28f); st.lineTo(cx + g, cy);
                    st.lineTo(cx + g * 0.28f, cy + g * 0.28f); st.lineTo(cx, cy + g); st.lineTo(cx - g * 0.28f, cy + g * 0.28f);
                    st.lineTo(cx - g, cy); st.lineTo(cx - g * 0.28f, cy - g * 0.28f); st.close();
                    cv.drawPath(st, p);
                    break;
                }
                case "Poison": {
                    p.setStyle(Paint.Style.FILL);
                    Path d = new Path();
                    d.moveTo(cx, cy - g);
                    d.quadTo(cx + g, cy + g * 0.2f, cx, cy + g);
                    d.quadTo(cx - g, cy + g * 0.2f, cx, cy - g);
                    cv.drawPath(d, p);
                    break;
                }
                case "Stun":
                    cv.drawLine(cx - g, cy - g * 0.3f, cx - g * 0.2f, cy + g * 0.3f, p);
                    cv.drawLine(cx - g * 0.2f, cy + g * 0.3f, cx + g * 0.2f, cy - g * 0.3f, p);
                    cv.drawLine(cx + g * 0.2f, cy - g * 0.3f, cx + g, cy + g * 0.3f, p);
                    break;
                default:
                    p.setStyle(Paint.Style.FILL);
                    cv.drawCircle(cx, cy, g * 0.45f, p);
            }
            p.setStyle(Paint.Style.FILL);
        }
    }

    /** Combo points: diamonds filled by fraction (1000 units per point in game data). */
    class ComboView extends View {
        final float pts; final int max; final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        ComboView(Context c, float pts, int max) { super(c); this.pts = pts; this.max = max; }
        @Override protected void onDraw(Canvas cv) {
            float h = getHeight(), s = h * 0.42f, gap = px(6);
            for (int i = 0; i < max; i++) {
                float cx = s + i * (2 * s + gap), cy = h / 2;
                Path d = new Path();
                d.moveTo(cx, cy - s); d.lineTo(cx + s, cy); d.lineTo(cx, cy + s); d.lineTo(cx - s, cy); d.close();
                float fill = Math.max(0, Math.min(1, pts - i));
                p.setStyle(Paint.Style.FILL);
                p.setColor(TRACK);
                cv.drawPath(d, p);
                if (fill > 0) {
                    cv.save();
                    cv.clipRect(cx - s, cy + s - 2 * s * fill, cx + s, cy + s);
                    p.setColor(fill >= 1 ? TEAL : withAlpha(TEAL, 0x90));
                    cv.drawPath(d, p);
                    cv.restore();
                }
                p.setStyle(Paint.Style.STROKE);
                p.setStrokeWidth(px(1.5f));
                p.setColor(fill >= 1 ? TEAL : BORDER);
                cv.drawPath(d, p);
            }
        }
    }

    /** Live Mana charges absorbed by a party member. */
    class ManaView extends View {
        final int n; final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        ManaView(Context c, int n) { super(c); this.n = n; }
        @Override protected void onDraw(Canvas cv) {
            float h = getHeight(), r = h * 0.32f;
            for (int i = 0; i < 3; i++) {
                float cx = r + px(1) + i * (2 * r + px(3));
                p.setStyle(i < n ? Paint.Style.FILL : Paint.Style.STROKE);
                p.setStrokeWidth(px(1.2f));
                p.setColor(i < n ? 0xFFB79CFF : BORDER);
                cv.drawCircle(cx, h / 2, r, p);
            }
        }
    }

    class CoinView extends View {
        final Paint p = new Paint(Paint.ANTI_ALIAS_FLAG);
        CoinView(Context c) { super(c); }
        @Override protected void onDraw(Canvas cv) {
            float s = Math.min(getWidth(), getHeight());
            p.setColor(GOLD);
            cv.drawCircle(s / 2, s / 2, s / 2 - 1, p);
            p.setColor(0xFFB8862F);
            cv.drawCircle(s / 2, s / 2, s / 4, p);
        }
    }
}
