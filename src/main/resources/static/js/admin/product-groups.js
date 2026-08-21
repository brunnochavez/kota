// ============================================================
// GRUPOS DE PRODUTOS
// ============================================================
// ============================================================
// PRODUCT GROUPS — mesmo padrão dos grupos de fornecedor acima, só que agrupando
// produtos em vez de fornecedores. Usado no botão "+ Adicionar Grupo" da Nova Cotação,
// pra adicionar um grupo inteiro (ex: "Vinhos") de uma vez, em vez de item por item.
// ============================================================
let productGroupsCache = [];
let productGroupMembersCache = [];

let productGroupsPage = 0;

async function loadProductGroups() {
  productGroupsCache = await safeCall(() => api('GET', '/product-groups'));
  renderProductGroupsList();
}

function renderProductGroupsList() {
  const { items, page, totalPages } = paginateSlice(productGroupsCache, productGroupsPage, DEFAULT_PAGE_SIZE);
  productGroupsPage = page;

  const tbody = document.getElementById('product-groups-tbody');
  tbody.innerHTML = '';
  document.getElementById('product-groups-empty').style.display = productGroupsCache.length ? 'none' : 'block';
  items.forEach(g => {
    const tr = document.createElement('tr');
    tr.innerHTML = `<td>${g.id}</td><td>${g.name}</td><td>${g.productCount}</td>
      <td><div class="row-actions">
        <button class="secondary small" onclick="openProductGroupModal(${g.id})">Editar</button>
        <button class="danger small" onclick="deleteProductGroup(${g.id}, this)">Desativar</button>
        <button class="secondary small" onclick="openProductGroupMembersModal(${g.id})">Produtos</button>
      </div></td>`;
    tbody.appendChild(tr);
  });

  updatePaginationControls('product-groups', page, totalPages, productGroupsCache.length, (newPage) => { productGroupsPage = newPage; renderProductGroupsList(); });
}

function findProductGroupById(id) {
  return productGroupsCache.find(g => g.id === id);
}

function openProductGroupModal(id) {
  const g = id ? findProductGroupById(id) : null;
  openModal(`
    <h2>${g ? 'Editar grupo #' + g.id : 'Novo grupo de produtos'}</h2>
    <input type="hidden" id="modal-product-group-id" value="${g ? g.id : ''}">
    <div class="field-grid">
      <div><label>Nome</label><input id="modal-product-group-name" value="${g ? escapeHtml(g.name) : ''}" placeholder="Ex: Vinhos"></div>
    </div>
    <div class="btn-row" style="margin-top:16px">
      <button onclick="submitProductGroupModal(this)">Salvar</button>
      <button class="secondary" onclick="closeModal()">Cancelar</button>
    </div>
  `);
}

function submitProductGroupModal(buttonEl) {
  const id = document.getElementById('modal-product-group-id').value;
  const body = { name: document.getElementById('modal-product-group-name').value.trim() };
  saveWithReactivation(
    () => api(id ? 'PUT' : 'POST', id ? `/product-groups/${id}` : '/product-groups', body),
    (existingId) => api('POST', `/product-groups/${existingId}/reactivate`),
    { name: 'modal-product-group-name' },
    buttonEl,
    () => {
      toast(id ? 'Grupo atualizado.' : 'Grupo salvo.');
      closeModal();
      loadProductGroups();
    }
  );
}

function deleteProductGroup(id, buttonEl) {
  const g = findProductGroupById(id);
  showConfirmPopover(buttonEl, 'Desativar o grupo ' + (g ? escapeHtml(g.name) : '#' + id) + '?', async () => {
    await safeCall(() => api('DELETE', `/product-groups/${id}`));
    toast('Grupo desativado.');
    loadProductGroups();
  });
}

