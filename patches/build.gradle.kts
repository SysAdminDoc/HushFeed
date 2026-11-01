import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyFactory
import java.security.MessageDigest
import java.security.Signature
import java.security.spec.EdECPrivateKeySpec
import java.security.spec.NamedParameterSpec
import java.security.spec.X509EncodedKeySpec
import java.util.Base64
import java.util.Properties
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import java.util.jar.Manifest
import java.util.zip.CRC32
import java.util.zip.ZipEntry
import java.util.zip.ZipFile
import java.util.zip.ZipOutputStream
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter

/**
 * The moment the bundle says it was built.
 *
 * Two builds of one commit used to differ in exactly one field, the manifest's Timestamp, so a
 * third party could rebuild the bundle and never match the published hash: a checksum then
 * attests to one file rather than to the source it came from. This pins the field to
 * SOURCE_DATE_EPOCH when the environment sets one, otherwise to the commit being built,
 * otherwise to zero. Anything read from the clock would put the difference straight back.
 *
 * This is a reproducibility field, not a source identity. Release eligibility comes from the
 * source snapshots captured before task execution and after the bundle has been produced.
 */
val sourceDateEpoch: Long = run {
    providers.environmentVariable("SOURCE_DATE_EPOCH").orNull?.trim()?.toLongOrNull()?.let {
        return@run it
    }
    try {
        val uncommitted = providers.exec {
            commandLine("git", "status", "--porcelain")
            workingDir = rootProject.projectDir
            isIgnoreExitValue = true
        }.standardOutput.asText.orNull
        if (!uncommitted.isNullOrBlank()) return@run 0L
        providers.exec {
            commandLine("git", "log", "-1", "--format=%ct")
            workingDir = rootProject.projectDir
            isIgnoreExitValue = true
        }.standardOutput.asText.orNull?.trim()?.toLongOrNull() ?: 0L
    } catch (_: Exception) {
        // No git, or no repository. Zero is stable, which is the only property that matters.
        0L
    }
}

/** Build-time source facts. Failure to read Git or an input always makes the build ineligible. */
object BundleSource {
    data class Snapshot(val commit: String, val clean: Boolean, val fingerprint: String,
                        val rawFingerprint: String)

    fun git(root: File, vararg arguments: String, input: String? = null): String {
        val process = ProcessBuilder(listOf("git", "-C", root.absolutePath) + arguments)
        // A pre-push hook exports GIT_DIR. -C alone does not select the requested repository.
        process.environment().keys.removeIf { it.startsWith("GIT_") }
        process.redirectError(ProcessBuilder.Redirect.DISCARD)
        val child = process.start()
        val output = ByteArrayOutputStream()
        val ioFailure = AtomicReference<Throwable>()
        val reader = Thread {
            try { child.inputStream.use { it.copyTo(output) } }
            catch (failure: Throwable) { ioFailure.set(failure) }
        }
        reader.isDaemon = true
        reader.start()
        val writer = input?.let { text ->
            Thread {
                try { child.outputStream.use { it.write(text.toByteArray(Charsets.UTF_8)) } }
                catch (failure: Throwable) { ioFailure.set(failure) }
            }.apply { isDaemon = true; start() }
        }
        if (writer == null) child.outputStream.close()
        if (!child.waitFor(30, TimeUnit.SECONDS)) {
            child.destroyForcibly()
            throw IllegalStateException("Git source inspection timed out")
        }
        reader.join(5_000)
        writer?.join(5_000)
        check(!reader.isAlive && writer?.isAlive != true && ioFailure.get() == null && child.exitValue() == 0) {
            "Git source inspection failed"
        }
        return output.toString(Charsets.UTF_8)
    }

