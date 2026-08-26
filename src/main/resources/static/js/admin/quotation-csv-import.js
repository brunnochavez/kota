// ============================================================
// IMPORTAÇÃO DE CSV
// ============================================================
// --- CSV import ---

// Fornecedores avulsos escolhidos pra cotação importada — mesmo conceito, mesmo picker
// (busca + checkbox + paginação) e mesmo cache de fornecedores (qdAllSuppliersCache,
// definido em quotation-detail-core.js) usado em manageQdExtraSuppliers/
// manageMqExtraSuppliers. Reaproveita o padrão de nomes com prefixo "import-".
let importExtraSupplierIds = [];
let importExtraSuppliersPage = 0;
let importExtraSuppliersSearchTerm = '';

// Grupo e Representantes são exclusivos aqui também — mesma regra de
// setQdRecipientMode/setMqRecipientMode, agora pro formulário de importação.
function setImportRecipientMode(mode) {
  document.querySelectorAll('#import-recipient-mode-tabs .tab').forEach(t => t.classList.toggle('active', t.dataset.mode === mode));
  document.getElementById('import-recipient-mode-group').style.display = mode === 'grupos' ? 'block' : 'none';
  document.getElementById('import-recipient-mode-reps').style.display = mode === 'representantes' ? 'block' : 'none';
  if (mode === 'grupos') {
    importExtraSupplierIds = [];
    const btn = document.getElementById('import-extra-suppliers-btn');
    if (btn) btn.textContent = 'Selecionar representantes';
  } else {
    document.getElementById('import-group').value = '';
  }
}

async function manageImportExtraSuppliers() {
  if (!qdAllSuppliersCache.length) {
    qdAllSuppliersCache = (await safeCall(() => api('GET', '/suppliers'))).filter(s => s.representativeId);
  }
  importExtraSuppliersPage = 0;
  importExtraSuppliersSearchTerm = '';
  openModal2(`
    <h2>Representantes</h2>
    <div class="subtitle" style="margin-bottom:12px">Adiciona a cotação diretamente pros representantes marcados aqui, sem depender de grupo nenhum.</div>
    <input id="import-extra-suppliers-search" placeholder="Buscar representante ou empresa..." autocomplete="off" oninput="onImportExtraSuppliersSearch(this.value)" style="margin-bottom:10px">
    <div id="import-extra-suppliers-list"></div>
    ${paginationControlsHtml('import-extra-suppliers')}
    <div class="btn-row" style="margin-top:16px; justify-content:space-between">
      <span id="import-extra-suppliers-selected-count" style="font-size:11.5px; color:var(--text-dim)">${importExtraSupplierIds.length} selecionado(s)</span>
      <button onclick="closeModal2()">Concluir</button>
    </div>
  `);
  renderImportExtraSuppliersList();
}

function onImportExtraSuppliersSearch(term) {
  importExtraSuppliersSearchTerm = term.trim().toLowerCase();
  importExtraSuppliersPage = 0;
  renderImportExtraSuppliersList();
}

function renderImportExtraSuppliersList() {
  const filtered = importExtraSuppliersSearchTerm
    ? qdAllSuppliersCache.filter(s =>
        s.name.toLowerCase().includes(importExtraSuppliersSearchTerm) ||
        (s.representativeName || '').toLowerCase().includes(importExtraSuppliersSearchTerm))
    : qdAllSuppliersCache;

  const { items, page, totalPages } = paginateSlice(filtered, importExtraSuppliersPage, DEFAULT_PAGE_SIZE);
  importExtraSuppliersPage = page;

  const listEl = document.getElementById('import-extra-suppliers-list');
  if (listEl) {
    listEl.innerHTML = items.length
      ? items.map(s => `
          <label class="expiring-item" style="cursor:pointer; justify-content:flex-start; gap:8px">
            <input type="checkbox" value="${s.id}" style="width:auto; flex-shrink:0" ${importExtraSupplierIds.includes(s.id) ? 'checked' : ''} onchange="toggleImportExtraSupplier(${s.id}, this.checked)">
            <span>${escapeHtml(s.name)}</span>
          </label>`).join('')
      : '<div class="empty">Nenhum representante encontrado.</div>';
  }

  updatePaginationControls('import-extra-suppliers', page, totalPages, filtered.length, (newPage) => {
    importExtraSuppliersPage = newPage;
    renderImportExtraSuppliersList();
  });
}

