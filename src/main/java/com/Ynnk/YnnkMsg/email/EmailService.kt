package com.Ynnk.YnnkMsg.email

import android.content.Context
import android.util.Log
import androidx.room.withTransaction
import com.Ynnk.YnnkMsg.R
import com.Ynnk.YnnkMsg.YnnkMsgApplication
import com.Ynnk.YnnkMsg.data.AppDatabase
import com.Ynnk.YnnkMsg.data.entity.Contact
import com.Ynnk.YnnkMsg.data.entity.Message
import com.Ynnk.YnnkMsg.util.AppPrefs
import com.Ynnk.YnnkMsg.util.Base26Encoder
import com.Ynnk.YnnkMsg.util.PgpUtils
import com.Ynnk.YnnkMsg.util.SecurePrefs
import com.google.gson.Gson
import com.google.gson.annotations.SerializedName
import com.google.gson.reflect.TypeToken
import kotlinx.coroutines.*
import java.io.File
import java.util.*
import javax.mail.*
import javax.mail.internet.*
import javax.mail.search.FlagTerm

private const val TAG = "EmailService"
private const val YNNKMSG_SUBJECT_PREFIX = "Ynnk:"

private const val MSGBODY_HEADER_START = "ynnk_msg_header:{"
private const val MSGHEADER_TYPE_TEXT = "text"
private const val MSGHEADER_TYPE_USERINFO = "user_info"
private const val MSGHEADER_TYPE_USERINFO_RESP = "user_info_resp"
private const val MSGHEADER_TYPE_PGPKEY = "pgp"
private const val MSGHEADER_TYPE_PGPKEY_RESP = "pgp_resp"
private const val MSGHEADER_TYPE_DATACHUNK = "data_chunk"
private const val MSGHEADER_TYPE_SECONDEMAIL = "second_imap"
private const val MSGHEADER_TYPE_CONFIRMATION = "messages_received"

/** Maximum size of a single file chunk (before base64 encoding). */
private const val CHUNK_SIZE_BYTES = 10 * 1024 * 1024L  // 10 MB

class EmailService(private val context: Context) {

    private val db = AppDatabase.getInstance(context)
    private val messageDao = db.messageDao()
    private val contactDao = db.contactDao()
    private val userDao = db.userDao()
    private val gson = Gson()
    private var emailRandomSubjects: List<String>? = null

    init {
        val jsonString = context.assets.open("subjects.json").bufferedReader().use { it.readText() }
        emailRandomSubjects = Gson().fromJson(jsonString, object : TypeToken<List<String>>() {}.type)
    }

    data class AttachmentParam(
        @SerializedName("index") val index: Int,
        @SerializedName("name") val name: String,
        @SerializedName("md5") var md5: String? = null
    )


    data class MessageParams(
        @SerializedName("type") val type: String? = null,
        @SerializedName("author_email") val authorEmail: String? = null,
        @SerializedName("reply_to") val replyTo: Int? = null,
        @SerializedName("send_time") val sendTime: Long? = null,
        @SerializedName("confirm_received") val needConfirmReceived: Boolean = false,
        @SerializedName("attachments") val attachments: List<AttachmentParam>? = null
    )

    data class DecodedBody(
        val text: String,
        val format: String,
        val params: MessageParams? = null
    )

    // ── Generate Subject ──────────────────────────────────────────────────────
    suspend fun getMessageSubject(userId: Long?, contactEmail: String, contact: Contact? ): String {
        val useUserId = userId ?: SecurePrefs.getActiveUserId(context);
        val useContact = contact ?: contactDao.getContactByEmail(useUserId, contactEmail)

        return if (useContact?.exclusivePrimaryEmail == true && !emailRandomSubjects.isNullOrEmpty()) {
             emailRandomSubjects?.random() ?: "$YNNKMSG_SUBJECT_PREFIX ${System.currentTimeMillis()}"
        } else {
            "$YNNKMSG_SUBJECT_PREFIX ${System.currentTimeMillis()}"
        }
    }

    // ── Send ──────────────────────────────────────────────────────────────────

