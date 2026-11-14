import java.io.ByteArrayOutputStream
import java.io.DataInputStream
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

// The release bundle's last two steps, run from buildAndroid's action. Inside an object rather than
// at the top of the script, because a call to a script function takes the script along with it,
// and the configuration cache can keep neither the script nor its project.
object BundleRelease {
    /**
     * MorpheSig1, the bundle signature morphe-patcher 2.1 checks and Morphe Manager 2.3 pins per
     * source: Ed25519 over "MorpheSig1" and the SHA-256 of the archive with an empty comment, stored
     * in the zip comment as "MorpheSig1 <public key> <signature>" (PatchBundleSignature.sign writes
     * the same bytes). HUSHFEED_BUNDLE_SIGNING_KEY is the Base64 raw private key. Without it the
     * bundle stays unsigned, which a release run refuses. Ed25519 is deterministic, so the same
     * bundle always gets the same signature.
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
        if (!matches) throw GradleException("HUSHFEED_BUNDLE_SIGNING_KEY is not the key morphe-signing.pub publishes")
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
}

group = "app.morphe"

patches {
    about {
        name = "Hushfeed"
        description = "Hushfeed patches for TikTok 47.7.3, built for Morphe. Fewer accidental taps, less noise, more control over the feed, inbox, comments and downloads."
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
val bouncyCastleRequestReason = rootProject.extra["bouncyCastleRequestReason"] as String

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
        val unresolved = sortedSetOf<String>()
        for (name in inspected) {
            val configuration = configurations.findByName(name)
            if (configuration == null || !configuration.isCanBeResolved) {
                missing += name
                continue
            }
            // An edge that resolves to nothing has no component below, so a published request
            // for a module the reviewed release doesn't publish (bcprov-jdk15on stopped at 1.70)
            // would pass here and only fail later on the classpath.
            for (dependency in configuration.incoming.resolutionResult.allDependencies) {
                if (dependency !is org.gradle.api.artifacts.result.UnresolvedDependencyResult) continue
                val attempted = dependency.attempted as? org.gradle.api.artifacts.component.ModuleComponentSelector ?: continue
                if (attempted.group == "org.bouncycastle") unresolved += "${attempted.module}:${attempted.version} in $name"
            }
            for (component in configuration.incoming.resolutionResult.allComponents) {
                val module = component.moduleVersion?.takeIf { it.group == "org.bouncycastle" } ?: continue
                seen[module.name] = module.version
                // A published component's request reaches eachDependency already rewritten by the
                // root build's metadata rule, which leaves the original as the selection reason.
                for (reason in component.selectionReason.descriptions) {
                    if (!reason.description.startsWith(bouncyCastleRequestReason)) continue
                    val asked = reason.description.removePrefix(bouncyCastleRequestReason)
                        .ifBlank { "a request with no version of its own" }
                    synchronized(requestedBouncyCastleVersions) { requestedBouncyCastleVersions.add(asked) }
                }
            }
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "These configurations were not there to inspect: " + missing.joinToString(", ") +
                    ". The plugin renamed them, so this gate is looking at the wrong graph."
            )
        }
        if (unresolved.isNotEmpty()) {
            throw GradleException("Bouncy Castle did not resolve: " + unresolved.joinToString(", "))
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

/**
 * Splits nativeTest's classes by area. Each area gets its test classes (seeds), the test classes
 * (lib.jar) and patch classes (main.jar) those reach, and a copy of the patch sources they come
 * from or name (src). Reach is read off the class files: any app/morphe name in a constant, in
 * internal, descriptor or dotted form, so a Class.forName string counts too. A class a test reaches
 * some other way fails that test with NoClassDefFoundError rather than passing it.
 *
 * Inside an object for the reason BundleRelease is: a script function would take the script along.
 */
object NativeTestAreas {
    const val SHARED = "Shared"
    private val reference = Regex("""app[/.]morphe[/.][\w/.$]+""")
    // A patch source or folder named by path, from src/main/kotlin, the project or the repository.
    private val sourcePath = Regex("""^(?:patches/)?(?:src/main/kotlin/)?(app/morphe/[\w/]+(?:\.kt)?)$""")
    // A source file named alone, or at the end of a string template's recipe (\x01 is its argument).
    private val sourceName = Regex("""(?:^|[/\x01])([\w$-]+\.kt)$""")
    private val repoFile = Regex("""(?:^|/)src/(?:main/resources|test)/|\.gradle\.kts$|patches-list\.json|^\.\./""")
    private val walks = listOf("walk", "walkTopDown", "walkBottomUp").map { "kotlin/io/FilesKt.$it" } +
        listOf("listFiles", "list").map { "java/io/File.$it" } +
        listOf("walk", "list", "newDirectoryStream", "find", "walkFileTree").map { "java/nio/file/Files.$it" } +
        listOf("listDirectoryEntries", "walk", "useDirectoryEntries", "forEachDirectoryEntry").map { "kotlin/io/path/PathsKt.$it" }
    private val scans = setOf("java/lang/ClassLoader.getResources", "java/util/ServiceLoader.load")