async function openProductGroupMembersModal(groupId) {
  const group = await safeCall(() => api('GET', `/product-groups/${groupId}`));
  if (!productsForSelect || !productsForSelect.length) {
    productsForSelect = await safeCall(() => api('GET', '/products'));
  }
  pgCurrentGroupId = groupId;
  pgSearchMatches = [];
  pgSearchPage = 0;
  pgSelectedIds = new Set();
  pgMembersPage = 0;

  openModal2(`
    <h2>Produtos do grupo "${escapeHtml(group.name)}"</h2>
    <div class="subtitle" style="margin-bottom:14px">Busque por código de barras ou nome — marque um ou mais e adicione de uma vez.</div>

    <div class="pg-modal-grid">
      <div class="pg-modal-col">
        <div style="position:relative; margin-bottom:10px">
          <label>Adicionar produto</label>
          <input type="text" id="pg-add-search" placeholder="Digite o código de barras ou o nome..." autocomplete="off"
            oninput="searchProductForGroup()">
        </div>

        <label id="pg-select-all-wrap" style="display:none; align-items:center; gap:8px; margin-bottom:7px; font-size:12px; color:var(--text-dim); cursor:pointer">
          <input type="checkbox" id="pg-select-all" style="width:auto" onchange="toggleSelectAllOnPage(this.checked)">
          Marcar todos desta página
        </label>
        <div id="pg-add-search-results" class="scroll-box"></div>
        <div class="btn-row" id="pg-search-pagination" style="justify-content:space-between; margin-top:8px; display:none">
          <div style="color:var(--text-dim); font-size:12px" id="pg-search-page-info">—</div>
          <div class="btn-row">
            <button class="secondary small" onclick="changePgSearchPage(-1)" id="pg-search-prev-btn">← Anterior</button>
            <button class="secondary small" onclick="changePgSearchPage(1)" id="pg-search-next-btn">Próxima →</button>
          </div>
        </div>
        <div class="btn-row" style="margin-top:10px">
          <button id="pg-add-selected-btn" onclick="addSelectedProductsToGroup()" style="display:none">Adicionar selecionados</button>
        </div>
      </div>

      <div class="pg-modal-col">
        <h3 id="product-group-members-title" style="margin-top:0">Produtos no grupo</h3>
        <div id="product-group-members-list" class="scroll-box">Carregando...</div>
        ${paginationControlsHtml('pg-members')}
      </div>
    </div>

    <div class="btn-row" style="margin-top:16px">
      <button class="secondary" onclick="closeModal2()">Fechar</button>
    </div>
  `, true);
  await refreshProductGroupMembersModal(groupId);
}

let pgMembersPage = 0;

async function refreshProductGroupMembersModal(groupId) {
  const members = await safeCall(() => api('GET', `/products/by-group/${groupId}`));
  productGroupMembersCache = members;

  const titleEl = document.getElementById('product-group-members-title');
  if (titleEl) titleEl.textContent = `Produtos no grupo (${members.length})`;

  // Página menor (6, não o DEFAULT_PAGE_SIZE global de 10) só nesse modal — precisa
  // caber sem rolagem dentro da janela de altura fixa, ver .pg-modal-col no CSS.
  const { items, page, totalPages } = paginateSlice(members, pgMembersPage, 6);
  pgMembersPage = page;

  const listEl = document.getElementById('product-group-members-list');
  if (listEl) {
    listEl.innerHTML = items.length
      ? items.map(p => `<div class="expiring-item">
          <span>${escapeHtml(p.name)} <span class="mono" style="color:var(--text-dim); font-size:12px">(${escapeHtml(p.barcode)})</span></span>
          <button class="danger small" onclick="removeProductFromGroupModal(${groupId}, ${p.id})">Remover</button>
        </div>`).join('')
      : '<div class="empty">Nenhum produto neste grupo ainda.</div>';
  }

  updatePaginationControls('pg-members', page, totalPages, members.length, (newPage) => {
    pgMembersPage = newPage;
    refreshProductGroupMembersModal(groupId);
  });

  // Busca já digitada precisa refletir a lista atualizada (produto que acabou de entrar
  // não pode continuar aparecendo como sugestão pra adicionar de novo).
  const searchInput = document.getElementById('pg-add-search');
  if (searchInput && searchInput.value.trim()) searchProductForGroup();
}

// ---------- busca com seleção múltipla + paginação (10 por página) ----------
let pgCurrentGroupId = null;
let pgSearchMatches = [];
let pgSearchPage = 0;
let pgSelectedIds = new Set();
const PG_SEARCH_PAGE_SIZE = 6;

function searchProductForGroup() {
  const term = document.getElementById('pg-add-search').value.trim().toLowerCase();
  const memberIds = new Set(productGroupMembersCache.map(p => p.id));
  pgSearchMatches = term
    ? productsForSelect.filter(p => !memberIds.has(p.id) && (p.name.toLowerCase().includes(term) || (p.barcode || '').includes(term)))
    : [];
  pgSearchPage = 0;
  renderPgSearchResults();
}

