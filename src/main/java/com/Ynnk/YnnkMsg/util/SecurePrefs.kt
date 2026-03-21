package com.Ynnk.YnnkMsg.util

import android.content.Context
import android.content.SharedPreferences
import android.util.Log
import androidx.security.crypto.EncryptedSharedPreferences
import androidx.security.crypto.MasterKey
import java.util.UUID

object SecurePrefs {

    private const val TAG = "SecurePrefs"
    private const val PREFS_NAME = "ynnkmsg_secure_prefs"
    private const val KEY_USER_ID = "active_user_id"
    private const val KEY_EMAIL = "user_email"
    private const val KEY_PASSWORD = "user_password"
    private const val KEY_IMAP_HOST = "imap_host"
    private const val KEY_IMAP_PORT = "imap_port"
    private const val KEY_SMTP_HOST = "smtp_host"
    private const val KEY_SMTP_PORT = "smtp_port"
    private const val KEY_IMAP_SSL = "imap_ssl"
    private const val KEY_SMTP_SSL = "smtp_ssl"
    private const val KEY_SMTP_STARTTLS = "smtp_starttls"

    private const val KEY_SEC_EMAIL = "sec_user_email"
    private const val KEY_SEC_PASSWORD = "sec_user_password"
    private const val KEY_SEC_IMAP_HOST = "sec_imap_host"
    private const val KEY_SEC_IMAP_PORT = "sec_imap_port"
    private const val KEY_SEC_SMTP_HOST = "sec_smtp_host"
    private const val KEY_SEC_SMTP_PORT = "sec_smtp_port"
    private const val KEY_SEC_IMAP_SSL = "sec_imap_ssl"
    private const val KEY_SEC_SMTP_SSL = "sec_smtp_ssl"
    private const val KEY_SEC_SMTP_STARTTLS = "sec_smtp_starttls"

    private const val KEY_IS_LOGGED_IN = "is_logged_in"
    private const val KEY_PGP_PUBLIC_KEY = "pgp_public_key"
    private const val KEY_PGP_PRIVATE_KEY = "pgp_private_key"
    private const val KEY_PIN_ENABLED = "pin_enabled"
    private const val KEY_PIN_CODE = "pin_code"
    private const val KEY_DATABASE_PASSWORD = "database_password"

    @Volatile
    private var cachedPrefs: SharedPreferences? = null

    fun getPrefs(context: Context): SharedPreferences {
        return cachedPrefs ?: synchronized(this) {
            cachedPrefs ?: initPrefs(context)
        }
    }

    private fun initPrefs(context: Context): SharedPreferences {
        Log.d(TAG, "Initializing SecurePrefs...")
        val appContext = context.applicationContext
        return try {
            val masterKey = MasterKey.Builder(appContext)
                .setKeyScheme(MasterKey.KeyScheme.AES256_GCM)
                .build()

            val prefs = EncryptedSharedPreferences.create(
                appContext,
                PREFS_NAME,
                masterKey,
                EncryptedSharedPreferences.PrefKeyEncryptionScheme.AES256_SIV,
                EncryptedSharedPreferences.PrefValueEncryptionScheme.AES256_GCM
            )
            cachedPrefs = prefs
            prefs
        } catch (e: Exception) {
            Log.e(TAG, "SecurePrefs init failed, using fallback", e)
            val fallback = appContext.getSharedPreferences("ynnkmsg_fallback_prefs", Context.MODE_PRIVATE)
            cachedPrefs = fallback
            fallback
        }
    }

    fun initInBackground(context: Context) {
        if (cachedPrefs == null) {
            getPrefs(context)
        }
    }

    fun saveCredentials(
        context: Context,
        userId: Long,
        email: String,
        password: String,
        imapHost: String,
        imapPort: Int,
        smtpHost: String,
        smtpPort: Int,
        imapSsl: Boolean,
        smtpSsl: Boolean,
        smtpStartTls: Boolean
    ) {
        getPrefs(context).edit().apply {
            putLong(KEY_USER_ID, userId)
            putString(KEY_EMAIL, email)
            putString(KEY_PASSWORD, password)
            putString(KEY_IMAP_HOST, imapHost)
            putInt(KEY_IMAP_PORT, imapPort)
            putString(KEY_SMTP_HOST, smtpHost)
            putInt(KEY_SMTP_PORT, smtpPort)
            putBoolean(KEY_IMAP_SSL, imapSsl)
            putBoolean(KEY_SMTP_SSL, smtpSsl)
            putBoolean(KEY_SMTP_STARTTLS, smtpStartTls)
            putBoolean(KEY_IS_LOGGED_IN, true)
        }.apply()
    }

    fun saveSecondaryCredentials(
        context: Context,
        email: String?,
        password: String?,
        imapHost: String?,
        imapPort: Int,
        smtpHost: String?,
        smtpPort: Int,
        imapSsl: Boolean,
        smtpSsl: Boolean,
        smtpStartTls: Boolean
    ) {
        getPrefs(context).edit().apply {
            putString(KEY_SEC_EMAIL, email)
            putString(KEY_SEC_PASSWORD, password)
            putString(KEY_SEC_IMAP_HOST, imapHost)
            putInt(KEY_SEC_IMAP_PORT, imapPort)
            putString(KEY_SEC_SMTP_HOST, smtpHost)
            putInt(KEY_SEC_SMTP_PORT, smtpPort)
            putBoolean(KEY_SEC_IMAP_SSL, imapSsl)
            putBoolean(KEY_SEC_SMTP_SSL, smtpSsl)
            putBoolean(KEY_SEC_SMTP_STARTTLS, smtpStartTls)
        }.apply()
    }

