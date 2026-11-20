package app.morphe.patches.shared.compat

import app.morphe.Fixtures
import app.morphe.patches.tiktok.misc.diagnostics.TARGET_BUILD_ANCHORS
import app.morphe.patches.tiktok.misc.diagnostics.missingTargetAnchors
import app.morphe.patches.tiktok.misc.diagnostics.targetBuildAnchors
import app.morphe.patches.tiktok.misc.extension.LITE_APPLICATION_CLASS
import app.morphe.patches.tiktok.misc.extension.LITE_HOME_ACTIVITY_CLASS
import app.morphe.patches.tiktok.misc.extension.LITE_MAIN_ACTIVITY_CLASS
import app.morphe.patches.tiktok.misc.extension.afterSuperAttachBaseContext
import app.morphe.patches.tiktok.misc.settings.SHORTCUT_CLEARS
import com.android.apksig.ApkVerifier
import com.android.apksig.apk.ApkUtils
import com.android.apksig.util.DataSources
import com.android.tools.smali.dexlib2.iface.ClassDef
import com.android.tools.smali.dexlib2.iface.instruction.ReferenceInstruction
import com.android.tools.smali.dexlib2.iface.reference.FieldReference
import com.android.tools.smali.dexlib2.iface.reference.MethodReference
import com.android.tools.smali.dexlib2.iface.reference.StringReference
import com.android.tools.smali.dexlib2.iface.reference.TypeReference
import com.android.tools.smali.dexlib2.Opcode
import app.morphe.patches.tiktok.feedfilter.LITE_FEED_ITEM_LIST_CLASS
import app.morphe.patches.tiktok.feedfilter.LITE_OPENED_LIST_BUILDERS
import java.io.RandomAccessFile
import java.security.MessageDigest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * TikTok Lite (#48) against its own vendor build: the bundle has to declare the certificate and
 * version code Lite really carries, and Lite has to start up through the classes Hushfeed hooks
 * there, not TikTok's.
 */
class TikTokLiteFixtureTest {
    private val classes: Map<String, ClassDef> by lazy {
        val container = Fixtures.dexContainer(Fixtures.liteApk())
        container.dexEntryNames.flatMap { name -> checkNotNull(container.getEntry(name)).dexFile.classes }
            .associateBy { it.type }
    }

    @Test
    fun `the declared certificate and version code are Lite's own`() {
        val apk = Fixtures.liteApk()
        val result = ApkVerifier.Builder(apk).build().verify()
        assertTrue("${apk.name} does not verify: ${result.errors}", result.isVerified)
        val signers = result.signerCertificates.map { certificate ->
            MessageDigest.getInstance("SHA-256").digest(certificate.encoded).joinToString("") { "%02x".format(it) }
        }.toSet()
        val lite = AppCompatibilities.tiktokLite()
        assertEquals(setOf(AppCompatibilities.TIKTOK_LITE_SIGNER_SHA256), lite.signatures)
        assertEquals(lite.signatures, signers)
        val manifest = RandomAccessFile(apk, "r").use { ApkUtils.getAndroidManifest(DataSources.asDataSource(it)) }
        assertEquals(AppCompatibilities.TIKTOK_LITE_4782_VERSION_CODE, ApkUtils.getVersionCodeFromBinaryAndroidManifest(manifest))
        assertEquals(listOf("47.8.2"), lite.targets.map { it.version })
        assertEquals(AppCompatibilities.TIKTOK_LITE_PACKAGE, lite.packageName)
    }

    @Test
    fun `Lite starts up through its own application and launcher, which the hooks can enter`() {
        val application = classes[LITE_APPLICATION_CLASS]
        assertNotNull(LITE_APPLICATION_CLASS, application)
        val attach = application!!.methods.single {
            it.name == "attachBaseContext" && it.parameterTypes == listOf("Landroid/content/Context;")
        }
        // The context hook goes in right after Lite's own call to Application.attachBaseContext.
        assertEquals(1, afterSuperAttachBaseContext(attach))
        val launcher = classes[LITE_MAIN_ACTIVITY_CLASS]
        assertNotNull(LITE_MAIN_ACTIVITY_CLASS, launcher)
        assertTrue(launcher!!.methods.any { it.name == "onCreate" && it.parameterTypes == listOf("Landroid/os/Bundle;") })
    }

    @Test
    fun `Lite opens its home screen in the launcher's place, and that screen has the onCreate the hooks enter`() {
        // The class names Lite's Instrumentation.newActivity hands to the real newActivity.
        val dispatchers = classes.values.filter { it.superclass == "Landroid/app/Instrumentation;" }.flatMap { it.methods }
            .filter { it.name == "newActivity" && it.parameterTypes.firstOrNull()?.toString() == "Ljava/lang/ClassLoader;" }
            .map { method ->
                method.implementation?.instructions
                    ?.mapNotNull { ((it as? ReferenceInstruction)?.reference as? StringReference)?.string }
                    .orEmpty().map { "L" + it.replace('.', '/') + ";" }.filter { it in classes }.toSet()
            }
        val swaps = dispatchers.filter { LITE_MAIN_ACTIVITY_CLASS in it }
        assertTrue("no Instrumentation.newActivity in Lite names its launcher", swaps.isNotEmpty())
        val standIns = swaps.flatten().toSet() - LITE_MAIN_ACTIVITY_CLASS
        assertTrue("Lite opens $standIns in its launcher's place, not $LITE_HOME_ACTIVITY_CLASS", LITE_HOME_ACTIVITY_CLASS in standIns)
        val home = classes[LITE_HOME_ACTIVITY_CLASS]
        assertNotNull(LITE_HOME_ACTIVITY_CLASS, home)
        assertTrue(home!!.methods.any { it.name == "onCreate" && it.parameterTypes == listOf("Landroid/os/Bundle;") })
    }

    @Test
    fun `none of the TikTok classes the TikTok-only hooks and settings ride on are in Lite`() {
        for (type in listOf(
            "Lcom/ss/android/ugc/aweme/app/host/AwemeHostApplication;",
            "Lcom/ss/android/ugc/aweme/main/MainActivity;",
            "Lcom/ss/android/ugc/aweme/legoImp/task/JatoInitTask;",
            "Lcom/ss/android/ugc/aweme/legoImp/task/StoreRegionInitTask;",
            "Lcom/bytedance/ies/ugc/aweme/commercialize/compliance/personalization/AdPersonalizationActivity;",
        )) assertNull("$type is in Lite, so its hook could be made there", classes[type])
    }

    @Test
    fun `every screen capture callback Lite registers is typed on Activity, which the sweep replaces`() {
        val calls = classes.values.flatMap { classDef ->
            classDef.methods.flatMap { method ->
                method.implementation?.instructions?.mapNotNull {
                    (it as? ReferenceInstruction)?.reference as? MethodReference
                }.orEmpty()
            }
        }.filter { it.name == "registerScreenCaptureCallback" || it.name == "unregisterScreenCaptureCallback" }
        assertTrue("Lite registers no screen capture callback", calls.any { it.name == "registerScreenCaptureCallback" })
        assertEquals(setOf("Landroid/app/Activity;"), calls.map { it.definingClass }.toSet())
    }

    @Test
    fun `Lite clears its dynamic shortcuts only through the calls the settings shortcut is put back after`() {
        val clears = classes.values.flatMap { classDef ->
            classDef.methods.flatMap { method ->
                method.implementation?.instructions?.mapNotNull {
                    (it as? ReferenceInstruction)?.reference as? MethodReference
                }.orEmpty()
            }
        }.filter {
            it.definingClass == "Landroid/content/pm/ShortcutManager;" &&
                it.name in setOf("removeAllDynamicShortcuts", "setDynamicShortcuts")
        }.map { it.toString() }.toSet()
        assertTrue("Lite never clears its shortcuts, so nothing has to put Hushfeed's back", clears.isNotEmpty())
        assertTrue("$clears", SHORTCUT_CLEARS.containsAll(clears))
    }

    /**
     * The privacy patches Lite gets stand in front of Android's own calls, found wherever the app
     * makes them, and each refuses to apply when it finds none. These are the calls each one needs.
     */
    @Test
    fun `Lite makes the Android calls its privacy patches stand in front of`() {
        val calls = classes.values.flatMap { classDef ->
            classDef.methods.flatMap { method ->
                method.implementation?.instructions?.mapNotNull {
                    (it as? ReferenceInstruction)?.reference as? MethodReference
                }.orEmpty()
            }
        }.mapTo(HashSet()) { "${it.definingClass}->${it.name}" }
        for ((patch, needed) in listOf(
            "Block contact list access" to listOf("Landroid/content/ContentResolver;->query"),
            "Block installed app scanning" to listOf("Landroid/content/pm/PackageManager;->queryIntentActivities"),
            // On WebView or any of its subclasses, so by name here.
            "In-app browser privacy guard" to listOf("->addJavascriptInterface", "->loadUrl"),
            "Camera and microphone indicator" to listOf(
                "Landroid/hardware/camera2/CameraManager;->openCamera",
                "Landroid/hardware/camera2/CameraDevice;->close",
                "Landroid/media/AudioRecord;->startRecording",
            ),
        )) {
            for (call in needed) {
                val found = if (call.startsWith("->")) calls.any { it.endsWith(call) } else call in calls
                assertTrue("$patch: Lite never calls $call", found)
            }
        }
    }

    @Test
    fun `Lite's feed getter returns the items field the feed filter reads`() {
        val list = classes[LITE_FEED_ITEM_LIST_CLASS]
        assertNotNull(LITE_FEED_ITEM_LIST_CLASS, list)
        assertTrue(list!!.fields.any { it.name == "items" && it.type == "Ljava/util/List;" })
        val getter = list.methods.single { it.name == "getItems" && it.parameterTypes.isEmpty() }
        val reads = getter.implementation!!.instructions.mapNotNull {
            (it as? ReferenceInstruction)?.reference as? FieldReference
        }
        assertEquals(listOf("items"), reads.map { it.name })
        assertEquals(LITE_FEED_ITEM_LIST_CLASS, reads.single().definingClass)
    }

    @Test
    fun `each list Lite builds for an opened profile or video is there once and makes a new FeedItemList`() {
        for (builder in LITE_OPENED_LIST_BUILDERS) {
            val owner = classes[builder.definingClass]
            assertNotNull(builder.definingClass, owner)
            val method = owner!!.methods.single { builder.matches(it) }
            val created = method.implementation!!.instructions.count {
                it.opcode == Opcode.NEW_INSTANCE &&
                    ((it as ReferenceInstruction).reference as? TypeReference)?.type == LITE_FEED_ITEM_LIST_CLASS
            }
            assertEquals("${builder.definingClass}${builder.name}", 1, created)
            assertTrue(method.implementation!!.instructions.any { it.opcode == Opcode.RETURN_OBJECT })
        }
    }

    @Test
    fun `Lite's items carry the ad markers the ad filter reads`() {
        val aweme = classes.getValue("Lcom/ss/android/ugc/aweme/feed/model/Aweme;")
        for ((name, returnType) in listOf(
            "isAd" to "Z",
            "isSoftAd" to "Z",
            "isWithPromotionalMusic" to "Z",
            "getAwemeRawAd" to "Lcom/ss/android/ugc/aweme/feed/model/AwemeRawAd;",
        )) assertTrue("Lite's Aweme has no $name()$returnType", aweme.methods.any {
            it.name == name && it.parameterTypes.isEmpty() && it.returnType == returnType
        })
    }

    @Test
    fun `Lite skips the target build guard, whose TikTok anchors it never has`() {
        assertEquals(TARGET_BUILD_ANCHORS, missingTargetAnchors({ classes[it] }))
        assertEquals(emptyList<String>(), targetBuildAnchors(AppCompatibilities.TIKTOK_LITE_PACKAGE))
        assertEquals(TARGET_BUILD_ANCHORS, targetBuildAnchors(AppCompatibilities.TIKTOK_PACKAGE))
    }
}
