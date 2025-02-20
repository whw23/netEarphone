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
