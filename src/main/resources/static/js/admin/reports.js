// ============================================================
// RELATÓRIOS
// ============================================================
// ============================================================
// RELATÓRIOS — uma linha por item ganho (cotação × fornecedor × representante × produto),
// com filtro por período/empresa/representante/produto. Backend já devolve tudo pronto
// (GET /quotations/report) — aqui é só popular os selects de filtro e renderizar.
// ============================================================
let reportRowsCache = [];
let reportGroupsCache = [];
let reportPage = 0;
const REPORT_PAGE_SIZE = 10;
let reportHasSearched = false;

async function loadReportsFilters() {
  const [suppliers, reps] = await Promise.all([
    safeCall(() => api('GET', '/suppliers')),
    safeCall(() => api('GET', '/representatives'))
  ]);
  document.getElementById('report-supplier').innerHTML =
    '<option value="">Todos</option>' + suppliers.map(s => `<option value="${s.id}">${escapeHtml(s.name)}</option>`).join('');
  document.getElementById('report-representative').innerHTML =
    '<option value="">Todos</option>' + reps.map(r => `<option value="${r.id}">${escapeHtml(r.name)}</option>`).join('');
  // Não carrega nada sozinho — só ao clicar em "Gerar Relatório". Reseta o estado da
  // tela toda vez que a seção é aberta, pra não mostrar resultado de uma visita anterior.
  reportHasSearched = false;
  document.getElementById('report-table').style.display = 'none';
  document.getElementById('report-empty').style.display = 'none';
  document.getElementById('report-summary').textContent = 'Configure os filtros acima e clique em "Gerar Relatório".';
}

async function loadReportData() {
  const params = new URLSearchParams();
  const from = document.getElementById('report-from').value;
  const to = document.getElementById('report-to').value;
  const supplierId = document.getElementById('report-supplier').value;
  const repId = document.getElementById('report-representative').value;
  const product = document.getElementById('report-product').value.trim();
  const onlyWinners = document.getElementById('report-mode').value === 'winners';

  if (from) params.set('from', from);
  if (to) params.set('to', to);
  if (supplierId) params.set('supplierId', supplierId);
  if (repId) params.set('representativeId', repId);
  if (product) params.set('product', product);
  params.set('onlyWinners', onlyWinners);

  reportRowsCache = await safeCall(() => api('GET', `/quotations/report?${params.toString()}`));
  reportHasSearched = true;
  reportPage = 0;
  renderReportTable();
}

function clearReportFilters() {
  document.getElementById('report-from').value = '';
  document.getElementById('report-to').value = '';
  document.getElementById('report-supplier').value = '';
  document.getElementById('report-representative').value = '';
  document.getElementById('report-product').value = '';
  document.getElementById('report-mode').value = 'winners';
  // Limpar só reseta os campos — não gera relatório sozinho, mesma regra do carregamento
  // inicial: só mostra o que foi explicitamente procurado.
  reportHasSearched = false;
  reportRowsCache = [];
  reportGroupsCache = [];
  reportPage = 0;
  document.getElementById('report-table').style.display = 'none';
  document.getElementById('report-pagination').style.display = 'none';
  document.getElementById('report-empty').style.display = 'none';
  document.getElementById('report-summary').textContent = 'Configure os filtros acima e clique em "Gerar Relatório".';
}

// Backend devolve uma linha por lance (nível de item) — pro relatório ficar legível numa
// cotação com vários produtos, agrupa aqui na tela por cotação + fornecedor, e guarda os
// lances originais em "items" pro botão "Ver Detalhes" abrir depois. Nenhuma chamada nova
// à API: é tudo reagrupamento do que já veio de loadReportData().
// Número "oficial" da cotação, com zero à esquerda (000002 em vez de 2) — visual mais
// de documento formal, tipo número de pedido/nota. Só formatação de exibição, o id
// continua o mesmo por baixo (é ele que abre o detalhe ao clicar).
function formatQuotationNumber(id) {
  return String(id).padStart(6, '0');
}

