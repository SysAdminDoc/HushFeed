package app.hushfeed.verification;

import android.content.Context;
import java.util.LinkedHashMap;
import java.util.Map;

public final class PineDramaCapabilitiesContract {
    private static int checks;
    public static final class Status { public static boolean commentPhotosEnabled = true; }
    public static final class Photos {
        static boolean enabled;
        static int reads;
        public static boolean isOn() { reads++; return enabled; }
    }
    public static class Gate { public boolean gate(boolean input) { throw new AssertionError("Native gate invoked"); } }
    public static final class AmbiguousGate extends Gate {
        public boolean first(boolean input) { throw new AssertionError("Native gate invoked"); }
        public boolean second(boolean input) { throw new AssertionError("Native gate invoked"); }
    }
    public static final class StaticGate { public static boolean gate(boolean input) { throw new AssertionError("Native gate invoked"); } }
    public static final class Album {
        public boolean ensureEntryPluginInstalled(Context context, String entry, boolean flag) { throw new AssertionError("Native plugin invoked"); }
    }
    public static final class WrongAlbum {
        public boolean ensureEntryPluginInstalled(Object context, String entry, boolean flag) { throw new AssertionError("Native plugin invoked"); }
    }
    private static final class Host implements PineDramaCapabilities.Facts {
        String pkg = PineDramaCapabilities.PACKAGE, version = PineDramaCapabilities.VERSION;
        boolean installedVersion = true, apk = true, base = true, permissions = true, settings = true, foreground;
        Class<?> gate = Gate.class, album = Album.class;
        int classReads;
        @Override public String packageName() { return pkg; }
        @Override public String versionName() { return version; }
        @Override public boolean installedVersionMatches() { return installedVersion; }
        @Override public boolean installedApkMatches() { return apk; }
        @Override public boolean singleBaseApk() { return base; }
        @Override public boolean photoPermissionsDeclared() { return permissions; }
        @Override public boolean settingsActivityDeclared() { return settings; }
        @Override public boolean settingsActivityForeground() { return foreground; }
        @Override public ClassLoader loader() {
            return new ClassLoader(PineDramaCapabilitiesContract.class.getClassLoader()) {
                @Override public Class<?> loadClass(String name) throws ClassNotFoundException {
                    classReads++;
                    switch (name) {
                        case "app.morphe.extension.tiktok.settings.SettingsStatus": return Status.class;
                        case "app.morphe.extension.tiktok.comment.CommentPhotos": return Photos.class;
                        case "com.ss.android.ugc.aweme.appdiff.DramaCommentAppDiffProtocol": return gate;
                        case "com.ss.android.ugc.aweme.creativetools.DramaCreativeToolsPluginServiceImpl": return album;
                        default: return super.loadClass(name);
                    }
                }
            };
        }
    }
    private static Map<String, Boolean> inspect(Host host, String profile, String action) {
        String report = PineDramaCapabilities.inspect(host, profile, action);
        check(report.startsWith("pine-capabilities-v1 "), "Report prefix changed");
        Map<String, Boolean> fields = new LinkedHashMap<>();
        for (String token : report.substring("pine-capabilities-v1 ".length()).split(" ")) {
            check(token.matches("[A-Za-z]+=(true|false)"), "Non-boolean output");
            String[] pair = token.split("=");
            check(fields.put(pair[0], Boolean.valueOf(pair[1])) == null, "Repeated field");
        }
        check(fields.size() == 15, "Unexpected capability field count");
        check(!fields.get("wholeAppCoverageComplete"), "Partial inspection became a full walk");
        return fields;
    }
    private static Map<String, Boolean> inspect(Host host) { return inspect(host, PineDramaCapabilities.PROFILE, "capabilities"); }
    private static void rejectsWithoutReflection(Host host, String profile, String action) {
        check(!inspect(host, profile, action).get("requestAccepted"), "Wrong host/request accepted");
        check(host.classReads == 0, "Wrong host/request reached extension or native classes");
    }
    private static void check(boolean condition, String reason) { checks++; if (!condition) throw new AssertionError(reason); }

    public static void main(String[] args) {
        Host host = new Host();
        check(inspect(host).get("requestAccepted"), "Supported capabilities rejected");
        check(!Photos.enabled && Photos.reads == 1, "Inspection changed the photo preference");
        Photos.enabled = true;
        check(inspect(host).get("photoSwitchOn"), "Selected switch was not observed");
        check(Photos.enabled && Photos.reads == 2, "Inspection wrote the photo preference");
        host = new Host(); host.pkg = "com.zhiliaoapp.musically"; rejectsWithoutReflection(host, PineDramaCapabilities.PROFILE, "capabilities");
        host = new Host(); host.version = "47.4.3"; rejectsWithoutReflection(host, PineDramaCapabilities.PROFILE, "capabilities");
        host = new Host(); host.installedVersion = false; rejectsWithoutReflection(host, PineDramaCapabilities.PROFILE, "capabilities");
        host = new Host(); host.apk = false; rejectsWithoutReflection(host, PineDramaCapabilities.PROFILE, "capabilities");
        host = new Host(); host.base = false; rejectsWithoutReflection(host, PineDramaCapabilities.PROFILE, "capabilities");
        rejectsWithoutReflection(new Host(), "tiktok-whole-app-v1", "capabilities");
        for (String action : new String[] {null, "dump", "settings", "set", "tap", "swipe", "comments", "open", "follow", "send", "publish", "labrule"}) {
            rejectsWithoutReflection(new Host(), PineDramaCapabilities.PROFILE, action);
        }
        Status.commentPhotosEnabled = false;
        int reads = Photos.reads;
        check(!inspect(new Host()).get("requestAccepted"), "Global negative/absent photo patch counted as Pine positive");
        check(Photos.reads == reads, "Unselected photo switch was invoked");
        Status.commentPhotosEnabled = true;
        host = new Host(); host.gate = AmbiguousGate.class; check(!inspect(host).get("requestAccepted"), "Ambiguous native gate accepted");
        host = new Host(); host.gate = StaticGate.class; check(!inspect(host).get("requestAccepted"), "Static native gate accepted");
        host = new Host(); host.album = WrongAlbum.class; check(!inspect(host).get("requestAccepted"), "Wrong album signature accepted");
        host = new Host(); host.permissions = false; check(!inspect(host).get("requestAccepted"), "Undeclared media permissions accepted");
        host = new Host(); host.settings = false; check(!inspect(host).get("requestAccepted"), "Undeclared settings activity accepted");
        check(Photos.reads == reads, "Failed prerequisite read or changed the photo preference");
        host = new Host(); host.foreground = true;
        check(inspect(host).get("settingsActivityForeground"), "Foreground identity was not a boolean");
        System.out.println("PineDrama capability contracts passed (" + checks + " assertions). No UI or native gate calls.");
    }
}
