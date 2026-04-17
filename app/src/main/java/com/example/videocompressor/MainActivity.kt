package com.example.videocompressor

import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.util.Log
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.documentfile.provider.DocumentFile
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.arthenica.ffmpegkit.*
import com.google.android.material.materialswitch.MaterialSwitch
import java.io.File

class MainActivity : AppCompatActivity() {

    private val selectedUris = mutableListOf<Uri>()
    private lateinit var adapter: VideoAdapter

    private var outputFolderUri: Uri? = null

    private lateinit var spinner: Spinner
    private lateinit var switchDelete: MaterialSwitch
    private lateinit var progressBar: ProgressBar
    private lateinit var textStatus: TextView
    private lateinit var buttonCompress: Button
    private lateinit var buttonStop: Button

    private var isCancelled = false

    private val pickVideos =
        registerForActivityResult(ActivityResultContracts.OpenMultipleDocuments()) { uris ->
            if (!uris.isNullOrEmpty()) {
                selectedUris.clear()
                selectedUris.addAll(uris)
                adapter.notifyDataSetChanged()
                Toast.makeText(this, "已选择 ${uris.size} 个视频", Toast.LENGTH_SHORT).show()
            }
        }

    private val pickFolder =
        registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
            if (uri != null) {
                contentResolver.takePersistableUriPermission(
                    uri,
                    Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                )
                outputFolderUri = uri
                Toast.makeText(this, "已选择输出目录", Toast.LENGTH_SHORT).show()
            }
        }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val recyclerView = findViewById<RecyclerView>(R.id.recyclerView)
        val buttonSelect = findViewById<Button>(R.id.buttonSelect)
        val buttonFolder = findViewById<Button>(R.id.buttonFolder)
        buttonCompress = findViewById(R.id.buttonCompress)
        buttonStop = findViewById(R.id.buttonStop)

        spinner = findViewById(R.id.spinnerResolution)
        switchDelete = findViewById(R.id.switchDelete)
        progressBar = findViewById(R.id.progressBar)
        textStatus = findViewById(R.id.textStatus)

        adapter = VideoAdapter(selectedUris)
        recyclerView.layoutManager = LinearLayoutManager(this)
        recyclerView.adapter = adapter

        val options = listOf("1080p (平衡)", "720p (较快)", "480p (极速)", "360p (最小体积)")
        val spinnerAdapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, options)
        spinnerAdapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinner.adapter = spinnerAdapter

        buttonSelect.setOnClickListener { pickVideos.launch(arrayOf("video/*")) }
        buttonFolder.setOnClickListener { pickFolder.launch(null) }
        buttonCompress.setOnClickListener { startCompressBatch() }

        buttonStop.setOnClickListener {
            isCancelled = true
            FFmpegKit.cancel()
            Toast.makeText(this, "正在强行中止当前任务...", Toast.LENGTH_SHORT).show()
        }
    }

    private fun getTargetWidth(resolution: String): Int {
        return when {
            resolution.contains("1080p") -> 1920
            resolution.contains("720p") -> 1280
            resolution.contains("480p") -> 854
            else -> 640
        }
    }

    private fun getTargetBitrate(resolution: String): Long {
        return when {
            resolution.contains("1080p") -> 3000000L
            resolution.contains("720p") -> 1800000L
            resolution.contains("480p") -> 1000000L
            else -> 600000L
        }
    }

    private fun startCompressBatch() {
        if (selectedUris.isEmpty()) return Toast.makeText(this, "请先选择视频文件", Toast.LENGTH_SHORT).show()

        if (outputFolderUri == null) {
            textStatus.text = "提示：未选目录，将默认存入原目录或系统相册"
        }

        isCancelled = false
        buttonCompress.isEnabled = false
        buttonStop.visibility = View.VISIBLE
        progressBar.visibility = View.VISIBLE
        progressBar.isIndeterminate = true 
        textStatus.visibility = View.VISIBLE

        processVideo(0)
    }

    private fun processVideo(index: Int) {
        if (index >= selectedUris.size || isCancelled) {
            runOnUiThread {
                buttonCompress.isEnabled = true
                buttonStop.visibility = View.GONE
                progressBar.visibility = View.GONE
                textStatus.text = if (isCancelled) "已中断" else "全部任务处理完成！"
                if (!isCancelled && switchDelete.isChecked) {
                    selectedUris.clear()
                    adapter.notifyDataSetChanged()
                }
            }
            return
        }

        val uri = selectedUris[index]
        val resolution = spinner.selectedItem.toString()

        Thread {
            try {
                val originalName = FileUtils.getFileName(this, uri)
                val nameWithoutExt = originalName.substringBeforeLast(".", "video")
                val outputFileName = "${nameWithoutExt}_compressed.mp4"

                runOnUiThread { 
                    progressBar.isIndeterminate = true
                    textStatus.text = "[$index/${selectedUris.size}] 准备中: $originalName"
                }
                
                val tempInputPath = FileUtils.getPath(this, uri) ?: return@Thread
                val infoSession = FFprobeKit.getMediaInformation(tempInputPath)
                val mediaInfo = infoSession.mediaInformation
                
                val durationMs = (mediaInfo?.duration?.toDouble() ?: 0.0) * 1000
                val srcWidth = mediaInfo?.streams?.find { it.type == "video" }?.width?.toInt() ?: 0
                val srcBitrate = mediaInfo?.bitrate?.toLong() ?: 0L
                val srcFormat = mediaInfo?.streams?.find { it.type == "video" }?.codec ?: ""
                
                val targetWidth = getTargetWidth(resolution)
                val targetBitrate = getTargetBitrate(resolution)
                val tempOutput = File(externalCacheDir ?: cacheDir, "comp_tmp_${System.currentTimeMillis()}.mp4")

                // 智能判断：是否需要压缩
                if (srcWidth <= targetWidth && srcBitrate <= targetBitrate && srcBitrate > 0 && srcFormat.contains("h264")) {
                    runOnUiThread { textStatus.text = "规格已达标，正在极速封装..." }
                    val cmd = "-y -i \"$tempInputPath\" -c copy \"${tempOutput.absolutePath}\""
                    executeSimple(cmd, tempInputPath, tempOutput, outputFileName, uri, index, 0.0, originalName)
                } else {
                    val hwCmd = "-y -threads 0 -i \"$tempInputPath\" -vf \"scale=$targetWidth:-2,format=yuv420p\" " +
                                "-c:v h264_mediacodec -b:v ${targetBitrate/1000}k -c:a aac -b:a 128k \"${tempOutput.absolutePath}\""
                    
                    val swCmd = "-y -threads 0 -i \"$tempInputPath\" -vf \"scale=$targetWidth:-2,format=yuv420p\" " +
                                "-c:v libx264 -preset ultrafast -crf 28 -c:a aac -b:a 128k \"${tempOutput.absolutePath}\""

                    runOnUiThread { progressBar.isIndeterminate = false }
                    executeWithFallback(hwCmd, swCmd, tempInputPath, tempOutput, outputFileName, uri, index, durationMs, originalName)
                }
            } catch (e: Exception) {
                Log.e("Compress", "Error", e)
                runOnUiThread { processVideo(index + 1) }
            }
        }.start()
    }

    private fun executeWithFallback(hw: String, sw: String, tIn: String, tOut: File, outName: String, u: Uri, idx: Int, dur: Double, oName: String) {
        FFmpegKit.executeAsync(hw, { session ->
            if (ReturnCode.isSuccess(session.returnCode) && !isCancelled) {
                finalizeAndSave(tIn, tOut, outName, u, idx)
            } else if (!isCancelled && !ReturnCode.isCancel(session.returnCode)) {
                runOnUiThread { textStatus.text = "模式切换中..." }
                FFmpegKit.executeAsync(sw, { swSession ->
                    if (ReturnCode.isSuccess(swSession.returnCode) && !isCancelled) {
                        finalizeAndSave(tIn, tOut, outName, u, idx)
                    } else {
                        cleanupAndNext(tIn, tOut, idx)
                    }
                }, { /* logs */ }, { stats -> updateProgressUI(stats, dur, idx, oName) })
            } else {
                cleanupAndNext(tIn, tOut, idx)
            }
        }, { /* logs */ }, { stats -> updateProgressUI(stats, dur, idx, oName) })
    }

    private fun executeSimple(cmd: String, tIn: String, tOut: File, outName: String, u: Uri, idx: Int, dur: Double, oName: String) {
        FFmpegKit.executeAsync(cmd, { session ->
            if (ReturnCode.isSuccess(session.returnCode) && !isCancelled) {
                finalizeAndSave(tIn, tOut, outName, u, idx)
            } else {
                cleanupAndNext(tIn, tOut, idx)
            }
        }, { /* logs */ }, { stats -> updateProgressUI(stats, dur, idx, oName) })
    }

    private fun updateProgressUI(stats: Statistics, dur: Double, idx: Int, oName: String) {
        if (dur > 0) {
            val p = (stats.time.toDouble() / dur * 100).toInt()
            runOnUiThread {
                progressBar.progress = p.coerceIn(0, 100)
                textStatus.text = "正在压缩 ($idx/${selectedUris.size}): $oName ($p%)"
            }
        }
    }

    private fun finalizeAndSave(tIn: String, tOut: File, outName: String, inputUri: Uri, idx: Int) {
        if (tOut.exists() && tOut.length() > 0) {
            if (outputFolderUri != null) {
                val folder = DocumentFile.fromTreeUri(this, outputFolderUri!!)
                folder?.createFile("video/mp4", outName)?.let { target ->
                    contentResolver.openOutputStream(target.uri)?.use { os -> tOut.inputStream().use { it.copyTo(os) } }
                    if (switchDelete.isChecked) contentResolver.delete(inputUri, null, null)
                }
            } else {
                saveToGallery(tOut, outName, inputUri)
            }
        }
        cleanupAndNext(tIn, tOut, idx)
    }

    private fun saveToGallery(file: File, name: String, inputUri: Uri) {
        val values = ContentValues().apply {
            put(MediaStore.Video.Media.DISPLAY_NAME, name)
            put(MediaStore.Video.Media.MIME_TYPE, "video/mp4")
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                var path = Environment.DIRECTORY_MOVIES + "/视频压缩"
                try {
                    contentResolver.query(inputUri, arrayOf(MediaStore.Video.Media.RELATIVE_PATH), null, null, null)?.use { c ->
                        if (c.moveToFirst()) {
                            val rel = c.getString(0)
                            if (rel != null) path = rel
                        }
                    }
                } catch (e: Exception) {}
                put(MediaStore.Video.Media.RELATIVE_PATH, path)
            }
        }
        val col = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) MediaStore.Video.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY) 
                  else MediaStore.Video.Media.EXTERNAL_CONTENT_URI
        contentResolver.insert(col, values)?.let { target ->
            contentResolver.openOutputStream(target)?.use { os -> file.inputStream().use { it.copyTo(os) } }
            if (switchDelete.isChecked) contentResolver.delete(inputUri, null, null)
        }
    }

    private fun cleanupAndNext(tIn: String, tOut: File, idx: Int) {
        File(tIn).delete()
        if (tOut.exists()) tOut.delete()
        runOnUiThread { processVideo(idx + 1) }
    }
}