// ============================================================
// DETALHE DA COTAÇÃO — INFO, ITENS, STEPPER
// ============================================================
let currentQuotationId = null;
let closeState = { tieBreakWinners: {}, excludedSupplierIds: [], acceptedViolationSupplierIds: [] };

// Grupo e Representantes são as duas formas de destinar uma cotação — mutuamente
// exclusivas (o backend também valida isso em QuotationService, não é só cosmético
// aqui). Trocar de aba limpa a seleção da outra, pra nunca ficar com as duas
// preenchidas ao mesmo tempo esperando "Salvar edição".
function setQdRecipientMode(mode) {
  document.querySelectorAll('#qd-recipient-mode-tabs .tab').forEach(t => t.classList.toggle('active', t.dataset.mode === mode));
  document.getElementById('qd-recipient-mode-group').style.display = mode === 'grupos' ? 'block' : 'none';
  document.getElementById('qd-recipient-mode-reps').style.display = mode === 'representantes' ? 'block' : 'none';
  if (mode === 'grupos') {
    qdExtraSupplierIds = [];
    const btn = document.getElementById('qd-extra-suppliers-btn');
    if (btn) btn.textContent = 'Selecionar representantes';
  } else {
    document.getElementById('qd-group').value = '';
  }
}

// Fornecedores avulsos selecionados pra cotação atual — só vira efetivo de verdade
// quando "Salvar edição" é clicado (updateQuotation lê esse array), mesmo padrão de
// qd-group (o <select> também só aplica ao salvar). qdAllSuppliersCache é o mesmo
// GET /suppliers já ordenado alfabeticamente (pt-BR) pelo backend — reaproveitado
// entre aberturas do modal na mesma sessão, igual groupsCache/productGroupsCache.
let qdExtraSupplierIds = [];
let qdAllSuppliersCache = [];
let qdExtraSuppliersPage = 0;
let qdExtraSuppliersSearchTerm = '';

// Modal de seleção — busca por nome + checkbox por fornecedor, paginado (nunca lista
// crua com scroll infinito, mesma convenção do resto do admin). A lista em si é
// re-renderizada isoladamente (renderQdExtraSuppliersList), sem recriar o modal
// inteiro a cada tecla digitada — senão o campo de busca perderia o foco a cada letra.
async function manageQdExtraSuppliers() {
  if (!qdAllSuppliersCache.length) {
    // Só entra quem tem representante vinculado — sem representante não tem quem
    // logar/receber e-mail, então não faz sentido aparecer numa lista de "Representantes".
    qdAllSuppliersCache = (await safeCall(() => api('GET', '/suppliers'))).filter(s => s.representativeId);
  }
  qdExtraSuppliersPage = 0;
  qdExtraSuppliersSearchTerm = '';
  openModal2(`
    <h2>Representantes</h2>
    <div class="subtitle" style="margin-bottom:12px">Adiciona a cotação diretamente pros representantes marcados aqui, sem depender de grupo nenhum.</div>
    <input id="qd-extra-suppliers-search" placeholder="Buscar representante ou empresa..." autocomplete="off" oninput="onQdExtraSuppliersSearch(this.value)" style="margin-bottom:10px">
    <div id="qd-extra-suppliers-list"></div>
    ${paginationControlsHtml('qd-extra-suppliers')}
    <div class="btn-row" style="margin-top:16px; justify-content:space-between">
      <span id="qd-extra-suppliers-selected-count" style="font-size:11.5px; color:var(--text-dim)">${qdExtraSupplierIds.length} selecionado(s)</span>
      <button onclick="closeModal2()">Concluir</button>
    </div>
  `);
  renderQdExtraSuppliersList();
}

function onQdExtraSuppliersSearch(term) {
  qdExtraSuppliersSearchTerm = term.trim().toLowerCase();
  qdExtraSuppliersPage = 0;
  renderQdExtraSuppliersList();
}

function renderQdExtraSuppliersList() {
  const filtered = qdExtraSuppliersSearchTerm
    ? qdAllSuppliersCache.filter(s =>
        s.name.toLowerCase().includes(qdExtraSuppliersSearchTerm) ||
        (s.representativeName || '').toLowerCase().includes(qdExtraSuppliersSearchTerm))
    : qdAllSuppliersCache;

  const { items, page, totalPages } = paginateSlice(filtered, qdExtraSuppliersPage, DEFAULT_PAGE_SIZE);
  qdExtraSuppliersPage = page;

  const listEl = document.getElementById('qd-extra-suppliers-list');
  if (listEl) {
    listEl.innerHTML = items.length
      ? items.map(s => `
          <label class="expiring-item" style="cursor:pointer; justify-content:flex-start">
            <input type="checkbox" value="${s.id}" ${qdExtraSupplierIds.includes(s.id) ? 'checked' : ''} onchange="toggleQdExtraSupplier(${s.id}, this.checked)">
            <span>${escapeHtml(s.representativeName)} <span style="color:var(--text-dim); font-size:11px">(${escapeHtml(s.name)})</span></span>
          </label>`).join('')
      : '<div class="empty">Nenhum representante encontrado.</div>';
  }

  updatePaginationControls('qd-extra-suppliers', page, totalPages, filtered.length, (newPage) => {
    qdExtraSuppliersPage = newPage;
    renderQdExtraSuppliersList();
  });
}