    fun capture(root: File, sourceDirectories: List<String> = listOf("src")): Snapshot {
        var commit = "unknown"
        val objectIdPattern = Regex("[0-9a-f]{40}")
        return try {
            check(File(git(root, "rev-parse", "--show-toplevel").trim()).canonicalFile == root.canonicalFile) {
                "The source root is not the requested repository"
            }
            commit = git(root, "rev-parse", "HEAD").trim()
            check(commit.matches(objectIdPattern)) { "No source commit" }
            val cleanAtStart = git(root, "status", "--porcelain", "--untracked-files=all").isBlank()
            val tracked = git(root, "ls-files", "-v", "-z").split('\u0000').filter { it.isNotEmpty() }
            // Assume-unchanged and skip-worktree entries can hide edits from git status.
            val visible = tracked.all { it.startsWith("H ") }
            check(sourceDirectories.isNotEmpty() && sourceDirectories.all {
                it.isNotBlank() && root.resolve(it).canonicalFile.toPath().startsWith(root.canonicalFile.toPath())
            }) { "The configured source directories are unavailable or outside the checkout" }
            // Git status omits these files, but the Java/Kotlin and Android source sets still read them.
            val ignoredSources = git(root, "ls-files", "--others", "--ignored", "--exclude-standard", "-z",
                "--", *sourceDirectories.toTypedArray()).split('\u0000').filter { it.isNotEmpty() }
            val names = (git(root, "ls-files", "--cached", "--others", "--exclude-standard", "-z")
                .split('\u0000').filter { it.isNotEmpty() } + ignoredSources).distinct().sorted()
            check(names.isNotEmpty()) { "No readable source inputs" }
            val rawDigest = MessageDigest.getInstance("SHA-256")
            rawDigest.update(commit.toByteArray(Charsets.UTF_8))
            for (name in names) {
                val input = root.resolve(name)
                check(input.isFile && input.canonicalFile.toPath().startsWith(root.canonicalFile.toPath())) {
                    "A source input is missing or outside the checkout"
                }
                val contentDigest = MessageDigest.getInstance("SHA-256")
                input.inputStream().use { stream ->
                    val buffer = ByteArray(16_384)
                    while (true) {
                        val read = stream.read(buffer)
                        if (read < 0) break
                        contentDigest.update(buffer, 0, read)
                    }
                }
                rawDigest.update(0.toByte())
                rawDigest.update(name.toByteArray(Charsets.UTF_8))
                rawDigest.update(0.toByte())
                rawDigest.update(contentDigest.digest())
            }
            // Manifest identity uses Git's canonical content, including the committed EOL policy.
            // Keep the raw digest private so any byte change during this build still rejects it.
            val paths = names.joinToString("\n", postfix = "\n") { name ->
                "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"")
                    .replace("\n", "\\n").replace("\r", "\\r").replace("\t", "\\t") + "\""
            }
            val objects = git(root, "hash-object", "--stdin-paths", input = paths)
                .lineSequence().filter { it.isNotEmpty() }.toList()
            check(objects.size == names.size && objects.all { it.matches(objectIdPattern) }) {
                "Git could not normalize every source input"
            }
            val digest = MessageDigest.getInstance("SHA-256")
            digest.update(commit.toByteArray(Charsets.UTF_8))
            for ((name, objectId) in names.zip(objects)) {
                digest.update(0.toByte())
                digest.update(name.toByteArray(Charsets.UTF_8))
                digest.update(0.toByte())
                digest.update(objectId.toByteArray(Charsets.UTF_8))
            }
            val clean = cleanAtStart && visible && ignoredSources.isEmpty()
                && git(root, "rev-parse", "HEAD").trim() == commit
                && git(root, "status", "--porcelain", "--untracked-files=all").isBlank()
            Snapshot(commit, clean, digest.digest().joinToString("") { "%02X".format(it) },
                rawDigest.digest().joinToString("") { "%02X".format(it) })
        } catch (_: Exception) {
            Snapshot(commit, false, "unavailable", "unavailable")
        }
    }

    fun manifestFacts(start: Snapshot, end: Snapshot): Map<String, String> {
        val clean = start.clean && end.clean && start.commit == end.commit
            && start.commit.matches(Regex("[0-9a-f]{40}"))
            && start.fingerprint.matches(Regex("[0-9A-Fa-f]{64}"))
            && start.fingerprint == end.fingerprint
            && start.rawFingerprint.matches(Regex("[0-9A-Fa-f]{64}"))
            && start.rawFingerprint == end.rawFingerprint
        return mapOf(
            "Hushfeed-Source-Commit" to start.commit,
            "Hushfeed-Source-Clean" to clean.toString(),
            "Hushfeed-Source-Start" to start.fingerprint,
            "Hushfeed-Source-End" to end.fingerprint
        )
    }
}

abstract class CaptureBundleSource : DefaultTask() {
    @get:Internal abstract val sourceRoot: DirectoryProperty
    @get:Internal abstract val sourceDirectories: ListProperty<String>
    @get:OutputFile abstract val snapshotFile: RegularFileProperty

    init { outputs.upToDateWhen { false } }