    /** One outer class with its nested ones, from the main or the test classes. */
    private class Outer(val main: Boolean, val name: String) {
        val classes = sortedMapOf<String, File>()
        val strings = HashSet<String>()
        val members = HashSet<String>()
        val sourceFiles = sortedSetOf<String>()
        val reaches = LinkedHashSet<Outer>()
    }

    /** The constant pool's strings and member references, and the SourceFile attribute. */
    private fun read(file: File, into: Outer) {
        val input = DataInputStream(file.readBytes().inputStream())
        input.skipBytes(8)
        val count = input.readUnsignedShort()
        val utf = arrayOfNulls<String>(count)
        val classNames = IntArray(count)
        val memberNames = IntArray(count)
        val memberRefs = ArrayList<Pair<Int, Int>>()
        var index = 1
        while (index < count) {
            when (val tag = input.readUnsignedByte()) {
                1 -> utf[index] = input.readUTF()
                3, 4, 17, 18 -> input.skipBytes(4)
                5, 6 -> { input.skipBytes(8); index++ }
                7 -> classNames[index] = input.readUnsignedShort()
                8, 16, 19, 20 -> input.skipBytes(2)
                9, 10, 11 -> memberRefs.add(input.readUnsignedShort() to input.readUnsignedShort())
                12 -> { memberNames[index] = input.readUnsignedShort(); input.skipBytes(2) }
                15 -> input.skipBytes(3)
                else -> throw GradleException("$file: constant pool tag $tag")
            }
            index++
        }
        memberRefs.mapTo(into.members) { (owner, member) -> "${utf[classNames[owner]]}.${utf[memberNames[member]]}" }
        utf.filterNotNullTo(into.strings)
        input.skipBytes(6)
        input.skipBytes(2 * input.readUnsignedShort())
        repeat(2) { // fields, then methods
            repeat(input.readUnsignedShort()) {
                input.skipBytes(6)
                repeat(input.readUnsignedShort()) { input.skipBytes(2); input.skipBytes(input.readInt()) }
            }
        }
        repeat(input.readUnsignedShort()) {
            val name = utf[input.readUnsignedShort()]
            val length = input.readInt()
            if (name == "SourceFile") utf[input.readUnsignedShort()]?.let { into.sourceFiles.add(it) } else input.skipBytes(length)
        }
    }

