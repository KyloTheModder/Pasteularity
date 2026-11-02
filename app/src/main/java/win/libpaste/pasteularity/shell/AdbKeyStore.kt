package win.libpaste.pasteularity.shell

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.File
import java.math.BigInteger
import java.security.KeyFactory
import java.security.KeyPairGenerator
import java.security.KeyStore
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.PKCS8EncodedKeySpec
import java.util.Date
import java.util.concurrent.TimeUnit
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.bouncycastle.asn1.x500.X500Name
import org.bouncycastle.cert.jcajce.JcaX509CertificateConverter
import org.bouncycastle.cert.jcajce.JcaX509v3CertificateBuilder
import org.bouncycastle.operator.jcajce.JcaContentSignerBuilder

class AdbIdentity(val privateKey: PrivateKey, val certificate: X509Certificate)

/**
 * The TLS identity adbd remembers after pairing. libadb needs the raw key, so it cannot live
 * inside the Android Keystore; it is sealed on disk with a Keystore AES key instead.
 */
class AdbKeyStore(context: Context) {
    private val file = File(context.noBackupFilesDir, "adb_identity.bin")

    fun loadOrCreate(): AdbIdentity {
        if (file.exists()) {
            runCatching { return read() }
        }
        val identity = generate()
        write(identity)
        return identity
    }

    private fun generate(): AdbIdentity {
        val keyPair = KeyPairGenerator.getInstance("RSA").apply { initialize(2048) }.generateKeyPair()
        val now = System.currentTimeMillis()
        val subject = X500Name("CN=Pasteularity")
        val holder = JcaX509v3CertificateBuilder(
            subject,
            BigInteger(64, SecureRandom()),
            Date(now - TimeUnit.DAYS.toMillis(1)),
            Date(now + TimeUnit.DAYS.toMillis(365L * 30)),
            subject,
            keyPair.public,
        ).build(JcaContentSignerBuilder("SHA256withRSA").build(keyPair.private))
        return AdbIdentity(keyPair.private, JcaX509CertificateConverter().getCertificate(holder))
    }

    private fun write(identity: AdbIdentity) {
        val plain = ByteArrayOutputStream().also { bytes ->
            DataOutputStream(bytes).use { out ->
                out.writeBlock(identity.privateKey.encoded)
                out.writeBlock(identity.certificate.encoded)
            }
        }.toByteArray()
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, sealingKey()) }
        val sealed = cipher.doFinal(plain)
        DataOutputStream(file.outputStream()).use { out ->
            out.writeBlock(cipher.iv)
            out.writeBlock(sealed)
        }
    }

    private fun read(): AdbIdentity {
        val (iv, sealed) = DataInputStream(file.inputStream()).use { it.readBlock() to it.readBlock() }
        val cipher = Cipher.getInstance(TRANSFORMATION).apply {
            init(Cipher.DECRYPT_MODE, sealingKey(), GCMParameterSpec(GCM_TAG_BITS, iv))
        }
        return DataInputStream(ByteArrayInputStream(cipher.doFinal(sealed))).use { input ->
            val key = KeyFactory.getInstance("RSA").generatePrivate(PKCS8EncodedKeySpec(input.readBlock()))
            val cert = CertificateFactory.getInstance("X.509")
                .generateCertificate(ByteArrayInputStream(input.readBlock())) as X509Certificate
            AdbIdentity(key, cert)
        }
    }

    private fun sealingKey(): SecretKey {
        val store = KeyStore.getInstance(ANDROID_KEYSTORE).apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, ANDROID_KEYSTORE).apply {
            init(
                KeyGenParameterSpec.Builder(
                    KEY_ALIAS,
                    KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT,
                )
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()
    }

    private fun DataOutputStream.writeBlock(bytes: ByteArray) {
        writeInt(bytes.size)
        write(bytes)
    }

    private fun DataInputStream.readBlock(): ByteArray = ByteArray(readInt()).also { readFully(it) }

    private companion object {
        const val ANDROID_KEYSTORE = "AndroidKeyStore"
        const val KEY_ALIAS = "pstu_adb_identity"
        const val TRANSFORMATION = "AES/GCM/NoPadding"
        const val GCM_TAG_BITS = 128
    }
}
