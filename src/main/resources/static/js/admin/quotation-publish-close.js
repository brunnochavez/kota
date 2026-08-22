// ============================================================
// PUBLICAR / FECHAR COTAÇÃO
// ============================================================
function showConfirmPopover(anchorEl, messageHtml, onConfirm) {
  closeConfirmPopover();

  const pop = document.createElement('div');
  pop.className = 'confirm-popover';
  pop.id = 'confirm-popover';
  pop.innerHTML = `
    <div class="confirm-popover-msg">${messageHtml}</div>
    <div class="btn-row" style="justify-content:flex-end; margin-top:10px">
      <button class="secondary small" type="button">Não</button>
      <button class="danger small" type="button">Sim</button>
    </div>`;
  document.body.appendChild(pop);

  const [noBtn, yesBtn] = pop.querySelectorAll('button');
  noBtn.onclick = closeConfirmPopover;
  yesBtn.onclick = () => { closeConfirmPopover(); onConfirm(); };

  // Tenta abrir logo abaixo do botão, alinhado pela direita; se estourar a borda da tela
  // (topo, base ou lateral), reposiciona pro lado que sobra espaço.
  const anchor = anchorEl.getBoundingClientRect();
  const popRect = pop.getBoundingClientRect();
  let top = anchor.bottom + 6;
  let left = anchor.right - popRect.width;
  if (top + popRect.height > window.innerHeight - 8) top = anchor.top - popRect.height - 6;
  if (left < 8) left = anchor.left;
  if (left + popRect.width > window.innerWidth - 8) left = window.innerWidth - popRect.width - 8;
  pop.style.top = Math.max(8, top) + 'px';
  pop.style.left = Math.max(8, left) + 'px';

  // setTimeout pra esse mesmo clique (o que abriu o popover) não ser lido como "clique fora"
  setTimeout(() => document.addEventListener('mousedown', handleOutsideConfirmPopoverClick), 0);
}

function handleOutsideConfirmPopoverClick(e) {
  const pop = document.getElementById('confirm-popover');
  if (pop && !pop.contains(e.target)) closeConfirmPopover();
}

function closeConfirmPopover() {
  const pop = document.getElementById('confirm-popover');
  if (pop) pop.remove();
  document.removeEventListener('mousedown', handleOutsideConfirmPopoverClick);
}

// Mensagem pronta pra colar no WhatsApp de cada fornecedor, com link direto pra essa
// cotação em representante.html (abre já na tela de lançamento, sem precisar procurar
// na lista — ver o parâmetro ?cotacao= tratado lá). Mesma lógica de cópia com fallback
// do representante.html: navigator.clipboard só funciona em contexto seguro (HTTPS ou
// localhost), então cai pra document.execCommand('copy') se não estiver disponível.
async function copyPublishMessage(buttonEl) {
  const [q, company] = await Promise.all([
    safeCall(() => api('GET', `/quotations/${currentQuotationId}`)),
    safeCall(() => api('GET', '/company-settings'))
  ]);
  // Domínio fixo de produção, de propósito — mesmo se o admin gerar a mensagem
  // testando local (localhost:8080), o link que vai pro representante precisa
  // sempre apontar pro site publicado, senão ninguém de fora consegue abrir.
  const link = `https://easykota.com.br/representante.html?cotacao=${currentQuotationId}`;
  const itemCount = qdCurrentItems.length;
  const message = `📋 *Nova cotação disponível — ${company.name || 'Empresa'}*\n\n`
      + `Cotação: *${q.name}* (Nº ${formatQuotationNumber(q.id)})\n`
      + `Grupo: ${q.supplierGroupName || '—'}\n`
      + `Itens: ${itemCount} ${itemCount === 1 ? 'produto' : 'produtos'}\n`
      + `Prazo para envio dos preços: *${fmtDate(q.expirationDate)}*\n\n`
      + `Acesse o link abaixo, informe seus preços e envie até o prazo — cotações enviadas depois do prazo não são consideradas.\n\n`
      + `🔗 ${link}\n\n`
      + `Qualquer dúvida, é só chamar!`;

  const onSuccess = () => {
    const original = buttonEl.textContent;
    buttonEl.textContent = 'Copiado!';
    toast('Mensagem copiada — já pode colar no WhatsApp.');
    setTimeout(() => { buttonEl.textContent = original; }, 1800);
  };
  const onFailure = () => toast('Não foi possível copiar a mensagem.', true);

  if (window.isSecureContext && navigator.clipboard && navigator.clipboard.writeText) {
    navigator.clipboard.writeText(message).then(onSuccess).catch(() => copyTextFallback(message, onSuccess, onFailure));
  } else {
    copyTextFallback(message, onSuccess, onFailure);
  }
}

