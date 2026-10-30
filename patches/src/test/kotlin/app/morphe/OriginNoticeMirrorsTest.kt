package app.morphe

import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Every mirror URL in a source notice names a file GitLab has.
 *
 * <p>The notices that cite `github.com/ReVanced/revanced-patches` point at a repository GitHub
 * has blocked under a DMCA notice since 2026-03-24, and CONTRIBUTING keeps those lines as the
 * record of which revision the code came from. The line beside each is the GitLab mirror, which
 * is the one a reader can open. This asks GitLab about every mirror, so the next takedown is
 * found by the suite rather than by a reader, and it is the only test here that needs the
 * network: a machine with none fails it by name rather than passing over it.
 *
 * <p>The question goes to GitLab's GraphQL `Repository.blobs`, once per revision the mirrors
 * name, and its answer lists the paths it found there and leaves the rest out. Fetching each
 * mirror's page cost a request per mirror, and gitlab.com lets one address make about 60
 * unauthenticated requests an hour (`unauthenticated_traffic_per_ip`) before it answers 429
 * until the hour is out, so 62 mirrors never fit in one run (2026-10-30). A page fetch also had
 * to catch GitLab sending a missing file's address on to the repository's front page, which
 * answers 200. Here a missing path is just absent from the list.
 *
 * <p>A 429 says nothing about the files. A wait GitLab names within two minutes is taken and the
 * question asked again; a longer one is the hourly limit, and the run fails with the time GitLab
 * gives rather than sleeping out the hour.
 *
 * <p>The patch sources are read as well as the extensions. Six of their notices named ReVanced
 * paths that never held the file (copied from De-Vanced and hxreborn's headers, 2026-10-30), and
 * nothing fetched them while the walk stopped at `extensions/`. This file is left out so a sample
 * URL in a comment here never counts as a notice.
 */
class OriginNoticeMirrorsTest {
    @Test
    fun `every mirror cited by a source notice names a file GitLab has`() {
        val root = generateSequence(File("").absoluteFile) { it.parentFile }
            .first { File(it, "extensions").isDirectory && File(it, "CONTRIBUTING.md").isFile }
        val mirrors = listOf(File(root, "extensions"), File(root, "patches/src")).asSequence()
            .flatMap { tree -> tree.walkTopDown().onEnter { it.name != "build" } }
            .filter { it.isFile && (it.extension == "java" || it.extension == "kt") }
            .filter { it.name != "OriginNoticeMirrorsTest.kt" }
            .flatMap { file -> MIRROR.findAll(file.readText()).map { it.value } }
            .toSortedSet()
        assertTrue("no source notice cites a mirror, so the block has nothing beside it", mirrors.isNotEmpty())

        val unreadable = mirrors.filter { BLOB.matchEntire(it) == null }
        assertTrue("mirrors that are not a link to one file:\n" + unreadable.joinToString("\n"), unreadable.isEmpty())

        val missing = mirrors.groupBy { BLOB.matchEntire(it)!!.groupValues[1] }.flatMap { (ref, links) ->
            val found = links.map { BLOB.matchEntire(it)!!.groupValues[2] }.distinct()
                .chunked(PATHS_PER_QUESTION)
                .flatMap { paths -> pathsIn(answer(ref, paths)) }
                .toSet()
            links.filter { BLOB.matchEntire(it)!!.groupValues[2] !in found }
        }
        assertTrue("mirrors GitLab has no file for:\n" + missing.joinToString("\n"), missing.isEmpty())
    }

    @Test
    fun `the paths GitLab lists are read from its answer`() {
        val answer = """{"data":{"project":{"repository":{"blobs":{"nodes":[{"path":"a/B.java"},{"path":"c/D.kt"}]}}}}}"""
        assertEquals(setOf("a/B.java", "c/D.kt"), pathsIn(answer))
        assertEquals(emptySet<String>(), pathsIn("""{"data":{"project":{"repository":{"blobs":{"nodes":[]}}}}}"""))
        for (refused in listOf(
            """{"errors":[{"message":"Ref is not valid"}],"data":{"project":{"repository":{"blobs":null}}}}""",
            """{"data":{"project":null}}""",
        )) {
            assertTrue(refused, runCatching { pathsIn(refused) }.isFailure)
        }
    }

    @Test
    fun `a wait GitLab names is taken only when it is short`() {
        assertEquals(7_000L, waitMillis("7"))
        assertEquals(MAX_WAIT_MS, waitMillis((MAX_WAIT_MS / 1_000).toString()))
        assertNull(waitMillis("3452"))
        assertEquals(DEFAULT_WAIT_MS, waitMillis(null))
        assertEquals(DEFAULT_WAIT_MS, waitMillis("Wed, 21 Oct 2026 07:28:00 GMT"))
    }

    /** GitLab's answer about [paths] at [ref], after any short wait it asks for. */
    private fun answer(ref: String, paths: List<String>): String {
        val question = JsonObject().apply {
            addProperty("query", QUERY)
            add("variables", JsonObject().apply {
                addProperty("ref", ref)
                add("paths", JsonArray().apply { paths.forEach { add(it) } })
            })
        }.toString()
        repeat(THROTTLED_TRIES) {
            val connection = URL(GRAPHQL).openConnection() as HttpURLConnection
            try {
                connection.requestMethod = "POST"
                connection.doOutput = true
                connection.connectTimeout = 20_000
                connection.readTimeout = 60_000
                connection.setRequestProperty("Content-Type", "application/json")
                connection.setRequestProperty("User-Agent", "hushfeed-origin-notice-check")
                connection.outputStream.use { it.write(question.toByteArray(Charsets.UTF_8)) }
                val status = connection.responseCode
                if (status == 200) return connection.inputStream.use { it.readBytes().toString(Charsets.UTF_8) }
                if (status != 429) throw AssertionError("GitLab answered $status asking about $ref")
                val retryAfter = connection.getHeaderField("Retry-After")
                val wait = waitMillis(retryAfter) ?: throw AssertionError(
                    "GitLab has this address over its unauthenticated limit for $retryAfter s more " +
                        "(${connection.getHeaderField("RateLimit-ResetTime")}); run it again after that"
                )
                Thread.sleep(wait)
            } finally {
                connection.disconnect()
            }
        }
        throw AssertionError("GitLab still answered 429 asking about $ref after $THROTTLED_TRIES tries")
    }

    /** The paths in a `Repository.blobs` answer; an answer that names an error or no project fails. */
    private fun pathsIn(answer: String): Set<String> {
        val body = JsonParser.parseString(answer).asJsonObject
        check(!body.has("errors")) { "GitLab refused the question: ${body.get("errors")}" }
        val project = body.getAsJsonObject("data").get("project")
        check(project != null && project.isJsonObject) { "GitLab shows no public ReVanced/revanced-patches" }
        return project.asJsonObject.getAsJsonObject("repository").getAsJsonObject("blobs")
            .getAsJsonArray("nodes").map { it.asJsonObject.get("path").asString }.toSet()
    }

    /** The wait to honor for a 429, or null when it runs past [MAX_WAIT_MS]. */
    private fun waitMillis(retryAfter: String?): Long? {
        val seconds = retryAfter?.trim()?.toLongOrNull() ?: return DEFAULT_WAIT_MS
        return (seconds * 1_000).coerceAtLeast(0).takeIf { it <= MAX_WAIT_MS }
    }

    private companion object {
        val MIRROR = Regex("""https://gitlab\.com/ReVanced/revanced-patches/[^\s"]+""")
        val BLOB = Regex("""https://gitlab\.com/ReVanced/revanced-patches/-/blob/([^/]+)/([^#?]+)(?:[#?].*)?""")
        const val GRAPHQL = "https://gitlab.com/api/graphql"
        const val QUERY = "query(\$ref: String!, \$paths: [String!]!) { project(fullPath: \"ReVanced/revanced-patches\") " +
            "{ repository { blobs(ref: \$ref, paths: \$paths) { nodes { path } } } } }"
        const val PATHS_PER_QUESTION = 50
        const val THROTTLED_TRIES = 4
        const val DEFAULT_WAIT_MS = 10_000L
        const val MAX_WAIT_MS = 120_000L
    }
}
