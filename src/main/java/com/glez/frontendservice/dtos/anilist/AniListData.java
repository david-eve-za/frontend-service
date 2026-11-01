package com.glez.frontendservice.dtos.anilist;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;

import java.util.List;

@Value
public class AniListData {
    @JsonProperty("Page")
    AniListPage page;
    
    @JsonProperty("Media")
    AniListMedia media;
    
    // Helper methods
    public boolean hasPage() {
        return page != null && page.getMedia() != null && !page.getMedia().isEmpty();
    }
    
    public boolean hasMedia() {
        return media != null;
    }
    
    public List<AniListMedia> getMediaList() {
        if (hasPage()) {
            return page.getMedia();
        } else if (hasMedia()) {
            return List.of(media);
        }
        return List.of();
    }
    
    public AniListMedia getSingleMedia() {
        return media;
    }
}