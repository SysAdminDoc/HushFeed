/*
 * Copyright 2026 Hushfeed contributors
 * https://github.com/SysAdminDoc/hushfeed
 */
package app.morphe.extension.tiktok.privacy;

import android.content.Context;
import android.content.pm.PackageInfo;
import android.content.pm.PackageManager;
import android.content.pm.Signature;
import android.util.Base64;

import androidx.annotation.Nullable;

import app.morphe.extension.shared.Logger;
import app.morphe.extension.shared.Utils;
import app.morphe.extension.shared.settings.BaseSettings;
import app.morphe.extension.tiktok.settings.Settings;

/**
 * Answers TikTok's own checks of how it was signed and installed the way the Play Store build
 * would. A patched TikTok is re-signed with the patcher's key and installed by Morphe Manager, and
 * both go out with TikTok's reports: AppLog's {@code sig_hash} and the security SDK's callbacks,
 * which hand its native side the signing certificate and the install source.
 *
 * <p>Every one of TikTok's own reads of its certificate goes through one {@code getPackageInfo}
 * wrapper, which caches some flag combinations and which the patch answers from
 * {@link #packageInfo}. Its reads of the installer go through {@link #installerFor} and, from
 * Android 11, {@link StoreInstallSource}. What TikTok's native code reads for itself, from the
 * APK on disk, is out of reach here.
 *
 * <p>Before Hushfeed has a context the setting can't be read, and every answer here is the
 * real one.
 */
@SuppressWarnings("unused")
public final class StoreIdentity {
    /** The Play Store, which installs the build a store user has. */
    static final String STORE = "com.android.vending";

    /**
     * The stores an installer read can name, the Play Store first. Each one ships TikTok, so a
     * store TikTok doesn't know never comes up. A saved value off this list reads as the Play Store.
     */
    private static final String[] INSTALLERS = {
            STORE,
            "com.sec.android.app.samsungapps",
            "com.huawei.appmarket",
            "com.amazon.venezia",
    };

    /** The choices for the settings row, in the order its labels name them. */
    public static String[] installers() {
        return INSTALLERS.clone();
    }

    /** The store picked under the switch, or the Play Store for anything not on the list. */
    static String installer() {
        String picked = Settings.STORE_IDENTITY_INSTALLER.get();
        for (String installer : INSTALLERS) {
            if (installer.equals(picked)) return installer;
        }
        return STORE;
    }

    /**
     * TikTok's own signing certificate (CN=musical.ly) as DER, the bytes
     * {@link Signature#toByteArray()} gives on a store build. Its SHA-256 is the
     * {@code 9041803e...ab5ba} digest Morphe Manager holds a picked APK to.
     */
    static final String CERTIFICATE =
            "MIIDhzCCAm+gAwIBAgIEMsei9zANBgkqhkiG9w0BAQsFADB0MQswCQYDVQQGEwI4NjERMA8GA1UECBMIU2hh"
            + "bmdoYWkxETAPBgNVBAcTCFNoYW5naGFpMRgwFgYDVQQKEw9tdXNpY2FsLmx5IEluYy4xEDAOBgNVBAsTB2Fu"
            + "ZHJvaWQxEzARBgNVBAMTCm11c2ljYWwubHkwHhcNMTUwNDI4MDQyNzE3WhcNNDAwNDIxMDQyNzE3WjB0MQsw"
            + "CQYDVQQGEwI4NjERMA8GA1UECBMIU2hhbmdoYWkxETAPBgNVBAcTCFNoYW5naGFpMRgwFgYDVQQKEw9tdXNp"
            + "Y2FsLmx5IEluYy4xEDAOBgNVBAsTB2FuZHJvaWQxEzARBgNVBAMTCm11c2ljYWwubHkwggEiMA0GCSqGSIb3"
            + "DQEBAQUAA4IBDwAwggEKAoIBAQCvEuNMCwMeQebJmsO2NtQlOqdYTrya5MWSRLApebgJaSefMubj3+AaDXy2"
            + "7UAA6JI92Q1xcaM3hk9qZMWQ2yBLqrl/AT/ox97+PKtMFrJMtpWaPP+5kFcjwKERbQAqnP1yHB56Fjg9R+J+"
            + "1Dh/jcy6bkTVdB2ly3opX4wytSdQw+1aVvSU/z1mfKPVnJw1c79oVmdyebhNRdgMU7OpQZEau+mxXOpjar+b"
            + "pj6ZssntevpI+i8JaB/dVZ9Hkuz1omBAAY76971BGtsNUuKlrYQkp3b1Q1g6fpJsawM3yqSu+iP5r+UsmTOh"
            + "/G5zvJPbjU4qtFDC3pBkjdGc19CcVlK/AgMBAAGjITAfMB0GA1UdDgQWBBQi6xoMVEjV/7KBd/FF3/fs9sH+"
            + "ITANBgkqhkiG9w0BAQsFAAOCAQEALzQnY5WC+mmWAssIZZuq9yLMEc4uKXhBMPa0alogY36uTxkQaiQzqthD"
            + "Ph8+JsKQSJVmb59PRhw283ApwlHHBg2cVUU605A3WjS7eFWQAjhZAEbXYY4CosyRvr+aH6250iC8ksGEcjw2"
            + "a2y//pOLsK6AL5YPhwOdG3xhO6hCgoRbl/zsrXRo3s6j2Db3VFNpGT3wTXQGxqAtC/cN/zZHbT1LV4wikpa/"
            + "ijVwSf5+V3mTcH0pQsSZiyM1wNqWiUr71jihfYY8l/f94qh4Rfh7dwIA3y6OtyCtP0+p/Oieop5s+i/Q08Tn"
            + "6xDztKKaUNvqO9pv83jB0UvACa9m2woW0g==";

