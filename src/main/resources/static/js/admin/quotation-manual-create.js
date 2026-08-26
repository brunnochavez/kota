// ============================================================
// CRIAÇÃO MANUAL DE COTAÇÃO
// ============================================================
// --- manual creation ---
let manualItemCount = 0;
let productsForSelect = [];

// Fornecedores avulsos escolhidos pra nova cotação — mesmo conceito e mesmo picker
// (busca + checkbox + paginação) usado em manageQdExtraSuppliers (edição de cotação
// já existente), só que aqui alimenta o body de createQuotationManually() em vez de
// um PUT de edição. Reaproveita o mesmo cache de fornecedores (qdAllSuppliersCache)
// definido em quotation-detail-core.js — é a mesma lista (GET /suppliers, já filtrada
// pra só quem tem representante), não faz sentido buscar duas vezes.
let mqExtraSupplierIds = [];
let mqExtraSuppliersPage = 0;
let mqExtraSuppliersSearchTerm = '';

// Grupo e Representantes são exclusivos aqui também — mesma regra e mesmo motivo de
// setQdRecipientMode (quotation-detail-core.js), só que pro formulário de criação.
function setMqRecipientMode(mode) {
  document.querySelectorAll('#mq-recipient-mode-tabs .tab').forEach(t => t.classList.toggle('active', t.dataset.mode === mode));
  document.getElementById('mq-recipient-mode-group').style.display = mode === 'grupos' ? 'block' : 'none';
  document.getElementById('mq-recipient-mode-reps').style.display = mode === 'representantes' ? 'block' : 'none';
  if (mode === 'grupos') {
    mqExtraSupplierIds = [];
    const btn = document.getElementById('mq-extra-suppliers-btn');
    if (btn) btn.textContent = 'Selecionar representantes';
  } else {
    document.getElementById('mq-group').value = '';
  }
}

async function manageMqExtraSuppliers() {
  if (!qdAllSuppliersCache.length) {
    qdAllSuppliersCache = (await safeCall(() => api('GET', '/suppliers'))).filter(s => s.representativeId);
  }
  mqExtraSuppliersPage = 0;
  mqExtraSuppliersSearchTerm = '';
  openModal2(`
    <h2>Representantes</h2>
    <div class="subtitle" style="margin-bottom:12px">Adiciona a cotação diretamente pros representantes marcados aqui, sem depender de grupo nenhum.</div>
    <input id="mq-extra-suppliers-search" placeholder="Buscar representante ou empresa..." autocomplete="off" oninput="onMqExtraSuppliersSearch(this.value)" style="margin-bottom:10px">
    <div id="mq-extra-suppliers-list"></div>
    ${paginationControlsHtml('mq-extra-suppliers')}
    <div class="btn-row" style="margin-top:16px; justify-content:space-between">
      <span id="mq-extra-suppliers-selected-count" style="font-size:11.5px; color:var(--text-dim)">${mqExtraSupplierIds.length} selecionado(s)</span>
      <button onclick="closeModal2()">Concluir</button>
    </div>
  `, true);
  renderMqExtraSuppliersList();
}

function onMqExtraSuppliersSearch(term) {
  mqExtraSuppliersSearchTerm = term.trim().toLowerCase();
  mqExtraSuppliersPage = 0;
  renderMqExtraSuppliersList();
}

function renderMqExtraSuppliersList() {
  const filtered = mqExtraSuppliersSearchTerm
    ? qdAllSuppliersCache.filter(s =>
        s.name.toLowerCase().includes(mqExtraSuppliersSearchTerm) ||
        (s.representativeName || '').toLowerCase().includes(mqExtraSuppliersSearchTerm))
    : qdAllSuppliersCache;

  const { items, page, totalPages } = paginateSlice(filtered, mqExtraSuppliersPage, DEFAULT_PAGE_SIZE);
  mqExtraSuppliersPage = page;

  const listEl = document.getElementById('mq-extra-suppliers-list');
  if (listEl) {
    listEl.innerHTML = items.length
      ? items.map(s => `
          <label class="expiring-item" style="cursor:pointer">
            <span><input type="checkbox" value="${s.id}" ${mqExtraSupplierIds.includes(s.id) ? 'checked' : ''} onchange="toggleMqExtraSupplier(${s.id}, this.checked)"> ${escapeHtml(s.representativeName)}</span>
            <span style="color:var(--text-dim); font-size:11px">${escapeHtml(s.name)}</span>
          </label>`).join('')
      : '<div class="empty">Nenhum representante encontrado.</div>';
  }

  updatePaginationControls('mq-extra-suppliers', page, totalPages, filtered.length, (newPage) => {
    mqExtraSuppliersPage = newPage;
    renderMqExtraSuppliersList();
  });
}

