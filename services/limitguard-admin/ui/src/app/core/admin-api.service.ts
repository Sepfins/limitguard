import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import {
  Channel,
  ChannelGroup,
  CodeNameRequest,
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

  listChannels(
    tenantId: string,
    search: string,
    active: boolean | null,
    page: number,
    size = 25
  ): Observable<PageResponse<Channel>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (search.trim()) params = params.set('search', search.trim());
    if (active !== null) params = params.set('active', active);
    return this.http.get<PageResponse<Channel>>(`${this.baseUrl}/tenants/${tenantId}/channels`, { params });
  }

  registerChannel(tenantId: string, request: CodeNameRequest): Observable<Channel> {
    return this.http.post<Channel>(`${this.baseUrl}/tenants/${tenantId}/channels`, request);
  }

  setChannelActive(tenantId: string, channelId: string, isActive: boolean): Observable<Channel> {
    return this.http.put<Channel>(`${this.baseUrl}/tenants/${tenantId}/channels/${channelId}/active`, {
      isActive
    });
  }

  listChannelGroups(
    tenantId: string,
    search: string,
    page: number,
    size = 25
  ): Observable<PageResponse<ChannelGroup>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (search.trim()) params = params.set('search', search.trim());
    return this.http.get<PageResponse<ChannelGroup>>(`${this.baseUrl}/tenants/${tenantId}/channel-groups`, {
      params
    });
  }

  createChannelGroup(tenantId: string, request: CodeNameRequest): Observable<ChannelGroup> {
    return this.http.post<ChannelGroup>(`${this.baseUrl}/tenants/${tenantId}/channel-groups`, request);
  }

  addChannelGroupMember(tenantId: string, channelGroupId: string, channelId: string): Observable<unknown> {
    return this.http.post(`${this.baseUrl}/tenants/${tenantId}/channel-groups/${channelGroupId}/members`, {
      channelId
    });
  }
}
