// ============================================================
// FORNECEDORES
// ============================================================
// ============================================================
// SUPPLIERS
// ============================================================
let suppliersCache = [];
let suppliersInactiveCache = [];
let currentSupplierListFilter = 'active';
let currentSupplierSearchTerm = '';

async function loadSuppliers() {
  currentSupplierSearchTerm = '';
  document.getElementById('supplier-search').value = '';
  suppliersCache = await safeCall(() => api('GET', '/suppliers'));
  renderSuppliersList();
}

function onSupplierSearchInput() {
  currentSupplierSearchTerm = document.getElementById('supplier-search').value.trim().toLowerCase();
  suppliersPage = 0;
  renderSuppliersList();
}

document.querySelectorAll('#supplier-status-tabs .tab').forEach(tab => {
  tab.addEventListener('click', async () => {
    document.querySelectorAll('#supplier-status-tabs .tab').forEach(t => t.classList.remove('active'));
    tab.classList.add('active');
    currentSupplierListFilter = tab.dataset.status;
    document.getElementById('supplier-list-title').textContent = currentSupplierListFilter === 'active'
      ? 'Fornecedores ativos' : 'Fornecedores inativos';
    if (currentSupplierListFilter === 'inactive' && !suppliersInactiveCache.length) {
      suppliersInactiveCache = await safeCall(() => api('GET', '/suppliers/inactive'));
    }
    suppliersPage = 0;
    renderSuppliersList();
  });
});

let suppliersPage = 0;

function renderSuppliersList() {
  const source = currentSupplierListFilter === 'active' ? suppliersCache : suppliersInactiveCache;
  const list = currentSupplierSearchTerm
    ? source.filter(s => s.name.toLowerCase().includes(currentSupplierSearchTerm) || (s.cnpj || '').includes(currentSupplierSearchTerm))
    : source;

  const { items, page, totalPages } = paginateSlice(list, suppliersPage, DEFAULT_PAGE_SIZE);
  suppliersPage = page;

  const tbody = document.getElementById('suppliers-tbody');
  tbody.innerHTML = '';
  document.getElementById('suppliers-empty').style.display = list.length ? 'none' : 'block';
  items.forEach(s => {
    const tr = document.createElement('tr');
    const actions = currentSupplierListFilter === 'active'
      ? `<button class="secondary small" onclick="openSupplierModal(${s.id})">Editar</button>
         <button class="danger small" onclick="deactivateSupplier(${s.id}, this)">Desativar</button>
         <button class="secondary small" onclick="openSupplierGroupModal(${s.id})">Grupos</button>
         <button class="danger small" onclick="hardDeleteSupplier(${s.id}, this)">Excluir</button>`
      : `<button class="success small" onclick="reactivateSupplierModal(${s.id})">Reativar</button>
         <button class="danger small" onclick="hardDeleteSupplier(${s.id}, this)">Excluir</button>`;
    tr.innerHTML = `<td>${s.id}</td><td class="truncate-cell" title="${escapeHtml(s.name)}">${s.name}</td><td class="mono">${s.cnpj}</td>
      <td class="num">${s.minimumOrderValue != null ? 'R$ ' + s.minimumOrderValue : '—'}</td>
      <td>${s.defaultDeliveryDeadlineDays != null ? s.defaultDeliveryDeadlineDays + ' dias' : '—'}</td>
      <td class="truncate-cell" title="${escapeHtml(s.representativeName || '')}">${s.representativeName || '—'}</td>
      <td class="truncate-cell" title="${escapeHtml((s.groupNames || []).join(', '))}">${(s.groupNames || []).length ? escapeHtml(s.groupNames.join(', ')) : '—'}</td>
      <td><div class="row-actions">${actions}</div></td>`;
    tbody.appendChild(tr);
  });

  updatePaginationControls('suppliers', page, totalPages, list.length, (newPage) => { suppliersPage = newPage; renderSuppliersList(); });
}

function findSupplierById(id) {
  return suppliersCache.find(s => s.id === id) || suppliersInactiveCache.find(s => s.id === id);
}