function copyTextFallback(text, onSuccess, onFailure) {
  const el = document.createElement('textarea');
  el.value = text;
  el.style.position = 'fixed';
  el.style.left = '-9999px';
  document.body.appendChild(el);
  el.focus();
  el.select();
  try {
    document.execCommand('copy') ? onSuccess() : onFailure();
  } catch (e) {
    onFailure();
  }
  document.body.removeChild(el);
}

function removeQuotationItem(itemId, buttonEl) {
  const item = qdCurrentItems.find(it => it.id === itemId);
  const productName = item ? item.productName : 'este item';
  showConfirmPopover(
    buttonEl,
    `Excluir o produto <strong>${escapeHtml(productName)}</strong> desta cotação?`,
    () => confirmRemoveQuotationItem(itemId)
  );
}

async function confirmRemoveQuotationItem(itemId) {
  await safeCall(() => api('DELETE', `/quotations/${currentQuotationId}/items/${itemId}`));
  toast('Item removido.');
  await loadQuotationItemsDetail(currentQuotationId, 'DRAFT');
}

async function updateQuotation() {
  const fieldMap = { name: 'qd-name' };
  Object.values(fieldMap).forEach(clearFieldError);

  const body = {
    name: document.getElementById('qd-name').value.trim(),
    supplierGroupId: document.getElementById('qd-group').value || null,
    expirationDate: getExpirationValue('qd-expiration'),
    defaultSalesProjectionDays: document.getElementById('qd-sales-projection').value || null
  };
  try {
    await api('PUT', `/quotations/${currentQuotationId}`, body);
  } catch (e) {
    distributeFieldErrors(e.message, fieldMap);
    return;
  }
  toast('Cotação atualizada.');
  loadQuotations();
  abrirDetalheCotacao(currentQuotationId);
}

// Exclusão de verdade — só disponível pra cotação em Rascunho (backend também valida
// isso, essa checagem no frontend é só pra não nem mostrar o botão fora desse status).
// As duas variantes (lista e modal) convergem pro mesmo core, só muda de onde é chamado
// e o que precisa atualizar depois.
function deleteQuotation(id, buttonEl, onDeleted) {
  const q = quotationsCache.find(x => x.id === id);
  showConfirmPopover(buttonEl, 'Excluir DEFINITIVAMENTE a cotação ' + (q ? '"' + escapeHtml(q.name) + '"' : '#' + id) + '? Essa ação não pode ser desfeita.', async () => {
    await safeCall(() => api('DELETE', `/quotations/${id}`));
    toast('Cotação excluída.');
    onDeleted();
  });
}

function deleteQuotationFromList(id, buttonEl) {
  deleteQuotation(id, buttonEl, () => loadQuotations());
}

function deleteQuotationFromModal(buttonEl) {
  deleteQuotation(currentQuotationId, buttonEl, () => { closeModal(); loadQuotations(); });
}

async function publishQuotation() {
  await safeCall(() => api('POST', `/quotations/${currentQuotationId}/publish`));
  toast('Cotação publicada.');
  loadQuotations();
  abrirDetalheCotacao(currentQuotationId);
}

// Clona nome/grupo/projeção padrão/itens (produto + quantidade) de uma cotação expirada
// pra uma nova em Rascunho — sem prazo de expiração definido, sem itens sujos de lance
// vencedor/corte de estoque, sem nada da vida anterior. O admin só ajusta o prazo e
// publica de novo, sem redigitar produto por produto.
async function duplicateQuotation() {
  const q = await safeCall(() => api('POST', `/quotations/${currentQuotationId}/duplicate`));
  toast('Cotação #' + q.id + ' criada em Rascunho, a partir desta.');
  closeModal();
  loadQuotations();
  abrirDetalheCotacao(q.id);
}

// Mesma ideia, mas só pros itens que fecharam sem vencedor — pra tentar de novo com
// outros fornecedores/representantes sem carregar junto o que já foi bem atendido.
async function duplicateUnquotedItems() {
  const q = await safeCall(() => api('POST', `/quotations/${currentQuotationId}/duplicate-unquoted-items`));
  toast('Cotação #' + q.id + ' criada em Rascunho, só com os itens sem lance.');
  closeModal();
  loadQuotations();
  abrirDetalheCotacao(q.id);
}