function toggleQdExtraSupplier(id, checked) {
  if (checked) {
    if (!qdExtraSupplierIds.includes(id)) qdExtraSupplierIds.push(id);
  } else {
    qdExtraSupplierIds = qdExtraSupplierIds.filter(x => x !== id);
  }
  const btn = document.getElementById('qd-extra-suppliers-btn');
  if (btn) btn.textContent = 'Selecionar representantes' + (qdExtraSupplierIds.length ? ` (${qdExtraSupplierIds.length})` : '');
  const countEl = document.getElementById('qd-extra-suppliers-selected-count');
  if (countEl) countEl.textContent = `${qdExtraSupplierIds.length} selecionado(s)`;
}

// Busca TUDO que a tela precisa antes de montar o conteúdo final — nada de abrir o modal
// já com os campos vazios e ir preenchendo conforme cada chamada de API responde (o efeito
// de "montando na hora" que dava a impressão de página quebrada). Mostra um carregando
// simples primeiro, busca os dados em paralelo (Promise.all — mais rápido que em série,
// já que uma chamada não depende da outra), e só troca pro conteúdo de verdade quando
// tudo já está pronto pra preencher de uma vez, sem nenhum "await" no meio da montagem.
async function abrirDetalheCotacao(id) {
  currentQuotationId = id;
  closeState = { tieBreakWinners: {}, excludedSupplierIds: [], acceptedViolationSupplierIds: [] };

  openModal('<div style="padding:60px 20px; text-align:center; color:var(--text-dim)">Carregando cotação…</div>', true);

  const [groups, q] = await Promise.all([
    safeCall(() => api('GET', '/supplier-groups')),
    safeCall(() => api('GET', `/quotations/${id}`))
  ]);

  // Itens depende de já saber o status (pra decidir cabeçalho/edição da tabela), mas o
  // cache de produtos (só usado se for Rascunho) não depende de nada — roda junto.
  const [items] = await Promise.all([
    safeCall(() => api('GET', `/quotations/${id}/items`)),
    q.status === 'DRAFT' ? loadQdProductsCache() : Promise.resolve()
  ]);

  qdCurrentItems = items;
  qdCurrentItemsIsDraft = q.status === 'DRAFT';
  qdItemsPage = 0;
  qdPendingQuantityEdits = {};
  qdExtraSupplierIds = q.extraSupplierIds ? [...q.extraSupplierIds] : [];
  // Grupo e Representantes são exclusivos — decide qual aba abre já marcada pelo que
  // essa cotação já tem hoje. Uma cotação nova (nenhum dos dois ainda) abre em
  // Representantes por padrão, só porque precisa começar em algum lugar.
  const qdInitialRecipientMode = q.supplierGroupId ? 'grupos' : 'representantes';

  openModal(`
    <div class="qd-modal-body">
    <div class="qd-modal-top">
    <h2 id="qd-title">Cotação Nº ${formatQuotationNumber(q.id)} - ${escapeHtml(q.name)} ${statusBadge(q)}</h2>

    <div class="qd-info-bar" id="qd-info-bar"></div>
    <div class="qd-stepper" id="qd-stepper"></div>

    <div class="inline-form">
      <div><label>Nome</label><input id="qd-name" placeholder="Ex: Cotação de Bebidas" value="${escapeHtml(q.name)}"></div>
      <div style="min-width:230px">
        <label>Destinatários da cotação</label>
        <div class="tabs" id="qd-recipient-mode-tabs" style="margin-bottom:6px">
          <div class="tab ${qdInitialRecipientMode === 'representantes' ? 'active' : ''}" data-mode="representantes" onclick="setQdRecipientMode('representantes')">Representantes</div>
          <div class="tab ${qdInitialRecipientMode === 'grupos' ? 'active' : ''}" data-mode="grupos" onclick="setQdRecipientMode('grupos')">Grupos</div>
        </div>
        <div id="qd-recipient-mode-reps" style="display:${qdInitialRecipientMode === 'representantes' ? 'block' : 'none'}">
          <button type="button" class="secondary" id="qd-extra-suppliers-btn" onclick="manageQdExtraSuppliers()">Selecionar representantes${q.extraSupplierNames && q.extraSupplierNames.length ? ` (${q.extraSupplierNames.length})` : ''}</button>
        </div>
        <div id="qd-recipient-mode-group" style="display:${qdInitialRecipientMode === 'grupos' ? 'block' : 'none'}">
          <select id="qd-group">
            <option value="">— selecione um grupo —</option>
            ${groups.map(g => `<option value="${g.id}" ${g.id === q.supplierGroupId ? 'selected' : ''}>${escapeHtml(g.name)}</option>`).join('')}
          </select>
        </div>
      </div>
      <div><label>Prazo de expiração</label>
        <div style="display:flex; gap:6px; flex-wrap:wrap">
          <input type="date" id="qd-expiration-date" style="flex:1.3">
          <input type="time" id="qd-expiration-time" style="flex:1">
        </div>
      </div>
      <div style="width:170px"><label>Projeção de venda padrão (dias)</label><input type="number" min="1" step="1" id="qd-sales-projection" placeholder="Ex: 30" value="${q.defaultSalesProjectionDays ?? ''}"></div>
      <button id="qd-save-btn" onclick="updateQuotation()">Salvar edição</button>
    </div>

    <div class="btn-row" style="margin-top:16px">
      <button id="qd-publish-btn" class="success" onclick="publishQuotation(this)">Publicar (Rascunho → Publicada)</button>
      <button id="qd-close-btn" class="secondary" onclick="closeQuotation(this)">Fechar (calcular vencedores)</button>
      <button id="qd-extend-btn" class="secondary" style="display:none" onclick="openExtendDeadlineModal()">Prorrogar Prazo</button>
      <button id="qd-republish-btn" class="success" style="display:none" onclick="openRepublishModal()">Republicar (mesmo Nº)</button>
      <button id="qd-whatsapp-btn" class="secondary" onclick="copyPublishMessage(this)" style="display:none">Copiar mensagem para WhatsApp</button>
      <button id="qd-whatsapp-result-btn" class="secondary" onclick="copyResultMessage(this)" style="display:none">Copiar mensagem para WhatsApp</button>
      <button id="qd-response-status-btn" class="secondary" onclick="openRepresentativeStatusModal()" style="display:none">Ver quem já respondeu</button>
      <button id="qd-history-btn" class="secondary" onclick="openQuotationHistoryModal()" style="display:none">Ver Histórico</button>
      <button id="qd-review-bids-btn" class="secondary" onclick="openReviewBidsModal()" style="display:none">Revisar Cotações Enviadas</button>
      <button id="qd-confirm-close-btn" class="success" onclick="confirmCloseQuotation(this)" style="display:none">Confirmar Fechamento (gerar PDF)</button>
      <button id="qd-pdf-link" class="secondary" style="display:${q.status === 'CLOSED' ? 'inline-block' : 'none'}" onclick="downloadPdfWithAuth('/quotations/' + currentQuotationId + '/result-pdf', 'cotacao-' + currentQuotationId + '.pdf', this)">Baixar PDF do resultado</button>
      <button id="qd-duplicate-btn" class="secondary" style="display:none" onclick="duplicateQuotation()">Gerar Outra Cotação</button>
      <button id="qd-delete-btn" class="danger" style="display:none" onclick="deleteQuotationFromModal(this)">Excluir Cotação</button>
    </div>

    <div id="close-flow"></div>

    <div id="qd-fulfillment-issues" style="display:none"></div>

    <hr class="divider">
    <div id="qd-add-item-panel" style="display:${qdCurrentItemsIsDraft ? 'block' : 'none'}">
      <h3>Adicionar item</h3>
      <div class="inline-form">
        <div style="flex:1">
          <label>Produto (nome ou código de barras)</label>
          <input id="qd-new-item-search" placeholder="Digite para buscar..." autocomplete="off" oninput="searchQdNewItemProduct()">
          <input type="hidden" id="qd-new-item-product-id">
        </div>
        <div><label>Quantidade</label><input type="number" step="0.001" id="qd-new-item-qty" style="width:120px"></div>
        <button class="secondary small" onclick="addQuotationItem()">+ Adicionar item</button>
      </div>
      <div id="qd-new-item-search-results"></div>
      <hr class="divider">
    </div>
    </div>

    <div class="qd-modal-items-area">
      <div style="display:flex; justify-content:space-between; align-items:center; gap:12px; flex-wrap:wrap; margin-bottom:12px">
        <h3 style="margin:0; font-size:14.5px; font-weight:600">Itens do lote</h3>
        <div style="position:relative; width:220px">
          <svg style="position:absolute; left:9px; top:50%; transform:translateY(-50%); width:14px; height:14px; opacity:0.5; pointer-events:none" viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2"><circle cx="11" cy="11" r="7"/><path d="M21 21l-4.3-4.3"/></svg>
          <input id="qd-items-filter" placeholder="Buscar nos itens..." oninput="filterQdItemsTable()" style="padding-left:30px">
        </div>
      </div>
      <div class="qd-items-table-wrap">
        <table class="qd-items-table"><thead><tr id="qd-items-thead">${qdCurrentItemsIsDraft
          ? '<th>Cód. de Barras</th><th>Produto</th><th style="text-align:center">Quantidade</th><th style="text-align:center">Projeção de venda (dias)</th><th></th>'
          : '<th>Cód. de Barras</th><th>Produto</th><th style="text-align:center">Quantidade</th><th style="text-align:center">Projeção de venda (dias)</th>'}</tr></thead>
          <tbody id="qd-items-tbody"></tbody></table>
      </div>
    </div>

    <div class="qd-modal-footer">
    <div class="btn-row" id="qd-items-pagination" style="justify-content:space-between; margin-top:10px">
      <div style="color:var(--text-dim); font-size:12px" id="qd-items-page-info">—</div>
      <div class="btn-row">
        <button class="secondary small" onclick="changeQdItemsPage(-1)" id="qd-items-prev-btn">← Anterior</button>
        <button class="secondary small" onclick="changeQdItemsPage(1)" id="qd-items-next-btn">Próxima →</button>
      </div>
    </div>
    <div class="btn-row" id="qd-save-quantities-row" style="display:${qdCurrentItemsIsDraft ? 'flex' : 'none'}; justify-content:flex-end; margin-top:12px">
      <button onclick="saveAllQuotationItemQuantities()">Salvar quantidades</button>
    </div>

    <div class="btn-row" style="justify-content:flex-start; margin-top:20px">
      <button class="secondary" onclick="closeModal()">Fechar</button>
    </div>
    </div>
    </div>
  `, true);

  setExpirationValue('qd-expiration', q.expirationDate);
  renderQdStepper(q.status, q.hasBids);
  applyQdEditLock(q.status, q.hasBids);
  document.getElementById('qd-items-filter').value = '';
  renderQdItemsRows(items);
  renderQdFulfillmentIssues(items, q.status);
  renderQdInfoBar(q);
}

