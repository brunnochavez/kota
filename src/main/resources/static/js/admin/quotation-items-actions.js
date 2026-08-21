// ============================================================
// ITENS DA COTAÇÃO — AÇÕES
// ============================================================
function onQdItemQtyInput(itemId, value) {
  qdPendingQuantityEdits[itemId] = value;
}

function renderQdItemsRows(items) {
  const tbody = document.getElementById('qd-items-tbody');
  tbody.innerHTML = '';

  const totalPages = Math.max(Math.ceil(items.length / QD_ITEMS_PAGE_SIZE), 1);
  if (qdItemsPage >= totalPages) qdItemsPage = totalPages - 1;
  if (qdItemsPage < 0) qdItemsPage = 0;
  const pageItems = items.slice(qdItemsPage * QD_ITEMS_PAGE_SIZE, (qdItemsPage + 1) * QD_ITEMS_PAGE_SIZE);

  if (!items.length) {
    tbody.innerHTML = `<tr><td colspan="${qdCurrentItemsIsDraft ? 5 : 4}" class="empty">Nenhum item encontrado.</td></tr>`;
  }

  // Célula da projeção de venda: mostra o valor efetivo (sobrescrita do item, ou o
  // padrão da cotação quando o item não tem sobrescrita própria). Mudou o número →
  // vira sobrescrita explícita desse item. O "×" só aparece quando já existe
  // sobrescrita, e serve pra voltar a herdar o padrão da cotação.
  const projectionCell = (it) => {
    const isOverridden = it.salesProjectionDaysOverride !== null && it.salesProjectionDaysOverride !== undefined;
    const value = it.effectiveSalesProjectionDays != null ? it.effectiveSalesProjectionDays : '';
    const tag = !isOverridden && value !== ''
      ? ' <span style="font-size:10px; color:var(--text-faint); white-space:nowrap">(padrão)</span>'
      : '';
    const resetBtn = isOverridden
      ? `<button type="button" class="icon-btn" title="Voltar a usar o padrão da cotação" onclick="resetItemSalesProjection(${it.id})" style="width:22px; height:22px; padding:0; margin-left:4px; font-size:14px; line-height:1">×</button>`
      : '';
    return `<td style="text-align:center; white-space:nowrap">
        <input type="number" min="1" step="1" value="${value}" id="qd-item-projection-${it.id}"
          style="width:64px; text-align:center" onchange="saveItemSalesProjection(${it.id}, this)">${tag}${resetBtn}
      </td>`;
  };

  pageItems.forEach(it => {
    const tr = document.createElement('tr');
    if (qdCurrentItemsIsDraft) {
      const currentValue = qdPendingQuantityEdits[it.id] !== undefined ? qdPendingQuantityEdits[it.id] : it.quantity;
      tr.innerHTML = `<td class="mono">${it.productBarcode}</td><td>${it.productName}</td>
        <td style="text-align:center"><input type="number" step="0.001" value="${currentValue}" id="qd-item-qty-${it.id}"
          style="width:80px; text-align:center" oninput="onQdItemQtyInput(${it.id}, this.value)"></td>
        ${projectionCell(it)}
        <td style="text-align:center">
          <button class="icon-btn danger" onclick="removeQuotationItem(${it.id}, this)" title="Remover item" aria-label="Remover item">${QD_TRASH_ICON}</button>
        </td>`;
    } else {
      const cutBadge = it.fulfillmentCut
        ? ' <span style="color:var(--danger); background:var(--danger-bg); border:1px solid var(--danger-border); font-size:10.5px; font-weight:700; padding:2px 7px; border-radius:20px; margin-left:6px; white-space:nowrap">Cortado — sem estoque</span>'
        : '';
      tr.innerHTML = `<td class="mono">${it.productBarcode}</td><td>${it.productName}${cutBadge}</td>
        <td style="text-align:center">${it.quantity}</td>
        ${projectionCell(it)}`;
    }
    tbody.appendChild(tr);
  });

  // Paginação sempre visível — não some com 1 página só, apenas desabilita os botões sem pra onde ir.
  document.getElementById('qd-items-page-info').textContent = `Página ${qdItemsPage + 1} de ${totalPages}`;
  document.getElementById('qd-items-prev-btn').disabled = qdItemsPage === 0;
  document.getElementById('qd-items-next-btn').disabled = qdItemsPage >= totalPages - 1;
}

