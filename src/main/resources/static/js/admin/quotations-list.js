// ============================================================
// LISTA DE COTAÇÕES
// ============================================================
// ============================================================
// QUOTATIONS
// ============================================================
let quotationsCache = [];
let currentStatusFilter = 'DRAFT';
let quotationsPage = 0;
let quotationsTotalPages = 1;
const QUOTATIONS_PAGE_SIZE = 15;

const STATUS_LABELS = { DRAFT: 'Rascunho', AVAILABLE: 'Publicada', REVIEWING: 'Em Revisão', CLOSED: 'Concluída', EXPIRED: 'Expirada' };
const STATUS_LABELS_PLURAL = { DRAFT: 'Rascunhos', AVAILABLE: 'Publicadas', REVIEWING: 'Em Revisão', CLOSED: 'Concluídas', EXPIRED: 'Expiradas', AWAITING_CLOSE: 'Aguardando Fechamento' };
const STATUS_CLASSES = { DRAFT: 'draft', AVAILABLE: 'available', REVIEWING: 'reviewing', CLOSED: 'closed', EXPIRED: 'expired' };

// Recebe a cotação inteira (não só o status) porque EXPIRED precisa saber se já tem
// lance registrado — uma cotação expirada SEM ninguém ter respondido é "Expirada" de
// verdade (vermelho, nada a fazer); uma que expirou COM lances pendentes ainda dá pra
// fechar e calcular vencedores, então usa o mesmo tom âmbar de "Em Revisão" em vez do
// vermelho, que sugeria erro/falha onde na verdade só falta um clique em "Fechar".
function statusBadge(q) {
  if (q.status === 'EXPIRED' && q.hasBids) {
    return `<span class="badge badge-reviewing">Aguardando fechamento</span>`;
  }
  return `<span class="badge badge-${STATUS_CLASSES[q.status] || 'draft'}">${STATUS_LABELS[q.status] || q.status}</span>`;
}

let quotationFillRates = new Map();

// "Aguardando Fechamento" e "Expiradas" não são status de verdade no banco pra fins de
// filtro — as duas são QuotationStatus.EXPIRED por baixo, separadas por hasBids (ver
// findExpiredWithGroupPaged no backend). O front só precisa mandar o nome certo da aba;
// quem decide qual metade de EXPIRED buscar é o backend.
async function loadQuotations() {
  const [result, fillRates] = await Promise.all([
    safeCall(() => api('GET', `/quotations/by-status?status=${currentStatusFilter}&page=${quotationsPage}&size=${QUOTATIONS_PAGE_SIZE}`)),
    safeCall(() => api('GET', '/quotations/representative-fill-rate'))
  ]);
  quotationsCache = result.content;
  quotationsPage = result.page;
  quotationsTotalPages = result.totalPages;
  quotationFillRates = new Map(fillRates.map(f => [f.quotationId, f]));
  renderQuotationsList();
  updateQuotationsPaginationControls(result.totalElements);
}

// Vermelho (0%) → laranja (50%) → verde (100%), interpolado em RGB — não dá pra fazer
// isso com var(--cor) direto (são strings, não números pra interpolar), por isso as
// cores fixas aqui em vez de reaproveitar as variáveis de tema.
function fillRateColor(pct) {
  const red = [192, 54, 44], orange = [217, 119, 6], green = [22, 129, 79];
  const [a, b, t] = pct <= 50 ? [red, orange, pct / 50] : [orange, green, (pct - 50) / 50];
  const mix = (i) => Math.round(a[i] + (b[i] - a[i]) * t);
  return `rgb(${mix(0)},${mix(1)},${mix(2)})`;
}

// HTML da barra pra uma cotação específica, ou '' se não tiver dado de preenchimento
// (sem grupo definido, ou grupo sem nenhum representante elegível). Reaproveitada na
// lista de Cotações e no card "Expirando nos próximos 3 dias" do Dashboard.
function fillRateBarHtml(quotationId) {
  const fr = quotationFillRates.get(quotationId);
  if (!fr || !fr.eligibleCount) return '';
  const pct = Math.round((fr.filledCount / fr.eligibleCount) * 100);
  return `<div style="padding:0 4px 10px; text-align:center">
    <div style="font-size:11.5px; color:var(--text-dim); margin-bottom:5px">${fr.filledCount}/${fr.eligibleCount} representantes — ${pct}%</div>
    <div class="fill-rate-track" style="max-width:240px; margin:0 auto"><div class="fill-rate-fill" style="width:${pct}%; background:${fillRateColor(pct)}"></div></div>
  </div>`;
}

// A página já vem filtrada e paginada pelo backend (ver loadQuotations) — aqui é só
// desenhar o que já chegou, sem filtrar de novo no front.
function renderQuotationsList() {
  const tbody = document.getElementById('quotations-tbody');
  tbody.innerHTML = '';
  document.getElementById('quotations-empty').style.display = quotationsCache.length ? 'none' : 'block';
  quotationsCache.forEach(q => {
    const tr = document.createElement('tr');
    const deleteBtn = q.status === 'DRAFT'
      ? `<button class="danger small" onclick="deleteQuotationFromList(${q.id}, this)">Excluir</button>`
      : '';
    tr.innerHTML = `<td>${formatQuotationNumber(q.id)}</td><td>${q.name}</td><td>${statusBadge(q)}</td>
      <td>${q.supplierGroupName || '—'}</td><td>${fmtDate(q.publishedAt)}</td><td>${fmtDate(q.expirationDate)}</td>
      <td class="btn-row"><button class="secondary small" onclick="abrirDetalheCotacao(${q.id})">Ver Detalhe</button>${deleteBtn}</td>`;
    tbody.appendChild(tr);

    if (q.status === 'AVAILABLE') {
      const bar = fillRateBarHtml(q.id);
      if (bar) {
        const barTr = document.createElement('tr');
        barTr.innerHTML = `<td colspan="7" style="padding-top:0; padding-bottom:0">${bar}</td>`;
        tbody.appendChild(barTr);
      }
    }
  });
}

function goToQuotationsPage(page) {
  quotationsPage = page;
  loadQuotations();
}

function updateQuotationsPaginationControls(totalItems) {
  const wrap = document.getElementById('quotations-pagination');
  wrap.style.display = quotationsTotalPages > 1 ? 'flex' : 'none';
  document.getElementById('quotations-page-info').textContent = `Página ${quotationsPage + 1} de ${quotationsTotalPages} (${totalItems} no total)`;
  const prevBtn = document.getElementById('quotations-prev-btn');
  const nextBtn = document.getElementById('quotations-next-btn');
  prevBtn.disabled = quotationsPage === 0;
  nextBtn.disabled = quotationsPage >= quotationsTotalPages - 1;
  prevBtn.onclick = () => goToQuotationsPage(quotationsPage - 1);
  nextBtn.onclick = () => goToQuotationsPage(quotationsPage + 1);
}