function toggleImportExtraSupplier(id, checked) {
  if (checked) {
    if (!importExtraSupplierIds.includes(id)) importExtraSupplierIds.push(id);
  } else {
    importExtraSupplierIds = importExtraSupplierIds.filter(x => x !== id);
  }
  const btn = document.getElementById('import-extra-suppliers-btn');
  if (btn) btn.textContent = importExtraSupplierIds.length ? `Selecionar representantes (${importExtraSupplierIds.length})` : 'Selecionar representantes';
  const countEl = document.getElementById('import-extra-suppliers-selected-count');
  if (countEl) countEl.textContent = `${importExtraSupplierIds.length} selecionado(s)`;
}

async function importFile(withMapping) {
  const fileInput = document.getElementById('import-file');
  if (!fileInput.files.length) { toast('Escolha um arquivo primeiro.', true); return; }
  const name = document.getElementById('import-name').value.trim();
  if (!name) { toast('Informe um nome para a cotação.', true); return; }
  const groupId = document.getElementById('import-group').value;
  const expiration = getExpirationValue('import-expiration');
  const salesProjection = document.getElementById('import-sales-projection').value;
  const includeCost = document.getElementById('import-include-cost').checked;

  const formData = new FormData();
  formData.append('file', fileInput.files[0]);

  let url = `/quotations/import?name=${encodeURIComponent(name)}&includeCostPrices=${includeCost}`;
  if (groupId) url += `&supplierGroupId=${groupId}`;
  importExtraSupplierIds.forEach(id => { url += `&extraSupplierIds=${id}`; });
  if (expiration) url += `&expirationDate=${encodeURIComponent(expiration)}`;
  if (salesProjection) url += `&defaultSalesProjectionDays=${salesProjection}`;
  if (withMapping) {
    const desc = document.getElementById('map-description').value;
    const barcode = document.getElementById('map-barcode').value;
    const qty = document.getElementById('map-quantity').value;
    url += `&descriptionColumn=${desc}&barcodeColumn=${barcode}&quantityColumn=${qty}`;
    // "— não usar —" (value vazio) é uma escolha válida mesmo com o checkbox marcado —
    // preço de custo nunca é obrigatório, só não manda o parâmetro nesse caso.
    const costEl = document.getElementById('map-cost');
    if (costEl && costEl.value !== '') url += `&costColumn=${costEl.value}`;
  }

  const result = await safeCall(() => api('POST', url, formData, true));

  if (result.needsMapping) {
    document.getElementById('import-mapping').style.display = 'block';
    const list = document.getElementById('import-headers-list');
    const columnOptions = result.headersFound.map((h, i) => `<option value="${i}">${i} — ${h}</option>`).join('');
    // Coluna de custo só aparece no mapeamento se o checkbox "incluir preço de custo"
    // estava marcado no momento do envio — sem isso, nem faz sentido perguntar qual
    // coluna usar. "— não usar —" continua disponível mesmo assim: o admin pode ter
    // marcado o checkbox e perceber, já na hora de mapear, que essa planilha específica
    // não tem coluna de custo nenhuma.
    const costFieldHtml = includeCost
      ? `<div><label>Coluna de preço de custo (opcional)</label><select id="map-cost"><option value="">— não usar —</option>${columnOptions}</select></div>`
      : '';
    list.innerHTML = `
      <div><label>Coluna de descrição</label><select id="map-description">${columnOptions}</select></div>
      <div><label>Coluna de código de barras</label><select id="map-barcode">${columnOptions}</select></div>
      <div><label>Coluna de quantidade</label><select id="map-quantity">${columnOptions}</select></div>
      ${costFieldHtml}`;
    toast('Cabeçalho novo — informe o mapeamento das colunas.');
  } else {
    toast('Cotação #' + result.quotation.id + ' importada com sucesso.');
    document.getElementById('import-mapping').style.display = 'none';
    document.getElementById('import-name').value = '';
    document.getElementById('import-group').value = '';
    clearExpirationValue('import-expiration');
    document.getElementById('import-sales-projection').value = '';
    document.getElementById('import-include-cost').checked = false;
    importExtraSupplierIds = [];
    const extraSuppliersBtn = document.getElementById('import-extra-suppliers-btn');
    if (extraSuppliersBtn) extraSuppliersBtn.textContent = 'Selecionar representantes';
    setImportRecipientMode('representantes');
    fileInput.value = '';
    goToSection('quotation-reports');
  }
}