    suspend fun sendMessage(
        toEmail: String,
        text: String,
        attachmentFiles: List<File> = emptyList(),
        originalAuthorEmail: String? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            val userId = SecurePrefs.getActiveUserId(context)
            if (userId == -1L) return@withContext Result.failure(Exception("No active user"))

            val userEmail = SecurePrefs.getEmail(context)
                ?: return@withContext Result.failure(Exception(context.getString(R.string.error_not_authorized)))
            val password = SecurePrefs.getPassword(context)
                ?: return@withContext Result.failure(Exception(context.getString(R.string.error_no_password)))
            val smtpHost = SecurePrefs.getSmtpHost(context)
                ?: return@withContext Result.failure(Exception(context.getString(R.string.error_no_smtp_settings)))
            val smtpPort = SecurePrefs.getSmtpPort(context)
            val useSsl = SecurePrefs.getSmtpSsl(context)
            val useStartTls = SecurePrefs.getSmtpStartTls(context)
            
            val usePgp = AppPrefs.isPgpEnabled(context)
            val contact = contactDao.getContactByEmail(userId, toEmail)
            
            val session = getSmtpSession(userEmail, password, smtpHost, smtpPort, useSsl, useStartTls)

            var bodyTextFinalized: String
            val hasPgp = usePgp && contact?.publicKey != null
            val filesToAttach = mutableListOf<File>()

            // prepare files first
            if (hasPgp)
            {
                attachmentFiles.forEachIndexed { index, file ->
                    val encryptedFile = File(context.cacheDir, "${index + 1}.bin")
                    val encFileBytes = PgpUtils.encryptBinary(file.readBytes(), contact.publicKey)
                    if (encFileBytes != null) {
                        encryptedFile.writeBytes(encFileBytes)
                        filesToAttach.add(encryptedFile)
                    } else {
                        filesToAttach.add(file)
                    }
                }
            }
            else {
                filesToAttach.addAll(attachmentFiles)
            }
            val totalSize = filesToAttach.sumOf { it.length() }
            val bSplitFiles = filesToAttach.isNotEmpty() && totalSize > CHUNK_SIZE_BYTES;

            // Construct the message text with params if needed
            val attachmentParams = attachmentFiles.mapIndexed { index, file ->
                AttachmentParam(
                    index = index + 1, // Индекс файла + 1
                    name = file.name   // Оригинальное имя файла
                )
            }

            // ── Large attachment: split into chunks ──────────────────────────
            if (bSplitFiles) {
                // For chunked non-PGP, rebuild body to include original filenames —
                // they are needed on the receive side because chunks are named 01.bin, 02.bin.
                val header = MessageParams(MSGHEADER_TYPE_DATACHUNK,
                    if (originalAuthorEmail != null) originalAuthorEmail else userEmail, -1,
                    System.currentTimeMillis(), AppPrefs.isConfirmationRequestEnabled(context),
                    attachmentParams)

                sendChunkedMessage(session, toEmail, userId, userEmail, header, text, attachmentFiles, filesToAttach)
                return@withContext Result.success(Unit)
            }

            // ── Regular message ──────────────────────────

            val msgHeader = generateMsgHeader(MSGHEADER_TYPE_TEXT,
                if (originalAuthorEmail != null) originalAuthorEmail else userEmail, -1, attachmentParams)
            val fullBody = msgHeader + text

            if (hasPgp) {
                // PGP: body always contains "{text;file1.name;file2.name}" (encrypted)
                val encryptedBytes = PgpUtils.encrypt(fullBody, contact!!.publicKey)
                bodyTextFinalized = if (encryptedBytes != null)
                    Base26Encoder.encode(encryptedBytes)
                else
                    Base26Encoder.encodeText(fullBody)
            } else {
                bodyTextFinalized = if (AppPrefs.isBase26Enabled(context)) Base26Encoder.encodeText(fullBody) else fullBody
            }

            // ── Normal single message send ────────────────────────────────────
            val mimeMessage = MimeMessage(session).apply {
                setFrom(InternetAddress(userEmail))
                addRecipient(javax.mail.Message.RecipientType.TO, InternetAddress(toEmail))
                subject = getMessageSubject(userId, toEmail, contact)
                
                val multipart = MimeMultipart()
                multipart.addBodyPart(MimeBodyPart().apply { setText(bodyTextFinalized, "utf-8") })
                for (file in filesToAttach) {
                    multipart.addBodyPart(MimeBodyPart().apply {
                        dataHandler = javax.activation.DataHandler(javax.activation.FileDataSource(file))
                        fileName = MimeUtility.encodeText(file.name)
                        disposition = Part.ATTACHMENT
                    })
                }
                setContent(multipart)
                sentDate = Date()
            }

            Transport.send(mimeMessage)

            val msgId = mimeMessage.messageID ?: UUID.randomUUID().toString()
            messageDao.insert(
                Message(
                    userId = userId,
                    contactEmail = toEmail,
                    authorEmail = originalAuthorEmail ?: userEmail,
                    text = text,
                    isOutgoing = true,
                    attachments = buildAttachmentsJsonFromFiles(attachmentFiles),
                    messageId = msgId
                )
            )

            Result.success(Unit)
        } catch (e: Exception) {
            Log.e(TAG, "Send failed", e)
            Result.failure(e)
        }
    }

    /**
     * Sends a large attachment set as a sequence of emails:
     *  1. MD5 header message  — subject: "Ynnk: [ts] {md5_1;md5_2;...}", body: encoded text + filenames
     *  2. Chunk data messages — subject: "Ynnk: [ts] {1/N;0/M;...}", attachment named 01.bin / 02.bin
     */

    private suspend fun sendChunkedMessage(
        session: Session,
        toEmail: String,
        userId: Long,
        userEmail: String,
        messageHeader: MessageParams,
        originalMessageText: String,
        originalFiles: List<File>,
        preparedFiles: List<File>
    ) {
        val timestamp = System.currentTimeMillis()
        val tempDir = File(context.cacheDir, "chunks_out/$timestamp").apply { mkdirs() }
        try {
            val md5s = preparedFiles.map { ChunkAssembler.computeMd5(it) }

            messageHeader.attachments?.forEachIndexed { idx, attachment ->
                if (idx < md5s.size) {
                    attachment.md5 = md5s[idx]
                }
            }

            val fileChunks = preparedFiles.mapIndexed { idx, file ->
                splitFile(file, CHUNK_SIZE_BYTES, File(tempDir, "f$idx").apply { mkdirs() })
            }
            val totals = fileChunks.map { it.size }

            Log.d(TAG, "Sending chunked: ${originalFiles.size} files, totals=$totals")

            val messageSubject = getMessageSubject(null, toEmail, null)

            // encrypting settings
            val contact = contactDao.getContactByEmail(userId, toEmail)
            val hasPgp = AppPrefs.isPgpEnabled(context) && contact?.publicKey != null

            val headerAsText = serializeMsgHeader(messageHeader)
            val firstMessageText = headerAsText + originalMessageText;
            val firstMessageEncrypted =
            if (hasPgp) {
                // PGP: body always contains "{text;file1.name;file2.name}" (encrypted)
                val encryptedBytes = PgpUtils.encrypt(firstMessageText, contact!!.publicKey)
                if (encryptedBytes != null)
                    Base26Encoder.encode(encryptedBytes)
                else
                    Base26Encoder.encodeText(firstMessageText)
            } else {
                if (AppPrefs.isBase26Enabled(context)) Base26Encoder.encodeText(firstMessageText) else firstMessageText
            }

            sendMimeEmail(session, toEmail, messageSubject, firstMessageEncrypted, emptyList())

            fileChunks.forEachIndexed { fileIdx, chunks ->
                val chunkDisplayName = String.format("%02d.bin", fileIdx + 1)
                chunks.forEachIndexed { zeroIdx, chunkFile ->
                    val chunkIdx = zeroIdx + 1   // 1-based

                    val messageText = headerAsText +
                        totals.mapIndexed { i, total ->
                            if (i == fileIdx) "$chunkIdx/$total" else "0/$total"
                        }.joinToString(";")

                    val messageEncrypted =
                        if (hasPgp) {
                            val encryptedBytes = PgpUtils.encrypt(messageText, contact!!.publicKey)
                            if (encryptedBytes != null)
                                Base26Encoder.encode(encryptedBytes)
                            else
                                Base26Encoder.encodeText(messageText)
                        } else {
                            if (AppPrefs.isBase26Enabled(context)) Base26Encoder.encodeText(messageText) else messageText
                        }

                    sendMimeEmail(session, toEmail, messageSubject, messageEncrypted,
                        listOf(chunkFile to chunkDisplayName))
                }
            }

            messageDao.insert(Message(
                userId = userId,
                contactEmail = toEmail,
                authorEmail = messageHeader.authorEmail ?: userEmail,
                text = originalMessageText,
                isOutgoing = true,
                attachments = buildAttachmentsJsonFromFiles(originalFiles),
                messageId = UUID.randomUUID().toString()
            ))
        } finally {
            tempDir.deleteRecursively()
        }
    }

    /** Splits [file] into chunks of at most [chunkSize] bytes, written to [outputDir]. */
    private fun splitFile(file: File, chunkSize: Long, outputDir: File): List<File> {
        val chunks = mutableListOf<File>()
        val stem = file.nameWithoutExtension
        val ext = if (file.extension.isNotEmpty()) ".${file.extension}" else ""
        var chunkIdx = 1
        var bytesInChunk = 0L
        var chunkFile = File(outputDir, "${stem}_c${chunkIdx}${ext}")

        file.inputStream().buffered().use { input ->
            var chunkOut = chunkFile.outputStream().buffered()
            val buffer = ByteArray(65_536)

            while (true) {
                val bytesRead = input.read(buffer)
                if (bytesRead == -1) {
                    chunkOut.close()
                    if (chunkFile.length() > 0) chunks.add(chunkFile) else chunkFile.delete()
                    break
                }
                var offset = 0
                while (offset < bytesRead) {
                    val canWrite = minOf((bytesRead - offset).toLong(), chunkSize - bytesInChunk).toInt()
                    chunkOut.write(buffer, offset, canWrite)
                    offset += canWrite
                    bytesInChunk += canWrite
                    if (bytesInChunk >= chunkSize) {
                        chunkOut.close()
                        chunks.add(chunkFile)
                        chunkIdx++
                        chunkFile = File(outputDir, "${stem}_c${chunkIdx}${ext}")
                        chunkOut = chunkFile.outputStream().buffered()
                        bytesInChunk = 0L
                    }
                }
            }
        }
        return chunks
    }

    /** Sends a single MIME email with optional file attachments. */
    private fun sendMimeEmail(
        session: Session,
        toEmail: String,
        subject: String,
        bodyText: String,
        attachments: List<Pair<File, String>>   // (file, displayName)
    ) {
        val fromEmail = (session.getProperty("mail.from") ?: SecurePrefs.getEmail(context)) as? String ?: throw Exception("No email configured")
        val mimeMessage = MimeMessage(session).apply {
            setFrom(InternetAddress(fromEmail))
            addRecipient(javax.mail.Message.RecipientType.TO, InternetAddress(toEmail))
            this.subject = subject
            sentDate = Date()
            if (attachments.isEmpty()) {
                setText(bodyText, "utf-8")
            } else {
                val multipart = MimeMultipart()
                multipart.addBodyPart(MimeBodyPart().apply { setText(bodyText, "utf-8") })
                for ((file, name) in attachments) {
                    multipart.addBodyPart(MimeBodyPart().apply {
                        dataHandler = javax.activation.DataHandler(javax.activation.FileDataSource(file))
                        fileName = MimeUtility.encodeText(name)
                        disposition = Part.ATTACHMENT
                    })
                }
                setContent(multipart)
            }
        }
        Transport.send(mimeMessage)
    }

    fun generateMsgHeader(type: String, authorEmail: String? = null, replyTo: Int = -1, attachments: List<AttachmentParam>? = null): String {
        return serializeMsgHeader(MessageParams(type, authorEmail, replyTo,
            System.currentTimeMillis(),
            AppPrefs.isConfirmationRequestEnabled(context),
            attachments))
    }

    fun serializeMsgHeader(header: MessageParams) : String
    {
        var params = gson.toJson(header)
        // screen symbols \\ and }
        params = params.replace("\\", "\\\\")
        params = params.trimEnd().substring(1, params.length - 1)
        params = params.replace("}", "\\1")

        return "$MSGBODY_HEADER_START$params}"
    }

    suspend fun sendServiceMessage(
        toEmail: String,
        type: String,
        nonEncodedBodyText: String? = null,
        attachmentFile: File? = null
    ): Result<Unit> = withContext(Dispatchers.IO) {
        try {
            var userEmail = SecurePrefs.getEmail(context) ?: return@withContext Result.failure(Exception("No email"))
            var password = SecurePrefs.getPassword(context) ?: return@withContext Result.failure(Exception("No pass"))
            var smtpHost = SecurePrefs.getSmtpHost(context) ?: return@withContext Result.failure(Exception("No host"))
            var smtpPort = SecurePrefs.getSmtpPort(context)
            var useSsl = SecurePrefs.getSmtpSsl(context)
            var useStartTls = SecurePrefs.getSmtpStartTls(context)

            if (type == MSGHEADER_TYPE_PGPKEY || type == MSGHEADER_TYPE_PGPKEY_RESP) {
                val secEmail = SecurePrefs.getSecondaryEmail(context)
                val secPass = SecurePrefs.getSecondaryPassword(context)
                val secHost = SecurePrefs.getSecondarySmtpHost(context)
                if (!secEmail.isNullOrEmpty() && !secPass.isNullOrEmpty() && !secHost.isNullOrEmpty()) {
                    userEmail = secEmail
                    password = secPass
                    smtpHost = secHost
                    smtpPort = SecurePrefs.getSecondarySmtpPort(context)
                    useSsl = SecurePrefs.getSecondarySmtpSsl(context)
                    useStartTls = SecurePrefs.getSecondarySmtpStartTls(context)
                }
            }

            val session = getSmtpSession(userEmail, password, smtpHost, smtpPort, useSsl, useStartTls)
            // Add property so sendMimeEmail knows which email to use in From field
            session.properties.setProperty("mail.from", userEmail)

            val att: List<AttachmentParam> =
                if (attachmentFile == null) {
                    emptyList()
                } else {
                    listOf(AttachmentParam(1, attachmentFile.name ?: ""))
                }
            val msgHeader = generateMsgHeader(type, userEmail, -1, att)
            val fullBody = msgHeader + (nonEncodedBodyText ?: "")
            val encodedFullBody = Base26Encoder.encodeText(fullBody)

            val mimeMessage = MimeMessage(session).apply {
                setFrom(InternetAddress(userEmail))
                addRecipient(javax.mail.Message.RecipientType.TO, InternetAddress(toEmail))
                subject = getMessageSubject(null, toEmail, null)
                sentDate = Date()
                
                if (attachmentFile != null) {
                    val multipart = MimeMultipart()
                    multipart.addBodyPart(MimeBodyPart().apply { setText(encodedFullBody, "utf-8") })
                    multipart.addBodyPart(MimeBodyPart().apply {
                        dataHandler = javax.activation.DataHandler(javax.activation.FileDataSource(attachmentFile))
                        fileName = MimeUtility.encodeText(attachmentFile.name)
                        disposition = Part.ATTACHMENT
                    })
                    setContent(multipart)
                } else {
                    setText(encodedFullBody, "utf-8")
                }
            }
            Transport.send(mimeMessage)
            Result.success(Unit)
        } catch (e: Exception) {
            db.appEventDao().logEvent("Error sending service message $type to $toEmail")
            Result.failure(e)
        }
    }

    private fun getSmtpSession(userEmail: String, password: String, smtpHost: String, smtpPort: Int, useSsl: Boolean, useStartTls: Boolean): Session {
        val props = Properties().apply {
            put("mail.smtp.host", smtpHost)
            put("mail.smtp.port", smtpPort.toString())
            put("mail.smtp.auth", "true")
            put("mail.smtp.connectiontimeout", "15000")
            put("mail.smtp.timeout", "15000")
            if (useSsl) {
                put("mail.smtp.ssl.enable", "true")
                put("mail.smtp.socketFactory.class", "javax.net.ssl.SSLSocketFactory")
            }
            if (useStartTls) put("mail.smtp.starttls.enable", "true")
        }
        return Session.getInstance(props, object : Authenticator() {
            override fun getPasswordAuthentication() = PasswordAuthentication(userEmail, password)
        })
    }

    // ── Receive ───────────────────────────────────────────────────────────────

    suspend fun fetchNewMessages(providedFolder: Folder? = null): Result<Int> = withContext(Dispatchers.IO) {
        var store: Store? = null
        var inbox: Folder? = null
        var shouldCheckSecondary = false
        val autoDelete = AppPrefs.isAutoDeleteEnabled(context)
        try {
            val userId = SecurePrefs.getActiveUserId(context)
            if (userId == -1L) return@withContext Result.failure(Exception("No user"))

            // Vulnerable mode: don't fetch any new incoming messages at all
            val user = userDao.getUserById(userId)
            if (user?.vulnerableMode == true) return@withContext Result.success(0)

            if (providedFolder != null) {
                inbox = providedFolder
            } else {
                val userEmail = SecurePrefs.getEmail(context) ?: return@withContext Result.failure(Exception("No email"))
                val password = SecurePrefs.getPassword(context) ?: return@withContext Result.failure(Exception("No pass"))
                val imapHost = SecurePrefs.getImapHost(context) ?: return@withContext Result.failure(Exception("No host"))
                val imapPort = SecurePrefs.getImapPort(context)
                val useSsl = SecurePrefs.getImapSsl(context)
                val protocol = if (useSsl) "imaps" else "imap"
                val props = Properties().apply {
                    put("mail.$protocol.host", imapHost)
                    put("mail.$protocol.port", imapPort.toString())
                    put("mail.$protocol.connectiontimeout", "15000")
                    put("mail.$protocol.timeout", "15000")
                    if (useSsl) put("mail.$protocol.ssl.enable", "true")
                }
                store = Session.getInstance(props).getStore(protocol)
                store.connect(imapHost, imapPort, userEmail, password)
                inbox = store.getFolder("INBOX")
                inbox.open(Folder.READ_WRITE)
            }

            checkMailboxQuotaSafe(inbox!!.store)?.let { (used, limit) ->
                if (limit > 0 && used.toDouble() / limit >= 0.95) {
                    return@withContext Result.failure(MailboxFullException("Mailbox full"))
                }
            }

            val allMessages = inbox.search(FlagTerm(Flags(Flags.Flag.SEEN), false))
            if (allMessages.isEmpty()) return@withContext Result.success(0)

            val messages = if (allMessages.size > 20)
                allMessages.sliceArray(allMessages.size - 20 until allMessages.size)
            else allMessages
            
            inbox.fetch(messages, FetchProfile().apply {
                add(FetchProfile.Item.ENVELOPE)
                add(FetchProfile.Item.CONTENT_INFO)
            })

            var newCount = 0
            val privateKey = SecurePrefs.getPgpPrivateKey(context)
            val ignoredEmails = AppPrefs.getIgnoredEmails(context)

            // key is email, Long is sent time
            val receiveToConfirmMap = mutableMapOf<String, Long>()

            db.withTransaction {
                for (msg in messages) {
                    val subject = (msg as? MimeMessage)?.subject ?: ""
                    val from = (msg.from?.firstOrNull() as? InternetAddress)?.address ?: continue
                    val loweredFrom = from.lowercase()
                    val contact = contactDao.getContactByEmail(userId, loweredFrom)
                    if (contact == null) {
                        if (AppPrefs.isAutoAddContactsEnabled(context)) {
                            contactDao.insert(Contact(userId = userId, email = loweredFrom, name = loweredFrom))
                        } else {
                            AppPrefs.addMessagingRequest(context, loweredFrom)
                        }
                    }
                    val effectiveEmail = contact?.email ?: loweredFrom

                    if (ignoredEmails.contains(effectiveEmail) || ignoredEmails.contains(from.lowercase())) {
                        msg.setFlag(Flags.Flag.SEEN, true)
                        if (autoDelete) msg.setFlag(Flags.Flag.DELETED, true)
                        continue
                    }

                    if (contact?.exclusivePrimaryEmail == false && !subject.startsWith(YNNKMSG_SUBJECT_PREFIX)) continue

                    // get message raw body and attachments
                    val (bodyRaw, rawAttachments) = parseMessage(msg)
                    // get message header
                    val decodedResult = decodeBody(bodyRaw)

                    if (decodedResult.params?.needConfirmReceived == true)

                        val use_time = decodedResult.params.sendTime ?: -1;
                        if (!receiveToConfirmMap.contains(effectiveEmail) || receiveToConfirmMap[effectiveEmail]!! < use_time) {
                            receiveToConfirmMap[effectiveEmail] = use_time;
                        }
                    }

                    if (decodedResult.params?.type == MSGHEADER_TYPE_CONFIRMATION) {
                        msg.setFlag(Flags.Flag.SEEN, true)
                        msg.setFlag(Flags.Flag.DELETED, true)
                        val confirmedTs = decodedResult.text.toLongOrNull() ?: 0L
                        if (confirmedTs > 0) {
                            messageDao.markOutgoingAsRead(userId, effectiveEmail, confirmedTs)
                        }
                        continue
                    }

                    if (decodedResult.params?.type == MSGHEADER_TYPE_USERINFO) {
                        msg.setFlag(Flags.Flag.SEEN, true)
                        msg.setFlag(Flags.Flag.DELETED, true)
                        handleGetUserInfo(effectiveEmail, decodedResult.text, rawAttachments)
                        continue
                    }
                    if (decodedResult.params?.type == MSGHEADER_TYPE_USERINFO_RESP) {
                        msg.setFlag(Flags.Flag.SEEN, true)
                        msg.setFlag(Flags.Flag.DELETED, true)
                        handleUserInfoReceived(effectiveEmail, decodedResult.text, rawAttachments)
                        continue
                    }
                    if (decodedResult.params?.type == MSGHEADER_TYPE_PGPKEY) {
                        db.appEventDao().logEvent("Received encryption key from $effectiveEmail")

                        msg.setFlag(Flags.Flag.SEEN, true)
                        msg.setFlag(Flags.Flag.DELETED, true)
                        handleGetPgpKey(effectiveEmail, decodedResult.text)
                        continue
                    }
                    if (decodedResult.params?.type == MSGHEADER_TYPE_PGPKEY_RESP) {
                        db.appEventDao().logEvent("Received encryption key from $effectiveEmail (response)")
                        msg.setFlag(Flags.Flag.SEEN, true)
                        msg.setFlag(Flags.Flag.DELETED, true)
                        handlePgpKeyReceived(effectiveEmail, decodedResult.text)
                        continue
                    }
                    if (decodedResult.params?.type == MSGHEADER_TYPE_SECONDEMAIL) {
                        msg.setFlag(Flags.Flag.SEEN, true)
                        msg.setFlag(Flags.Flag.DELETED, true)
                        db.appEventDao().logEvent("Received notification to check secondary email from $effectiveEmail")
                        if (effectiveEmail.lowercase() == loweredFrom) {
                            shouldCheckSecondary = true
                        }
                        continue
                    }

                    // ── Chunk message detection ───────────────────────────────
                    if (decodedResult.params?.type == MSGHEADER_TYPE_DATACHUNK) {
                        // Subject is always "Ynnk: TIMESTAMP" — extract timestamp as grouping key
                        val subjectTimestamp = decodedResult.params.sendTime?.toString() ?: "0";
                        val bFirstMessage = rawAttachments.isEmpty()

                        ensureContactExists(userId, effectiveEmail)
                        msg.setFlag(Flags.Flag.SEEN, true)
                        if (autoDelete) msg.setFlag(Flags.Flag.DELETED, true)

                        val assemblyResult = if (bFirstMessage) {
                            // First message: params.attachments carries md5s and original filenames
                            val attachParams = decodedResult.params.attachments ?: emptyList()
                            val md5s = attachParams.mapNotNull { it.md5 }
                            val originalNames = attachParams.map { it.name }
                            val authorEmail = decodedResult.params.authorEmail ?: effectiveEmail
                            val msgTs = (msg as? MimeMessage)?.sentDate?.time ?: System.currentTimeMillis()

                            db.appEventDao().logEvent("Chunked transfer started from $effectiveEmail: ${attachParams.size} file(s)")

                            ChunkAssembler.addMd5Message(
                                context, effectiveEmail, subjectTimestamp,
                                md5s, originalNames, authorEmail,
                                decodedResult.text, msgTs, decodedResult.format
                            )
                        } else {
                            // Chunk data message: body text is chunk info "1/2;0/4"
                            val entries = parseChunkEntries(decodedResult.text)
                            val chunkAttachments = saveChunkAttachments(
                                msg, effectiveEmail, subjectTimestamp, entries
                            )
                            db.appEventDao().logEvent("Chunk received from $effectiveEmail: ${decodedResult.text}")

                            ChunkAssembler.addChunkMessage(
                                context, effectiveEmail, subjectTimestamp,
                                entries, chunkAttachments
                            )
                        }

                        if (assemblyResult != null) {
                            val assembledMsgId = "chunked:$effectiveEmail:$subjectTimestamp"
                            if (messageDao.countByMessageId(userId, assembledMsgId) == 0) {
                                val isChatOpen = YnnkMsgApplication.activeChatEmail
                                    ?.lowercase() == effectiveEmail.lowercase()
                                
                                // Assembled files already have original names (set from params in addMd5Message).
                                // For PGP: decrypt each file in-place (overwrite encrypted bytes with plaintext).
                                val finalAttachments = mutableListOf<String>()
                                if (!assemblyResult.isCorrupted && assemblyResult.format == "pgp" && privateKey != null) {
                                    for (path in assemblyResult.attachmentPaths) {
                                        val file = File(path)
                                        val decryptedBytes = PgpUtils.decryptBinary(file.readBytes(), privateKey)
                                        if (decryptedBytes != null) {
                                            file.writeBytes(decryptedBytes)
                                            finalAttachments.add(path)
                                            } else {
                                                Log.e(TAG, "Failed to decrypt assembled file: $path")
                                                finalAttachments.add(path)
                                            }
                                        }
                                } else {
                                    finalAttachments.addAll(assemblyResult.attachmentPaths)
                                }

                                var finalText = assemblyResult.decodedMessage
                                if (assemblyResult.isCorrupted) {
                                    finalText += "\n[${context.getString(R.string.currupted_attachment)}]"
                                }

                                messageDao.insert(Message(
                                    userId = userId,
                                    contactEmail = assemblyResult.fromEmail,
                                    authorEmail = decodedResult.params?.authorEmail ?: assemblyResult.fromEmail,
                                    text = finalText,
                                    isOutgoing = false,
                                    timestamp = assemblyResult.timestamp,
                                    attachments = buildAttachmentsJsonFromPaths(finalAttachments),
                                    messageId = assembledMsgId,
                                    isRead = isChatOpen
                                ))
                                newCount++
                            }
                        }
                        continue
                    }
                    // ── End chunk handling ────────────────────────────────────

                    val msgId = (msg as? MimeMessage)?.messageID ?: UUID.randomUUID().toString()

                    if (messageDao.countByMessageId(userId, msgId) > 0) {
                        msg.setFlag(Flags.Flag.SEEN, true)
                        if (autoDelete) msg.setFlag(Flags.Flag.DELETED, true)
                        continue
                    }

                    var finalText = decodedResult.text
                    var effectiveAuthorEmail = decodedResult.params?.authorEmail ?: effectiveEmail
                    val finalAttachments = mutableListOf<String>()

                    if (decodedResult.format == "pgp" && rawAttachments.isNotEmpty() && privateKey != null) {
                        val parts = decodedResult.text.split(";")
                        val numAttachments = rawAttachments.size

                        if (parts.size > numAttachments) {
                            val fileNames = parts.takeLast(numAttachments)
                            var cutLength = 0
                            fileNames.forEach { cutLength += it.length + 1 }
                            finalText = decodedResult.text.substring(0, (decodedResult.text.length - cutLength).coerceAtLeast(0))

                            rawAttachments.forEachIndexed { index, path ->
                                val encryptedFile = File(path)
                                val decryptedBytes = PgpUtils.decryptBinary(encryptedFile.readBytes(), privateKey)
                                if (decryptedBytes != null) {
                                    val decryptedFile = File(encryptedFile.parent, fileNames[index])
                                    decryptedFile.writeBytes(decryptedBytes)
                                    finalAttachments.add(decryptedFile.absolutePath)
                                    encryptedFile.delete()
                                } else {
                                    finalAttachments.add(path)
                                }
                            }
                        } else {
                            finalAttachments.addAll(rawAttachments)
                        }
                    } else {
                        finalAttachments.addAll(rawAttachments)
                    }

                    val isChatOpen = YnnkMsgApplication.activeChatEmail?.lowercase() == effectiveEmail.lowercase()

                    messageDao.insert(Message(
                        userId = userId,
                        contactEmail = effectiveEmail,
                        authorEmail = effectiveAuthorEmail,
                        text = finalText,
                        isOutgoing = false,
                        timestamp = msg.sentDate.time,
                        attachments = buildAttachmentsJsonFromPaths(finalAttachments),
                        messageId = msgId,
                        isRead = isChatOpen
                    ))
                    newCount++
                    
                    msg.setFlag(Flags.Flag.SEEN, true)
                    if (autoDelete) msg.setFlag(Flags.Flag.DELETED, true)
                }
            }
            
            for ((email, time) in receiveToConfirmMap) {
                if (time > 0) {
                    sendServiceMessage(email, MSGHEADER_TYPE_CONFIRMATION, time.toString())
                }
            }

            Result.success(newCount)
        } catch (e: Exception) {
            Result.failure(e)
        } finally {
            if (shouldCheckSecondary) {
                triggerSecondaryEmailCheck()
            }

            if (providedFolder == null) {
                try { inbox?.close(true) } catch (_: Exception) {}
                try { store?.close() } catch (_: Exception) {}
            } else {
                if (autoDelete && inbox?.isOpen == true && inbox.mode == Folder.READ_WRITE) {
                    try { inbox.expunge() } catch (_: Exception) {}
                }
            }
        }
    }

    private fun triggerSecondaryEmailCheck() {
        val secEmail = SecurePrefs.getSecondaryEmail(context) ?: return
        val secPass = SecurePrefs.getSecondaryPassword(context) ?: return
        val secHost = SecurePrefs.getSecondaryImapHost(context) ?: return
        val secPort = SecurePrefs.getSecondaryImapPort(context)
        val secSsl = SecurePrefs.getSecondaryImapSsl(context)

        // Launch polling in IO scope
        CoroutineScope(Dispatchers.IO).launch {
            db.appEventDao().logEvent("Starting secondary email polling (10s)...")
            var attempts = 0
            while (attempts < 10) {
                attempts++
                Log.d(TAG, "Secondary fetch attempt $attempts")
                val result = fetchSecondaryMessages(secEmail, secPass, secHost, secPort, secSsl)
                if (result.isSuccess) {
                    val count = result.getOrNull() ?: 0
                    if (count > 0) {
                        db.appEventDao().logEvent("Secondary fetch successful: $count new messages. Stopping poll.")
                        break
                    }
                } else {
                    Log.e(TAG, "Secondary fetch error on attempt $attempts", result.exceptionOrNull())
                }
                delay(1000)
            }
            if (attempts >= 10) {
                db.appEventDao().logEvent("Secondary email polling finished after 10 attempts.")
            }
        }
    }

    suspend fun fetchSecondaryMessages(email: String, pass: String, host: String, port: Int, ssl: Boolean): Result<Int> {
        var store: Store? = null
        var inbox: Folder? = null
        try {
            val protocol = if (ssl) "imaps" else "imap"
            val props = Properties().apply {
                put("mail.$protocol.host", host)
                put("mail.$protocol.port", port.toString())
                put("mail.$protocol.connectiontimeout", "10000")
                put("mail.$protocol.timeout", "10000")
                if (ssl) put("mail.$protocol.ssl.enable", "true")
            }
            store = Session.getInstance(props).getStore(protocol)
            store.connect(host, port, email, pass)
            inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_WRITE)
            
            return fetchNewMessages(inbox)
        } catch (e: Exception) {
            return Result.failure(e)
        } finally {
            try { inbox?.close(true) } catch (_: Exception) {}
            try { store?.close() } catch (_: Exception) {}
        }
    }

    // ── Chunk entry parsing ───────────────────────────────────────────────────

    /**
     * Parses chunk entries from content like "1/3;0/2;2/4".
     * Returns a list of (chunkIndex, totalChunks) per file.
     * chunkIndex == 0 means no data for that file in this message.
     */
    private fun parseChunkEntries(content: String): List<Pair<Int, Int>> =
        content.split(';').map { entry ->
            val trimmed = entry.trim()
            if (trimmed.contains('/')) {
                val slash = trimmed.indexOf('/')
                val a = trimmed.substring(0, slash).trim().toIntOrNull() ?: 0
                val b = trimmed.substring(slash + 1).trim().toIntOrNull() ?: 0
                a to b
            } else {
                0 to 0
            }
        }

    /**
     * Saves the attachment parts of a chunk data message to a temporary directory.
     * Non-zero entries in [chunkEntries] correspond to attachments in order.
     * Returns list of (originalFileName, tempFilePath) for non-zero entries.
     */
    private fun saveChunkAttachments(
        msg: javax.mail.Message,
        fromEmail: String,
        timestamp: String,
        chunkEntries: List<Pair<Int, Int>>
    ): List<Pair<String, String>> {
        val attachParts = mutableListOf<Part>()
        collectAttachmentParts(msg, attachParts)

        val result = mutableListOf<Pair<String, String>>()
        var attachIdx = 0
        chunkEntries.forEachIndexed { fileIdx, (chunkIdx, _) ->
            if (chunkIdx > 0 && attachIdx < attachParts.size) {
                val part = attachParts[attachIdx++]
                saveChunkToTemp(part, fromEmail, timestamp, fileIdx, chunkIdx)?.let {
                    result.add(it)
                }
            }
        }
        return result
    }

    private fun collectAttachmentParts(part: Part, result: MutableList<Part>) {
        if (part.isMimeType("multipart/*")) {
            val mp = part.content as Multipart
            for (i in 0 until mp.count) collectAttachmentParts(mp.getBodyPart(i), result)
        } else if (part.disposition?.equals(Part.ATTACHMENT, ignoreCase = true) == true) {
            result.add(part)
        }
    }

    /**
     * Saves a single chunk attachment to a temp location under
     * externalFilesDir/chunk_temp/{safeEmail}/{timestamp}/f{fileIdx}_c{chunkIdx}_{name}.
     * Returns (originalFileName, tempFilePath) or null on failure.
     */
    private fun saveChunkToTemp(
        part: Part,
        fromEmail: String,
        timestamp: String,
        fileIdx: Int,
        chunkIdx: Int
    ): Pair<String, String>? {
        return try {
            val originalName = MimeUtility.decodeText(part.fileName ?: "file_$fileIdx")
            val safeEmail = fromEmail.replace(Regex("[^a-zA-Z0-9]"), "_")
            val chunkDir = File(
                context.getExternalFilesDir(null),
                "chunk_temp/$safeEmail/$timestamp"
            ).apply { mkdirs() }
            val tempFile = File(chunkDir, "f${fileIdx}_c${chunkIdx}_$originalName")
            part.inputStream.use { input -> tempFile.outputStream().use { input.copyTo(it) } }
            originalName to tempFile.absolutePath
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save chunk f${fileIdx}_c${chunkIdx}", e)
            null
        }
    }

    // ── Contact helper ────────────────────────────────────────────────────────

    private suspend fun ensureContactExists(userId: Long, normalizedFrom: String) {
        val contact = contactDao.getContactByEmail(userId, normalizedFrom)
        if (contact == null) {
            if (AppPrefs.isAutoAddContactsEnabled(context)) {
                contactDao.insert(Contact(userId = userId, email = normalizedFrom, name = normalizedFrom))
            } else {
                AppPrefs.addMessagingRequest(context, normalizedFrom)
            }
        }
    }

    // ── Body parsing & decoding ──────────────────────────────────

    private fun decodeBody(raw: String): DecodedBody {
        var retText = ""
        var retFormat = "raw"
        if (raw.startsWith(MSGBODY_HEADER_START)) {
            retText = raw;//.substring(MSGBODY_HEADER_START.length)
        }
        else {
            try {
                val decodedBytes = Base26Encoder.decode(raw, context)
                val text = decodedBytes.toString(Charsets.UTF_8)
                if (text.startsWith(MSGBODY_HEADER_START)) {
                    retFormat = "base26"
                    retText = text;//.substring(MSGBODY_HEADER_START.length)
                }
                else {
                    retText = raw

                    val privateKey = SecurePrefs.getPgpPrivateKey(context)
                    if (privateKey != null) {
                        val decrypted = PgpUtils.decrypt(decodedBytes, privateKey)
                        if (decrypted != null) {
                            retText = decrypted
                            retFormat = "pgp"
                        }
                    }
                }
            } catch (e: Exception) {
                retText = raw
            }
        }

        if (retText.startsWith(MSGBODY_HEADER_START)) {
            val endBracketIdx = retText.indexOf("}")
            if (endBracketIdx != -1) {
                var jsonBlock = retText.substring(MSGBODY_HEADER_START.length - 1, endBracketIdx + 1)
                val remainingText = retText.substring(endBracketIdx + 1)

                // symbol screening
                jsonBlock = jsonBlock.replace("\\1", "}")
                jsonBlock = jsonBlock.replace("\\\\", "\\")

                return try {
                    val params = gson.fromJson(jsonBlock, MessageParams::class.java)
                    DecodedBody(remainingText, retFormat, params)
                } catch (e: Exception) {
                    DecodedBody(retText, retFormat)
                }
            }
        }

        return DecodedBody(retText, retFormat)
    }

    private fun parseMessage(part: Part): Pair<String, List<String>> {
        val text = StringBuilder()
        val paths = mutableListOf<String>()
        if (part.isMimeType("text/plain") && part.disposition == null) {
            text.append(part.content as String)
        } else if (part.isMimeType("multipart/*")) {
            val mp = part.content as Multipart
            for (i in 0 until mp.count) {
                val (pText, pPaths) = parseMessage(mp.getBodyPart(i))
                text.append(pText)
                paths.addAll(pPaths)
            }
        } else if (part.disposition != null) {
            saveAttachment(part)?.let { paths.add(it) }
        }
        return Pair(text.toString().trim(), paths)
    }

    private fun saveAttachment(part: Part): String? {
        return try {
            val name = MimeUtility.decodeText(part.fileName ?: "file_${System.currentTimeMillis()}")
            val dir = File(context.getExternalFilesDir(null), "attachments").apply { mkdirs() }
            val file = File(dir, name)
            part.inputStream.use { input -> file.outputStream().use { out -> input.copyTo(out) } }
            file.absolutePath
        } catch (e: Exception) { null }
    }

    private fun checkMailboxQuotaSafe(store: Store): Pair<Long, Long>? {
        return try {
            val getQuota = store.javaClass.getMethod("getQuota", String::class.java)
            @Suppress("UNCHECKED_CAST")
            val quotas = getQuota.invoke(store, "INBOX") as? Array<*> ?: return null
            for (quota in quotas) {
                val resources = quota?.javaClass?.getField("resources")?.get(quota) as? Array<*> ?: continue
                for (resource in resources) {
                    val name = resource?.javaClass?.getField("name")?.get(resource) as? String
                    if (name.equals("STORAGE", ignoreCase = true)) {
                        val usage = resource!!.javaClass.getField("usage").getLong(resource)
                        val limit = resource!!.javaClass.getField("limit").getLong(resource)
                        return Pair(usage, limit)
                    }
                }
            }
            null
        } catch (e: Exception) { null }
    }

    // ── User info exchange ────────────────────────────────────────

    private suspend fun handleGetUserInfo(fromEmail: String, msgBody: String, attachments: List<String>) {
        val userId = SecurePrefs.getActiveUserId(context)
        if (userId == -1L) return

        val contact = contactDao.getContactByEmail(userId, fromEmail)
        if (contact == null) {
            db.appEventDao().logEvent("Rejecting SUBJECT_GET_USER_INFO from unknown sender: $fromEmail.")
            return
        }

        try {
            val info = gson.fromJson(msgBody, UserInfoExchange::class.java)
            updateContactWithInfo(userId, fromEmail, info, attachments)
        } catch (e: Exception) {
            db.appEventDao().logEvent("Rejecting GET_USER_INFO (unknown data format): $msgBody.")
        }
        val user = userDao.getUserById(userId)
        val avatarPath = user?.avatarPath
        val hasAvatar = avatarPath != null && File(avatarPath).exists()
        val exchange = UserInfoExchange(
            hasAvatar = hasAvatar,
            secureEmail = SecurePrefs.getSecondaryEmail(context),
            exclusivePrimaryEmail = user?.exclusivePrimaryEmail ?: false,
            publicName = user?.name
        )
        val json = gson.toJson(exchange)
        //val encodedBody = Base26Encoder.encodeText(json)
        val avatarFile = if (hasAvatar) File(avatarPath) else null

        db.appEventDao().logEvent("Service message GET_USER_INFO from $fromEmail.")

        sendServiceMessage(fromEmail, MSGHEADER_TYPE_USERINFO_RESP, json, avatarFile)
    }

    private suspend fun handleUserInfoReceived(fromEmail: String, msgBody: String, attachments: List<String>) {
        val userId = SecurePrefs.getActiveUserId(context)
        if (userId == -1L) return

        try {
            db.appEventDao().logEvent("Service message USER_INFO from $fromEmail.")
            // msgBody is already decoded by decodeBody() — parse JSON directly
            val info = gson.fromJson(msgBody, UserInfoExchange::class.java)
            updateContactWithInfo(userId, fromEmail, info, attachments)
        } catch (e: Exception) {
            db.appEventDao().logEvent("Rejecting USER_INFO (unknown data format): $msgBody.")
        }
    }

    private suspend fun updateContactWithInfo(userId: Long, email: String, info: UserInfoExchange, attachments: List<String>) {
        val contact = contactDao.getContactByEmail(userId, email)
        if (contact != null) {
            // Create a mutable copy to track changes
            var updated = contact

            // 1. Update secure (secondary) email
            if (!info.secureEmail.isNullOrEmpty()) {
                updated = updated.copy(secondaryEmail = info.secureEmail)
            }

            // 2. Update public name
            if (!info.publicName.isNullOrEmpty()) {
                val prevName = updated.name
                updated = updated.copy(name = info.publicName ?: prevName)
            }

            // 3. Update 'is exclusive email' flag
            updated = updated.copy(exclusivePrimaryEmail = info.exclusivePrimaryEmail)

            // 4. Handle avatar
            val imagePath = attachments.firstOrNull {
                it.lowercase().let { l -> l.endsWith(".jpg") || l.endsWith(".png") }
            }

            if (imagePath != null) {
                val sourceFile = File(imagePath)
                if (sourceFile.exists()) {
                    val safeEmail = email.replace(Regex("[^a-zA-Z0-9]"), "_")
                    val avatarDir = File(context.getExternalFilesDir(null), "avatars/$safeEmail").apply { mkdirs() }

                    // Use a timestamp or unique name to force UI refresh (optional but recommended)
                    val ext = sourceFile.extension.let { if (it.isNotEmpty()) ".$it" else "jpg" }
                    val destFile = File(avatarDir, "avatar_${System.currentTimeMillis()}.$ext")

                    try {
                        // Delete old avatar file if it exists to save space
                        contact.avatarPath?.let { oldPath ->
                            val oldFile = File(oldPath)
                            if (oldFile.exists()) oldFile.delete()
                        }

                        sourceFile.copyTo(destFile, overwrite = true)
                        updated = updated.copy(avatarPath = destFile.absolutePath)
                    } catch (e: Exception) {
                        Log.e(TAG, "Failed to copy avatar for $email", e)
                        // If copy fails, we keep the old path or use the new temp path
                        updated = updated.copy(avatarPath = imagePath)
                    }
                }
            }

            contactDao.insert(updated)
            db.appEventDao().logEvent("Updated contact info for $email")
        }
        else
        {
            db.appEventDao().logEvent("Can't update contact details for $email.")
        }
    }

    suspend fun requestRemoteAvatar(email: String) {
        val userId = SecurePrefs.getActiveUserId(context)
        if (userId == -1L) return
        val user = userDao.getUserById(userId)
        val avatarPath = user?.avatarPath
        val hasAvatar = avatarPath != null && File(avatarPath).exists()
        val exchange = UserInfoExchange(
            hasAvatar = hasAvatar,
            secureEmail = SecurePrefs.getSecondaryEmail(context),
            exclusivePrimaryEmail = user?.exclusivePrimaryEmail ?: false,
            publicName = user?.name
        )
        val json = gson.toJson(exchange)
        //val encodedBody = Base26Encoder.encodeText(json)
        val avatarFile = if (hasAvatar) File(avatarPath) else null
        sendServiceMessage(email, MSGHEADER_TYPE_USERINFO, json, avatarFile)
    }

    // ── PGP Key exchange ────────────────────────────────────────

    suspend fun exchangePgpKeys(email: String) {
        val userId = SecurePrefs.getActiveUserId(context)
        if (userId == -1L) return

        val myPublicKey = SecurePrefs.getPgpPublicKey(context) ?: return
        val contact = contactDao.getContactByEmail(userId, email)
        
        val targetEmail = contact?.secondaryEmail ?: email
        
        sendServiceMessage(targetEmail, MSGHEADER_TYPE_PGPKEY, myPublicKey, null)
        
        if (!contact?.secondaryEmail.isNullOrEmpty()) {
            sendServiceMessage(email, MSGHEADER_TYPE_SECONDEMAIL, "", null)
        }
        
        db.appEventDao().logEvent("PGP key exchange request sent to $targetEmail")
    }

    private suspend fun handleGetPgpKey(fromEmail: String, publicKey: String) {
        val userId = SecurePrefs.getActiveUserId(context)
        if (userId == -1L) return

        val contact = contactDao.getContactByEmail(userId, fromEmail)
        if (contact == null) {
            db.appEventDao().logEvent("Rejecting PGP key request from unknown sender: $fromEmail.")
            return
        }

        // Save received key
        contactDao.insert(contact.copy(publicKey = publicKey))
        db.appEventDao().logEvent("PGP public key saved for $fromEmail.")

        // Send my key back
        val myPublicKey = SecurePrefs.getPgpPublicKey(context)
        if (myPublicKey != null) {
            val targetEmail = contact.secondaryEmail ?: fromEmail
            sendServiceMessage(targetEmail, MSGHEADER_TYPE_PGPKEY_RESP, myPublicKey, null)
            
            if (!contact.secondaryEmail.isNullOrEmpty()) {
                sendServiceMessage(fromEmail, MSGHEADER_TYPE_SECONDEMAIL, "", null)
            }
            db.appEventDao().logEvent("PGP public key response sent to $targetEmail. Notification sent to $fromEmail")
        }
    }

    private suspend fun handlePgpKeyReceived(fromEmail: String, publicKey: String) {
        val userId = SecurePrefs.getActiveUserId(context)
        if (userId == -1L) return

        val contact = contactDao.getContactByEmail(userId, fromEmail)
        if (contact != null) {
            contactDao.insert(contact.copy(publicKey = publicKey))
            db.appEventDao().logEvent("PGP public key updated for $fromEmail.")
        }
    }

    private fun buildAttachmentsJsonFromFiles(files: List<File>) =
        files.joinToString(",", "[", "]") { "\"${it.absolutePath}\"" }

    private fun buildAttachmentsJsonFromPaths(paths: List<String>) =
        paths.joinToString(",", "[", "]") { "\"$it\"" }
}

class MailboxFullException(message: String) : Exception(message)