    @TaskAction fun capture() {
        val source = BundleSource.capture(sourceRoot.get().asFile, sourceDirectories.get())
        val output = snapshotFile.get().asFile
        output.parentFile.mkdirs()
        val facts = Properties()
        facts.setProperty("commit", source.commit)
        facts.setProperty("clean", source.clean.toString())
        facts.setProperty("fingerprint", source.fingerprint)
        facts.setProperty("rawFingerprint", source.rawFingerprint)
        output.outputStream().use { facts.store(it, "Build source before compilation") }
        if (!source.clean) logger.lifecycle("Bundle source is dirty or unreadable; a new release receipt will be refused.")
    }
}

/**
 * MorpheSig1, the bundle signature morphe-patcher 2.1 checks and Morphe Manager 2.3 pins per
 * source: Ed25519 over "MorpheSig1" and the SHA-256 of the archive with an empty comment, stored
 * in the zip comment as "MorpheSig1 <public key> <signature>" (PatchBundleSignature.sign writes
 * the same bytes). MORPHE_SIGNING_KEY is the Base64 raw private key; scripts/release/release.ps1
 * loads it from HUSHFEED_BUNDLE_SIGNING_KEY. Without it the bundle stays unsigned, which a release
 * run refuses. Ed25519 is deterministic, so the same bundle always gets the same signature.
 */
fun signBundle(bundle: File, privateKey: String?, publicKey: String) {
    val bytes = bundle.readBytes()
    fun length(at: Int) = (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)
    // The end of central directory record is the last one whose comment reaches the end.
    val end = (bytes.size - 22 downTo maxOf(0, bytes.size - 22 - 0xFFFF)).firstOrNull {
        bytes[it] == 0x50.toByte() && bytes[it + 1] == 0x4b.toByte() && bytes[it + 2] == 5.toByte() &&
            bytes[it + 3] == 6.toByte() && length(it + 20) == bytes.size - it - 22
    } ?: throw GradleException("$bundle is not a zip archive")
    val unsigned = bytes.copyOf(end + 22).also { it[end + 20] = 0; it[end + 21] = 0 }
    if (privateKey.isNullOrBlank()) {
        if (unsigned.size != bytes.size) bundle.writeBytes(unsigned)
        return
    }
    val message = "MorpheSig1".toByteArray(Charsets.US_ASCII) + MessageDigest.getInstance("SHA-256").digest(unsigned)
    val keys = KeyFactory.getInstance("Ed25519")
    val signature = Signature.getInstance("Ed25519").run {
        initSign(keys.generatePrivate(EdECPrivateKeySpec(NamedParameterSpec.ED25519, Base64.getDecoder().decode(privateKey.trim()))))
        update(message)
        sign()
    }
    // A raw Ed25519 public key behind its fixed X.509 prefix.
    val x509 = byteArrayOf(0x30, 0x2a, 0x30, 0x05, 0x06, 0x03, 0x2b, 0x65, 0x70, 0x03, 0x21, 0x00) +
        Base64.getDecoder().decode(publicKey)
    val matches = Signature.getInstance("Ed25519").run {
        initVerify(keys.generatePublic(X509EncodedKeySpec(x509)))
        update(message)
        verify(signature)
    }
    if (!matches) throw GradleException("MORPHE_SIGNING_KEY is not the key morphe-signing.pub publishes")
    val comment = "MorpheSig1 $publicKey ${Base64.getEncoder().encodeToString(signature)}".toByteArray(Charsets.US_ASCII)
    unsigned[end + 20] = (comment.size and 0xFF).toByte()
    unsigned[end + 21] = (comment.size shr 8).toByte()
    bundle.writeBytes(unsigned + comment)
}

/**
 * Rewrites the bundle with the timestamp pinned, leaving everything else as it was.
 *
 * <p>Every entry is written again rather than copied compressed, because the zip API offers no
 * way to move compressed bytes across without decoding them. That makes the output differ from
 * what the plugin first wrote, which does not matter: what matters is that two runs of this
 * produce the same bytes, and they do, because nothing here reads a clock.
 */
