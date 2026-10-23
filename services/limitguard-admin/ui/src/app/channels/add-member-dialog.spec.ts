import { importProvidersFrom } from '@angular/core';
import { TestBed } from '@angular/core/testing';
import { LucideAngularModule, X } from 'lucide-angular';
import { of } from 'rxjs';
import { AdminApiService } from '../core/admin-api.service';
import { AddMemberDialog } from './add-member-dialog';

describe('AddMemberDialog', () => {
  const tenantId = '61f2e9e5-6651-47df-9f37-e463669020db';
  const channel = {
    id: '6f4c1a7e-2b7d-4f5e-9a51-0c3c1e2d9b10',
    code: 'ATM',
    name: 'ATM',
    isActive: true,
    createdAt: '2026-08-09T10:00:00Z'
  };
  const group = {
    id: 'a3b2c1d0-1111-4222-8333-944455556666',
    code: 'PHY',
    name: 'Physical',
    memberChannelIds: [],
    createdAt: '2026-08-09T10:00:00Z'
  };
  const api = { addChannelGroupMember: vi.fn() };

  beforeAll(() => {
    HTMLDialogElement.prototype.close ??= vi.fn();
    HTMLDialogElement.prototype.showModal ??= vi.fn();
  });

  beforeEach(async () => {
    api.addChannelGroupMember.mockReset().mockReturnValue(of({}));
    await TestBed.configureTestingModule({
      imports: [AddMemberDialog],
      providers: [
        { provide: AdminApiService, useValue: api },
        importProvidersFrom(LucideAngularModule.pick({ X }))
      ]
    }).compileComponents();
  });

  it('adds the chosen channel when the form is submitted', () => {
    const fixture = TestBed.createComponent(AddMemberDialog);
    fixture.componentRef.setInput('tenantId', tenantId);
    fixture.componentRef.setInput('channels', [channel]);
    fixture.detectChanges();
    const component = fixture.componentInstance as any;
    component.open(group);
    component.channelId.setValue(channel.id);
    fixture.detectChanges();

    (fixture.nativeElement.querySelector('button[type="submit"]') as HTMLButtonElement).click();

    expect(api.addChannelGroupMember).toHaveBeenCalledWith(tenantId, group.id, channel.id);
  });
});