// Rascunho > Disponível > Em Revisão > Fechada — dá pro admin bater o olho e saber
// exatamente em que ponto do ciclo aquela cotação está e o que falta pra chegar no fim,
// sem precisar decorar o que cada status "quer dizer". Expirada é tratada à parte: não é
// uma etapa normal do caminho, é o que acontece quando ninguém fecha a tempo — trava
// visualmente logo depois de "Disponível" (foi até ali que ela chegou de verdade). Com
// hasBids=false (ninguém respondeu) o aviso é vermelho, de verdade sem solução. Com
// hasBids=true (tem lance pendente, só falta clicar em Fechar) usa o tom âmbar de "Em
// Revisão" — vermelho ali passaria a ideia de erro/falha quando na real é só uma etapa
// que ficou pra trás.
function renderQdStepper(status, hasBids) {
  const steps = [
    { key: 'DRAFT', label: 'Rascunho' },
    { key: 'AVAILABLE', label: 'Publicada' },
    { key: 'REVIEWING', label: 'Em Revisão' },
    { key: 'CLOSED', label: 'Concluída' }
  ];
  const order = ['DRAFT', 'AVAILABLE', 'REVIEWING', 'CLOSED'];
  const isExpired = status === 'EXPIRED';
  const expiredWithBids = isExpired && hasBids;
  const effectiveIndex = isExpired ? 1 : order.indexOf(status);

  const CHECK_SVG = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="3"><path d="M5 12l4 4L19 7"/></svg>';

  const stepsHtml = steps.map((step, idx) => {
    let stateClass = 'upcoming';
    if (idx < effectiveIndex) stateClass = 'done';
    else if (idx === effectiveIndex) stateClass = isExpired ? 'expired-at' : 'current';
    if (stateClass === 'expired-at' && expiredWithBids) stateClass += ' has-bids';

    const dotContent = stateClass === 'done' ? CHECK_SVG : (idx + 1);
    const lineClass = idx < effectiveIndex ? 'done' : '';
    const line = idx < steps.length - 1 ? `<div class="qd-step-line ${lineClass}"></div>` : '';

    return `<div class="qd-step ${stateClass}">
      <div class="qd-step-dot">${dotContent}</div>
      <div class="qd-step-label">${step.label}</div>
    </div>${line}`;
  }).join('');

  let expiredNote = '';
  if (expiredWithBids) {
    expiredNote = '<div class="qd-expired-note has-bids">⏳ O prazo passou, mas há lances registrados — clique em "Fechar" pra calcular os vencedores.</div>';
  } else if (isExpired) {
    expiredNote = '<div class="qd-expired-note">⚠ Expirou antes de ser fechada — o prazo passou sem ninguém responder.</div>';
  }

  document.getElementById('qd-stepper').innerHTML = `<div class="qd-stepper-track">${stepsHtml}</div>${expiredNote}`;
}

