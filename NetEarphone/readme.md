以下是当前的 Spring Boot + Maven + WebSocket 服务器示例：

1. Maven 项目结构：

```
NetEarphone
├── pom.xml
└── src
    └── main
        ├── java
        │   └── org
        │       └── whw23
        │           └── netearphone
        │               ├── NetEarphoneApplication.java
        │               ├── config
        │               │   └── WebSocketConfig.java
        │               ├── handler
        │               │   ├── AudioSessionManager.java
        │               │   └── MyWebSocketHandler.java
        │               └── model
        │                   ├── AudioFormatMessage.java
        │                   ├── MessageType.java
        │                   └── WebSocketResponse.java
        └── resources
            ├── application.properties
            └── static
                └── index.html
```

2. `pom.xml` 依赖：

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>
    <parent>
        <groupId>org.springframework.boot</groupId>
        <artifactId>spring-boot-starter-parent</artifactId>
        <version>3.4.2</version>
        <relativePath/> <!-- lookup parent from repository -->
    </parent>
    <groupId>org.whw23</groupId>
    <artifactId>NetEarphone</artifactId>
    <version>0.0.1-SNAPSHOT</version>
    <name>NetEarphone</name>
    <description>NetEarphone</description>
    <url/>
    <licenses>
        <license/>
    </licenses>
    <developers>
        <developer/>
    </developers>
    <scm>
        <connection/>
        <developerConnection/>
        <tag/>
        <url/>
    </scm>
    <properties>
        <java.version>17</java.version>
    </properties>
    <dependencies>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-web</artifactId>
        </dependency>
        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-websocket</artifactId>
        </dependency>
        <!-- swagger -->
        <dependency>
            <groupId>org.springdoc</groupId>
            <artifactId>springdoc-openapi-starter-webmvc-ui</artifactId>
            <version>2.6.0</version>
        </dependency>
        <dependency>
            <groupId>org.projectlombok</groupId>
            <artifactId>lombok</artifactId>
            <optional>true</optional>
        </dependency>

        <dependency>
            <groupId>org.springframework.boot</groupId>
            <artifactId>spring-boot-starter-test</artifactId>
            <scope>test</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.springframework.boot</groupId>
                <artifactId>spring-boot-maven-plugin</artifactId>
            </plugin>
        </plugins>
    </build>

</project>

```

3. 主应用类 `src/main/java/org/whw23/netearphone/NetEarphoneApplication.java`:

```java
package org.whw23.netearphone;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;

@SpringBootApplication
public class NetEarphoneApplication {

    public static void main(String[] args) {
        SpringApplication.run(NetEarphoneApplication.class, args);
    }

}

```

4. WebSocket 配置类 `src/main/java/org/whw23/netearphone/config/WebSocketConfig.java`:

```java
package org.whw23.netearphone.config;

import org.whw23.netearphone.handler.MyWebSocketHandler;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.socket.config.annotation.EnableWebSocket;
import org.springframework.web.socket.config.annotation.WebSocketConfigurer;
import org.springframework.web.socket.config.annotation.WebSocketHandlerRegistry;

@Configuration
@EnableWebSocket
public class WebSocketConfig implements WebSocketConfigurer {

    private final MyWebSocketHandler webSocketHandler;

    public WebSocketConfig(MyWebSocketHandler webSocketHandler) {
        this.webSocketHandler = webSocketHandler;
    }

    @Override
    public void registerWebSocketHandlers(WebSocketHandlerRegistry registry) {
        registry.addHandler(webSocketHandler, "/ws")
                .setAllowedOrigins("*");
    }
}

```

5. Audio线程管理器 `src/main/java/org/whw23/netearphone/handler/AudioSessionManager.java`:

```java
package org.whw23.netearphone.handler;

import org.springframework.stereotype.Component;

import javax.sound.sampled.*;
import java.util.concurrent.ConcurrentHashMap;

@Component
public class AudioSessionManager {
    private final ConcurrentHashMap<String, SourceDataLine> activePlayers = new ConcurrentHashMap<>();

