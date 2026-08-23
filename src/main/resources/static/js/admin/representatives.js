// ============================================================
// REPRESENTANTES
// ============================================================
// ============================================================
// REPRESENTATIVES
// ============================================================
let repsCache = [];
let repsInactiveCache = [];
let currentRepListFilter = 'active';
let currentRepSearchTerm = '';

async function loadRepresentatives() {
  currentRepSearchTerm = '';
  document.getElementById('rep-search').value = '';
  repsCache = await safeCall(() => api('GET', '/representatives'));
  renderRepsList();
}

function onRepSearchInput() {
  currentRepSearchTerm = document.getElementById('rep-search').value.trim().toLowerCase();
  repsPage = 0;
  renderRepsList();
}

document.querySelectorAll('#rep-status-tabs .tab').forEach(tab => {
  tab.addEventListener('click', async () => {
    document.querySelectorAll('#rep-status-tabs .tab').forEach(t => t.classList.remove('active'));
    tab.classList.add('active');
    currentRepListFilter = tab.dataset.status;
    document.getElementById('rep-list-title').textContent = currentRepListFilter === 'active'
      ? 'Representantes ativos' : 'Representantes inativos';
    if (currentRepListFilter === 'inactive' && !repsInactiveCache.length) {
      repsInactiveCache = await safeCall(() => api('GET', '/representatives/inactive'));
    }
    repsPage = 0;
    renderRepsList();
  });
});

let repsPage = 0;

function renderRepsList() {
  const source = currentRepListFilter === 'active' ? repsCache : repsInactiveCache;
  const list = currentRepSearchTerm
    ? source.filter(r => r.name.toLowerCase().includes(currentRepSearchTerm) || (r.phone || '').includes(currentRepSearchTerm))
    : source;

  const { items, page, totalPages } = paginateSlice(list, repsPage, DEFAULT_PAGE_SIZE);
  repsPage = page;

  const tbody = document.getElementById('reps-tbody');
  tbody.innerHTML = '';
  document.getElementById('reps-empty').style.display = list.length ? 'none' : 'block';
  items.forEach(r => {
    const tr = document.createElement('tr');
    const actions = currentRepListFilter === 'active'
      ? `<button class="secondary small" onclick="openRepModal(${r.id})">Editar</button>
         <button class="secondary small" onclick="openRepAccessModal(${r.id})">Acesso</button>
         <button class="danger small" onclick="deactivateRep(${r.id}, this)">Desativar</button>
         <button class="danger small" onclick="hardDeleteRep(${r.id}, this)">Excluir</button>`
      : `<button class="success small" onclick="reactivateRepModal(${r.id})">Reativar</button>
         <button class="danger small" onclick="hardDeleteRep(${r.id}, this)">Excluir</button>`;
    tr.innerHTML = `<td>${r.id}</td><td>${r.name}</td><td>${r.phone}</td><td>${r.email}</td>
      <td><div class="row-actions">${actions}</div></td>`;
    tbody.appendChild(tr);
  });

  updatePaginationControls('reps', page, totalPages, list.length, (newPage) => { repsPage = newPage; renderRepsList(); });
}

function findRepById(id) {
  return repsCache.find(r => r.id === id) || repsInactiveCache.find(r => r.id === id);
}

// Cadastro do representante (nome, telefone, e-mail) e o login dele (email/senha) são coisas
// separadas de propósito — nem todo representante precisa logar direto no sistema. Esse
// modal só cuida do login: criar do zero, resetar senha, ou ativar/desativar o acesso.
async function openRepAccessModal(id) {
  const rep = findRepById(id);
  openModal2(`<h2>Acesso de "${escapeHtml(rep.name)}"</h2><div id="rep-access-body">Carregando...</div>`);
  await refreshRepAccessModal(id);
}