async function openSupplierModal(id) {
  const s = id ? findSupplierById(id) : null;
  openModal(`
    <h2>${s ? 'Editar fornecedor #' + s.id : 'Novo fornecedor'}</h2>
    <input type="hidden" id="modal-supplier-id" value="${s ? s.id : ''}">
    <div class="field-grid">
      <div><label>Nome</label><input id="modal-supplier-name" value="${s ? escapeHtml(s.name) : ''}" placeholder="Distribuidora Exemplo Ltda"></div>
      <div><label>CNPJ</label><input id="modal-supplier-cnpj" value="${s ? maskCnpj(s.cnpj) : ''}" placeholder="12.345.678/0001-99" oninput="this.value = maskCnpj(this.value)"></div>
      <div><label>Telefone</label><input id="modal-supplier-phone" value="${s ? maskPhone(s.phone) : ''}" placeholder="(27) 99999-9999" oninput="this.value = maskPhone(this.value)"></div>
      <div><label>Endereço</label><input id="modal-supplier-address" value="${s ? escapeHtml(s.address) : ''}" placeholder="Rua, número, cidade"></div>
      <div><label>Pedido mínimo (R$)</label><input id="modal-supplier-minorder" type="text" inputmode="decimal" value="${s && s.minimumOrderValue != null ? formatCurrencyFromNumber(s.minimumOrderValue) : ''}" placeholder="0,00" oninput="this.value = maskCurrencyInput(this.value)"></div>
      <div><label>Prazo de entrega padrão (dias)</label><input id="modal-supplier-deadline" type="number" min="1" step="1" value="${s && s.defaultDeliveryDeadlineDays != null ? s.defaultDeliveryDeadlineDays : ''}" placeholder="Ex: 5"></div>
      <div><label>Representante</label><select id="modal-supplier-representative"><option value="">— nenhum —</option></select></div>
    </div>
    <div class="btn-row" style="margin-top:16px">
      <button onclick="submitSupplierModal(this)">Salvar</button>
      <button class="secondary" onclick="closeModal()">Cancelar</button>
    </div>
  `);
  await populateModalRepresentativeSelect(s ? s.representativeId : null);
}

async function populateModalRepresentativeSelect(selectedId) {
  const reps = await safeCall(() => api('GET', '/representatives'));
  const sel = document.getElementById('modal-supplier-representative');
  if (!sel) return;
  sel.innerHTML = '<option value="">— nenhum —</option>' +
    reps.map(r => `<option value="${r.id}" ${selectedId === r.id ? 'selected' : ''}>${escapeHtml(r.name)}</option>`).join('');
}

function submitSupplierModal(buttonEl) {
  const id = document.getElementById('modal-supplier-id').value;
  const repId = document.getElementById('modal-supplier-representative').value;
  const minOrder = document.getElementById('modal-supplier-minorder').value;
  const body = {
    name: document.getElementById('modal-supplier-name').value.trim(),
    cnpj: unmaskDigits(document.getElementById('modal-supplier-cnpj').value),
    phone: unmaskDigits(document.getElementById('modal-supplier-phone').value) || null,
    address: document.getElementById('modal-supplier-address').value.trim() || null,
    minimumOrderValue: unmaskCurrencyToNumber(minOrder),
    representativeId: repId ? parseInt(repId) : null,
    defaultDeliveryDeadlineDays: document.getElementById('modal-supplier-deadline').value || null
  };
  saveWithReactivation(
    () => api(id ? 'PUT' : 'POST', id ? `/suppliers/${id}` : '/suppliers', body),
    (existingId) => api('POST', `/suppliers/${existingId}/reactivate`, body),
    { cnpj: 'modal-supplier-cnpj', name: 'modal-supplier-name', phone: 'modal-supplier-phone',
      address: 'modal-supplier-address', minimumOrderValue: 'modal-supplier-minorder' },
    buttonEl,
    () => {
      toast(id ? 'Fornecedor atualizado.' : 'Fornecedor salvo.');
      closeModal();
      refreshCurrentSuppliersView();
    }
  );
}

async function reactivateSupplierModal(id) {
  const s = findSupplierById(id);
  if (!s) return;
  openModal(`
    <h2>Reativar fornecedor #${s.id}</h2>
    <div class="subtitle">Confira os dados antes de reativar — eles são atualizados junto.</div>
    <input type="hidden" id="modal-supplier-id" value="${s.id}">
    <div class="field-grid">
      <div><label>Nome</label><input id="modal-supplier-name" value="${escapeHtml(s.name)}"></div>
      <div><label>CNPJ</label><input id="modal-supplier-cnpj" value="${maskCnpj(s.cnpj)}" oninput="this.value = maskCnpj(this.value)"></div>
      <div><label>Telefone</label><input id="modal-supplier-phone" value="${maskPhone(s.phone)}" oninput="this.value = maskPhone(this.value)"></div>
      <div><label>Endereço</label><input id="modal-supplier-address" value="${escapeHtml(s.address)}"></div>
      <div><label>Pedido mínimo (R$)</label><input id="modal-supplier-minorder" type="text" inputmode="decimal" value="${s.minimumOrderValue != null ? formatCurrencyFromNumber(s.minimumOrderValue) : ''}" oninput="this.value = maskCurrencyInput(this.value)"></div>
      <div><label>Prazo de entrega padrão (dias)</label><input id="modal-supplier-deadline" type="number" min="1" step="1" value="${s.defaultDeliveryDeadlineDays != null ? s.defaultDeliveryDeadlineDays : ''}" placeholder="Ex: 5"></div>
      <div><label>Representante</label><select id="modal-supplier-representative"><option value="">— nenhum —</option></select></div>
    </div>
    <div class="btn-row" style="margin-top:16px">
      <button class="success" onclick="submitReactivateSupplier()">Reativar</button>
      <button class="secondary" onclick="closeModal()">Cancelar</button>
    </div>
  `);
  await populateModalRepresentativeSelect(s.representativeId);
}