    fun getActiveUserId(context: Context): Long = getPrefs(context).getLong(KEY_USER_ID, -1L)
    
    fun getEmail(context: Context): String? = getPrefs(context).getString(KEY_EMAIL, null)
    fun getPassword(context: Context): String? = getPrefs(context).getString(KEY_PASSWORD, null)
    fun getImapHost(context: Context): String? = getPrefs(context).getString(KEY_IMAP_HOST, null)
    fun getImapPort(context: Context): Int = getPrefs(context).getInt(KEY_IMAP_PORT, 993)
    fun getSmtpHost(context: Context): String? = getPrefs(context).getString(KEY_SMTP_HOST, null)
    fun getSmtpPort(context: Context): Int = getPrefs(context).getInt(KEY_SMTP_PORT, 587)
    fun getImapSsl(context: Context): Boolean = getPrefs(context).getBoolean(KEY_IMAP_SSL, true)
    fun getSmtpSsl(context: Context): Boolean = getPrefs(context).getBoolean(KEY_SMTP_SSL, false)
    fun getSmtpStartTls(context: Context): Boolean = getPrefs(context).getBoolean(KEY_SMTP_STARTTLS, true)

    fun getSecondaryEmail(context: Context): String? = getPrefs(context).getString(KEY_SEC_EMAIL, null)
    fun getSecondaryPassword(context: Context): String? = getPrefs(context).getString(KEY_SEC_PASSWORD, null)
    fun getSecondaryImapHost(context: Context): String? = getPrefs(context).getString(KEY_SEC_IMAP_HOST, null)
    fun getSecondaryImapPort(context: Context): Int = getPrefs(context).getInt(KEY_SEC_IMAP_PORT, 993)
    fun getSecondarySmtpHost(context: Context): String? = getPrefs(context).getString(KEY_SEC_SMTP_HOST, null)
    fun getSecondarySmtpPort(context: Context): Int = getPrefs(context).getInt(KEY_SEC_SMTP_PORT, 587)
    fun getSecondaryImapSsl(context: Context): Boolean = getPrefs(context).getBoolean(KEY_SEC_IMAP_SSL, true)
    fun getSecondarySmtpSsl(context: Context): Boolean = getPrefs(context).getBoolean(KEY_SEC_SMTP_SSL, false)
    fun getSecondarySmtpStartTls(context: Context): Boolean = getPrefs(context).getBoolean(KEY_SEC_SMTP_STARTTLS, true)

    fun isLoggedIn(context: Context): Boolean = getPrefs(context).getBoolean(KEY_IS_LOGGED_IN, false)

    fun getPgpPublicKey(context: Context): String? = getPrefs(context).getString(KEY_PGP_PUBLIC_KEY, null)
    fun getPgpPrivateKey(context: Context): String? = getPrefs(context).getString(KEY_PGP_PRIVATE_KEY, null)

    fun savePgpKeys(context: Context, publicKey: String, privateKey: String) {
        getPrefs(context).edit().apply {
            putString(KEY_PGP_PUBLIC_KEY, publicKey)
            putString(KEY_PGP_PRIVATE_KEY, privateKey)
        }.apply()
    }

    fun isPinEnabled(context: Context): Boolean = getPrefs(context).getBoolean(KEY_PIN_ENABLED, false)
    fun setPinEnabled(context: Context, enabled: Boolean) = getPrefs(context).edit().putBoolean(KEY_PIN_ENABLED, enabled).apply()
    
    fun getPinCode(context: Context): String? = getPrefs(context).getString(KEY_PIN_CODE, null)
    fun setPinCode(context: Context, pin: String?) = getPrefs(context).edit().putString(KEY_PIN_CODE, pin).apply()

    fun getDatabasePassword(context: Context): String {
        val prefs = getPrefs(context)
        var password = prefs.getString(KEY_DATABASE_PASSWORD, null)
        if (password == null) {
            password = UUID.randomUUID().toString()
            prefs.edit().putString(KEY_DATABASE_PASSWORD, password).apply()
        }
        return password
    }

    fun logout(context: Context) {
        getPrefs(context).edit().apply {
            remove(KEY_USER_ID)
            remove(KEY_EMAIL)
            remove(KEY_PASSWORD)
            remove(KEY_IMAP_HOST)
            remove(KEY_IMAP_PORT)
            remove(KEY_SMTP_HOST)
            remove(KEY_SMTP_PORT)
            remove(KEY_IMAP_SSL)
            remove(KEY_SMTP_SSL)
            remove(KEY_SMTP_STARTTLS)
            remove(KEY_SEC_EMAIL)
            remove(KEY_SEC_PASSWORD)
            remove(KEY_SEC_IMAP_HOST)
            remove(KEY_SEC_IMAP_PORT)
            remove(KEY_SEC_SMTP_HOST)
            remove(KEY_SEC_SMTP_PORT)
            remove(KEY_SEC_IMAP_SSL)
            remove(KEY_SEC_SMTP_SSL)
            remove(KEY_SEC_SMTP_STARTTLS)
            putBoolean(KEY_IS_LOGGED_IN, false)
        }.apply()
    }

    fun clearAll(context: Context) {
        getPrefs(context).edit().clear().apply()
    }
}