// Resumo rápido — datas relevantes conforme a etapa (não faz sentido mostrar "Fechada em"
// pra quem ainda tá em Rascunho), quantidade de itens, e quantos fornecedores do grupo já
// responderam. Fill rate busca de novo aqui porque o modal pode ser aberto de qualquer
// tela (Dashboard, Cotações, Relatórios) — nem todas já tinham esse dado em cache.
async function renderQdInfoBar(q) {
  const fillRates = await safeCall(() => api('GET', '/quotations/representative-fill-rate'));
  const fillRate = fillRates.find(f => f.quotationId === q.id);

  const stats = [
    { label: 'Grupo', value: q.supplierGroupName || 'sem grupo' },
    { label: 'Itens', value: qdCurrentItems.length }
  ];

  if (q.status === 'DRAFT') {
    stats.push({ label: 'Criada em', value: fmtDate(q.createdAt) });
  } else if (q.status === 'CLOSED') {
    stats.push({ label: 'Publicada em', value: fmtDate(q.publishedAt) });
    stats.push({ label: 'Concluída em', value: fmtDate(q.updatedAt) });
  } else if (q.status === 'EXPIRED') {
    stats.push({ label: 'Publicada em', value: fmtDate(q.publishedAt) });
    stats.push({ label: 'Expirou em', value: fmtDate(q.expirationDate) });
  } else {
    stats.push({ label: 'Publicada em', value: fmtDate(q.publishedAt) });
    stats.push({ label: 'Expira em', value: fmtDate(q.expirationDate) });
  }

  if (fillRate) {
    stats.push({ label: 'Responderam', value: `${fillRate.filledCount}/${fillRate.eligibleCount}` });
  }

  document.getElementById('qd-info-bar').innerHTML = stats.map(s => `
    <div class="qd-stat">
      <div class="qd-stat-value">${s.value}</div>
      <div class="qd-stat-label">${s.label}</div>
    </div>
  `).join('');
}