// Salva a sobrescrita de projeção de venda desse item. Campo vazio = remove a
// sobrescrita e volta a herdar o padrão da cotação (mesmo endpoint, valor null).
async function saveItemSalesProjection(itemId, inputEl) {
  const raw = (inputEl.value || '').trim();
  const value = raw === '' ? null : parseInt(raw, 10);
  if (value !== null && (isNaN(value) || value <= 0)) {
    toast('Projeção de venda deve ser um número maior que zero.', true);
    renderQdItemsRows(getQdFilteredItems());
    return;
  }
  const updated = await safeCall(() => api('PUT', `/quotations/${currentQuotationId}/items/${itemId}/sales-projection`, { salesProjectionDays: value }));
  const idx = qdCurrentItems.findIndex(i => i.id === itemId);
  if (idx !== -1) qdCurrentItems[idx] = updated;
  toast('Projeção de venda atualizada.');
  renderQdItemsRows(getQdFilteredItems());
}

function resetItemSalesProjection(itemId) {
  saveItemSalesProjection(itemId, { value: '' });
}

function getQdFilteredItems() {
  const term = document.getElementById('qd-items-filter').value.trim().toLowerCase();
  return term
    ? qdCurrentItems.filter(it => it.productName.toLowerCase().includes(term) || (it.productBarcode || '').includes(term))
    : qdCurrentItems;
}

function filterQdItemsTable() {
  qdItemsPage = 0;
  renderQdItemsRows(getQdFilteredItems());
}

function changeQdItemsPage(delta) {
  qdItemsPage += delta;
  renderQdItemsRows(getQdFilteredItems());
}

let qdProductsCache = [];

async function loadQdProductsCache() {
  qdProductsCache = await safeCall(() => api('GET', '/products'));
}

function searchQdNewItemProduct() {
  const term = document.getElementById('qd-new-item-search').value.trim().toLowerCase();
  document.getElementById('qd-new-item-product-id').value = '';
  const wrap = document.getElementById('qd-new-item-search-results');
  if (!term) { wrap.innerHTML = ''; return; }
  const matches = qdProductsCache.filter(p =>
    p.name.toLowerCase().includes(term) || (p.barcode || '').includes(term)
  ).slice(0, 8);
  wrap.innerHTML = matches.length
    ? matches.map(p => {
        const alreadyIn = qdCurrentItems.some(it => it.productId === p.id);
        return `<div class="search-result-item" onclick="selectQdNewItemProduct(${p.id})">
          <strong>${escapeHtml(p.name)}</strong> <span class="mono" style="color:var(--text-dim); font-size:12px">(${escapeHtml(p.barcode)})</span>${
            alreadyIn ? ' <span style="color:var(--success); font-weight:600; font-size:12px">Este produto já está na cotação!</span>' : ''
          }
        </div>`;
      }).join('')
    : '<div class="empty">Nenhum resultado.</div>';
}

function selectQdNewItemProduct(productId) {
  const p = qdProductsCache.find(x => x.id === productId);
  if (!p) return;
  document.getElementById('qd-new-item-product-id').value = p.id;
  document.getElementById('qd-new-item-search').value = `${p.name} (${p.barcode})`;
  document.getElementById('qd-new-item-search-results').innerHTML = '';
}

async function addQuotationItem() {
  const productId = document.getElementById('qd-new-item-product-id').value;
  const quantity = document.getElementById('qd-new-item-qty').value;
  if (!productId) { toast('Busque e selecione um produto na lista.', true); return; }
  if (!quantity) { toast('Informe a quantidade.', true); return; }
  await safeCall(() => api('POST', `/quotations/${currentQuotationId}/items`, {
    productId: parseInt(productId),
    quantity: parseFloat(quantity)
  }));
  toast('Item adicionado.');
  document.getElementById('qd-new-item-search').value = '';
  document.getElementById('qd-new-item-product-id').value = '';
  document.getElementById('qd-new-item-qty').value = '';
  await loadQuotationItemsDetail(currentQuotationId, 'DRAFT');
}

// Uma chamada só (/review-batch-update), não várias em paralelo — evita a corrida entre
// requisições concorrentes editando a mesma cotação. Só roda em Rascunho: em Revisão a
// tabela geral vira somente leitura (ver qdCurrentItemsIsDraft), então esse botão nem
// aparece nesse status.
async function saveAllQuotationItemQuantities() {
  const entries = Object.entries(qdPendingQuantityEdits);
  if (!entries.length) { toast('Nenhuma alteração para salvar.', true); return; }
  const itemUpdates = entries.map(([itemId, quantity]) => ({ itemId: parseInt(itemId), quantity: parseFloat(quantity) }));
  await safeCall(() => api('POST', `/quotations/${currentQuotationId}/review-batch-update`, { bidUpdates: [], itemUpdates }));
  toast('Quantidades salvas.');
  qdPendingQuantityEdits = {};
  loadQuotations();
  abrirDetalheCotacao(currentQuotationId);
}

// Popover pequeno, ancorado perto de quem clicou, em vez de modal centralizado — genérico
// o bastante pra reaproveitar em qualquer outra confirmação pontual no futuro. Fecha ao
// clicar fora, ao confirmar, ou ao cancelar.