    fun split(mainDirs: Set<File>, testDirs: Set<File>, sourceRoot: File, areas: Map<String, List<String>>,
              excluded: Set<String>, output: File) {
        val outers = HashMap<String, MutableList<Outer>>()
        val known = HashSet<String>()
        val mainExtras = sortedMapOf<String, File>()
        val testExtras = sortedMapOf<String, File>()
        for ((dirs, main) in listOf(mainDirs to true, testDirs to false)) for (dir in dirs.filter { it.isDirectory }) {
            dir.walkTopDown().filter { it.isFile }.forEach { file ->
                val path = file.relativeTo(dir).invariantSeparatorsPath
                if (!path.endsWith(".class")) {
                    (if (main) mainExtras else testExtras)[path] = file
                    return@forEach
                }
                val name = path.removeSuffix(".class")
                val parts = outers.getOrPut(name.substringBefore('$')) { ArrayList(2) }
                val outer = parts.firstOrNull { it.main == main } ?: Outer(main, name.substringBefore('$')).also { parts.add(it) }
                outer.classes[name] = file
                read(file, outer)
                known.add(name)
            }
        }
        for (outer in outers.values.flatten()) for (text in outer.strings) {
            if ("morphe" !in text) continue
            for (match in reference.findAll(text)) {
                var candidate = match.value.replace('.', '/')
                while (candidate !in known && '/' in candidate) {
                    val last = candidate.substringAfterLast('/')
                    candidate = if ('$' in last) candidate.substringBeforeLast('$') else candidate.substringBeforeLast('/')
                }
                if (candidate in known) outers.getValue(candidate.substringBefore('$')).filterTo(outer.reaches) { it !== outer }
            }
        }
        fun closure(seeds: Collection<Outer>): Set<Outer> {
            val seen = LinkedHashSet(seeds)
            val todo = ArrayList(seeds)
            while (todo.isNotEmpty()) for (next in todo.removeAt(todo.lastIndex).reaches) if (seen.add(next)) todo.add(next)
            return seen
        }
        fun areaOf(name: String): String {
            val folder = name.substringBeforeLast('/')
            return areas.flatMap { (area, prefixes) -> prefixes.map { area to it } }
                .filter { (_, prefix) -> folder == prefix || folder.startsWith("$prefix/") }
                .maxByOrNull { it.second.length }?.first ?: SHARED
        }
        // Why a test can't run on its area's slice: it walks the source tree, scans the classpath
        // or reads a repository file that is not a patch source.
        fun needsEverything(test: Outer): String? {
            val parts = closure(listOf(test)).filter { !it.main }
            for (part in parts) {
                if (part.members.any { it in walks } && part.strings.any { "src/main" in it }) return "walks the sources in ${part.name}"
                if (part.members.any { it in scans }) return "scans the classpath in ${part.name}"
                part.strings.firstOrNull { ' ' !in it && repoFile.containsMatchIn(it) }?.let { return "reads $it in ${part.name}" }
            }
            // A source root named in one class and walked in another. No source gets named for it,
            // so on an area's slice the test would stay up to date while the sources it walks change.
            // A test that names a file under the root reads that file, which becomes its input, and
            // the walk it reaches is another one (Fixtures lists the fixture folder).
            val walker = parts.firstOrNull { part -> part.members.any { it in walks } } ?: return null
            if (parts.any { part -> part.strings.any { sourcePath.matchEntire(it) != null || sourceName.find(it) != null } }) return null
            for (part in parts) {
                part.strings.firstOrNull { "src/main" in it && ' ' !in it && sourcePath.matchEntire(it) == null }
                    ?.let { return "walks $it from ${part.name} in ${walker.name}" }
            }
            return null
        }

        val seeds = (areas.keys + SHARED).associateWith { sortedMapOf<String, Outer>() }
        val reasons = sortedMapOf<String, String>()
        for (outer in outers.values.flatten()) {
            if (outer.main || outer.name in excluded) continue
            var area = areaOf(outer.name)
            if (area != SHARED) needsEverything(outer)?.let { reasons[outer.name] = it; area = SHARED }
            seeds.getValue(area)[outer.name] = outer
        }
        val empty = seeds.filterValues { it.isEmpty() }.keys
        if (empty.isNotEmpty()) throw GradleException("nativeTest areas with no test classes: $empty")

        val sourceFiles = sourceRoot.walkTopDown().filter { it.isFile }.map { it.relativeTo(sourceRoot).invariantSeparatorsPath }.toSortedSet()
        val byFileName = sourceFiles.groupBy { it.substringAfterLast('/') }
        output.deleteRecursively()
        val summary = StringBuilder()
        for ((area, members) in seeds) {
            val folder = File(output, area)
            val reached = closure(members.values)
            val named = sortedSetOf<String>()
            for (part in reached) {
                if (part.main) {
                    for (file in part.sourceFiles) named.add(part.name.substringBeforeLast('/') + "/" + file)
                    continue
                }
                for (text in part.strings) {
                    val path = sourcePath.matchEntire(text)?.groupValues?.get(1)
                    if (path != null) {
                        if (path in sourceFiles) named.add(path) else sourceFiles.filterTo(named) { it.startsWith("$path/") }
                        continue
                    }
                    val fileName = sourceName.find(text)?.groupValues?.get(1) ?: continue
                    if (fileName !in part.sourceFiles) byFileName[fileName]?.let { named.addAll(it) }
                }
            }
            named.retainAll(sourceFiles)
            File(folder, "seeds").mkdirs()
            for (outer in members.values) for ((name, file) in outer.classes) file.copyTo(File(folder, "seeds/$name.class"))
            File(folder, "src").mkdirs()
            for (path in named) File(sourceRoot, path).copyTo(File(folder, "src/$path"))
            fun jar(name: String, main: Boolean, extras: Map<String, File>) {
                val entries = sortedMapOf<String, File>()
                entries.putAll(extras)
                for (part in reached) if (part.main == main && (main || part.name !in members)) {
                    for ((className, file) in part.classes) entries["$className.class"] = file
                }
                ZipOutputStream(File(folder, name).outputStream().buffered()).use { zip ->
                    for ((path, file) in entries) {
                        zip.putNextEntry(ZipEntry(path).apply { time = 0 })
                        file.inputStream().use { it.copyTo(zip) }
                        zip.closeEntry()
                    }
                }
            }
            jar("main.jar", true, mainExtras)
            jar("lib.jar", false, testExtras)
            summary.append("$area: ${members.size} test classes, ${reached.count { it.main }} patch classes, ${named.size} sources\n")
        }
        for ((name, reason) in reasons) summary.append("Shared, $name: $reason\n")
        File(output, "areas.txt").writeText(summary.toString())
    }
}

abstract class SplitNativeTests : DefaultTask() {
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val mainClasses: ConfigurableFileCollection
    @get:InputFiles @get:PathSensitive(PathSensitivity.RELATIVE) abstract val testClasses: ConfigurableFileCollection
    @get:InputDirectory @get:PathSensitive(PathSensitivity.RELATIVE) abstract val sources: DirectoryProperty
    // Area name to folder prefixes, space-separated.
    @get:Input abstract val areas: MapProperty<String, String>
    @get:Input abstract val excluded: ListProperty<String>
    @get:OutputDirectory abstract val outputDirectory: DirectoryProperty

