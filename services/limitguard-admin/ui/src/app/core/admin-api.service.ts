import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import {
  CreateFundingSourceProductRequest,
  CreateTenantRequest,
  FundingSourceProduct,
  MeResponse,
  PageResponse,
  Tenant
} from './admin-api.models';

@Injectable({ providedIn: 'root' })
export class AdminApiService {
  private readonly baseUrl = '/limitguard/admin/api/v1';

  constructor(private readonly http: HttpClient) {}

  me(): Observable<MeResponse> {
    return this.http.get<MeResponse>(`${this.baseUrl}/me`);
  }

  listTenants(search: string, page: number, size = 25): Observable<PageResponse<Tenant>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (search.trim()) params = params.set('search', search.trim());
    return this.http.get<PageResponse<Tenant>>(`${this.baseUrl}/tenants`, { params });
  }

  createTenant(request: CreateTenantRequest): Observable<Tenant> {
    return this.http.post<Tenant>(`${this.baseUrl}/tenants`, request);
  }

  listFundingSourceProducts(
    tenantId: string,
    search: string,
    active: boolean | null,
    page: number,
    size = 25
  ): Observable<PageResponse<FundingSourceProduct>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (search.trim()) params = params.set('search', search.trim());
    if (active !== null) params = params.set('active', active);
    return this.http.get<PageResponse<FundingSourceProduct>>(
      `${this.baseUrl}/tenants/${tenantId}/funding-source-products`,
      { params }
    );
  }

  createFundingSourceProduct(
    tenantId: string,
    request: CreateFundingSourceProductRequest
  ): Observable<FundingSourceProduct> {
    return this.http.post<FundingSourceProduct>(
      `${this.baseUrl}/tenants/${tenantId}/funding-source-products`,
      request
    );
  }
}