// Fora de Rascunho, nome/grupo/prazo/publicar/gerenciar grupo ficam travados. "Fechar"
// funciona em Disponível ou Expirada. Em Revisão (depois de calcular vencedores, antes
// de confirmar), some "Fechar" e aparece "Confirmar Fechamento" — a tabela geral de itens
// fica travada nesse meio-tempo (ver qdCurrentItemsIsDraft mais abaixo); os ajustes finos
// de quantidade/preço/item de cada representante passam a ser feitos exclusivamente pelo
// modal "Revisar Lances Enviados", de forma individual por vencedor.
function applyQdEditLock(status, hasBids) {
  const isDraft = status === 'DRAFT';
  const isReviewing = status === 'REVIEWING';
  const isExpired = status === 'EXPIRED';
  const canClose = status === 'AVAILABLE' || isExpired;

  ['qd-name', 'qd-group', 'qd-expiration-date', 'qd-expiration-time', 'qd-sales-projection'].forEach(id => {
    document.getElementById(id).disabled = !isDraft;
  });
  document.getElementById('qd-save-btn').disabled = !isDraft;
  document.getElementById('qd-extra-suppliers-btn').disabled = !isDraft;
  document.querySelectorAll('#qd-recipient-mode-tabs .tab').forEach(t => {
    t.style.pointerEvents = isDraft ? 'auto' : 'none';
    t.style.opacity = isDraft ? '1' : '0.55';
  });
  document.getElementById('qd-publish-btn').disabled = !isDraft;
  document.getElementById('qd-close-btn').style.display = canClose ? 'inline-block' : 'none';
  document.getElementById('qd-extend-btn').style.display = status === 'AVAILABLE' ? 'inline-block' : 'none';
  document.getElementById('qd-republish-btn').style.display = (isExpired && !hasBids) ? 'inline-block' : 'none';
  document.getElementById('qd-whatsapp-btn').style.display = status === 'AVAILABLE' ? 'inline-block' : 'none';
  document.getElementById('qd-whatsapp-result-btn').style.display = status === 'CLOSED' ? 'inline-block' : 'none';
  document.getElementById('qd-response-status-btn').style.display = !isDraft ? 'inline-block' : 'none';
  document.getElementById('qd-history-btn').style.display = 'inline-block';
  document.getElementById('qd-review-bids-btn').style.display = isReviewing ? 'inline-block' : 'none';
  document.getElementById('qd-confirm-close-btn').style.display = isReviewing ? 'inline-block' : 'none';
  document.getElementById('qd-duplicate-btn').style.display = isExpired ? 'inline-block' : 'none';
  document.getElementById('qd-delete-btn').style.display = isDraft ? 'inline-block' : 'none';
}

