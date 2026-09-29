import { HttpClient } from '@angular/common/http';
import { Component, OnInit, inject, signal } from '@angular/core';
import { NonNullableFormBuilder, ReactiveFormsModule, Validators } from '@angular/forms';
import { Observable, firstValueFrom } from 'rxjs';
import { apiErrorMessage, applyFieldErrors } from '../../core/api-errors';
import { FieldError } from '../../shared/ui/field-error';
import { digits } from '../../shared/digits';

export interface Address {
  id: number;
  label: string | null;
  recipientName: string;
  phone: string;
  postalCode: string;
  state: string;
  city: string;
  district: string;
  street: string;
  number: string;
  complement: string | null;
  reference: string | null;
  isDefault: boolean;
}

interface ViaCep {
  erro?: boolean;
  logradouro: string;
  bairro: string;
  localidade: string;
  uf: string;
}

const UFS = 'AC AL AP AM BA CE DF ES GO MA MT MS MG PA PB PR PE PI RJ RN RS RO RR SC SP SE TO'.split(' ');

@Component({
  selector: 'app-addresses',
  imports: [ReactiveFormsModule, FieldError],
  template: `
    <h1>Endereços</h1>

    @if (error(); as e) {
      <p class="form-error" role="alert">{{ e }}</p>
    }

    <ul class="cards">
      @for (a of addresses(); track a.id) {
        <li class="card">
          <p>
            <strong>{{ a.label || 'Endereço' }}</strong>
            @if (a.isDefault) {
              <span class="badge">Principal</span>
            }
          </p>
          <p>{{ a.recipientName }}</p>
          <p>{{ a.street }}, {{ a.number }}@if (a.complement) { - {{ a.complement }} }</p>
          <p>{{ a.district }} · {{ a.city }}/{{ a.state }} · CEP {{ a.postalCode }}</p>
          <div class="card-actions">
            <button type="button" class="link" (click)="edit(a)">Editar</button>
            @if (!a.isDefault) {
              <button type="button" class="link" (click)="makeDefault(a)">Tornar principal</button>
            }
            <button type="button" class="link danger" (click)="remove(a)">Remover</button>
          </div>
        </li>
      } @empty {
        <li class="empty">Nenhum endereço cadastrado.</li>
      }
    </ul>

    @if (editing() === null) {
      <button type="button" class="btn" (click)="edit(null)">Adicionar endereço</button>
    } @else {
      <form [formGroup]="form" (ngSubmit)="save()" novalidate class="form">
        <h2>{{ editing() ? 'Editar endereço' : 'Novo endereço' }}</h2>
        <label class="field">
          <span>CEP</span>
          <input formControlName="postalCode" inputmode="numeric" autocomplete="postal-code" (blur)="lookupCep()" />
          <app-field-error [control]="form.controls.postalCode" />
        </label>
        <label class="field">
          <span>Rua</span>
          <input formControlName="street" autocomplete="address-line1" />
          <app-field-error [control]="form.controls.street" />
        </label>
        <div class="row">
          <label class="field">
            <span>Número</span>
            <input formControlName="number" />
            <app-field-error [control]="form.controls.number" />
          </label>
          <label class="field">
            <span>Complemento</span>
            <input formControlName="complement" autocomplete="address-line2" />
          </label>
        </div>
        <label class="field">
          <span>Bairro</span>
          <input formControlName="district" />
          <app-field-error [control]="form.controls.district" />
        </label>
        <div class="row">
          <label class="field">
            <span>Cidade</span>
            <input formControlName="city" autocomplete="address-level2" />
            <app-field-error [control]="form.controls.city" />
          </label>
          <label class="field">
            <span>UF</span>
            <select formControlName="state" autocomplete="address-level1">
              <option value="">—</option>
              @for (uf of ufs; track uf) {
                <option [value]="uf">{{ uf }}</option>
              }
            </select>
            <app-field-error [control]="form.controls.state" />
          </label>
        </div>
        <label class="field">
          <span>Ponto de referência</span>
          <input formControlName="reference" />
        </label>
        <label class="field">
          <span>Destinatário</span>
          <input formControlName="recipientName" autocomplete="name" />
          <app-field-error [control]="form.controls.recipientName" />
        </label>
        <label class="field">
          <span>Telefone do destinatário</span>
          <input formControlName="phone" inputmode="tel" autocomplete="tel-national" />
          <app-field-error [control]="form.controls.phone" />
        </label>
        <label class="field">
          <span>Apelido <small>(ex.: Casa, Trabalho)</small></span>
          <input formControlName="label" />
        </label>
        <div class="card-actions">
          <button type="submit" class="btn" [disabled]="pending()">Salvar</button>
          <button type="button" class="link" (click)="editing.set(null)">Cancelar</button>
        </div>
      </form>
    }
  `,
})
export class Addresses implements OnInit {
  private readonly http = inject(HttpClient);
  protected readonly ufs = UFS;
  protected readonly addresses = signal<Address[]>([]);
  /** null = formulário fechado; 0 = novo; id = editando. */
  protected readonly editing = signal<number | null>(null);
  protected readonly pending = signal(false);
  protected readonly error = signal<string | null>(null);

