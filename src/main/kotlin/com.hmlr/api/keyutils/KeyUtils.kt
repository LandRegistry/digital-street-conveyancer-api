package com.hmlr.api.keyutils

import net.corda.core.crypto.Crypto
import net.corda.core.utilities.loggerFor
import net.corda.core.utilities.toBase64
import org.springframework.util.ResourceUtils
import java.io.File
import java.io.FileOutputStream
import java.io.FileReader
import java.security.PublicKey
import java.util.*

class KeyUtils(val filename: String) {

    companion object {
        val DEFAULT_KEY_PROPERTIES_FILE = "keys.properties"
    }

    private val propertiesObject = Properties()

    init{
        try {
            val resourceUrl = ResourceUtils.getURL("classpath:$filename")
            val resourceStream = resourceUrl.openStream()
            propertiesObject.load(resourceStream)
        } catch(e: Exception) {
            loggerFor<KeyUtils>().warn("Failed reading of $filename! Generating new set of keys. Exception: $e")
            init("12345")
        }
    }

    private fun init (message: String){
        val properties = Properties()
        val buyerKeys = Crypto.generateKeyPair(Crypto.RSA_SHA256)
        val sellerKeys = Crypto.generateKeyPair(Crypto.RSA_SHA256)
        val wrongSellerKeys = Crypto.generateKeyPair(Crypto.RSA_SHA256)

        val buyerPublicKeyBytes = buyerKeys.public.encoded
        val buyerPrivateKeyBytes = buyerKeys.private.encoded
        val buyerPivateKey = Crypto.decodePrivateKey(Crypto.RSA_SHA256, buyerPrivateKeyBytes)
        val buyerSign = Crypto.doSign(Crypto.RSA_SHA256, buyerPivateKey, message.toByteArray())

        val sellerPublicKeyBytes = sellerKeys.public.encoded
        val sellerPrivateKeyBytes = sellerKeys.private.encoded
        val sellerPivateKey = Crypto.decodePrivateKey(Crypto.RSA_SHA256, sellerPrivateKeyBytes)
        val sellerSign = Crypto.doSign(Crypto.RSA_SHA256, sellerPivateKey, message.toByteArray())


        val wrongSellerPublicKeyBytes = wrongSellerKeys.public.encoded
        val wrongSellerPrivateKeyBytes = wrongSellerKeys.private.encoded
        val wrongSellerPivateKey = Crypto.decodePrivateKey(Crypto.RSA_SHA256, wrongSellerPrivateKeyBytes)
        val wrongSellerSign = Crypto.doSign(Crypto.RSA_SHA256, wrongSellerPivateKey, message.toByteArray())

        properties.put("buyer.public", buyerPublicKeyBytes.toBase64())
        properties.put("buyer.private", buyerPrivateKeyBytes.toBase64())
        properties.put("buyer.signature", buyerSign.toBase64())

        properties.put("seller.public",sellerPublicKeyBytes.toBase64())
        properties.put("seller.private", sellerPrivateKeyBytes.toBase64())
        properties.put("seller.signature",sellerSign.toBase64())

        properties.put("wrongSeller.public",wrongSellerPublicKeyBytes.toBase64())
        properties.put("wrongSeller.private", wrongSellerPrivateKeyBytes.toBase64())
        properties.put("wrongSeller.signature",wrongSellerSign.toBase64())

        val file = File(filename)
        val out = FileOutputStream(file)
        properties.store(out,"")

        val reader = FileReader(filename)
        propertiesObject.load(reader)
    }

     fun sign(userType: String, message: String): ByteArray {
        // get the private key of the user from the file
        val privKeyBase64 = propertiesObject.getProperty(userType+".private")

        // convert to bytes
        val privKeyBytes = Base64.getDecoder().decode(privKeyBase64)

        // decode bytes to PrivateKey object
        val privateKey = Crypto.decodePrivateKey(Crypto.RSA_SHA256, privKeyBytes)

        //sign
        return Crypto.doSign(Crypto.RSA_SHA256, privateKey, message.toByteArray())
    }

     fun readPublicKey(userType: String): PublicKey {
        // get the public key from the file
        val pubKeyBase64 = propertiesObject.getProperty(userType+".public")

        // convert to bytes
        val pubKeyBytes = Base64.getDecoder().decode(pubKeyBase64)

        // decode bytes to PublicKey object
        return Crypto.decodePublicKey(Crypto.RSA_SHA256, pubKeyBytes)
    }

     fun readSignature(userType: String): ByteArray {
        val sigBase64 = propertiesObject.getProperty(userType+".signature")
        return Base64.getDecoder().decode(sigBase64)
    }

    fun verify(publicKey: PublicKey, signature: ByteArray, message: String): Boolean {
         return Crypto.doVerify(Crypto.RSA_SHA256, publicKey, signature, message.toByteArray() )
    }
}