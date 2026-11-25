package com.textilecad.pro

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.TextView
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.exifinterface.media.ExifInterface
import java.text.SimpleDateFormat
import java.util.Locale
import java.util.concurrent.Executors
import kotlin.math.max

class MainActivity : AppCompatActivity() {

    private lateinit var cropView: PerspectiveCropView
    private lateinit var tvHint: TextView
    private lateinit var tvStatus: TextView
    private lateinit var btnColors: Button
    private lateinit var btnChemistry: Button
    private lateinit var btnRotary: Button
    private lateinit var btnRepeatType: Button
    private lateinit var btnBuildCad: Button

    private lateinit var cadEngine: TextileCadEngine
    private val worker = Executors.newSingleThreadExecutor()

    private var loadedSource: Bitmap? = null
    private var selectedColors = 4
    private var selectedChemistry = PrintChemistry.PROCION
    private var selectedRotary = RotaryRepeat.DIV_4
    private var selectedRepeatType = RepeatType.STRAIGHT
    private var isProcessing = false

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) loadPickedImage(uri)
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cropView = findViewById(R.id.cropView)
        tvHint = findViewById(R.id.tvHint)
        tvStatus = findViewById(R.id.tvStatus)
        btnColors = findViewById(R.id.btnColors)
        btnChemistry = findViewById(R.id.btnChemistry)
        btnRotary = findViewById(R.id.btnRotary)
        btnRepeatType = findViewById(R.id.btnRepeatType)
        btnBuildCad = findViewById(R.id.btnBuildCad)

        cadEngine = TextileCadEngine(this)

        findViewById<Button>(R.id.btnPick).setOnClickListener {
            if (!isProcessing) imagePicker.launch("image/*")
        }

        findViewById<Button>(R.id.btnPhotopea).setOnClickListener {
            openPhotopea()
        }

        btnColors.setOnClickListener {
            selectedColors = if (selectedColors >= 8) 2 else selectedColors + 1
            updateControlLabels()
        }

        btnChemistry.setOnClickListener {
            val all = PrintChemistry.values()
            selectedChemistry = all[(selectedChemistry.ordinal + 1) % all.size]
            updateControlLabels()
        }

        btnRotary.setOnClickListener {
            val all = RotaryRepeat.values()
            selectedRotary = all[(selectedRotary.ordinal + 1) % all.size]
            updateControlLabels()
        }

        btnRepeatType.setOnClickListener {
            selectedRepeatType = if (selectedRepeatType == RepeatType.STRAIGHT) {
                RepeatType.HALF_DROP
            } else {
                RepeatType.STRAIGHT
            }
            updateControlLabels()
        }

        findViewById<Button>(R.id.btnResetQuad).setOnClickListener {
            if (loadedSource != null) {
                cropView.cropHandlesVisible = true
                cropView.setBitmap(loadedSource, resetCorners = true)
                tvStatus.text = "4 corners ko repeat phoolon par set karke BUILD PSD dabao"
            }
        }

        btnBuildCad.setOnClickListener {
            runCadPipeline()
        }

        updateControlLabels()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }

    private fun updateControlLabels() {
        btnColors.text = "SCREENS: $selectedColors"
        btnChemistry.text = when (selectedChemistry) {
            PrintChemistry.PROCION -> "PROCION"
            PrintChemistry.DISCHARGE -> "DISCHARGE"
            PrintChemistry.KHADI_GOLD -> "KHADI/GOLD"
        }
        btnRotary.text = selectedRotary.label
        btnRepeatType.text = selectedRepeatType.label
    }

    private fun loadPickedImage(uri: Uri) {
        tvStatus.text = "Loading image..."
        worker.execute {
            try {
                val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
                contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, bounds) }
                val longest = max(bounds.outWidth, bounds.outHeight)
                var sample = 1
                while (longest / (sample * 2) >= 2000) sample *= 2

                val opts = BitmapFactory.Options().apply {
                    inSampleSize = sample
                    inPreferredConfig = Bitmap.Config.ARGB_8888
                }
                val raw = contentResolver.openInputStream(uri)?.use {
                    BitmapFactory.decodeStream(it, null, opts)
                } ?: throw IllegalStateException("Cannot decode image")

                val orientation = contentResolver.openInputStream(uri)?.use {
                    ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
                } ?: ExifInterface.ORIENTATION_NORMAL

                val m = Matrix()
                when (orientation) {
                    ExifInterface.ORIENTATION_ROTATE_90 -> m.postRotate(90f)
                    ExifInterface.ORIENTATION_ROTATE_180 -> m.postRotate(180f)
                    ExifInterface.ORIENTATION_ROTATE_270 -> m.postRotate(270f)
                }
                val oriented = if (!m.isIdentity) {
                    Bitmap.createBitmap(raw, 0, 0, raw.width, raw.height, m, true).also {
                        if (it !== raw) raw.recycle()
                    }
                } else raw

                runOnUiThread {
                    loadedSource?.recycle()
                    loadedSource = oriented
                    tvHint.visibility = View.GONE
                    cropView.cropHandlesVisible = true
                    cropView.setBitmap(oriented, resetCorners = true)
                    tvStatus.text = "4 corners ko repeat ke 4 konon par set karo, phir ✦ BUILD PSD + CAD dabao"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Image load failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun runCadPipeline() {
        val src = loadedSource
        if (src == null) {
            Toast.makeText(this, "Pehle PICK PHOTO dabakar WhatsApp design select karo", Toast.LENGTH_SHORT).show()
            return
        }
        if (isProcessing) return
        isProcessing = true
        btnBuildCad.isEnabled = false
        btnBuildCad.text = "BUILDING..."

        val corners = cropView.getNormalizedCorners()
        val colors = selectedColors
        val chem = selectedChemistry
        val rotary = selectedRotary
        val repType = selectedRepeatType
        val code = "CAD_" + SimpleDateFormat("MMdd_HHmm", Locale.US).format(System.currentTimeMillis())

        worker.execute {
            try {
                val res = cadEngine.buildOperatorReadyCad(
                    source = src,
                    normCorners = corners,
                    numColors = colors,
                    chemistry = chem,
                    repeatType = repType,
                    rotary = rotary,
                    designCode = code
                ) { stepMsg ->
                    runOnUiThread { tvStatus.text = stepMsg }
                }

                runOnUiThread {
                    cropView.cropHandlesVisible = false
                    cropView.setBitmap(res.previewCadBitmap, resetCorners = false)
                    tvStatus.text = "✓ READY: ${res.summary}"
                    showExportSuccessDialog(res)
                }
            } catch (e: Exception) {
                runOnUiThread {
                    tvStatus.text = "Error: ${e.message}"
                    Toast.makeText(this, "Build failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            } finally {
                runOnUiThread {
                    isProcessing = false
                    btnBuildCad.isEnabled = true
                    btnBuildCad.text = "✦ BUILD PSD + CAD"
                }
            }
        }
    }

    private fun showExportSuccessDialog(res: CadBuildResult) {
        val msg = StringBuilder()
        msg.append("1. Real 300 DPI Spot-Channel .PSD:\n   ${res.psdPathLabel}\n\n")
        msg.append("2. Master CAD Sheet + ${res.positiveCount} B&W Film Positives:\n   Gallery → Pictures/TextileCAD-Pro\n\n")
        msg.append("Spot Channels (Top → Bottom):\n")
        res.channelNames.forEach { msg.append("  • $it\n") }
        msg.append("\nAb 'Open Photopea' dabao → Open From Computer → Downloads/TextileCAD-Pro se apni .PSD file khol kar operator ke liye final check kar lo!")

        AlertDialog.Builder(this)
            .setTitle("✦ Operator-Ready CAD & PSD Saved!")
            .setMessage(msg.toString())
            .setPositiveButton("Open Photopea") { _, _ -> openPhotopea() }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun openPhotopea() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.photopea.com")))
        } catch (_: Exception) {
            Toast.makeText(this, "Browser me photopea.com kholo", Toast.LENGTH_SHORT).show()
        }
    }
}
