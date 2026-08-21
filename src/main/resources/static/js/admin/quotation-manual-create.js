// ============================================================
// CRIAÇÃO MANUAL DE COTAÇÃO
// ============================================================
// --- manual creation ---
let manualItemCount = 0;
let productsForSelect = [];

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

async function createQuotationManually() {
  const name = document.getElementById('mq-name').value.trim();
  if (!name) { toast('Informe um nome para a cotação.', true); return; }
  const rows = document.querySelectorAll('#mq-items .item-row');
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
    expirationDate: document.getElementById('mq-expiration').value || null,
    defaultSalesProjectionDays: document.getElementById('mq-sales-projection').value || null,
    items
  };
  const q = await safeCall(() => api('POST', '/quotations', body));
  toast('Cotação #' + q.id + ' criada em DRAFT.');
  document.getElementById('mq-name').value = '';
  document.getElementById('mq-sales-projection').value = '';
  document.getElementById('mq-items').innerHTML = '';
  goToSection('quotation-reports');
}

