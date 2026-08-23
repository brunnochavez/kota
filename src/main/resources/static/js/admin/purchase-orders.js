// ============================================================
// ORDENS DE COMPRA
// ============================================================
let poCache = [];
let poPage = 0;
let currentPoStatusFilter = 'all';

const PO_STATUS_LABELS = { PENDING: 'Pendente', RECEIVED: 'Recebida' };

async function loadPurchaseOrders() {
  poCache = await safeCall(() => api('GET', '/purchase-orders'));
  poPage = 0;
  renderPurchaseOrdersList();
}

document.querySelectorAll('#po-status-tabs .tab').forEach(tab => {
  tab.addEventListener('click', () => {
    document.querySelectorAll('#po-status-tabs .tab').forEach(t => t.classList.remove('active'));
    tab.classList.add('active');
    currentPoStatusFilter = tab.dataset.status;
    poPage = 0;
    renderPurchaseOrdersList();
  });
});

// formatOrderNumber espelha o mesmo visual de formatQuotationNumber (zero à esquerda,
// 6 dígitos) — mas com prefixo "OC-" pra não confundir com o número da cotação de
// origem, que também aparece na mesma linha da tabela.
function formatOrderNumber(id) {
  return 'OC-' + String(id).padStart(6, '0');
}

function poStatusBadge(status) {
  return status === 'RECEIVED'
    ? '<span class="badge badge-available">Recebida</span>'
    : '<span class="badge badge-reviewing">Pendente</span>';
}

function renderPurchaseOrdersList() {
  const list = currentPoStatusFilter === 'all'
    ? poCache
    : poCache.filter(po => po.status === currentPoStatusFilter);

  const { items, page, totalPages } = paginateSlice(list, poPage, DEFAULT_PAGE_SIZE);
  poPage = page;

  const tbody = document.getElementById('po-tbody');
  tbody.innerHTML = '';
  document.getElementById('po-empty').style.display = list.length ? 'none' : 'block';

  items.forEach(po => {
    const tr = document.createElement('tr');
    const actions = po.status === 'PENDING'
      ? `<button class="secondary small" onclick="markPurchaseOrderReceived(${po.id}, this)">Marcar como recebida</button>
         <button class="secondary small" onclick="downloadPdfWithAuth('/purchase-orders/${po.id}/pdf', 'ordem-compra-${po.id}.pdf')">Baixar PDF</button>`
      : `<button class="secondary small" onclick="downloadPdfWithAuth('/purchase-orders/${po.id}/pdf', 'ordem-compra-${po.id}.pdf')">Baixar PDF</button>`;

    tr.innerHTML = `
      <td class="mono">${formatOrderNumber(po.id)}</td>
      <td class="truncate-cell" title="${escapeHtml(po.quotationName)}"><a href="#" onclick="abrirDetalheCotacao(${po.quotationId}); return false;">${escapeHtml(po.quotationName)}</a></td>
      <td class="truncate-cell" title="${escapeHtml(po.supplierName)}">${escapeHtml(po.supplierName)}</td>
      <td>${fmtDate(po.createdAt)}</td>
      <td>${fmtDateOnly(po.estimatedDeliveryDate)}</td>
      <td style="text-align:right">${formatCurrencyFromNumber(po.totalValue)}</td>
      <td>${poStatusBadge(po.status)}</td>
      <td><div class="row-actions">${actions}</div></td>`;
    tbody.appendChild(tr);
  });

  updatePaginationControls('po', page, totalPages, list.length, (newPage) => { poPage = newPage; renderPurchaseOrdersList(); });
}

async function markPurchaseOrderReceived(id, buttonEl) {
  showConfirmPopover(buttonEl, 'Marcar essa ordem de compra como recebida?', async () => {
    await safeCall(() => api('POST', `/purchase-orders/${id}/mark-received`));
    toast('Ordem de compra marcada como recebida.');
    await loadPurchaseOrders();
  });
}