function toggleMqExtraSupplier(id, checked) {
  if (checked) {
    if (!mqExtraSupplierIds.includes(id)) mqExtraSupplierIds.push(id);
  } else {
    mqExtraSupplierIds = mqExtraSupplierIds.filter(x => x !== id);
  }
  const btn = document.getElementById('mq-extra-suppliers-btn');
  if (btn) btn.textContent = mqExtraSupplierIds.length ? `Selecionar representantes (${mqExtraSupplierIds.length})` : 'Selecionar representantes';
  const countEl = document.getElementById('mq-extra-suppliers-selected-count');
  if (countEl) countEl.textContent = `${mqExtraSupplierIds.length} selecionado(s)`;
}

async function loadProductsForManualItems() {
  productsForSelect = await safeCall(() => api('GET', '/products'));
}

// Linha de item da Nova Cotação (manual) — reaproveitada tanto por "+ Adicionar item"
// (vazia) quanto por "+ Adicionar Grupo" (já preenchida com o produto do grupo). Campo
// visível é busca por nome/código; o id do produto escolhido fica guardado num input
// escondido, que é o que createQuotationManually() de fato lê — assim não precisei mexer
// nela ao trocar o select pela busca.
function buildMqItemRowHtml(id, prefillProduct) {
  const searchValue = prefillProduct ? `${prefillProduct.name} (${prefillProduct.barcode})` : '';
  const productIdValue = prefillProduct ? prefillProduct.id : '';
  return `
    <div style="position:relative">
      <label>Produto</label>
      <input type="text" id="mq-item-search-${id}" placeholder="Buscar por nome ou código de barras..." autocomplete="off"
        value="${escapeHtml(searchValue)}" oninput="searchMqItemProduct(${id})">
      <input type="hidden" id="mq-item-product-${id}" value="${productIdValue}">
      <div id="mq-item-results-${id}" style="position:absolute; z-index:20; left:0; right:0; top:100%; margin-top:4px"></div>
    </div>
    <div><label>Quantidade</label><input type="number" step="0.001" id="mq-item-qty-${id}"></div>
    <button class="danger small" onclick="document.getElementById('mq-item-${id}').remove()">Remover</button>`;
}

function searchMqItemProduct(id) {
  const term = document.getElementById('mq-item-search-' + id).value.trim().toLowerCase();
  const wrap = document.getElementById('mq-item-results-' + id);
  document.getElementById('mq-item-product-' + id).value = '';

  if (!term) { wrap.innerHTML = ''; return; }

  const matches = productsForSelect
    .filter(p => p.name.toLowerCase().includes(term) || (p.barcode || '').includes(term))
    .slice(0, 8);

  wrap.innerHTML = matches.length
    ? matches.map(p => `<div class="search-result-item" onclick="selectMqItemProduct(${id}, ${p.id})">
        <strong>${escapeHtml(p.name)}</strong> <span class="mono" style="color:var(--text-dim); font-size:12px">(${escapeHtml(p.barcode)})</span>
      </div>`).join('')
    : '<div class="empty">Nenhum produto encontrado.</div>';
}

function selectMqItemProduct(id, productId) {
  const p = productsForSelect.find(prod => prod.id === productId);
  if (!p) return;
  document.getElementById('mq-item-product-' + id).value = productId;
  document.getElementById('mq-item-search-' + id).value = `${p.name} (${p.barcode})`;
  document.getElementById('mq-item-results-' + id).innerHTML = '';
}

function addManualItemRow() {
  manualItemCount++;
  const id = manualItemCount;
  const div = document.createElement('div');
  div.className = 'item-row';
  div.id = 'mq-item-' + id;
  div.innerHTML = buildMqItemRowHtml(id, null);
  document.getElementById('mq-items').appendChild(div);
}

// Adiciona todos os produtos de um grupo de uma vez como linhas de item — mesmo formato
// de linha do "+ Adicionar item" (reaproveita o mesmo select, só já vem com o produto
// certo pré-selecionado), então createQuotationManually() nem precisa saber que essas
// linhas vieram de um grupo. Só a criação da cotação em si dispara a API; adicionar
// linha aqui é só DOM, igual ao botão de item avulso.
async function openAddGroupToManualQuotationModal() {
  if (!productGroupsCache.length) {
    productGroupsCache = await safeCall(() => api('GET', '/product-groups'));
  }
  if (!productGroupsCache.length) {
    toast('Nenhum grupo de produtos cadastrado ainda — crie um em "Grupos de Produtos".', true);
    return;
  }
  openModal2(`
    <h2>Adicionar grupo de produtos</h2>
    <div class="subtitle" style="margin-bottom:14px">Adiciona todos os produtos do grupo como itens — só falta preencher a quantidade de cada um depois.</div>
    <div><label>Grupo</label><select id="mq-add-group-select">
      ${productGroupsCache.map(g => `<option value="${g.id}">${escapeHtml(g.name)}</option>`).join('')}
    </select></div>
    <div class="btn-row" style="margin-top:16px">
      <button onclick="addManualGroupRows()">Adicionar</button>
      <button class="secondary" onclick="closeModal2()">Cancelar</button>
    </div>
  `);
}

