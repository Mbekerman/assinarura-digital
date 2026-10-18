package br.com.bkm.assinapdf.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Armazenamento cifrado com chave AES-GCM do Android Keystore (a chave nunca sai do hardware
 * seguro do aparelho). Usado para o token de acesso do SafeID e para o estado temporário da
 * autorização (verificador PKCE). Nunca guarda PIN, senha ou chave privada de certificado.
 */
class SecureStore(private val dir: File) {
    private val keyAlias = "assinapdf_segredos"

    private fun key(): SecretKey {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getEntry(keyAlias, null) as? KeyStore.SecretKeyEntry)?.let { return it.secretKey }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(
            KeyGenParameterSpec.Builder(keyAlias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .setKeySize(256)
                .build(),
        )
        return gen.generateKey()
    }

    @Synchronized
    fun put(name: String, value: String) {
        dir.mkdirs()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key())
        val iv = cipher.iv
        val ct = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        File(dir, "$name.bin").writeBytes(byteArrayOf(iv.size.toByte()) + iv + ct)
    }

    @Synchronized
    fun get(name: String): String? {
        val f = File(dir, "$name.bin")
        if (!f.exists()) return null
        return runCatching {
            val data = f.readBytes()
            val ivLen = data[0].toInt()
            val iv = data.copyOfRange(1, 1 + ivLen)
            val ct = data.copyOfRange(1 + ivLen, data.size)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, iv))
            String(cipher.doFinal(ct), Charsets.UTF_8)
        }.getOrElse {
            f.delete() // chave trocada ou arquivo corrompido: descarta
            null
        }
    }

    @Synchronized
    fun remove(name: String) {
        File(dir, "$name.bin").delete()
    }
}
