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

async function openRepresentativeStatusModal() {
  const rows = await safeCall(() => api('GET', `/quotations/${currentQuotationId}/representative-status`));

  const rowsHtml = rows.length
    ? rows.map(r => `
        <tr>
          <td>${escapeHtml(r.representativeName)}</td>
          <td>${escapeHtml(r.supplierName)}</td>
          <td>${repResponseStatusBadge(r.status)}</td>
          <td>${r.status === 'SUBMITTED'
            ? `<button class="secondary small" onclick="viewRepresentativeBids(${r.representativeId}, ${r.supplierId}, '${escapeHtml(r.representativeName).replace(/'/g, "\\'")}', '${escapeHtml(r.supplierName).replace(/'/g, "\\'")}')">Ver</button>`
            : ''}</td>
        </tr>`).join('')
    : '';

  const pendingCount = rows.filter(r => r.status === 'PENDING').length;

  openModal2(`
    <h2>Quem já respondeu</h2>
    <div class="subtitle" style="margin-bottom:14px">
      ${rows.length
        ? `${rows.length - pendingCount} de ${rows.length} representante${rows.length > 1 ? 's' : ''} do grupo já responderam${pendingCount ? ` — ${pendingCount} ainda ${pendingCount > 1 ? 'faltam' : 'falta'}` : ''}.`
        : 'Essa cotação não tem grupo de fornecedores definido, ou nenhum fornecedor do grupo tem representante vinculado.'}
    </div>
    ${rows.length ? `
      <div class="scroll-box">
        <table><thead><tr><th>Representante</th><th>Fornecedor</th><th>Status</th><th></th></tr></thead>
          <tbody>${rowsHtml}</tbody></table>
      </div>` : ''}
    <div class="btn-row" style="margin-top:16px">
      <button class="secondary" onclick="closeModal2()">Fechar</button>
    </div>
  `);
}

// Reaproveita GET /bids?quotationItemId=X (já usado na Revisão de Lances) — cruza com
// os itens da cotação e filtra pela dupla (representante + fornecedor), não só pelo
// representante. Precisa ser assim porque um representante pode estar vinculado a mais
// de um fornecedor do grupo — filtrar só por representante misturava lance de um
// fornecedor com o outro (era exatamente o bug: cotar por UM aparecia duplicado nos
// dois). É só leitura (sem editar preço/quantidade nem marcar vencedor), diferente do
// modal de Revisar Lances.
let vrbEntries = [];
let vrbPage = 0;
let vrbRepName = '';
let vrbSupplierName = '';
const VRB_PAGE_SIZE = 10;

async function viewRepresentativeBids(representativeId, supplierId, representativeName, supplierName) {
  const perItem = await Promise.all(qdCurrentItems.map(item =>
    safeCall(() => api('GET', `/bids?quotationItemId=${item.id}`)).then(bids => ({ item, bids }))
  ));

  vrbEntries = [];
  perItem.forEach(({ item, bids }) => {
    const bid = bids.find(b => b.submittedById === representativeId && b.supplierId === supplierId);
    if (bid) vrbEntries.push({ item, bid });
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
    `Como ${vrbSupplierName} — ${vrbEntries.length} ${vrbEntries.length === 1 ? 'item cotado' : 'itens cotados'} nessa cotação.`;

  const rowsHtml = pageEntries.map(({ item, bid }) => `
    <tr>
      <td class="mono">${item.productBarcode}</td>
      <td>${escapeHtml(item.productName)}</td>
      <td style="text-align:center">${item.quantity}</td>
      <td style="text-align:right">R$ ${formatCurrencyFromNumber(bid.value)}</td>
    </tr>`).join('');
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

async function openReviewBidsModal() {
  openModal2(`
    <h2 style="margin-bottom:4px">Revisar Lances Enviados</h2>
    <div class="subtitle" style="margin-bottom:10px">Escolha um representante vencedor pra ver os produtos que ele ganhou.</div>

    <div style="display:flex; justify-content:space-between; align-items:end; flex-wrap:wrap; gap:16px; margin-bottom:10px">
      <div style="max-width:320px; flex:1; min-width:200px">
        <label style="margin-bottom:3px">Representante</label>
        <select id="review-bids-rep-select" onchange="onReviewRepChange()"><option value="">Carregando...</option></select>
      </div>
      <div style="display:flex; gap:18px; text-align:right">
        <div><div style="font-size:10.5px; color:var(--text-dim); text-transform:uppercase; letter-spacing:0.03em">Qtd. de itens</div><div id="review-rep-count" style="font-weight:700; font-size:14px">—</div></div>
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

  reviewBidsPage = 0;
  await loadReviewBidsData();
}