function groupReportRows() {
  const groups = new Map();
  reportRowsCache.forEach(r => {
    const key = r.quotationId + '|' + r.supplierName;
    if (!groups.has(key)) {
      groups.set(key, {
        quotationId: r.quotationId,
        quotationName: r.quotationName,
        closedAt: r.closedAt,
        supplierName: r.supplierName,
        representativeName: r.representativeName,
        total: 0,
        wonCount: 0,
        orderConfirmed: false,
        items: []
      });
    }
    const g = groups.get(key);
    g.total += r.subtotal;
    if (r.won) g.wonCount++;
    if (r.orderConfirmed) g.orderConfirmed = true;
    g.items.push(r);
  });
  return Array.from(groups.values());
}

function reportResultBadge(group) {
  const total = group.items.length;
  if (group.wonCount === total) {
    return `<span class="badge badge-available">Venceu (${total} ${total > 1 ? 'itens' : 'item'})</span>`;
  }
  if (group.wonCount === 0) {
    return '<span class="badge badge-expired">Não venceu</span>';
  }
  return `<span class="badge badge-closed">Parcial (${group.wonCount}/${total} itens)</span>`;
}

// Reflete o OrderFulfillmentConfirmation que o representante gera ao clicar "Finalizar
// Pedido" em "Resultado da cotação" — não existe conceito de "pedido" quando não venceu
// nada, por isso o "—" nesse caso, em vez de forçar um "pendente" que não faz sentido.
function reportOrderBadge(group) {
  if (group.wonCount === 0) return '<span style="color:var(--text-faint)">—</span>';
  return group.orderConfirmed
    ? '<span class="badge badge-available">Pedido enviado</span>'
    : '<span class="badge badge-reviewing">Pendente</span>';
}

