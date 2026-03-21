package com.Ynnk.YnnkMsg.util

import android.util.Log
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bouncycastle.bcpg.ArmoredOutputStream
import org.bouncycastle.bcpg.HashAlgorithmTags
import org.bouncycastle.jce.provider.BouncyCastleProvider
import org.bouncycastle.openpgp.*
import org.bouncycastle.openpgp.operator.jcajce.*
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.security.KeyPairGenerator
import java.security.Security
import java.util.*

object PgpUtils {

    private const val TAG = "PgpUtils"
    private val bcProvider = BouncyCastleProvider()

    init {
        setupProvider()
    }

    private fun setupProvider() {
        try {
            Security.removeProvider(BouncyCastleProvider.PROVIDER_NAME)
            Security.insertProviderAt(bcProvider, 1)
        } catch (e: Exception) {
            Log.e(TAG, "Failed to register BouncyCastle provider", e)
        }
    }

    data class PgpKeyPair(val publicKey: String, val privateKey: String)

    /**
     * Generates a new PGP RSA 2048-bit key pair.
     */
    suspend fun generateKeyPair(identity: String): PgpKeyPair? = withContext(Dispatchers.IO) {
        try {
            if (Security.getProvider(BouncyCastleProvider.PROVIDER_NAME) !is BouncyCastleProvider) setupProvider()

            val kpg = KeyPairGenerator.getInstance("RSA", BouncyCastleProvider.PROVIDER_NAME)
            kpg.initialize(2048)
            val kp = kpg.generateKeyPair()

            val pgpKeyPair = JcaPGPKeyPair(PGPPublicKey.RSA_GENERAL, kp, Date())
            val digestCalc = JcaPGPDigestCalculatorProviderBuilder()
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                .build()
                .get(HashAlgorithmTags.SHA1)
            
            val secretKey = PGPSecretKey(
                PGPSignature.DEFAULT_CERTIFICATION,
                pgpKeyPair,
                identity,
                digestCalc,
                null,
                null,
                JcaPGPContentSignerBuilder(pgpKeyPair.publicKey.algorithm, HashAlgorithmTags.SHA256)
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME),
                JcePBESecretKeyEncryptorBuilder(PGPEncryptedData.AES_256, digestCalc)
                    .setProvider(BouncyCastleProvider.PROVIDER_NAME)
                    .build("".toCharArray())
            )

            val pubOut = ByteArrayOutputStream()
            val armoredPub = ArmoredOutputStream(pubOut)
            secretKey.publicKey.encode(armoredPub)
            armoredPub.close()

            val secOut = ByteArrayOutputStream()
            val armoredSec = ArmoredOutputStream(secOut)
            secretKey.encode(armoredSec)
            armoredSec.close()

            PgpKeyPair(pubOut.toString("UTF-8"), secOut.toString("UTF-8"))
        } catch (e: Exception) {
            Log.e(TAG, "PGP Key generation failed", e)
            null
        }
    }

    /**
     * Encrypts plain text using the recipient's public key.
     * Returns encrypted bytes (binary).
     */
    fun encrypt(text: String, publicKeyArmored: String): ByteArray? {
        return encryptBinary(text.toByteArray(Charsets.UTF_8), publicKeyArmored)
    }

    /**
     * Encrypts binary data using the recipient's public key.
     */
    fun encryptBinary(data: ByteArray, publicKeyArmored: String): ByteArray? {
        try {
            val publicKey = readPublicKey(publicKeyArmored) ?: return null
            
            val encOut = ByteArrayOutputStream()
            val encryptorBuilder = JcePGPDataEncryptorBuilder(PGPEncryptedData.AES_256)
                .setWithIntegrityPacket(true)
                .setSecureRandom(java.security.SecureRandom())
                .setProvider(BouncyCastleProvider.PROVIDER_NAME)

            val encryptionDataGenerator = PGPEncryptedDataGenerator(encryptorBuilder)
            encryptionDataGenerator.addMethod(JcePublicKeyKeyEncryptionMethodGenerator(publicKey).setProvider(BouncyCastleProvider.PROVIDER_NAME))

            val out = encryptionDataGenerator.open(encOut, ByteArray(4096))
            
            val compressedDataGenerator = PGPCompressedDataGenerator(PGPCompressedData.ZIP)
            val cos = compressedDataGenerator.open(out)
            
            val literalDataGenerator = PGPLiteralDataGenerator()
            val los = literalDataGenerator.open(cos, PGPLiteralData.UTF8, PGPLiteralData.CONSOLE, data.size.toLong(), Date())
            los.write(data)
            los.close()
            
            compressedDataGenerator.close()
            out.close()
            encryptionDataGenerator.close()
            
            return encOut.toByteArray()
        } catch (e: Exception) {
            Log.e(TAG, "Encryption failed", e)
            return null
        }
    }

    /**
     * Decrypts binary data using the user's private key.
     */
    fun decrypt(encryptedData: ByteArray, privateKeyArmored: String): String? {
        val decryptedBytes = decryptBinary(encryptedData, privateKeyArmored)
        return decryptedBytes?.toString(Charsets.UTF_8)
    }

    /**
     * Decrypts binary data using the user's private key.
     */
    fun decryptBinary(encryptedData: ByteArray, privateKeyArmored: String): ByteArray? {
        try {
            val inputStream = PGPUtil.getDecoderStream(ByteArrayInputStream(encryptedData))
            val pgpObjectFactory = PGPObjectFactory(inputStream, JcaKeyFingerprintCalculator())
            
            var obj: Any? = pgpObjectFactory.nextObject()
            while (obj != null && obj !is PGPEncryptedDataList) {
                obj = pgpObjectFactory.nextObject()
            }
            
            val encDataList = obj as? PGPEncryptedDataList ?: return null
            
            var pbe: PGPPublicKeyEncryptedData? = null
            var sKey: PGPPrivateKey? = null
            
            val secretKeyRingCollection = PGPSecretKeyRingCollection(
                PGPUtil.getDecoderStream(ByteArrayInputStream(privateKeyArmored.toByteArray(Charsets.UTF_8))),
                JcaKeyFingerprintCalculator()
            )

            val it = encDataList.iterator()
            while (it.hasNext()) {
                val data = it.next() as PGPPublicKeyEncryptedData
                val pgpSecKey = secretKeyRingCollection.getSecretKey(data.keyID)
                if (pgpSecKey != null) {
                    pbe = data
                    sKey = pgpSecKey.extractPrivateKey(
                        JcePBESecretKeyDecryptorBuilder().setProvider(BouncyCastleProvider.PROVIDER_NAME).build("".toCharArray())
                    )
                    break
                }
            }

            if (sKey == null || pbe == null) return null

            val clear = pbe.getDataStream(JcePublicKeyDataDecryptorFactoryBuilder().setProvider(BouncyCastleProvider.PROVIDER_NAME).build(sKey))
            val plainFact = PGPObjectFactory(clear, JcaKeyFingerprintCalculator())
            
            var message = plainFact.nextObject()
            if (message is PGPCompressedData) {
                val compFact = PGPObjectFactory(message.dataStream, JcaKeyFingerprintCalculator())
                message = compFact.nextObject()
            }
            
            if (message is PGPLiteralData) {
                return message.inputStream.readBytes()
            }
            
            return null
        } catch (e: Exception) {
            Log.e(TAG, "Decryption failed", e)
            return null
        }
    }

    private fun readPublicKey(armoredText: String): PGPPublicKey? {
        try {
            val input = PGPUtil.getDecoderStream(ByteArrayInputStream(armoredText.toByteArray(Charsets.UTF_8)))
            val pgpPub = PGPPublicKeyRingCollection(input, JcaKeyFingerprintCalculator())
            val keyRings = pgpPub.iterator()
            while (keyRings.hasNext()) {
                val kRing = keyRings.next() as PGPPublicKeyRing
                val keys = kRing.publicKeys
                while (keys.hasNext()) {
                    val k = keys.next() as PGPPublicKey
                    if (k.isEncryptionKey) return k
                }
            }
        } catch (e: Exception) {
            Log.e(TAG, "Failed to read public key", e)
        }
        return null
    }
}
