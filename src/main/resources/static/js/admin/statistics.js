// ============================================================
// ESTATÍSTICAS
// ============================================================
// Endpoint agregado (GET /statistics) — os 5 painéis vêm numa chamada só, mesmo padrão
// já usado em "Meu Desempenho" do representante.

async function loadStatistics() {
  const data = await safeCall(() => api('GET', '/statistics'));
  renderVolumeChart(data.quotationVolume);
  renderSavingsChart(data.savingsTrend);
  renderSupplierRanking(data.supplierRanking);
  renderRepresentativeRanking(data.representativeRanking);
  renderPriceVariation(data.priceVariation);
}

// Gráfico de barras genérico, sem biblioteca externa — cada mês vira uma coluna com
// 1+ barrinhas dentro. A altura é proporcional ao MAIOR valor de toda a série (não só
// do mês em questão), senão a comparação visual entre meses fica sem sentido (um mês
// com valor baixo pareceria "cheio" se comparado só consigo mesmo).
function renderBarChart(containerId, labels, series) {
  const container = document.getElementById(containerId);
  if (!container) return;

  const allValues = series.flatMap(s => s.values);
  const maxValue = Math.max(1, ...allValues);

  const legendHtml = series.length > 1
    ? `<div class="stats-chart-legend">${series.map(s =>
        `<span><span class="stats-chart-legend-dot" style="background:${s.color}"></span>${escapeHtml(s.label)}</span>`
      ).join('')}</div>`
    : '';

  const colsHtml = labels.map((label, i) => {
    const barsHtml = series.map(s => {
      const value = s.values[i];
      const heightPct = value > 0 ? Math.max(3, (value / maxValue) * 100) : 1;
      const displayValue = s.formatValue ? s.formatValue(value) : value;
      return `<div class="stats-chart-bar" style="height:${heightPct}%; background:${s.color}">
        <span class="stats-chart-value">${displayValue}</span>
      </div>`;
    }).join('');
    return `<div class="stats-chart-col">
      <div class="stats-chart-bars">${barsHtml}</div>
      <div class="stats-chart-label">${escapeHtml(label)}</div>
    </div>`;
  }).join('');

  container.innerHTML = `${legendHtml}<div class="stats-chart-cols">${colsHtml}</div>`;
}

function renderVolumeChart(rows) {
  renderBarChart('stats-volume-chart', rows.map(r => r.monthLabel), [
    { label: 'Criadas', color: 'var(--accent)', values: rows.map(r => r.created) },
    { label: 'Fechadas', color: 'var(--success)', values: rows.map(r => r.closed) }
  ]);
}

function renderSavingsChart(rows) {
  renderBarChart('stats-savings-chart', rows.map(r => r.monthLabel), [
    {
      label: 'Economia',
      color: 'var(--success)',
      values: rows.map(r => r.totalSavings),
      formatValue: v => v > 0 ? 'R$ ' + formatCurrencyFromNumber(v) : '—'
    }
  ]);
}

function renderSupplierRanking(rows) {
  const tbody = document.getElementById('stats-supplier-ranking-tbody');
  const empty = document.getElementById('stats-supplier-ranking-empty');
  tbody.innerHTML = '';
  empty.style.display = rows.length ? 'none' : 'block';

  rows.forEach(r => {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td class="truncate-cell" title="${escapeHtml(r.supplierName)}">${escapeHtml(r.supplierName)}</td>
      <td style="text-align:center">${r.itemsWon}</td>
      <td style="text-align:right; color:var(--success); font-weight:600">R$ ${formatCurrencyFromNumber(r.totalValueWon)}</td>
      <td style="text-align:center">${r.bidsSubmitted}</td>
      <td style="text-align:center">${r.declines}</td>
      <td style="text-align:center">${r.responseRatePct != null ? r.responseRatePct.toFixed(0) + '%' : '—'}</td>`;
    tbody.appendChild(tr);
  });
}

// Duração formatada de um jeito legível — menos de 1h em minutos, menos de 48h em
// horas, acima disso em dias (arredondado pra baixo + horas restantes).
function formatResponseDuration(hours) {
  if (hours == null) return '—';
  if (hours < 1) return Math.round(hours * 60) + ' min';
  if (hours < 48) return hours.toFixed(1).replace('.', ',') + 'h';
  const days = Math.floor(hours / 24);
  const remainingHours = Math.round(hours % 24);
  return `${days}d ${remainingHours}h`;
}

function renderRepresentativeRanking(rows) {
  const tbody = document.getElementById('stats-rep-ranking-tbody');
  const empty = document.getElementById('stats-rep-ranking-empty');
  tbody.innerHTML = '';
  empty.style.display = rows.length ? 'none' : 'block';

  rows.forEach(r => {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td class="truncate-cell" title="${escapeHtml(r.representativeName)}">${escapeHtml(r.representativeName)}</td>
      <td style="text-align:center">${r.bidsSubmitted}</td>
      <td style="text-align:center">${r.declines}</td>
      <td style="text-align:center">${formatResponseDuration(r.avgResponseHours)}</td>
      <td style="text-align:center">${r.responseRatePct != null ? r.responseRatePct.toFixed(0) + '%' : '—'}</td>`;
    tbody.appendChild(tr);
  });
}

function renderPriceVariation(rows) {
  const tbody = document.getElementById('stats-price-variation-tbody');
  const empty = document.getElementById('stats-price-variation-empty');
  tbody.innerHTML = '';
  empty.style.display = rows.length ? 'none' : 'block';

  rows.forEach(r => {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td class="truncate-cell" title="${escapeHtml(r.productName)}">${escapeHtml(r.productName)}</td>
      <td style="text-align:right">R$ ${formatCurrencyFromNumber(r.minPrice)}</td>
      <td style="text-align:right">R$ ${formatCurrencyFromNumber(r.maxPrice)}</td>
      <td style="text-align:right; font-weight:600; color:var(--warning)">${r.variationPct.toFixed(1).replace('.', ',')}%</td>`;
    tbody.appendChild(tr);
  });
}