    public void createAudioOutput(String sessionId, AudioFormat format) throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, format);
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
        line.open(format);
        line.start();
        activePlayers.put(sessionId, line);
    }

    public void playAudio(String sessionId, byte[] audioData) {
        SourceDataLine line = activePlayers.get(sessionId);
        if (line != null && line.isOpen()) {
            line.write(audioData, 0, audioData.length);
        }
    }

    public void cleanup(String sessionId) {
        SourceDataLine line = activePlayers.remove(sessionId);
        if (line != null) {
            line.drain();
            line.close();
        }
    }
}

```

6. WebSocket 处理器 `src/main/java/org/whw23/netearphone/handler/MyWebSocketHandler.java`:

```java
package org.whw23.netearphone.handler;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import lombok.Data;
import lombok.extern.slf4j.Slf4j;
import org.whw23.netearphone.model.*;
import org.springframework.stereotype.Component;
import org.springframework.web.socket.*;
import org.springframework.web.socket.handler.AbstractWebSocketHandler;

import javax.sound.sampled.AudioFormat;
import java.io.IOException;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

@Slf4j
@Component
public class MyWebSocketHandler extends AbstractWebSocketHandler {
    private final ConcurrentHashMap<String, AudioFormat> audioFormats = new ConcurrentHashMap<>();
    private final ObjectMapper objectMapper = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
    private final AudioSessionManager audioSessionManager;

    public MyWebSocketHandler(AudioSessionManager audioSessionManager) {
        this.audioSessionManager = audioSessionManager;
    }

    @Override
    public void afterConnectionEstablished(WebSocketSession session) throws IOException {
        WebSocketResponse res = WebSocketResponse.success(
                MessageType.CONNECTED.name(),
                new SessionData(session.getId())
        );
        session.sendMessage(new TextMessage(objectMapper.writeValueAsString(res)));
    }

    @Override
    protected void handleTextMessage(WebSocketSession session, TextMessage message) throws IOException {
        AudioFormatMessage formatMessage = objectMapper.readValue(
                message.getPayload(),
                new TypeReference<AudioFormatMessage>() {}
        );

        if (Objects.equals(formatMessage.getType(), MessageType.SET_AUDIO_FORMAT.name())) {
            handleAudioFormat(session, formatMessage);
        }else {
            sendError(session, "Invalid message type");
        }
    }

    private void handleAudioFormat(WebSocketSession session, AudioFormatMessage formatMessage) {
        try {
            AudioFormat format = new AudioFormat(
                    formatMessage.getSampleRate(),
                    formatMessage.getSampleSizeInBits(),
                    formatMessage.getChannels(),
                    true,
                    formatMessage.isBigEndian()
            );

            audioSessionManager.createAudioOutput(session.getId(), format);
            audioFormats.put(session.getId(), format);

            WebSocketResponse res = WebSocketResponse.success(
                    MessageType.AUDIO_FORMAT_ACK.name(),
                    "Format accepted"
            );
            session.sendMessage(new TextMessage(objectMapper.writeValueAsString(res)));
        } catch (Exception e) {
            log.error("Error setting audio format for session {}: {}", session.getId(), e.getMessage());
            sendError(session, "Invalid audio format: " + e.getMessage());
        }
    }

    @Override
    protected void handleBinaryMessage(WebSocketSession session, BinaryMessage message) {
        if (!audioFormats.containsKey(session.getId())) {
            sendError(session, "Set audio format first");
            return;
        }
        audioSessionManager.playAudio(session.getId(), message.getPayload().array());
    }

    @Override
    public void afterConnectionClosed(WebSocketSession session, CloseStatus status) {
        audioSessionManager.cleanup(session.getId());
        audioFormats.remove(session.getId());
    }

    private void sendError(WebSocketSession session, String msg) {
        try {
            session.sendMessage(new TextMessage(
                    objectMapper.writeValueAsString(WebSocketResponse.error(msg))
            ));
        } catch (IOException e) {
            log.error("Failed to send error message to session {}: {}", session.getId(), e.getMessage());
        }
    }

    @Data
    private static class SessionData {
        private final String sessionId;
    }
}

```

7. 设置音频格式模板 `src/main/java/org/whw23/netearphone/model/AudioFormatMessage.java`:

```java
package org.whw23.netearphone.model;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

