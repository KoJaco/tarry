package me.tarry.app

import com.google.common.truth.Truth.assertThat
import org.junit.Test
import java.io.File

/**
 * Privacy copy must match what the app sends (ADR 0004): each place's position goes to the platform geocoder and the
 * viewed map area to OpenFreeMap. Only the location history stays on the phone, so absolute claims are not allowed.
 */
class PrivacyClaimsTest {
    private fun file(rel: String): File =
        listOf(File(rel), File("../$rel"), File("app/$rel")).firstOrNull { it.exists() }
            ?: error("$rel not found from ${File(".").absoluteFile}")

    private val banned = listOf(
        "never uploaded",
        "never leaves",
        "your location stays",
        "location stays on",
        "data stays on this phone",
        "everything stays on",
        "it all stays on",
    )

    private fun offenders(f: File): List<String> =
        f.readLines().withIndex().flatMap { (i, line) ->
            banned.filter { line.contains(it, ignoreCase = true) }.map { "${f.name}:${i + 1} \"$it\"" }
        }

    @Test
    fun appStringsAndStoreCopyMakeNoAbsoluteLocationClaims() {
        val strings = file("app/src/main/res/values").listFiles { f -> f.extension == "xml" }!!.toList()
        val docs = listOf("docs/store-listing.md", "docs/privacy-policy.md", "docs/brand/brand-and-copy.md").map(::file)
        assertThat((strings + docs).flatMap(::offenders)).isEmpty()
    }

    @Test
    fun theBackgroundLocationDisclosureNamesBothThingsThatLeaveThePhone() {
        val xml = file("app/src/main/res/values/strings.xml").readText()
        val body = Regex("<string name=\"permissions_disclosure_body\">(.*?)</string>").find(xml)!!.groupValues[1]
        assertThat(body).contains("geocoder (Google)")
        assertThat(body).contains("OpenFreeMap")
        assertThat(body).contains("location history is stored only on this phone")
    }
}