    @Nullable
    private static volatile Signature[] original;
    /** TikTok's own package with GET_SIGNATURES alone, which is how every reader of it asks. */
    @Nullable
    private static volatile PackageInfo signedOnly;
    private static volatile boolean logged;
    /** The store the last logged installer read answered. Tests clear it. */
    @Nullable
    static volatile String loggedInstaller;

    private StoreIdentity() {
    }

    /**
     * From the head of TikTok's cached {@code getPackageInfo}: TikTok's own package asked for with
     * its signatures comes back carrying TikTok's certificate. Null lets TikTok's lookup run.
     */
    @Nullable
    public static PackageInfo packageInfo(PackageManager manager, String packageName, int flags) {
        if ((flags & PackageManager.GET_SIGNATURES) == 0 || !answersFor(packageName)) return null;
        try {
            if (flags == PackageManager.GET_SIGNATURES) {
                PackageInfo cached = signedOnly;
                if (cached != null) return cached;
            }
            PackageInfo info = manager.getPackageInfo(packageName, flags);
            info.signatures = signatures();
            if (flags == PackageManager.GET_SIGNATURES) signedOnly = info;
            if (!logged) {
                logged = true;
                Logger.printInfo(() -> "Store identity: the store build's certificate answered a signature read");
            }
            return info;
        } catch (Exception ex) {
            Logger.printException(() -> "Store identity: package lookup failed", ex);
            return null;
        }
    }

    /**
     * In place of {@link PackageManager#getInstallerPackageName(String)}: the picked store for
     * TikTok's own package, and the real installer for anything else it asks about. The patch
     * puts this in place of each call site, so anything else, and everything with the switch
     * off, gets the real call with whatever it throws.
     */
    @Nullable
    public static String installerFor(PackageManager manager, String packageName) {
        if (!answersFor(packageName)) return manager.getInstallerPackageName(packageName);
        return logged(installer());
    }

    /**
     * {@code store}, after Log diagnostics names it once per store rather than once per read.
     * Every installer answer goes through here, {@link StoreInstallSource}'s included, since from
     * Android 11 TikTok asks that one first. A read with logging off doesn't count, so turning
     * logging on mid-session still shows the store.
     */
    static String logged(String store) {
        if (BaseSettings.DEBUG.get() && !store.equals(loggedInstaller)) {
            loggedInstaller = store;
            Logger.printDebug(() -> "Store identity: TikTok's installer read answered " + store);
        }
        return store;
    }

    /** Whether the switch is on and {@code packageName} is TikTok's own. */
    static boolean answersFor(@Nullable String packageName) {
        Context context = Utils.getContext();
        if (context == null || packageName == null || !Settings.STORE_IDENTITY.get()) return false;
        return packageName.equals(context.getPackageName());
    }

    /** The certificate of the store build of the app this runs in, which can't change while it runs. */
    static Signature[] signatures() {
        Signature[] known = original;
        if (known == null) {
            known = new Signature[]{new Signature(Base64.decode(CERTIFICATE, Base64.DEFAULT))};
            original = known;
        }
        return known.clone();
    }
}