let qdCurrentItems = [];
let qdCurrentItemsIsDraft = false;
let qdItemsPage = 0;
let qdPendingQuantityEdits = {};
const QD_ITEMS_PAGE_SIZE = 6;

async function loadQuotationItemsDetail(id, status) {
  const items = await safeCall(() => api('GET', `/quotations/${id}/items`));
  qdCurrentItems = items;
  renderQdFulfillmentIssues(items, status);
  // Editável só em Rascunho. Em Revisão, a tabela geral vira somente leitura — editar
  // quantidade, adicionar ou excluir item aqui derrubaria TODOS os vencedores já calculados
  // (não só o item mexido). Ajustes durante a revisão são feitos por representante, no modal
  // "Revisar Lances Enviados", que mexe só no item daquele vencedor sem invalidar o resto.
  qdCurrentItemsIsDraft = status === 'DRAFT';
  qdItemsPage = 0;
  qdPendingQuantityEdits = {};

  document.getElementById('qd-items-thead').innerHTML = qdCurrentItemsIsDraft
    ? '<th>Cód. de Barras</th><th>Produto</th><th style="text-align:center">Quantidade</th><th style="text-align:center">Projeção de venda (dias)</th><th></th>'
    : '<th>Cód. de Barras</th><th>Produto</th><th style="text-align:center">Quantidade</th><th style="text-align:center">Projeção de venda (dias)</th>';

  document.getElementById('qd-items-filter').value = '';
  renderQdItemsRows(items);

  document.getElementById('qd-add-item-panel').style.display = qdCurrentItemsIsDraft ? 'block' : 'none';
  document.getElementById('qd-save-quantities-row').style.display = qdCurrentItemsIsDraft ? 'flex' : 'none';
  if (qdCurrentItemsIsDraft) {
    await loadQdProductsCache();
  }
}

const QD_TRASH_ICON = '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.8" width="15" height="15"><path d="M3 6h18M8 6V4a2 2 0 012-2h4a2 2 0 012 2v2m3 0l-1 14a2 2 0 01-2 2H7a2 2 0 01-2-2L4 6h16z"/></svg>';

// Lista completa de cada bloco (Sem lance / Cortados) — guardada à parte pra alimentar o
// modal "Ver mais", já que o painel de atenção em si não mostra mais nenhum item (só
// título + contagem + botão) — uma cotação com dezenas de produtos sem atendimento
// não empurra mais o resto do modal pra baixo.
let qdNoWinnerItemsFull = [];
let qdCutItemsFull = [];

const qdItemLine = i => `
  <div class="expiring-item">
    <span><strong style="font-size:12.5px">${escapeHtml(i.productName)}</strong> <span class="mono" style="color:var(--text-dim); font-size:11px">(${escapeHtml(i.productBarcode)})</span></span>
    <span style="color:var(--text-dim); font-size:11.5px; white-space:nowrap; flex-shrink:0">${i.quantity} un.</span>
  </div>`;

// which é 'noWinner' ou 'cut' — decide qual lista/título mostrar e serve de sufixo
// pros ids/handlers compartilhados entre os dois blocos (evita duplicar toda essa
// função pra cada caso).
const QD_FULFILLMENT_LABELS = {
  noWinner: { title: 'Sem nenhum lance', description: 'Nenhum representante ofertou esses produtos — a cotação fechou sem vencedor pra eles.' },
  cut: { title: 'Cortados por falta de estoque', description: 'O representante venceu, mas depois confirmou que não tem esse item em estoque.' }
};

// Modal "Ver mais" — layout em lista (uma linha por produto, com respiro entre elas e
// dentro de uma caixa com rolagem própria) em vez da lista crua de antes, que ficava
// toda espremida e com o botão Fechar colado em cima do último item.
function qdOpenFullItemListModal(which) {
  const items = which === 'cut' ? qdCutItemsFull : qdNoWinnerItemsFull;
  const labels = QD_FULFILLMENT_LABELS[which];
  openModal2(`
    <h2>${labels.title} (${items.length})</h2>
    <div class="subtitle" style="margin-bottom:12px">${labels.description}</div>
    <div class="scroll-box" style="max-height:50vh; overflow-y:auto; border:1px solid var(--border); border-radius:9px; padding:2px 12px">
      ${items.map(qdItemLine).join('')}
    </div>
    <div class="btn-row" style="margin-top:16px">
      <button class="secondary" onclick="closeModal2()">Fechar</button>
    </div>
  `, true);
}

