/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.navigation;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Puts Discover in the bottom bar, in the second spot where Friends sits (#98). Indonesia gets
 * that layout from TikTok; everywhere else needed a region change, which changes the feed too.
 *
 * <p>TikTok lays the bar out from one server gate, {@code tab_fcp_config_v2}, which it parses into
 * its TabConfig class. TabConfig's {@code bottomTab} map holds five spots, "0" to "4", each a list
 * of candidate tabs, and TikTok shows the first candidate of a spot that passes its rules. In the
 * US spot "1" is [friends, discover], so Friends wins. With the switch on that spot holds Discover
 * alone. TikTok checks the layout before using it, and a map without all five spots sends it back
 * to its built-in layout, so the one spot is replaced and every other one stays as sent.
 */
public final class DiscoverTab {
    static final String GATE_KEY = "tab_fcp_config_v2";
    static final String SPOT = "1";
    static final String DISCOVER = "discover";
    /** What the bottom tab filter calls the Discover tab once TikTok shows it. */
    public static final String FILTER_KEY = BottomNavigationTabOptions.normalizeRuntimeTag(DISCOVER);

    private DiscoverTab() {
    }

    /** Every value the App AB class's typed object getter returns, with its key. */
    @SuppressWarnings("unused")
    public static Object apply(String key, Object value) {
        if (value == null || !GATE_KEY.equals(key)) {
            return value;
        }
        try {
            if (Settings.DISCOVER_BOTTOM_TAB.get()) {
                putDiscover(value);
            }
        } catch (Throwable problem) {
            Logger.printException(() -> "Discover tab: left the bottom bar as TikTok sent it", problem);
        }
        return value;
    }

    /**
     * Makes Discover the only candidate of the second spot. False when the layout has no such
     * spot or no tab to copy the class from, which leaves it as it was.
     */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static boolean putDiscover(Object config) throws ReflectiveOperationException {
        Field slotsField = findField(config.getClass(), "bottomTab");
        if (slotsField == null) {
            return false;
        }
        slotsField.setAccessible(true);
        Object slots = slotsField.get(config);
        if (!(slots instanceof Map)) {
            return false;
        }
        Map map = (Map) slots;
        Object spot = map.get(SPOT);
        if (!(spot instanceof List)) {
            return false;
        }
        List<?> candidates = (List<?>) spot;
        if (candidates.size() == 1 && DISCOVER.equals(tabKey(candidates.get(0)))) {
            return true;
        }
        Object template = anyTab(map);
        if (template == null) {
            return false;
        }
        // A new one rather than TikTok's own Discover candidate, whose rules may be what keeps it
        // out where it is listed. The field is declared as an ArrayList.
        ArrayList<Object> discoverOnly = new ArrayList<>(1);
        discoverOnly.add(newTab(template.getClass()));
        try {
            map.put(SPOT, discoverOnly);
        } catch (UnsupportedOperationException readOnly) {
            Map copy = new LinkedHashMap(map);
            copy.put(SPOT, discoverOnly);
            slotsField.set(config, copy);
        }
        return true;
    }

    private static Object anyTab(Map<?, ?> slots) {
        for (Object spot : slots.values()) {
            if (spot instanceof List) {
                for (Object tab : (List<?>) spot) {
                    if (tab != null) {
                        return tab;
                    }
                }
            }
        }
        return null;
    }

    private static Object newTab(Class<?> type) throws ReflectiveOperationException {
        try {
            Constructor<?> constructor = type.getDeclaredConstructor(String.class, Map.class);
            constructor.setAccessible(true);
            return constructor.newInstance(DISCOVER, null);
        } catch (NoSuchMethodException noKeyConstructor) {
            Constructor<?> constructor = type.getDeclaredConstructor();
            constructor.setAccessible(true);
            Object tab = constructor.newInstance();
            Field key = findField(type, "bottomTabKey");
            if (key == null) {
                throw new NoSuchFieldException("bottomTabKey on " + type.getName());
            }
            key.setAccessible(true);
            key.set(tab, DISCOVER);
            return tab;
        }
    }

    private static String tabKey(Object tab) throws IllegalAccessException {
        if (tab == null) {
            return null;
        }
        Field key = findField(tab.getClass(), "bottomTabKey");
        if (key == null) {
            return null;
        }
        key.setAccessible(true);
        Object value = key.get(tab);
        return value instanceof String ? (String) value : null;
    }

    private static Field findField(Class<?> type, String name) {
        for (Class<?> current = type; current != null && current != Object.class; current = current.getSuperclass()) {
            try {
                return current.getDeclaredField(name);
            } catch (NoSuchFieldException ignored) {
                // Declared further up, or not at all.
            }
        }
        return null;
    }
}
