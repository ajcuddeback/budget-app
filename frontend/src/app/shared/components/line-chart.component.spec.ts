import { Component } from '@angular/core';
import { ComponentFixture, TestBed } from '@angular/core/testing';
import { beforeEach, describe, expect, it } from 'vitest';
import { ChartMarker, ChartSeries, LineChartComponent } from './line-chart.component';

function points(amounts: readonly string[], from = 1): ChartSeries['points'] {
  return amounts.map((amount, index) => ({
    month: `2027-${String(from + index).padStart(2, '0')}`,
    label: `Month ${from + index}`,
    amount,
  }));
}

@Component({
  imports: [LineChartComponent],
  template: `
    <app-line-chart
      [series]="series"
      [marker]="marker"
      [label]="'Balance to zero'"
      chartId="test-chart"
      [assumption]="assumption"
      [coincidentLabel]="coincidentLabel"
    />
  `,
})
class Host {
  series: readonly ChartSeries[] = [];
  marker: ChartMarker | null = null;
  assumption = '';
  coincidentLabel = '';
}

describe('LineChartComponent', () => {
  let fixture: ComponentFixture<Host>;
  let host: Host;

  beforeEach(async () => {
    TestBed.resetTestingModule();
    TestBed.configureTestingModule({ imports: [Host] });
    fixture = TestBed.createComponent(Host);
    host = fixture.componentInstance;
  });

  function render(series: readonly ChartSeries[]): HTMLElement {
    host.series = series;
    fixture.detectChanges();
    return fixture.nativeElement as HTMLElement;
  }

  const avalanche: ChartSeries = {
    id: 'a',
    name: 'Avalanche',
    said: 'Avalanche: £1,000.00 in Month 1, down to £0.00 in Month 3.',
    points: points(['1000.0000', '500.0000', '0.0000']),
    role: 'selected',
  };

  const snowball: ChartSeries = {
    id: 's',
    name: 'Snowball',
    said: 'Snowball: £1,000.00 in Month 1, down to £0.00 in Month 3.',
    points: points(['1000.0000', '750.0000', '0.0000']),
    role: 'compared',
  };

  it('scales each point against the largest figure on the chart, exactly', () => {
    const root = render([avalanche]);

    // 1000 is the top of the box and 0 the baseline, so 500 lands halfway. Computed from the
    // decimal strings in bigint minor units — no amount is ever handed to a double.
    expect(root.querySelector('polyline')?.getAttribute('points')).toBe('0,0 50,50 100,100');
  });

  it('draws both lines against one scale, so the two are comparable', () => {
    const root = render([avalanche, snowball]);

    const drawn = Array.from(root.querySelectorAll('polyline')).map((line) =>
      line.getAttribute('points'),
    );
    expect(drawn).toEqual(['0,0 50,50 100,100', '0,0 50,25 100,100']);
  });

  it('plots exactly the points it was given and never resamples them', () => {
    // A thinned line: the server kept every other month and said so with seriesMonthStride. A
    // component that interpolated would draw a shape the server did not compute.
    const root = render([{ ...avalanche, points: points(['800.0000', '400.0000']) }]);

    // Two points, two coordinates: 800 is the top of the box and 400 is half of it.
    expect(root.querySelector('polyline')?.getAttribute('points')).toBe('0,0 100,50');
  });

  it('distinguishes the compared line by dash as well as by colour', () => {
    const root = render([avalanche, snowball]);

    const lines = Array.from(root.querySelectorAll('polyline'));
    expect(lines[0].classList.contains('is-compared')).toBe(false);
    expect(lines[1].classList.contains('is-compared')).toBe(true);
  });

  it('is readable without being seen: an image role, a name, and the same figures in words', () => {
    host.marker = { month: '2027-03', label: 'Debt-free Month 3' };
    const root = render([avalanche, snowball]);

    const svg = root.querySelector('svg');
    expect(svg?.getAttribute('role')).toBe('img');
    expect(svg?.getAttribute('aria-label')).toBe('Balance to zero');
    expect(svg?.getAttribute('aria-describedby')).toBe('test-chart-description');

    const described = root.querySelector('#test-chart-description');
    expect(described?.textContent).toContain('£1,000.00 in Month 1');
    expect(described?.textContent).toContain('£0.00 in Month 3');
    expect(described?.textContent).toContain('Snowball');
    expect(described?.textContent).toContain('Debt-free Month 3');
  });

  it('labels both ends of the axis from the longest line', () => {
    const root = render([
      { ...avalanche, points: points(['1000.0000', '0.0000']) },
      { ...snowball, points: points(['1000.0000', '600.0000', '300.0000', '0.0000']) },
    ]);

    const axis = root.querySelector('.chart-axis')?.textContent ?? '';
    expect(axis).toContain('Month 1');
    expect(axis).toContain('Month 4');
  });

  it('places the marker on the month it names', () => {
    host.marker = { month: '2027-02', label: 'Halfway' };
    const root = render([avalanche]);

    expect(root.querySelector('.chart-mark')?.getAttribute('x1')).toBe('50');
    expect(root.querySelector('.chart-mark-label')?.textContent).toContain('Halfway');
  });

  it('marks nothing when the month it names is not on the chart', () => {
    host.marker = { month: '2099-01', label: 'Never' };
    const root = render([avalanche]);

    expect(root.querySelector('.chart-mark')).toBeNull();
  });

  it('names every line in the legend, so a line without a key cannot ship', () => {
    const root = render([avalanche, snowball]);

    expect(
      Array.from(root.querySelectorAll('.chart-legend li')).map((item) => item.textContent?.trim()),
    ).toEqual(['Avalanche', 'Snowball']);
  });

  it('draws nothing at all rather than an empty axis when there is nothing to plot', () => {
    const empty = render([{ ...avalanche, points: [] }]);
    expect(empty.querySelector('svg')).toBeNull();

    const flat = render([{ ...avalanche, points: points(['0.0000', '0.0000']) }]);
    expect(flat.querySelector('svg')).toBeNull();

    const single = render([{ ...avalanche, points: points(['1000.0000']) }]);
    expect(single.querySelector('svg')).toBeNull();
  });

  it('still says in words what it could not draw', () => {
    const root = render([
      { ...avalanche, said: 'Avalanche: no line, because it never clears.', points: [] },
    ]);

    expect(root.querySelector('#test-chart-description')?.textContent).toContain('never clears');
  });

  it('holds an overpaid balance at the baseline rather than drawing outside its own box', () => {
    const root = render([{ ...avalanche, points: points(['1000.0000', '500.0000', '-20.0000']) }]);

    expect(root.querySelector('polyline')?.getAttribute('points')).toBe('0,0 50,50 100,100');
  });

  it('draws two lines as one when they are closer than the chart can show', () => {
    // Avalanche and snowball over three real debts differ by about 0.2% of the starting balance,
    // which is under a pixel at this plot's height. Two strokes on the same pixels read as one
    // line that failed to render.
    host.coincidentLabel = 'Avalanche and Snowball are one line here';
    const root = render([
      avalanche,
      { ...snowball, points: points(['1000.0000', '502.0000', '0.0000']) },
    ]);

    expect(root.querySelectorAll('polyline')).toHaveLength(1);
    // One key, naming both: dropping a name would be the same bug inverted.
    expect(
      Array.from(root.querySelectorAll('.chart-legend li')).map((item) => item.textContent?.trim()),
    ).toEqual(['Avalanche · Snowball']);
    expect(root.textContent).toContain('Avalanche and Snowball are one line here');
  });

  it('still states both lines’ own figures when it has drawn them as one', () => {
    host.coincidentLabel = 'Avalanche and Snowball are one line here';
    const root = render([
      avalanche,
      { ...snowball, points: points(['1000.0000', '502.0000', '0.0000']) },
    ]);

    // The second stroke is suppressed; the second set of figures is not.
    const described = root.querySelector('#test-chart-description')?.textContent ?? '';
    expect(described).toContain('Avalanche:');
    expect(described).toContain('Snowball:');
    expect(described).toContain('are one line here');
  });

  it('draws both when they are far enough apart to tell apart', () => {
    host.coincidentLabel = 'Avalanche and Snowball are one line here';
    const root = render([avalanche, snowball]);

    expect(root.querySelectorAll('polyline')).toHaveLength(2);
    expect(root.querySelectorAll('.chart-legend li')).toHaveLength(2);
  });

  it('never collapses two lines of different lengths, however close their balances', () => {
    // A plan that runs longer is a visible difference whatever the balances do.
    host.coincidentLabel = 'one line';
    const root = render([
      avalanche,
      { ...snowball, points: points(['1000.0000', '667.0000', '333.0000', '0.0000']) },
    ]);

    expect(root.querySelectorAll('polyline')).toHaveLength(2);
  });

  it('shows the assumption the lines were drawn under', () => {
    host.assumption = 'Every line assumes £560.00 a month.';
    const root = render([avalanche]);

    expect(root.querySelector('figcaption')?.textContent).toContain('£560.00 a month');
  });
});