async function submitReactivateSupplier() {
  const id = document.getElementById('modal-supplier-id').value;
  const repId = document.getElementById('modal-supplier-representative').value;
  const minOrder = document.getElementById('modal-supplier-minorder').value;
  const body = {
    name: document.getElementById('modal-supplier-name').value.trim(),
    cnpj: unmaskDigits(document.getElementById('modal-supplier-cnpj').value),
    phone: unmaskDigits(document.getElementById('modal-supplier-phone').value) || null,
    address: document.getElementById('modal-supplier-address').value.trim() || null,
    minimumOrderValue: unmaskCurrencyToNumber(minOrder),
    representativeId: repId ? parseInt(repId) : null,
    defaultDeliveryDeadlineDays: document.getElementById('modal-supplier-deadline').value || null
  };
  await safeCall(() => api('POST', `/suppliers/${id}/reactivate`, body));
  toast('Fornecedor reativado.');
  closeModal();
  refreshCurrentSuppliersView();
}

function deactivateSupplier(id, buttonEl) {
  const s = findSupplierById(id);
  showConfirmPopover(buttonEl, 'Desativar o fornecedor ' + (s ? escapeHtml(s.name) : '#' + id) + '?', async () => {
    await safeCall(() => api('DELETE', `/suppliers/${id}`));
    toast('Fornecedor desativado.');
    refreshCurrentSuppliersView();
  });
}

// Diferente do "Desativar" — isso EXCLUI de verdade, sem volta. Só funciona quando o
// backend confirma que o fornecedor nunca foi usado (sem lance, sem "Não Cotar", sem
// pedido confirmado); se já tiver histórico, o backend recusa com uma mensagem clara,
// que o safeCall já mostra como toast de erro — não precisa checar nada aqui antes.
function hardDeleteSupplier(id, buttonEl) {
  const s = findSupplierById(id);
  showConfirmPopover(buttonEl, 'Excluir DEFINITIVAMENTE o fornecedor ' + (s ? escapeHtml(s.name) : '#' + id) + '? Essa ação não pode ser desfeita.', async () => {
    await safeCall(() => api('DELETE', `/suppliers/${id}/permanent`));
    toast('Fornecedor excluído.');
    refreshCurrentSuppliersView();
  });
}

async function refreshCurrentSuppliersView() {
  suppliersInactiveCache = [];
  if (currentSupplierListFilter === 'inactive') {
    suppliersInactiveCache = await safeCall(() => api('GET', '/suppliers/inactive'));
  } else {
    suppliersCache = await safeCall(() => api('GET', '/suppliers'));
  }
  renderSuppliersList();
}

function openSupplierGroupModal(id) {
  const s = findSupplierById(id);
  if (!s) return;
  const currentGroupsHtml = (s.groupIds || []).length
    ? s.groupIds.map((gid, i) => `
        <span class="chip">${escapeHtml(s.groupNames[i])}
          <button type="button" class="chip-remove" title="Remover do grupo" onclick="removeSupplierFromGroup(${s.id}, ${gid}, this)">×</button>
        </span>`).join('')
    : '<span class="subtitle">Esse fornecedor ainda não está em nenhum grupo.</span>';

  openModal(`
    <h2>Grupos de ${escapeHtml(s.name)}</h2>
    <label style="margin-bottom:6px">Grupos atuais</label>
    <div id="modal-current-groups" style="display:flex; flex-wrap:wrap; gap:6px; margin-bottom:16px">${currentGroupsHtml}</div>

    <hr class="divider">
    <h3 style="margin:14px 0 6px">Incluir em outro grupo</h3>
    <input type="hidden" id="modal-group-supplier-id" value="${s.id}">
    <div class="field-grid">
      <div><label>Grupo</label><select id="modal-group-select"></select></div>
    </div>
    <div class="btn-row" style="margin-top:16px">
      <button onclick="submitSupplierGroupModal(this)">Incluir</button>
      <button class="secondary" onclick="closeModal()">Fechar</button>
    </div>
  `);
  populateModalGroupSelect();
}

async function populateModalGroupSelect() {
  const groups = await safeCall(() => api('GET', '/supplier-groups'));
  const sel = document.getElementById('modal-group-select');
  if (!sel) return;
  sel.innerHTML = groups.map(g => `<option value="${g.id}">${escapeHtml(g.name)}</option>`).join('');
}

async function submitSupplierGroupModal(buttonEl) {
  const supplierId = document.getElementById('modal-group-supplier-id').value;
  const groupId = document.getElementById('modal-group-select').value;
  if (!groupId) { toast('Escolha um grupo.', true); return; }
  await withButtonLoading(buttonEl, 'Incluindo...', () => safeCall(() => api('POST', `/suppliers/${supplierId}/groups/${groupId}`)));
  toast('Fornecedor incluído no grupo.');
  await loadSuppliers();
  openSupplierGroupModal(Number(supplierId));
}

async function removeSupplierFromGroup(supplierId, groupId, buttonEl) {
  try {
    await withButtonLoading(buttonEl, '...', () => safeCall(() => api('DELETE', `/suppliers/${supplierId}/groups/${groupId}`)));
  } catch (e) {
    return;
  }
  toast('Fornecedor removido do grupo.');
  await loadSuppliers();
  openSupplierGroupModal(supplierId);
}

