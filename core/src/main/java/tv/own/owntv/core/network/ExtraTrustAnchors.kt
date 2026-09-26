package tv.own.owntv.core.network

import java.io.InputStream
import java.security.KeyStore
import java.security.cert.CertificateException
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLSocketFactory
import javax.net.ssl.TrustManagerFactory
import javax.net.ssl.X509TrustManager

/**
 * Let's Encrypt roots that many devices do not trust yet (#208), read from `res/raw/isrg_extra_roots.pem`.
 *
 * ISRG Root YE / YR (2025) are in no platform trust store at all, and ISRG Root X2 only from Android 14,
 * so a panel whose chain ends at one of them failed import with "Unacceptable certificate: CN=Root YR".
 * The platform decides first; these roots are consulted only when it rejects the chain, so nothing that
 * worked before changes. Source: https://letsencrypt.org/certificates/ — each Y root was checked
 * against its cross-sign by X1 / X2 (same key, valid signature) before it was added.
 */
class ExtraTrustAnchors(pems: InputStream) {

    val certificates: List<X509Certificate> = pems.use {
        CertificateFactory.getInstance("X.509").generateCertificates(it).map { c -> c as X509Certificate }
    }

    /** The platform trust manager, falling back to [certificates] when it rejects a server chain. */
    val trustManager: X509TrustManager = run {
        val system = trustManagerFor(null)
        val bundled = trustManagerFor(
            KeyStore.getInstance(KeyStore.getDefaultType()).apply {
                load(null, null)
                certificates.forEachIndexed { i, cert -> setCertificateEntry(i.toString(), cert) }
            },
        )
        object : X509TrustManager {
            override fun checkClientTrusted(chain: Array<X509Certificate>, authType: String) =
                system.checkClientTrusted(chain, authType)

            override fun checkServerTrusted(chain: Array<X509Certificate>, authType: String) {
                try {
                    system.checkServerTrusted(chain, authType)
                } catch (e: CertificateException) {
                    runCatching { bundled.checkServerTrusted(chain, authType) }.getOrElse { throw e }
                }
            }

            override fun getAcceptedIssuers(): Array<X509Certificate> =
                system.acceptedIssuers + bundled.acceptedIssuers
        }
    }

    val sslSocketFactory: SSLSocketFactory =
        SSLContext.getInstance("TLS").apply { init(null, arrayOf(trustManager), null) }.socketFactory

    private fun trustManagerFor(keyStore: KeyStore?): X509TrustManager =
        TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm())
            .apply { init(keyStore) }
            .trustManagers.filterIsInstance<X509TrustManager>().first()
}
