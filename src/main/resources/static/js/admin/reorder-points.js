// ============================================================
// PONTO DE COMPRA
// ============================================================
let reorderPointsCache = [];        // dados brutos vindos do backend, sem filtro de dias
let reorderPointsFiltered = [];     // só os itens com daysUntilReorder <= corte — é o que a tela mostra
let reorderPointsPage = 0;
let reorderPointsSelected = new Set(); // quotationItemId dos itens marcados — mantido ao trocar de página
const REORDER_POINTS_PAGE_SIZE = 10;
const REORDER_POINTS_DAYS_CUTOFF = 10;

async function loadReorderPointReport() {
  document.getElementById('reorder-points-summary').textContent = 'Carregando...';
  document.getElementById('reorder-points-table').style.display = 'none';
  document.getElementById('reorder-points-empty').style.display = 'none';

  reorderPointsCache = await safeCall(() => api('GET', '/quotations/reorder-points'));
  reorderPointsFiltered = reorderPointsCache.filter(r => r.daysUntilReorder <= REORDER_POINTS_DAYS_CUTOFF);
  reorderPointsPage = 0;
  reorderPointsSelected.clear();
  renderReorderPointsTable();
}

// Selo de urgência é só client-side, em cima de daysUntilReorder que já vem calculado
// do backend — evita duplicar a conta dos dois lados. Ainda usado no painel do
// Dashboard (renderDashboardReorderPanel); a tabela desta tela não exibe mais o selo,
// só a data, então essa função continua existindo mas não é chamada aqui embaixo.
function reorderUrgencyBadge(daysUntilReorder) {
  if (daysUntilReorder < 0) {
    return `<span class="badge badge-expired">Atrasado (${Math.abs(daysUntilReorder)}d)</span>`;
  }
  if (daysUntilReorder <= 7) {
    return `<span class="badge badge-reviewing">Em ${daysUntilReorder}d</span>`;
  }
  return `<span class="badge badge-available">Em ${daysUntilReorder}d</span>`;
}

function renderReorderPointsTable() {
  const table = document.getElementById('reorder-points-table');
  const tbody = document.getElementById('reorder-points-tbody');
  const emptyEl = document.getElementById('reorder-points-empty');
  const paginationEl = document.getElementById('reorder-points-pagination');
  const summaryEl = document.getElementById('reorder-points-summary');
  const actionsEl = document.getElementById('reorder-points-actions');

  const totalPages = Math.max(Math.ceil(reorderPointsFiltered.length / REORDER_POINTS_PAGE_SIZE), 1);
  if (reorderPointsPage >= totalPages) reorderPointsPage = totalPages - 1;
  if (reorderPointsPage < 0) reorderPointsPage = 0;
  const pageStart = reorderPointsPage * REORDER_POINTS_PAGE_SIZE;
  const pageItems = reorderPointsFiltered.slice(pageStart, pageStart + REORDER_POINTS_PAGE_SIZE);

  table.style.display = reorderPointsFiltered.length ? 'table' : 'none';
  emptyEl.style.display = reorderPointsFiltered.length ? 'none' : 'block';
  paginationEl.style.display = reorderPointsFiltered.length ? 'flex' : 'none';
  actionsEl.style.display = reorderPointsFiltered.length ? 'flex' : 'none';

  const overdueCount = reorderPointsFiltered.filter(r => r.daysUntilReorder < 0).length;
  summaryEl.textContent = reorderPointsFiltered.length
    ? `${reorderPointsFiltered.length} produto${reorderPointsFiltered.length > 1 ? 's' : ''} com ponto de compra em até ${REORDER_POINTS_DAYS_CUTOFF} dias`
      + (overdueCount ? ` — ${overdueCount} já ${overdueCount > 1 ? 'passaram' : 'passou'} do ponto ideal` : '')
    : `Nenhum produto com ponto de compra em até ${REORDER_POINTS_DAYS_CUTOFF} dias no momento.`;

  tbody.innerHTML = '';
  pageItems.forEach(r => {
    const tr = document.createElement('tr');
    const checked = reorderPointsSelected.has(r.quotationItemId) ? 'checked' : '';
    tr.innerHTML = `
      <td><input type="checkbox" onchange="toggleReorderPointSelected(${r.quotationItemId}, this.checked)" ${checked}></td>
      <td>${escapeHtml(r.productBarcode)} — ${escapeHtml(r.productName)}</td>
      <td style="text-align:center">${r.quantity}</td>
      <td style="font-weight:700">${fmtDateOnly(r.reorderDate)}</td>`;
    tbody.appendChild(tr);
  });

  document.getElementById('reorder-points-page-info').textContent = `Página ${reorderPointsPage + 1} de ${totalPages}`;
  document.getElementById('reorder-points-prev-btn').disabled = reorderPointsPage === 0;
  document.getElementById('reorder-points-next-btn').disabled = reorderPointsPage >= totalPages - 1;

  updateReorderPointsSelectAllCheckbox(pageItems);
  updateReorderPointsActionBar();
}

