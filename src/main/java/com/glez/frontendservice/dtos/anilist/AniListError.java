package com.glez.frontendservice.dtos.anilist;

import lombok.Value;

import java.util.List;

@Value
public class AniListError {
    String message;
    List<AniListErrorLocation> locations;
    String path;
}