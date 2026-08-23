// ============================================================
// DASHBOARD (VISÃO GERAL)
// ============================================================

// Visão Geral redesenhada com base em como dashboard de procurement/RFQ costuma ser
// estruturado no mercado (pesquisei referências antes de mexer): poucos KPIs (5-8),
// cada um ligado a uma ação de verdade — não só contagem de cadastro — com o mais
// urgente em destaque acima da dobra, e números "de arquivo" (produtos/representantes/
// fornecedores cadastrados) rebaixados pro rodapé, já que não pedem nenhuma decisão.
// Tudo calculado em cima de endpoints que já existiam — nenhum backend novo.
async function loadDashboard() {
  const [quotations, products, reps, suppliers, groups, fillRates, reorderPoints] = await Promise.all([
    safeCall(() => api('GET', '/quotations')),
    safeCall(() => api('GET', '/products')),
    safeCall(() => api('GET', '/representatives')),
    safeCall(() => api('GET', '/suppliers')),
    safeCall(() => api('GET', '/supplier-groups')),
    safeCall(() => api('GET', '/quotations/representative-fill-rate')),
    safeCall(() => api('GET', '/quotations/reorder-points'))
  ]);

  quotationsCache = quotations;
  quotationFillRates = new Map(fillRates.map(f => [f.quotationId, f]));
  dashboardReorderPoints = reorderPoints;

  const now = new Date();
  const days30Ago = new Date(now.getTime() - 30 * 24 * 60 * 60 * 1000);

  const availableCount = quotations.filter(q => q.status === 'AVAILABLE').length;
  const closed30d = quotations.filter(q => q.status === 'CLOSED' && q.updatedAt && new Date(q.updatedAt) >= days30Ago).length;
  const expired30d = quotations.filter(q => q.status === 'EXPIRED' && q.expirationDate && new Date(q.expirationDate) >= days30Ago).length;
  // "Parado" = Rascunho criado há mais de 3 dias e ainda não publicado — cotação criada
  // ontem não é problema nenhum, é só trabalho em andamento.
  const staleDrafts = quotations.filter(q => q.status === 'DRAFT' && q.createdAt
    && (now - new Date(q.createdAt)) > 3 * 24 * 60 * 60 * 1000).length;
  const urgentReorderCount = reorderPoints.filter(r => r.daysUntilReorder <= 7).length;

  // Taxa média só entre cotações que de fato têm gente elegível pra responder — sem
  // isso, cotação sem grupo/sem representante puxaria a média pra baixo à toa.
  const ratesWithEligible = fillRates.filter(f => f.eligibleCount > 0);
  const avgResponseRate = ratesWithEligible.length
    ? Math.round(ratesWithEligible.reduce((sum, f) => sum + (f.filledCount / f.eligibleCount), 0) / ratesWithEligible.length * 100)
    : null;

  document.getElementById('kpi-available').textContent = availableCount;
  document.getElementById('kpi-response-rate').textContent = avgResponseRate === null ? '—' : avgResponseRate + '%';
  document.getElementById('kpi-closed-30d').textContent = closed30d;
  document.getElementById('kpi-stale-drafts').textContent = staleDrafts;
  document.getElementById('kpi-reorder-urgent').textContent = urgentReorderCount;
  document.getElementById('kpi-expired-30d').textContent = expired30d;
  document.getElementById('kpi-products').textContent = products.length;
  document.getElementById('kpi-reps').textContent = reps.length;
  document.getElementById('kpi-suppliers').textContent = suppliers.length;
  document.getElementById('kpi-groups').textContent = groups.length;

  // Cor só entra quando o número indica algo que precisa de atenção — zero fica neutro
  // de propósito, senão a cor perde o sentido de alerta (fica tudo colorido sempre).
  setKpiSeverity('kpi-stale-drafts', staleDrafts, 'warn');
  setKpiSeverity('kpi-reorder-urgent', urgentReorderCount, 'danger');
  setKpiSeverity('kpi-expired-30d', expired30d, 'warn');

  renderRecentQuotations(quotations);
  renderExpiringSoon(quotations);
  renderDashboardReorderPanel(reorderPoints);
}

function setKpiSeverity(elementId, value, severityClass) {
  const el = document.getElementById(elementId);
  el.classList.remove('warn', 'danger');
  if (value > 0) el.classList.add(severityClass);
}

let dashboardReorderPoints = [];

