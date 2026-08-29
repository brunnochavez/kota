// ============================================================
// PRODUTOS SEM ATENDIMENTO (visão geral — todas as cotações fechadas)
// ============================================================
// Diferente do painel "Produtos sem atendimento" de dentro de uma cotação
// (renderQdFulfillmentIssues, em quotation-detail-core.js) — aqui é uma tela própria
// que junta os itens de TODAS as cotações fechadas, pra dar pra selecionar itens de
// cotações diferentes e juntar numa cotação nova ou existente só, em vez de resolver
// cotação por cotação.
let ufiCache = [];
let ufiSelectedIds = new Set();

async function loadUnfulfilledItems() {
  ufiCache = await safeCall(() => api('GET', '/quotations/unfulfilled-items'));
  ufiSelectedIds = new Set();
  renderUnfulfilledItems();
}

function renderUnfulfilledItems() {
  const noWinnerItems = ufiCache.filter(i => !i.cut);
  const cutItems = ufiCache.filter(i => i.cut);

  document.getElementById('ufi-empty').style.display = ufiCache.length ? 'none' : 'block';
  document.getElementById('ufi-section-noWinner').innerHTML = ufiCategoryHtml('noWinner', 'Sem nenhum lance', noWinnerItems);
  document.getElementById('ufi-section-cut').innerHTML = ufiCategoryHtml('cut', 'Cortados por falta de estoque', cutItems);

  updateUfiSelectedCount();
  checkUfiDraftQuotationsForAddButton();
}

// Checkbox "selecionar todos" no cabeçalho da categoria + um checkbox por item, com o
// nome da cotação de origem e a quantidade — a mesma linha (expiring-item) usada em
// outras listas de checkbox do sistema.
function ufiCategoryHtml(which, title, items) {
  if (!items.length) return '';
  const allSelected = items.every(i => ufiSelectedIds.has(i.quotationItemId));
  return `
    <div style="margin-bottom:16px">
      <label class="expiring-item" style="cursor:pointer; font-weight:700; background:var(--surface-2); border-radius:7px; padding:8px 10px">
        <input type="checkbox" style="width:auto; flex-shrink:0" id="ufi-select-all-${which}" ${allSelected ? 'checked' : ''} onchange="toggleUfiSelectAll('${which}', this.checked)">
        <span>${title} (${items.length})</span>
      </label>
      <div style="max-height:340px; overflow-y:auto; border:1px solid var(--border); border-top:none; border-radius:0 0 7px 7px">
        ${items.map(i => `
          <label class="expiring-item" style="cursor:pointer; padding-left:26px">
            <input type="checkbox" style="width:auto; flex-shrink:0" value="${i.quotationItemId}" ${ufiSelectedIds.has(i.quotationItemId) ? 'checked' : ''} onchange="toggleUfiItem(${i.quotationItemId}, this.checked)">
            <span style="flex:1">
              <strong style="font-size:12.5px">${escapeHtml(i.productName)}</strong> <span class="mono" style="font-size:11px; color:var(--text-dim)">${escapeHtml(i.productBarcode)}</span>
              <div style="font-size:11px; color:var(--text-dim)">${escapeHtml(i.quotationName)} · ${i.quantity} un.</div>
            </span>
          </label>`).join('')}
      </div>
    </div>`;
}

function toggleUfiSelectAll(which, checked) {
  ufiCache.filter(i => (which === 'cut') === i.cut).forEach(i => {
    if (checked) ufiSelectedIds.add(i.quotationItemId);
    else ufiSelectedIds.delete(i.quotationItemId);
  });
  renderUnfulfilledItems();
}

