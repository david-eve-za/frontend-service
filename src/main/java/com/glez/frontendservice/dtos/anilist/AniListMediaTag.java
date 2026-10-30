package com.glez.frontendservice.dtos.anilist;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;

@Value
public class AniListMediaTag {
    String name;
    String description;
    @JsonProperty("category")
    String category;
    Integer rank;
    Boolean isGeneralSpoiler;
    Boolean isMediaSpoiler;
    @JsonProperty("isAdult")
    Boolean isAdult;
}