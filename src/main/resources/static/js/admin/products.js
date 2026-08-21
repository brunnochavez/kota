// ============================================================
// PRODUTOS
// ============================================================
// ============================================================
// PRODUCTS
// ============================================================
let productsCache = [];
let productsInactiveCache = [];
let currentProductListFilter = 'active';
let currentProductSearchTerm = '';
let currentProductsPage = 0;
let currentProductsTotalPages = 0;
let productSearchDebounce = null;

async function loadProducts() {
  currentProductsPage = 0;
  currentProductSearchTerm = '';
  document.getElementById('product-search').value = '';
  await fetchProductsPage();
}

async function fetchProductsPage() {
  const result = await safeCall(() => api('GET',
    `/products/search?term=${encodeURIComponent(currentProductSearchTerm)}&page=${currentProductsPage}&size=20`));
  productsCache = result.content;
  currentProductsTotalPages = result.totalPages;
  renderProductsList();
}

function onProductSearchInput() {
  currentProductSearchTerm = document.getElementById('product-search').value.trim();
  currentProductsPage = 0;
  clearTimeout(productSearchDebounce);
  productSearchDebounce = setTimeout(() => {
    if (currentProductListFilter === 'active') fetchProductsPage();
  }, 300);
}

function changeProductsPage(delta) {
  const next = currentProductsPage + delta;
  if (next < 0 || next >= currentProductsTotalPages) return;
  currentProductsPage = next;
  fetchProductsPage();
}

document.querySelectorAll('#product-status-tabs .tab').forEach(tab => {
  tab.addEventListener('click', async () => {
    document.querySelectorAll('#product-status-tabs .tab').forEach(t => t.classList.remove('active'));
    tab.classList.add('active');
    currentProductListFilter = tab.dataset.status;
    document.getElementById('product-list-title').textContent = currentProductListFilter === 'active'
      ? 'Produtos ativos' : 'Produtos inativos';
    document.getElementById('products-pagination').style.display = currentProductListFilter === 'active' ? 'flex' : 'none';
    if (currentProductListFilter === 'inactive' && !productsInactiveCache.length) {
      productsInactiveCache = await safeCall(() => api('GET', '/products/inactive'));
    }
    if (currentProductListFilter === 'active') {
      fetchProductsPage();
    } else {
      renderProductsList();
    }
  });
});

function renderProductsList() {
  const list = currentProductListFilter === 'active' ? productsCache : productsInactiveCache;
  const tbody = document.getElementById('products-tbody');
  tbody.innerHTML = '';
  document.getElementById('products-empty').style.display = list.length ? 'none' : 'block';
  list.forEach(p => {
    const tr = document.createElement('tr');
    const actions = currentProductListFilter === 'active'
      ? `<button class="secondary small" onclick="openProductModal(${p.id})">Editar</button>
         <button class="danger small" onclick="deactivateProduct(${p.id}, this)">Desativar</button>`
      : `<button class="success small" onclick="reactivateProductModal(${p.id})">Reativar</button>`;
    tr.innerHTML = `<td>${p.id}</td><td class="mono">${p.barcode}</td><td>${p.name}</td><td>${p.unitOfMeasure || '—'}</td>
      <td class="num">${p.lastQuotedPrice != null ? 'R$ ' + p.lastQuotedPrice : '—'}</td>
      <td>${p.lastQuotedSupplierName || '—'}</td>
      <td><div class="row-actions">${actions}</div></td>`;
    tbody.appendChild(tr);
  });

  if (currentProductListFilter === 'active') {
    document.getElementById('products-page-info').textContent =
      `Página ${currentProductsPage + 1} de ${Math.max(currentProductsTotalPages, 1)}`;
    document.getElementById('products-prev-btn').disabled = currentProductsPage === 0;
    document.getElementById('products-next-btn').disabled = currentProductsPage >= currentProductsTotalPages - 1;
  }
}

function findProductById(id) {
  return productsCache.find(p => p.id === id) || productsInactiveCache.find(p => p.id === id);
}

