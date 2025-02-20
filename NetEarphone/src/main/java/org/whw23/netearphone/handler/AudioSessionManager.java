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