function renderPgSearchResults() {
  const wrap = document.getElementById('pg-add-search-results');
  const paginationEl = document.getElementById('pg-search-pagination');
  const selectAllWrap = document.getElementById('pg-select-all-wrap');
  const term = document.getElementById('pg-add-search').value.trim();

  if (!pgSearchMatches.length) {
    wrap.innerHTML = term ? '<div class="empty">Nenhum produto encontrado (ou já está todo no grupo).</div>' : '';
    paginationEl.style.display = 'none';
    selectAllWrap.style.display = 'none';
    updatePgAddButton();
    return;
  }

  const totalPages = Math.max(Math.ceil(pgSearchMatches.length / PG_SEARCH_PAGE_SIZE), 1);
  if (pgSearchPage >= totalPages) pgSearchPage = totalPages - 1;
  if (pgSearchPage < 0) pgSearchPage = 0;
  const pageStart = pgSearchPage * PG_SEARCH_PAGE_SIZE;
  const pageItems = pgSearchMatches.slice(pageStart, pageStart + PG_SEARCH_PAGE_SIZE);

  wrap.innerHTML = pageItems.map(p => `
    <label class="search-result-item" style="display:flex; align-items:center; gap:10px">
      <input type="checkbox" style="width:auto; flex-shrink:0" ${pgSelectedIds.has(p.id) ? 'checked' : ''} onchange="togglePgSelected(${p.id}, this.checked)">
      <span><strong>${escapeHtml(p.name)}</strong> <span class="mono" style="color:var(--text-dim); font-size:12px">(${escapeHtml(p.barcode)})</span></span>
    </label>`).join('');

  paginationEl.style.display = pgSearchMatches.length > PG_SEARCH_PAGE_SIZE ? 'flex' : 'none';
  document.getElementById('pg-search-page-info').textContent = `Página ${pgSearchPage + 1} de ${totalPages}`;
  document.getElementById('pg-search-prev-btn').disabled = pgSearchPage === 0;
  document.getElementById('pg-search-next-btn').disabled = pgSearchPage >= totalPages - 1;

  // "Marcar todos" reflete a página atual — marcado só quando TODO item desta página já
  // está selecionado, não quando é o total geral (que pode incluir outras páginas).
  selectAllWrap.style.display = 'flex';
  document.getElementById('pg-select-all').checked = pageItems.every(p => pgSelectedIds.has(p.id));

  updatePgAddButton();
}

function toggleSelectAllOnPage(checked) {
  const pageStart = pgSearchPage * PG_SEARCH_PAGE_SIZE;
  const pageItems = pgSearchMatches.slice(pageStart, pageStart + PG_SEARCH_PAGE_SIZE);
  pageItems.forEach(p => {
    if (checked) pgSelectedIds.add(p.id); else pgSelectedIds.delete(p.id);
  });
  renderPgSearchResults();
}

function changePgSearchPage(delta) {
  pgSearchPage += delta;
  renderPgSearchResults();
}

function togglePgSelected(productId, checked) {
  if (checked) pgSelectedIds.add(productId); else pgSelectedIds.delete(productId);
  // Um item desmarcado manualmente pode derrubar o "Marcar todos" da página pra
  // desmarcado — recalcula o resto da tela sem perder a página nem os já marcados.
  renderPgSearchResults();
}

function updatePgAddButton() {
  const btn = document.getElementById('pg-add-selected-btn');
  btn.style.display = pgSelectedIds.size ? 'inline-block' : 'none';
  btn.textContent = `Adicionar selecionados (${pgSelectedIds.size})`;
}

async function addSelectedProductsToGroup() {
  if (!pgSelectedIds.size) return;
  const ids = Array.from(pgSelectedIds);

  const result = await safeCall(() => api('POST', `/product-groups/${pgCurrentGroupId}/products`, { productIds: ids }));

  if (result.failedProductIds && result.failedProductIds.length) {
    toast(`${result.addedCount} adicionado${result.addedCount !== 1 ? 's' : ''}, ${result.failedProductIds.length} falhou/falharam (produto não encontrado).`, true);
  } else {
    toast(`${result.addedCount} produto${result.addedCount !== 1 ? 's' : ''} adicionado${result.addedCount !== 1 ? 's' : ''} ao grupo.`);
  }

  pgSelectedIds = new Set();
  pgSearchMatches = [];
  document.getElementById('pg-add-search').value = '';
  document.getElementById('pg-add-search-results').innerHTML = '';
  document.getElementById('pg-search-pagination').style.display = 'none';
  document.getElementById('pg-select-all-wrap').style.display = 'none';
  updatePgAddButton();

  await refreshProductGroupMembersModal(pgCurrentGroupId);
}

async function removeProductFromGroupModal(groupId, productId) {
  await safeCall(() => api('DELETE', `/products/${productId}/groups/${groupId}`));
  toast('Produto removido do grupo.');
  await refreshProductGroupMembersModal(groupId);
}

