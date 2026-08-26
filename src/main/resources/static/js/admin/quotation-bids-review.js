// ============================================================
// REVISÃO DE LANCES
// ============================================================
let qdReviewBidGroups = new Map();

// Selo de status por representante — mesma paleta usada no resto do sistema pra cada
// situação (verde=feito, âmbar=atenção/pendente, cinza=neutro/declinado).
function repResponseStatusBadge(status) {
  if (status === 'SUBMITTED') return '<span class="badge badge-available">Enviou lance</span>';
  if (status === 'DECLINED') return '<span class="badge badge-draft">Não cotar</span>';
  return '<span class="badge badge-reviewing">Pendente</span>';
}

// Estado da busca/paginação do modal — vive fora da função porque cada digitação e cada
// clique de página chamam loadRepStatusPage() de novo, sem reabrir o modal inteiro.
let repStatusSearch = '';
let repStatusPage = 0;
const REP_STATUS_PAGE_SIZE = 10;
let repStatusSearchTimer = null;

async function openRepresentativeStatusModal() {
  repStatusSearch = '';
  repStatusPage = 0;
  openModal2(`
    <div class="rep-status-modal">
    <h2>Quem já respondeu</h2>
    <div class="subtitle" id="rep-status-summary" style="margin-bottom:10px">Carregando…</div>
    <input type="text" id="rep-status-search" placeholder="Buscar por representante ou fornecedor..."
      oninput="onRepStatusSearchInput()" style="margin-bottom:9px">
    <div id="rep-status-body">Carregando…</div>
    ${paginationControlsHtml('rep-status')}
    <div class="btn-row" style="margin-top:12px">
      <button class="secondary" onclick="closeModal2()">Fechar</button>
    </div>
    </div>
  `, 'medium');
  await loadRepStatusPage();
}

// Debounce de 350ms — busca já dispara requisição pro backend a cada letra digitada
// (é ele quem filtra agora, não o front), então esperar a pessoa parar de digitar evita
// uma chamada de API por tecla.
function onRepStatusSearchInput() {
  repStatusSearch = document.getElementById('rep-status-search').value;
  repStatusPage = 0;
  clearTimeout(repStatusSearchTimer);
  repStatusSearchTimer = setTimeout(loadRepStatusPage, 350);
}

function goToRepStatusPage(page) {
  repStatusPage = page;
  loadRepStatusPage();
}