async function addManualGroupRows() {
  const groupId = document.getElementById('mq-add-group-select').value;
  if (!groupId) return;

  const groupProducts = await safeCall(() => api('GET', `/products/by-group/${groupId}`));
  if (!groupProducts.length) {
    toast('Esse grupo ainda não tem produtos cadastrados.', true);
    return;
  }

  const existingProductIds = new Set(
    Array.from(document.querySelectorAll('#mq-items [id^="mq-item-product-"]')).map(sel => parseInt(sel.value))
  );

  let added = 0;
  groupProducts.forEach(p => {
    if (existingProductIds.has(p.id)) return;
    manualItemCount++;
    const id = manualItemCount;
    const div = document.createElement('div');
    div.className = 'item-row';
    div.id = 'mq-item-' + id;
    div.innerHTML = buildMqItemRowHtml(id, p);
    document.getElementById('mq-items').appendChild(div);
    added++;
  });

  closeModal2();
  toast(added
    ? `${added} produto(s) do grupo adicionado(s) — falta preencher a quantidade de cada um.`
    : 'Todos os produtos desse grupo já estavam na lista.');
}

// Monta o fieldMap dinamicamente (diferente do fieldMap fixo que saveWithReactivation usa
// nas outras telas) porque aqui o número de campos varia — um por item da lista. Resultado:
// "items[0].quantity" vira uma chave, apontando pro id do input de quantidade daquela linha
// específica. distributeFieldErrors() (o mesmo helper já usado em Produtos/Fornecedores/
// Representantes/Grupos) faz o resto: separa a mensagem do backend por campo e mostra cada
// uma embaixo do input certo.
function buildMqFieldMap(rows) {
  const fieldMap = {
    name: 'mq-name',
    supplierGroupId: 'mq-group',
    expirationDate: 'mq-expiration-date',
    defaultSalesProjectionDays: 'mq-sales-projection'
  };
  Array.from(rows).forEach((row, index) => {
    const id = row.id.replace('mq-item-', '');
    // productId não tem input visível próprio — quem o usuário vê e preenche é a busca
    // por texto, então é nela que a mensagem deve aparecer.
    fieldMap['items[' + index + '].productId'] = 'mq-item-search-' + id;
    fieldMap['items[' + index + '].quantity'] = 'mq-item-qty-' + id;
  });
  return fieldMap;
}

async function createQuotationManually() {
  const rows = document.querySelectorAll('#mq-items .item-row');
  const fieldMap = buildMqFieldMap(rows);
  Object.values(fieldMap).forEach(clearFieldError);

  const name = document.getElementById('mq-name').value.trim();
  if (!name) { showFieldError('mq-name', 'Informe um nome para a cotação.'); return; }
  if (!rows.length) { toast('Adicione pelo menos um item.', true); return; }
  const items = Array.from(rows).map(row => {
    const id = row.id.replace('mq-item-', '');
    return {
      productId: parseInt(document.getElementById('mq-item-product-' + id).value),
      quantity: parseFloat(document.getElementById('mq-item-qty-' + id).value)
    };
  });
  const body = {
    name,
    supplierGroupId: document.getElementById('mq-group').value || null,
    extraSupplierIds: mqExtraSupplierIds,
    expirationDate: getExpirationValue('mq-expiration'),
    defaultSalesProjectionDays: document.getElementById('mq-sales-projection').value || null,
    items
  };

  let q;
  try {
    q = await api('POST', '/quotations', body);
  } catch (e) {
    distributeFieldErrors(e.message, fieldMap);
    return;
  }

  toast('Cotação #' + q.id + ' criada como Rascunho. Você pode publicá-la a qualquer momento na aba Rascunhos.');
  document.getElementById('mq-name').value = '';
  document.getElementById('mq-sales-projection').value = '';
  document.getElementById('mq-items').innerHTML = '';
  mqExtraSupplierIds = [];
  const extraSuppliersBtn = document.getElementById('mq-extra-suppliers-btn');
  if (extraSuppliersBtn) extraSuppliersBtn.textContent = 'Selecionar representantes';
  document.getElementById('mq-group').value = '';
  setMqRecipientMode('representantes');
  goToSection('quotation-reports');
}

