package com.glez.frontendservice.dtos.anilist;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;

@Value
public class AniListMediaTitle {
    String romaji;
    String english;
    String nativeLanguage;

    @JsonProperty("userPreferred")
    String userPreferred;
}