@Data
public class AudioFormatMessage {
    private String type; // 必须为 SET_AUDIO_FORMAT

    @JsonProperty("sample_rate")
    private float sampleRate;

    @JsonProperty("sample_size")
    private int sampleSizeInBits;

    private int channels;

    @JsonProperty("big_endian")
    private boolean bigEndian;
}

```

8. WebSocket消息类型枚举 `src/main/java/org/whw23/netearphone/model/MessageType.java`:

```java
package org.whw23.netearphone.model;

public enum MessageType {
    SET_AUDIO_FORMAT,
    AUDIO_FORMAT_ACK,
    CONNECTED,
    ERROR
}

```

8. WebSocketResponse模板 `src/main/java/org/whw23/netearphone/model/WebSocketResponse.java`:

```java
package org.whw23.netearphone.model;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class WebSocketResponse {
    private int code;
    private String type;
    private String message;
    private Object data;
    private LocalDateTime timestamp = LocalDateTime.now();

    public static WebSocketResponse success(String type, Object data) {
        WebSocketResponse res = new WebSocketResponse();
        res.code = 200;
        res.type = type;
        res.data = data;
        return res;
    }

    public static WebSocketResponse error(String message) {
        WebSocketResponse res = new WebSocketResponse();
        res.code = 400;
        res.type = "ERROR";
        res.message = message;
        return res;
    }
}

```

9. 测试页面 `src/main/resources/static/index.html`:

```html
<!DOCTYPE html>
<html>
<head>
    <meta charset="UTF-8">
    <title>Real-time Audio Streaming Tester</title>
    <style>
        body {
            padding: 20px;
            font-family: Arial, sans-serif;
            background-color: #f0f2f5;
        }
        h1 { text-align: center; }
        .container {
            max-width: 700px;
            margin: auto;
            background: #fff;
            padding: 25px;
            border-radius: 8px;
            box-shadow: 0 0 15px rgba(0, 0, 0, 0.1);
        }
        .input-group {
            margin-bottom: 20px;
        }
        .input-group label {
            display: block;
            font-weight: bold;
            margin-bottom: 8px;
        }
        .input-group input, .input-group select {
            width: 100%;
            padding: 10px;
            box-sizing: border-box;
            border: 1px solid #ccc;
            border-radius: 4px;
        }
        #status {
            margin-top: 15px;
            padding: 15px;
            border-radius: 4px;
            font-weight: bold;
            text-align: center;
        }
        #status.connected {
            background-color: #d4edda;
            color: #155724;
        }
        #status.connecting {
            background-color: #fff3cd;
            color: #856404;
        }
        #status.error {
            background-color: #f8d7da;
            color: #721c24;
        }
        #controls {
            display: flex;
            flex-wrap: wrap;
            justify-content: space-between;
            margin-top: 25px;
        }
        .controls-group {
            flex: 1 1 45%;
            margin-bottom: 15px;
        }
        button {
            width: 100%;
            padding: 12px 20px;
            margin-top: 10px;
            cursor: pointer;
            border: none;
            border-radius: 4px;
            font-size: 16px;
            transition: background-color 0.3s;
        }
        button.connect {
            background-color: #007bff;
            color: #fff;
        }
        button.disconnect {
            background-color: #6c757d;
            color: #fff;
        }
        button.start {
            background-color: #28a745;
            color: #fff;
        }
        button.stop {
            background-color: #dc3545;
            color: #fff;
        }
        button.download {
            background-color: #17a2b8;
            color: white;
        }
        button:disabled {
            background-color: #cccccc;
            cursor: not-allowed;
        }
        #log {
            margin-top: 25px;
            max-height: 250px;
            overflow-y: auto;
            background: #f8f9fa;
            padding: 15px;
            border-radius: 4px;
            font-size: 14px;
            white-space: pre-wrap;
            border: 1px solid #ddd;
        }
        #downloadBtn {
            display: none;
        }
        /* 隐藏视频元素 */
        #sharedVideo {
            display: none;
        }
    </style>
