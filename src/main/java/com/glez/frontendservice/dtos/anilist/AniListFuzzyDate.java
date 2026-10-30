package com.glez.frontendservice.dtos.anilist;

import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Value;

@Value
public class AniListFuzzyDate {
    Integer year;
    Integer month;
    Integer day;
}