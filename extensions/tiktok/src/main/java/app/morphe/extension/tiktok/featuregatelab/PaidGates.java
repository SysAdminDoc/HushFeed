/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.featuregatelab;

import java.util.Arrays;
import java.util.HashSet;
import java.util.Locale;
import java.util.Set;

/**
 * Gates the Lab never changes: TikTok's paid space. That covers subscriptions, Super Fan and
 * fan clubs, paid content and paywalls, LIVE gifts, coins and wallets, payments, Promote,
 * ad-free, AI credits and quotas, and rewards. Changing one could open something TikTok sells,
 * so these gates are left out of the catalogue, refused on save and import, dropped from
 * backups and stored rules, and never applied.
 *
 * <p>The match is on whole words of the key (split at anything not a letter or digit, and
 * where camelCase starts a new word), so a new build's gates are covered without a list of
 * names. It errs wide: a few unrelated gates that share a word are left out too.
 */
public final class PaidGates {
    private static final Set<String> WORDS = new HashSet<>(Arrays.asList(
            "paid", "prepaid", "unpaid", "paywall", "premium", "vip", "pgc", "iap",
            "purchase", "repurchase",
            "subscription", "subscriptions", "subscribe", "subscribing", "subscriber",
            "subscribers", "superfan", "privilege", "privileges", "membership",
            "gift", "gifts", "coin", "coins", "diamond", "diamonds", "recharge", "wallet",
            "pay", "payment", "payments", "credit", "credits", "coupon", "coupons",
            "voucher", "vouchers", "promote", "monetization", "m10n",
            "entitlement", "entitlements", "unlock", "unlocked",
            "reward", "rewards", "bonus", "benefit", "benefits"
    ));

    /** Two words that name a paid thing only together ("trial" or "quota" alone is often not). */
    private static final String[][] PAIRS = {
            {"super", "fan"}, {"fans", "club"}, {"fan", "club"}, {"ad", "free"},
            {"sub", "only"}, {"free", "trial"}, {"ai", "quota"}
    };

    private PaidGates() {
    }

    public static boolean isPaid(String key) {
        if (key == null || key.isEmpty()) return false;
        String[] words = words(key);
        for (String word : words) {
            if (WORDS.contains(word)) return true;
        }
        for (int i = 0; i + 1 < words.length; i++) {
            for (String[] pair : PAIRS) {
                if (pair[0].equals(words[i]) && pair[1].equals(words[i + 1])) return true;
            }
        }
        return false;
    }

    private static String[] words(String key) {
        StringBuilder spaced = new StringBuilder(key.length() + 8);
        for (int i = 0; i < key.length(); i++) {
            char c = key.charAt(i);
            if (i > 0 && Character.isUpperCase(c)) {
                char before = key.charAt(i - 1);
                if (Character.isLowerCase(before) || Character.isDigit(before)) spaced.append('_');
            }
            spaced.append(c);
        }
        String lower = spaced.toString().toLowerCase(Locale.ROOT);
        String[] parts = lower.split("[^a-z0-9]+");
        if (parts.length > 0 && parts[0].isEmpty()) {
            return Arrays.copyOfRange(parts, 1, parts.length);
        }
        return parts;
    }
}
