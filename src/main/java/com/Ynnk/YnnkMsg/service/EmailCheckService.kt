package com.Ynnk.YnnkMsg.service

import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.IBinder
import android.util.Log
import com.sun.mail.imap.IMAPFolder
import com.sun.mail.imap.*
import com.Ynnk.YnnkMsg.YnnkMsgApplication
import com.Ynnk.YnnkMsg.email.EmailService
import com.Ynnk.YnnkMsg.util.EventBus
import com.Ynnk.YnnkMsg.util.SecurePrefs
import kotlinx.coroutines.*
import java.util.Properties
import javax.mail.*
import javax.mail.event.MessageCountAdapter
import javax.mail.event.MessageCountEvent

private const val TAG = "EmailCheckService"

class EmailCheckService : Service() {

    private val scope = CoroutineScope(Dispatchers.IO + SupervisorJob())
    private var idleJob: Job? = null
    private var currentFolder: IMAPFolder? = null
    private var currentStore: Store? = null

    @Volatile
    private var lastActivityTime: Long = System.currentTimeMillis()

    companion object {
        const val ACTION_START_INTENSIVE = "start_intensive"
        const val ACTION_STOP = "stop_service"
        const val ACTION_USER_ACTIVITY = "user_activity"

        @Volatile
        var isRunning: Boolean = false

        private var instance: EmailCheckService? = null

        fun startIntensive(context: Context) {
            context.startService(Intent(context, EmailCheckService::class.java).apply {
                action = ACTION_START_INTENSIVE
            })
        }

        fun stopService(context: Context) {
            context.stopService(Intent(context, EmailCheckService::class.java))
        }

        fun stopIntensive() {
            instance?.stopSelf()
        }

        fun notifyUserActivity(context: Context) {
            if (YnnkMsgApplication.isAppInForeground) {
                context.startService(Intent(context, EmailCheckService::class.java).apply {
                    action = ACTION_USER_ACTIVITY
                })
            }
        }
    }

    override fun onCreate() {
        super.onCreate()
        isRunning = true
        instance = this
        Log.d(TAG, "Service created, setting isRunning = true")
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!YnnkMsgApplication.isAppInForeground && intent?.action != ACTION_STOP) {
            Log.d(TAG, "Ignoring start command: App is in background")
            stopSelf()
            return START_NOT_STICKY
        }

        when (intent?.action) {
            ACTION_STOP -> {
                stopSelf()
                return START_NOT_STICKY
            }
            ACTION_USER_ACTIVITY -> {
                lastActivityTime = System.currentTimeMillis()
                Log.d(TAG, "User activity registered, polling interval reset")
            }
            else -> {
                lastActivityTime = System.currentTimeMillis()
                startIdleLoop()
            }
        }

