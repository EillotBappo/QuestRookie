package com.questrookie

import android.content.Context
import android.os.Build
import io.github.muntashirakon.adb.AbsAdbConnectionManager
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.cert.Certificate
import java.security.cert.CertificateFactory
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date

/**
 * Android 11+ Wireless debugging, used only to switch the headset's adbd to plain TCP on 5555 so [Installer] can talk to it.
 * Pairing is once per headset; after each reboot the user turns Wireless debugging back on and [enableTcpip] finds it over mDNS.
 */
class WirelessAdb(private val ctx: Context) : AbsAdbConnectionManager() {
    private val keyFile = File(ctx.filesDir, "tls.key")
    private val certFile = File(ctx.filesDir, "tls.crt")
    private val key: PrivateKey
    private val cert: Certificate

    init {
        api = Build.VERSION.SDK_INT
        if (!keyFile.exists() || !certFile.exists()) generate()
        key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(keyFile.readBytes()))
        cert = certFile.inputStream().use { CertificateFactory.getInstance("X.509").generateCertificate(it) }
    }

    private fun generate() {
        val kp = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val name = X500Name("CN=QuestRookie")
        val now = System.currentTimeMillis()
        // adbd only checks the public key, so a long-lived self-signed cert is enough.
        val holder = JcaX509v3CertificateBuilder(name, BigInteger.valueOf(now), Date(now), Date(now + 30L * 365 * 86_400_000), name, kp.public)
            .build(JcaContentSignerBuilder("SHA256withRSA").build(kp.private))
        keyFile.writeBytes(kp.private.encoded)
        certFile.writeBytes(JcaX509CertificateConverter().getCertificate(holder).encoded)
    }

    override fun getPrivateKey() = key
    override fun getCertificate() = cert
    override fun getDeviceName() = "QuestRookie"

    /** Port and six-digit code from Developer options > Wireless debugging > Pair device with pairing code. */
    fun pairWith(port: Int, code: String) {
        check(pair("127.0.0.1", port, code)) { "Pairing failed; check the port and code" }
        prefs(ctx).edit().putBoolean("paired", true).apply()
    }

    /** Finds Wireless debugging over mDNS and restarts adbd listening on TCP [port]. */
    fun enableTcpip(port: Int = 5555) {
        try {
            check(autoConnect(ctx, 10_000)) { "Turn on Wireless debugging in Developer options" }
            openStream("tcpip:$port").use { s -> s.openInputStream().bufferedReader().readText() }
        } finally {
            runCatching { disconnect() }
        }
    }

    companion object {
        private fun prefs(ctx: Context) = ctx.getSharedPreferences("adb", Context.MODE_PRIVATE)
        fun isPaired(ctx: Context) = prefs(ctx).getBoolean("paired", false)
    }
}
