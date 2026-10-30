package com.glez.frontendservice.dtos.anilist;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;

@Value
public class AniListCoverImage {
    String large;
    String medium;
    String extraLarge;

    @JsonProperty("color")
    String color;
}