        return START_NOT_STICKY
    }

    private suspend fun checkSecondaryEmailOnce(emailService: EmailService)
    {
        val secEmail = SecurePrefs.getSecondaryEmail(applicationContext) ?: return
        val secPass = SecurePrefs.getSecondaryPassword(applicationContext) ?: return
        val secHost = SecurePrefs.getSecondaryImapHost(applicationContext) ?: return
        val secPort = SecurePrefs.getSecondaryImapPort(applicationContext)
        val secSsl = SecurePrefs.getSecondaryImapSsl(applicationContext)

        /*
        var store: Store? = null
        var inbox: Folder? = null
        try {
            val protocol = if (secSsl) "imaps" else "imap"
            val props = Properties().apply {
                put("mail.$protocol.host", secHost)
                put("mail.$protocol.port", secPort.toString())
                put("mail.$protocol.connectiontimeout", "10000")
                put("mail.$protocol.timeout", "10000")
                if (secSsl) put("mail.$protocol.ssl.enable", "true")
            }
            store = Session.getInstance(props).getStore(protocol)
            store.connect(secHost, secPort, secEmail, secPass)
            inbox = store.getFolder("INBOX")
            inbox.open(Folder.READ_WRITE)

            emailService.fetchNewMessages(inbox)
        } catch (e: Exception) {
        } finally {
            try { inbox?.close(true) } catch (_: Exception) {}
            try { store?.close() } catch (_: Exception) {}
        }
        */
        emailService.fetchSecondaryMessages(secEmail, secPass, secHost, secPort, secSsl)
    }

    private fun adaptivePollingInterval(): Long {
        val elapsed = System.currentTimeMillis() - lastActivityTime
        return when {
            elapsed < 30_000L    ->  5_000L
            elapsed < 180_000L   -> 15_000L
            elapsed < 900_000L   -> 45_000L
            else                 -> 180_000L
        }
    }

    private fun startIdleLoop() {
        if (idleJob?.isActive == true) return

        idleJob = scope.launch {
            val emailService = EmailService(applicationContext)

            // try to check secure email on launch
            checkSecondaryEmailOnce(emailService)
            
            while (isActive) {
                try {
                    val userEmail = SecurePrefs.getEmail(applicationContext) ?: throw Exception("No email")
                    val password = SecurePrefs.getPassword(applicationContext) ?: throw Exception("No password")
                    val host = SecurePrefs.getImapHost(applicationContext) ?: throw Exception("No host")
                    val port = SecurePrefs.getImapPort(applicationContext)
                    val ssl = SecurePrefs.getImapSsl(applicationContext)

                    val protocol = if (ssl) "imaps" else "imap"
                    val props = Properties().apply {
                        put("mail.$protocol.host", host)
                        put("mail.$protocol.port", port.toString())
                        put("mail.$protocol.connectiontimeout", "15000")
                        put("mail.$protocol.timeout", "15000")
                        put("mail.$protocol.idletimeout", "600000")
                        if (ssl) {
                            put("mail.$protocol.ssl.enable", "true")
                        }
                    }

                    val session = Session.getInstance(props)
                    val store = session.getStore(protocol)
                    currentStore = store
                    store.connect(host, port, userEmail, password)

                    val folder = store.getFolder("INBOX")
                    folder.open(Folder.READ_WRITE)
                    val imapFolder = folder as IMAPFolder
                    currentFolder = imapFolder

                    // Initial sync
                    emailService.fetchNewMessages(imapFolder)

                    val imapStore = store as IMAPStore
                    var idleSupported = imapStore.hasCapability("IDLE")

                    while (isActive) {
                        if (idleSupported) {
                            try {
                                Log.d(TAG, "Entering IMAP IDLE...")
                                withTimeoutOrNull(20 * 60 * 1000L) { // 20 minutes timeout
                                    imapFolder.idle(true)
                                }
                                Log.d(TAG, "IDLE returned, fetching...")
                                val result = emailService.fetchNewMessages(imapFolder)
                                result.onSuccess { count ->
                                    if (count > 0) lastActivityTime = System.currentTimeMillis()
                                }
                            } catch (e: MessagingException) {
                                if (e.message?.contains("IDLE not supported", ignoreCase = true) == true) {
                                    idleSupported = false
                                } else {
                                    throw e
                                }
                            }
                        } else {
                            if (isActive) {
                                val result = emailService.fetchNewMessages(imapFolder)
                                result.onSuccess { count ->
                                    if (count > 0) lastActivityTime = System.currentTimeMillis()
                                }
                                val interval = adaptivePollingInterval()
                                delay(interval)
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.e(TAG, "IMAP loop error: ${e.message}")
                    if (isActive) delay(10_000)
                } finally {
                    cleanupConnection()
                }
            }
        }
        
        idleJob?.invokeOnCompletion {
            cleanupConnection()
        }
    }

    private fun cleanupConnection() {
        try { currentFolder?.close(false) } catch (e: Exception) {}
        try { currentStore?.close() } catch (e: Exception) {}
        idleJob?.cancel()
        currentFolder = null
        currentStore = null
    }

    override fun onDestroy() {
        Log.d(TAG, "Service stopped, allowing Worker to run")
        isRunning = false
        instance = null
        cleanupConnection()
        scope.cancel()
        super.onDestroy()
    }

    override fun onBind(intent: Intent?): IBinder? = null
}
