package com.dsprenkels.codimdshare

import android.app.PendingIntent
import android.content.*
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
import java.net.URL
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import android.content.Intent




private const val COPY_BUFFER_SIZE = 128 * 1024

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

    private fun showNotification(msg: UploadSuccessMessage) {
        val copyIntent = Intent(this, CopyToClipboardReceiver::class.java).apply {
            action = ACTION_COPY_TO_CLIPBOARD
            putExtra(EXTRA_LINK, msg.link)
        }
        val pendingCopyIntent: PendingIntent = PendingIntent.getBroadcast(this, 0, copyIntent, 0)
        val browserIntent = Intent(this, OpenBrowserReceiver::class.java).apply {
            action = ACTION_OPEN_LINK
            putExtra(EXTRA_LINK, msg.link)
        }
        val pendingBrowserIntent: PendingIntent = PendingIntent.getBroadcast(this, 0, browserIntent, 0)
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

    class CopyToClipboardReceiver : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            Log.d(this::class.java.name, "onReceive was called")
            if (intent?.action != ACTION_COPY_TO_CLIPBOARD) {
                Log.e(this::class.java.name, "unsupported intent action: ${intent?.action}")
                return
            }
            val extra = intent.extras.get(EXTRA_LINK)
            if (extra == null) {
                Log.e(this::class.java.name, "intent extra is null: ${extra}")
                return
            }
            copyToClipBoard(ctx!!, extra as String)
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
                Log.e(this::class.java.name, "intent extra is null: ${extra}")
                return
            }
            val intent = Intent(Intent.ACTION_VIEW, Uri.parse(extra as String))
            ctx!!.startActivity(intent)
        }
    }

    private fun loadBitmap(file: File): Bitmap? {
        val options = BitmapFactory.Options()
        options.inPreferredConfig = Bitmap.Config.ARGB_8888
        return BitmapFactory.decodeFile(file.path, options)
    }

    companion object {
        const val NOTIFICATION_ID_UPLOAD_SUCCESS = 1
        const val ACTION_COPY_TO_CLIPBOARD = "copy_to_clipboard"
        const val ACTION_OPEN_LINK = "copy_to_clipboard"
        const val EXTRA_LINK = "link"

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
                        return
                    }
                    val fd = parcelFD.fileDescriptor

                    // Compose the post url
                    val preferences = PreferenceManager.getDefaultSharedPreferences(activity)
                    val baseUrl = preferences.getString("base_url", null)
                    if (baseUrl == null) {
                        Log.e(this::class.java.name, "Base URL not set")
                        return
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
                    val response = client.newCall(request).execute()
                    if (!response.isSuccessful) {
                        Log.e(this::class.java.name, "Upload failed, unexpected code: $response")
                        return@let
                    }

                    // Decode the response
                    val uploadImageResponse = try {
                        gson.fromJson(response.body()?.string(), UploadImageResponse::class.java)
                    } catch (e: JsonParseException) {
                        Log.e(this::class.java.name, "Unexpected upload response: ${response.body().toString()}", e)
                        return@let
                    }

                    // Send a message to the UI thread
                    val messageObj = UploadSuccessMessage(uploadImageResponse.link, tempFile)
                    val message = activity.handler.obtainMessage(MessageType.UPLOAD_SUCCESS.value, messageObj)
                    message.sendToTarget()
                    Log.i(this::class.java.name, "Upload successful, received link: ${uploadImageResponse.link}")
                }
            }

            internal inner class UploadImageResponse private constructor(val link: String)
        }

        class MessageHandler(val parent: ShareActivity) : Handler() {
            override fun handleMessage(message: Message?) {
                when (message?.what) {
                    MessageType.UPLOAD_SUCCESS.value -> parent.handleUploadSuccess(message.obj as UploadSuccessMessage)
                    else -> super.handleMessage(message)
                }
            }
        }

        enum class MessageType(val value: Int) {
            UPLOAD_SUCCESS(1)
        }

        private class UploadSuccessMessage(val link: String, val file: File)
    }
}
