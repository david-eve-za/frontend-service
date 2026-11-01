package com.glez.frontendservice.dtos.anilist;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;
import lombok.Value;

import java.util.List;

@Value
public class AniListMedia {

    Integer id;
    Integer idMal;
    AniListMediaTitle title;
    String description;
    String format;
    String status;
    Integer episodes;
    Integer chapters;
    Integer volumes;
    String countryOfOrigin;
    
    @JsonDeserialize(using = StringListDeserializer.class)
    List<String> genres;
    
    List<AniListMediaTag> tags;
    
    AniListCoverImage coverImage;
    Integer averageScore;
    Integer meanScore;
    Integer popularity;
    Boolean isAdult;
    String source;
    
    @JsonProperty("startDate")
    AniListFuzzyDate startDate;
    
    @JsonProperty("endDate")
    AniListFuzzyDate endDate;
    
    @JsonProperty("season")
    String season;
    
    @JsonProperty("seasonYear")
    Integer seasonYear;
}