import { HttpClient, HttpParams } from '@angular/common/http';
import { Injectable } from '@angular/core';
import { Observable } from 'rxjs';
import {
  Channel,
  ChannelGroup,
  CodeNameRequest,
  CreateFundingSourceProductRequest,
  CreateLimitChangePolicyRequest,
  CreateLimitConfigurationRequest,
  CreateOperationTypeRequest,
  CreateTenantRequest,
  FundingSourceProduct,
  LimitChangePolicy,
  LimitConfiguration,
  MeResponse,
  OperationType,
  PageResponse,
  Tenant,
  UpdateLimitConfigurationRequest
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

  listOperationTypes(
    tenantId: string,
    search: string,
    leaf: boolean | null,
    page: number,
    size = 25
  ): Observable<PageResponse<OperationType>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (search.trim()) params = params.set('search', search.trim());
    if (leaf !== null) params = params.set('leaf', leaf);
    return this.http.get<PageResponse<OperationType>>(`${this.baseUrl}/tenants/${tenantId}/operation-types`, {
      params
    });
  }

  registerOperationType(tenantId: string, request: CreateOperationTypeRequest): Observable<OperationType> {
    return this.http.post<OperationType>(`${this.baseUrl}/tenants/${tenantId}/operation-types`, request);
  }

  moveOperationType(tenantId: string, operationTypeId: string, parentId: string | null): Observable<OperationType> {
    return this.http.put<OperationType>(
      `${this.baseUrl}/tenants/${tenantId}/operation-types/${operationTypeId}/parent`,
      { parentId }
    );
  }

  listLimitConfigurations(
    tenantId: string,
    search: string,
    active: boolean | null,
    parentConfigurationId: string | null,
    page: number,
    size = 25
  ): Observable<PageResponse<LimitConfiguration>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (search.trim()) params = params.set('search', search.trim());
    if (active !== null) params = params.set('active', active);
    if (parentConfigurationId) params = params.set('parentConfigurationId', parentConfigurationId);
    return this.http.get<PageResponse<LimitConfiguration>>(
      `${this.baseUrl}/tenants/${tenantId}/limit-configurations`,
      { params }
    );
  }

  createLimitConfiguration(
    tenantId: string,
    request: CreateLimitConfigurationRequest
  ): Observable<LimitConfiguration> {
    return this.http.post<LimitConfiguration>(`${this.baseUrl}/tenants/${tenantId}/limit-configurations`, request);
  }

  updateLimitConfiguration(
    tenantId: string,
    configurationId: string,
    request: UpdateLimitConfigurationRequest
  ): Observable<LimitConfiguration> {
    return this.http.put<LimitConfiguration>(
      `${this.baseUrl}/tenants/${tenantId}/limit-configurations/${configurationId}`,
      request
    );
  }

  setLimitConfigurationActive(
    tenantId: string,
    configurationId: string,
    isActive: boolean
  ): Observable<LimitConfiguration> {
    return this.http.put<LimitConfiguration>(
      `${this.baseUrl}/tenants/${tenantId}/limit-configurations/${configurationId}/active`,
      { isActive }
    );
  }

  listLimitChangePolicies(
    tenantId: string,
    limitConfigurationId: string | null,
    page: number,
    size = 25
  ): Observable<PageResponse<LimitChangePolicy>> {
    let params = new HttpParams().set('page', page).set('size', size);
    if (limitConfigurationId) params = params.set('limitConfigurationId', limitConfigurationId);
    return this.http.get<PageResponse<LimitChangePolicy>>(`${this.baseUrl}/tenants/${tenantId}/limit-change-policies`, {
      params
    });
  }

  createLimitChangePolicy(tenantId: string, request: CreateLimitChangePolicyRequest): Observable<LimitChangePolicy> {
    return this.http.post<LimitChangePolicy>(`${this.baseUrl}/tenants/${tenantId}/limit-change-policies`, request);
  }
}
