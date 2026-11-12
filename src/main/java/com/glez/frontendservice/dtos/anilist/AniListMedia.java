package com.glez.frontendservice.dtos.anilist;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.annotation.JsonDeserialize;

import java.util.List;

/**
 * AniList Media DTO - immutable data class for AniList GraphQL responses.
 */
public class AniListMedia {

    private Integer id;
    private Integer idMal;
    private AniListMediaTitle title;
    private String description;
    private String format;
    private String status;
    private Integer episodes;
    private Integer chapters;
    private Integer volumes;
    private String countryOfOrigin;

    @JsonDeserialize(using = StringListDeserializer.class)
    private List<String> genres;

    private List<AniListMediaTag> tags;

    private AniListCoverImage coverImage;
    private Integer averageScore;
    private Integer meanScore;
    private Integer popularity;
    private Boolean isAdult;
    private String source;

    @JsonProperty("startDate")
    private AniListFuzzyDate startDate;

    @JsonProperty("endDate")
    private AniListFuzzyDate endDate;

    @JsonProperty("season")
    private String season;

    @JsonProperty("seasonYear")
    private Integer seasonYear;

    public Integer getId() {
        return id;
    }

    public void setId(Integer id) {
        this.id = id;
    }

    public Integer getIdMal() {
        return idMal;
    }

    public void setIdMal(Integer idMal) {
        this.idMal = idMal;
    }

    public AniListMediaTitle getTitle() {
        return title;
    }

    public void setTitle(AniListMediaTitle title) {
        this.title = title;
    }

    public String getDescription() {
        return description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public String getFormat() {
        return format;
    }

    public void setFormat(String format) {
        this.format = format;
    }

    public String getStatus() {
        return status;
    }

    public void setStatus(String status) {
        this.status = status;
    }

    public Integer getEpisodes() {
        return episodes;
    }

    public void setEpisodes(Integer episodes) {
        this.episodes = episodes;
    }

    public Integer getChapters() {
        return chapters;
    }

    public void setChapters(Integer chapters) {
        this.chapters = chapters;
    }

    public Integer getVolumes() {
        return volumes;
    }

    public void setVolumes(Integer volumes) {
        this.volumes = volumes;
    }

    public String getCountryOfOrigin() {
        return countryOfOrigin;
    }

    public void setCountryOfOrigin(String countryOfOrigin) {
        this.countryOfOrigin = countryOfOrigin;
    }

    public List<String> getGenres() {
        return genres;
    }

    public void setGenres(List<String> genres) {
        this.genres = genres;
    }

    public List<AniListMediaTag> getTags() {
        return tags;
    }

    public void setTags(List<AniListMediaTag> tags) {
        this.tags = tags;
    }

    public AniListCoverImage getCoverImage() {
        return coverImage;
    }

    public void setCoverImage(AniListCoverImage coverImage) {
        this.coverImage = coverImage;
    }

    public Integer getAverageScore() {
        return averageScore;
    }

    public void setAverageScore(Integer averageScore) {
        this.averageScore = averageScore;
    }

    public Integer getMeanScore() {
        return meanScore;
    }

    public void setMeanScore(Integer meanScore) {
        this.meanScore = meanScore;
    }

    public Integer getPopularity() {
        return popularity;
    }

    public void setPopularity(Integer popularity) {
        this.popularity = popularity;
    }

    public Boolean getIsAdult() {
        return isAdult;
    }

    public void setIsAdult(Boolean isAdult) {
        this.isAdult = isAdult;
    }

    public String getSource() {
        return source;
    }

    public void setSource(String source) {
        this.source = source;
    }

    public AniListFuzzyDate getStartDate() {
        return startDate;
    }

    public void setStartDate(AniListFuzzyDate startDate) {
        this.startDate = startDate;
    }

    public AniListFuzzyDate getEndDate() {
        return endDate;
    }

    public void setEndDate(AniListFuzzyDate endDate) {
        this.endDate = endDate;
    }

    public String getSeason() {
        return season;
    }

    public void setSeason(String season) {
        this.season = season;
    }

    public Integer getSeasonYear() {
        return seasonYear;
    }

    public void setSeasonYear(Integer seasonYear) {
        this.seasonYear = seasonYear;
    }
}