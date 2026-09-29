import { Component, computed, effect, input, signal } from '@angular/core';
import { AbstractControl } from '@angular/forms';

/**
 * Primeira mensagem de erro de um controle, depois que o usuário interagiu com ele.
 * Componentes são OnPush por padrão (Angular 22): o estado do controle não é signal,
 * então os eventos do controle (touched, status, valor) disparam a atualização.
 */
@Component({
  selector: 'app-field-error',
  template: `
    @if (message(); as m) {
      <span class="field-error" role="alert">{{ m }}</span>
    }
  `,
})
export class FieldError {
  readonly control = input.required<AbstractControl>();
  private readonly changes = signal(0);

  constructor() {
    effect((onCleanup) => {
      const sub = this.control().events.subscribe(() => this.changes.update((n) => n + 1));
      onCleanup(() => sub.unsubscribe());
    });
  }

  protected readonly message = computed(() => {
    this.changes();
    const c = this.control();
    if (!c.errors || !(c.touched || c.dirty)) return null;
    const e = c.errors;
    if (e['server']) return e['server'] as string;
    if (e['required']) return 'Campo obrigatório.';
    if (e['email']) return 'E-mail inválido.';
    if (e['minlength']) return `Mínimo de ${e['minlength'].requiredLength} caracteres.`;
    if (e['maxlength']) return `Máximo de ${e['maxlength'].requiredLength} caracteres.`;
    if (e['pattern']) return 'Formato inválido.';
    return 'Valor inválido.';
  });
}
