package com.glez.frontendservice.dtos.anilist;

import lombok.Value;

import java.util.List;

@Value
public class AniListPageInfo {
    Integer total;
    Integer currentPage;
    Integer lastPage;
    Boolean hasNextPage;
    @JsonProperty("perPage")
    Integer perPage;
}