function changeReorderPointsPage(delta) {
  reorderPointsPage += delta;
  renderReorderPointsTable();
}

// Marca o checkbox "selecionar todos" como marcado só quando TODOS os itens da
// página atual já estão selecionados — senão fica desmarcado, mesmo com seleção
// parcial (não existe estado indeterminado aqui pra manter simples).
function updateReorderPointsSelectAllCheckbox(pageItems) {
  const selectAll = document.getElementById('reorder-points-select-all');
  if (!selectAll) return;
  selectAll.checked = pageItems.length > 0 && pageItems.every(r => reorderPointsSelected.has(r.quotationItemId));
}

function toggleReorderPointSelected(quotationItemId, checked) {
  if (checked) reorderPointsSelected.add(quotationItemId);
  else reorderPointsSelected.delete(quotationItemId);
  const pageStart = reorderPointsPage * REORDER_POINTS_PAGE_SIZE;
  const pageItems = reorderPointsFiltered.slice(pageStart, pageStart + REORDER_POINTS_PAGE_SIZE);
  updateReorderPointsSelectAllCheckbox(pageItems);
  updateReorderPointsActionBar();
}

// Seleciona/desmarca só os itens da página atual — a seleção de outras páginas
// (se houver) continua intacta.
function toggleReorderPointsSelectAll(checked) {
  const pageStart = reorderPointsPage * REORDER_POINTS_PAGE_SIZE;
  const pageItems = reorderPointsFiltered.slice(pageStart, pageStart + REORDER_POINTS_PAGE_SIZE);
  pageItems.forEach(r => {
    if (checked) reorderPointsSelected.add(r.quotationItemId);
    else reorderPointsSelected.delete(r.quotationItemId);
  });
  renderReorderPointsTable();
}

function updateReorderPointsActionBar() {
  const count = reorderPointsSelected.size;
  const countEl = document.getElementById('reorder-points-selected-count');
  if (countEl) countEl.textContent = `${count} selecionado${count !== 1 ? 's' : ''}`;
  const newBtn = document.getElementById('reorder-points-new-quotation-btn');
  const existingBtn = document.getElementById('reorder-points-existing-quotation-btn');
  if (newBtn) newBtn.disabled = count === 0;
  if (existingBtn) existingBtn.disabled = count === 0;
}

// Monta o payload de itens a partir da seleção (pode abranger mais de uma página).
// Se o mesmo produto aparecer selecionado em mais de uma linha (ex: veio de duas
// cotações fechadas diferentes), soma as quantidades em vez de duplicar o produto.
function getSelectedReorderItemsPayload() {
  const byProduct = new Map();
  reorderPointsFiltered
    .filter(r => reorderPointsSelected.has(r.quotationItemId))
    .forEach(r => {
      const existing = byProduct.get(r.productId);
      if (existing) existing.quantity += r.quantity;
      else byProduct.set(r.productId, { productId: r.productId, quantity: r.quantity });
    });
  return Array.from(byProduct.values());
}

async function openReorderPointsNewQuotationModal() {
  if (!reorderPointsSelected.size) return;
  const items = getSelectedReorderItemsPayload();

  openModal2(`
    <h2>Adicionar a nova cotação</h2>
    <div class="subtitle" style="margin-bottom:14px">${items.length} produto${items.length > 1 ? 's' : ''} selecionado${items.length > 1 ? 's' : ''} do relatório de Ponto de Compra.</div>
    <label>Nome da cotação</label>
    <input id="rp-new-name" placeholder="Ex: Reposição de estoque">
    <label style="margin-top:10px">Grupo (opcional)</label>
    <select id="rp-new-group">
      <option value="">— definir depois —</option>
      ${groupsCache.map(g => `<option value="${g.id}">${escapeHtml(g.name)}</option>`).join('')}
    </select>
    <div class="btn-row" style="margin-top:16px; justify-content:space-between">
      <button class="secondary" onclick="closeModal2()">Cancelar</button>
      <button id="rp-new-confirm-btn" onclick="confirmReorderPointsNewQuotation()">Criar cotação</button>
    </div>
  `);
}

