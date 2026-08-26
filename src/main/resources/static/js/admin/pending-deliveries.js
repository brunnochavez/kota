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

function renderPendingDeliveriesList() {
  // Busca por nome do produto, do fornecedor OU código de barras — antes só pegava
  // nome de produto/fornecedor; código de barras é o jeito mais rápido de achar um
  // item quando o admin está com o produto físico (ou o leitor) na mão.
  const list = pendingDeliveriesSearchTerm
    ? pendingDeliveriesCache.filter(i =>
        i.productName.toLowerCase().includes(pendingDeliveriesSearchTerm) ||
        i.supplierName.toLowerCase().includes(pendingDeliveriesSearchTerm) ||
        (i.productBarcode || '').toLowerCase().includes(pendingDeliveriesSearchTerm))
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
      <td class="truncate-cell"><a href="#" onclick="openLastOrderModal(${i.supplierId}, ${i.productId}); return false;">Ver último pedido</a></td>
      <td>${fmtDateOnly(i.estimatedDeliveryDate)}</td>`;
    tbody.appendChild(tr);
  });

  updatePaginationControls('pending-deliveries', page, totalPages, list.length, (newPage) => {
    pendingDeliveriesPage = newPage;
    renderPendingDeliveriesList();
  });
}

// Antes esse clique abria direto a cotação de origem daquele item (abrirDetalheCotacao)
// — trocado por um modal com o pedido mais recente que ESSE fornecedor já ganhou pra
// ESSE produto (GET /purchase-orders/last-for-supplier-product), que é a informação que
// o admin normalmente quer nessa hora: "da última vez que pedi isso desse fornecedor,
// quanto paguei / quando chegou" — não necessariamente a cotação específica que gerou
// a linha em que ele clicou.
async function openLastOrderModal(supplierId, productId) {
  let order;
  try {
    order = await api('GET', `/purchase-orders/last-for-supplier-product?supplierId=${supplierId}&productId=${productId}`);
  } catch (e) {
    toast(e.message, true);
    return;
  }

  const orderNumberLine = order.purchaseOrderId
    ? `<div class="expiring-item"><span>Ordem de compra</span><strong>${formatOrderNumber(order.purchaseOrderId)}</strong></div>`
    : `<div class="expiring-item"><span>Ordem de compra</span><strong style="color:var(--text-dim)">Não gerada</strong></div>`;
  const statusLine = order.status
    ? `<div class="expiring-item"><span>Status</span>${poStatusBadge(order.status)}</div>`
    : '';
  const deliveryLine = order.estimatedDeliveryDate
    ? `<div class="expiring-item"><span>Previsão de entrega</span><strong>${fmtDateOnly(order.estimatedDeliveryDate)}</strong></div>`
    : '';

  openModal2(`
    <h2>Último pedido — ${escapeHtml(order.supplierName)}</h2>
    <div class="subtitle" style="margin-bottom:14px">${escapeHtml(order.productName)} <span class="mono" style="color:var(--text-dim)">(${escapeHtml(order.productBarcode || '—')})</span></div>
    <div>
      <div class="expiring-item"><span>Cotação de origem</span><a href="#" onclick="closeModal2(); abrirDetalheCotacao(${order.quotationId}); return false;">${escapeHtml(order.quotationName)}</a></div>
      <div class="expiring-item"><span>Data do pedido</span><strong>${order.orderCreatedAt ? fmtDate(order.orderCreatedAt) : '—'}</strong></div>
      <div class="expiring-item"><span>Quantidade</span><strong>${order.quantity} un.</strong></div>
      <div class="expiring-item"><span>Preço unitário</span><strong>${formatCurrencyFromNumber(order.unitPrice)}</strong></div>
      ${orderNumberLine}
      ${statusLine}
      ${deliveryLine}
    </div>
    <div class="btn-row" style="margin-top:16px">
      <button class="secondary" onclick="closeModal2()">Fechar</button>
    </div>
  `);
}
