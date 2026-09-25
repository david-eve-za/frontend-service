package com.glez.frontendservice.model;

import java.util.List;

public record BlockSaveRequest(String documentId, String rawText, List<BlockItem> blocks) {

    public record BlockItem(String type, Integer id, String content) {}
}
