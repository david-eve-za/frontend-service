package com.glez.frontendservice.dtos.anilist;

import lombok.Value;

import java.util.List;

@Value
public class AniListGraphQLResponse {
    AniListData data;
    List<AniListError> errors;
}