async function loadRepStatusPage() {
  const query = `?search=${encodeURIComponent(repStatusSearch)}&page=${repStatusPage}&size=${REP_STATUS_PAGE_SIZE}`;
  const result = await safeCall(() => api('GET', `/quotations/${currentQuotationId}/representative-status${query}`));

  const summaryEl = document.getElementById('rep-status-summary');
  const pendingCount = result.totalGroupSize - result.respondedCount;
  summaryEl.textContent = result.totalGroupSize
    ? `${result.respondedCount} de ${result.totalGroupSize} representante${result.totalGroupSize > 1 ? 's' : ''} do grupo já responderam${pendingCount ? ` — ${pendingCount} ainda ${pendingCount > 1 ? 'faltam' : 'falta'}` : ''}.`
    : 'Essa cotação não tem grupo de fornecedores definido, ou nenhum fornecedor do grupo tem representante vinculado.';

  const bodyEl = document.getElementById('rep-status-body');
  if (!result.totalGroupSize) {
    bodyEl.innerHTML = '';
    return;
  }
  if (!result.content.length) {
    bodyEl.innerHTML = '<div class="subtitle" style="padding:18px 0">Nenhum representante ou fornecedor encontrado com esse termo.</div>';
    updatePaginationControls('rep-status', 0, 1, 0, goToRepStatusPage);
    return;
  }

  const rowsHtml = result.content.map(r => `
    <tr>
      <td class="truncate-cell" title="${escapeHtml(r.representativeName)}">${escapeHtml(r.representativeName)}</td>
      <td class="truncate-cell" title="${escapeHtml(r.supplierName)}">${escapeHtml(r.supplierName)}</td>
      <td>${repResponseStatusBadge(r.status)}</td>
      <td>${r.status === 'SUBMITTED'
        ? `<button class="secondary small" onclick="viewRepresentativeBids(${r.representativeId}, ${r.supplierId}, '${escapeHtml(r.representativeName).replace(/'/g, "\\'")}', '${escapeHtml(r.supplierName).replace(/'/g, "\\'")}')">Ver</button>`
        : ''}</td>
    </tr>`).join('');

  bodyEl.innerHTML = `
    <div class="scroll-box">
      <table>
        <thead><tr><th>Representante</th><th>Fornecedor</th><th>Status</th><th></th></tr></thead>
        <tbody>${rowsHtml}</tbody>
      </table>
    </div>`;
  updatePaginationControls('rep-status', result.page, result.totalPages, result.totalElements, goToRepStatusPage);
}

// Reaproveita GET /bids/by-quotation/{id} (1 chamada só, pra cotação inteira — ver
// BidService.findByQuotation) — cruza com os itens da cotação e filtra pela dupla
// (representante + fornecedor), não só pelo representante. Precisa ser assim porque um
// representante pode estar vinculado a mais de um fornecedor do grupo — filtrar só por
// representante misturava lance de um fornecedor com o outro (era exatamente o bug: cotar por UM aparecia duplicado nos
// dois). É só leitura (sem editar preço/quantidade nem marcar vencedor), diferente do
// modal de Revisar Lances.
let vrbEntries = [];
let vrbPage = 0;
let vrbRepName = '';
let vrbSupplierName = '';
let vrbMinValueByItem = new Map();
const VRB_PAGE_SIZE = 10;

async function viewRepresentativeBids(representativeId, supplierId, representativeName, supplierName) {
  const bids = await safeCall(() => api('GET', `/bids/by-quotation/${currentQuotationId}`));
  const itemsById = new Map(qdCurrentItems.map(item => [item.id, item]));

  // Menor valor JÁ RECEBIDO por item, entre TODOS os fornecedores que já responderam —
  // não é o vencedor oficial (que só existe depois de "Fechar"), é "quem está na frente
  // agora, se a cotação fechasse nesse instante". Por isso calculado aqui, em cima de
  // TODOS os lances da cotação, antes de filtrar pra só os desse representante/fornecedor.
  vrbMinValueByItem = new Map();
  bids.forEach(bid => {
    const current = vrbMinValueByItem.get(bid.quotationItemId);
    if (current === undefined || bid.value < current) {
      vrbMinValueByItem.set(bid.quotationItemId, bid.value);
    }
  });

  vrbEntries = [];
  bids.forEach(bid => {
    if (bid.submittedById === representativeId && bid.supplierId === supplierId) {
      const item = itemsById.get(bid.quotationItemId);
      if (item) vrbEntries.push({ item, bid });
    }
  });
  vrbPage = 0;
  vrbRepName = representativeName;
  vrbSupplierName = supplierName;

  openModal2(`
    <h2>Itens enviados por ${escapeHtml(representativeName)}</h2>
    <div class="subtitle" style="margin-bottom:14px" id="vrb-summary"></div>
    <div class="scroll-box" style="min-height:320px">
      <table><thead><tr><th>Cód. de Barras</th><th>Produto</th><th style="text-align:center">Qtd.</th><th style="text-align:right">Preço (R$)</th></tr></thead>
        <tbody id="vrb-tbody"></tbody></table>
    </div>
    <div class="btn-row" id="vrb-pagination" style="justify-content:space-between; margin-top:10px; display:none">
      <div style="color:var(--text-dim); font-size:12px" id="vrb-page-info">—</div>
      <div class="btn-row">
        <button class="secondary small" onclick="changeVrbPage(-1)" id="vrb-prev-btn">← Anterior</button>
        <button class="secondary small" onclick="changeVrbPage(1)" id="vrb-next-btn">Próxima →</button>
      </div>
    </div>
    <div class="btn-row" style="margin-top:16px; justify-content:space-between">
      <button class="secondary" onclick="openRepresentativeStatusModal()">← Voltar</button>
      <button class="secondary" onclick="closeModal2()">Fechar</button>
    </div>
  `, 'medium');

  renderVrbPage();
}

function renderVrbPage() {
  const totalPages = Math.max(Math.ceil(vrbEntries.length / VRB_PAGE_SIZE), 1);
  if (vrbPage >= totalPages) vrbPage = totalPages - 1;
  if (vrbPage < 0) vrbPage = 0;
  const pageStart = vrbPage * VRB_PAGE_SIZE;
  const pageEntries = vrbEntries.slice(pageStart, pageStart + VRB_PAGE_SIZE);

  document.getElementById('vrb-summary').textContent =
    `Como ${vrbSupplierName} — ${vrbEntries.length} ${vrbEntries.length === 1 ? 'item cotado' : 'itens cotados'} nessa cotação. "Menor preço" indica quem está na frente agora, entre quem já respondeu — não é o vencedor oficial (isso só é calculado ao Fechar a cotação).`;

  const rowsHtml = pageEntries.map(({ item, bid }) => {
    const isCurrentLowest = vrbMinValueByItem.get(item.id) === bid.value;
    const tag = isCurrentLowest
      ? '<span style="color:var(--success); font-weight:600; font-size:11.5px; margin-left:8px">menor preço</span>'
      : '';
    return `
    <tr>
      <td class="mono">${item.productBarcode}</td>
      <td>${escapeHtml(item.productName)}${tag}</td>
      <td style="text-align:center">${item.quantity}</td>
      <td style="text-align:right">R$ ${formatCurrencyFromNumber(bid.value)}</td>
    </tr>`;
  }).join('');
  document.getElementById('vrb-tbody').innerHTML = rowsHtml || '<tr><td colspan="4" class="empty">Nenhum item encontrado.</td></tr>';

  const paginationEl = document.getElementById('vrb-pagination');
  paginationEl.style.display = vrbEntries.length > VRB_PAGE_SIZE ? 'flex' : 'none';
  document.getElementById('vrb-page-info').textContent = `Página ${vrbPage + 1} de ${totalPages}`;
  document.getElementById('vrb-prev-btn').disabled = vrbPage === 0;
  document.getElementById('vrb-next-btn').disabled = vrbPage >= totalPages - 1;
}

function changeVrbPage(delta) {
  vrbPage += delta;
  renderVrbPage();
}

// Busca os lances de todos os itens ANTES de montar o modal — antes, o formulário
// inteiro aparecia vazio (select com "Carregando...", tabela em branco) e só se
// preenchia depois que loadReviewBidsData() terminava. As outras 3 chamadas de
// loadReviewBidsData() no arquivo (excludeSupplier, acceptViolation, addProductToRep)
// continuam usando a função original — ali o modal já está aberto e cheio de conteúdo
// de verdade, é só uma atualização depois de uma ação, não a abertura inicial.
// Cache de fornecedores carregado uma vez por abertura do modal (openReviewBidsModal) e
// reaproveitado nas atualizações parciais (loadReviewBidsData) — usado só pra achar o
// pedido mínimo do fornecedor de cada representante, exibido ao lado do total.
let qdReviewSuppliersByRepId = new Map();

async function openReviewBidsModal() {
  openModal2('<div style="padding:60px 20px; text-align:center; color:var(--text-dim)">Carregando lances enviados…</div>', true);
  reviewBidsPage = 0;

  const [allBids, suppliers] = await Promise.all([
    safeCall(() => api('GET', `/bids/by-quotation/${currentQuotationId}`)),
    safeCall(() => api('GET', '/suppliers'))
  ]);
  qdReviewSuppliersByRepId = new Map(suppliers.filter(s => s.representativeId).map(s => [s.representativeId, s]));

  const itemsById = new Map(qdCurrentItems.map(item => [item.id, item]));
  qdReviewBidGroups = new Map();
  allBids.forEach(bid => {
    const item = itemsById.get(bid.quotationItemId);
    if (!item) return;
    if (!qdReviewBidGroups.has(bid.submittedById)) {
      qdReviewBidGroups.set(bid.submittedById, { name: bid.submittedByName, supplierName: bid.supplierName, entries: [] });
    }
    qdReviewBidGroups.get(bid.submittedById).entries.push({ item, bid });
  });

  const winningReps = Array.from(qdReviewBidGroups.entries())
    .filter(([, group]) => group.entries.some(({ item, bid }) => item.winningBidId === bid.id));

  openModal2(`
    <h2 style="margin-bottom:4px">Revisar Lances Enviados</h2>
    <div class="subtitle" style="margin-bottom:10px">Escolha um fornecedor vencedor pra ver os produtos que ele ganhou.</div>

    <div style="display:flex; justify-content:space-between; align-items:end; flex-wrap:wrap; gap:16px; margin-bottom:10px">
      <div style="max-width:320px; flex:1; min-width:200px">
        <label style="margin-bottom:3px">Fornecedor</label>
        <select id="review-bids-rep-select" onchange="onReviewRepChange()">${winningReps.length
          ? winningReps.map(([repId, group]) => `<option value="${repId}">${escapeHtml(group.supplierName || group.name)}</option>`).join('')
          : '<option value="">Nenhum representante venceu itens</option>'}</select>
      </div>
      <div style="display:flex; gap:18px; text-align:right">
        <div><div style="font-size:10.5px; color:var(--text-dim); text-transform:uppercase; letter-spacing:0.03em">Qtd. de itens</div><div id="review-rep-count" style="font-weight:700; font-size:14px">—</div></div>
        <div><div style="font-size:10.5px; color:var(--text-dim); text-transform:uppercase; letter-spacing:0.03em">Pedido Mínimo</div><div id="review-rep-minorder" style="font-weight:700; font-size:14px">—</div></div>
        <div><div style="font-size:10.5px; color:var(--text-dim); text-transform:uppercase; letter-spacing:0.03em">Total da Cotação</div><div id="review-total-geral" style="font-weight:700; font-size:14px; color:var(--success)">—</div></div>
      </div>
    </div>

    <table class="qd-items-table"><thead><tr>
      <th>Produto</th><th style="text-align:center">Quantidade</th><th style="text-align:center">Preço (R$)</th><th style="text-align:center">Subtotal</th><th></th>
    </tr></thead><tbody id="review-bids-list"></tbody></table>
    <div class="btn-row" id="review-bids-pagination" style="display:none; justify-content:space-between; margin-top:6px">
      <div style="color:var(--text-dim); font-size:12px" id="review-bids-page-info">—</div>
      <div class="btn-row">
        <button class="secondary small" onclick="changeReviewBidsPage(-1)" id="review-bids-prev-btn">← Anterior</button>
        <button class="secondary small" onclick="changeReviewBidsPage(1)" id="review-bids-next-btn">Próxima →</button>
      </div>
    </div>

    <hr class="divider" style="margin:10px 0">
    <h3 style="margin-bottom:6px">Adicionar produto a esse representante</h3>
    <div id="review-add-product-panel"></div>

    <div class="btn-row" style="margin-top:10px; justify-content:space-between">
      <button class="secondary" onclick="closeModal2()">Fechar</button>
      <button onclick="saveAllRepBidChanges()">Salvar Alterações</button>
    </div>
  `, true);

  if (!winningReps.length) {
    document.getElementById('review-bids-list').innerHTML = '<tr><td colspan="5" class="empty">Sem vencedores calculados no momento.</td></tr>';
    return;
  }
  renderSelectedRepBids();
}

async function loadReviewBidsData() {
  const allBids = await safeCall(() => api('GET', `/bids/by-quotation/${currentQuotationId}`));
  const itemsById = new Map(qdCurrentItems.map(item => [item.id, item]));

  qdReviewBidGroups = new Map();
  allBids.forEach(bid => {
    const item = itemsById.get(bid.quotationItemId);
    if (!item) return;
    if (!qdReviewBidGroups.has(bid.submittedById)) {
      qdReviewBidGroups.set(bid.submittedById, { name: bid.submittedByName, supplierName: bid.supplierName, entries: [] });
    }
    qdReviewBidGroups.get(bid.submittedById).entries.push({ item, bid });
  });

  const winningReps = Array.from(qdReviewBidGroups.entries())
    .filter(([, group]) => group.entries.some(({ item, bid }) => item.winningBidId === bid.id));

  const select = document.getElementById('review-bids-rep-select');
  const list = document.getElementById('review-bids-list');
  if (!select || !list) return;

  if (!winningReps.length) {
    select.innerHTML = '<option value="">Nenhum representante venceu itens</option>';
    list.innerHTML = '<div class="empty">Sem vencedores calculados no momento.</div>';
    document.getElementById('review-add-product-panel').innerHTML = '';
    return;
  }

  select.innerHTML = winningReps.map(([repId, group]) => `<option value="${repId}">${escapeHtml(group.supplierName || group.name)}</option>`).join('');
  renderSelectedRepBids();
}

let reviewBidsPage = 0;
const REVIEW_BIDS_PAGE_SIZE = 10;

function onReviewRepChange() {
  reviewBidsPage = 0;
  renderSelectedRepBids();
}

// Mostra só os produtos que esse representante VENCEU — os que ele só ofertou mas
// perdeu não aparecem aqui (essa tela é sobre o que ele vai receber, não tudo que cotou).
// Paginado de 15 em 15, mesmo raciocínio da tabela de itens: cotação grande não pode
// travar a tela mostrando tudo de uma vez.
function renderSelectedRepBids() {
  const select = document.getElementById('review-bids-rep-select');
  const list = document.getElementById('review-bids-list');
  const pag = document.getElementById('review-bids-pagination');
  if (!select || !list) return;

  const repId = parseInt(select.value);
  const group = qdReviewBidGroups.get(repId);
  if (!group) { list.innerHTML = ''; pag.style.display = 'none'; return; }

  const wonEntries = group.entries.filter(({ item, bid }) => item.winningBidId === bid.id);

  // "Total da Cotação" aqui é sempre por representante — quanto ESSE representante vai
  // receber pelo que ele ganhou, não a soma de todos juntos. Recalcula a cada troca no dropdown.
  const countEl = document.getElementById('review-rep-count');
  const totalEl = document.getElementById('review-total-geral');
  const minOrderEl = document.getElementById('review-rep-minorder');
  if (countEl) countEl.textContent = wonEntries.length;
  if (totalEl) {
    const repTotal = wonEntries.reduce((sum, { item, bid }) => sum + bid.value * item.quantity, 0);
    totalEl.textContent = 'R$ ' + formatCurrencyFromNumber(repTotal);
  }
  if (minOrderEl) {
    const supplier = qdReviewSuppliersByRepId.get(repId);
    minOrderEl.textContent = (supplier && supplier.minimumOrderValue != null)
      ? 'R$ ' + formatCurrencyFromNumber(supplier.minimumOrderValue)
      : '—';
  }

  if (!wonEntries.length) {
    list.innerHTML = '<tr><td colspan="5" class="empty">Esse representante ainda não venceu nenhum produto.</td></tr>';
    pag.style.display = 'none';
    renderAddProductPanel(repId);
    return;
  }

  const totalPages = Math.max(Math.ceil(wonEntries.length / REVIEW_BIDS_PAGE_SIZE), 1);
  if (reviewBidsPage >= totalPages) reviewBidsPage = totalPages - 1;
  if (reviewBidsPage < 0) reviewBidsPage = 0;
  const pageEntries = wonEntries.slice(reviewBidsPage * REVIEW_BIDS_PAGE_SIZE, (reviewBidsPage + 1) * REVIEW_BIDS_PAGE_SIZE);

  list.innerHTML = pageEntries.map(({ item, bid }) => `
      <tr class="rep-bid-winner-row">
        <td>${escapeHtml(item.productName)} <span class="mono" style="font-size:11px; color:var(--text-dim)">${item.productBarcode}</span></td>
        <td style="text-align:center"><input type="number" step="0.001" id="rebid-qty-${item.id}" value="${item.quantity}" oninput="updateRebidSubtotal(${bid.id}, ${item.id})" style="width:70px; text-align:center"></td>
        <td style="text-align:center"><input type="text" inputmode="decimal" id="rebid-value-${bid.id}" value="${formatCurrencyFromNumber(bid.value)}" oninput="this.value = maskCurrencyInput(this.value); updateRebidSubtotal(${bid.id}, ${item.id})" style="width:75px; text-align:center"></td>
        <td style="text-align:center" id="rebid-subtotal-${bid.id}">R$ ${formatCurrencyFromNumber(bid.value * item.quantity)}</td>
        <td style="text-align:center"><button class="icon-btn danger" onclick="deleteBidAsAdmin(${bid.id}, this, '${escapeHtml(item.productName).replace(/'/g, "\\'")}')" title="Excluir lance">${QD_TRASH_ICON}</button></td>
      </tr>`).join('');

  pag.style.display = 'flex';
  document.getElementById('review-bids-page-info').textContent = `Página ${reviewBidsPage + 1} de ${totalPages}`;
  document.getElementById('review-bids-prev-btn').disabled = reviewBidsPage === 0;
  document.getElementById('review-bids-next-btn').disabled = reviewBidsPage >= totalPages - 1;

  renderAddProductPanel(repId);
}

function changeReviewBidsPage(delta) {
  reviewBidsPage += delta;
  renderSelectedRepBids();
}

function updateRebidSubtotal(bidId, itemId) {
  const priceInput = document.getElementById(`rebid-value-${bidId}`);
  const qtyInput = document.getElementById(`rebid-qty-${itemId}`);
  const subtotalEl = document.getElementById(`rebid-subtotal-${bidId}`);
  if (!priceInput || !qtyInput || !subtotalEl) return;
  const price = unmaskCurrencyToNumber(priceInput.value) || 0;
  const qty = parseFloat(qtyInput.value) || 0;
  subtotalEl.textContent = 'R$ ' + formatCurrencyFromNumber(price * qty);
  recalculateRepTotalFromScreen();
}

// Soma os subtotais de TODAS as linhas do representante atual (não só a página visível) —
// usa preço e quantidade já digitados (ainda não salvos) pras linhas visíveis, e o valor
// salvo pras demais.
function recalculateRepTotalFromScreen() {
  const totalEl = document.getElementById('review-total-geral');
  const select = document.getElementById('review-bids-rep-select');
  if (!totalEl || !select) return;

  const repId = parseInt(select.value);
  const group = qdReviewBidGroups.get(repId);
  if (!group) return;

  const wonEntries = group.entries.filter(({ item, bid }) => item.winningBidId === bid.id);
  const total = wonEntries.reduce((sum, { item, bid }) => {
    const priceInput = document.getElementById(`rebid-value-${bid.id}`);
    const qtyInput = document.getElementById(`rebid-qty-${item.id}`);
    const price = priceInput ? (unmaskCurrencyToNumber(priceInput.value) ?? bid.value) : bid.value;
    const qty = qtyInput ? (parseFloat(qtyInput.value) || 0) : item.quantity;
    return sum + price * qty;
  }, 0);
  totalEl.textContent = 'R$ ' + formatCurrencyFromNumber(total);
}

// Busca por texto entre TODOS os produtos do sistema — não só os que já estavam nessa
// cotação. Se o produto escolhido já é item da cotação, quantidade fica travada (usa a
// que já existe); se é produto novo pra essa cotação, quantidade fica livre pra digitar.
async function renderAddProductPanel(repId) {
  const wrap = document.getElementById('review-add-product-panel');
  wrap.innerHTML = `
    <div class="inline-form">
      <div style="flex:1">
        <label>Produto (nome ou código de barras)</label>
        <input id="review-add-product-search" placeholder="Digite para buscar..." autocomplete="off" oninput="searchReviewAddProduct()">
        <input type="hidden" id="review-add-product-id">
      </div>
      <div style="width:90px"><label>Quantidade</label><input type="number" step="0.001" id="review-add-product-qty" placeholder="se novo"></div>
      <div style="width:100px"><label>Preço (R$)</label><input type="text" inputmode="decimal" id="review-add-product-price" placeholder="0,00" oninput="this.value = maskCurrencyInput(this.value)"></div>
      <button class="secondary small" id="review-add-product-btn" onclick="addProductToRep(${repId})">+ Adicionar</button>
    </div>
    <div id="review-add-product-hint" style="margin-top:6px; font-size:12px; color:var(--text-dim)"></div>
    <div id="review-add-product-search-results"></div>`;

  await loadQdProductsCache();
}

function searchReviewAddProduct() {
  const term = document.getElementById('review-add-product-search').value.trim().toLowerCase();
  document.getElementById('review-add-product-id').value = '';
  document.getElementById('review-add-product-hint').textContent = '';
  resetReviewAddProductFields();
  const wrap = document.getElementById('review-add-product-search-results');
  if (!term) { wrap.innerHTML = ''; return; }
  const matches = qdProductsCache.filter(p =>
    p.name.toLowerCase().includes(term) || (p.barcode || '').includes(term)
  ).slice(0, 8);
  wrap.innerHTML = matches.length
    ? matches.map(p => {
        const alreadyIn = qdCurrentItems.some(item => item.productId === p.id);
        return `<div class="search-result-item" onclick="selectReviewAddProduct(${p.id})">
          <strong>${escapeHtml(p.name)}</strong> <span class="mono" style="color:var(--text-dim); font-size:12px">(${escapeHtml(p.barcode)})</span>${
            alreadyIn ? ' <span style="color:var(--success); font-weight:600; font-size:12px">Este produto já está na cotação!</span>' : ''
          }
        </div>`;
      }).join('')
    : '<div class="empty">Nenhum resultado.</div>';
}

function resetReviewAddProductFields() {
  const qtyInput = document.getElementById('review-add-product-qty');
  const priceInput = document.getElementById('review-add-product-price');
  const addBtn = document.getElementById('review-add-product-btn');
  if (qtyInput) { qtyInput.value = ''; qtyInput.disabled = false; }
  if (priceInput) { priceInput.value = ''; priceInput.disabled = false; }
  if (addBtn) addBtn.disabled = false;
}

// Produto já cadastrado na cotação (de qualquer representante) não pode ser "adicionado"
// de novo por aqui — bloqueia quantidade, preço e o próprio botão, e mostra só o aviso.
// Esse painel serve exclusivamente pra produto novo na cotação.
function selectReviewAddProduct(productId) {
  const p = qdProductsCache.find(x => x.id === productId);
  if (!p) return;
  document.getElementById('review-add-product-id').value = p.id;
  document.getElementById('review-add-product-search').value = `${p.name} (${p.barcode})`;
  document.getElementById('review-add-product-search-results').innerHTML = '';

  const existing = qdCurrentItems.find(item => item.productId === p.id);
  const hint = document.getElementById('review-add-product-hint');
  resetReviewAddProductFields();
  if (existing) {
    document.getElementById('review-add-product-qty').disabled = true;
    document.getElementById('review-add-product-price').disabled = true;
    document.getElementById('review-add-product-btn').disabled = true;
    hint.innerHTML = '<span style="color:var(--success); font-weight:600">Esse produto já está na cotação!</span>';
  } else {
    hint.textContent = 'Produto novo pra essa cotação — informe a quantidade e o preço.';
  }
}

// Painel serve só pra produto NOVO na cotação (produto já existente fica travado em
// selectReviewAddProduct). Reconfere direto do backend antes de enviar, com a mesma
// checagem — evita adicionar um produto que virou item da cotação depois da busca
// (outra aba, outro admin mexendo ao mesmo tempo).
async function addProductToRep(repId) {
  const fieldMap = {
    productId: 'review-add-product-search',
    quantity: 'review-add-product-qty',
    value: 'review-add-product-price'
  };
  Object.values(fieldMap).forEach(clearFieldError);

  const productId = document.getElementById('review-add-product-id').value;
  const qty = document.getElementById('review-add-product-qty').value;
  const price = unmaskCurrencyToNumber(document.getElementById('review-add-product-price').value);
  if (!productId) { showFieldError('review-add-product-search', 'Busque e selecione um produto.'); return; }
  if (!qty) { showFieldError('review-add-product-qty', 'Informe a quantidade.'); return; }
  if (!price) { showFieldError('review-add-product-price', 'Informe o preço.'); return; }

  const suppliers = await safeCall(() => api('GET', '/suppliers'));
  const supplier = suppliers.find(s => s.representativeId === repId);
  if (!supplier) { toast('Não encontrei o fornecedor desse representante.', true); return; }

  qdCurrentItems = await safeCall(() => api('GET', `/quotations/${currentQuotationId}/items`));
  if (qdCurrentItems.some(item => item.productId === parseInt(productId))) {
    toast('Esse produto já está na cotação — não é possível adicionar de novo.', true);
    return;
  }

  try {
    await api('POST', `/quotations/${currentQuotationId}/items/add-with-winner`, {
      productId: parseInt(productId),
      quantity: parseFloat(qty),
      supplierId: supplier.id,
      representativeId: repId,
      value: price
    });
  } catch (e) {
    distributeFieldErrors(e.message, fieldMap);
    return;
  }

  toast('Produto adicionado ao pedido desse representante.');
  qdCurrentItems = await safeCall(() => api('GET', `/quotations/${currentQuotationId}/items`));
  await loadReviewBidsData();
  document.getElementById('review-bids-rep-select').value = repId;
  renderSelectedRepBids();
}

// Um botão só, salva preço E quantidade de todos os produtos vencidos exibidos na tela —
// Um botão só, salva preço E quantidade de todos os produtos vencidos exibidos na tela —
// mesmo padrão do "Salvar quantidades" da tabela de itens, em vez de um Salvar por linha.
// Tudo numa chamada só (/review-batch-update): evita a corrida em que a primeira edição
// derruba o status da cotação (Revisão → Disponível) no meio de um salvamento com várias
// mudanças, fazendo as seguintes falharem com "só é possível editar em Rascunho/Revisão".
// Prazo não aparece mais nessa tela, mas o backend exige o campo — reaproveita o valor
// que já estava salvo pra cada lance, buscando nos dados já carregados em memória.
async function saveAllRepBidChanges() {
  const priceInputs = document.querySelectorAll('[id^="rebid-value-"]');
  const qtyInputs = document.querySelectorAll('[id^="rebid-qty-"]');
  if (!priceInputs.length && !qtyInputs.length) { toast('Nada pra salvar.', true); return; }

  const repId = parseInt(document.getElementById('review-bids-rep-select').value);
  const group = qdReviewBidGroups.get(repId);

  const bidUpdates = Array.from(priceInputs).map(input => {
    const bidId = parseInt(input.id.replace('rebid-value-', ''));
    const value = unmaskCurrencyToNumber(input.value);
    const entry = group.entries.find(({ bid }) => bid.id === bidId);
    const deliveryDeadlineDays = entry ? entry.bid.deliveryDeadlineDays : null;
    return { bidId, value, deliveryDeadlineDays };
  });

  const itemUpdates = Array.from(qtyInputs).map(input => {
    const itemId = parseInt(input.id.replace('rebid-qty-', ''));
    const quantity = parseFloat(input.value);
    return { itemId, quantity };
  });

  await safeCall(() => api('POST', `/quotations/${currentQuotationId}/review-batch-update`, { bidUpdates, itemUpdates }));
  toast('Alterações salvas.');

  // abrirDetalheCotacao só mexe no modal1 (fica por trás) — modal2 (esse aqui) continua
  // aberto. Ela também atualiza qdCurrentItems, que é de onde loadReviewBidsData tira as
  // quantidades — sem isso, a tela continuaria mostrando o valor antigo até fechar e reabrir.
  await abrirDetalheCotacao(currentQuotationId);
  await loadReviewBidsData();
  document.getElementById('review-bids-rep-select').value = repId;
  reviewBidsPage = 0;
  renderSelectedRepBids();
  loadQuotations();
}

function deleteBidAsAdmin(bidId, buttonEl, productName) {
  showBidDeleteChoicePopover(
    buttonEl,
    `Excluir o lance de <strong>${escapeHtml(productName)}</strong>?`,
    () => confirmDeleteBidAsAdmin(bidId, false),
    () => confirmDeleteBidAsAdmin(bidId, true)
  );
}

// Variante de showConfirmPopover (quotation-publish-close.js) com 3 escolhas em vez de
// 2 — só usada aqui, na exclusão de lance em "Revisar Lances Enviados". Reaproveita a
// mesma classe/posicionamento/clique-fora do popover genérico (closeConfirmPopover e
// handleOutsideConfirmPopoverClick funcionam sem alteração, já que ambos procuram pelo
// id "confirm-popover"), só o corpo com os botões muda.
function showBidDeleteChoicePopover(anchorEl, messageHtml, onDeleteOnly, onReassign) {
  closeConfirmPopover();

  const pop = document.createElement('div');
  pop.className = 'confirm-popover';
  pop.id = 'confirm-popover';
  pop.innerHTML = `
    <div class="confirm-popover-msg">${messageHtml}</div>
    <div class="btn-row" style="flex-direction:column; align-items:stretch; margin-top:10px">
      <button class="danger small" type="button" data-action="reassign">Excluir e atribuir ao 2º menor preço</button>
      <button class="danger small" type="button" data-action="delete-only">Excluir — item fica sem vencedor</button>
      <button class="secondary small" type="button" data-action="cancel">Cancelar</button>
    </div>`;
  document.body.appendChild(pop);

  const cancelBtn = pop.querySelector('[data-action="cancel"]');
  const deleteOnlyBtn = pop.querySelector('[data-action="delete-only"]');
  const reassignBtn = pop.querySelector('[data-action="reassign"]');

  cancelBtn.onclick = closeConfirmPopover;
  deleteOnlyBtn.onclick = () => withButtonLoading(deleteOnlyBtn, 'Aguarde...', async () => {
    cancelBtn.disabled = true;
    reassignBtn.disabled = true;
    try { await onDeleteOnly(); } finally { closeConfirmPopover(); }
  });
  reassignBtn.onclick = () => withButtonLoading(reassignBtn, 'Aguarde...', async () => {
    cancelBtn.disabled = true;
    deleteOnlyBtn.disabled = true;
    try { await onReassign(); } finally { closeConfirmPopover(); }
  });

  const anchor = anchorEl.getBoundingClientRect();
  const popRect = pop.getBoundingClientRect();
  let top = anchor.bottom + 6;
  let left = anchor.right - popRect.width;
  if (top + popRect.height > window.innerHeight - 8) top = anchor.top - popRect.height - 6;
  if (left < 8) left = anchor.left;
  if (left + popRect.width > window.innerWidth - 8) left = window.innerWidth - popRect.width - 8;
  pop.style.top = Math.max(8, top) + 'px';
  pop.style.left = Math.max(8, left) + 'px';

  setTimeout(() => document.addEventListener('mousedown', handleOutsideConfirmPopoverClick), 0);
}

async function confirmDeleteBidAsAdmin(bidId, reassignToRunnerUp) {
  const repId = document.getElementById('review-bids-rep-select').value;
  await safeCall(() => api('DELETE', `/bids/${bidId}?reassignToRunnerUp=${reassignToRunnerUp}`));
  toast(reassignToRunnerUp
    ? 'Lance excluído — reatribuído ao 2º menor preço, se havia outro lance pra esse item.'
    : 'Lance excluído.');

  await abrirDetalheCotacao(currentQuotationId);
  await loadReviewBidsData();
  document.getElementById('review-bids-rep-select').value = repId;
  reviewBidsPage = 0;
  renderSelectedRepBids();
  loadQuotations();
}

