package com.zhayi.qookix.compose.pages

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.AtomicFile
import android.util.Base64
import java.io.File
import java.io.IOException
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import org.json.JSONObject

internal class QookixAccountStorage(context: Context) {
    private val file = AtomicFile(File(context.noBackupFilesDir, "qookix_account"))
    private val alias = "qookix_service_account"

    fun read(): JSONObject? {
        val bytes = try {
            file.readFully()
        } catch (_: IOException) {
            return null
        }
        return runCatching {
            val stored = JSONObject(bytes.toString(Charsets.UTF_8))
            val iv = Base64.decode(stored.getString("iv"), Base64.NO_WRAP)
            val ciphertext = Base64.decode(stored.getString("session"), Base64.NO_WRAP)
            val key = checkNotNull(keyStore().getKey(alias, null) as? SecretKey)
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
            JSONObject(cipher.doFinal(ciphertext).toString(Charsets.UTF_8))
        }.getOrNull()
    }

    fun write(session: JSONObject?) {
        if (session == null) {
            file.delete()
            return
        }
        val key = keyStore().getKey(alias, null) as? SecretKey ?: createKey()
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, key)
        val ciphertext = cipher.doFinal(session.toString().toByteArray(Charsets.UTF_8))
        val bytes = JSONObject()
            .put("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .put("session", Base64.encodeToString(ciphertext, Base64.NO_WRAP))
            .toString().toByteArray(Charsets.UTF_8)
        val stream = file.startWrite()
        try {
            stream.write(bytes)
            file.finishWrite(stream)
        } catch (error: Exception) {
            file.failWrite(stream)
            throw error
        }
    }

    private fun keyStore() = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }

    private fun createKey(): SecretKey {
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(
            KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                .build(),
        )
        return generator.generateKey()
    }
}
