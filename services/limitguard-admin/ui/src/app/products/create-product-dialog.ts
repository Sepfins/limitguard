import {
  ChangeDetectionStrategy,
  Component,
  ElementRef,
  input,
  output,
  signal,
  viewChild
} from '@angular/core';
import { FormControl, FormGroup, ReactiveFormsModule, Validators } from '@angular/forms';
import { LucideAngularModule } from 'lucide-angular';
import { finalize } from 'rxjs';
import { apiErrorMessage } from '../core/api-error';
import {
  CreateFundingSourceProductRequest,
  FundingSourceCategory,
  FundingSourceProduct,
  ProductNodeType
} from '../core/admin-api.models';
import { AdminApiService } from '../core/admin-api.service';

@Component({
  selector: 'app-create-product-dialog',
  imports: [ReactiveFormsModule, LucideAngularModule],
  templateUrl: './create-product-dialog.html',
  styleUrl: '../shared/dialog.css',
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class CreateProductDialog {
  readonly tenantId = input.required<string>();
  readonly parentOptions = input<FundingSourceProduct[]>([]);
  readonly created = output<FundingSourceProduct>();
  private readonly dialog = viewChild.required<ElementRef<HTMLDialogElement>>('dialog');
  protected readonly submitting = signal(false);
  protected readonly error = signal<string | null>(null);
  protected readonly categories: FundingSourceCategory[] = [
    'TRANSACTIONAL',
    'SAVINGS',
    'TERM_DEPOSIT',
    'PREPAID',
    'CREDIT',
    'SECURITIES',
    'EWALLET'
  ];
  protected readonly nodeTypes: ProductNodeType[] = ['FAMILY', 'SUBFAMILY', 'PRODUCT'];
  protected readonly form = new FormGroup({
    code: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(100)]
    }),
    name: new FormControl('', {
      nonNullable: true,
      validators: [Validators.required, Validators.maxLength(255)]
    }),
    category: new FormControl<FundingSourceCategory>('TRANSACTIONAL', { nonNullable: true }),
    nodeType: new FormControl<ProductNodeType>('PRODUCT', { nonNullable: true }),
    parentId: new FormControl<string>(''),
    isLeaf: new FormControl(true, { nonNullable: true }),
    isActive: new FormControl(true, { nonNullable: true })
  });

  constructor(private readonly api: AdminApiService) {}

  open(): void {
    this.form.reset({
      code: '',
      name: '',
      category: 'TRANSACTIONAL',
      nodeType: 'PRODUCT',
      parentId: '',
      isLeaf: true,
      isActive: true
    });
    this.error.set(null);
    this.dialog().nativeElement.showModal();
  }

  close(): void {
    if (!this.submitting()) this.dialog().nativeElement.close();
  }

  submit(): void {
    if (this.form.invalid || this.submitting()) {
      this.form.markAllAsTouched();
      return;
    }
    const value = this.form.getRawValue();
    const request: CreateFundingSourceProductRequest = {
      ...value,
      parentId: value.parentId || null
    };
    this.submitting.set(true);
    this.error.set(null);
    this.api
      .createFundingSourceProduct(this.tenantId(), request)
      .pipe(finalize(() => this.submitting.set(false)))
      .subscribe({
        next: (product) => {
          this.created.emit(product);
          this.dialog().nativeElement.close();
        },
        error: (error) => this.error.set(apiErrorMessage(error, 'The product could not be created.'))
      });
  }

  protected label(value: string): string {
    return value.replaceAll('_', ' ');
  }
}