// Passa por confirmação extra SÓ quando é o clique original no botão "Fechar" (buttonEl
// vem preenchido, veio do onclick="closeQuotation(this)" no HTML) E a cotação ainda está
// dentro do prazo — fechar antes da hora corta fornecedores que ainda não responderam,
// então vale um "tem certeza?" antes de seguir. As reexecuções internas do próprio fluxo
// de fechamento (reenviar com desempate, excluir fornecedor, aceitar violação) chamam
// closeQuotation() sem argumento nenhum — aí cai direto, sem popover de novo, porque o
// admin já confirmou a intenção de fechar na primeira vez.
function closeQuotation(buttonEl) {
  const expirationRaw = getExpirationValue('qd-expiration');
  const stillWithinDeadline = expirationRaw && new Date(expirationRaw) > new Date();

  if (buttonEl && stillWithinDeadline) {
    showConfirmPopover(
      buttonEl,
      'Essa cotação ainda está dentro do prazo — fornecedores podem ainda não ter enviado seus preços. Fechar mesmo assim?',
      doCloseQuotation
    );
    return;
  }

  doCloseQuotation();
}

async function doCloseQuotation() {
  const result = await safeCall(() => api('POST', `/quotations/${currentQuotationId}/close`, closeState));
  renderCloseFlow(result);
}

async function confirmCloseQuotation(anchorEl) {
  showConfirmPopover(
    anchorEl,
    'Confirmar o fechamento definitivo? Depois disso não dá mais pra editar os itens, e o PDF do resultado fica disponível.',
    attemptConfirmClose
  );
}

async function attemptConfirmClose() {
  const result = await safeCall(() => api('POST', `/quotations/${currentQuotationId}/confirm-close`, {
    acceptedViolationSupplierIds: closeState.acceptedViolationSupplierIds
  }));

  if (!result.closed) {
    renderConfirmCloseViolations(result.pendingViolations);
    return;
  }

  toast('Cotação fechada definitivamente.');
  closeState = { tieBreakWinners: {}, excludedSupplierIds: [], acceptedViolationSupplierIds: [] };
  loadQuotations();
  abrirDetalheCotacao(currentQuotationId);
}

// Revalidação de pedido mínimo no momento de confirmar — pode acontecer se um ajuste em
// "Revisar Cotações Enviadas" (depois do close() já calculado) derrubou algum fornecedor
// pra baixo do mínimo de novo. Sem "Excluir fornecedor" aqui — os vencedores já estão
// definidos; pra reatribuir um item pra outro representante, é pelo "Adicionar produto a
// esse representante" na revisão, não por aqui.
function renderConfirmCloseViolations(violations) {
  let html = '<div class="card" style="margin-top:16px"><h2>Fornecedor abaixo do pedido mínimo!</h2>';
  html += '<div class="subtitle" style="margin-bottom:10px">Isso mudou depois do cálculo original — provavelmente um ajuste em "Revisar Cotações Enviadas". Ajuste de novo, ou aceite mesmo assim pra confirmar o fechamento.</div>';
  violations.forEach(v => {
    const itemsHtml = v.wonItems.map(i => {
      const alt = i.secondBestSupplierName
        ? `o segundo menor preço será da ${escapeHtml(i.secondBestSupplierName)} — R$ ${formatCurrencyFromNumber(i.secondBestValue)}`
        : 'sem outra oferta pra esse item';
      return `<div>${escapeHtml(i.productName)} - R$ ${formatCurrencyFromNumber(i.winningValue)} - Quantidade: ${i.quantity}
        <span style="color:var(--danger)">→ se reatribuir: ${alt}</span></div>`;
    }).join('');
    html += `<div class="violation-box"><h4>${v.supplierName}</h4>
      <div>Total ganho: R$ ${formatCurrencyFromNumber(v.total)} — Pedido mínimo: R$ ${formatCurrencyFromNumber(v.minimumOrderValue)}</div>
      <div style="margin:8px 0; font-size:12.5px; color:var(--text-dim); display:flex; flex-direction:column; gap:2px">${itemsHtml}</div>
      <div class="btn-row">
        <button onclick="reviewViolatedSupplier(${v.supplierId})">Revisar esta cotação</button>
        <button class="secondary small" onclick="acceptConfirmCloseViolation(${v.supplierId})">Aceitar mesmo assim</button>
      </div>
    </div>`;
  });
  html += '</div>';
  document.getElementById('close-flow').innerHTML = html;
}