</head>
<body>
<div class="container">
    <h1>Real-time Audio Streaming Tester</h1>

    <div class="input-group">
        <label for="wsAddress">WebSocket Address:</label>
        <input type="text" id="wsAddress" value="wss://haoweivmsea.southeastasia.cloudapp.azure.com:12222/ws" placeholder="wss://localhost:8080/ws">
    </div>

    <div class="input-group">
        <label for="audioSource">Audio Source:</label>
        <select id="audioSource">
            <option value="microphone">Microphone</option>
            <option value="system">System Audio</option>
        </select>
    </div>

    <div id="status" class="connected">Disconnected</div>

    <div id="controls">
        <div class="controls-group">
            <button id="connectBtn" class="connect">Connect</button>
            <button id="disconnectBtn" class="disconnect" disabled>Disconnect</button>
        </div>
        <div class="controls-group">
            <button id="startBtn" class="start" disabled>Start Recording</button>
            <button id="stopBtn" class="stop" disabled>Stop Recording</button>
        </div>
        <div class="controls-group" style="flex-basis: 100%;">
            <button id="downloadBtn" class="download">Download Recorded Audio</button>
        </div>
    </div>

    <div id="log"></div>

    <!-- 隐藏的视频元素，用于捕获系统音频 -->
    <video id="sharedVideo" autoplay></video>
</div>

