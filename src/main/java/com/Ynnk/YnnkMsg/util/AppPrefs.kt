package com.Ynnk.YnnkMsg.util

import android.content.Context

object AppPrefs {
    private const val PREFS_NAME = "ynnkmsg_prefs"
    private const val KEY_AUTO_DELETE = "auto_delete"
    private const val KEY_BASE26 = "base26_enabled"
    private const val KEY_PGP = "pgp_enabled"
    private const val KEY_CHAT_BG = "chat_background"
    private const val KEY_FONT_SIZE = "font_size"
    private const val KEY_AUTO_ADD_CONTACTS = "auto_add_contacts"
    private const val KEY_IGNORED_EMAILS = "ignored_emails"
    private const val KEY_MESSAGING_REQUESTS = "messaging_requests"
    private const val KEY_REQUEST_MSG_CONFIRMATION = "message_request_confirmation"

    private fun prefs(context: Context) = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    fun isAutoDeleteEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_DELETE, true)
    fun isConfirmationRequestEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_REQUEST_MSG_CONFIRMATION, false)
    fun isBase26Enabled(context: Context): Boolean = prefs(context).getBoolean(KEY_BASE26, true)
    fun isPgpEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_PGP, false)
    fun getChatBackground(context: Context): String? = prefs(context).getString(KEY_CHAT_BG, null)
    fun getFontSize(context: Context): Int = prefs(context).getInt(KEY_FONT_SIZE, 16)
    fun isAutoAddContactsEnabled(context: Context): Boolean = prefs(context).getBoolean(KEY_AUTO_ADD_CONTACTS, false)

    fun getIgnoredEmails(context: Context): Set<String> = prefs(context).getStringSet(KEY_IGNORED_EMAILS, emptySet()) ?: emptySet()
    fun addIgnoredEmail(context: Context, email: String) {
        val set = getIgnoredEmails(context).toMutableSet()
        set.add(email)
        prefs(context).edit().putStringSet(KEY_IGNORED_EMAILS, set).apply()
    }
    fun removeIgnoredEmail(context: Context, email: String) {
        val set = getIgnoredEmails(context).toMutableSet()
        set.remove(email)
        prefs(context).edit().putStringSet(KEY_IGNORED_EMAILS, set).apply()
    }

    fun getMessagingRequests(context: Context): Set<String> = prefs(context).getStringSet(KEY_MESSAGING_REQUESTS, emptySet()) ?: emptySet()
    fun addMessagingRequest(context: Context, email: String) {
        val set = getMessagingRequests(context).toMutableSet()
        set.add(email)
        prefs(context).edit().putStringSet(KEY_MESSAGING_REQUESTS, set).apply()
    }
    fun removeMessagingRequest(context: Context, email: String) {
        val set = getMessagingRequests(context).toMutableSet()
        set.remove(email)
        prefs(context).edit().putStringSet(KEY_MESSAGING_REQUESTS, set).apply()
    }

    fun setAutoDelete(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_AUTO_DELETE, enabled).apply()
    fun setConfirmationRequest(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_REQUEST_MSG_CONFIRMATION, enabled).apply()
    fun setBase26(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_BASE26, enabled).apply()
    fun setPgp(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_PGP, enabled).apply()
    fun setChatBackground(context: Context, path: String?) = prefs(context).edit().putString(KEY_CHAT_BG, path).apply()
    fun setFontSize(context: Context, size: Int) = prefs(context).edit().putInt(KEY_FONT_SIZE, size).apply()
    fun setAutoAddContacts(context: Context, enabled: Boolean) = prefs(context).edit().putBoolean(KEY_AUTO_ADD_CONTACTS, enabled).apply()
}
