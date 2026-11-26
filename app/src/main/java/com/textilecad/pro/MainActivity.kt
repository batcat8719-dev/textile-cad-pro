package com.textilecad.pro

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Color
import android.graphics.Matrix
import android.net.Uri
import android.os.Bundle
import android.view.View
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
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
    private lateinit var btnApiKey: Button
    private lateinit var btnAiExtract: Button
    private lateinit var btnTool: Button
    private lateinit var vColorSwatch: View
    private lateinit var btnDotting: Button
    private lateinit var btnColors: Button
    private lateinit var btnChemistry: Button
    private lateinit var btnRotary: Button
    private lateinit var btnRepeatType: Button
    private lateinit var btnBuildCad: Button

    private lateinit var cadEngine: TextileCadEngine
    private val worker = Executors.newSingleThreadExecutor()

    private var lastPickedUri: Uri? = null
    private var selectedColors = 4
    private var selectedChemistry = PrintChemistry.PROCION
    private var selectedRotary = RotaryRepeat.DIV_4
    private var selectedRepeatType = RepeatType.STRAIGHT
    private var enableDotting32Lpi = false
    private var isProcessing = false

    private val imagePicker = registerForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        if (uri != null) {
            lastPickedUri = uri
            loadPickedImage(uri)
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        cropView = findViewById(R.id.cropView)
        tvHint = findViewById(R.id.tvHint)
        tvStatus = findViewById(R.id.tvStatus)
        btnApiKey = findViewById(R.id.btnApiKey)
        btnAiExtract = findViewById(R.id.btnAiExtract)
        btnTool = findViewById(R.id.btnTool)
        vColorSwatch = findViewById(R.id.vColorSwatch)
        btnDotting = findViewById(R.id.btnDotting)
        btnColors = findViewById(R.id.btnColors)
        btnChemistry = findViewById(R.id.btnChemistry)
        btnRotary = findViewById(R.id.btnRotary)
        btnRepeatType = findViewById(R.id.btnRepeatType)
        btnBuildCad = findViewById(R.id.btnBuildCad)

        cadEngine = TextileCadEngine(this)

        cropView.onColorPicked = { pickedCol ->
            vColorSwatch.setBackgroundColor(pickedCol)
            tvStatus.text = "Colour Picked! Ab F3: FILL ya F2: REKH se trace karo"
        }

        findViewById<Button>(R.id.btnPick).setOnClickListener {
            if (!isProcessing) imagePicker.launch("image/*")
        }

        btnApiKey.setOnClickListener { showApiKeyDialog() }

        findViewById<Button>(R.id.btnGeminiPro).setOnClickListener {
            shareToGeminiProApp()
        }

        findViewById<Button>(R.id.btnPhotopea).setOnClickListener {
            openPhotopea()
        }

        btnAiExtract.setOnClickListener {
            runOnlineAiExtract()
        }

        btnTool.setOnClickListener {
            val all = StudioTool.values()
            val next = all[(cropView.activeTool.ordinal + 1) % all.size]
            cropView.activeTool = next
            cropView.cropHandlesVisible = (next == StudioTool.CROP_4PT)
            updateControlLabels()
            val tip = when (next) {
                StudioTool.CROP_4PT -> "4-Point Mode: Repeat ke 4 konon par dots set karo"
                StudioTool.F3_FILL -> "F3 Hard-Edge Fill: Ungli se patti/booty ghero, chhodte hi fill hoga"
                StudioTool.F2_REKH -> "F2 Rekh Outline: Crisp 4px hard-edge line draw karo"
                StudioTool.EYEDROPPER -> "Eyedropper: Design par tap karke screen colour pick karo"
            }
            tvStatus.text = tip
        }

        findViewById<Button>(R.id.btnUndo).setOnClickListener {
            if (!cropView.undoTrace()) {
                Toast.makeText(this, "Nothing to undo", Toast.LENGTH_SHORT).show()
            }
        }

        btnDotting.setOnClickListener {
            enableDotting32Lpi = !enableDotting32Lpi
            updateControlLabels()
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
            cropView.activeTool = StudioTool.CROP_4PT
            cropView.cropHandlesVisible = true
            cropView.resetQuad()
            updateControlLabels()
        }

        btnBuildCad.setOnClickListener { runCadPipeline() }

        updateControlLabels()
    }

    override fun onDestroy() {
        super.onDestroy()
        worker.shutdown()
    }

    private fun updateControlLabels() {
        btnApiKey.text = if (getApiKey().isNotBlank()) "✦ API ✓" else "✦ API"
        btnTool.text = cropView.activeTool.label
        btnDotting.text = if (enableDotting32Lpi) "DOT: 32LPI" else "DOT: OFF"
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
                while (longest / (sample * 2) >= 1800) sample *= 2

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
                    tvHint.visibility = View.GONE
                    cropView.activeTool = StudioTool.CROP_4PT
                    cropView.cropHandlesVisible = true
                    cropView.setBitmap(oriented, resetCorners = true, clearUndo = true)
                    updateControlLabels()
                    tvStatus.text = "Garment photo hai? Pehle '✦ AI EXTRACT' (ya ✦ GEMINI) dabao!"
                }
            } catch (e: Exception) {
                runOnUiThread {
                    Toast.makeText(this, "Image load failed: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
        }
    }

    private fun runOnlineAiExtract() {
        val src = cropView.getCurrentBitmap()
        if (src == null) {
            Toast.makeText(this, "Pehle PICK PHOTO dabao", Toast.LENGTH_SHORT).show()
            return
        }
        val key = getApiKey()
        if (key.isBlank()) {
            showApiKeyDialog()
            return
        }
        if (isProcessing) return
        isProcessing = true
        btnAiExtract.isEnabled = false
        btnBuildCad.isEnabled = false
        tvStatus.text = "✦ Online AI garment se flat traced design nikaal raha hai..."

        val snapshot = src.copy(Bitmap.Config.ARGB_8888, false)
        val colors = selectedColors
        val model = getModelId()

        worker.execute {
            try {
                val res = cadEngine.extractFlatDesignWithAi(snapshot, colors, key, model)
                snapshot.recycle()
                runOnUiThread {
                    if (res.success && res.bitmap != null) {
                        cropView.activeTool = StudioTool.CROP_4PT
                        cropView.cropHandlesVisible = true
                        cropView.setBitmap(res.bitmap, resetCorners = true, clearUndo = true)
                        tvStatus.text = "✓ Flat Traced Tile Ready! Ab '✦ BUILD PSD + CAD' dabao"
                        Toast.makeText(this, res.message, Toast.LENGTH_SHORT).show()
                    } else {
                        tvStatus.text = "AI Extract failed: ${res.message}"
                        Toast.makeText(this, "${res.message} — ✦ GEMINI button try karo!", Toast.LENGTH_LONG).show()
                    }
                }
            } finally {
                runOnUiThread {
                    isProcessing = false
                    btnAiExtract.isEnabled = true
                    btnBuildCad.isEnabled = true
                }
            }
        }
    }

    private fun shareToGeminiProApp() {
        val uri = lastPickedUri
        if (uri == null) {
            Toast.makeText(this, "Pehle PICK PHOTO se WhatsApp design select karo", Toast.LENGTH_SHORT).show()
            return
        }
        val prompt = TextileCadEngine.buildExtractionPrompt(selectedColors)
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("Textile CAD Extract Prompt", prompt))
        Toast.makeText(this, "Prompt copied! Gemini Pro me paste karke flat design nikalo, phir PICK PHOTO karo", Toast.LENGTH_LONG).show()

        val share = Intent(Intent.ACTION_SEND).apply {
            type = "image/*"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_TEXT, prompt)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        try {
            startActivity(Intent.createChooser(share, "Extract Design in Gemini Pro"))
        } catch (_: Exception) {
            Toast.makeText(this, "Prompt clipboard me copied hai", Toast.LENGTH_SHORT).show()
        }
    }

    private fun runCadPipeline() {
        val src = cropView.getCurrentBitmap()
        if (src == null) {
            Toast.makeText(this, "Pehle PICK PHOTO dabao", Toast.LENGTH_SHORT).show()
            return
        }
        if (isProcessing) return
        isProcessing = true
        btnBuildCad.isEnabled = false
        btnBuildCad.text = "BUILDING..."

        val snapshot = src.copy(Bitmap.Config.ARGB_8888, false)
        val corners = cropView.getNormalizedCorners()
        val colors = selectedColors
        val chem = selectedChemistry
        val rotary = selectedRotary
        val repType = selectedRepeatType
        val dotting = enableDotting32Lpi
        val code = "CAD_" + SimpleDateFormat("MMdd_HHmm", Locale.US).format(System.currentTimeMillis())

        worker.execute {
            try {
                val res = cadEngine.buildOperatorReadyCad(
                    source = snapshot,
                    normCorners = corners,
                    numColors = colors,
                    chemistry = chem,
                    repeatType = repType,
                    rotary = rotary,
                    enableDotting32Lpi = dotting,
                    designCode = code
                ) { stepMsg ->
                    runOnUiThread { tvStatus.text = stepMsg }
                }
                snapshot.recycle()

                runOnUiThread {
                    cropView.activeTool = StudioTool.CROP_4PT
                    cropView.cropHandlesVisible = false
                    cropView.setBitmap(res.previewCadBitmap, resetCorners = false, clearUndo = false)
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
        msg.append("2. Master CAD + ${res.positiveCount} Speckle-Free B&W Positives:\n   Gallery → Pictures/TextileCAD-Pro\n\n")
        msg.append("Spot Channels (Top → Bottom):\n")
        res.channelNames.forEach { msg.append("  • $it\n") }

        AlertDialog.Builder(this)
            .setTitle("✦ Operator-Ready V2 CAD Saved!")
            .setMessage(msg.toString())
            .setPositiveButton("Open Photopea") { _, _ -> openPhotopea() }
            .setNegativeButton("Done", null)
            .show()
    }

    private fun getApiKey(): String =
        getSharedPreferences("cad_prefs", Context.MODE_PRIVATE).getString("api_key", "") ?: ""

    private fun getModelId(): String =
        getSharedPreferences("cad_prefs", Context.MODE_PRIVATE)
            .getString("model_id", TextileCadEngine.DEFAULT_AI_MODEL)
            ?.ifBlank { TextileCadEngine.DEFAULT_AI_MODEL }
            ?: TextileCadEngine.DEFAULT_AI_MODEL

    private fun showApiKeyDialog() {
        val pad = (18 * resources.displayMetrics.density).toInt()
        val box = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(pad, pad / 2, pad, 0)
        }
        val etKey = EditText(this).apply { hint = "Gemini API Key (AIzaSy...)"; setText(getApiKey()) }
        val etModel = EditText(this).apply { hint = TextileCadEngine.DEFAULT_AI_MODEL; setText(getModelId()) }
        box.addView(etKey); box.addView(etModel)

        AlertDialog.Builder(this)
            .setTitle("✦ Online AI Pattern Extractor")
            .setMessage("aistudio.google.com ki API key yahan save karo. (Ya bina API ke upar '✦ GEMINI' button se apne Gemini Pro app me ₹0 me extract karo).")
            .setView(box)
            .setPositiveButton("Save") { _, _ ->
                getSharedPreferences("cad_prefs", Context.MODE_PRIVATE).edit()
                    .putString("api_key", etKey.text.toString().trim())
                    .putString("model_id", etModel.text.toString().trim().ifBlank { TextileCadEngine.DEFAULT_AI_MODEL })
                    .apply()
                updateControlLabels()
            }
            .setNegativeButton("Cancel", null)
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