<script>
    let socket = null;
    let isRecording = false;
    let audioContext = null;
    let mediaStream = null;
    let sourceNode = null;
    let processorNode = null;
    let recordedChunks = [];

    const wsAddressInput = document.getElementById('wsAddress');
    const connectBtn = document.getElementById('connectBtn');
    const disconnectBtn = document.getElementById('disconnectBtn');
    const startBtn = document.getElementById('startBtn');
    const stopBtn = document.getElementById('stopBtn');
    const downloadBtn = document.getElementById('downloadBtn');
    const statusDiv = document.getElementById('status');
    const logDiv = document.getElementById('log');
    const audioSourceSelect = document.getElementById('audioSource');
    const sharedVideo = document.getElementById('sharedVideo');

    // 更新状态和日志
    function updateStatus(status, message) {
        statusDiv.className = '';
        statusDiv.classList.add(status);
        statusDiv.textContent = message;
        log(message);
    }

    function log(message) {
        const timestamp = new Date().toLocaleTimeString();
        logDiv.textContent += `[${timestamp}] ${message}\n`;
        logDiv.scrollTop = logDiv.scrollHeight;
    }

    // 初始化 WebSocket 连接
    function connectWebSocket() {
        const wsAddress = wsAddressInput.value.trim();
        if (!wsAddress) {
            alert('请填写有效的 WebSocket 地址');
            return;
        }

        try {
            socket = new WebSocket(wsAddress);
        } catch (error) {
            log('WebSocket 创建失败: ' + error.message);
            updateStatus('error', 'WebSocket 创建失败');
            return;
        }

        updateStatus('connecting', 'Connecting to ' + wsAddress + '...');

        socket.onopen = () => {
            updateStatus('connected', 'Connected to ' + wsAddress);
            log('WebSocket connected');
            connectBtn.disabled = true;
            disconnectBtn.disabled = false;
            startBtn.disabled = false;
        };

        socket.onmessage = (event) => {
            try {
                const response = JSON.parse(event.data);
                log(`Received: ${event.data}`);

                if (response.type === 'AUDIO_FORMAT_ACK') {
                    log('Server is ready to receive audio data');
                } else if (response.type === 'CONNECTED') {
                    log(`Session ID: ${response.data.sessionId}`);
                } else if (response.type === 'ERROR') {
                    log(`Error: ${response.message}`);
                }
            } catch (e) {
                log('Received non-JSON message');
            }
        };

        socket.onerror = (error) => {
            updateStatus('error', 'WebSocket error');
            log('WebSocket error');
        };

        socket.onclose = (event) => {
            updateStatus('error', 'WebSocket disconnected');
            log(`WebSocket closed: Code=${event.code}, Reason=${event.reason}`);
            connectBtn.disabled = false;
            disconnectBtn.disabled = true;
            startBtn.disabled = true;
            stopBtn.disabled = true;
            if (isRecording) {
                stopRecording();
            }
        };
    }

    // 断开 WebSocket 连接
    function disconnectWebSocket() {
        if (socket && socket.readyState === WebSocket.OPEN) {
            socket.close();
        }
    }

    // 检查浏览器是否支持 getDisplayMedia
    function isGetDisplayMediaSupported() {
        return !!navigator.mediaDevices && !!navigator.mediaDevices.getDisplayMedia;
    }

    // 启用或禁用系统音频选项
    function toggleSystemAudioOption() {
        const systemOption = audioSourceSelect.querySelector('option[value="system"]');
        if (!isGetDisplayMediaSupported()) {
            systemOption.disabled = true;
            systemOption.textContent += ' (Not Supported)';
            if (audioSourceSelect.value === 'system') {
                audioSourceSelect.value = 'microphone';
            }
        } else {
            systemOption.disabled = false;
            systemOption.textContent = 'System Audio';
        }
    }

    // 开始录音
    async function startRecording() {
        const audioSource = audioSourceSelect.value;
        try {
            if (audioSource === 'microphone') {
                mediaStream = await navigator.mediaDevices.getUserMedia({ audio: true });
            } else if (audioSource === 'system') {
                // 使用 getDisplayMedia 捕获系统音频和视频（视频作为占位符）
                mediaStream = await navigator.mediaDevices.getDisplayMedia({
                    audio: true,
                    video: true
                });
                log('请在弹出的窗口中选择要共享的屏幕/窗口/标签页，并确保该窗口正在播放音频。');
                // 显示共享的视频
                sharedVideo.srcObject = mediaStream;
                sharedVideo.style.display = 'block';
            }

            audioContext = new (window.AudioContext || window.webkitAudioContext)();

            // 创建 MediaStreamAudioSourceNode
            sourceNode = audioContext.createMediaStreamSource(mediaStream);

            // 设置音频格式
            const formatMessage = {
                type: "SET_AUDIO_FORMAT",
                sample_rate: audioContext.sampleRate,
                sample_size: 16, // 输出16位PCM
                channels: 1,     // 单声道
                big_endian: false
            };
            socket.send(JSON.stringify(formatMessage));
            log('Sent audio format: ' + JSON.stringify(formatMessage));

            // 创建 ScriptProcessorNode
            processorNode = audioContext.createScriptProcessor(4096, 1, 1);

            // 连接节点
            sourceNode.connect(processorNode);
            processorNode.connect(audioContext.destination);

            // 音频处理回调
            processorNode.onaudioprocess = (event) => {
                if (!isRecording) return;

                const inputData = event.inputBuffer.getChannelData(0);
                const pcmData = convertFloat32ToInt16(inputData);
                socket.send(pcmData);
                recordedChunks.push(new Int16Array(pcmData.buffer));
            };

            isRecording = true;
            startBtn.disabled = true;
            stopBtn.disabled = false;
            connectBtn.disabled = true;
            downloadBtn.style.display = 'none';
            audioSourceSelect.disabled = true;
            updateStatus('connected', 'Recording...');
            log('Recording started');
        } catch (err) {
            if (err.name === 'NotAllowedError' || err.name === 'PermissionDeniedError') {
                log('用户拒绝访问音频源');
                updateStatus('error', 'Audio access denied');
            } else if (err.name === 'AbortError') {
                log('录音操作被中止');
                updateStatus('error', 'Recording aborted');
            } else if (err.message.includes('Unable to find a suitable')) {
                log('系统音频捕获不支持该操作');
                updateStatus('error', 'System audio capture not supported');
            } else {
                log('Error accessing audio source: ' + err.message);
                updateStatus('error', 'Audio access denied');
            }
        }
    }

    // 停止录音
    function stopRecording() {
        if (mediaStream) {
            mediaStream.getTracks().forEach(track => track.stop());
        }
        if (sourceNode) {
            sourceNode.disconnect();
        }
        if (processorNode) {
            processorNode.disconnect();
        }
        if (audioContext) {
            audioContext.close();
        }

        isRecording = false;
        startBtn.disabled = false;
        stopBtn.disabled = true;
        downloadBtn.style.display = recordedChunks.length ? 'inline-block' : 'none';
        audioSourceSelect.disabled = false;
        sharedVideo.style.display = 'none';
        updateStatus('connected', 'Recording stopped');
        log('Recording stopped');
    }

    // 下载录制的音频
    function downloadAudio() {
        if (recordedChunks.length === 0) return;

        // 合并所有录制的 Int16Array
        let length = 0;
        recordedChunks.forEach(chunk => { length += chunk.length; });
        let merged = new Int16Array(length);
        let offset = 0;
        recordedChunks.forEach(chunk => {
            merged.set(chunk, offset);
            offset += chunk.length;
        });

        // 创建 WAV 文件
        const wavBlob = int16ToWav(merged, audioContext.sampleRate);
        const url = URL.createObjectURL(wavBlob);
        const a = document.createElement('a');
        a.style.display = 'none';
        a.href = url;
        a.download = 'recorded_audio.wav';
        document.body.appendChild(a);
        a.click();
        window.URL.revokeObjectURL(url);
        log('Audio downloaded');
    }

    // 将 Int16Array 转换为 WAV Blob
    function int16ToWav(int16Array, sampleRate) {
        const buffer = new ArrayBuffer(44 + int16Array.length * 2);
        const view = new DataView(buffer);

        /* RIFF identifier */
        writeString(view, 0, 'RIFF');
        /* file length */
        view.setUint32(4, 36 + int16Array.length * 2, true);
        /* RIFF type */
        writeString(view, 8, 'WAVE');
        /* format chunk identifier */
        writeString(view, 12, 'fmt ');
        /* format chunk length */
        view.setUint32(16, 16, true);
        /* sample format (raw) */
        view.setUint16(20, 1, true);
        /* channel count */
        view.setUint16(22, 1, true);
        /* sample rate */
        view.setUint32(24, sampleRate, true);
        /* byte rate (sample rate * block align) */
        view.setUint32(28, sampleRate * 2, true);
        /* block align (channel count * bytes per sample) */
        view.setUint16(32, 2, true);
        /* bits per sample */
        view.setUint16(34, 16, true);
        /* data chunk identifier */
        writeString(view, 36, 'data');
        /* data chunk length */
        view.setUint32(40, int16Array.length * 2, true);

        // Write the PCM samples
        let offset = 44;
        for (let i = 0; i < int16Array.length; i++, offset += 2) {
            view.setInt16(offset, int16Array[i], true);
        }

        return new Blob([view], { type: 'audio/wav' });
    }

    function writeString(view, offset, string) {
        for (let i = 0; i < string.length; i++) {
            view.setUint8(offset + i, string.charCodeAt(i));
        }
    }

    // 将Float32数组转换为16位PCM Blob
    function convertFloat32ToInt16(buffer) {
        const int16Buffer = new Int16Array(buffer.length);
        for (let i = 0; i < buffer.length; i++) {
            const s = Math.max(-1, Math.min(1, buffer[i]));
            int16Buffer[i] = s < 0 ? s * 0x8000 : s * 0x7FFF;
        }
        return new Blob([int16Buffer.buffer], { type: 'application/octet-stream' });
    }

    // 事件监听
    connectBtn.addEventListener('click', () => {
        connectWebSocket();
    });

    disconnectBtn.addEventListener('click', () => {
        disconnectWebSocket();
    });

    startBtn.addEventListener('click', () => {
        startRecording();
    });

    stopBtn.addEventListener('click', () => {
        stopRecording();
    });

    downloadBtn.addEventListener('click', () => {
        downloadAudio();
    });

    // 清理资源 on page unload
    window.addEventListener('beforeunload', () => {
        if (socket && socket.readyState === WebSocket.OPEN) {
            socket.close();
        }
        if (isRecording) {
            stopRecording();
        }
    });

    // 在页面加载时检查浏览器对 System Audio 的支持
    window.addEventListener('load', () => {
        toggleSystemAudioOption();
    });
</script>
</body>
</html>

```

10. 程序配置文件 `src/main/resources/application.properties`

```
spring.application.name=NetEarphone

# logging configuration
logging.level.root=INFO
logging.level.org.whw23.netearphone=DEBUG
logging.pattern.console=%d{yyyy-MM-dd HH:mm:ss} [%thread] %-5level %logger{36} - %msg%n

```