  protected readonly form = inject(NonNullableFormBuilder).group({
    label: ['', Validators.maxLength(40)],
    recipientName: ['', [Validators.required, Validators.maxLength(120)]],
    phone: ['', [Validators.required, Validators.pattern(/^\(?\d{2}\)?\s?\d{4,5}-?\d{4}$/)]],
    postalCode: ['', [Validators.required, Validators.pattern(/^\d{5}-?\d{3}$/)]],
    state: ['', Validators.required],
    city: ['', Validators.required],
    district: ['', Validators.required],
    street: ['', Validators.required],
    number: ['', Validators.required],
    complement: [''],
    reference: [''],
  });

  ngOnInit(): Promise<void> {
    return this.load();
  }

  private async load(): Promise<void> {
    this.addresses.set(await firstValueFrom(this.http.get<Address[]>('/api/me/addresses')));
  }

  edit(a: Address | null): void {
    this.form.reset();
    if (a) this.form.patchValue({ ...a, label: a.label ?? '', complement: a.complement ?? '', reference: a.reference ?? '' });
    this.editing.set(a?.id ?? 0);
  }

  /** Autopreenche pelo CEP (ViaCEP). Falha silenciosa: o usuário digita à mão. */
  async lookupCep(): Promise<void> {
    const cep = digits(this.form.controls.postalCode.value);
    if (cep.length !== 8) return;
    try {
      const r = await firstValueFrom(this.http.get<ViaCep>(`https://viacep.com.br/ws/${cep}/json/`));
      if (r.erro) {
        this.form.controls.postalCode.setErrors({ server: 'CEP não encontrado.' });
        return;
      }
      this.form.patchValue({ street: r.logradouro, district: r.bairro, city: r.localidade, state: r.uf });
    } catch {
      // sem autopreenchimento
    }
  }

  async save(): Promise<void> {
    this.form.markAllAsTouched();
    if (this.form.invalid || this.pending()) return;
    this.pending.set(true);
    this.error.set(null);
    const v = this.form.getRawValue();
    const body = {
      ...v,
      postalCode: digits(v.postalCode),
      phone: digits(v.phone),
      label: v.label || null,
      complement: v.complement || null,
      reference: v.reference || null,
    };
    const id = this.editing();
    try {
      await firstValueFrom(
        id ? this.http.put(`/api/me/addresses/${id}`, body) : this.http.post('/api/me/addresses', body),
      );
      this.editing.set(null);
      await this.load();
    } catch (err) {
      applyFieldErrors(this.form, err);
      this.error.set(apiErrorMessage(err));
    } finally {
      this.pending.set(false);
    }
  }

  async makeDefault(a: Address): Promise<void> {
    await this.run(this.http.put(`/api/me/addresses/${a.id}/default`, null));
  }

  async remove(a: Address): Promise<void> {
    if (!confirm('Remover este endereço?')) return;
    await this.run(this.http.delete(`/api/me/addresses/${a.id}`));
  }

  private async run(request: Observable<unknown>): Promise<void> {
    this.error.set(null);
    try {
      await firstValueFrom(request);
      await this.load();
    } catch (err) {
      this.error.set(apiErrorMessage(err));
    }
  }
}
