package com.dsprenkels.codimdshare

import android.app.Activity
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Intent
import android.net.Uri
import android.os.*
import android.preference.PreferenceManager
import android.support.v7.app.AppCompatActivity
import android.util.Log
import com.google.gson.Gson
import com.google.gson.JsonParseException
import okhttp3.*
import java.net.URL
import java.io.*
import android.content.Context.CLIPBOARD_SERVICE
import android.widget.Toast


class ShareActivity : AppCompatActivity() {
    private val handler = MessageHandler(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_share)

        when {
            (intent?.action != Intent.ACTION_SEND) -> {
                Log.e(this::class.java.name, "unhandled intent action: ${intent?.action}")
            }
            (intent.type?.startsWith("image/") != true) -> {
                Log.e(this::class.java.name, "unhandled intent type: ${intent?.type}")
            }
            else -> {
                val task = UploadImageIntentHandler(this, intent)
                AsyncTask.execute(task)
            }
        }
    }

    private fun handleUploadSuccess(message: Message) {
        val imageLink = (message.obj as UploadSuccessMessage).link
        val clipData = ClipData.newUri(null, imageLink, Uri.parse(imageLink))
        val clipboard = this.getSystemService(CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.primaryClip = clipData

        Toast.makeText(this, "Link copied to clipboard", Toast.LENGTH_SHORT).show()

        this.finish()
    }

    companion object {
        private class UploadImageIntentHandler(private val activity: ShareActivity, private val intent: Intent) : Runnable {
            private val client = OkHttpClient()
            private val gson = Gson();

            override fun run() {
                (intent.getParcelableExtra<Parcelable>(Intent.EXTRA_STREAM) as? Uri)?.let { uri ->

                    // Load the file
                    val filename = uri.pathSegments.last()
                    val parcelFD = try {
                        activity.contentResolver.openFileDescriptor(uri, "r")
                    } catch (e: FileNotFoundException) {
                        e.printStackTrace()
                        Log.e(this::class.java.name, "File at $uri not found")
                        return
                    }
                    val fd = parcelFD.fileDescriptor

                    // Update UI to reflect image being shared
                    Log.i(this::class.java.name, "fd is: $fd")

                    // Compose the post url
                    val preferences = PreferenceManager.getDefaultSharedPreferences(activity)
                    val baseUrl = preferences.getString("base_url", null)
                    if (baseUrl == null) {
                        Log.e(this::class.java.name, "Base URL not set")
                        return
                    }
                    Log.d(this::class.java.name, "Base URL: $baseUrl")
                    val url = URL("$baseUrl/uploadimage")
                    Log.d(this::class.java.name, "Upload URL: $url")

                    // Open the file and copy it to a temporary file
                    val inputStream = FileInputStream(fd)
                    val extension = filename.substring(filename.lastIndexOf(".") + 1)
                    val mediaType = MediaType.parse(getMimeType(extension))
                    val tempFile = File.createTempFile("codimd-share", ".$extension")
                    val outStream = tempFile.outputStream()
                    val buffer = ByteArray(128 * 1024)
                    while (true) {
                        val bytesRead = inputStream.read(buffer)
                        if (bytesRead == -1) {
                            break
                        }
                        outStream.write(buffer, 0, bytesRead)
                    }
                    inputStream.close()
                    outStream.close()

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
                        Log.e(this::class.java.name, "Upload failed, unexpected code: ${response}")
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
                    val messageObj = UploadSuccessMessage(uploadImageResponse.link)
                    val message = activity.handler.obtainMessage(MessageType.UPLOAD_SUCCESS.value, messageObj)
                    message.sendToTarget()
                }
            }
            internal inner class UploadImageResponse private constructor(val link: String)

            private fun getMimeType(extension: String): String =
                when (extension) {
                    "bmp" -> "image/bmp"
                    "gif" -> "image/gif"
                    "jpg", "jpeg" -> "image/jpeg"
                    "png" -> "image/png"
                    "tiff" -> "image/tiff"
                    else -> "application/octet-stream"
                }
        }

        class MessageHandler(val parent: ShareActivity) : Handler() {
            override fun handleMessage(message: Message?) {
                when (message?.what) {
                    MessageType.UPLOAD_SUCCESS.value -> parent.handleUploadSuccess(message)
                    else -> super.handleMessage(message)
                }
            }
        }

        enum class MessageType(val value: Int) {
            UPLOAD_SUCCESS(1)
        }
        class UploadSuccessMessage(val link: String)
    }
}
