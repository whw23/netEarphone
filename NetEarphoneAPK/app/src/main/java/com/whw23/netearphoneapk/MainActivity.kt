package com.whw23.netearphoneapk

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.app.NotificationManager
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.media.*
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.*
import androidx.appcompat.app.AppCompatActivity
import android.util.Log
import android.view.View
import android.widget.*
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import okhttp3.*
import okio.ByteString
import okio.ByteString.Companion.toByteString
import java.io.*
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    // UI控件
    private lateinit var etWsAddress: EditText
    private lateinit var spinnerAudioSource: Spinner
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnDisconnect: Button
    private lateinit var btnStartRecording: Button
    private lateinit var btnStopRecording: Button
    private lateinit var btnDownload: Button

    // WebSocket
    private var client: OkHttpClient? = null
    private var ws: WebSocket? = null

    // 录音相关
    private var isRecording = false
    private var recordingThread: Thread? = null
    private var audioRecord: AudioRecord? = null
    private var sampleRate = 44100
    private var audioBufferSize = 0

    // 缓存录制数据
    private var recordedData = ByteArrayOutputStream()

    // MediaProjection（系统录制使用）
    private var mediaProjectionManager: MediaProjectionManager? = null
    private var mediaProjection: MediaProjection? = null
    private val REQUEST_CODE_MEDIA_PROJECTION = 1001

    // 权限请求码
    private val REQUEST_CODE_PERMISSIONS = 2001
    private val REQUIRED_PERMISSIONS = arrayOf(
        Manifest.permission.RECORD_AUDIO,
        Manifest.permission.WRITE_EXTERNAL_STORAGE
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        title = "NetEarphone Android"

        initUI()
        checkPermissions()

        // 初始化 MediaProjectionManager
        mediaProjectionManager = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
    }

    private fun initUI() {
        etWsAddress = findViewById(R.id.etWsAddress)
        spinnerAudioSource = findViewById(R.id.spinnerAudioSource)
        tvStatus = findViewById(R.id.tvStatus)
        tvLog = findViewById(R.id.tvLog)
        btnConnect = findViewById(R.id.btnConnect)
        btnDisconnect = findViewById(R.id.btnDisconnect)
        btnStartRecording = findViewById(R.id.btnStartRecording)
        btnStopRecording = findViewById(R.id.btnStopRecording)
        btnDownload = findViewById(R.id.btnDownload)

        // 设置 spinner 数据
        val sources = listOf("Microphone", "System Audio")
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, sources)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerAudioSource.adapter = adapter

        btnConnect.setOnClickListener { connectWebSocket() }
        btnDisconnect.setOnClickListener { disconnectWebSocket() }
        btnStartRecording.setOnClickListener { startRecording() }
        btnStopRecording.setOnClickListener { stopRecording() }
        btnDownload.setOnClickListener { downloadRecordedAudio() }
    }

    private fun checkPermissions() {
        val missing = REQUIRED_PERMISSIONS.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }
        if (missing.isNotEmpty()) {
            ActivityCompat.requestPermissions(this, missing.toTypedArray(), REQUEST_CODE_PERMISSIONS)
        }
    }

    // WebSocket 连接
    private fun connectWebSocket() {
        val wsUrl = etWsAddress.text.toString().trim()
        if (wsUrl.isEmpty()) {
            showAlert("请输入有效的 WebSocket 地址")
            return
        }

        client = OkHttpClient()
        val request = Request.Builder().url(wsUrl).build()
        ws = client?.newWebSocket(request, object : WebSocketListener() {
            override fun onOpen(webSocket: WebSocket, response: Response) {
                runOnUiThread {
                    updateStatus("Connected")
                    btnConnect.isEnabled = false
                    btnDisconnect.isEnabled = true
                    btnStartRecording.isEnabled = true
                    log("WebSocket opened")
                }
                // 连接后发送设置音频格式消息
                sendAudioFormat(ws!!, sampleRate, 16, 1, false)
            }

            override fun onMessage(webSocket: WebSocket, text: String) {
                runOnUiThread {
                    log("Received: $text")
                }
            }

            override fun onMessage(webSocket: WebSocket, bytes: ByteString) {
                // 二进制消息一般不处理
            }

            override fun onClosing(webSocket: WebSocket, code: Int, reason: String) {
                webSocket.close(1000, null)
                runOnUiThread {
                    updateStatus("Closing: $code / $reason")
                    log("WebSocket closing: $code / $reason")
                }
            }

            override fun onClosed(webSocket: WebSocket, code: Int, reason: String) {
                runOnUiThread {
                    updateStatus("Disconnected")
                    btnConnect.isEnabled = true
                    btnDisconnect.isEnabled = false
                    btnStartRecording.isEnabled = false
                    btnStopRecording.isEnabled = false
                    log("WebSocket closed")
                }
            }

            override fun onFailure(webSocket: WebSocket, t: Throwable, response: Response?) {
                runOnUiThread {
                    updateStatus("Error: ${t.message}")
                    log("WebSocket error: ${t.message}")
                    btnConnect.isEnabled = true
                    btnDisconnect.isEnabled = false
                    btnStartRecording.isEnabled = false
                    btnStopRecording.isEnabled = false
                }
            }
        })
    }

    private fun disconnectWebSocket() {
        ws?.close(1000, "User disconnect")
        ws = null
        client?.dispatcher?.executorService?.shutdown()
        runOnUiThread {
            updateStatus("Disconnected")
            btnConnect.isEnabled = true
            btnDisconnect.isEnabled = false
            btnStartRecording.isEnabled = false
            btnStopRecording.isEnabled = false
        }
    }

    // 发送设置音频格式消息（与服务端协商音频格式）
    private fun sendAudioFormat(ws: WebSocket, sampleRate: Int, sampleSize: Int, channels: Int, bigEndian: Boolean) {
        val msg = """
            {
                "type": "SET_AUDIO_FORMAT",
                "sample_rate": $sampleRate,
                "sample_size": $sampleSize,
                "channels": $channels,
                "big_endian": $bigEndian
            }
        """.trimIndent()
        ws.send(msg)
        log("Sent audio format: $msg")
    }

    // 开始录制
    private fun startRecording() {
        if (ws == null) {
            showAlert("请先连接 WebSocket")
            return
        }
        // 清空之前录制数据
        recordedData.reset()
        isRecording = true
        btnStartRecording.isEnabled = false
        btnStopRecording.isEnabled = true
        btnDownload.visibility = View.GONE

        val source = spinnerAudioSource.selectedItem.toString()
        if (source == "Microphone") {
            startMicRecording()
        } else if (source == "System Audio") {
            // 启动前台服务
            val serviceIntent = Intent(this, MediaProjectionService::class.java)
            ContextCompat.startForegroundService(this, serviceIntent)
            // 请求系统录制权限
            requestMediaProjection()
        }
    }

    // 停止录制
    private fun stopRecording() {
        isRecording = false
        btnStartRecording.isEnabled = true
        btnStopRecording.isEnabled = false
        recordingThread?.interrupt()
        recordingThread = null
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null

        // 停止 MediaProjection（如果存在）
        mediaProjection?.stop()
        mediaProjection = null

        // 停止前台服务
        stopService(Intent(this, MediaProjectionService::class.java))

        log("Recording stopped")
        btnDownload.visibility = if (recordedData.size() > 0) View.VISIBLE else View.GONE
    }

    // 使用麦克风进行录音
    private fun startMicRecording() {
        sampleRate = 44100
        audioBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            return
        }
        audioRecord = AudioRecord(
            MediaRecorder.AudioSource.MIC,
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT,
            audioBufferSize
        )
        audioRecord?.startRecording()

        recordingThread = Thread {
            val buffer = ByteArray(audioBufferSize)
            while (isRecording && !Thread.currentThread().isInterrupted) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    ws?.send(buffer.toByteString(0, read))
                    recordedData.write(buffer, 0, read)
                }
            }
        }
        recordingThread?.start()
        log("Mic recording started")
    }

    // 使用系统音频录制（需 Android Q 及以上）
    private fun startSystemRecording() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
            runOnUiThread { showAlert("系统录制仅支持 Android 10 及以上") }
            return
        }
        sampleRate = 44100
        audioBufferSize = AudioRecord.getMinBufferSize(
            sampleRate,
            AudioFormat.CHANNEL_IN_MONO,
            AudioFormat.ENCODING_PCM_16BIT
        )
        // 构造 AudioPlaybackCaptureConfiguration
        val config = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .build()

        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED) {
            return
        }
        audioRecord = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(sampleRate)
                    .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
                    .build()
            )
            .setBufferSizeInBytes(audioBufferSize)
            .setAudioPlaybackCaptureConfig(config)
            .build()

        audioRecord?.startRecording()

        recordingThread = Thread {
            val buffer = ByteArray(audioBufferSize)
            while (isRecording && !Thread.currentThread().isInterrupted) {
                val read = audioRecord?.read(buffer, 0, buffer.size) ?: 0
                if (read > 0) {
                    ws?.send(buffer.toByteString(0, read))
                    recordedData.write(buffer, 0, read)
                }
            }
        }
        recordingThread?.start()
        log("System audio recording started")
    }

    // 请求 MediaProjection 授权
    private fun requestMediaProjection() {
        val intent = mediaProjectionManager?.createScreenCaptureIntent()
        if (intent != null) {
            startActivityForResult(intent, REQUEST_CODE_MEDIA_PROJECTION)
        }
    }

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (requestCode == REQUEST_CODE_MEDIA_PROJECTION) {
            if (resultCode == RESULT_OK && data != null) {
                // 获取授权后启动系统录制
                mediaProjection = mediaProjectionManager?.getMediaProjection(resultCode, data)
                startSystemRecording()
            } else {
                showAlert("用户拒绝了系统录制授权")
            }
        }
        super.onActivityResult(requestCode, resultCode, data)
    }

    // 下载录制的音频，生成 WAV 文件并启动分享
    private fun downloadRecordedAudio() {
        if (recordedData.size() == 0) return

        val wavBytes = convertPcmToWav(recordedData.toByteArray(), sampleRate, 1, 16)
        try {
            val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
            val fileName = "recorded_audio_$timeStamp.wav"
            val outFile = File(getExternalCacheDir(), fileName)
            FileOutputStream(outFile).use { it.write(wavBytes) }
            log("Audio saved: ${outFile.absolutePath}")

            val shareIntent = Intent(Intent.ACTION_SEND).apply {
                type = "audio/wav"
                putExtra(Intent.EXTRA_STREAM, Uri.fromFile(outFile))
            }
            startActivity(Intent.createChooser(shareIntent, "分享录制音频"))
        } catch (e: Exception) {
            log("保存文件失败: ${e.message}")
        }
    }

    // 将 PCM 数据转换为 WAV 文件格式字节数组（添加 WAV 头）
    private fun convertPcmToWav(pcmData: ByteArray, sampleRate: Int, channels: Int, bitsPerSample: Int): ByteArray {
        val byteRate = sampleRate * channels * bitsPerSample / 8
        val dataSize = pcmData.size
        val totalDataLen = 36 + dataSize
        val header = ByteArray(44)
        var offset = 0

        fun writeString(s: String) {
            s.forEach {
                header[offset++] = it.code.toByte()
            }
        }

        // RIFF header
        writeString("RIFF")
        header[offset++] = (totalDataLen and 0xff).toByte()
        header[offset++] = ((totalDataLen shr 8) and 0xff).toByte()
        header[offset++] = ((totalDataLen shr 16) and 0xff).toByte()
        header[offset++] = ((totalDataLen shr 24) and 0xff).toByte()
        writeString("WAVE")
        // fmt chunk
        writeString("fmt ")
        header[offset++] = 16
        header[offset++] = 0
        header[offset++] = 0
        header[offset++] = 0
        header[offset++] = 1  // PCM
        header[offset++] = 0
        header[offset++] = channels.toByte()
        header[offset++] = 0
        header[offset++] = (sampleRate and 0xff).toByte()
        header[offset++] = ((sampleRate shr 8) and 0xff).toByte()
        header[offset++] = ((sampleRate shr 16) and 0xff).toByte()
        header[offset++] = ((sampleRate shr 24) and 0xff).toByte()
        header[offset++] = (byteRate and 0xff).toByte()
        header[offset++] = ((byteRate shr 8) and 0xff).toByte()
        header[offset++] = ((byteRate shr 16) and 0xff).toByte()
        header[offset++] = ((byteRate shr 24) and 0xff).toByte()
        header[offset++] = (channels * bitsPerSample / 8).toByte()
        header[offset++] = 0
        header[offset++] = bitsPerSample.toByte()
        header[offset++] = 0
        // data chunk header
        writeString("data")
        header[offset++] = (dataSize and 0xff).toByte()
        header[offset++] = ((dataSize shr 8) and 0xff).toByte()
        header[offset++] = ((dataSize shr 16) and 0xff).toByte()
        header[offset++] = ((dataSize shr 24) and 0xff).toByte()

        val outputStream = ByteArrayOutputStream()
        outputStream.write(header)
        outputStream.write(pcmData)
        return outputStream.toByteArray()
    }

    private fun updateStatus(status: String) {
        tvStatus.text = status
    }

    private fun log(message: String) {
        runOnUiThread {
            tvLog.append("[${SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date())}] $message\n")
        }
        Log.d("MainActivity", message)
    }

    private fun showAlert(message: String) {
        runOnUiThread {
            AlertDialog.Builder(this)
                .setMessage(message)
                .setPositiveButton("确定", null)
                .show()
        }
    }

    // 权限回调
    override fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray) {
        if (requestCode == REQUEST_CODE_PERMISSIONS) {
            val denied = grantResults.any { it != PackageManager.PERMISSION_GRANTED }
            if (denied) {
                showAlert("部分权限被拒绝，应用可能无法正常工作")
            }
        }
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
    }
}
