package com.dsprenkels.codimdshare

import android.app.PendingIntent
import android.content.*
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.*
import android.preference.PreferenceManager
import android.support.v4.app.NotificationCompat
import android.support.v4.app.NotificationManagerCompat
import android.support.v7.app.AppCompatActivity
import android.util.Log
import android.webkit.MimeTypeMap
import android.widget.Toast
import com.google.gson.Gson
import com.google.gson.JsonParseException
import okhttp3.*
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.net.URL


class ShareActivity : AppCompatActivity() {
    private val handler = MessageHandler(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share)

        if (intent?.action != Intent.ACTION_SEND) {
            Log.e(this::class.java.name, "unsupported intent action: ${intent?.action}")
            return
        }
        val task = UploadImageIntentHandler(this, intent)
        AsyncTask.execute(task)
    }

    private fun handleUploadSuccess(msg: UploadSuccessMessage) {
        val preferences = PreferenceManager.getDefaultSharedPreferences(this)
        if (preferences.getBoolean("clipboard_copy", false)) {
            copyToClipBoard(this, msg.link)
        }
        if (preferences.getBoolean("show_notification", true)) {
            showNotification(msg)
        }
        this.finish()
    }

    private fun handleUploadFail(msg: UploadFailMessage) {
        showNotification(msg)
        this.finish()
    }

    private fun showNotification(msg: UploadSuccessMessage) {
        val requestCode = msg.link.hashCode()
        val copyIntent = Intent(this, CopyToClipboardReceiver::class.java).apply {
            action = ACTION_COPY_TO_CLIPBOARD
            putExtra(EXTRA_LINK, msg.link)
        }
        val pendingCopyIntent: PendingIntent = PendingIntent.getBroadcast(this, requestCode, copyIntent, 0)
        val browserIntent = Intent(this, OpenBrowserReceiver::class.java).apply {
            action = ACTION_OPEN_LINK
            putExtra(EXTRA_LINK, msg.link)
        }
        val pendingBrowserIntent = PendingIntent.getBroadcast(this, requestCode, browserIntent, 0)
        val notification = NotificationCompat.Builder(this, "codimd-share-channel").apply {
            setSmallIcon(R.drawable.ic_file_upload)
            setContentTitle(getString(R.string.notification_upload_success))
            priority = NotificationCompat.PRIORITY_DEFAULT
            setCategory(NotificationCompat.CATEGORY_PROGRESS)
            setLargeIcon(loadBitmap(msg.file))
            addAction(R.drawable.ic_copy_clipboard, getString(R.string.notification_copy_clipboard), pendingCopyIntent)
            setContentIntent(pendingBrowserIntent)
        }.build()
        NotificationManagerCompat.from(this).apply {
            notify(NOTIFICATION_ID_UPLOAD_SUCCESS, notification)
        }
    }

    private fun showNotification(msg: UploadFailMessage) {
        val requestCode = msg.javaClass.hashCode()
        val openSettingsIntent = Intent(this, OpenSettingsReceiver::class.java).apply {
            action = ACTION_OPEN_SETTINGS
        }
        val pendingOpenSettingsIntent = PendingIntent.getBroadcast(this, requestCode, openSettingsIntent, 0)

        val notification = NotificationCompat.Builder(this, "codimd-share-channel").apply {
            setSmallIcon((R.drawable.ic_file_upload))
            setContentTitle(getString(R.string.notification_upload_fail))
            setContentText(
                when (msg.errorType) {
                    UploadErrorType.BASE_URL_UNDEFINED -> getString(R.string.notification_error_base_url_undefined)
                    UploadErrorType.IO_EXCEPTION -> getString(
                        R.string.notification_error_io_exception,
                        msg.ioException?.message
                    )
                    UploadErrorType.MALFORMED_RESPONSE -> getString(
                        R.string.notification_error_malformed_response,
                        msg.response?.body()
                    )
                    UploadErrorType.BAD_STATUS_CODE -> getString(
                        R.string.notification_error_bad_status_code,
                        msg.response?.code()
                    )
                }
            )
            priority = NotificationCompat.PRIORITY_DEFAULT
            setCategory(NotificationCompat.CATEGORY_ERROR)
            if (msg.file !== null) {
                setLargeIcon(loadBitmap(msg.file))
            }
            addAction(
                R.drawable.ic_open_settings,
                getString(R.string.notification_open_settings),
                pendingOpenSettingsIntent
            )
            setAutoCancel(true)
        }.build()
        NotificationManagerCompat.from(this).apply {
            notify(NOTIFICATION_ID_UPLOAD_FAIL, notification)
        }
    }

    class CopyToClipboardReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            Log.d(this::class.java.name, "onReceive was called")
            if (intent?.action != ACTION_COPY_TO_CLIPBOARD) {
                Log.e(this::class.java.name, "unsupported intent action: ${intent?.action}")
                return
            }
            val extra = intent.extras.get(EXTRA_LINK)
            if (extra == null) {
                Log.e(this::class.java.name, "intent extra is null: $extra")
                return
            }
            val link = extra as String
            Log.d(this::class.java.name, "Copying to clipboard: $link")
            copyToClipBoard(ctx!!, link)
        }
    }

    class OpenBrowserReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            Log.d(this::class.java.name, "onReceive was called")
            if (intent?.action != ACTION_OPEN_LINK) {
                Log.e(this::class.java.name, "unsupported intent action: ${intent?.action}")
                return
            }
            val extra = intent.extras.get(EXTRA_LINK)
            if (extra == null) {
                Log.e(this::class.java.name, "intent extra is null: $extra")
                return
            }
            val link = extra as String
            Log.d(this::class.java.name, "Opening in browser: $link")
            ctx!!.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(link)))
        }
    }

    class OpenSettingsReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            Log.d(this::class.java.name, "onReceive was called")
            if (intent?.action != ACTION_OPEN_SETTINGS) {
                Log.e(this::class.java.name, "unsupported intent action: ${intent?.action}")
                return
            }
            Log.d(this::class.java.name, "Starting SettingsActivity")
            ctx!!.startActivity(Intent(ctx, SettingsActivity::class.java))
            // Remove the notification now
            NotificationManagerCompat.from(ctx).apply {
                cancel(NOTIFICATION_ID_UPLOAD_FAIL)
            }
        }
    }

    private fun loadBitmap(file: File): Bitmap? {
        val options = BitmapFactory.Options()
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        return BitmapFactory.decodeFile(file.path, options)
    }

    companion object {
        const val NOTIFICATION_ID_UPLOAD_SUCCESS = 1
        const val NOTIFICATION_ID_UPLOAD_FAIL = 2
        const val ACTION_COPY_TO_CLIPBOARD = "copy_to_clipboard"
        const val ACTION_OPEN_LINK = "copy_to_clipboard"
        const val ACTION_OPEN_SETTINGS = "open_settings"
        const val EXTRA_LINK = "link"
        private const val COPY_BUFFER_SIZE = 128 * 1024

        private fun copyToClipBoard(ctx: Context, link: String) {
            val clipData = ClipData.newUri(null, link, Uri.parse(link))
            val clipboard = ctx.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.primaryClip = clipData

            Toast.makeText(ctx, "Link copied to clipboard", Toast.LENGTH_SHORT).show()
        }

        private class UploadImageIntentHandler(private val activity: ShareActivity, private val intent: Intent) :
            Runnable {
            private val client = OkHttpClient()
            private val gson = Gson()

            override fun run() {
                (intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)?.let { fileUri ->

                    // Load the file
                    Log.d(this::class.java.name, "fileUri: $fileUri")
                    val parcelFD = try {
                        activity.contentResolver.openFileDescriptor(fileUri, "r")
                    } catch (e: FileNotFoundException) {
                        e.printStackTrace()
                        Log.e(this::class.java.name, "File at $fileUri not found")
                        return@let
                    }
                    val fd = parcelFD.fileDescriptor

                    // Compose the post url
                    val preferences = PreferenceManager.getDefaultSharedPreferences(activity)
                    val baseUrl = preferences.getString("base_url", null)
                    if (baseUrl == null) {
                        Log.e(this::class.java.name, "Base URL not set")
                        val messageObj = UploadFailMessage(UploadErrorType.BASE_URL_UNDEFINED)
                        val message = activity.handler.obtainMessage(MessageType.UPLOAD_FAIL.value, messageObj)
                        message.sendToTarget()
                        return@let
                    }
                    Log.d(this::class.java.name, "Base URL: $baseUrl")
                    val url = URL("$baseUrl/uploadimage")

                    // Resolve file type and extension
                    val mime = MimeTypeMap.getSingleton()
                    val mimeType = activity.contentResolver.getType(fileUri)
                    val extension = mime.getExtensionFromMimeType(mimeType)
                    val mediaType = MediaType.parse(mimeType)

                    // Open the file and copy it to a temporary file
                    val inputStream = FileInputStream(fd)
                    val tempFile = File.createTempFile("codimd-share", ".$extension")
                    val outStream = tempFile.outputStream()
                    val buffer = ByteArray(COPY_BUFFER_SIZE)
                    while (true) {
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead == -1) {
                            break
                        }
                        outStream.write(buffer, 0, bytesRead)
                    }
                    inputStream.close()
                    outStream.close()
                    Log.d(this::class.java.name, "tempFile path: ${tempFile.path} ($mediaType)")

                    // Do the request
                    val requestBody = MultipartBody.Builder()
                        .setType(MultipartBody.FORM)
                        .addFormDataPart(
                            "image", tempFile.path,
                            RequestBody.create(mediaType, tempFile)
                        )
                        .build()
                    // TODO(dsprenkels) Provide a user agent in the headers
                    val request = Request.Builder().url(url).post(requestBody).build()
                    val response = try {
                        client.newCall(request).execute()
                    } catch (error: IOException) {
                        Log.e(this::class.java.name, "Upload failed, request error: $error")
                        val messageObj = UploadFailMessage(UploadErrorType.IO_EXCEPTION, tempFile, ioException = error)
                        val message = activity.handler.obtainMessage(MessageType.UPLOAD_FAIL.value, messageObj)
                        message.sendToTarget()
                        return@let
                    }
                    if (!response.isSuccessful) {
                        Log.e(this::class.java.name, "Upload failed, unexpected code: $response")
                        val messageObj = UploadFailMessage(UploadErrorType.BAD_STATUS_CODE, tempFile, null, response)
                        val message = activity.handler.obtainMessage(MessageType.UPLOAD_FAIL.value, messageObj)
                        message.sendToTarget()
                        return@let
                    }

                    // Decode the response
                    val uploadImageResponse = try {
                        gson.fromJson(response.body()?.string(), UploadImageResponse::class.java)
                    } catch (e: JsonParseException) {
                        Log.e(this::class.java.name, "Malformed upload response: ${response.body().toString()}", e)
                        val messageObj =
                            UploadFailMessage(UploadErrorType.MALFORMED_RESPONSE, tempFile, response = response)
                        val message = activity.handler.obtainMessage(MessageType.UPLOAD_FAIL.value, messageObj)
                        message.sendToTarget()
                        return@let
                    }

                    // Send a message to the UI thread
                    val messageObj = UploadSuccessMessage(uploadImageResponse.link, tempFile)
                    val message = activity.handler.obtainMessage(MessageType.UPLOAD_SUCCESS.value, messageObj)
                    message.sendToTarget()
                    Log.i(this::class.java.name, "Upload successful, received link: ${uploadImageResponse.link}")
                }
            }
        }

        class UploadImageResponse private constructor(val link: String)

        class MessageHandler(val parent: ShareActivity) : Handler() {
            override fun handleMessage(message: Message?) {
                when (message?.what) {
                    MessageType.UPLOAD_SUCCESS.value -> parent.handleUploadSuccess(message.obj as UploadSuccessMessage)
                    MessageType.UPLOAD_FAIL.value -> parent.handleUploadFail(message.obj as UploadFailMessage)
                    else -> super.handleMessage(message)
                }
            }
        }

        enum class MessageType(val value: Int) {
            UPLOAD_SUCCESS(1),
            UPLOAD_FAIL(2),
        }

        enum class UploadErrorType(val value: Int) {
            BASE_URL_UNDEFINED(1),
            IO_EXCEPTION(2),
            BAD_STATUS_CODE(3),
            MALFORMED_RESPONSE(4),
        }

        private class UploadSuccessMessage(val link: String, val file: File)
        private class UploadFailMessage(
            val errorType: UploadErrorType,
            val file: File? = null,
            val ioException: IOException? = null,
            val response: Response? = null
        )
    }
}