    @TaskAction fun split() {
        NativeTestAreas.split(mainClasses.files, testClasses.files, sources.get().asFile,
            areas.get().mapValues { (_, prefixes) -> prefixes.split(' ') }, excluded.get().toSet(), outputDirectory.get().asFile)
    }
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
    "app/morphe/patches/tiktok/interaction/publishdate/LikedJumpHooksTest.class",
    "app/morphe/patches/tiktok/interaction/publishdate/SoundGridSortHooksTest.class",
    "app/morphe/patches/tiktok/interaction/seekbar/SoundSeekbarBridgesTest.class",
    "app/morphe/patches/tiktok/interaction/videooverlays/MusicDiscSpinAnchorsTest.class",
    "app/morphe/patches/tiktok/misc/diagnostics/BuildDetailsPatchTest.class",
    "app/morphe/patches/tiktok/misc/diagnostics/OwnClassesTest.class",
    "app/morphe/patches/tiktok/misc/featuregatelab/FeatureGateLabFramesTest.class",
    "app/morphe/patches/tiktok/misc/inbox/InboxQueryOwnerFixturesTest.class",
    "app/morphe/patches/tiktok/misc/inbox/InboxRenderedModelsFixturesTest.class",
    "app/morphe/patches/tiktok/misc/onboarding/FirstLaunchSkipListTest.class",
    // Reads AmoledTheme.java for the names it looks up, and decodes and encodes a whole TikTok
    // resource table, which needs this partition's 4 GB heap.
    "app/morphe/patches/tiktok/misc/theme/AmoledRestoreResourcesTest.class",
    "app/morphe/patches/tiktok/misc/theme/AmoledThemeSwitchHooksTest.class",
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
// nativeTest runs in areas, so an edit reruns only the areas whose tests can reach it. A prefix
// names a folder under app/morphe/patches/tiktok/ and takes its subfolders, the longest prefix
// winning, so bare "interaction" and "misc" take what the named folders leave. A test no prefix
// takes (app/morphe, util, gatecatalog, patches/shared) runs in Shared, and so does any test that
// walks the source tree or reads a repo file other than one patch source. The areas are sized by
// run time, not by meaning: tiktok/shared sits in Feed.
val nativeTestAreas = linkedMapOf(
    "Feed" to "feedfilter captchapopup promobanners privacy profile shared",
    "Playback" to "interaction/live interaction/blockauthor interaction/resume interaction/looping " +
        "interaction/backgroundplay interaction/pip interaction/soundrestore interaction/feedmute interaction/videofit",
    "Player" to "interaction interaction/seekbar interaction/videooverlays interaction/cleardisplay interaction/speed " +
        "interaction/gesture interaction/quickactions interaction/confirmation interaction/feedtext " +
        "interaction/feedtoolbar interaction/captions interaction/systemfont interaction/haptics",
    "Sharing" to "interaction/downloads interaction/sharesheet interaction/offlinevideos interaction/copyids " +
        "interaction/hdupload interaction/duetstitch interaction/publishdate interaction/ghostmode " +
        "interaction/antirecording interaction/authorregion interaction/engagement interaction/searchautoplay " +
        "interaction/searchsuggestions interaction/sensitivewarnings",
    "Messages" to "misc/inbox misc/translation misc/comment misc/commenttools misc/commentpublish",
    "Look" to "misc/theme misc/branding misc/navigation",
    "App" to "misc",
)
val splitNativeTests = tasks.register<SplitNativeTests>("splitNativeTests") {
    mainClasses.from(sourceSets.main.get().output.classesDirs)
    testClasses.from(sourceSets.test.get().output.classesDirs)
    sources.set(layout.projectDirectory.dir("src/main/kotlin"))
    areas.set(nativeTestAreas.mapValues { (_, folders) -> folders.split(' ').joinToString(" ") { "app/morphe/patches/tiktok/$it" } })
    excluded.set(extensionClasses.map { it.removeSuffix(".class") })
    outputDirectory.set(layout.buildDirectory.dir("nativeTestAreas"))
}
val mainResources = sourceSets.main.get().output.resourcesDir
val nativeTestClasspath = sourceSets.test.get().runtimeClasspath.filter { it != mainResources } + files(nativeTestResources)
val nativeTestAreaTasks = (nativeTestAreas.keys + NativeTestAreas.SHARED).map { area ->
    tasks.register<Test>("nativeTest$area") {
        group = "verification"
        description = "Runs the $area patch tests that read neither extension sources nor the extension bundle."
        val folder = splitNativeTests.flatMap { it.outputDirectory.dir(area) }
        testClassesDirs = files(folder.map { it.dir("seeds") })
        if (area == NativeTestAreas.SHARED) {
            // These read the whole source tree, the patch list and this build file, so every class
            // and every source is theirs.
            classpath = files(folder.map { it.dir("seeds") }) + nativeTestClasspath
            inputs.dir("src/main").withPropertyName("patchSources").withPathSensitivity(PathSensitivity.RELATIVE)
            inputs.files(rootProject.file("patches-list.json"), file("build.gradle.kts")).withPropertyName("repoFiles")
                .withPathSensitivity(PathSensitivity.RELATIVE)
            mustRunAfter("generatePatchesList")
        } else {
            // Only the classes this area's tests reach, and the sources they come from or name.
            val compiled = sourceSets.main.get().output.classesDirs + sourceSets.test.get().output.classesDirs
            classpath = files(folder.map { it.dir("seeds") }, folder.map { it.file("lib.jar") }, folder.map { it.file("main.jar") }) +
                nativeTestClasspath.filter { it !in compiled }
            inputs.dir(folder.map { it.dir("src") }).withPropertyName("patchSources").withPathSensitivity(PathSensitivity.RELATIVE)
        }
        exclude(extensionClasses)
        // Under test-results/nativeTest, one folder per area, where the single task's results were.
        reports.junitXml.outputLocation.set(layout.buildDirectory.dir("test-results/nativeTest/$area"))
        binaryResultsDirectory.set(layout.buildDirectory.dir("test-results/nativeTest/$area/binary"))
        reports.html.outputLocation.set(layout.buildDirectory.dir("reports/tests/nativeTest/$area"))
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
}
val nativeTest = tasks.register("nativeTest") {
    group = "verification"
    description = "Runs every nativeTest area."
    dependsOn(nativeTestAreaTasks)
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
// Every partition that opens TikTok APKs.
tasks.withType<Test>().matching { it.name == "test" || it.name.startsWith("nativeTest") }.configureEach {
    // GateCatalogFixturesTest runs the catalog generator on each declared build, and the
    // generator holds a whole APK's dex (some 430 MB on 47.1.x, feature modules included)
    // while it walks it; the fixture scans in nativeTest hold the same dex. Gradle's default
    // test heap is 512 MB, where that ran out of memory.
    maxHeapSize = "4g"
    // The nativeTest areas are some 600 single-threaded scans over the one declared build's dex.
    // Two forks per area each decode it once (the [fixtures] line in the report counts decodes)
    // and split the classes, which takes the two workers the build governor allows. 2 GB holds
    // one 47.1.4 dex and a scan, so two forks take no more heap than the single 4 GB JVM did.
    if (name.startsWith("nativeTest")) {
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
    // NearestMethodsReport ranks methods for the fingerprint these name, so a new name runs it again.
    inputs.property("nearestMethods", providers.environmentVariable("HUSHFEED_NEAREST").orElse(""))
    inputs.property("nearestMethodsFrom", providers.environmentVariable("HUSHFEED_NEAREST_FROM").orElse(""))
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
        // Never MORPHE_SIGNING_KEY: since 2.1.0 the patches plugin signs build/libs in place from
        // it (or from a morphe-signing.key file), and pinBundleTimestamp throws that signature
        // away, so the bundle would be signed twice. With neither set the plugin warns that the
        // libs bundle is unsigned, which is true and harmless: the release bundle is signed below.
        val signingKey = providers.environmentVariable("HUSHFEED_BUNDLE_SIGNING_KEY")
        val publicKeyFile = rootProject.layout.projectDirectory.file("morphe-signing.pub")
        val pluginKeyFile = rootProject.layout.projectDirectory.file("morphe-signing.key")
        // Before the plugin's own action, which would sign with another Hush repo's key left in
        // the shell by its scripts, or fail on it.
        doFirst {
            if (!System.getenv("MORPHE_SIGNING_KEY").isNullOrBlank()) throw GradleException(
                "MORPHE_SIGNING_KEY is set, probably left by another Hush repo's script. hushfeed signs " +
                    "with HUSHFEED_BUNDLE_SIGNING_KEY: clear it with Remove-Item env:MORPHE_SIGNING_KEY")
            if (pluginKeyFile.asFile.exists()) throw GradleException(
                "morphe-signing.key would have the patches plugin sign build/libs. hushfeed signs with " +
                    "HUSHFEED_BUNDLE_SIGNING_KEY: move the file out of the repo")
        }
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
            BundleRelease.pinBundleTimestamp(releaseBundle, pinnedEpoch, BundleSource.manifestFacts(start, end))
            // Signed before the checksum too: the bundle a release publishes is the signed file.
            BundleRelease.signBundle(releaseBundle, signingKey.orNull, publicKeyFile.asFile.readText().trim())
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
