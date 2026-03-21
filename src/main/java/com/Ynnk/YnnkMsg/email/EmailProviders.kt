package com.Ynnk.YnnkMsg.email

data class EmailProviderConfig(
    val imapHost: String,
    val imapPort: Int,
    val imapSsl: Boolean,
    val smtpHost: String,
    val smtpPort: Int,
    val smtpSsl: Boolean,
    val smtpStartTls: Boolean
)

object EmailProviders {

    private val configs = mapOf(
        "gmail.com" to EmailProviderConfig(
            imapHost = "imap.gmail.com", imapPort = 993, imapSsl = true,
            smtpHost = "smtp.gmail.com", smtpPort = 587, smtpSsl = false, smtpStartTls = true
        ),
        "googlemail.com" to EmailProviderConfig(
            imapHost = "imap.gmail.com", imapPort = 993, imapSsl = true,
            smtpHost = "smtp.gmail.com", smtpPort = 587, smtpSsl = false, smtpStartTls = true
        ),
        "yandex.ru" to EmailProviderConfig(
            imapHost = "imap.yandex.ru", imapPort = 993, imapSsl = true,
            smtpHost = "smtp.yandex.ru", smtpPort = 465, smtpSsl = true, smtpStartTls = true
        ),
        "ya.ru" to EmailProviderConfig(
            imapHost = "imap.yandex.ru", imapPort = 993, imapSsl = true,
            smtpHost = "smtp.yandex.ru", smtpPort = 587, smtpSsl = true, smtpStartTls = false
        ),
        "yandex.com" to EmailProviderConfig(
            imapHost = "imap.yandex.com", imapPort = 993, imapSsl = true,
            smtpHost = "smtp.yandex.com", smtpPort = 587, smtpSsl = true, smtpStartTls = false
        ),
        "tuta.com" to EmailProviderConfig(
            imapHost = "mail.tutanota.de", imapPort = 993, imapSsl = true,
            smtpHost = "mail.tutanota.de", smtpPort = 587, smtpSsl = false, smtpStartTls = true
        ),
        "tutanota.com" to EmailProviderConfig(
            imapHost = "mail.tutanota.de", imapPort = 993, imapSsl = true,
            smtpHost = "mail.tutanota.de", smtpPort = 587, smtpSsl = false, smtpStartTls = true
        ),
        "outlook.com" to EmailProviderConfig(
            imapHost = "outlook.office365.com", imapPort = 993, imapSsl = true,
            smtpHost = "smtp.office365.com", smtpPort = 587, smtpSsl = false, smtpStartTls = true
        ),
        "hotmail.com" to EmailProviderConfig(
            imapHost = "outlook.office365.com", imapPort = 993, imapSsl = true,
            smtpHost = "smtp.office365.com", smtpPort = 587, smtpSsl = false, smtpStartTls = true
        ),
        "mail.ru" to EmailProviderConfig(
            imapHost = "imap.mail.ru", imapPort = 993, imapSsl = true,
            smtpHost = "smtp.mail.ru", smtpPort = 587, smtpSsl = false, smtpStartTls = true
        ),
        "bk.ru" to EmailProviderConfig(
            imapHost = "imap.mail.ru", imapPort = 993, imapSsl = true,
            smtpHost = "smtp.mail.ru", smtpPort = 587, smtpSsl = false, smtpStartTls = true
        ),
        "list.ru" to EmailProviderConfig(
            imapHost = "imap.mail.ru", imapPort = 993, imapSsl = true,
            smtpHost = "smtp.mail.ru", smtpPort = 587, smtpSsl = false, smtpStartTls = true
        ),
        "yahoo.com" to EmailProviderConfig(
            imapHost = "imap.mail.yahoo.com", imapPort = 993, imapSsl = true,
            smtpHost = "smtp.mail.yahoo.com", smtpPort = 465, smtpSsl = true, smtpStartTls = true
        )
    )

    fun getConfigForEmail(email: String): EmailProviderConfig? {
        val domain = email.substringAfterLast("@").lowercase()
        return configs[domain]
    }

    fun isKnownProvider(email: String): Boolean {
        return getConfigForEmail(email) != null
    }
}
