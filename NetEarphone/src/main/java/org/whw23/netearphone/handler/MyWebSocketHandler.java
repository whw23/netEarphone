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
