import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import { map } from 'rxjs/operators';

export interface SourceMetadata {
  id: string;
  displayName: string;
  icon: string;
  language: string;
  baseUrl: string;
  rateLimitRpm: number;
  requiresLogin: boolean;
  enabled: boolean;
  tags: string[];
}

export interface NovelSearchResult {
  title: string;
  url: string;
  info: string;
  coverUrl: string;
  sourceNovelId: string;
}

export interface NovelDetail {
  title: string;
  author: string;
  coverUrl: string;
  synopsis: string;
  tags: string[];
  volumes: VolumeInfo[];
  language: string;
  isMtl: boolean;
  isManga: boolean;
  sourceNovelId: string;
  status: string;
  lastUpdate: string;
}

export interface VolumeInfo {
  id: number;
  title: string;
  chapters: ChapterInfo[];
}

export interface ChapterInfo {
  id: number;
  title: string;
  url: string;
  volumeId: number;
}

export interface ChapterContent {
  html: string;
  images: string[];
  success: boolean;
  errorMessage: string;
}

@Injectable({ providedIn: 'root' })
export class SourcesService {
  private readonly API_URL = '/api/sources';

  constructor(private http: HttpClient) {}

  getAllSources(): Observable<SourceMetadata[]> {
    return this.http.get<SourceMetadata[]>(this.API_URL);
  }

  getSource(sourceId: string): Observable<SourceMetadata> {
    return this.http.get<SourceMetadata>(`${this.API_URL}/${sourceId}`);
  }

  search(sourceId: string, query: string): Observable<NovelSearchResult[]> {
    const params = new HttpParams().set('q', query);
    return this.http.get<NovelSearchResult[]>(`${this.API_URL}/${sourceId}/search`, { params });
  }

  getNovelDetails(sourceId: string, novelId: string): Observable<NovelDetail> {
    return this.http.get<NovelDetail>(`${this.API_URL}/${sourceId}/novels/${novelId}`);
  }

  getChapters(sourceId: string, novelId: string): Observable<ChapterInfo[]> {
    return this.http.get<ChapterInfo[]>(`${this.API_URL}/${sourceId}/novels/${novelId}/chapters`);
  }

  getChapterContent(sourceId: string, novelId: string, chapterId: string): Observable<ChapterContent> {
    return this.http.get<ChapterContent>(`${this.API_URL}/${sourceId}/novels/${novelId}/chapters/${chapterId}`);
  }

  healthCheck(sourceId: string): Observable<{ [key: string]: boolean }> {
    return this.http.get<{ [key: string]: boolean }>(`${this.API_URL}/${sourceId}/health`);
  }

  healthCheckAll(): Observable<{ [key: string]: boolean }> {
    return this.http.get<{ [key: string]: boolean }>(`${this.API_URL}/health`);
  }
}