fun pinBundleTimestamp(bundle: File, epochSeconds: Long, sourceFacts: Map<String, String>) {
    val stampMillis = epochSeconds * 1000L
    val names = mutableListOf<String>()
    val contents = mutableMapOf<String, ByteArray>()
    val times = mutableMapOf<String, Long>()
    val methods = mutableMapOf<String, Int>()

    ZipFile(bundle).use { zip ->
        for (entry in zip.entries()) {
            val bytes = zip.getInputStream(entry).use { it.readBytes() }
            names += entry.name
            contents[entry.name] = bytes
            times[entry.name] = entry.time
            methods[entry.name] = entry.method
        }
    }

    val manifestName = "META-INF/MANIFEST.MF"
    val manifestBytes = contents[manifestName]
        ?: throw GradleException("The bundle has no $manifestName: $bundle")
    val manifest = Manifest(manifestBytes.inputStream())
    if (manifest.mainAttributes.getValue("Timestamp") == null) {
        throw GradleException("The bundle manifest has no Timestamp line to pin: $bundle")
    }
    manifest.mainAttributes.putValue("Timestamp", stampMillis.toString())
    for ((name, value) in sourceFacts) manifest.mainAttributes.putValue(name, value)
    contents[manifestName] = ByteArrayOutputStream().also { manifest.write(it) }.toByteArray()

    val rebuilt = ByteArrayOutputStream()
    ZipOutputStream(rebuilt).use { out ->
        for (name in names) {
            val bytes = contents.getValue(name)
            val entry = ZipEntry(name)
            entry.time = times.getValue(name)
            entry.method = methods.getValue(name)
            if (entry.method == ZipEntry.STORED) {
                entry.size = bytes.size.toLong()
                entry.crc = CRC32().apply { update(bytes) }.value
            }
            out.putNextEntry(entry)
            out.write(bytes)
            out.closeEntry()
        }
    }
    bundle.writeBytes(rebuilt.toByteArray())
}

group = "app.morphe"

patches {
    about {
        name = "Hushfeed"
        description = "Hushfeed patches for TikTok 47.6.3, built for Morphe. Fewer accidental taps, less noise, more control over the feed, inbox, comments and downloads."
        source = "https://github.com/SysAdminDoc/hushfeed"
        author = "SysAdminDoc"
        contact = "https://github.com/SysAdminDoc/hushfeed/issues"
        website = "https://github.com/SysAdminDoc/hushfeed"
        license = "GNU General Public License v3.0, with additional GPL section 7 requirements"
    }
}

// Morphe patcher 1.12.0 asks for Bouncy Castle 1.77 and the Android build tools it brings ask
// for 1.79, so this module's graph resolved at 1.79: 1.77 is inside all seven advisories
// (CVE-2025-8916 1.44 to 1.78, CVE-2026-5588 1.67 to 1.83 apart from 1.80.2 and 1.81.1,
// CVE-2025-14813, CVE-2026-0636 fixed in 1.84, CVE-2026-8763, CVE-2026-13506 fixed in 1.85,
// CVE-2026-97873 fixed in 1.86) and 1.79 is inside all but CVE-2025-8916. None of it reaches the
// payload, and the APK a user gets is
// signed by their own Manager with its own patcher, so this is the build and signing classpath
// here rather than anything shipped. The repository's rule is that a known-affected component
// does not stay in a reproducible graph either way. Every request is rewritten to the reviewed
// release the catalog pins, the same treatment :extensions:tiktok gives its test graph.
val safeBouncyCastleVersion = libs.versions.bouncycastle.get()
// What the patcher and the Android build tools are known to ask for. A request for anything
// else is a version nobody has read the advisories for, and the gate below stops the build on
// it rather than rewriting it away in silence.
val reviewedBouncyCastleRequests = setOf("1.77", "1.79", safeBouncyCastleVersion)
// Guarded by hand rather than with a synchronized collection wrapper: in a Kotlin build script
// `java` is the Java extension, so the java.util package cannot be named here.
val requestedBouncyCastleVersions = sortedSetOf<String>()

configurations.configureEach {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.bouncycastle") {
            // Recorded before the rewrite, and recorded as a name rather than dropped when the
            // request carries no version of its own: a request arriving through a platform or
            // a constraint would otherwise be rewritten and never counted.
            // Blank as well as null: a declaration with no version reports "" rather than null,
            // and a plain ?: leaves the failure message with an empty name in it.
            val asked = requested.version?.takeIf { it.isNotBlank() } ?: "a request with no version of its own"
            synchronized(requestedBouncyCastleVersions) { requestedBouncyCastleVersions.add(asked) }
            useVersion(safeBouncyCastleVersion)
            because("The build classpath must use the reviewed Bouncy Castle release.")
        }
    }
}