function toggleUfiItem(id, checked) {
  if (checked) ufiSelectedIds.add(id);
  else ufiSelectedIds.delete(id);
  updateUfiSelectedCount();

  // Atualiza só o checkbox "selecionar todos" da categoria desse item, sem
  // re-renderizar a lista inteira (perderia a posição de rolagem à toa).
  const item = ufiCache.find(i => i.quotationItemId === id);
  if (!item) return;
  const which = item.cut ? 'cut' : 'noWinner';
  const categoryItems = ufiCache.filter(i => i.cut === item.cut);
  const selectAllEl = document.getElementById('ufi-select-all-' + which);
  if (selectAllEl) selectAllEl.checked = categoryItems.every(i => ufiSelectedIds.has(i.quotationItemId));
}

function updateUfiSelectedCount() {
  document.getElementById('ufi-selected-count').textContent = ufiSelectedIds.size;
}

// "Adicionar a uma cotação existente" só aparece se houver algum Rascunho pra receber
// os itens — mesma regra do painel de dentro da cotação.
async function checkUfiDraftQuotationsForAddButton() {
  const all = await safeCall(() => api('GET', '/quotations'));
  const hasDraft = all.some(q => q.status === 'DRAFT');
  const btn = document.getElementById('ufi-add-existing-btn');
  if (btn) btn.style.display = hasDraft ? 'inline-block' : 'none';
}

async function createQuotationFromUnfulfilled() {
  if (!ufiSelectedIds.size) { toast('Selecione pelo menos um item.', true); return; }
  const q = await safeCall(() => api('POST', '/quotations/create-from-unfulfilled-items', Array.from(ufiSelectedIds)));
  toast('Cotação #' + q.id + ' criada em Rascunho com os itens selecionados.');
  loadQuotations();
  abrirDetalheCotacao(q.id);
}

async function openAddUnfulfilledToExistingModal() {
  if (!ufiSelectedIds.size) { toast('Selecione pelo menos um item.', true); return; }
  const all = await safeCall(() => api('GET', '/quotations'));
  const drafts = all.filter(q => q.status === 'DRAFT');

  openModal2(`
    <h2>Adicionar a uma cotação existente</h2>
    <div class="subtitle" style="margin-bottom:14px">Os ${ufiSelectedIds.size} produtos selecionados serão adicionados ao Rascunho escolhido.</div>
    <label>Cotação em Rascunho</label>
    <select id="ufi-add-existing-target">
      ${drafts.map(q => `<option value="${q.id}">${escapeHtml(q.name)} (Nº ${formatQuotationNumber(q.id)})</option>`).join('')}
    </select>
    <div class="btn-row" style="margin-top:16px; justify-content:space-between">
      <button class="secondary" onclick="closeModal2()">Cancelar</button>
      <button id="ufi-add-existing-confirm-btn" onclick="confirmAddUnfulfilledToExisting()">Adicionar</button>
    </div>
  `);
}

// Não existe endpoint de lote pra isso — reaproveita POST /quotations/{id}/items um
// item de cada vez, mesmo caminho já usado pelo painel de dentro da cotação
// (confirmAddToExisting, em quotation-detail-core.js).
async function confirmAddUnfulfilledToExisting() {
  const targetId = document.getElementById('ufi-add-existing-target').value;
  const btn = document.getElementById('ufi-add-existing-confirm-btn');
  const items = ufiCache.filter(i => ufiSelectedIds.has(i.quotationItemId));

  let added = 0;
  let failed = 0;
  await withButtonLoading(btn, 'Adicionando...', async () => {
    for (const item of items) {
      try {
        await api('POST', `/quotations/${targetId}/items`, { productId: item.productId, quantity: item.quantity });
        added++;
      } catch (e) {
        failed++;
      }
    }
  });

  if (failed) {
    toast(`${added} adicionado${added !== 1 ? 's' : ''}, ${failed} falhou/falharam (já deve existir nesse rascunho).`, true);
  } else {
    toast(`${added} produto${added !== 1 ? 's' : ''} adicionado${added !== 1 ? 's' : ''} à cotação escolhida.`);
  }
  closeModal2();
  loadUnfulfilledItems();
}
