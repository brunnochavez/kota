// ============================================================
// GRUPOS DE FORNECEDORES
// ============================================================
// ============================================================
// SUPPLIER GROUPS
// ============================================================
let groupsCache = [];
let groupsInactiveCache = [];
let currentGroupListFilter = 'active';

let groupsPage = 0;

async function loadGroups() {
  currentGroupListFilter = 'active';
  document.querySelectorAll('#group-status-tabs .tab').forEach(t => t.classList.remove('active'));
  document.querySelector('#group-status-tabs .tab[data-status="active"]').classList.add('active');
  document.getElementById('groups-list-title').textContent = 'Grupos ativos';
  groupsCache = await safeCall(() => api('GET', '/supplier-groups'));
  groupsPage = 0;
  renderGroupsList();
}

document.querySelectorAll('#group-status-tabs .tab').forEach(tab => {
  tab.addEventListener('click', async () => {
    document.querySelectorAll('#group-status-tabs .tab').forEach(t => t.classList.remove('active'));
    tab.classList.add('active');
    currentGroupListFilter = tab.dataset.status;
    document.getElementById('groups-list-title').textContent = currentGroupListFilter === 'active'
      ? 'Grupos ativos' : 'Grupos inativos';
    groupsPage = 0;
    if (currentGroupListFilter === 'inactive' && !groupsInactiveCache.length) {
      groupsInactiveCache = await safeCall(() => api('GET', '/supplier-groups/inactive'));
    }
    renderGroupsList();
  });
});

function renderGroupsList() {
  const list = currentGroupListFilter === 'active' ? groupsCache : groupsInactiveCache;
  const { items, page, totalPages } = paginateSlice(list, groupsPage, DEFAULT_PAGE_SIZE);
  groupsPage = page;

  const tbody = document.getElementById('groups-tbody');
  tbody.innerHTML = '';
  document.getElementById('groups-empty').style.display = list.length ? 'none' : 'block';
  document.getElementById('groups-empty').textContent = currentGroupListFilter === 'active'
    ? 'Nenhum grupo cadastrado ainda.' : 'Nenhum grupo inativo no momento.';
  items.forEach(g => {
    const actions = currentGroupListFilter === 'active'
      ? `<button class="secondary small" onclick="openGroupModal(${g.id})">Editar</button>
         <button class="danger small" onclick="deleteGroup(${g.id}, this)">Desativar</button>
         <button class="secondary small" onclick="openGroupMembersModal(${g.id})">Fornecedores</button>`
      : `<button class="success small" onclick="reactivateGroup(${g.id}, this)">Reativar</button>`;
    const tr = document.createElement('tr');
    tr.innerHTML = `<td>${g.id}</td><td>${escapeHtml(g.name)}</td>
      <td><div class="row-actions">${actions}</div></td>`;
    tbody.appendChild(tr);
  });

  updatePaginationControls('groups', page, totalPages, list.length, (newPage) => { groupsPage = newPage; renderGroupsList(); });
}

function reactivateGroup(id, buttonEl) {
  const g = groupsInactiveCache.find(x => x.id === id);
  showConfirmPopover(buttonEl, 'Reativar o grupo ' + (g ? escapeHtml(g.name) : '#' + id) + '?', async () => {
    await safeCall(() => api('POST', `/supplier-groups/${id}/reactivate`));
    toast('Grupo reativado.');
    groupsInactiveCache = groupsInactiveCache.filter(x => x.id !== id);
    groupsCache = [];
    await loadGroups();
  });
}

function findGroupById(id) {
  return groupsCache.find(g => g.id === id);
}

function openGroupModal(id) {
  const g = id ? findGroupById(id) : null;
  openModal(`
    <h2>${g ? 'Editar grupo #' + g.id : 'Novo grupo'}</h2>
    <input type="hidden" id="modal-group-id" value="${g ? g.id : ''}">
    <div class="field-grid">
      <div><label>Nome</label><input id="modal-group-name" value="${g ? escapeHtml(g.name) : ''}" placeholder="Ex: Fornecedores Premium"></div>
    </div>
    <div class="btn-row" style="margin-top:16px">
      <button onclick="submitGroupModal(this)">Salvar</button>
      <button class="secondary" onclick="closeModal()">Cancelar</button>
    </div>
  `);
}