val verifyBouncyCastleBuildGraph = tasks.register("verifyBouncyCastleBuildGraph") {
    group = "verification"
    description = "Checks this module's resolvable graphs for unreviewed Bouncy Castle requests."

    doLast {
        // Resolving is what runs the rewrite above, so the requests are collected here rather
        // than read out of a set nothing has filled yet. Checking the resolved version instead
        // would prove nothing: the rewrite guarantees that answer, so its failure branch could
        // never run. The request underneath it is the live fact.
        // Named rather than "every resolvable configuration": the ones the patcher's graph
        // actually arrives on. Resolution failures are not caught. A swallowed one is how this
        // gate first passed while reporting a single module, because dependency verification
        // was refusing the very artifacts the force had just introduced.
        val inspected = listOf("patcherProvidedClasspath", "compileClasspath", "runtimeClasspath",
            "testCompileClasspath", "testRuntimeClasspath")
        val seen = sortedMapOf<String, String>()
        val missing = mutableListOf<String>()
        for (name in inspected) {
            val configuration = configurations.findByName(name)
            if (configuration == null || !configuration.isCanBeResolved) {
                missing += name
                continue
            }
            val modules = configuration.incoming.resolutionResult.allComponents
                .mapNotNull { it.moduleVersion?.takeIf { module -> module.group == "org.bouncycastle" } }
            for (module in modules) seen[module.name] = module.version
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "These configurations were not there to inspect: " + missing.joinToString(", ") +
                    ". The plugin renamed them, so this gate is looking at the wrong graph."
            )
        }
        // Named, not merely non-empty. A module that fails to resolve is absent from
        // allComponents rather than raising, so a force at a version that does not publish a
        // module leaves this reporting the ones that did resolve and passing. That is exactly
        // what 1.85.2 did: it is a bcprov-only release, and bcpkix and bcutil read FAILED in
        // the dependency report while this gate said the graph was clean.
        val expected = setOf("bcpkix-jdk18on", "bcprov-jdk18on", "bcutil-jdk18on")
        val absent = expected - seen.keys
        if (absent.isNotEmpty()) {
            throw GradleException(
                "Bouncy Castle " + absent.sorted().joinToString(", ") + " is not on the build " +
                    "graph. Either the force names a version that does not publish it, or the " +
                    "patcher stopped bringing it and this gate is now blind."
            )
        }
        val wrong = seen.filterValues { it != safeBouncyCastleVersion }
        if (wrong.isNotEmpty()) {
            throw GradleException(
                "Bouncy Castle resolved at " + wrong.entries.joinToString(", ") { "${it.key}:${it.value}" } +
                    " rather than the reviewed $safeBouncyCastleVersion."
            )
        }
        logger.lifecycle(
            "Bouncy Castle on the build graph: " + seen.entries.joinToString(", ") { "${it.key}:${it.value}" }
        )

        val requested = synchronized(requestedBouncyCastleVersions) { requestedBouncyCastleVersions.toSet() }
        val unreviewed = requested - reviewedBouncyCastleRequests
        if (unreviewed.isNotEmpty()) {
            throw GradleException(
                "The build graph now asks for Bouncy Castle " + unreviewed.sorted().joinToString(", ") +
                    ", which nobody has reviewed. It is being rewritten to $safeBouncyCastleVersion. " +
                    "Check the advisory for the requested release, then add it to " +
                    "reviewedBouncyCastleRequests or move the pin."
            )
        }
    }
}

// By type rather than by the one name, so a second test task cannot start on a graph nothing
// has looked at.
tasks.withType<Test>().configureEach {
    dependsOn(verifyBouncyCastleBuildGraph)
}

dependencies {
    compileOnly(libs.morphe.patcher)

    // Used by JsonGenerator.
    implementation(libs.gson)

    // Required due to smali, or build fails. Can be removed once smali is bumped.
    implementation(libs.guava)

    // Android API stubs defined here.
    compileOnly(project(":patches:stub"))

    // The register and instruction helpers under app.morphe.util are the only thing tested
    // here. They take dexlib2 methods, so the patcher has to be on the test runtime classpath
    // as well as the compile one.
    testImplementation("junit:junit:4.13.2")
    testImplementation(libs.morphe.patcher)
    // Reads the signing certificate of every retained fixture. The patcher already brings this
    // exact version at run time; this puts it on the test compile classpath as well.
    testImplementation("com.android.tools.build:apksig:9.1.1")
    // Runs the patcher over a fixture (ClassesCallingTest). The patcher brings this exact version
    // at run time; this puts it on the test compile classpath as well.
    testImplementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}

