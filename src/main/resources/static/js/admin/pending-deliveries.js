// ============================================================
// PRODUTOS A RECEBER
// ============================================================
// Quebra as Ordens de Compra em itens individuais (uma OC pode ter vários produtos) —
// já vem filtrado pelo backend pra só trazer quem ainda não passou do prazo de entrega
// do fornecedor (ver PurchaseOrderService.findPendingDeliveryItems). Não precisa
// re-filtrar isso aqui, só busca/paginar em cima do que já chegou.
let pendingDeliveriesCache = [];
let pendingDeliveriesPage = 0;
let pendingDeliveriesSearchTerm = '';

async function loadPendingDeliveries() {
  pendingDeliveriesCache = await safeCall(() => api('GET', '/purchase-orders/pending-deliveries'));
  pendingDeliveriesPage = 0;
  renderPendingDeliveriesList();
}

function onPendingDeliveriesSearchInput() {
  pendingDeliveriesSearchTerm = document.getElementById('pending-deliveries-search').value.trim().toLowerCase();
  pendingDeliveriesPage = 0;
  renderPendingDeliveriesList();
}

function pendingDeliveryStatusBadge(status) {
  return status === 'RECEIVED'
    ? '<span class="badge badge-available">Recebida</span>'
    : '<span class="badge badge-reviewing">Pendente</span>';
}

function renderPendingDeliveriesList() {
  const list = pendingDeliveriesSearchTerm
    ? pendingDeliveriesCache.filter(i =>
        i.productName.toLowerCase().includes(pendingDeliveriesSearchTerm) ||
        i.supplierName.toLowerCase().includes(pendingDeliveriesSearchTerm))
    : pendingDeliveriesCache;

  const { items, page, totalPages } = paginateSlice(list, pendingDeliveriesPage, DEFAULT_PAGE_SIZE);
  pendingDeliveriesPage = page;

  const tbody = document.getElementById('pending-deliveries-tbody');
  tbody.innerHTML = '';
  document.getElementById('pending-deliveries-empty').style.display = list.length ? 'none' : 'block';

  items.forEach(i => {
    const tr = document.createElement('tr');
    tr.innerHTML = `
      <td class="truncate-cell" title="${escapeHtml(i.productName)}">${escapeHtml(i.productName)}</td>
      <td class="mono">${escapeHtml(i.productBarcode || '—')}</td>
      <td style="text-align:center">${i.quantity}</td>
      <td style="text-align:right">${formatCurrencyFromNumber(i.unitPrice)}</td>
      <td class="truncate-cell" title="${escapeHtml(i.supplierName)}">${escapeHtml(i.supplierName)}</td>
      <td class="truncate-cell" title="${escapeHtml(i.quotationName)}"><a href="#" onclick="abrirDetalheCotacao(${i.quotationId}); return false;">${escapeHtml(i.quotationName)}</a></td>
      <td>${fmtDateOnly(i.estimatedDeliveryDate)}</td>
      <td>${pendingDeliveryStatusBadge(i.status)}</td>`;
    tbody.appendChild(tr);
  });

  updatePaginationControls('pending-deliveries', page, totalPages, list.length, (newPage) => {
    pendingDeliveriesPage = newPage;
    renderPendingDeliveriesList();
  });
}