async function loadReviewBidsData() {
  const perItem = await Promise.all(qdCurrentItems.map(item =>
    safeCall(() => api('GET', `/bids?quotationItemId=${item.id}`)).then(bids => ({ item, bids }))
  ));

  qdReviewBidGroups = new Map();
  perItem.forEach(({ item, bids }) => {
    bids.forEach(bid => {
      if (!qdReviewBidGroups.has(bid.submittedById)) {
        qdReviewBidGroups.set(bid.submittedById, { name: bid.submittedByName, entries: [] });
      }
      qdReviewBidGroups.get(bid.submittedById).entries.push({ item, bid });
    });
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

  select.innerHTML = winningReps.map(([repId, group]) => `<option value="${repId}">${escapeHtml(group.name)}</option>`).join('');
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
  if (countEl) countEl.textContent = wonEntries.length;
  if (totalEl) {
    const repTotal = wonEntries.reduce((sum, { item, bid }) => sum + bid.value * item.quantity, 0);
    totalEl.textContent = 'R$ ' + formatCurrencyFromNumber(repTotal);
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
  const productId = document.getElementById('review-add-product-id').value;
  const qty = document.getElementById('review-add-product-qty').value;
  const price = unmaskCurrencyToNumber(document.getElementById('review-add-product-price').value);
  if (!productId) { toast('Busque e selecione um produto.', true); return; }
  if (!qty) { toast('Informe a quantidade.', true); return; }
  if (!price) { toast('Informe o preço.', true); return; }

  const suppliers = await safeCall(() => api('GET', '/suppliers'));
  const supplier = suppliers.find(s => s.representativeId === repId);
  if (!supplier) { toast('Não encontrei o fornecedor desse representante.', true); return; }

  qdCurrentItems = await safeCall(() => api('GET', `/quotations/${currentQuotationId}/items`));
  if (qdCurrentItems.some(item => item.productId === parseInt(productId))) {
    toast('Esse produto já está na cotação — não é possível adicionar de novo.', true);
    return;
  }

  await safeCall(() => api('POST', `/quotations/${currentQuotationId}/items/add-with-winner`, {
    productId: parseInt(productId),
    quantity: parseFloat(qty),
    supplierId: supplier.id,
    representativeId: repId,
    value: price
  }));

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
  showConfirmPopover(
    buttonEl,
    `Excluir o lance de <strong>${escapeHtml(productName)}</strong>? Se ele for o vencedor atual, esse item fica sem vencedor — dá pra reatribuir depois em "Adicionar produto a esse representante", sem afetar os outros itens já calculados.`,
    () => confirmDeleteBidAsAdmin(bidId)
  );
}

async function confirmDeleteBidAsAdmin(bidId) {
  const repId = document.getElementById('review-bids-rep-select').value;
  await safeCall(() => api('DELETE', `/bids/${bidId}`));
  toast('Lance excluído.');

  await abrirDetalheCotacao(currentQuotationId);
  await loadReviewBidsData();
  document.getElementById('review-bids-rep-select').value = repId;
  reviewBidsPage = 0;
  renderSelectedRepBids();
  loadQuotations();
}

