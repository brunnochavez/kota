// ============================================================
// LISTA DE COTAÇÕES
// ============================================================
// ============================================================
// QUOTATIONS
// ============================================================
let quotationsCache = [];
let currentStatusFilter = 'DRAFT';

const STATUS_LABELS = { DRAFT: 'Rascunho', AVAILABLE: 'Disponível', REVIEWING: 'Em Revisão', CLOSED: 'Concluída', EXPIRED: 'Expirada' };
const STATUS_LABELS_PLURAL = { DRAFT: 'Rascunhos', AVAILABLE: 'Disponíveis', REVIEWING: 'Em Revisão', CLOSED: 'Concluídas', EXPIRED: 'Expiradas', AWAITING_CLOSE: 'Aguardando Fechamento' };
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

async function loadQuotations() {
  const [quotations, fillRates] = await Promise.all([
    safeCall(() => api('GET', '/quotations')),
    safeCall(() => api('GET', '/quotations/representative-fill-rate'))
  ]);
  quotationsCache = quotations;
  quotationFillRates = new Map(fillRates.map(f => [f.quotationId, f]));
  renderQuotationsList();
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

// "Aguardando Fechamento" não é um status de verdade no banco — é sempre EXPIRED com
// hasBids=true por baixo. Ganhou aba própria (em vez de ficar misturado com as
// "Expiradas" de verdade) porque as duas situações pedem ações completamente
// diferentes: uma cotação aqui ainda dá pra fechar e calcular vencedores; uma Expirada
// de verdade (sem nenhum lance) não tem mais o que fazer, só arquivar mentalmente.
function renderQuotationsList() {
  const filtered = quotationsCache.filter(q => {
    if (currentStatusFilter === 'AWAITING_CLOSE') return q.status === 'EXPIRED' && q.hasBids;
    if (currentStatusFilter === 'EXPIRED') return q.status === 'EXPIRED' && !q.hasBids;
    return q.status === currentStatusFilter;
  });
  const tbody = document.getElementById('quotations-tbody');
  tbody.innerHTML = '';
  document.getElementById('quotations-empty').style.display = filtered.length ? 'none' : 'block';
  filtered.forEach(q => {
    const tr = document.createElement('tr');
    const deleteBtn = q.status === 'DRAFT'
      ? `<button class="danger small" onclick="deleteQuotationFromList(${q.id}, this)">Excluir</button>`
      : '';
    tr.innerHTML = `<td>${q.id}</td><td>${q.name}</td><td>${statusBadge(q)}</td>
      <td>${q.supplierGroupName || '—'}</td><td>${fmtDate(q.expirationDate)}</td>
      <td class="btn-row"><button class="secondary small" onclick="abrirDetalheCotacao(${q.id})">Ver Detalhe</button>${deleteBtn}</td>`;
    tbody.appendChild(tr);

    if (q.status === 'AVAILABLE') {
      const bar = fillRateBarHtml(q.id);
      if (bar) {
        const barTr = document.createElement('tr');
        barTr.innerHTML = `<td colspan="6" style="padding-top:0; padding-bottom:0">${bar}</td>`;
        tbody.appendChild(barTr);
      }
    }
  });
}