function renderReportTable() {
  const table = document.getElementById('report-table');
  const tbody = document.getElementById('report-tbody');
  const emptyEl = document.getElementById('report-empty');
  const summaryEl = document.getElementById('report-summary');
  const paginationEl = document.getElementById('report-pagination');

  const groups = groupReportRows();
  reportGroupsCache = groups;

  const totalPages = Math.max(Math.ceil(groups.length / REPORT_PAGE_SIZE), 1);
  if (reportPage >= totalPages) reportPage = totalPages - 1;
  if (reportPage < 0) reportPage = 0;
  const pageStart = reportPage * REPORT_PAGE_SIZE;
  const pageGroups = groups.slice(pageStart, pageStart + REPORT_PAGE_SIZE);

  tbody.innerHTML = '';
  table.style.display = groups.length ? 'table' : 'none';
  emptyEl.style.display = groups.length ? 'none' : 'block';
  paginationEl.style.display = groups.length ? 'flex' : 'none';

  const grandTotal = reportRowsCache.reduce((sum, r) => sum + r.subtotal, 0);
  summaryEl.textContent = reportRowsCache.length
    ? `${reportRowsCache.length} lance${reportRowsCache.length > 1 ? 's' : ''} em ${groups.length} cotaç${groups.length > 1 ? 'ões' : 'ão'} — total: R$ ${formatCurrencyFromNumber(grandTotal)}`
    : 'Nenhum lance encontrado com esses filtros.';

  // idx usa a posição no array COMPLETO (pageStart + i), não a posição na página — senão
  // "Ver Detalhes" abriria o grupo errado a partir da segunda página em diante.
  pageGroups.forEach((g, i) => {
    const idx = pageStart + i;
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td><a href="#" onclick="abrirDetalheCotacao(${g.quotationId}); return false;">${formatQuotationNumber(g.quotationId)}</a></td>
      <td class="truncate-cell" title="${escapeHtml(g.quotationName)}"><a href="#" onclick="abrirDetalheCotacao(${g.quotationId}); return false;">${escapeHtml(g.quotationName)}</a></td>
      <td>${fmtDate(g.closedAt)}</td>
      <td class="truncate-cell" title="${escapeHtml(g.supplierName)}">${escapeHtml(g.supplierName)}</td>
      <td class="truncate-cell" title="${escapeHtml(g.representativeName)}">${escapeHtml(g.representativeName)}</td>
      <td style="text-align:right">${formatCurrencyFromNumber(g.total)}</td>
      <td>${reportResultBadge(g)}</td>
      <td>${reportOrderBadge(g)}</td>
      <td><button class="secondary small" onclick="openReportDetailModal(${idx})">Ver Detalhes</button></td>`;
    tbody.appendChild(tr);
  });

  document.getElementById('report-page-info').textContent = `Página ${reportPage + 1} de ${totalPages}`;
  document.getElementById('report-prev-btn').disabled = reportPage === 0;
  document.getElementById('report-next-btn').disabled = reportPage >= totalPages - 1;
}

function changeReportPage(delta) {
  reportPage += delta;
  renderReportTable();
}

function openReportDetailModal(idx) {
  const g = reportGroupsCache[idx];
  const rowsHtml = g.items.map(i => `
    <tr>
      <td>${escapeHtml(i.productName)}</td>
      <td style="text-align:right">${i.quantity}</td>
      <td style="text-align:right">${formatCurrencyFromNumber(i.unitValue)}</td>
      <td style="text-align:right">${formatCurrencyFromNumber(i.subtotal)}</td>
      <td>${i.won ? '<span class="badge badge-available">Venceu</span>' : '<span class="badge badge-expired">Não venceu</span>'}</td>
    </tr>`).join('');

  openModal2(`
    <h2>${formatQuotationNumber(g.quotationId)} — ${escapeHtml(g.quotationName)}</h2>
    <div class="subtitle" style="margin-bottom:14px">${escapeHtml(g.supplierName)} · ${escapeHtml(g.representativeName)} · Concluída em ${fmtDate(g.closedAt)}</div>
    <div style="margin-bottom:14px">${reportOrderBadge(g)}</div>
    <table><thead><tr><th>Produto</th><th>Qtd.</th><th>Preço Unit. (R$)</th><th>Subtotal (R$)</th><th>Resultado</th></tr></thead>
      <tbody>${rowsHtml}</tbody></table>
    <div style="text-align:right; margin-top:14px; font-weight:700">Total: R$ ${formatCurrencyFromNumber(g.total)}</div>
    <div class="btn-row" style="margin-top:16px">
      <button class="secondary" onclick="closeModal2()">Fechar</button>
    </div>
  `, 'medium');
}

// CSV é montado no próprio navegador a partir do que já está carregado — não precisa ir
// no backend de novo, o relatório filtrado já está todo na tela.
function exportReportCsv() {
  if (!reportHasSearched || !reportRowsCache.length) { toast('Gere o relatório antes de exportar.', true); return; }

  const header = ['Nº da Cotação', 'Nome', 'Concluída em', 'Fornecedor', 'Representante', 'Produto', 'Quantidade', 'Preço Unit.', 'Subtotal', 'Resultado', 'Pedido Enviado'];
  const csvEscape = (v) => `"${String(v).replace(/"/g, '""')}"`;
  const lines = [header.map(csvEscape).join(';')];

  reportRowsCache.forEach(r => {
    lines.push([
      formatQuotationNumber(r.quotationId), r.quotationName, fmtDate(r.closedAt), r.supplierName, r.representativeName, r.productName,
      r.quantity, formatCurrencyFromNumber(r.unitValue), formatCurrencyFromNumber(r.subtotal),
      r.won ? 'Venceu' : 'Não venceu',
      r.won ? (r.orderConfirmed ? 'Sim' : 'Não') : '—'
    ].map(csvEscape).join(';'));
  });

  const blob = new Blob(['\uFEFF' + lines.join('\r\n')], { type: 'text/csv;charset=utf-8' });
  const url = URL.createObjectURL(blob);
  const a = document.createElement('a');
  a.href = url;
  a.download = `relatorio-cotacoes-${new Date().toISOString().slice(0, 10)}.csv`;
  a.click();
  URL.revokeObjectURL(url);
}

