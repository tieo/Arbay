package io.github.tieo.arbay.api

import dev.whyoleg.cryptography.CryptographyProvider
import dev.whyoleg.cryptography.DelicateCryptographyApi
import dev.whyoleg.cryptography.algorithms.AES
import dev.whyoleg.cryptography.algorithms.HMAC
import dev.whyoleg.cryptography.algorithms.SHA256
import dev.whyoleg.cryptography.algorithms.XDH
import kotlin.io.encoding.Base64

/**
 * Seals a sign-in secret (a password, a code) to Neuland's public key, so that only Neuland opens it
 * and whatever carries it on, Arbay's server among them, holds the sealed text alone.
 *
 * HPKE (RFC 9180), base mode, DHKEM(X25519, HKDF-SHA256), HKDF-SHA256 and AES-128-GCM, under the
 * info "neuland sign-in" and no associated data, as Neuland's keyring opens it: the sealed text is
 * the 32-byte encapsulated key followed by the ciphertext with its tag, in base64. Built on the
 * X25519, HMAC and AES-GCM every platform has; HKDF is HMAC (an empty salt is a key of zeros, which
 * HMAC pads to the same).
 */
object Sealing {
    const val INFO = "neuland sign-in"

    private val provider get() = CryptographyProvider.Default

    /** [secret] sealed to [publicKeyBase64], the key Neuland hands out. */
    suspend fun seal(publicKeyBase64: String, secret: String): String {
        val ephemeral = provider.get(XDH).keyPairGenerator(XDH.Curve.X25519).generateKey()
        return Base64.encode(seal(Base64.decode(publicKeyBase64), ephemeral, secret.encodeToByteArray(), INFO.encodeToByteArray(), ByteArray(0)))
    }

    /** One sealing with a given ephemeral key pair: the encapsulated key, then the ciphertext. */
    @OptIn(DelicateCryptographyApi::class)
    internal suspend fun seal(recipient: ByteArray, ephemeral: XDH.KeyPair, plaintext: ByteArray, info: ByteArray, aad: ByteArray): ByteArray {
        val xdh = provider.get(XDH)
        val pkR = xdh.publicKeyDecoder(XDH.Curve.X25519).decodeFromByteArray(XDH.PublicKey.Format.RAW, recipient)
        val enc = ephemeral.publicKey.encodeToByteArray(XDH.PublicKey.Format.RAW)
        // Encap: the shared secret from the Diffie-Hellman output and both public keys.
        val dh = ephemeral.privateKey.sharedSecretGenerator().generateSharedSecretToByteArray(pkR)
        val eaePrk = labeledExtract(KEM_SUITE, ByteArray(0), "eae_prk", dh)
        val sharedSecret = labeledExpand(KEM_SUITE, eaePrk, "shared_secret", enc + recipient, 32)
        // Key schedule, base mode: no PSK.
        val pskIdHash = labeledExtract(HPKE_SUITE, ByteArray(0), "psk_id_hash", ByteArray(0))
        val infoHash = labeledExtract(HPKE_SUITE, ByteArray(0), "info_hash", info)
        val context = byteArrayOf(0) + pskIdHash + infoHash
        val secret = labeledExtract(HPKE_SUITE, sharedSecret, "secret", ByteArray(0))
        val key = labeledExpand(HPKE_SUITE, secret, "key", context, 16)
        val nonce = labeledExpand(HPKE_SUITE, secret, "base_nonce", context, 12)
        // The first message: the base nonce as it is.
        val sealed = provider.get(AES.GCM).keyDecoder().decodeFromByteArray(AES.Key.Format.RAW, key)
            .cipher().encryptWithIv(nonce, plaintext, aad)
        return enc + sealed
    }

    // "KEM" || kem_id 0x0020, and "HPKE" || kem_id || kdf_id 0x0001 || aead_id 0x0001.
    private val KEM_SUITE = "KEM".encodeToByteArray() + byteArrayOf(0x00, 0x20)
    private val HPKE_SUITE = "HPKE".encodeToByteArray() + byteArrayOf(0x00, 0x20, 0x00, 0x01, 0x00, 0x01)
    private val VERSION = "HPKE-v1".encodeToByteArray()

    private suspend fun labeledExtract(suite: ByteArray, salt: ByteArray, label: String, ikm: ByteArray): ByteArray =
        hmac(salt, VERSION + suite + label.encodeToByteArray() + ikm)

    /** HKDF-Expand for one block or less, which every length here is. */
    private suspend fun labeledExpand(suite: ByteArray, prk: ByteArray, label: String, info: ByteArray, length: Int): ByteArray {
        val labeled = byteArrayOf((length shr 8).toByte(), length.toByte()) + VERSION + suite + label.encodeToByteArray() + info
        return hmac(prk, labeled + byteArrayOf(1)).copyOf(length)
    }

    private suspend fun hmac(key: ByteArray, data: ByteArray): ByteArray =
        provider.get(HMAC).keyDecoder(SHA256)
            // An empty salt is a key of zeros: HMAC pads a short key with zeros either way, and some
            // platforms (WebCrypto) refuse an empty key.
            .decodeFromByteArray(HMAC.Key.Format.RAW, key.takeIf { it.isNotEmpty() } ?: ByteArray(32))
            .signatureGenerator().generateSignature(data)
}
