package com.shilapi.xcertplay.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29])
class CarTabletUpdatesTest {
    private fun release(tag: String, prerelease: Boolean = false, sums: Boolean = true) = """
        {"tag_name": "$tag", "draft": false, "prerelease": $prerelease, "assets": [
          {"name": "DiPlay-car-tablet.apk", "url": "${CarTabletUpdates.ASSET_PREFIX}1"}
          ${if (sums) """, {"name": "SHA256SUMS.txt", "url": "${CarTabletUpdates.ASSET_PREFIX}2"}""" else ""}
        ]}"""

    @Test
    fun picksHighestCarBuildAboveInstalled() {
        val json = "[${release("v0.2.22-car20")}, ${release("v0.2.22-car21", sums = false)}, ${release("signing-key", prerelease = true)}, ${release("v0.2.22-car18")}]"
        assertEquals("v0.2.22-car20", CarTabletUpdates.pick(json, 19)?.tagName)
        assertNull(CarTabletUpdates.pick(json, 20))
        assertEquals("v0.2.22-car20", CarTabletUpdates.pick(json, null)?.tagName)
    }
}
