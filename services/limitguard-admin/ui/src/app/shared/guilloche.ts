import { ChangeDetectionStrategy, Component, computed, input } from '@angular/core';

// Banknote-style linework drawn from a seed, so every tenant gets its own band.
@Component({
  selector: 'app-guilloche',
  template: `
    @if (variant() === 'band') {
      <svg class="band" viewBox="0 0 1200 120" preserveAspectRatio="none" aria-hidden="true" focusable="false">
        @for (path of paths(); track $index) {
          <path [attr.d]="path" pathLength="1"
            [attr.opacity]="$index % 3 === 0 ? 0.75 : 0.4" [style.animation-delay.ms]="$index * 30" />
        }
      </svg>
    } @else {
      <svg viewBox="-32 -32 64 64" aria-hidden="true" focusable="false">
        @for (path of paths(); track $index) {
          <path [attr.d]="path" [attr.opacity]="$index % 2 === 0 ? 0.9 : 0.5" />
        }
      </svg>
    }
  `,
  styles: `
    :host {
      display: block;
    }

    /* The band's linework is engraved once when a tenant opens. */
    .band path {
      stroke-dasharray: 1;
      animation: engrave 1400ms cubic-bezier(0.16, 1, 0.3, 1) both;
    }

    @keyframes engrave {
      from {
        stroke-dashoffset: 1;
      }
    }

    svg {
      display: block;
      width: 100%;
      height: 100%;
      fill: none;
      stroke: currentColor;
      stroke-width: 0.6;
    }
  `,
  changeDetection: ChangeDetectionStrategy.OnPush
})
export class Guilloche {
  readonly seed = input('limitguard');
  readonly variant = input<'band' | 'rosette'>('band');

  protected readonly paths = computed(() => {
    const [a, b, c] = numbers(this.seed());
    return this.variant() === 'band' ? band(a, b, c) : rosette(a, b, c);
  });
}

function band(a: number, b: number, c: number): string[] {
  const paths: string[] = [];
  for (let line = 0; line < 22; line++) {
    const phase = a * 6 + line * 0.19;
    const amplitude = 22 + 18 * Math.sin(line * 0.35 + b * 6);
    let d = '';
    for (let x = 0; x <= 1200; x += 10) {
      const y = 60 + amplitude * Math.sin(x / (40 + c * 30) + phase) * Math.cos(x / (120 + b * 60) - phase * 0.6)
        + (line - 11) * 1.4;
      d += `${x === 0 ? 'M' : 'L'}${x} ${y.toFixed(1)}`;
    }
    paths.push(d);
  }
  return paths;
}

function rosette(a: number, b: number, c: number): string[] {
  const petals = 7 + Math.floor(a * 6);
  const paths: string[] = [];
  for (let ring = 0; ring < 6; ring++) {
    const turn = (ring / 6) * (Math.PI / petals) + b;
    const depth = 3 + c * 4;
    let d = '';
    for (let step = 0; step <= 360; step++) {
      const theta = (step / 360) * Math.PI * 2;
      const r = 20 + depth * Math.sin(petals * theta + turn) + 2.5 * Math.sin(petals * 3 * theta - turn);
      d += `${step === 0 ? 'M' : 'L'}${(r * Math.cos(theta)).toFixed(2)} ${(r * Math.sin(theta)).toFixed(2)}`;
    }
    paths.push(d + 'Z');
  }
  return paths;
}

// Three stable numbers in [0, 1) from the seed text.
function numbers(seed: string): [number, number, number] {
  let hash = 2166136261;
  const values: number[] = [];
  for (let round = 0; round < 3; round++) {
    for (const char of seed + round) {
      hash ^= char.charCodeAt(0);
      hash = Math.imul(hash, 16777619);
    }
    values.push((hash >>> 0) / 4294967296);
  }
  return values as [number, number, number];
}