// Espelha o mesmo painel da tela "Ponto de Compra" (mesmos selos de urgência), só que
// mostrando os mais urgentes primeiro e limitando a 4 — é um resumo pra puxar atenção,
// não a lista inteira (isso já tem tela própria pra quando o admin quiser).
function renderDashboardReorderPanel(reorderPoints) {
  const wrap = document.getElementById('dash-reorder');
  const urgent = reorderPoints.filter(r => r.daysUntilReorder <= 7);

  if (!urgent.length) {
    wrap.innerHTML = '<div class="empty">Nenhum produto com ponto de compra próximo.</div>';
    return;
  }

  const visible = urgent.slice(0, 4);
  // Sem onclick/cursor de link aqui de propósito: quotationId nesse relatório é a
  // cotação FECHADA que originou esse ponto de compra, não uma cotação em aberto —
  // clicar levava pra um detalhe antigo e sem ação nenhuma disponível, sem relação
  // com "o que fazer agora". A única ação daqui é "Ver mais", que já leva pra tela de
  // Ponto de Compra de verdade. Mostra só produto + urgência — sem fornecedor nem
  // quantidade, esse resumo do dashboard é só pra sinalizar "o quê" e "quando",
  // detalhe fica pra tela de Ponto de Compra em si.
  let html = visible.map(r => `
    <div class="expiring-item">
      <div class="expiring-row not-clickable">
        <div class="expiring-row-main">
          <div class="expiring-row-name" title="${escapeHtml(r.productName)}">${escapeHtml(r.productName)}</div>
        </div>
        <div class="expiring-row-date">${reorderUrgencyBadge(r.daysUntilReorder)}</div>
      </div>
    </div>`).join('');

  const remaining = urgent.length - visible.length;
  if (remaining > 0) {
    html += `<button class="secondary small" style="margin-top:12px; width:100%" onclick="goToSection('reorder-points')">Ver mais (${remaining})</button>`;
  }

  wrap.innerHTML = html;
}

function renderRecentQuotations(quotations) {
  const recent = [...quotations]
    .sort((a, b) => new Date(b.createdAt) - new Date(a.createdAt))
    .slice(0, 6);

  const tbody = document.getElementById('dash-recent-tbody');
  tbody.innerHTML = recent.length
    ? recent.map(q => `<tr style="cursor:pointer" onclick="abrirDetalheCotacao(${q.id})">
        <td>${q.id}</td><td>${escapeHtml(q.name)}</td><td>${statusBadge(q)}</td></tr>`).join('')
    : '<tr><td colspan="3" class="empty">Nenhuma cotação criada ainda.</td></tr>';
}

// O modal de detalhe funciona a partir de qualquer tela, então "Ver Detalhe" no
// Dashboard chama abrirDetalheCotacao() direto — sem precisar navegar até Cotações antes.
function countdownLabel(iso) {
  const diffMs = new Date(iso) - new Date();
  if (diffMs <= 0) return 'expirando agora';
  const hours = Math.floor(diffMs / (1000 * 60 * 60));
  if (hours < 1) return 'menos de 1h restante';
  if (hours < 24) return `${hours}h restantes`;
  const days = Math.floor(hours / 24);
  return `${days}d restantes`;
}

function renderExpiringSoon(quotations) {
  const now = new Date();
  const limit = new Date(now.getTime() + 3 * 24 * 60 * 60 * 1000);

  const expiring = quotations
    .filter(q => q.status === 'AVAILABLE' && q.expirationDate
      && new Date(q.expirationDate) <= limit && new Date(q.expirationDate) > now)
    .sort((a, b) => new Date(a.expirationDate) - new Date(b.expirationDate));

  const wrap = document.getElementById('dash-expiring');
  if (!expiring.length) {
    wrap.innerHTML = '<div class="empty">Nada expirando nos próximos 3 dias.</div>';
    return;
  }

  const visible = expiring.slice(0, 4);
  let html = visible.map(q => `
    <div class="expiring-item">
      <div class="expiring-row" onclick="abrirDetalheCotacao(${q.id})">
        <div class="expiring-row-main">
          <div class="expiring-row-name" title="${escapeHtml(q.name)}">${escapeHtml(q.name)}</div>
          <div class="expiring-row-group" title="${escapeHtml(q.supplierGroupName || 'sem grupo')}">${escapeHtml(q.supplierGroupName || 'sem grupo')}</div>
        </div>
        <div class="expiring-row-date">
          <span class="expiring-row-countdown">${countdownLabel(q.expirationDate)}</span>
          <span class="expiring-row-exact">${fmtDate(q.expirationDate)}</span>
        </div>
      </div>
      ${fillRateBarHtml(q.id)}
    </div>`).join('');

  const remaining = expiring.length - visible.length;
  if (remaining > 0) {
    html += `<button class="secondary small" style="margin-top:12px; width:100%" onclick="goToQuotationsFiltered('AVAILABLE')">Ver mais (${remaining})</button>`;
  }

  wrap.innerHTML = html;
}