async function refreshRepAccessModal(id) {
  const status = await safeCall(() => api('GET', `/representatives/${id}/access`));
  const body = document.getElementById('rep-access-body');
  if (!body) return;

  if (!status.hasAccess) {
    body.innerHTML = `
      <div class="subtitle" style="margin-bottom:16px">Esse representante ainda não tem login criado — ele não consegue entrar no sistema até isso ser feito.</div>
      <div class="field-grid">
        <div><label>E-mail</label><input type="email" id="rep-access-email" placeholder="representante@empresa.com"></div>
        <div><label>Senha</label><input type="password" id="rep-access-password" placeholder="Mínimo 6 caracteres"></div>
      </div>
      <div class="btn-row" style="margin-top:16px">
        <button onclick="createRepAccess(${id})">Criar acesso</button>
        <button class="secondary" onclick="closeModal2()">Fechar</button>
      </div>`;
    return;
  }

  const statusBadge = status.enabled
    ? '<span class="badge badge-available">Ativo</span>'
    : '<span class="badge badge-expired">Desativado</span>';

  body.innerHTML = `
    <div class="subtitle" style="margin-bottom:6px">E-mail de login</div>
    <div style="font-weight:700; margin-bottom:16px">${escapeHtml(status.email)} ${statusBadge}</div>

    <hr class="divider">
    <h3 style="margin-top:14px">Redefinir senha</h3>
    <div class="inline-form">
      <div style="flex:1"><input type="password" id="rep-access-new-password" placeholder="Nova senha (mínimo 6 caracteres)"></div>
      <button onclick="resetRepAccessPassword(${id})">Redefinir</button>
    </div>

    <div class="btn-row" style="margin-top:20px">
      ${status.enabled
        ? `<button class="danger" onclick="setRepAccessEnabled(${id}, false)">Desativar acesso</button>`
        : `<button class="success" onclick="setRepAccessEnabled(${id}, true)">Reativar acesso</button>`}
      <button class="secondary" onclick="closeModal2()">Fechar</button>
    </div>`;
}

async function createRepAccess(id) {
  const email = document.getElementById('rep-access-email').value.trim();
  const password = document.getElementById('rep-access-password').value;
  if (!email || !password) { toast('Preencha e-mail e senha.', true); return; }
  if (password.length < 6) { toast('Senha deve ter pelo menos 6 caracteres.', true); return; }

  await safeCall(() => api('POST', `/representatives/${id}/access`, { email, password }));
  toast('Acesso criado — já pode passar o e-mail e a senha pro representante.');
  await refreshRepAccessModal(id);
}

async function resetRepAccessPassword(id) {
  const newPassword = document.getElementById('rep-access-new-password').value;
  if (!newPassword || newPassword.length < 6) { toast('Senha deve ter pelo menos 6 caracteres.', true); return; }

  await safeCall(() => api('PUT', `/representatives/${id}/access/password?newPassword=${encodeURIComponent(newPassword)}`));
  toast('Senha redefinida.');
  await refreshRepAccessModal(id);
}

async function setRepAccessEnabled(id, enabled) {
  await safeCall(() => api('PUT', `/representatives/${id}/access/enabled?enabled=${enabled}`));
  toast(enabled ? 'Acesso reativado.' : 'Acesso desativado.');
  await refreshRepAccessModal(id);
}

function openRepModal(id) {
  const r = id ? findRepById(id) : null;
  openModal(`
    <h2>${r ? 'Editar representante #' + r.id : 'Novo representante'}</h2>
    <input type="hidden" id="modal-rep-id" value="${r ? r.id : ''}">
    <div class="field-grid">
      <div><label>Nome</label><input id="modal-rep-name" value="${r ? escapeHtml(r.name) : ''}" placeholder="Nome completo"></div>
      <div><label>Telefone</label><input id="modal-rep-phone" value="${r ? maskPhone(r.phone) : ''}" placeholder="(27) 99999-9999" oninput="this.value = maskPhone(this.value)"></div>
      <div><label>E-mail</label><input id="modal-rep-email" type="email" value="${r ? escapeHtml(r.email) : ''}" placeholder="nome@empresa.com"></div>
    </div>
    <div class="btn-row" style="margin-top:16px">
      <button onclick="submitRepModal(this)">Salvar</button>
      <button class="secondary" onclick="closeModal()">Cancelar</button>
    </div>
  `);
}