function openProductModal(id) {
  const p = id ? findProductById(id) : null;
  openModal(`
    <h2>${p ? 'Editar produto #' + p.id : 'Novo produto'}</h2>
    <input type="hidden" id="modal-product-id" value="${p ? p.id : ''}">
    <div class="field-grid">
      <div><label>Código de barras</label><input id="modal-product-barcode" value="${p ? escapeHtml(p.barcode) : ''}" placeholder="7891000100103"></div>
      <div><label>Nome</label><input id="modal-product-name" value="${p ? escapeHtml(p.name) : ''}" placeholder="Arroz Tipo 1 5kg"></div>
      <div><label>Descrição</label><input id="modal-product-description" value="${p ? escapeHtml(p.description) : ''}" placeholder="opcional"></div>
      <div><label>Unidade de medida</label><input id="modal-product-unit" value="${p ? escapeHtml(p.unitOfMeasure) : ''}" placeholder="UN, KG, CX..."></div>
    </div>
    <div class="btn-row" style="margin-top:16px">
      <button onclick="submitProductModal(this)">Salvar</button>
      <button class="secondary" onclick="closeModal()">Cancelar</button>
    </div>
  `);
}

function submitProductModal(buttonEl) {
  const id = document.getElementById('modal-product-id').value;
  const body = {
    barcode: document.getElementById('modal-product-barcode').value.trim(),
    name: document.getElementById('modal-product-name').value.trim(),
    description: document.getElementById('modal-product-description').value.trim() || null,
    unitOfMeasure: document.getElementById('modal-product-unit').value.trim() || null
  };
  saveWithReactivation(
    () => api(id ? 'PUT' : 'POST', id ? `/products/${id}` : '/products', body),
    (existingId) => api('POST', `/products/${existingId}/reactivate`, body),
    { barcode: 'modal-product-barcode', name: 'modal-product-name' },
    buttonEl,
    () => {
      toast(id ? 'Produto atualizado.' : 'Produto salvo.');
      closeModal();
      refreshCurrentProductsView();
    }
  );
}

function reactivateProductModal(id) {
  const p = findProductById(id);
  if (!p) return;
  openModal(`
    <h2>Reativar produto #${p.id}</h2>
    <div class="subtitle">Confira os dados antes de reativar — eles são atualizados junto.</div>
    <input type="hidden" id="modal-product-id" value="${p.id}">
    <div class="field-grid">
      <div><label>Código de barras</label><input id="modal-product-barcode" value="${escapeHtml(p.barcode)}"></div>
      <div><label>Nome</label><input id="modal-product-name" value="${escapeHtml(p.name)}"></div>
      <div><label>Descrição</label><input id="modal-product-description" value="${escapeHtml(p.description)}"></div>
      <div><label>Unidade de medida</label><input id="modal-product-unit" value="${escapeHtml(p.unitOfMeasure)}"></div>
    </div>
    <div class="btn-row" style="margin-top:16px">
      <button class="success" onclick="submitReactivateProduct()">Reativar</button>
      <button class="secondary" onclick="closeModal()">Cancelar</button>
    </div>
  `);
}

async function submitReactivateProduct() {
  const id = document.getElementById('modal-product-id').value;
  const body = {
    barcode: document.getElementById('modal-product-barcode').value.trim(),
    name: document.getElementById('modal-product-name').value.trim(),
    description: document.getElementById('modal-product-description').value.trim() || null,
    unitOfMeasure: document.getElementById('modal-product-unit').value.trim() || null
  };
  await safeCall(() => api('POST', `/products/${id}/reactivate`, body));
  toast('Produto reativado.');
  closeModal();
  refreshCurrentProductsView();
}

function deactivateProduct(id, buttonEl) {
  const p = findProductById(id);
  showConfirmPopover(buttonEl, 'Desativar o produto ' + (p ? escapeHtml(p.name) : '#' + id) + '?', async () => {
    await safeCall(() => api('DELETE', `/products/${id}`));
    toast('Produto desativado.');
    refreshCurrentProductsView();
  });
}

async function refreshCurrentProductsView() {
  productsInactiveCache = [];
  if (currentProductListFilter === 'inactive') {
    productsInactiveCache = await safeCall(() => api('GET', '/products/inactive'));
    renderProductsList();
  } else {
    fetchProductsPage();
  }
}

