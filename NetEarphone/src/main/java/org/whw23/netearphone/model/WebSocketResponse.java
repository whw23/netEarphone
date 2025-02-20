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
