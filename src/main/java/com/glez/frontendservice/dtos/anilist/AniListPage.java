package com.glez.frontendservice.dtos.anilist;

import lombok.Value;

import java.util.List;

@Value
public class AniListPage {
    AniListPageInfo pageInfo;
    List<AniListMedia> media;
}