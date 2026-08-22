// ============================================================
// DETALHE DA COTAÇÃO — INFO, ITENS, STEPPER
// ============================================================
let currentQuotationId = null;
let closeState = { tieBreakWinners: {}, excludedSupplierIds: [], acceptedViolationSupplierIds: [] };

async function abrirDetalheCotacao(id) {
  currentQuotationId = id;
  closeState = { tieBreakWinners: {}, excludedSupplierIds: [], acceptedViolationSupplierIds: [] };

  openModal(`
    <div class="qd-modal-body">
    <div class="qd-modal-top">
    <h2 id="qd-title">Detalhe da cotação</h2>

    <div class="qd-info-bar" id="qd-info-bar"></div>
    <div class="qd-stepper" id="qd-stepper"></div>

    <div class="inline-form">
      <div><label>Nome</label><input id="qd-name" placeholder="Ex: Cotação de Bebidas"></div>
      <div><label>Grupo</label><select id="qd-group"></select></div>
      <div><label>Prazo de expiração</label>
        <div style="display:flex; gap:6px">
          <input type="date" id="qd-expiration-date" style="flex:1.3">
          <input type="time" id="qd-expiration-time" style="flex:1">
        </div>
      </div>
      <div style="width:170px"><label>Projeção de venda padrão (dias)</label><input type="number" min="1" step="1" id="qd-sales-projection" placeholder="Ex: 30"></div>
      <button id="qd-save-btn" onclick="updateQuotation()">Salvar edição</button>
      <button id="qd-group-suppliers-btn" class="secondary" onclick="manageQdGroupSuppliers()">Fornecedores do Grupo</button>
    </div>

    <div class="btn-row" style="margin-top:16px">
      <button id="qd-publish-btn" class="success" onclick="publishQuotation()">Publicar (Rascunho → Disponível)</button>
      <button id="qd-close-btn" class="secondary" onclick="closeQuotation(this)">Fechar (calcular vencedores)</button>
      <button id="qd-whatsapp-btn" class="secondary" onclick="copyPublishMessage(this)" style="display:none">Copiar mensagem para WhatsApp</button>
      <button id="qd-response-status-btn" class="secondary" onclick="openRepresentativeStatusModal()" style="display:none">Ver quem já respondeu</button>
      <button id="qd-review-bids-btn" class="secondary" onclick="openReviewBidsModal()" style="display:none">Revisar Cotações Enviadas</button>
      <button id="qd-confirm-close-btn" class="success" onclick="confirmCloseQuotation(this)" style="display:none">Confirmar Fechamento (gerar PDF)</button>
      <button id="qd-pdf-link" class="secondary" style="display:none" onclick="downloadPdfWithAuth('/quotations/' + currentQuotationId + '/result-pdf', 'cotacao-' + currentQuotationId + '.pdf')">Baixar PDF do resultado</button>
      <button id="qd-duplicate-btn" class="secondary" style="display:none" onclick="duplicateQuotation()">Gerar Outra Cotação</button>
      <button id="qd-delete-btn" class="danger" style="display:none" onclick="deleteQuotationFromModal(this)">Excluir Cotação</button>
    </div>

    <div id="close-flow"></div>

    <div id="qd-fulfillment-issues" style="display:none"></div>

    <hr class="divider">
    <div id="qd-add-item-panel" style="display:none">
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
        <table class="qd-items-table"><thead><tr id="qd-items-thead"></tr></thead>
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
    <div class="btn-row" id="qd-save-quantities-row" style="display:none; justify-content:flex-end; margin-top:12px">
      <button onclick="saveAllQuotationItemQuantities()">Salvar quantidades</button>
    </div>

    <div class="btn-row" style="justify-content:flex-start; margin-top:20px">
      <button class="secondary" onclick="closeModal()">Fechar</button>
    </div>
    </div>
    </div>
  `, true);

  const groups = await safeCall(() => api('GET', '/supplier-groups'));
  document.getElementById('qd-group').innerHTML = '<option value="">— nenhum —</option>' +
    groups.map(g => `<option value="${g.id}">${g.name}</option>`).join('');

  const q = await safeCall(() => api('GET', `/quotations/${id}`));
  document.getElementById('qd-title').innerHTML = `Cotação Nº ${formatQuotationNumber(q.id)} - ${escapeHtml(q.name)} ${statusBadge(q)}`;
  document.getElementById('qd-name').value = q.name;
  document.getElementById('qd-group').value = q.supplierGroupId || '';
  setExpirationValue('qd-expiration', q.expirationDate);
  document.getElementById('qd-sales-projection').value = q.defaultSalesProjectionDays ?? '';

  const pdfLink = document.getElementById('qd-pdf-link');
  pdfLink.style.display = q.status === 'CLOSED' ? 'inline-block' : 'none';

  renderQdStepper(q.status, q.hasBids);
  applyQdEditLock(q.status);
  await loadQuotationItemsDetail(id, q.status);
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
    { key: 'AVAILABLE', label: 'Disponível' },
    { key: 'REVIEWING', label: 'Em Revisão' },
    { key: 'CLOSED', label: 'Fechada' }
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
    stats.push({ label: 'Fechada em', value: fmtDate(q.updatedAt) });
  } else if (q.status === 'EXPIRED') {
    stats.push({ label: 'Expirou em', value: fmtDate(q.expirationDate) });
  } else {
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
function applyQdEditLock(status) {
  const isDraft = status === 'DRAFT';
  const isReviewing = status === 'REVIEWING';
  const isExpired = status === 'EXPIRED';
  const canClose = status === 'AVAILABLE' || isExpired;

  ['qd-name', 'qd-group', 'qd-expiration-date', 'qd-expiration-time', 'qd-sales-projection'].forEach(id => {
    document.getElementById(id).disabled = !isDraft;
  });
  document.getElementById('qd-save-btn').disabled = !isDraft;
  document.getElementById('qd-group-suppliers-btn').disabled = !isDraft;
  document.getElementById('qd-publish-btn').disabled = !isDraft;
  document.getElementById('qd-close-btn').style.display = canClose ? 'inline-block' : 'none';
  document.getElementById('qd-whatsapp-btn').style.display = status === 'AVAILABLE' ? 'inline-block' : 'none';
  document.getElementById('qd-response-status-btn').style.display = !isDraft ? 'inline-block' : 'none';
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
// modal "Ver mais" sem precisar embutir o array inteiro no onclick (JSON escapado em
// atributo HTML é frágil e ilegível). Só o bloco visível na tela usa QD_ITEM_LIST_LIMIT;
// o "Ver mais" mostra a lista inteira.
const QD_ITEM_LIST_LIMIT = 6;
let qdNoWinnerItemsFull = [];
let qdCutItemsFull = [];

const qdItemLine = i => `<li><strong>${escapeHtml(i.productName)}</strong> <span class="mono" style="color:var(--text-dim); font-size:12px">(${escapeHtml(i.productBarcode)})</span> — ${i.quantity} un.</li>`;

// Corta a lista em QD_ITEM_LIST_LIMIT itens e, se sobrar mais, acrescenta um botão "Ver
// mais" que abre a lista inteira num modal à parte — sem isso, o painel de atenção podia
// crescer indefinidamente na tela (uma cotação com dezenas de produtos sem atendimento
// empurrava todo o resto do modal pra baixo, ficando difícil até de rolar até o fim).
function qdRenderItemListWithMore(items, which) {
  const visible = items.slice(0, QD_ITEM_LIST_LIMIT);
  const listHtml = `<ul style="margin:0 0 8px; padding-left:18px; font-size:13px">${visible.map(qdItemLine).join('')}</ul>`;
  if (items.length <= QD_ITEM_LIST_LIMIT) return listHtml;
  const remaining = items.length - QD_ITEM_LIST_LIMIT;
  return `${listHtml}<button class="secondary small" onclick="qdOpenFullItemListModal('${which}')">Ver mais (${remaining})</button>`;
}

function qdOpenFullItemListModal(which) {
  const isCut = which === 'cut';
  const items = isCut ? qdCutItemsFull : qdNoWinnerItemsFull;
  const title = isCut ? 'Cortados por falta de estoque' : 'Produtos sem nenhum lance';
  openModal2(`
    <h2>${title} (${items.length})</h2>
    <div class="scroll-box" style="max-height:60vh">
      <ul style="margin:0; padding-left:18px; font-size:13px">${items.map(qdItemLine).join('')}</ul>
    </div>
    <div class="btn-row" style="margin-top:16px">
      <button class="secondary" onclick="closeModal2()">Fechar</button>
    </div>
  `);
}

// Painel de atenção, só pra cotação FECHADA — só nessa fase "sem vencedor" e "cortado"
// têm sentido (antes disso winningBidId é sempre nulo pra todo mundo, é só o processo
// normal de ainda não ter fechado). Some sozinho quando não há nenhum caso, pra não virar
// ruído em toda cotação fechada sem problema nenhum.
function renderQdFulfillmentIssues(items, status) {
  const wrap = document.getElementById('qd-fulfillment-issues');
  if (status !== 'CLOSED') { wrap.style.display = 'none'; wrap.innerHTML = ''; return; }

  const noWinnerItems = items.filter(i => !i.winningBidId);
  const cutItems = items.filter(i => i.fulfillmentCut);
  if (!noWinnerItems.length && !cutItems.length) { wrap.style.display = 'none'; wrap.innerHTML = ''; return; }

  qdNoWinnerItemsFull = noWinnerItems;
  qdCutItemsFull = cutItems;

  const noWinnerBlock = noWinnerItems.length ? `
    <div style="margin-bottom:${cutItems.length ? '14px' : '0'}">
      <div style="font-weight:700; color:var(--warning); font-size:13px">Sem nenhum lance (${noWinnerItems.length})</div>
      <div style="font-size:12px; color:var(--text-dim); margin-bottom:6px">Nenhum representante ofertou esses produtos — a cotação fechou sem vencedor pra eles.</div>
      ${qdRenderItemListWithMore(noWinnerItems, 'noWinner')}
      <div class="btn-row" style="margin-top:6px">
        <button class="secondary small" onclick="duplicateUnquotedItems()">Gerar Outra Cotação</button>
        <button class="secondary small" id="qd-add-to-existing-btn" style="display:none" onclick="openAddUnquotedToExistingModal()">Adicionar a uma cotação existente</button>
      </div>
    </div>` : '';

  const cutBlock = cutItems.length ? `
    <div>
      <div style="font-weight:700; color:var(--danger); font-size:13px">Cortados por falta de estoque (${cutItems.length})</div>
      <div style="font-size:12px; color:var(--text-dim); margin-bottom:6px">O representante venceu, mas depois confirmou que não tem esse item em estoque.</div>
      ${qdRenderItemListWithMore(cutItems, 'cut')}
    </div>` : '';

  wrap.style.display = 'block';
  wrap.innerHTML = `
    <div style="background:var(--warning-bg); border:1px solid var(--warning-border); border-radius:10px; padding:14px 16px; margin-top:14px">
      <div style="font-weight:700; font-size:14px; margin-bottom:8px">⚠ Produtos sem atendimento</div>
      ${noWinnerBlock}
      ${cutBlock}
    </div>`;

  if (noWinnerItems.length) checkDraftQuotationsForAddButton();
}

// Botão "Adicionar a uma cotação existente" só aparece se houver pelo menos um
// Rascunho pra receber os itens — busca à parte pra não travar a renderização do
// painel de atenção esperando essa checagem.
async function checkDraftQuotationsForAddButton() {
  const all = await safeCall(() => api('GET', '/quotations'));
  const hasDraft = all.some(q => q.status === 'DRAFT' && q.id !== currentQuotationId);
  const btn = document.getElementById('qd-add-to-existing-btn');
  if (btn) btn.style.display = hasDraft ? 'inline-block' : 'none';
}

// Modal simples: escolhe um Rascunho existente e adiciona os itens sem lance um a um
// (não existe endpoint de lote pra isso — POST /quotations/{id}/items já é o suficiente
// pra uma lista curta de itens "sobrando", sem precisar criar um endpoint novo só pra isso).
async function openAddUnquotedToExistingModal() {
  const noWinnerItems = qdCurrentItems.filter(i => !i.winningBidId);
  const all = await safeCall(() => api('GET', '/quotations'));
  const drafts = all.filter(q => q.status === 'DRAFT' && q.id !== currentQuotationId);

  openModal2(`
    <h2>Adicionar a uma cotação existente</h2>
    <div class="subtitle" style="margin-bottom:14px">Os ${noWinnerItems.length} produtos sem lance dessa cotação serão adicionados ao Rascunho escolhido.</div>
    <label>Cotação em Rascunho</label>
    <select id="add-unquoted-target">
      ${drafts.map(q => `<option value="${q.id}">${escapeHtml(q.name)} (Nº ${formatQuotationNumber(q.id)})</option>`).join('')}
    </select>
    <div class="btn-row" style="margin-top:16px; justify-content:space-between">
      <button class="secondary" onclick="closeModal2()">Cancelar</button>
      <button id="add-unquoted-confirm-btn" onclick="confirmAddUnquotedToExisting()">Adicionar</button>
    </div>
  `);
}

async function confirmAddUnquotedToExisting() {
  const targetId = document.getElementById('add-unquoted-target').value;
  const noWinnerItems = qdCurrentItems.filter(i => !i.winningBidId);
  const btn = document.getElementById('add-unquoted-confirm-btn');
  btn.disabled = true;

  let added = 0;
  let failed = 0;
  for (const item of noWinnerItems) {
    try {
      await api('POST', `/quotations/${targetId}/items`, { productId: item.productId, quantity: item.quantity });
      added++;
    } catch (e) {
      failed++;
    }
  }

  if (failed) {
    toast(`${added} adicionado${added !== 1 ? 's' : ''}, ${failed} falhou/falharam (já deve existir nesse rascunho).`, true);
  } else {
    toast(`${added} produto${added !== 1 ? 's' : ''} adicionado${added !== 1 ? 's' : ''} à cotação escolhida.`);
  }
  closeModal2();
}

// Revisão em modal separado (segunda camada), sob demanda — só busca lances quando o
// admin realmente clica no botão, e só monta a lista de UM representante por vez, escolhido
// no dropdown. Com cotações grandes (centenas de itens, várias dezenas de fornecedores),
// montar tudo de uma vez travaria a tela — assim o custo fica limitado a quem está selecionado.
