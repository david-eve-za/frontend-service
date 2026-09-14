import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

export interface RegexPattern {
  id?: string;
  name: string;
  displayName: string;
  description?: string;
  pattern: string;
  replacement?: string;
  patternType: 'URL' | 'SOCIAL_MEDIA' | 'ISBN' | 'PAGE_NUMBER' | 'WHITESPACE' | 'CUSTOM';
  enabled: boolean;
  caseInsensitive: boolean;
  multiline: boolean;
  dotAll: boolean;
  orderIndex: number;
  createdAt?: string;
  updatedAt?: string;
}

export interface PaginatedResponse<T> {
  content: T[];
  pageNumber: number;
  pageSize: number;
  totalElements: number;
  totalPages: number;
  last: boolean;
  first: boolean;
}

@Injectable({
  providedIn: 'root'
})
export class RegexPatternService {
  private readonly API_URL = '/api/regex-patterns';

  constructor(private http: HttpClient) {}

  getAll(page = 0, size = 20): Observable<PaginatedResponse<RegexPattern>> {
    const params = new HttpParams()
      .set('page', page.toString())
      .set('size', size.toString());
    return this.http.get<PaginatedResponse<RegexPattern>>(this.API_URL, { params });
  }

  getEnabled(): Observable<RegexPattern[]> {
    return this.http.get<RegexPattern[]>(`${this.API_URL}/enabled`);
  }

  getEnabledByType(type: string): Observable<RegexPattern[]> {
    return this.http.get<RegexPattern[]>(`${this.API_URL}/enabled/type/${type}`);
  }

  getById(id: string): Observable<RegexPattern> {
    return this.http.get<RegexPattern>(`${this.API_URL}/${id}`);
  }

  create(pattern: RegexPattern): Observable<RegexPattern> {
    return this.http.post<RegexPattern>(this.API_URL, pattern);
  }

  update(id: string, pattern: RegexPattern): Observable<RegexPattern> {
    return this.http.put<RegexPattern>(`${this.API_URL}/${id}`, pattern);
  }

  toggle(id: string): Observable<RegexPattern> {
    return this.http.patch<RegexPattern>(`${this.API_URL}/${id}/toggle`, {});
  }

  delete(id: string): Observable<void> {
    return this.http.delete<void>(`${this.API_URL}/${id}`);
  }

  validate(pattern: string): Observable<{ valid: boolean; message: string }> {
    return this.http.post<{ valid: boolean; message: string }>(`${this.API_URL}/validate`, { pattern });
  }
}