async function confirmReorderPointsNewQuotation() {
  const name = document.getElementById('rp-new-name').value.trim();
  if (!name) { toast('Informe um nome para a cotação.', true); return; }
  const items = getSelectedReorderItemsPayload();
  const btn = document.getElementById('rp-new-confirm-btn');
  btn.disabled = true;

  const body = {
    name,
    supplierGroupId: document.getElementById('rp-new-group').value || null,
    items
  };
  const q = await safeCall(() => api('POST', '/quotations', body));
  toast(`Cotação #${q.id} criada em Rascunho com ${items.length} produto${items.length > 1 ? 's' : ''}.`);
  closeModal2();
  reorderPointsSelected.clear();
  renderReorderPointsTable();
}

async function openReorderPointsExistingQuotationModal() {
  if (!reorderPointsSelected.size) return;
  const items = getSelectedReorderItemsPayload();
  const all = await safeCall(() => api('GET', '/quotations'));
  const drafts = all.filter(q => q.status === 'DRAFT');

  if (!drafts.length) {
    toast('Não há nenhuma cotação em Rascunho no momento.', true);
    return;
  }

  openModal2(`
    <h2>Adicionar a uma cotação existente</h2>
    <div class="subtitle" style="margin-bottom:14px">${items.length} produto${items.length > 1 ? 's' : ''} selecionado${items.length > 1 ? 's' : ''} serão adicionados ao Rascunho escolhido.</div>
    <label>Cotação em Rascunho</label>
    <select id="rp-existing-target">
      ${drafts.map(q => `<option value="${q.id}">${escapeHtml(q.name)} (Nº ${formatQuotationNumber(q.id)})</option>`).join('')}
    </select>
    <div class="btn-row" style="margin-top:16px; justify-content:space-between">
      <button class="secondary" onclick="closeModal2()">Cancelar</button>
      <button id="rp-existing-confirm-btn" onclick="confirmReorderPointsExistingQuotation()">Adicionar</button>
    </div>
  `);
}

async function confirmReorderPointsExistingQuotation() {
  const targetId = document.getElementById('rp-existing-target').value;
  const items = getSelectedReorderItemsPayload();
  const btn = document.getElementById('rp-existing-confirm-btn');
  btn.disabled = true;

  let added = 0;
  let failed = 0;
  for (const item of items) {
    try {
      await api('POST', `/quotations/${targetId}/items`, { productId: item.productId, quantity: item.quantity });
      added++;
    } catch (e) {
      failed++;
    }
  }

  if (failed) {
    toast(`${added} adicionado${added !== 1 ? 's' : ''}, ${failed} falhou/falharam (já deve existir nessa cotação).`, true);
  } else {
    toast(`${added} produto${added !== 1 ? 's' : ''} adicionado${added !== 1 ? 's' : ''} à cotação escolhida.`);
  }
  closeModal2();
  reorderPointsSelected.clear();
  renderReorderPointsTable();
}

// Tela focada só nos dados desse grupo (cotação + fornecedor) — não é o modal de edição
// da cotação (abrirDetalheCotacao), que tem formulário, botão de publicar/fechar etc.
// Aqui é só leitura: produto, quantidade, preço, subtotal e resultado de cada lance.

