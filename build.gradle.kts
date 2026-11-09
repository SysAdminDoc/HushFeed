plugins {
    alias(libs.plugins.android.library) apply false
}

// One Bouncy Castle version across every module, because the request arrives in more places
// than any single module can see. The patcher pins 1.77 and the Android build tools ask for
// 1.79. 1.77 is inside CVE-2025-8916 (1.44 to 1.78), both are inside CVE-2026-5588 (1.67 to
// 1.83, apart from the 1.80.2 and 1.81.1 maintenance releases) and CVE-2026-97873 (everything
// before 1.86); the reviewed release is the one gradle/libs.versions.toml pins, which says why
// it is that one. None of this reaches the payload injected into TikTok. It is the build and signing
// classpath, and the repository's rule is that a known-affected component does not stay in a
// reproducible graph.
//
// Per module this was applied twice and still missed three graphs: :extensions:shared's release
// runtime classpath, which has no force of its own, and two configurations the Android plugin
// makes for itself. The settings classpath is forced separately in settings.gradle.kts, which
// resolves before this file exists.
val reviewedBouncyCastle = libs.versions.bouncycastle.get()
// Guava the same way, for the same reason: the patcher's own graph, the Robolectric test graph
// and the Android test platform each ask for a release inside CVE-2026-102554 and
// CVE-2026-118042, and the catalog's pin reaches only :patches. The catalog says which release
// and why.
val reviewedGuava = libs.versions.guava.get()
// Netty the same way: the Android plugin's unified test platform configurations in each
// extension module ask for releases inside critical netty-handler and netty-codec-http2
// advisories. The catalog says which release and why.
val reviewedNetty = libs.versions.netty.get()

/**
 * The same three pins, applied to what every published component asks for. A force on the
 * configurations container misses graphs the Android plugin resolves from a detached
 * configuration, which is how lint loads its own classpath: lint-gradle still brought Guava
 * 33.3.1 and Bouncy Castle 1.79 with only the force in place. A dependency rewritten here is
 * required at the reviewed release, so it wins over an older request wherever the graph comes from.
 */
abstract class ReviewedReleasesRule @javax.inject.Inject constructor(
    private val bouncyCastle: String,
    private val guava: String,
    private val netty: String,
) : ComponentMetadataRule {
    override fun execute(context: ComponentMetadataContext) {
        context.details.allVariants {
            withDependencies {
                forEach { dependency ->
                    val reviewed = when {
                        dependency.group == "org.bouncycastle" -> bouncyCastle
                        dependency.group == "com.google.guava" && dependency.name == "guava" -> guava
                        isNettyMainTrain(dependency.group, dependency.name, dependency.versionConstraint.requiredVersion) -> netty
                        else -> null
                    }
                    if (reviewed != null) dependency.version { require(reviewed) }
                }
            }
        }
    }

    companion object {
        /** Netty's main release train, the one the catalog's pin is on; netty-tcnative versions apart. */
        fun isNettyMainTrain(group: String, name: String, version: String?): Boolean =
            group == "io.netty" && !name.startsWith("netty-tcnative") && version?.startsWith("4.1.") == true
    }
}

allprojects {
    configurations.configureEach {
        resolutionStrategy.eachDependency {
            if (requested.group == "org.bouncycastle") {
                useVersion(reviewedBouncyCastle)
                because("The build classpath must use the reviewed Bouncy Castle release.")
            }
            if (requested.group == "com.google.guava" && requested.name == "guava") {
                useVersion(reviewedGuava)
                because("The build classpath must use the Guava release outside CVE-2026-102554 and CVE-2026-118042.")
            }
            if (ReviewedReleasesRule.isNettyMainTrain(requested.group, requested.name, requested.version)) {
                useVersion(reviewedNetty)
                because("The test platform classpath must use a Netty release outside GHSA-c4c3-7fpv-j4q5.")
            }
        }
    }
    dependencies.components.all(ReviewedReleasesRule::class.java) {
        params(reviewedBouncyCastle, reviewedGuava, reviewedNetty)
    }
}