// Vai direto pro representante desse fornecedor dentro de "Revisar Lances Enviados" —
// evita o admin ter que fechar esse card e procurar o botão "Revisar Cotações Enviadas"
// em outro canto da tela pra corrigir a mesma violação que acabou de ver aqui.
async function reviewViolatedSupplier(supplierId) {
  const suppliers = await safeCall(() => api('GET', '/suppliers'));
  const supplier = suppliers.find(s => s.id === supplierId);
  if (!supplier || !supplier.representativeId) {
    toast('Não encontrei o representante desse fornecedor.', true);
    return;
  }
  await openReviewBidsModal();
  const select = document.getElementById('review-bids-rep-select');
  if (select) {
    select.value = supplier.representativeId;
    onReviewRepChange();
  }
}

function acceptConfirmCloseViolation(id) {
  if (!closeState.acceptedViolationSupplierIds.includes(id)) closeState.acceptedViolationSupplierIds.push(id);
  attemptConfirmClose();
}

function renderCloseFlow(result) {
  const box = document.getElementById('close-flow');

  if (result.closed) {
    box.innerHTML = `<div class="card" style="border-color:var(--success-border); margin-top:16px">
      <h2 style="color:var(--success)">Vencedores calculados — revise antes de confirmar.</h2>
      <div class="subtitle">Confira os itens abaixo. Pra ajustar quantidade, preço ou incluir/excluir um item de algum representante, use "Revisar Cotações Enviadas" — o ajuste é individual e não derruba o cálculo dos demais. Quando estiver certo, clique em "Confirmar Fechamento" pra gerar o PDF.</div>
    </div>`;
    loadQuotations();
    abrirDetalheCotacao(currentQuotationId);
    return;
  }

  let html = '';

  if (result.pendingTieBreaks && result.pendingTieBreaks.length) {
    html += '<div class="card" style="margin-top:16px"><h2>Empates a resolver</h2>';
    result.pendingTieBreaks.forEach(tie => {
      html += `<div class="tie-box"><h4>${tie.productName}</h4>`;
      tie.tiedBids.forEach(b => {
        html += `<label style="display:flex;align-items:center;gap:8px;margin-bottom:6px;cursor:pointer">
          <input type="radio" name="tie-${tie.quotationItemId}" style="width:auto" onclick="setTieWinner(${tie.quotationItemId}, ${b.id})">
          ${b.supplierName} — R$ ${formatCurrencyFromNumber(b.value)}
        </label>`;
      });
      html += '</div>';
    });
    html += '<button onclick="closeQuotation()">Reenviar com desempates escolhidos</button></div>';
  }

  if (result.pendingViolations && result.pendingViolations.length) {
    html += '<div class="card" style="margin-top:16px"><h2>Fornecedor abaixo do pedido mínimo!</h2>';
    result.pendingViolations.forEach(v => {
      const itemsHtml = v.wonItems.map(i => {
        const alt = i.secondBestSupplierName
          ? `o segundo menor preço será da ${escapeHtml(i.secondBestSupplierName)} — R$ ${formatCurrencyFromNumber(i.secondBestValue)}`
          : 'sem outra oferta pra esse item';
        return `<div>${escapeHtml(i.productName)} - R$ ${formatCurrencyFromNumber(i.winningValue)} - Quantidade: ${i.quantity}
          <span style="color:var(--danger)">→ se excluir: ${alt}</span></div>`;
      }).join('');
      html += `<div class="violation-box"><h4>${v.supplierName}</h4>
        <div>Total ganho: R$ ${formatCurrencyFromNumber(v.total)} — Pedido mínimo: R$ ${formatCurrencyFromNumber(v.minimumOrderValue)}</div>
        <div style="margin:8px 0; font-size:12.5px; color:var(--text-dim); display:flex; flex-direction:column; gap:2px">${itemsHtml}</div>
        <div class="btn-row">
          <button class="danger small" onclick="excludeSupplier(${v.supplierId})">Excluir fornecedor</button>
          <button class="secondary small" onclick="acceptViolation(${v.supplierId})">Aceitar mesmo assim</button>
        </div>
      </div>`;
    });
    html += '</div>';
  }

  box.innerHTML = html;
}

function setTieWinner(itemId, bidId) {
  closeState.tieBreakWinners[itemId] = bidId;
}
function excludeSupplier(id) {
  if (!closeState.excludedSupplierIds.includes(id)) closeState.excludedSupplierIds.push(id);
  closeQuotation();
}
function acceptViolation(id) {
  if (!closeState.acceptedViolationSupplierIds.includes(id)) closeState.acceptedViolationSupplierIds.push(id);
  closeQuotation();
}

