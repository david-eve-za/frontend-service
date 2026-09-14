import { Injectable } from '@angular/core';
import { HttpClient, HttpParams } from '@angular/common/http';
import { Observable, of, BehaviorSubject } from 'rxjs';
import { tap, catchError } from 'rxjs/operators';

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
  
  // Cache for enabled patterns - cache for 5 minutes
  private enabledPatternsCache$ = new BehaviorSubject<RegexPattern[] | null>(null);
  private cacheTimestamp: number = 0;
  private readonly CACHE_TTL = 5 * 60 * 1000; // 5 minutes

  constructor(private http: HttpClient) {}

  private isCacheValid(): boolean {
    const cache = this.enabledPatternsCache$.value;
    if (!cache) return false;
    return Date.now() - this.cacheTimestamp < this.CACHE_TTL;
  }

  getEnabled(): Observable<RegexPattern[]> {
    // Return cached data if valid
    if (this.isCacheValid()) {
      return of(this.enabledPatternsCache$.value!);
    }

    // Fetch from API and cache
    return this.http.get<RegexPattern[]>(`${this.API_URL}/enabled`).pipe(
      tap(patterns => {
        this.enabledPatternsCache$.next(patterns);
        this.cacheTimestamp = Date.now();
      }),
      catchError(err => {
        // If error and we have stale cache, return stale cache
        const cache = this.enabledPatternsCache$.value;
        if (cache) {
          return of(cache);
        }
        throw err;
      })
    );
  }

  // Force refresh cache
  refreshCache(): Observable<RegexPattern[]> {
    this.enabledPatternsCache$.next(null);
    this.cacheTimestamp = 0;
    return this.getEnabled();
  }

  getAll(page = 0, size = 20): Observable<any> {
    const params = new HttpParams()
      .set('page', page.toString())
      .set('size', size.toString());
    return this.http.get(`${this.API_URL}`, { params });
  }

  getEnabledByType(type: string): Observable<any> {
    return this.http.get(`${this.API_URL}/enabled/type/${type}`);
  }

  getById(id: string): Observable<any> {
    return this.http.get(`${this.API_URL}/${id}`);
  }

  create(pattern: any): Observable<any> {
    return this.http.post(this.API_URL, pattern).pipe(
      tap(() => this.invalidateCache())
    );
  }

  update(id: string, pattern: any): Observable<any> {
    return this.http.put(`${this.API_URL}/${id}`, pattern).pipe(
      tap(() => this.invalidateCache())
    );
  }

  toggle(id: string): Observable<any> {
    return this.http.patch(`${this.API_URL}/${id}/toggle`, {}).pipe(
      tap(() => this.invalidateCache())
    );
  }

  delete(id: string): Observable<any> {
    return this.http.delete(`${this.API_URL}/${id}`).pipe(
      tap(() => this.invalidateCache())
    );
  }

  validate(pattern: string): Observable<any> {
    return this.http.post(`${this.API_URL}/validate`, { pattern });
  }

  private invalidateCache(): void {
    this.enabledPatternsCache$.next(null);
    this.cacheTimestamp = 0;
  }
}