// Impressão usa o diálogo nativo do navegador (Ctrl+P / window.print()), não geração
// de PDF no backend — mais simples e já cobre o caso de uso (o admin quer uma via em
// papel ou salvar como PDF direto do "Salvar como PDF" do próprio navegador). O nome
// sugerido no "Salvar como" vem do document.title no momento do print — por isso troca
// o título só durante a impressão e devolve o original logo depois (evento
// 'afterprint', que dispara tanto ao confirmar quanto ao cancelar o diálogo).
// Imprime a lista FILTRADA inteira (todos os produtos com ponto de compra em até 10
// dias), não só a página atual da tabela — faria pouco sentido imprimir 10 de,
// digamos, 23 produtos sem avisar que o resto ficou de fora.
// Cabeçalho com as duas marcas — logo da empresa contratante (CompanySettings, a
// mesma usada no PDF de resultado e no cabeçalho da barra lateral) à esquerda, e a
// marca do software (Easy Kota) à direita, discreta — mesmo espírito do rodapé do PDF
// de cotação (QuotationPdfService): reforça "gerado pelo Kota", sem competir com a
// marca de quem está usando o sistema.
async function printReorderPointsReport() {
  if (!reorderPointsFiltered.length) {
    toast('Nada para imprimir — nenhum produto com ponto de compra em até 10 dias no momento.', true);
    return;
  }

  const company = await safeCall(() => api('GET', '/company-settings'));

  const today = new Date();
  const isoDate = today.toISOString().slice(0, 10); // YYYY-MM-DD
  const printFilename = `ponto_de_compra_${isoDate}`;

  const rowsHtml = reorderPointsFiltered.map((r, i) => `
    <tr class="${i % 2 === 1 ? 'stripe' : ''}">
      <td class="rp-print-barcode">${escapeHtml(r.productBarcode)}</td>
      <td>${escapeHtml(r.productName)}</td>
      <td class="rp-print-center">${r.quantity}</td>
      <td class="rp-print-center rp-print-date">${fmtDateOnly(r.reorderDate)}</td>
    </tr>`).join('');

  const companyLogoHtml = company.logoUrl
    ? `<img src="${company.logoUrl}?t=${Date.now()}" alt="${escapeHtml(company.name || '')}" class="rp-print-company-logo">`
    : `<div class="rp-print-company-name">${escapeHtml(company.name || '')}</div>`;

  const printArea = document.getElementById('reorder-points-print-area');
  printArea.innerHTML = `
    <div class="rp-print-header">
      <div class="rp-print-brand">${companyLogoHtml}</div>
      <div class="rp-print-title">
        <h1>Ponto de Compra</h1>
        <div class="print-meta">Gerado em ${fmtDate(today.toISOString())} — ${reorderPointsFiltered.length} produto${reorderPointsFiltered.length !== 1 ? 's' : ''}</div>
      </div>
      <img src="/img/kota-wordmark.png" alt="Easy Kota" class="rp-print-kota-logo">
    </div>
    <table>
      <colgroup>
        <col style="width:13%"><col style="width:68%"><col style="width:6%"><col style="width:13%">
      </colgroup>
      <thead><tr><th>Cód. de Barras</th><th>Produto</th><th class="rp-print-center">Qtd.</th><th class="rp-print-center">Ponto de compra</th></tr></thead>
      <tbody>${rowsHtml}</tbody>
    </table>
    <div class="rp-print-footer">Relatório Gerado por Easy Kota</div>`;

  const originalTitle = document.title;
  document.title = printFilename;
  document.body.classList.add('printing-reorder-points');

  const restore = () => {
    document.title = originalTitle;
    document.body.classList.remove('printing-reorder-points');
    printArea.innerHTML = '';
    window.removeEventListener('afterprint', restore);
  };
  window.addEventListener('afterprint', restore);

  // As duas logos (empresa + Kota) carregam via <img src="..."> — pedido de rede
  // assíncrono, que pode ainda não ter terminado no instante em que window.print()
  // seria chamado logo em seguida (a logo da empresa em especial, que não fica em
  // cache do navegador como o wordmark do Kota já costuma ficar). Sem esperar,
  // window.print() capturava a página com a logo ainda em branco. waitForImages
  // espera cada <img> carregar (ou falhar, com timeout de segurança) antes de imprimir.
  await waitForImages(printArea);
  window.print();
}

function waitForImages(container, timeoutMs = 3000) {
  const imgs = Array.from(container.querySelectorAll('img'));
  return Promise.all(imgs.map(img => {
    if (img.complete) return Promise.resolve();
    return new Promise(resolve => {
      const done = () => { img.removeEventListener('load', done); img.removeEventListener('error', done); resolve(); };
      img.addEventListener('load', done);
      img.addEventListener('error', done);
      setTimeout(done, timeoutMs);
    });
  }));
}