// Painel de atenção, só pra cotação FECHADA — só nessa fase "sem vencedor" e "cortado"
// têm sentido (antes disso winningBidId é sempre nulo pra todo mundo, é só o processo
// normal de ainda não ter fechado). Some sozinho quando não há nenhum caso, pra não virar
// ruído em toda cotação fechada sem problema nenhum. Cada bloco mostra só título +
// contagem + descrição + botões — nenhum item aparece direto no painel, só dentro do
// modal "Ver mais" (qdOpenFullItemListModal).
function renderQdFulfillmentIssues(items, status) {
  const wrap = document.getElementById('qd-fulfillment-issues');
  if (status !== 'CLOSED') { wrap.style.display = 'none'; wrap.innerHTML = ''; return; }

  const noWinnerItems = items.filter(i => !i.winningBidId);
  const cutItems = items.filter(i => i.fulfillmentCut);
  if (!noWinnerItems.length && !cutItems.length) { wrap.style.display = 'none'; wrap.innerHTML = ''; return; }

  qdNoWinnerItemsFull = noWinnerItems;
  qdCutItemsFull = cutItems;

  const qdFulfillmentBlock = (which, count) => count ? `
    <div style="margin-bottom:${which === 'noWinner' && cutItems.length ? '10px' : '0'}">
      <div style="font-weight:700; color:var(--${which === 'cut' ? 'danger' : 'warning'}); font-size:11.5px">${QD_FULFILLMENT_LABELS[which].title} (${count})</div>
      <div style="font-size:10.5px; color:var(--text-dim); margin-bottom:6px">${QD_FULFILLMENT_LABELS[which].description}</div>
      <div class="btn-row" style="margin:0">
        <button class="secondary small" onclick="qdOpenFullItemListModal('${which}')">Ver mais</button>
        <button class="secondary small" onclick="duplicateFulfillmentItems('${which}')">Gerar Outra Cotação</button>
        <button class="secondary small" id="qd-add-to-existing-${which}-btn" style="display:none" onclick="openAddToExistingModal('${which}')">Adicionar a uma cotação existente</button>
      </div>
    </div>` : '';

  wrap.style.display = 'block';
  wrap.innerHTML = `
    <div style="background:var(--warning-bg); border:1px solid var(--warning-border); border-radius:9px; padding:10px 13px; margin-top:10px">
      <div style="font-weight:700; font-size:12px; margin-bottom:5px">⚠ Produtos sem atendimento</div>
      ${qdFulfillmentBlock('noWinner', noWinnerItems.length)}
      ${qdFulfillmentBlock('cut', cutItems.length)}
    </div>`;

  checkDraftQuotationsForAddButtons();
}

// Botões "Adicionar a uma cotação existente" (dos dois blocos) só aparecem se houver
// pelo menos um Rascunho pra receber os itens — busca à parte pra não travar a
// renderização do painel de atenção esperando essa checagem.
async function checkDraftQuotationsForAddButtons() {
  const all = await safeCall(() => api('GET', '/quotations'));
  const hasDraft = all.some(q => q.status === 'DRAFT' && q.id !== currentQuotationId);
  ['noWinner', 'cut'].forEach(which => {
    const btn = document.getElementById(`qd-add-to-existing-${which}-btn`);
    if (btn) btn.style.display = hasDraft ? 'inline-block' : 'none';
  });
}

// Modal simples: escolhe um Rascunho existente e adiciona os itens (sem lance OU
// cortados, conforme which) um a um — não existe endpoint de lote pra isso (POST
// /quotations/{id}/items já é o suficiente pra uma lista curta de itens "sobrando",
// sem precisar criar um endpoint novo só pra isso).
async function openAddToExistingModal(which) {
  const items = which === 'cut' ? qdCutItemsFull : qdNoWinnerItemsFull;
  const label = QD_FULFILLMENT_LABELS[which].title.toLowerCase();
  const all = await safeCall(() => api('GET', '/quotations'));
  const drafts = all.filter(q => q.status === 'DRAFT' && q.id !== currentQuotationId);

  openModal2(`
    <h2>Adicionar a uma cotação existente</h2>
    <div class="subtitle" style="margin-bottom:14px">Os ${items.length} produtos ${label} dessa cotação serão adicionados ao Rascunho escolhido.</div>
    <label>Cotação em Rascunho</label>
    <select id="add-existing-target">
      ${drafts.map(q => `<option value="${q.id}">${escapeHtml(q.name)} (Nº ${formatQuotationNumber(q.id)})</option>`).join('')}
    </select>
    <input type="hidden" id="add-existing-which" value="${which}">
    <div class="btn-row" style="margin-top:16px; justify-content:space-between">
      <button class="secondary" onclick="closeModal2()">Cancelar</button>
      <button id="add-existing-confirm-btn" onclick="confirmAddToExisting()">Adicionar</button>
    </div>
  `);
}

