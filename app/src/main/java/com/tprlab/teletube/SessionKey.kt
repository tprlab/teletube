package com.tprlab.teletube

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

internal fun sessionKey(context: Context): ByteArray {
    val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
    val prefs = context.getSharedPreferences("session-key", Context.MODE_PRIVATE)
    val wrapped = prefs.getString("ciphertext", null)
    val alias = "teletube.tdlib"
    val cipher = Cipher.getInstance("AES/GCM/NoPadding")
    val key = store.getKey(alias, null) as? SecretKey ?: KeyGenerator
        .getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        .apply {
            init(
                KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                    .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
                    .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
                    .build(),
            )
        }.generateKey()

    if (wrapped != null) {
        val iv = Base64.decode(checkNotNull(prefs.getString("iv", null)), Base64.NO_WRAP)
        cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, iv))
        return cipher.doFinal(Base64.decode(wrapped, Base64.NO_WRAP))
    }

    val secret = ByteArray(32).also(SecureRandom()::nextBytes)
    cipher.init(Cipher.ENCRYPT_MODE, key)
    check(
        prefs.edit()
            .putString("ciphertext", Base64.encodeToString(cipher.doFinal(secret), Base64.NO_WRAP))
            .putString("iv", Base64.encodeToString(cipher.iv, Base64.NO_WRAP))
            .commit(),
    )
    return secret
}
