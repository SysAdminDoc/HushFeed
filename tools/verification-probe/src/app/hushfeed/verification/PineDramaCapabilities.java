package app.hushfeed.verification;

import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

/** Read-only, boolean-only prerequisites. This profile cannot prove runtime photo entitlement. */
final class PineDramaCapabilities {
    static final String PROFILE = "pinedrama-47.5.3-capabilities-v1";
    static final String PACKAGE = "com.ss.android.ttmd.video";
    static final String VERSION = "47.5.3";
    static final String SETTINGS_ACTIVITY = "com.bytedance.ies.ugc.aweme.commercialize.compliance.personalization.AdPersonalizationActivity";

    interface Facts {
        String packageName();
        String versionName();
        boolean installedVersionMatches();
        boolean installedApkMatches();
        boolean singleBaseApk();
        boolean photoPermissionsDeclared();
        boolean settingsActivityDeclared();
        boolean settingsActivityForeground();
        ClassLoader loader();
    }

    static String inspect(Facts facts, String profile, String action) {
        boolean host = PACKAGE.equals(facts.packageName());
        boolean version = VERSION.equals(facts.versionName()) && facts.installedVersionMatches();
        boolean profileMatches = PROFILE.equals(profile);
        boolean actionMatches = "capabilities".equals(action);
        boolean installed = facts.installedApkMatches();
        boolean singleBase = facts.singleBaseApk();
        boolean selected = false, photoOn = false, gate = false, album = false;
        boolean permissions = false, settings = false, foreground = false;
        boolean accepted = host && version && profileMatches && actionMatches && installed && singleBase;
        if (accepted) {
            try {
                ClassLoader loader = facts.loader();
                Class<?> status = loader.loadClass("app.morphe.extension.tiktok.settings.SettingsStatus");
                selected = status.getField("commentPhotosEnabled").getBoolean(null);
                if (selected) {
                    Class<?> protocol = loader.loadClass("com.ss.android.ugc.aweme.appdiff.DramaCommentAppDiffProtocol");
                    int matches = 0;
                    for (Method method : protocol.getDeclaredMethods()) {
                        Class<?>[] parameters = method.getParameterTypes();
                        if (!Modifier.isStatic(method.getModifiers()) && method.getReturnType() == boolean.class
                                && parameters.length == 1 && parameters[0] == boolean.class) matches++;
                    }
                    gate = matches == 1;
                    Class<?> plugin = loader.loadClass("com.ss.android.ugc.aweme.creativetools.DramaCreativeToolsPluginServiceImpl");
                    Method entry = plugin.getDeclaredMethod("ensureEntryPluginInstalled",
                            loader.loadClass("android.content.Context"), String.class, boolean.class);
                    album = entry.getReturnType() == boolean.class && !Modifier.isStatic(entry.getModifiers());
                    permissions = facts.photoPermissionsDeclared();
                    settings = facts.settingsActivityDeclared();
                    foreground = facts.settingsActivityForeground();
                    if (gate && album && permissions && settings) {
                        Class<?> photos = loader.loadClass("app.morphe.extension.tiktok.comment.CommentPhotos");
                        photoOn = Boolean.TRUE.equals(photos.getMethod("isOn").invoke(null));
                    }
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError unavailable) {
                accepted = false;
            }
        }
        accepted &= selected && gate && album && permissions && settings;
        return "pine-capabilities-v1 requestAccepted=" + accepted + " targetPackageMatches=" + host
                + " targetVersionMatches=" + version + " profileMatches=" + profileMatches + " actionMatches=" + actionMatches
                + " installedApkMatches=" + installed + " singleBaseApk=" + singleBase
                + " photoPatchRegistered=" + selected + " photoSwitchOn=" + photoOn
                + " nativePhotoGateShape=" + gate + " nativeAlbumEntryShape=" + album
                + " photoPermissionsDeclared=" + permissions + " settingsActivityDeclared=" + settings
                + " settingsActivityForeground=" + foreground + " wholeAppCoverageComplete=false";
    }

    private PineDramaCapabilities() { }
}