async function confirmAddToExisting() {
  const which = document.getElementById('add-existing-which').value;
  const targetId = document.getElementById('add-existing-target').value;
  const items = which === 'cut' ? qdCutItemsFull : qdNoWinnerItemsFull;
  const btn = document.getElementById('add-existing-confirm-btn');

  let added = 0;
  let failed = 0;
  await withButtonLoading(btn, 'Adicionando...', async () => {
    for (const item of items) {
      try {
        await api('POST', `/quotations/${targetId}/items`, { productId: item.productId, quantity: item.quantity });
        added++;
      } catch (e) {
        failed++;
      }
    }
  });

  if (failed) {
    toast(`${added} adicionado${added !== 1 ? 's' : ''}, ${failed} falhou/falharam (já deve existir nesse rascunho).`, true);
  } else {
    toast(`${added} produto${added !== 1 ? 's' : ''} adicionado${added !== 1 ? 's' : ''} à cotação escolhida.`);
  }
  closeModal2();
}

// Histórico/timeline da cotação — lista de eventos (criada, publicada, lance recebido,
// lembrete enviado, prazo prorrogado, fechada) em ordem cronológica, montada a partir
// do que o backend já registrou em cada transição (não é reconstruído aqui no front).
const QD_EVENT_LABELS = {
  CREATED: 'Criada',
  PUBLISHED: 'Publicada',
  BID_RECEIVED: 'Lance recebido',
  REMINDER_SENT: 'Lembrete enviado',
  DEADLINE_EXTENDED: 'Prazo prorrogado',
  CLOSED: 'Concluída',
  REPUBLISHED: 'Republicada',
  DECLINED: 'Recusada (Não Cotar)',
  FULFILLMENT_CONFIRMED: 'Atendimento confirmado',
  EMAIL_SENT: 'E-mail disparado'
};
const QD_EVENT_COLORS = {
  CREATED: 'var(--text-dim)',
  PUBLISHED: 'var(--accent)',
  BID_RECEIVED: 'var(--success)',
  REMINDER_SENT: 'var(--warning)',
  DEADLINE_EXTENDED: 'var(--warning)',
  CLOSED: 'var(--accent)',
  REPUBLISHED: 'var(--accent)',
  DECLINED: 'var(--danger)',
  FULFILLMENT_CONFIRMED: 'var(--success)',
  EMAIL_SENT: 'var(--text-dim)'
};

let qdHistoryEvents = [];
let qdHistoryPage = 0;
const QD_HISTORY_PAGE_SIZE = 5;

async function openQuotationHistoryModal() {
  qdHistoryEvents = await safeCall(() => api('GET', `/quotations/${currentQuotationId}/events`));
  qdHistoryPage = 0;

  openModal2(`
    <h2>Histórico da cotação</h2>
    <div class="scroll-box" style="max-height:60vh" id="qd-history-body"></div>
    ${paginationControlsHtml('qd-history')}
    <div class="btn-row" style="margin-top:16px">
      <button class="secondary" onclick="closeModal2()">Fechar</button>
    </div>
  `);
  renderQdHistoryPage();
}

function renderQdHistoryPage() {
  const { items, page, totalPages } = paginateSlice(qdHistoryEvents, qdHistoryPage, QD_HISTORY_PAGE_SIZE);
  qdHistoryPage = page;

  const bodyEl = document.getElementById('qd-history-body');
  if (!bodyEl) return;

  bodyEl.innerHTML = items.length
    ? items.map(e => `
        <div style="display:flex; gap:10px; padding:8px 0; border-bottom:1px solid var(--surface-2)">
          <div style="width:8px; height:8px; border-radius:50%; margin-top:5px; flex-shrink:0; background:${QD_EVENT_COLORS[e.type] || 'var(--text-dim)'}"></div>
          <div style="flex:1; min-width:0">
            <div style="font-size:11px; font-weight:700; color:var(--text-dim); text-transform:uppercase; letter-spacing:0.03em">${QD_EVENT_LABELS[e.type] || e.type}</div>
            <div style="font-size:13px; margin-top:2px">${escapeHtml(e.description)}</div>
            <div style="font-size:11px; color:var(--text-dim); margin-top:2px">${fmtDate(e.occurredAt)}${e.performedBy ? ' — por ' + escapeHtml(e.performedBy) : ''}</div>
          </div>
        </div>`).join('')
    : '<div class="empty">Nenhum evento registrado ainda.</div>';

  updatePaginationControls('qd-history', page, totalPages, qdHistoryEvents.length, (newPage) => {
    qdHistoryPage = newPage;
    renderQdHistoryPage();
  });
}

// Revisão em modal separado (segunda camada), sob demanda — só busca lances quando o
// admin realmente clica no botão, e só monta a lista de UM representante por vez, escolhido
// no dropdown. Com cotações grandes (centenas de itens, várias dezenas de fornecedores),
// montar tudo de uma vez travaria a tela — assim o custo fica limitado a quem está selecionado.