function submitGroupModal(buttonEl) {
  const id = document.getElementById('modal-group-id').value;
  const body = { name: document.getElementById('modal-group-name').value.trim() };
  saveWithReactivation(
    () => api(id ? 'PUT' : 'POST', id ? `/supplier-groups/${id}` : '/supplier-groups', body),
    (existingId) => api('POST', `/supplier-groups/${existingId}/reactivate`),
    { name: 'modal-group-name' },
    buttonEl,
    () => {
      toast(id ? 'Grupo atualizado.' : 'Grupo salvo.');
      closeModal();
      loadGroups();
    }
  );
}

function deleteGroup(id, buttonEl) {
  const g = findGroupById(id);
  showConfirmPopover(buttonEl, 'Desativar o grupo ' + (g ? escapeHtml(g.name) : '#' + id) + '?', async () => {
    await safeCall(() => api('DELETE', `/supplier-groups/${id}`));
    toast('Grupo desativado.');
    loadGroups();
  });
}

async function openGroupMembersModal(groupId) {
  const group = await safeCall(() => api('GET', `/supplier-groups/${groupId}`));
  groupMembersPage = 0;
  openModal2(`
    <h2>Fornecedores do grupo "${escapeHtml(group.name)}"</h2>
    <div id="group-members-list">Carregando...</div>
    ${paginationControlsHtml('group-members')}
    <hr class="divider">
    <h3>Adicionar fornecedor</h3>
    <div class="inline-form">
      <div style="flex:1"><select id="group-members-add-select"></select></div>
      <button onclick="addSupplierToGroupModal(${groupId})">Adicionar</button>
    </div>
    <div class="btn-row" style="margin-top:16px">
      <button class="secondary" onclick="closeModal2()">Fechar</button>
    </div>
  `);
  await refreshGroupMembersModal(groupId);
}

function manageQdGroupSuppliers() {
  const groupId = document.getElementById('qd-group').value;
  if (!groupId) { toast('Selecione um grupo primeiro.', true); return; }
  openGroupMembersModal(parseInt(groupId));
}

let groupMembersPage = 0;

async function refreshGroupMembersModal(groupId) {
  const [members, allSuppliers] = await Promise.all([
    safeCall(() => api('GET', `/suppliers/by-group/${groupId}`)),
    safeCall(() => api('GET', '/suppliers'))
  ]);

  const { items, page, totalPages } = paginateSlice(members, groupMembersPage, DEFAULT_PAGE_SIZE);
  groupMembersPage = page;

  const listEl = document.getElementById('group-members-list');
  if (listEl) {
    listEl.innerHTML = items.length
      ? items.map(s => `<div class="expiring-item">
          <span>${escapeHtml(s.name)} <span class="mono" style="color:var(--text-dim); font-size:12px">(${escapeHtml(s.cnpj)})</span></span>
          <button class="danger small" onclick="removeSupplierFromGroupModal(${groupId}, ${s.id})">Remover</button>
        </div>`).join('')
      : '<div class="empty">Nenhum fornecedor neste grupo ainda.</div>';
  }

  updatePaginationControls('group-members', page, totalPages, members.length, (newPage) => {
    groupMembersPage = newPage;
    refreshGroupMembersModal(groupId);
  });

  const memberIds = new Set(members.map(s => s.id));
  const available = allSuppliers.filter(s => !memberIds.has(s.id));
  const selectEl = document.getElementById('group-members-add-select');
  if (selectEl) {
    selectEl.innerHTML = available.length
      ? available.map(s => `<option value="${s.id}">${escapeHtml(s.name)}</option>`).join('')
      : '<option value="">Todos os fornecedores já estão neste grupo</option>';
  }
}

async function addSupplierToGroupModal(groupId) {
  const supplierId = document.getElementById('group-members-add-select').value;
  if (!supplierId) return;
  await safeCall(() => api('POST', `/suppliers/${supplierId}/groups/${groupId}`));
  toast('Fornecedor adicionado ao grupo.');
  await refreshGroupMembersModal(groupId);
}

async function removeSupplierFromGroupModal(groupId, supplierId) {
  await safeCall(() => api('DELETE', `/suppliers/${supplierId}/groups/${groupId}`));
  toast('Fornecedor removido do grupo.');
  await refreshGroupMembersModal(groupId);
}

async function loadGroupsForSelects() {
  const groups = await safeCall(() => api('GET', '/supplier-groups'));
  const optionsHtml = '<option value="">— definir depois —</option>' +
    groups.map(g => `<option value="${g.id}">${g.name}</option>`).join('');
  document.getElementById('mq-group').innerHTML = optionsHtml;
  document.getElementById('import-group').innerHTML = optionsHtml;
}

