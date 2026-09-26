package tv.own.owntv.core.network

import org.junit.Assert.assertEquals
import org.junit.Test
import java.io.File
import java.security.MessageDigest

class ExtraTrustAnchorsTest {

    private val anchors = ExtraTrustAnchors(File("src/main/res/raw/isrg_extra_roots.pem").inputStream())

    private fun sha256(bytes: ByteArray) =
        MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02X".format(it) }

    @Test
    fun `bundled roots are exactly the published Let's Encrypt ones`() {
        // Fingerprints of https://letsencrypt.org/certs/{isrg-root-x2,gen-y/root-ye,gen-y/root-yr}.pem
        assertEquals(
            listOf(
                "69729B8E15A86EFC177A57AFB7171DFC64ADD28C2FCA8CF1507E34453CCB1470",
                "E14FFCAD5B0025731006CAA43A121A22D8E9700F4FB9CF852F02A708AA5D5666",
                "E57B7E6F150C419102E8D5C055729FF967B9D1A829BF00CEC89CA604EBF4A86F",
            ),
            anchors.certificates.map { sha256(it.encoded) },
        )
    }

    @Test
    fun `a chain ending at Root YR is accepted`() {
        anchors.trustManager.checkServerTrusted(arrayOf(anchors.certificates.last()), "RSA")
    }
}
