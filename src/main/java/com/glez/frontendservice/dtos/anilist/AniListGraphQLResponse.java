package com.glez.frontendservice.dtos.anilist;

import lombok.Value;

@Value
public class AniListGraphQLResponse {
    AniListData data;
    List<AniListError> errors;
}