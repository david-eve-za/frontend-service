package com.glez.frontendservice.dtos.anilist;

import lombok.Value;

@Value
public class AniListErrorLocation {
    Integer line;
    Integer column;
}