// The patch tests that read extension sources or the extension bundle. They stay in :patches:test,
// which a runtime Java edit reruns. Every other patch test runs in nativeTest, whose inputs leave
// the extensions out, so that edit reuses the long fixture fingerprint scans.
val extensionClasses = listOf(
    "app/morphe/patches/tiktok/interaction/videooverlays/StoryOverLiveBindingFixturesTest.class",
    "app/morphe/ExtensionBridgeLookupTest.class",
    "app/morphe/ExtensionHostsTest.class",
    "app/morphe/ObfuscatedIdentityTest.class",
    "app/morphe/RuntimeViewIdAnchorsTest.class",
    "app/morphe/gatecatalog/GateCatalogFixturesTest.class",
    "app/morphe/patches/tiktok/ExtensionReferencesResolveTest.class",
    "app/morphe/patches/tiktok/TikTokPatchAnchorsMatchFixturesTest.class",
    "app/morphe/patches/tiktok/feedfilter/LiveDrawRoomPageTest.class",
    "app/morphe/patches/tiktok/feedfilter/SearchAnswerProtocolsTest.class",
    "app/morphe/patches/tiktok/interaction/downloads/StickerSourceFixturesTest.class",
    "app/morphe/patches/tiktok/interaction/downloads/StoryHoldFixturesTest.class",
    "app/morphe/patches/tiktok/interaction/gesture/LikePressAnchorsTest.class",
    "app/morphe/patches/tiktok/interaction/seekbar/SoundSeekbarBridgesTest.class",
    "app/morphe/patches/tiktok/interaction/videooverlays/MusicDiscSpinAnchorsTest.class",
    "app/morphe/patches/tiktok/misc/diagnostics/BuildDetailsPatchTest.class",
    "app/morphe/patches/tiktok/misc/featuregatelab/FeatureGateLabFramesTest.class",
    "app/morphe/patches/tiktok/misc/inbox/InboxQueryOwnerFixturesTest.class",
    "app/morphe/patches/tiktok/misc/inbox/InboxRenderedModelsFixturesTest.class",
    "app/morphe/patches/tiktok/misc/onboarding/FirstLaunchSkipListTest.class",
    "app/morphe/patches/tiktok/misc/translation/ReceivedDmTranslationIconTest.class",
    "app/morphe/patches/tiktok/privacy/StoreIdentityExtensionTest.class",
    "app/morphe/patches/tiktok/profile/LocalProfileAppearanceAnchorsTest.class",
)
// The plugin copies extensions/*.mpe into the main resources. nativeTest reads this copy instead,
// so rebuilding the extension doesn't change its classpath.
val nativeTestResources = tasks.register<Sync>("nativeTestResources") {
    from(tasks.processResources)
    exclude("extensions/**")
    into(layout.buildDirectory.dir("resources/nativeTest"))
}
val nativeTest = tasks.register<Test>("nativeTest") {
    group = "verification"
    description = "Runs the patch tests that read neither extension sources nor the extension bundle."
    testClassesDirs = sourceSets.test.get().output.classesDirs
    val mainResources = sourceSets.main.get().output.resourcesDir
    classpath = sourceSets.test.get().runtimeClasspath.filter { it != mainResources } + files(nativeTestResources)
    exclude(extensionClasses)
    inputs.property("suiteSelection", provider {
        (tasks.test.get().filter as DefaultTestFilter).commandLineIncludePatterns
    })
    failOnNoDiscoveredTests.set(provider {
        (tasks.test.get().filter as DefaultTestFilter).commandLineIncludePatterns.isEmpty()
    })
    doFirst {
        val selected = (tasks.test.get().filter as DefaultTestFilter).commandLineIncludePatterns
        if (selected.isNotEmpty()) {
            setTestNameIncludePatterns(selected.toList())
            filter.isFailOnNoMatchingTests = false
        }
    }
}
// The fixture tests skip when this is unset and read the folder when it is set. Blank counts as
// unset, as Fixtures.kt reads it; File("") would be the whole project.
val fixtureDirectory = providers.environmentVariable("HUSHFEED_FIXTURE_DIR")
// Gradle's file-hash cache can reuse a digest after an external file is replaced with the same
// size and timestamp, so the fixture bytes are read independently. Once per build: both
// partitions that open the APKs declare the same digests.
val fixtureDigests by lazy {
    fixtureDirectory.map { directory ->
        val fixtures = if (directory.isBlank()) emptyList() else File(directory).listFiles()
            ?.filter { it.isFile && it.extension in setOf("apk", "apkm") }.orEmpty()
        fixtures.associate { fixture ->
            val digest = MessageDigest.getInstance("SHA-256")
            fixture.inputStream().use { stream ->
                val buffer = ByteArray(65_536)
                while (true) {
                    val read = stream.read(buffer)
                    if (read < 0) break
                    digest.update(buffer, 0, read)
                }
            }
            fixture.name to digest.digest().joinToString("") { "%02x".format(it) }
        }
    }.getOrElse(emptyMap())
}
// Both partitions that open TikTok APKs.
tasks.withType<Test>().matching { it.name == "test" || it.name == "nativeTest" }.configureEach {
    // GateCatalogFixturesTest runs the catalog generator on each declared build, and the
    // generator holds a whole APK's dex (some 430 MB on 47.1.x, feature modules included)
    // while it walks it; the fixture scans in nativeTest hold the same dex. Gradle's default
    // test heap is 512 MB, where that ran out of memory.
    maxHeapSize = "4g"
    // nativeTest is some 600 single-threaded scans over the one declared build's dex. Two forks
    // each decode it once (the [fixtures] line in the report counts decodes) and split the
    // classes, which takes the two workers the build governor allows. 2 GB holds one 47.1.4 dex
    // and a scan, so two forks take no more heap than the single 4 GB JVM did.
    if (name == "nativeTest") {
        maxParallelForks = 2
        maxHeapSize = "2g"
    }
    // What the folder holds is the input, not its name: a run whose APK was swapped, re-signed
    // or deleted under the same path has to run again, not come back up to date or out of the
    // build cache with the last folder's verdict. Relative, so where the folder sits on this
    // machine does not count, and an APK moved into or out of a subfolder does: the tests read
    // only the folder's top level, and name only would call that move no change.
    inputs.files(fixtureDirectory.map { if (it.isBlank()) emptyList() else listOf(File(it)) }.orElse(emptyList()))
        .withPropertyName("fixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    inputs.property("fixtureBytes", provider { fixtureDigests })
}

tasks {
    test {
        dependsOn(nativeTest)
        include(extensionClasses)
        // A --tests selection may belong only to the other partition.
        filter.isFailOnNoMatchingTests = false
        failOnNoDiscoveredTests.set(false)
    }
    // The bundle a release publishes lives in build/release, not build/libs. The plugin's
    // buildAndroid merges the DEX payload into the jar task's own output in place, so any later
    // task that reruns jar (test does) put the plain jar back over the finished bundle under the
    // same name: v0.43.0 shipped with no classes.dex that way, and on 2026-09-21 the pre-push
    // test run did it again between the build and the index push. Nothing but buildAndroid
    // writes build/release. scripts/common.ps1 names the same path for every release script.
    val releaseBundleName = "patches-${project.version}.mpp"
    val verifyBundle = register<JavaExec>("verifyBundle") {
        group = "verification"
        description = "Check the Android bundle and its published patch list without rebuilding it"
        dependsOn(classes)
        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("app.morphe.util.BundleVerifier")
        args(
            providers.gradleProperty("patchBundle").getOrElse(
                layout.buildDirectory.file("release/$releaseBundleName").get().asFile.absolutePath
            ),
            rootProject.file("patches-list.json").absolutePath,
            project.version.toString(),
            layout.buildDirectory.file("release/bundle.sha256").get().asFile.absolutePath
        )
    }
    // Every configured module uses its standard src tree; generated build directories stay excluded.
    val bundleSourceDirectories = rootProject.allprojects.map {
        it.projectDir.resolve("src").relativeTo(rootProject.projectDir).invariantSeparatorsPath
    }.distinct().sorted()
    val captureSource = register<CaptureBundleSource>("captureBundleSource") {
        sourceRoot.set(rootProject.layout.projectDirectory)
        sourceDirectories.set(bundleSourceDirectories)
        snapshotFile.set(layout.buildDirectory.file("source-provenance/start.properties"))
    }
    // A doFirst on buildAndroid is too late: its compiler dependencies have already run.
    // Ordering does not add these tasks to the graph. Requested clean tasks precede the snapshot.
    rootProject.allprojects {
        val cleaning = tasks.matching { it is Delete || it.name == "clean" }
        captureSource.configure { mustRunAfter(cleaning) }
        tasks.configureEach {
            if (path == ":patches:captureBundleSource") return@configureEach
            if (this !is Delete && name != "clean") mustRunAfter(captureSource)
        }
    }
    named("buildAndroid") {
        dependsOn(captureSource)
        // Resolved at configuration time. Reaching for project inside doLast is what the
        // configuration cache refuses, and Gradle 10 turns that refusal into an error.
        val bundleFile = layout.buildDirectory.file("libs/$releaseBundleName")
        val releaseDirectory = layout.buildDirectory.dir("release")
        val pinnedEpoch = sourceDateEpoch
        val signingKey = providers.environmentVariable("MORPHE_SIGNING_KEY")
        val publicKeyFile = rootProject.layout.projectDirectory.file("morphe-signing.pub")
        // Whether the bundle is signed changes the output, but the key itself is never an input.
        inputs.property("bundleSigned", signingKey.map { it.isNotBlank() }.orElse(false))
        val sourceRoot = rootProject.layout.projectDirectory.asFile
        val sourceDirectories = bundleSourceDirectories
        val startFile = layout.buildDirectory.file("source-provenance/start.properties")
        doLast {
            val saved = Properties().apply { startFile.get().asFile.inputStream().use { load(it) } }
            val start = BundleSource.Snapshot(saved.getProperty("commit", "unknown"),
                saved.getProperty("clean") == "true", saved.getProperty("fingerprint", "unavailable"),
                saved.getProperty("rawFingerprint", "unavailable"))
            val end = BundleSource.capture(sourceRoot, sourceDirectories)
            // Emptied first, so the directory never holds a bundle of another version or a
            // checksum of another build: the release scripts take the one file they find.
            val directory = releaseDirectory.get().asFile
            directory.mkdirs()
            directory.listFiles()?.filter { it.isFile }?.forEach { stale ->
                if (!stale.delete()) throw GradleException("Could not clear the old release file $stale")
            }
            val releaseBundle = directory.resolve(releaseBundleName)
            bundleFile.get().asFile.copyTo(releaseBundle)
            // Before the checksum, so what is recorded is what a rebuild will produce.
            pinBundleTimestamp(releaseBundle, pinnedEpoch, BundleSource.manifestFacts(start, end))
            // Signed before the checksum too: the bundle a release publishes is the signed file.
            signBundle(releaseBundle, signingKey.orNull, publicKeyFile.asFile.readText().trim())
            // Record only at the producer boundary. Standalone verification must not
            // bless a modified bundle by generating its own expected checksum.
            val digest = MessageDigest.getInstance("SHA-256")
                .digest(releaseBundle.readBytes())
                .joinToString("") { "%02x".format(it) }
            directory.resolve("bundle.sha256").writeText(digest)
        }
        finalizedBy(verifyBundle)
    }
    register<JavaExec>("generatePatchesList") {
        description = "Build patch with patch list"

        // jar, not build: build runs check, which runs test. The generator reads
        // build/libs/patches-<version>.mpp, which jar produces.
        dependsOn(jar)

        classpath = sourceSets["main"].runtimeClasspath
        mainClass.set("app.morphe.util.PatchListGeneratorKt")
        args(project.version.toString())
    }
    // The patch list has to be regenerated before anything publishes the bundle.
    publish {
        dependsOn("generatePatchesList")
    }
    // Rebuilds the Feature Gate Lab's offline catalogs from every declared TikTok build:
    // ./gradlew :patches:generateGateCatalog reads their APKs from HUSHFEED_FIXTURE_DIR, and
    // -Papk=<a.apk>;<b.apk> names them instead. The generator is a test-source tool because the
    // test classpath is the one that carries dexlib2 and apksig.
    register<JavaExec>("generateGateCatalog") {
        description = "Rebuild the Feature Gate Lab catalogs from the declared TikTok builds"
        dependsOn(testClasses)
        classpath = sourceSets["test"].runtimeClasspath
        mainClass.set("app.morphe.gatecatalog.GateCatalogGenerator")
        maxHeapSize = "8g"
        args(
            rootProject.file("extensions/tiktok/src/main/java/app/morphe/extension/tiktok/featuregatelab").absolutePath,
            file("src/test/resources/gate-catalog-curated.tsv").absolutePath,
            providers.gradleProperty("apk").getOrElse("")
        )
    }
}
