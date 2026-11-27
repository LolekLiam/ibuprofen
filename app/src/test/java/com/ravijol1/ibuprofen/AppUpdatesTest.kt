package com.ravijol1.ibuprofen

import com.ravijol1.ibuprofen.data.*
import org.junit.Assert.*
import org.junit.Test

class AppUpdatesTest {
    @Test fun numericalVersionsDoNotCompareLexicographically() {
        val update = findAppUpdate("v1.9.0", listOf(GithubRelease("v1.10.0"), GithubRelease("v1.9.9")))
        assertEquals("v1.10.0", update?.tag)
        assertNull(findAppUpdate("v1.10.0", listOf(GithubRelease("v1.9.0"), GithubRelease("v1.10.0"))))
    }
    @Test fun stableDoesNotOfferPrereleasesIncludingIncorrectlyTaggedOnes() {
        assertNull(findAppUpdate("v1.1.0", listOf(GithubRelease("v1.2.0-beta", prerelease = true), GithubRelease("v1.2.0", prerelease = true))))
    }
    @Test fun betaReceivesNewBetaAndFinalButNotAlphaOrDraft() {
        val releases = listOf(GithubRelease("v2.0.0-alpha"), GithubRelease("v9.0.0", draft = true), GithubRelease("v1.1.0-beta.2"))
        assertEquals("v1.1.0-beta.2", findAppUpdate("v1.1.0-beta.1", releases)?.tag)
        assertEquals("v1.1.0", findAppUpdate("v1.1.0-beta", releases + GithubRelease("v1.1.0"))?.tag)
    }
    @Test fun semverPrereleaseOrderAndBuildMetadata() {
        val ordered = listOf("1.0.0-alpha", "1.0.0-alpha.1", "1.0.0-alpha.beta", "1.0.0-beta", "1.0.0-beta.2", "1.0.0-beta.11", "1.0.0-rc.1", "1.0.0")
        assertEquals(ordered, ordered.reversed().sortedBy { AppVersion.parse(it) })
        assertEquals(0, AppVersion.parse("v1.1.0+build.2")!!.compareTo(AppVersion.parse("1.1.0+build.3")!!))
    }
    @Test fun malformedAndUnsafeTagsAreNotOffered() {
        listOf("latest", "v01.1.0", "1.2.3-beta..1", "1.2.3-beta.01", "1.2.3+..", "https://example.invalid/", "v1.2.3/evil").forEach {
            assertNull(it, AppVersion.parse(it))
        }
        assertNull(findAppUpdate("unknown", listOf(GithubRelease("v2.0.0"))))
        assertNull(findAppUpdate("v1.0.0", listOf(GithubRelease(null))))
    }
    @Test fun canaryAndRegularUpdateChannelsStaySeparate() {
        val releases = listOf(GithubRelease("v1.1.11-dev", prerelease = true), GithubRelease("v1.2.0-beta"), GithubRelease("v1.2.0"))
        assertEquals("v1.1.11-dev", findAppUpdate("v1.1.10-dev", releases)?.tag)
        assertEquals("v1.2.0", findAppUpdate("v1.1.0-beta", releases)?.tag)
        assertNull(findAppUpdate("v1.1.0-beta", listOf(GithubRelease("v1.1.11-dev"))))
        assertNull(findAppUpdate("v1.1.10-dev", listOf(GithubRelease("v2.0.0"))))
    }

    @Test fun updateLinkAlwaysTargetsTheAppReleasePage() {
        assertEquals("https://github.com/LolekLiam/ibuprofen/releases/tag/v1.2.0-beta.1",
            findAppUpdate("v1.1.0-beta", listOf(GithubRelease("v1.2.0-beta.1")))?.url)
    }
}
