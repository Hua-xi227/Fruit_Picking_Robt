package org.example.fruitpickingrobt.dto;

import lombok.Data;

@Data
public class VoiceResponse {
    private String text;           // 识别出的文字
    private String answer;         // 回答文字
    private String audioBase64;    // 回答的音频（base64编码）
}