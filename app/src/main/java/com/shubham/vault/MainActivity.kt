package com.shubham.vault

import android.content.ContentValues
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import android.view.WindowManager
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.ActivityResultLauncher
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.biometric.BiometricManager
import androidx.biometric.BiometricPrompt
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.webkit.WebViewAssetLoader
import org.json.JSONObject
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * A shell around the vault page. It holds no vault logic of its own.
 *
 * Three things it adds that a browser cannot:
 *   1. No INTERNET permission, so the app cannot transmit anything, ever.
 *   2. FLAG_SECURE, so screenshots and the app-switcher thumbnail are blocked.
 *   3. Fingerprint unlock, backed by this phone's hardware keystore.
 */
class MainActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private var fileCallback: ValueCallback<Array<Uri>>? = null
    private lateinit var filePicker: ActivityResultLauncher<Intent>
    private lateinit var attachPicker: ActivityResultLauncher<Intent>

    /** Set while a system dialog is up, so leaving the activity does not lock mid-task. */
    private var suppressLock = false

    private val prefs by lazy { getSharedPreferences("vault", MODE_PRIVATE) }

    companion object {
        private const val ORIGIN = "https://appassets.androidplatform.net"
        private const val PAGE = "$ORIGIN/assets/vault.html"
        private const val KEY_NAME = "vault_bio_key_v1"
        private const val P_CT = "bio_ct"
        private const val P_IV = "bio_iv"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Block screenshots, screen recording and the recents preview.
        window.setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE)

        filePicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            suppressLock = false
            fileCallback?.onReceiveValue(
                WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
            )
            fileCallback = null
        }

        // The app reads picked files itself and hands the bytes to the page.
        attachPicker = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
            suppressLock = false
            val data = result.data
            if (result.resultCode != RESULT_OK || data == null) {
                web.evaluateJavascript("window.__filesDone && window.__filesDone(0)", null)
            } else {
                val uris = ArrayList<Uri>()
                data.clipData?.let { clip -> for (i in 0 until clip.itemCount) uris.add(clip.getItemAt(i).uri) }
                if (uris.isEmpty()) data.data?.let { uris.add(it) }
                Thread { deliverFiles(uris) }.start()
            }
        }

        // Serving the page from a real https origin (intercepted locally, no network)
        // is what makes Web Crypto and IndexedDB available inside a WebView.
        val loader = WebViewAssetLoader.Builder()
            .setDomain("appassets.androidplatform.net")
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(this))
            .build()

        web = WebView(this)
        web.settings.apply {
            javaScriptEnabled = true
            domStorageEnabled = true
            databaseEnabled = true
            allowFileAccess = false
            allowContentAccess = true
            mediaPlaybackRequiresUserGesture = true
            setSupportZoom(false)
        }
        WebView.setWebContentsDebuggingEnabled(false)

        web.webViewClient = object : WebViewClient() {
            override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
                loader.shouldInterceptRequest(request.url)

            // Anything that is not our own page is refused outright.
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean =
                !request.url.toString().startsWith(ORIGIN)
        }

        web.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                view: WebView,
                callback: ValueCallback<Array<Uri>>,
                params: FileChooserParams
            ): Boolean {
                fileCallback?.onReceiveValue(null)
                fileCallback = callback
                return try {
                    suppressLock = true
                    // Built by hand so that picking several files at once always works.
                    val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf"))
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                    filePicker.launch(intent)
                    true
                } catch (e: Exception) {
                    suppressLock = false
                    fileCallback = null
                    false
                }
            }
        }

        web.addJavascriptInterface(Bridge(), "AndroidVault")
        web.loadUrl(PAGE)
        setContentView(web)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                web.evaluateJavascript("(window.vaultBack ? window.vaultBack() : false)") { handled ->
                    if (handled != "true") finish()
                }
            }
        })
    }

    /** Leaving the app locks the vault, unless a system picker or prompt caused it. */
    override fun onPause() {
        super.onPause()
        if (!suppressLock) {
            web.evaluateJavascript("window.vaultLock && window.vaultLock()", null)
            clearSharedFiles()
        }
    }

    /** Removes any decrypted copy handed to a viewer app. */
    private fun clearSharedFiles() {
        try { File(cacheDir, "share").listFiles()?.forEach { it.delete() } } catch (e: Exception) { }
    }

    override fun onDestroy() {
        web.destroy()
        super.onDestroy()
    }

    private fun toJs(script: String) = runOnUiThread { web.evaluateJavascript(script, null) }

    // =================================================================
    //  Bridge exposed to the page as window.AndroidVault
    // =================================================================
    inner class Bridge {

        /** Writes a backup or an attachment into Downloads/Vault. */
        @JavascriptInterface
        fun saveFile(name: String, base64: String, mime: String) {
            try {
                val bytes = Base64.decode(base64, Base64.DEFAULT)
                val values = ContentValues().apply {
                    put(MediaStore.Downloads.DISPLAY_NAME, name)
                    put(MediaStore.Downloads.MIME_TYPE, if (mime.isBlank()) "application/octet-stream" else mime)
                    put(MediaStore.Downloads.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/Vault")
                    put(MediaStore.Downloads.IS_PENDING, 1)
                }
                val resolver = contentResolver
                val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                    ?: throw IllegalStateException("no uri")
                resolver.openOutputStream(uri).use { out ->
                    out?.write(bytes) ?: throw IllegalStateException("no stream")
                }
                values.clear()
                values.put(MediaStore.Downloads.IS_PENDING, 0)
                resolver.update(uri, values, null, null)
                runOnUiThread { Toast.makeText(this@MainActivity, "Saved to Downloads/Vault/$name", Toast.LENGTH_LONG).show() }
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this@MainActivity, "Could not save the file", Toast.LENGTH_LONG).show() }
            }
        }

        /** Opens the system file picker. Several files can be chosen at once. */
        @JavascriptInterface
        fun pickFiles() {
            runOnUiThread {
                try {
                    suppressLock = true
                    attachPicker.launch(Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                        addCategory(Intent.CATEGORY_OPENABLE)
                        type = "*/*"
                        putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("image/*", "application/pdf"))
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    })
                } catch (e: Exception) {
                    suppressLock = false
                    Toast.makeText(this@MainActivity, "No file picker on this phone", Toast.LENGTH_LONG).show()
                }
            }
        }

        /**
         * Writes one attachment to a private cache file and hands it to whatever
         * app can display it. The copy is deleted as soon as you leave the vault.
         */
        @JavascriptInterface
        fun openFile(name: String, base64: String, mime: String) {
            runOnUiThread {
                try {
                    val dir = File(cacheDir, "share").apply { mkdirs() }
                    val safe = name.replace(Regex("[^A-Za-z0-9._-]"), "_").take(60)
                    val out = File(dir, if (safe.isBlank()) "file" else safe)
                    out.writeBytes(Base64.decode(base64, Base64.DEFAULT))
                    val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", out)
                    val view = Intent(Intent.ACTION_VIEW).apply {
                        setDataAndType(uri, if (mime.isBlank()) "*/*" else mime)
                        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                    }
                    suppressLock = true          // coming straight back should not force a re-unlock
                    startActivity(Intent.createChooser(view, "Open with"))
                } catch (e: Exception) {
                    suppressLock = false
                    Toast.makeText(this@MainActivity, "No app on this phone can open that file", Toast.LENGTH_LONG).show()
                }
            }
        }

        /** Is there usable fingerprint or face hardware with something enrolled? */
        @JavascriptInterface
        fun biometricAvailable(): Boolean =
            BiometricManager.from(this@MainActivity)
                .canAuthenticate(BiometricManager.Authenticators.BIOMETRIC_STRONG) == BiometricManager.BIOMETRIC_SUCCESS

        /** Has the user already wrapped their master password with it? */
        @JavascriptInterface
        fun biometricEnrolled(): Boolean = biometricAvailable() && prefs.contains(P_CT)

        @JavascriptInterface
        fun disableBiometric() {
            prefs.edit().remove(P_CT).remove(P_IV).apply()
            try {
                KeyStore.getInstance("AndroidKeyStore").apply { load(null) }.deleteEntry(KEY_NAME)
            } catch (e: Exception) { /* key already gone */ }
        }

        @JavascriptInterface
        fun enableBiometric(password: String) = runOnUiThread { wrapPassword(password) }

        @JavascriptInterface
        fun unlockBiometric() = runOnUiThread { unwrapPassword() }
    }

    // =================================================================
    //  Reading picked files
    //  Photos are shrunk here rather than in the page, so a 9 MB camera
    //  shot never has to cross into JavaScript at full size.
    // =================================================================
    private fun deliverFiles(uris: List<Uri>) {
        var added = 0
        for (uri in uris) {
            try {
                val original = displayName(uri)
                val mime = contentResolver.getType(uri) ?: "application/octet-stream"
                var name = original
                var type = mime
                var bytes: ByteArray

                if (mime.startsWith("image/")) {
                    val small = shrinkImage(uri)
                    if (small != null) {
                        bytes = small
                        type = "image/jpeg"
                        name = original.substringBeforeLast('.', original) + ".jpg"
                    } else bytes = readAll(uri)
                } else bytes = readAll(uri)

                if (bytes.size > 4_000_000) {
                    runOnUiThread { Toast.makeText(this, "$original is too large, skipped", Toast.LENGTH_LONG).show() }
                    continue
                }

                val o = JSONObject()
                o.put("name", name)
                o.put("type", type)
                o.put("size", bytes.size)
                o.put("data", Base64.encodeToString(bytes, Base64.NO_WRAP))
                val js = "window.__filePicked && window.__filePicked(" + JSONObject.quote(o.toString()) + ")"
                runOnUiThread { web.evaluateJavascript(js, null) }
                added++
            } catch (e: Exception) {
                runOnUiThread { Toast.makeText(this, "One file could not be read", Toast.LENGTH_LONG).show() }
            }
        }
        val n = added
        runOnUiThread { web.evaluateJavascript("window.__filesDone && window.__filesDone($n)", null) }
    }

    private fun displayName(uri: Uri): String {
        try {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
                if (c.moveToFirst()) {
                    val i = c.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    if (i >= 0) c.getString(i)?.let { return it }
                }
            }
        } catch (e: Exception) { }
        return uri.lastPathSegment?.substringAfterLast('/') ?: "file"
    }

    private fun readAll(uri: Uri): ByteArray =
        contentResolver.openInputStream(uri).use { it?.readBytes() ?: ByteArray(0) }

    private fun shrinkImage(uri: Uri): ByteArray? {
        return try {
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, bounds) }
            var sample = 1
            val longest = maxOf(bounds.outWidth, bounds.outHeight)
            if (longest <= 0) return null
            while (longest / sample > 2000) sample *= 2
            val opts = BitmapFactory.Options().apply { inSampleSize = sample }
            val bmp = contentResolver.openInputStream(uri).use { BitmapFactory.decodeStream(it, null, opts) } ?: return null
            val out = ByteArrayOutputStream()
            bmp.compress(Bitmap.CompressFormat.JPEG, 82, out)
            bmp.recycle()
            out.toByteArray()
        } catch (e: Exception) { null }
    }

    // =================================================================
    //  Keystore. The master password is sealed by a hardware key that
    //  only a verified fingerprint can use, and that the phone destroys
    //  if a new fingerprint is enrolled.
    // =================================================================
    private fun secretKey(create: Boolean): SecretKey? {
        val ks = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (ks.getKey(KEY_NAME, null) as? SecretKey)?.let { return it }
        if (!create) return null
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        val spec = KeyGenParameterSpec.Builder(
            KEY_NAME,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        )
            .setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setUserAuthenticationRequired(true)
            .setInvalidatedByBiometricEnrollment(true)
            .apply {
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                    setUserAuthenticationParameters(0, KeyProperties.AUTH_BIOMETRIC_STRONG)
                }
            }
            .build()
        gen.init(spec)
        return gen.generateKey()
    }

    private fun prompt(title: String, subtitle: String, cipher: Cipher, onOk: (Cipher) -> Unit, onFail: (String) -> Unit) {
        suppressLock = true
        val bp = BiometricPrompt(this, ContextCompat.getMainExecutor(this),
            object : BiometricPrompt.AuthenticationCallback() {
                override fun onAuthenticationSucceeded(result: BiometricPrompt.AuthenticationResult) {
                    suppressLock = false
                    val c = result.cryptoObject?.cipher
                    if (c == null) onFail("The phone did not return a usable key.") else onOk(c)
                }
                override fun onAuthenticationError(code: Int, msg: CharSequence) {
                    suppressLock = false
                    onFail(msg.toString())
                }
            })
        bp.authenticate(
            BiometricPrompt.PromptInfo.Builder()
                .setTitle(title)
                .setSubtitle(subtitle)
                .setNegativeButtonText("Use password")
                .setConfirmationRequired(false)
                .build(),
            BiometricPrompt.CryptoObject(cipher)
        )
    }

    private fun wrapPassword(password: String) {
        try {
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.ENCRYPT_MODE, secretKey(true))
            prompt("Turn on fingerprint unlock", "Confirm it is you", cipher, { c ->
                try {
                    val ct = c.doFinal(password.toByteArray(Charsets.UTF_8))
                    prefs.edit()
                        .putString(P_CT, Base64.encodeToString(ct, Base64.NO_WRAP))
                        .putString(P_IV, Base64.encodeToString(c.iv, Base64.NO_WRAP))
                        .apply()
                    toJs("window.__bioEnabled && window.__bioEnabled(true)")
                } catch (e: Exception) {
                    toJs("window.__bioEnabled && window.__bioEnabled(false)")
                }
            }, {
                toJs("window.__bioEnabled && window.__bioEnabled(false)")
            })
        } catch (e: Exception) {
            toJs("window.__bioEnabled && window.__bioEnabled(false)")
        }
    }

    private fun unwrapPassword() {
        val ctB64 = prefs.getString(P_CT, null)
        val ivB64 = prefs.getString(P_IV, null)
        if (ctB64 == null || ivB64 == null) return
        try {
            val key = secretKey(false) ?: throw IllegalStateException("key gone")
            val cipher = Cipher.getInstance("AES/GCM/NoPadding")
            cipher.init(Cipher.DECRYPT_MODE, key, GCMParameterSpec(128, Base64.decode(ivB64, Base64.NO_WRAP)))
            prompt("Unlock your vault", "Fingerprint or face", cipher, { c ->
                try {
                    val pw = String(c.doFinal(Base64.decode(ctB64, Base64.NO_WRAP)), Charsets.UTF_8)
                    toJs("window.__bioUnlock && window.__bioUnlock(${JSONObject.quote(pw)})")
                } catch (e: Exception) {
                    fingerprintReset()
                }
            }, { msg ->
                toJs("window.__bioError && window.__bioError(${JSONObject.quote(msg)})")
            })
        } catch (e: Exception) {
            fingerprintReset()
        }
    }

    /**
     * The hardware key is destroyed when a fingerprint is added or removed on the
     * phone, which is the behaviour we want. Clear the leftovers and say so plainly.
     */
    private fun fingerprintReset() {
        Bridge().disableBiometric()
        toJs("window.__bioError && window.__bioError(\"Fingerprint unlock was reset because this phone's biometrics changed. Use your master password, then turn it on again.\")")
    }
}