function submitRepModal(buttonEl) {
  const id = document.getElementById('modal-rep-id').value;
  const body = {
    name: document.getElementById('modal-rep-name').value.trim(),
    phone: unmaskDigits(document.getElementById('modal-rep-phone').value),
    email: document.getElementById('modal-rep-email').value.trim()
  };
  saveWithReactivation(
    () => api(id ? 'PUT' : 'POST', id ? `/representatives/${id}` : '/representatives', body),
    (existingId) => api('POST', `/representatives/${existingId}/reactivate`, body),
    { name: 'modal-rep-name', phone: 'modal-rep-phone', email: 'modal-rep-email' },
    buttonEl,
    () => {
      toast(id ? 'Representante atualizado.' : 'Representante salvo.');
      closeModal();
      refreshCurrentRepsView();
    }
  );
}

function reactivateRepModal(id) {
  const r = findRepById(id);
  if (!r) return;
  openModal(`
    <h2>Reativar representante #${r.id}</h2>
    <div class="subtitle">Confira os dados antes de reativar — eles são atualizados junto.</div>
    <input type="hidden" id="modal-rep-id" value="${r.id}">
    <div class="field-grid">
      <div><label>Nome</label><input id="modal-rep-name" value="${escapeHtml(r.name)}"></div>
      <div><label>Telefone</label><input id="modal-rep-phone" value="${maskPhone(r.phone)}" oninput="this.value = maskPhone(this.value)"></div>
      <div><label>E-mail</label><input id="modal-rep-email" type="email" value="${escapeHtml(r.email)}"></div>
    </div>
    <div class="btn-row" style="margin-top:16px">
      <button class="success" onclick="submitReactivateRep()">Reativar</button>
      <button class="secondary" onclick="closeModal()">Cancelar</button>
    </div>
  `);
}

async function submitReactivateRep() {
  const id = document.getElementById('modal-rep-id').value;
  const body = {
    name: document.getElementById('modal-rep-name').value.trim(),
    phone: unmaskDigits(document.getElementById('modal-rep-phone').value),
    email: document.getElementById('modal-rep-email').value.trim()
  };
  await safeCall(() => api('POST', `/representatives/${id}/reactivate`, body));
  toast('Representante reativado.');
  closeModal();
  refreshCurrentRepsView();
}

function deactivateRep(id, buttonEl) {
  const r = findRepById(id);
  showConfirmPopover(buttonEl, 'Desativar o representante ' + (r ? escapeHtml(r.name) : '#' + id) + '?', async () => {
    await safeCall(() => api('DELETE', `/representatives/${id}`));
    toast('Representante desativado.');
    refreshCurrentRepsView();
  });
}

// Diferente do "Desativar" — isso EXCLUI de verdade (representante + o login dele),
// sem volta. Só funciona quando o backend confirma que ele nunca participou de nada
// e não está vinculado a nenhum fornecedor no momento; senão recusa com mensagem clara.
function hardDeleteRep(id, buttonEl) {
  const r = findRepById(id);
  showConfirmPopover(buttonEl, 'Excluir DEFINITIVAMENTE o representante ' + (r ? escapeHtml(r.name) : '#' + id) + '? Essa ação não pode ser desfeita.', async () => {
    await safeCall(() => api('DELETE', `/representatives/${id}/permanent`));
    toast('Representante excluído.');
    refreshCurrentRepsView();
  });
}

async function refreshCurrentRepsView() {
  repsInactiveCache = [];
  if (currentRepListFilter === 'inactive') {
    repsInactiveCache = await safeCall(() => api('GET', '/representatives/inactive'));
  } else {
    repsCache = await safeCall(() => api('GET', '/representatives'));
  }
  renderRepsList();
}

async function loadRepresentativesForSelect() {
  if (!repsCache.length) repsCache = await safeCall(() => api('GET', '/representatives'));
  const sel = document.getElementById('supplier-representative');
  sel.innerHTML = '<option value="">— nenhum —</option>' +
    repsCache.map(r => `<option value="${r.id}">${r.name}</option>`).join('');
}

